// Link server tests: protocol correctness plus the persistence behaviour that keeps the phone
// able to reconnect. The phone side is an independent Node-crypto client (./link-client.mjs), and
// the native GATT module and the clock are doubles, so every assertion here is deterministic.

import { test } from 'node:test'
import assert from 'node:assert/strict'
import { randomBytes } from 'node:crypto'
import { setTimeout as delay } from 'node:timers/promises'
import { b64 } from '../src/lib/link/bytes.mjs'
import { HEALTH_INTERVAL, READVERTISE_AFTER, REOPEN_AFTER, ADVERTISE_DELAY } from '../src/lib/link/server-core.mjs'
import { SERVICE, CHARACTERISTIC, MAX_BODY } from '../src/lib/link/frames.mjs'
import { harness, settle, send, client, KIND, STATE, REQUEST_FLAG, begin, part, end, poll, release, reset, crc32 } from './link-client.mjs'

const secretOf = () => b64(new Uint8Array(randomBytes(32)))
const stored = (secret, epoch = 5000, pending = false) => JSON.stringify({ mode: 1, secret, epoch, pending })

test('the native service uses the documented property enumeration and one characteristic', async () => {
  const h = harness()
  await h.start()
  const service = h.current().services[0]
  assert.equal(service.serviceUuid, SERVICE)
  assert.equal(service.isPrimary, true)
  assert.deepEqual(service.includeServices, [])
  assert.equal(service.characteristics.length, 1)
  const characteristic = service.characteristics[0]
  assert.equal(characteristic.characteristicUuid, CHARACTERISTIC)
  // BlueOS enumerates read as 4 and write as 5; this is not an Android property bitmask.
  assert.deepEqual(characteristic.properties, [4, 5])
  assert.deepEqual(characteristic.descriptors, [])
  // The documented type is ArrayBuffer, never a typed-array view.
  assert.equal(ArrayBuffer.isView(characteristic.characteristicValue), false)
  assert.equal(characteristic.characteristicValue.byteLength, 20)
  assert.equal(h.current().advertised.length, 1)
  assert.deepEqual(h.current().advertised[0].data.serviceUuids, [SERVICE])
  assert.equal(h.current().advertised[0].setting.connectable, true)
  h.server.close()
})

test('first-use enrollment installs the phone without transferring any record', async () => {
  const h = harness()
  await h.start()
  try {
    const opening = h.read()
    assert.equal(opening.length, 20)
    assert.deepEqual([...opening.subarray(0, 3)], [0xd6, 1, 0], 'protocol version 1, not yet enrolled')
    assert.equal(h.write(reset()), 0)

    const phone = client(h.identity.epoch, secretOf())
    send(h, 1, KIND.ENROLL, phone.enrollment)
    const ready = await settle(h, STATE.READY)
    assert.equal(phone.verifyReceipt(ready, STATE.READY, 1), true)
    assert.equal(h.identity.secret, b64(phone.secret))
    assert.equal(h.identity.epoch, h.clock.now + 1, 'the epoch advances exactly once per handshake')
    assert.deepEqual(h.merges, [], 'connecting must never transfer health records')
    // A different phone now sees the enrolled flag.
    assert.equal(h.read('AA:BB:CC:DD:EE:99')[2], 1)
  } finally { h.server.close() }
})

test('a saved credential reconnects with the short hello and never enrols again', async () => {
  const secret = secretOf()
  const h = harness({ stored: stored(secret, 5000) })
  await h.start()
  try {
    const phone = client(5000, secret)
    send(h, 1, KIND.HELLO, phone.hello)
    assert.equal(phone.verifyReceipt(await settle(h, STATE.READY), STATE.READY, 1), true)
    assert.equal(h.identity.secret, secret)
    assert.equal(h.persisted.filter(value => value.secret === secret).length >= 1, true)
    assert.deepEqual(h.merges, [])
  } finally { h.server.close() }
})

