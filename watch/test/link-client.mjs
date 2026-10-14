// Independent phone-side implementation used by the watch link tests.
//
// This file deliberately does NOT import anything from ../src/lib/link: it rebuilds the wire
// format and the session derivation from Node's own crypto, so the tests check the protocol
// rather than checking the implementation against itself. If the watch code and this file ever
// disagree, the tests fail — which is exactly the point.

import { createHash, createHmac, createCipheriv, createDecipheriv } from 'node:crypto'
import { crc32 } from 'node:zlib'
import { createLinkServerCore } from '../src/lib/link/server-core.mjs'

// These are an intentional duplicate of the contract, not an import of the watch implementation:
// if the production UUIDs ever drift from what this independent client expects, the suite fails.
// The service and characteristic pair is the one Android already has cached for this watch, and
// that decision is documented in watch/src/lib/link/frames.mjs.
export const SERVICE = 'fa954400-48d6-4a12-b74b-4c5b5170360e'
export const CHARACTERISTIC = 'fa954401-48d6-4a12-b74b-4c5b5170360e'
export const OP = { BEGIN: 1, DATA: 2, END: 3, POLL: 4, RELEASE: 5, RESET: 6 }
export const KIND = { HELLO: 1, BODY: 2, ENROLL: 3 }
export const STATE = { OPEN: 1, READY: 2, RECEIVING: 3, SAVED: 4, FAILED: 5, REJECTED: 6, BUSY: 7, REPLY: 8 }
export const REQUEST_FLAG = 0x8000

export const u32 = value => { const b = Buffer.alloc(4); b.writeUInt32BE(value >>> 0); return b }
export const u64 = value => { const b = Buffer.alloc(8); b.writeBigUInt64BE(BigInt(value)); return b }
export const mac = (key, data) => createHmac('sha256', key).update(data).digest()
export const sum = data => createHash('sha256').update(data).digest()
export { crc32 }

export const begin = (id, kind, length, checksum) => Buffer.concat([Buffer.from([OP.BEGIN]), u32(id), Buffer.from([kind]), u32(length), u32(checksum)])
export const part = (id, offset, chunk) => Buffer.concat([Buffer.from([OP.DATA]), u32(id), u32(offset), Buffer.from(chunk)])
export const end = (id, checksum) => Buffer.concat([Buffer.from([OP.END]), u32(id), u32(checksum)])
export const poll = (id, offset, size) => Buffer.concat([Buffer.from([OP.POLL]), u32(id), u32(offset), Buffer.from([size])])
export const release = id => Buffer.concat([Buffer.from([OP.RELEASE]), u32(id)])
export const reset = () => Buffer.from([OP.RESET])

/** Feed a whole body through BEGIN/DATA/END, honouring the 20-byte ATT limit. */
export function send(h, id, kind, body) {
  const payload = Buffer.from(body)
  const checksum = crc32(payload)
  if (h.write(begin(id, kind, payload.length, checksum)) !== 0) throw new Error('BEGIN rejected')
  for (let offset = 0; offset < payload.length; offset += 11) {
    if (h.write(part(id, offset, payload.subarray(offset, offset + 11))) !== 0) throw new Error('DATA rejected at ' + offset)
  }
  if (h.write(end(id, checksum)) !== 0) throw new Error('END rejected')
}

