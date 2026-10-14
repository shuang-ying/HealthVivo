// Watch-side link protocol engine.
//
// This module contains every decision that matters for correctness and is deliberately free
// of native imports, timers that outlive a call, and randomness: the test suite drives it with
// API doubles. ./server.js owns the BlueOS GATT lifecycle and feeds requests into this engine.
//
// Guarantees:
//   * The native write callback is answered before any crypto or storage work runs, so a slow
//     archive commit can never look like a dropped ATT write to the phone.
//   * A receipt is never "saved" until archive.merge has durably committed the day files.
//   * A frame is rejected unless its transfer id is new, its offsets are exact and its CRC
//     matches, so a truncated or reordered transfer can never overwrite a stored day.

import { bytes, join, u32, u16, b64, unb64, equal, crc32 } from './bytes.mjs'
import * as F from './frames.mjs'
import { validSecret, openSession, splitEnrollment, openBody, receiptTag, replyIv, replyTag } from './session.mjs'

/** Failure stage codes carried in the low bits of a FAILED receipt. Never carry secret material. */
export const STAGE = { ACCEPT: 1, PROOF: 2, ENROL: 3, MAC: 4, DECRYPT: 5, STORE: 6, REPLY: 7, COMMAND: 8 }

const peerId = value => (typeof value === 'string' ? value.trim().toLowerCase() : '')

function nativeAes(cipher, options) {
  return new Promise((resolve, reject) => {
    let done = false
    const finish = (error, value) => { if (done) return; done = true; clearTimeout(timer); error ? reject(error) : resolve(value) }
    const timer = setTimeout(() => finish(new Error('加密接口超时')), 15000)
    try {
      cipher.aes(Object.assign({}, options, {
        transformation: 'AES/CBC/PKCS5Padding', ivOffset: 0, ivLen: 16,
        success: result => finish(null, result && typeof result.text === 'string' ? result.text : ''),
        fail: () => finish(new Error('加密接口不可用'))
      }))
    } catch (_) { finish(new Error('加密接口不可用')) }
  })
}

/**
 * @param cipher    native `@blueos.security.cipher` module (or a double in tests)
 * @param identity  { secret, epoch, pending, acceptHandshake(secret), completeRequest() }
 * @param archive   { versions(): object, merge(days): Promise<number> }
 * @param clock     () => milliseconds, injected so a test never sleeps for real
 * @param timers    setTimeout/clearTimeout source; the test suite supplies a virtual clock
 * @param hooks     { state(snapshot), saved(count) }
 */
