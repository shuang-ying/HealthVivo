// Wire-format tests for the watch link layer.
//
// Every assertion is checked against an independent implementation (Node's crypto, Node's zlib
// CRC-32) so a mistake in the watch byte helpers cannot hide behind a matching mistake here.

import { test } from 'node:test'
import assert from 'node:assert/strict'
import { randomBytes } from 'node:crypto'
import { crc32, bytes, join, u16, u32, u64, read16, read32, read64, b64, unb64, sha256, hmac, equal, text, hex } from '../src/lib/link/bytes.mjs'
import * as F from '../src/lib/link/frames.mjs'
import { validSecret, openSession, splitEnrollment, openBody, receiptTag, replyIv, replyTag } from '../src/lib/link/session.mjs'
import { createIdentity, IDENTITY_KEY } from '../src/lib/link/identity.mjs'
import { client, mac, sum, u32 as cu32, u64 as cu64, crc32 as nodeCrc, KIND, STATE } from './link-client.mjs'

test('crc32 matches the ISO-HDLC vector and Node zlib for arbitrary input', () => {
  assert.equal(crc32(new TextEncoder().encode('123456789')), 0xcbf43926)
  assert.equal(crc32(new Uint8Array(0)), 0)
  for (const size of [1, 2, 7, 11, 64, 255, 1024]) {
    const data = new Uint8Array(randomBytes(size))
    assert.equal(crc32(data), nodeCrc(Buffer.from(data)), 'crc32 disagreed at size ' + size)
  }
})

test('integer helpers encode big-endian and reject nothing silently', () => {
  assert.equal(hex(u16(0x1234)), '1234')
  assert.equal(hex(u32(0x12345678)), '12345678')
  assert.equal(hex(u64(0x0001020304050607)), '0001020304050607')
  assert.equal(read16(u16(65535), 0), 65535)
  assert.equal(read32(u32(4294967295), 0), 4294967295)
  assert.equal(read64(u64(9007199254740991), 0), 9007199254740991)
  assert.equal(hex(join(u16(1), u32(2), new Uint8Array([3]))), '00010000000203')
})

test('base64 and utf8 helpers round-trip every byte value', () => {
  const data = new Uint8Array(256).map((_, index) => index)
  assert.deepEqual(unb64(b64(data)), data)
  assert.equal(b64(new Uint8Array([0xfb, 0xff])), '+/8=')
  assert.deepEqual(bytes(new Uint8Array([1, 2]).buffer), new Uint8Array([1, 2]))
  assert.equal(text(new TextEncoder().encode('活力记录')), '活力记录')
  // A view over a larger buffer must only expose its own window.
  const backing = new Uint8Array([9, 9, 7, 8, 9])
  assert.equal(hex(bytes(backing.subarray(2, 4))), '0708')
})

test('sha256 and hmac agree with Node crypto and hmac is length-safe', () => {
  for (const size of [0, 1, 55, 56, 64, 65, 200]) {
    const data = new Uint8Array(randomBytes(size))
    assert.equal(hex(sha256(data)), sum(Buffer.from(data)).toString('hex'))
  }
  const key = new Uint8Array(randomBytes(32)), value = new Uint8Array(randomBytes(100))
  assert.equal(hex(hmac(key, value)), mac(Buffer.from(key), Buffer.from(value)).toString('hex'))
  const longKey = new Uint8Array(randomBytes(100))
  assert.equal(hex(hmac(longKey, value)), mac(Buffer.from(longKey), Buffer.from(value)).toString('hex'), 'keys over one block must be hashed first')
  assert.equal(equal(new Uint8Array([1, 2]), new Uint8Array([1, 2])), true)
  assert.equal(equal(new Uint8Array([1, 2]), new Uint8Array([1, 3])), false)
})