/** The phone half of one session, derived independently with Node crypto. */
export function client(epoch, owner) {
  const secret = Buffer.from(owner, 'base64')
  const master = sum('vitality-link-v1:' + owner)
  const nonce = Buffer.alloc(16, 0x23)
  const derive = label => mac(master, Buffer.concat([Buffer.from(label), u64(epoch), nonce]))
  const keys = { phoneEnc: derive('phone-enc'), phoneMac: derive('phone-mac'), watchEnc: derive('watch-enc'),
    watchMac: derive('watch-mac'), receipt: derive('receipt') }
  const hello = Buffer.concat([nonce, mac(mac(master, Buffer.from('hello')), Buffer.concat([u64(epoch), nonce]))])
  let counter = 0
  return {
    secret, keys, hello, enrollment: Buffer.concat([secret, hello]),
    encrypt(id, message, iv) {
      const vector = iv || Buffer.alloc(16, id & 0xff)
      const aes = createCipheriv('aes-256-cbc', keys.phoneEnc, vector)
      const body = Buffer.concat([vector, aes.update(JSON.stringify(message), 'utf8'), aes.final()])
      return Buffer.concat([body, mac(keys.phoneMac, Buffer.concat([u32(id), body]))])
    },
    verifyReceipt(value, state, id) {
      if (value.length !== 20 || value[0] !== state || value.readUInt32BE(1) !== id) return false
      return value.subarray(7).equals(mac(keys.receipt, value.subarray(0, 7)).subarray(0, 13))
    },
    readFlags(value) { const flags = value.readUInt16BE(5); return { request: (flags & REQUEST_FLAG) !== 0, value: flags & 0x7fff } },
    decryptReply(id, value) {
      const body = value.subarray(0, value.length - 32)
      if (!value.subarray(value.length - 32).equals(mac(keys.watchMac, Buffer.concat([u32(id), body])))) throw new Error('reply MAC mismatch')
      const iv = mac(keys.watchMac, Buffer.concat([u64(++counter), u32(id)])).subarray(0, 16)
      if (!body.subarray(0, 16).equals(iv)) throw new Error('reply IV mismatch')
      const aes = createDecipheriv('aes-256-cbc', keys.watchEnc, iv)
      return JSON.parse(Buffer.concat([aes.update(body.subarray(16)), aes.final()]).toString('utf8'))
    }
  }
}

/**
 * Native BLE + cipher doubles, plus a clock the test drives by hand.
 * `options.stored` seeds the persisted identity; `options.addServiceResult` forces a failure.
 */
export function harness(options = {}) {
  const clock = new Clock(options.now ?? 10000)
  const records = [], saved = [], states = [], persisted = [], merges = []
  const identity = {
    secret: '', epoch: 0, pending: false,
    async load() {
      const raw = options.stored ?? ''
      if (raw) { const value = JSON.parse(raw); this.secret = value.secret; this.epoch = value.epoch; this.pending = value.pending }
      else { this.epoch = clock.now; await this.save(JSON.stringify({ mode: 1, secret: '', epoch: this.epoch, pending: false })) }
    },
    async save(text) { persisted.push(JSON.parse(text)); const value = JSON.parse(text)
      this.secret = value.secret; this.epoch = value.epoch; this.pending = value.pending },
    async acceptHandshake(secret) { await this.save(JSON.stringify({ mode: 1, secret, epoch: Math.max(clock.now, this.epoch + 1), pending: this.pending })) },
    async completeRequest() { await this.save(JSON.stringify({ mode: 1, secret: this.secret, epoch: this.epoch, pending: false })) },
    async queueRequest() { await this.save(JSON.stringify({ mode: 1, secret: this.secret, epoch: this.epoch, pending: true })) },
    async clearSecret() { await this.save(JSON.stringify({ mode: 1, secret: '', epoch: Math.max(clock.now, this.epoch + 1), pending: this.pending })) }
  }
  const archive = {
    versions: () => options.versions ?? {},
    async merge(days) { merges.push(days); if (options.merge) return options.merge(days); return days.length }
  }
  const ble = { BLE: { createGattServer() {
    const index = records.length
    const record = { index, services: [], advertised: [], stopped: 0, closed: 0, removed: [], responses: [],
      addServiceResult: options.addServiceResults ? options.addServiceResults[index] : true,
      failRead: options.failReadOn === index }
    records.push(record)
    return {
      addService(service) { record.services.push(service); return record.addServiceResult },
      startAdvertising(setting, data) { record.advertised.push({ setting, data, at: clock.now }); return options.advertiseResult },
      stopAdvertising() { record.stopped++ },
      removeService(uuid) { record.removed.push(uuid); return true },
      close() { record.closed++ },
      subscribeCharacteristicRead(subscription) { record.read = subscription.callback; if (record.failRead) subscription.fail({}, 7) },
      subscribeCharacteristicWrite(subscription) { record.write = subscription.callback },
      subscribeConnectStateChange(subscription) { record.connection = subscription.callback },
      unsubscribeCharacteristicRead() {}, unsubscribeCharacteristicWrite() {}, unsubscribeConnectStateChange() {},
      sendResponse(response) { record.responses.push(response); return true }
    }
  } } }
  const cipher = { aes(request) {
    try {
      const encrypting = request.action === 'encrypt'
      const aes = (encrypting ? createCipheriv : createDecipheriv)('aes-256-cbc',
        Buffer.from(request.key, 'base64'), Buffer.from(request.iv, 'base64'))
      const input = Buffer.from(request.text, encrypting ? 'utf8' : 'base64')
      request.success({ text: Buffer.concat([aes.update(input), aes.final()]).toString(encrypting ? 'base64' : 'utf8') })
    } catch (_) { request.fail({}, 200) }
  } }
  const server = createLinkServerCore({ ble, cipher, identity, archive, clock: () => clock.now,
    timers: clock, hooks: { state: value => states.push(value), saved: value => saved.push(value) } })
  const current = () => records[records.length - 1]
  return {
    server, identity, archive, records, saved, states, persisted, merges, clock, current,
    async start() { await identity.load(); server.start(); return this },    advance(ms) { clock.advance(ms) },
    advertised: () => records.reduce((total, record) => total + record.advertised.length, 0),
    lastAdvertisedAt: () => { const all = records.flatMap(record => record.advertised); return all.length ? all[all.length - 1].at : -1 },
    /** One characteristic read. Returns the bytes the server answered with. */
    read(deviceId = 'AA:BB:CC:DD:EE:01') {
      const record = current(), before = record.responses.length
      record.read({ deviceId, transId: record.responses.length + 1, offset: 0, characteristicUuid: CHARACTERISTIC, serviceUuid: SERVICE })
      if (record.responses.length === before) throw new Error('read produced no response')
      const response = record.responses[record.responses.length - 1]
      if (response.status !== 0) throw new Error('read response status ' + response.status)
      return Buffer.from(response.value)
    },
    /** One characteristic write. Returns the ATT status the server answered with. */
    write(value, deviceId = 'AA:BB:CC:DD:EE:01') {
      const record = current(), before = record.responses.length
      record.write({ deviceId, transId: record.responses.length + 1, offset: 0, isPrep: false, needRsp: true,
        characteristicUuid: CHARACTERISTIC, serviceUuid: SERVICE, value: new Uint8Array(value).buffer })
      if (record.responses.length === before) throw new Error('write produced no response')
      return record.responses[record.responses.length - 1].status
    },
    connect(deviceId = 'AA:BB:CC:DD:EE:01') { current().connection({ deviceId, state: 2 }) },
    disconnect(deviceId = 'AA:BB:CC:DD:EE:01') { current().connection({ deviceId, state: 0 }) }
  }
}