test('a stale hello is refused and never installs a session', async () => {
  const secret = secretOf()
  const h = harness({ stored: stored(secret, 5000) })
  await h.start()
  try {
    const stale = client(4999, secret)
    send(h, 1, KIND.HELLO, stale.hello)
    const failure = await settleFailure(h)
    assert.equal(failure[0], 5, 'a rejected handshake answers with the short failure marker, not a receipt')
    assert.equal(h.server.snapshot().connected, false)
    assert.deepEqual(h.merges, [])
    // A rejected handshake must not have advanced the stored credential.
    assert.equal(h.identity.secret, secret)
    // A correct hello still works afterwards, without waiting out the attempt window.
    const good = client(5000, secret)
    send(h, 2, KIND.HELLO, good.hello)
    assert.equal(good.verifyReceipt(await settle(h, STATE.READY), STATE.READY, 2), true)
  } finally { h.server.close() }
})

test('SAVED is only reported after the archive committed the day', async () => {
  let releaseMerge
  const gate = new Promise(resolve => { releaseMerge = resolve })
  const h = harness({ merge: () => gate })
  await h.start()
  try {
    const phone = client(h.identity.epoch, secretOf())
    send(h, 1, KIND.ENROLL, phone.enrollment)
    await settle(h, STATE.READY)
    await h.identity.queueRequest()

    send(h, 2, KIND.BODY, phone.encrypt(2, { type: 'days', days: [{ day: '2026-10-14' }], final: true }))
    assert.equal(h.read()[0], STATE.RECEIVING)
    await delay(20)
    assert.equal(h.merges.length, 1)
    assert.equal(h.read()[0], STATE.RECEIVING, 'still awaiting the storage commit')
    assert.equal(h.identity.pending, true, 'the request stays pending until the archive commits')
    assert.deepEqual(h.saved, [])

    releaseMerge(1)
    const saved = await settle(h, STATE.SAVED)
    assert.equal(phone.verifyReceipt(saved, STATE.SAVED, 2), true)
    assert.deepEqual(h.saved, [1])
    assert.equal(h.identity.pending, false)
    assert.equal(h.server.snapshot().saved, 1)
  } finally { releaseMerge(1); h.server.close() }
})

test('an inventory reply is encrypted, authenticated and delivered in POLL chunks', async () => {
  const h = harness({ versions: { '2026-10-14': 7, '2026-10-13': 3 } })
  await h.start()
  try {
    const phone = client(h.identity.epoch, secretOf())
    send(h, 1, KIND.ENROLL, phone.enrollment)
    await settle(h, STATE.READY)
    send(h, 2, KIND.BODY, phone.encrypt(2, { type: 'inventory', cursor: 0 }))
    const ready = await settle(h, STATE.REPLY)
    assert.equal(phone.verifyReceipt(ready, STATE.REPLY, 2), true)
    const size = readLength(phone, ready)
    assert.ok(size > 0 && size <= 32767)

    const parts = []
    for (let offset = 0; offset < size; offset += 11) {
      assert.equal(h.write(poll(2, offset, 11)), 0)
      const chunk = h.read()
      assert.equal(chunk[0], 0xd8)
      assert.equal(chunk.readUInt32BE(1), 2, 'the reply part names the transfer it belongs to')
      assert.equal(chunk.readUInt32BE(5), offset)
      assert.ok(chunk.length <= 20)
      parts.push(chunk.subarray(9))
    }
    const page = phone.decryptReply(2, Buffer.concat(parts))
    assert.deepEqual(page.known, { '2026-10-13': 3, '2026-10-14': 7 })
    assert.equal(page.cursor, 2)
    assert.equal(page.more, false)

    assert.equal(h.write(release(2)), 0)
    assert.equal(phone.verifyReceipt(h.read(), STATE.READY, 2), true)
    assert.deepEqual(h.merges, [])
  } finally { h.server.close() }
})