test('frame encoders produce the documented byte layout and decode back', () => {
  assert.equal(hex(F.begin(0x01020304, KIND.BODY, 0x11223344, 0xaabbccdd)), '01010203040211223344aabbccdd')
  assert.equal(hex(F.data(0x01020304, 0x0000000b, new Uint8Array([1, 2, 3]))), '02010203040000000b010203')
  assert.equal(hex(F.end(0x01020304, 1)), '0301020304' + '00000001')
  assert.equal(hex(F.poll(0x01020304, 15, 15)), '04010203040000000f0f')
  assert.equal(hex(F.release(0x01020304)), '0501020304')
  assert.equal(hex(F.reset()), '06')

  // Every phone frame must fit one default-MTU ATT write.
  assert.equal(F.begin(1, KIND.BODY, 1, 1).length, F.BEGIN_SIZE)
  assert.equal(F.end(1, 1).length, F.END_SIZE)
  assert.equal(F.data(1, 0, new Uint8Array(F.DATA_PAYLOAD)).length, F.ATT_PAYLOAD)
  assert.ok(F.MAX_BODY > F.DATA_PAYLOAD)

  assert.deepEqual(F.decode(F.begin(7, KIND.BODY, 64, 99)), { op: F.OP.BEGIN, id: 7, kind: KIND.BODY, length: 64, crc: 99 })
  const decoded = F.decode(F.data(7, 11, new Uint8Array([5, 6])))
  assert.equal(decoded.op, F.OP.DATA); assert.equal(decoded.offset, 11); assert.deepEqual([...decoded.chunk], [5, 6])
  assert.deepEqual(F.decode(F.end(7, 42)), { op: F.OP.END, id: 7, crc: 42 })
  assert.deepEqual(F.decode(F.poll(7, 30, 15)), { op: F.OP.POLL, id: 7, offset: 30, size: 15 })
  assert.deepEqual(F.decode(F.release(7)), { op: F.OP.RELEASE, id: 7 })
  assert.deepEqual(F.decode(F.reset()), { op: F.OP.RESET })
})

test('decode rejects malformed frames without throwing', () => {
  const cases = [
    [new Uint8Array(0), 'empty'],
    [new Uint8Array([F.OP.DATA]), 'short'],
    [join(new Uint8Array([F.OP.DATA]), u32(0), u32(0), new Uint8Array([1])), 'id'],
    [F.begin(1, KIND.BODY, 1, 1).slice(0, 13), 'begin-length'],
    [new Uint8Array([F.OP.DATA]), 'short'],
    [join(new Uint8Array([F.OP.DATA]), u32(1), u32(0)), 'data-length'],
    [join(new Uint8Array([F.OP.DATA]), u32(1), u32(0), new Uint8Array(12)), 'data-length'],
    [join(new Uint8Array([F.OP.END]), u32(1)), 'end-length'],
    [join(new Uint8Array([F.OP.POLL]), u32(1), u32(0), new Uint8Array([15, 15])), 'poll-length'],
    [F.release(1).slice(0, 4), 'short'],
    [join(new Uint8Array([9]), u32(1)), 'op'],
    [join(new Uint8Array([F.OP.RESET]), new Uint8Array([0])), 'reset-length']
  ]
  for (const [frame, error] of cases) assert.equal(F.decode(frame).error, error, 'expected ' + error)
})

test('greeting, receipt and reply part are exactly 20 bytes and correctly marked', () => {
  const greeting = F.greeting(1234567, true, 4, 9, 3)
  assert.equal(greeting.length, F.GREETING_SIZE)
  assert.equal(greeting[0], F.GREETING_MARK)
  assert.equal(greeting[1], F.PROTOCOL_VERSION)
  assert.equal(greeting[2], 1)
  assert.equal(read64(greeting, 3), 1234567)
  assert.equal(read32(greeting, 11), 4)
  assert.equal(read32(greeting, 15), 9)
  assert.equal(greeting[19], 3)
  assert.equal(F.isGreeting(greeting), true)

  const receipt = join(new Uint8Array([STATE.SAVED]), cu32(7), u16(100), new Uint8Array(13))
  assert.equal(receipt.length, F.RECEIPT_SIZE)
  assert.equal(F.isReceipt(receipt), true)
  assert.equal(F.readFlags(receipt).request, false)
  assert.equal(F.readFlags(receipt).value, 100)
  const flags = F.readFlags(join(new Uint8Array([STATE.READY]), cu32(7), u16(F.REQUEST_FLAG | 12), new Uint8Array(13)))
  assert.equal(flags.request, true)
  assert.equal(flags.value, 12)

  const reply = F.replyPart(7, 11, new Uint8Array(F.REPLY_PAYLOAD))
  assert.equal(reply.length, F.ATT_PAYLOAD)
  assert.equal(reply[0], F.REPLY_MARK)
  assert.equal(F.isReplyPart(reply), true)
  assert.equal(F.isReplyPart(F.replyPart(7, 0, new Uint8Array(0))), false)
  assert.deepEqual([...F.failure(4)], [F.FAILURE_MARK, 4])
})