/** Poll reads until the receipt carries `state`, mirroring the phone's retry behaviour. */
export async function settle(h, state, tries = 200) {
  for (let attempt = 0; attempt < tries; attempt++) {
    const value = h.read()
    if (value.length === 20 && value[0] === state) return value
    await new Promise(resolve => setImmediate(resolve))
  }
  throw new Error('watch never reached state ' + state)
}

class Clock {
  constructor(now) { this.now = now; this.jobs = new Map(); this.sequence = 0 }
  setTimeout(fn, ms) {
    const id = ++this.sequence
    // A zero delay means "after the current native callback returns". The protocol relies on
    // that to answer an ATT write before it starts crypto or storage work, so model it with a
    // real macrotask instead of a clock entry the test would have to advance by hand.
    if (!ms) {
      const handle = setImmediate(() => { this.jobs.delete(id); fn() })
      this.jobs.set(id, { at: Infinity, fn, every: 0, handle })
      return id
    }
    this.jobs.set(id, { at: this.now + ms, fn, every: 0 })
    return id
  }
  setInterval(fn, ms) { const id = ++this.sequence; this.jobs.set(id, { at: this.now + (ms || 1), fn, every: ms || 1 }); return id }
  clearTimeout(id) { const job = this.jobs.get(id); if (job && job.handle) clearImmediate(job.handle); this.jobs.delete(id) }
  clearInterval(id) { this.jobs.delete(id) }
  advance(ms) {
    const target = this.now + ms
    for (;;) {
      let next = null
      for (const [id, job] of this.jobs) if (job.at <= target && (!next || job.at < next.job.at)) next = { id, job }
      if (!next) break
      this.now = Math.max(this.now, next.job.at)
      if (next.job.every) next.job.at = this.now + next.job.every
      else this.jobs.delete(next.id)
      next.job.fn()
    }
    this.now = target
  }
}