test('a pending update request is advertised to the phone through the receipt flag', async () => {
  const secret = secretOf()
  const h = harness({ stored: stored(secret, 5000, true) })
  await h.start()
  try {
    const phone = client(5000, secret)
    send(h, 1, KIND.HELLO, phone.hello)
    const ready = await settle(h, STATE.READY)
    assert.equal(phone.readFlags(ready).request, true, 'bit 15 asks the phone for an update')
    assert.equal(h.write(release(1)), 6, 'RELEASE is only meaningful while a reply is outstanding')
  } finally { h.server.close() }
})

test('replay, oversized bodies, a wrong CRC and out-of-order parts can never save a record', async () => {
  const h = harness()
  await h.start()
  try {
    const phone = client(h.identity.epoch, secretOf())
    send(h, 1, KIND.ENROLL, phone.enrollment)
    await settle(h, STATE.READY)

    // Zero is not a transfer id, the size limit applies before anything is buffered, and a
    // handshake kind is refused once a session exists.
    assert.equal(h.write(begin(0, KIND.BODY, 64, 0)), 6)
    assert.equal(h.write(begin(3, KIND.BODY, MAX_BODY + 1, 0)), 6)
    assert.equal(h.write(begin(4, KIND.HELLO, 48, 0)), 6)

    const body = Buffer.from(phone.encrypt(2, { type: 'days', days: [{ day: '2026-10-14' }], final: true }))
    const checksum = crc32(body)
    assert.equal(h.write(begin(2, KIND.BODY, body.length, checksum)), 0)
    // An out-of-order offset is refused without disturbing the assembly.
    assert.equal(h.write(part(2, 11, body.subarray(11, 22))), 6)
    assert.equal(h.write(part(2, 0, body.subarray(0, 11))), 0)
    // END with the wrong checksum is answered but never saved.
    assert.equal(h.write(end(2, checksum ^ 1)), 0)
    assert.equal((await settle(h, STATE.FAILED))[0], STATE.FAILED)
    assert.deepEqual(h.merges, [])
    // The consumed transfer id can never be replayed.
    assert.equal(h.write(begin(2, KIND.BODY, body.length, checksum)), 6)

    // A body whose MAC does not match its id fails after the checksum already passed.
    const corrupt = Buffer.from(body); corrupt[corrupt.length - 1] ^= 1
    assert.equal(h.write(begin(3, KIND.BODY, corrupt.length, crc32(corrupt))), 0)
    for (let offset = 0; offset < corrupt.length; offset += 11) assert.equal(h.write(part(3, offset, corrupt.subarray(offset, offset + 11))), 0)
    assert.equal(h.write(end(3, crc32(corrupt))), 0)
    assert.equal((await settle(h, STATE.FAILED))[0], STATE.FAILED)
    assert.deepEqual(h.merges, [])

    // A max-size body is refused outright rather than buffered.
    assert.equal(h.write(begin(4, KIND.BODY, MAX_BODY, 0)), 0)
    assert.equal(h.write(begin(5, KIND.BODY, 1, 0)), 6, 'a second BEGIN while an assembly is open is refused')
    assert.equal(h.write(reset()), 0)
  } finally { h.server.close() }
})

test('an unfinished assembly times out and a later transfer starts from a fresh id', async () => {
  const h = harness()
  await h.start()
  try {
    const phone = client(h.identity.epoch, secretOf())
    send(h, 1, KIND.ENROLL, phone.enrollment)
    await settle(h, STATE.READY)
    assert.equal(h.write(begin(2, KIND.BODY, 64, 0)), 0)
    assert.equal(h.read()[0], STATE.RECEIVING)
    h.advance(61000)
    assert.equal(h.read()[0], STATE.FAILED, 'a stalled transfer is reported as failed, never as saved')
    assert.equal(h.write(begin(2, KIND.BODY, 64, 0)), 6, 'a consumed id stays consumed')
    assert.equal(h.write(begin(3, KIND.BODY, 64, 0)), 0, 'a new id is accepted after the timeout')
    assert.equal(h.write(reset()), 0)
    assert.equal(h.read()[0], 0xd6, 'RESET returns to the public greeting')
  } finally { h.server.close() }
})