test('chunks splits a body into ATT-sized payloads that reassemble exactly', () => {
  const body = new Uint8Array(randomBytes(37))
  const frames = [...F.chunks(3, body)]
  assert.equal(frames.length, Math.ceil(37 / F.DATA_PAYLOAD))
  const rebuilt = new Uint8Array(37)
  frames.forEach(frame => { const decoded = F.decode(frame); rebuilt.set(decoded.chunk, decoded.offset) })
  assert.deepEqual(rebuilt, body)
  assert.ok(frames.every(frame => frame.length <= F.ATT_PAYLOAD))
})

test('a secret must be exactly 32 base64-encoded bytes', () => {
  assert.equal(validSecret(b64(new Uint8Array(randomBytes(32)))), true)
  assert.equal(validSecret(b64(new Uint8Array(randomBytes(31)))), false)
  assert.equal(validSecret('not base64!!'), false)
  assert.equal(validSecret(undefined), false)
  assert.equal(validSecret(42), false)
})

test('session keys and hello match an independent Node crypto derivation', () => {
  const secret = b64(new Uint8Array(randomBytes(32)))
  const epoch = 1712345678901
  const reference = client(epoch, secret)
  const session = openSession(secret, epoch, reference.hello)
  assert.ok(session)
  assert.equal(hex(session.phoneEnc), reference.keys.phoneEnc.toString('hex'))
  assert.equal(hex(session.phoneMac), reference.keys.phoneMac.toString('hex'))
  assert.equal(hex(session.watchEnc), reference.keys.watchEnc.toString('hex'))
  assert.equal(hex(session.watchMac), reference.keys.watchMac.toString('hex'))
  assert.equal(hex(session.receiptKey), reference.keys.receipt.toString('hex'))
  assert.deepEqual([...session.nonce], new Array(16).fill(0x23))
  assert.equal(session.counter, 0)
})

test('a hello from a different epoch is refused so a captured frame cannot be replayed', () => {
  const secret = b64(new Uint8Array(randomBytes(32)))
  const stale = client(1000, secret)
  assert.equal(openSession(secret, 1001, stale.hello), null)
  assert.equal(openSession(secret, 1000, stale.hello) !== null, true)
  assert.equal(openSession(secret, 1000, stale.hello.slice(0, 47)), null)
  const tampered = Uint8Array.from(stale.hello); tampered[40] ^= 1
  assert.equal(openSession(secret, 1000, tampered), null)
})

test('enrollment splits into the secret and a hello computed on that same secret', () => {
  const secret = b64(new Uint8Array(randomBytes(32)))
  const reference = client(500, secret)
  const split = splitEnrollment(reference.enrollment)
  assert.ok(split)
  assert.equal(split.secret, secret)
  assert.deepEqual([...split.hello], [...reference.hello])
  assert.ok(openSession(split.secret, 500, split.hello))
  assert.equal(splitEnrollment(reference.enrollment.slice(0, 79)), null)
  assert.ok(splitEnrollment(new Uint8Array(80)), 'any 80-byte payload splits; the proof check is what rejects it')
  assert.equal(openSession('', 500, new Uint8Array(80).slice(32)), null)
  assert.equal(openSession(secret, 500, new Uint8Array(48)), null)
})

test('body MACs are verified before the ciphertext is ever handed to the native decryptor', () => {
  const secret = b64(new Uint8Array(randomBytes(32)))
  const reference = client(9, secret)
  const session = openSession(secret, 9, reference.hello)
  const body = reference.encrypt(4, { type: 'ping' })
  assert.ok(openBody(session, 4, body))
  assert.equal(openBody(session, 5, body), null, 'a different transfer id must not authenticate')
  const broken = Uint8Array.from(body); broken[broken.length - 1] ^= 1
  assert.equal(openBody(session, 4, broken), null)
  assert.equal(openBody(session, 4, body.slice(0, 60)), null)
})