export function createLinkProtocol({ cipher, identity, archive, clock = () => Date.now(), timers = globalThis, hooks = {} }) {
  const schedule = timers.setTimeout.bind(timers), cancelSchedule = timers.clearTimeout.bind(timers)
  let closed = false, paused = false, session = null, peer = '', assembly = null, busy = false
  let stateCode = 0, transferId = 0, flags = 0, reply = null, replyOffset = null, replyChunk = F.REPLY_PAYLOAD
  let generation = 0, timer = null, lastId = 0, attempts = [], stage = STAGE.ACCEPT, authFailed = false
  let reads = 0, writes = 0, savedDays = 0

  const snapshot = () => ({
    sessionOpen: !!session, peer, paused, state: stateCode, stage, busy,
    requestPending: !!identity.pending, replies: reply ? reply.length : 0, reads, writes, savedDays
  })
  const publish = message => { if (hooks.state) hooks.state(Object.assign({ message: message || '' }, snapshot())) }

  const clearTimer = () => { if (timer !== null) cancelSchedule(timer); timer = null }
  /** Any new transfer or disconnect invalidates assembly, reply and the transfer timeout at once. */
  const reset = reason => {
    generation++; clearTimer(); session = null; peer = ''; assembly = null; busy = false
    stateCode = 0; transferId = 0; flags = 0; reply = null; replyOffset = null
    lastId = 0; stage = STAGE.ACCEPT; authFailed = false
    publish(reason || '')
  }
  const update = (code, id, nextFlags, message) => {
    stateCode = code; transferId = id >>> 0; flags = nextFlags & 0xffff
    publish(message)
  }
  const fail = (id, code, message) => {
    assembly = null; reply = null; replyOffset = null; busy = false; clearTimer()
    stage = code
    update(F.STATE.FAILED, id, code, message)
  }

  const greetingValue = () => F.greeting(identity.epoch, validSecret(identity.secret), reads, writes,
    (paused ? 2 : 0) | (identity.pending ? 1 : 0))
  const receiptValue = () => {
    const head = join(new Uint8Array([stateCode]), u32(transferId), u16(flags))
    return join(head, receiptTag(session, head))
  }

  /** One read of the characteristic. Returns an ArrayBuffer-ready Uint8Array. */
  const read = request => {
    reads++
    if (closed || (request && request.offset)) return new Uint8Array(0)
    const sender = peerId(request && request.deviceId)
    if (!session || sender !== peer) return authFailed ? F.failure(stage) : greetingValue()
    if (stateCode === F.STATE.REPLY && replyOffset !== null && reply) {
      return F.replyPart(transferId, replyOffset, reply.slice(replyOffset, Math.min(replyOffset + replyChunk, reply.length)))
    }
    return receiptValue()
  }

  const armTimeout = id => {
    clearTimer()
    timer = schedule(() => { if (!session) { peer = '' } fail(id, STAGE.ACCEPT, '接收超时，请重新获取更新') }, F.ASSEMBLY_TIMEOUT)
  }

  /**
   * One write to the characteristic. Returns true when the ATT write should be acknowledged.
   * Every rejection is a plain `false`: a rejected write is normal protocol traffic, not a crash.
   */
  const write = request => {
    writes++
    if (closed || paused || !request) return false
    const sender = peerId(request.deviceId)
    if (!sender || request.offset || request.isPrep) return false
    const raw = bytes(request.value)
    if (!raw.length || raw.length > 512 || busy) return false
    if (peer && sender !== peer) return false
    const frame = F.decode(raw)
    if (frame.error) return false

    if (frame.op === F.OP.RESET) {
      // A bound phone may resynchronise at any time; an unrelated device may not end a session.
      if (session && sender !== peer) return false
      reset('手机重新开始会话')
      return true
    }
    if (frame.op === F.OP.POLL) {
      if (frame.size < F.MIN_POLL || frame.size > F.MAX_POLL) return false
      if (stateCode !== F.STATE.REPLY || !reply || frame.id !== transferId) return false
      if (frame.offset >= reply.length) return false
      replyChunk = frame.size; replyOffset = frame.offset
      return true
    }
    if (frame.op === F.OP.RELEASE) {
      if (stateCode !== F.STATE.REPLY || frame.id !== transferId) return false
      reply = null; replyOffset = null
      update(F.STATE.READY, frame.id, 0, identity.pending ? '手机已连接 · 等待请求的更新' : '手机已连接 · 等待手动更新')
      return true
    }
    if (frame.op === F.OP.BEGIN) {
      if (assembly) return false
      if (frame.length < 1 || frame.length > F.MAX_BODY) return false
      if (frame.kind === F.KIND.HELLO || frame.kind === F.KIND.ENROLL) {
        const enrolling = frame.kind === F.KIND.ENROLL
        if (session) return false
        if (enrolling ? frame.length !== F.ENROLL_SIZE || validSecret(identity.secret)
                      : frame.length !== F.HELLO_SIZE || !validSecret(identity.secret)) return false
        const recent = attempts.filter(time => clock() - time < F.HANDSHAKE_WINDOW)
        if (recent.length >= F.MAX_HANDSHAKES) return false
        attempts = recent.concat(clock())
        authFailed = false; stage = STAGE.ACCEPT
      } else if (frame.kind !== F.KIND.BODY || !session || frame.id <= lastId) return false
      peer = sender
      if (frame.kind === F.KIND.BODY) lastId = frame.id
      reply = null; replyOffset = null
      assembly = { id: frame.id, kind: frame.kind, length: frame.length, crc: frame.crc, offset: 0, peer: sender, data: new Uint8Array(frame.length) }
      update(F.STATE.RECEIVING, frame.id, 0, '正在接收手机数据')
      armTimeout(frame.id)
      return true
    }
    if (!assembly || assembly.id !== frame.id || assembly.peer !== sender) return false
    if (frame.op === F.OP.DATA) {
      if (frame.offset !== assembly.offset) return false
      if (assembly.offset + frame.chunk.length > assembly.length) return false
      assembly.data.set(frame.chunk, assembly.offset); assembly.offset += frame.chunk.length
      if (assembly.kind === F.KIND.BODY) {
        const percent = Math.floor(assembly.offset * 100 / assembly.length)
        if (percent !== flags) { flags = percent; publish('正在接收手机更新 ' + percent + '%') }
      }
      armTimeout(frame.id)
      return true
    }
    if (frame.op === F.OP.END) {
      if (assembly.offset !== assembly.length || frame.crc !== assembly.crc || crc32(assembly.data) !== assembly.crc) {
        fail(frame.id, STAGE.ACCEPT, '数据分段不完整，请重新获取更新')
        return true
      }
      const packet = assembly; assembly = null; clearTimer(); busy = true
      // Answer the ATT write first; crypto and storage run on the next tick.
      const active = generation
      schedule(() => { if (!closed && active === generation) receive(packet) }, 0)
      return true    }
    return false
  }

  async function decryptBody(id, body) {
    const opened = openBody(session, id, body)
    if (!opened) throw Object.assign(new Error('mac'), { stage: STAGE.MAC })
    try {
      return await nativeAes(cipher, { action: 'decrypt', text: b64(opened.cipher), key: b64(session.phoneEnc), iv: b64(opened.iv) })
    } catch (_) { throw Object.assign(new Error('decrypt'), { stage: STAGE.DECRYPT }) }
  }
  async function encryptReply(id, plain) {
    const iv = replyIv(session, id)
    let cipherText
    try {
      cipherText = unb64(await nativeAes(cipher, { action: 'encrypt', text: plain, key: b64(session.watchEnc), iv: b64(iv) }))
    } catch (_) { throw Object.assign(new Error('encrypt'), { stage: STAGE.REPLY }) }
    return join(iv, cipherText)
  }

  async function receive(packet) {
    const active = generation
    busy = true
    try {
      if (packet.kind !== F.KIND.BODY) {
        const enrolling = packet.kind === F.KIND.ENROLL
        let secret = identity.secret, hello = packet.data
        if (enrolling) {
          const split = splitEnrollment(packet.data)
          if (!split) throw Object.assign(new Error('enrolment'), { stage: STAGE.ENROL })
          secret = split.secret; hello = split.hello
        }
        const next = openSession(secret, identity.epoch, hello)
        if (!next) throw Object.assign(new Error('proof'), { stage: STAGE.PROOF })
        // Persist before installing: a crash here leaves the phone free to enrol again.
        await identity.acceptHandshake(secret)
        if (closed || active !== generation) return
        session = next; peer = packet.peer; lastId = 0; authFailed = false; stage = STAGE.ACCEPT
        busy = false
        update(F.STATE.READY, packet.id, identity.pending ? F.REQUEST_FLAG : 0,
          identity.pending ? '手机已连接 · 等待请求的更新' : '手机已连接 · 等待手动更新')
        return
      }
      if (!session) throw Object.assign(new Error('session'), { stage: STAGE.MAC })
      let plain
      try { plain = await decryptBody(packet.id, packet.data) }
      catch (error) { throw error }
      if (closed || active !== generation) return
      let message
      try { message = JSON.parse(plain) } catch (_) { throw Object.assign(new Error('json'), { stage: STAGE.DECRYPT }) }
      await dispatch(packet.id, message, active)
    } catch (error) {
      if (closed || active !== generation) return
      const handshake = packet.kind !== F.KIND.BODY
      if (handshake) { session = null; peer = ''; authFailed = true }
      const code = typeof error.stage === 'number' ? error.stage : STAGE.STORE
      fail(packet.id, code, handshake ? '手机会话建立失败（阶段 ' + code + '）' : '校验或保存失败，已保存日期仍保留')
    }
  }

  async function dispatch(id, message, active) {
    if (!message || typeof message !== 'object') throw Object.assign(new Error('message'), { stage: STAGE.COMMAND })
    if (message.type === 'inventory') {
      const versions = archive.versions(), dates = Object.keys(versions).sort(), cursor = message.cursor
      if (!Number.isSafeInteger(cursor) || cursor < 0 || cursor > dates.length) throw Object.assign(new Error('cursor'), { stage: STAGE.REPLY })
      const known = {}
      dates.slice(cursor, cursor + 100).forEach(day => { known[day] = versions[day] })
      const next = cursor + Object.keys(known).length
      const sealed = await encryptReply(id, JSON.stringify({ known, cursor: next, more: next < dates.length }))
      if (closed || active !== generation) return
      const body = join(sealed, replyTag(session, id, sealed.slice(0, 16), sealed.slice(16)))
      if (body.length > F.MAX_REPLY) throw Object.assign(new Error('reply'), { stage: STAGE.REPLY })
      reply = body; replyOffset = null; busy = false
      update(F.STATE.REPLY, id, body.length, '手机正在比较日期 · 记录尚未更改')
      return
    }
    if (message.type === 'days') {
      if (!Array.isArray(message.days) || message.days.length > 1 || typeof message.final !== 'boolean') {
        throw Object.assign(new Error('days'), { stage: STAGE.COMMAND })
      }
      update(stateCode = F.STATE.RECEIVING, id, 0, '正在保存更新日期…')
      let changed
      try { changed = await archive.merge(message.days) }
      catch (_) { throw Object.assign(new Error('merge'), { stage: STAGE.STORE }) }
      if (closed || active !== generation) return
      if (message.final) await identity.completeRequest()
      if (closed || active !== generation) return
      savedDays += changed
      busy = false
      update(F.STATE.SAVED, id, 100, message.final ? '更新完成 · 保存确认已就绪' : '该日已保存 · 正在接收后续日期')
      if (hooks.saved) hooks.saved(changed)
      return
    }
    // A heartbeat keeps the session warm without transferring any health record.
    if (message.type === 'ping') {
      busy = false
      update(F.STATE.READY, id, 0, identity.pending ? '手机已连接 · 等待请求的更新' : '手机已连接 · 等待手动更新')
      return
    }
    throw Object.assign(new Error('command'), { stage: STAGE.COMMAND })
  }

  return {
    read, write, snapshot,
    /** Value the native service is registered with. Does not count as a phone read. */
    initialValue: greetingValue,
    /** Physical link lost: forget the session but keep the archive and the pending request. */
    disconnect(reason) { reset(reason || '手机已断开 · 等待重连，记录可离线查看') },
    pauseEnrolment() { if (busy || assembly || session) return false; reset('正在清除旧手机连接'); paused = true; return true },
    resumeEnrolment() { paused = false; publish('直连服务已开启，在手机搜索并连接') },
    close() { closed = true; reset('直连服务已关闭') }
  }
}