test('only the bound phone may hold the link, and it cannot be displaced by another device', async () => {
  const secret = secretOf()
  const h = harness({ stored: stored(secret, 5000) })
  await h.start()
  try {
    const owner = client(5000, secret)
    send(h, 1, KIND.HELLO, owner.hello)
    await settle(h, STATE.READY)
    // A second phone cannot enrol while a secret exists, cannot reset the session, and cannot poll.
    assert.equal(h.write(begin(2, KIND.ENROLL, 80, 0), 'AA:BB:CC:DD:EE:02'), 6)
    assert.equal(h.write(reset(), 'AA:BB:CC:DD:EE:02'), 6)
    assert.equal(h.read('AA:BB:CC:DD:EE:02')[0], 0xd6, 'a stranger still only sees the public greeting')
    // The bound phone is unaffected.
    assert.equal(h.read('AA:BB:CC:DD:EE:01')[0], STATE.READY)
  } finally { h.server.close() }
})

test('enrolment attempts are rate limited so a nearby device cannot grind the secret', async () => {
  const secret = secretOf()
  const h = harness({ stored: stored(secret, 5000) })
  await h.start()
  try {
    // Three wrong-epoch hellos are allowed through to the proof check, which rejects them.
    for (let attempt = 0; attempt < 3; attempt++) {
      const wrong = client(4999, secret)
      send(h, attempt + 1, KIND.HELLO, wrong.hello)
      assert.equal((await settleFailure(h))[0], 5)
    }
    // The fourth attempt inside the window is refused before any crypto runs.
    const fourth = client(5000, secret)
    assert.equal(h.write(begin(9, KIND.HELLO, fourth.hello.length, crc32(fourth.hello))), 6)
    h.advance(30001)
    // send() throws when BEGIN is rejected, so this proves the window reopened.
    send(h, 9, KIND.HELLO, fourth.hello)
    assert.equal(fourth.verifyReceipt(await settle(h, STATE.READY), STATE.READY, 9), true)
  } finally { h.server.close() }
})

test('the watchdog re-requests advertising so a lost advertisement self-heals', async () => {
  const h = harness()
  await h.start()
  try {
    assert.equal(h.advertised(), 1, 'advertising starts with the server')
    h.advance(HEALTH_INTERVAL)
    assert.equal(h.advertised(), 1, 'no redundant advertising before the grace period')
    h.advance(READVERTISE_AFTER)
    assert.equal(h.advertised() >= 2, true, 'advertising is re-requested when nobody is connected')

    h.connect()
    const whileConnected = h.advertised()
    h.advance(READVERTISE_AFTER * 3)
    assert.equal(h.advertised(), whileConnected, 'a connected link is never disturbed by the watchdog')

    h.disconnect()
    const beforeReconnect = h.advertised()
    h.advance(ADVERTISE_DELAY)
    assert.equal(h.advertised() > beforeReconnect, true, 'a disconnect immediately re-opens the link')
  } finally { h.server.close() }
})

test('a server that fails to register is rebuilt by the watchdog', async () => {
  const h = harness({ addServiceResults: [false, true] })
  await h.start()
  try {
    assert.equal(h.records.length, 1)
    assert.equal(h.server.snapshot().serverReady, false)
    assert.match(h.server.snapshot().message, /启动失败/)
    h.advance(REOPEN_AFTER - 1000)
    assert.equal(h.records.length, 1, 'a failed server is not rebuilt on every tick')
    h.advance(HEALTH_INTERVAL)
    assert.equal(h.records.length, 2, 'the watchdog rebuilds the whole server')
    assert.equal(h.server.snapshot().serverReady, true)
    assert.equal(h.advertised(), 1, 'only the healthy server advertises')
  } finally { h.server.close() }
})