test('the reply IV is derived from the session counter so it can never repeat', () => {
  const secret = b64(new Uint8Array(randomBytes(32)))
  const reference = client(9, secret)
  const session = openSession(secret, 9, reference.hello)
  const first = replyIv(session, 4), second = replyIv(session, 4), third = replyIv(session, 5)
  assert.equal(first.length, 16)
  assert.notEqual(hex(first), hex(second))
  assert.notEqual(hex(second), hex(third))
  assert.equal(session.counter, 3)
  const tag = replyTag(session, 4, first, new Uint8Array([1, 2, 3]))
  assert.equal(hex(tag), mac(reference.keys.watchMac, Buffer.concat([cu32(4), Buffer.from(first), Buffer.from([1, 2, 3])])).toString('hex'))
  assert.equal(hex(receiptTag(session, new Uint8Array([2, 0, 0, 0, 4, 0, 0]))),
    mac(reference.keys.receipt, Buffer.from([2, 0, 0, 0, 4, 0, 0])).subarray(0, 13).toString('hex'))
})

test('identity persists before publishing and refuses an invalid credential', async () => {
  const writes = []
  const identity = createIdentity({ async load() { return '' }, async save(value) { writes.push(JSON.parse(value)) } }, () => 5000)
  await identity.load()
  assert.equal(writes.length, 1)
  assert.equal(identity.epoch, 5000)
  assert.equal(identity.secret, '')
  const secret = b64(new Uint8Array(randomBytes(32)))
  await identity.acceptHandshake(secret)
  assert.equal(identity.secret, secret)
  assert.equal(identity.epoch, 5001)
  assert.equal(writes[writes.length - 1].mode, 1)
  await assert.rejects(identity.acceptHandshake('bad'), /连接凭据无效/)
  assert.equal(identity.secret, secret, 'a rejected handshake must not change the stored credential')
  const failure = createIdentity({ async load() { return '' }, async save() { throw new Error('disk full') } }, () => 1)
  await assert.rejects(failure.acceptHandshake(secret), /disk full/)
  assert.equal(failure.secret, '', 'a failed write must not publish the new credential')
})

test('identity advances the epoch monotonically across restarts and keeps the request flag', async () => {
  const store = { value: '' }
  const io = { async load() { return store.value }, async save(text) { store.value = text } }
  const first = createIdentity(io, () => 1000)
  await first.load()
  await first.acceptHandshake(b64(new Uint8Array(randomBytes(32))))
  const afterHandshake = first.epoch
  await first.queueRequest()
  assert.equal(first.pending, true)
  await first.completeRequest()
  assert.equal(first.pending, false)

  // A restart must not reuse or regress the epoch, even when the clock jumps backwards.
  const second = createIdentity(io, () => 1)
  await second.load()
  assert.equal(second.epoch > afterHandshake, true)
  assert.equal(second.secret, first.secret)
  assert.equal(second.pending, false)
  assert.equal(JSON.parse(store.value).mode, 1)
})

test('a corrupt stored record is reported so the runtime can reset it', async () => {
  for (const value of ['{"mode":1,"secret":"zz","epoch":5,"pending":false}', '{"mode":1,"epoch":-1,"pending":false}', '{"mode":2,"secret":"","epoch":5,"pending":false}', 'null']) {
    const identity = createIdentity({ async load() { return value }, async save() {} }, () => 10)
    await assert.rejects(identity.load(), Error, 'expected a rejected record: ' + value)
  }
  const writes = []
  const identity = createIdentity({ async load() { return '' }, async save(text) { writes.push(JSON.parse(text)) } }, () => 77)
  await identity.load()
  await identity.forget()
  const last = writes[writes.length - 1]
  assert.equal(last.secret, '')
  assert.equal(last.pending, false)
  // load() publishes epoch 77, then forget() advances the epoch to 78 before it publishes.
  assert.equal(last.epoch, 78)
})

test('IDENTITY_KEY is a new key so the previous protocol cannot be misread', () => {
  assert.equal(IDENTITY_KEY, 'fitness.link.identity.v1')
})