test('a subscription failure keeps its stage and never announces a ready service', async () => {
  const h = harness({ addServiceResults: [true, true], failReadOn: 0 })
  await h.start()
  try {
    const snapshot = h.server.snapshot()
    assert.equal(snapshot.serverReady, false)
    assert.equal(snapshot.nativeStage, 'subscribe-read')
    assert.match(snapshot.message, /监听读取/)
    assert.equal(h.advertised(), 0, 'a half-registered server never advertises')
  } finally { h.server.close() }
})

test('a disconnect drops the session but keeps the archive and the pending request', async () => {
  const secret = secretOf()
  const h = harness({ stored: stored(secret, 5000, true) })
  await h.start()
  try {
    const phone = client(5000, secret)
    send(h, 1, KIND.HELLO, phone.hello)
    await settle(h, STATE.READY)
    assert.equal(h.server.snapshot().connected, true)
    h.disconnect()
    assert.equal(h.server.snapshot().connected, false)
    assert.equal(h.read()[0], 0xd6, 'no session survives a physical disconnect')
    assert.equal(h.identity.pending, true, 'the user request survives so it can resume')
    assert.equal(h.identity.secret, secret, 'the credential survives so no re-enrolment is needed')
    // Reconnecting works with the same credential and a brand new nonce.
    const again = client(h.identity.epoch, secret)
    send(h, 2, KIND.HELLO, again.hello)
    assert.equal(again.verifyReceipt(await settle(h, STATE.READY), STATE.READY, 2), true)
  } finally { h.server.close() }
})

test('clearPhone pauses enrolment, wipes the secret and leaves the archive intact', async () => {
  const secret = secretOf()
  const h = harness({ stored: stored(secret, 5000) })
  await h.start()
  try {
    assert.equal(h.server.pauseEnrolment(), true)
    assert.equal(h.server.snapshot().paused, true)
    await h.identity.clearSecret()
    h.server.resumeEnrolment()
    assert.equal(h.identity.secret, '')
    assert.equal(h.server.snapshot().paused, false)
    assert.equal(h.read()[2], 0, 'the watch is open for a new phone again')
    // After clearing, a new phone may enrol.
    const phone = client(h.identity.epoch, secretOf())
    send(h, 1, KIND.ENROLL, phone.enrollment)
    assert.equal(phone.verifyReceipt(await settle(h, STATE.READY), STATE.READY, 1), true)
  } finally { h.server.close() }
})

test('close() stops the watchdog and unregisters the native service in order', async () => {
  const h = harness()
  await h.start()
  const record = h.current()
  h.server.close()
  assert.equal(record.stopped, 1, 'advertising is stopped first')
  assert.deepEqual(record.removed, [SERVICE], 'the service is removed before close')
  assert.equal(record.closed, 1)
  const advertised = h.advertised()
  h.advance(READVERTISE_AFTER * 4)
  assert.equal(h.advertised(), advertised, 'a closed server never advertises again')
  assert.equal(h.server.ensureServer(), false)
})

test('ensureServer only repairs what is actually broken', async () => {
  const h = harness()
  await h.start()
  try {
    const advertised = h.advertised()
    assert.equal(h.server.ensureServer(), true)
    h.advance(HEALTH_INTERVAL)
    assert.equal(h.records.length, 1, 'a healthy server is never rebuilt')
    assert.equal(h.advertised() >= advertised, true)
  } finally { h.server.close() }
})

function readLength(phone, receipt) { return phone.readFlags(receipt).value }

/** Wait for the short two-byte failure marker a rejected handshake answers with. */
async function settleFailure(h, tries = 200) {
  for (let attempt = 0; attempt < tries; attempt++) {
    const value = h.read()
    if (value.length === 2 && value[0] === 5) return value
    await delay(0)
  }
  throw new Error('the watch never reported a handshake failure')
}
