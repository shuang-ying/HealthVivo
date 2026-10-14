// Regenerate the cross-implementation test vectors shared by the watch (JavaScript) and the
// phone (Kotlin) link layers.
//
// The watch suite already checks its byte helpers against Node's own crypto and zlib. This script
// extends that check across languages: it freezes a handful of inputs and the exact bytes the
// watch implementation produces for them, and the phone unit test asserts that the Kotlin
// implementation reproduces the same bytes. A divergence in either language fails a build.
//
// Usage:  node scripts/generate-link-vectors.mjs
// Output: android/app/src/test/resources/link-vectors.json

import { writeFile, mkdir } from 'node:fs/promises'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'
import { createHash, createHmac, createCipheriv, randomBytes } from 'node:crypto'
import { crc32 } from 'node:zlib'
import * as F from '../watch/src/lib/link/frames.mjs'
import { hex, b64, u32 as w32, u64 as w64, join as concatBytes } from '../watch/src/lib/link/bytes.mjs'
import { openSession, replyIv, replyTag, receiptTag } from '../watch/src/lib/link/session.mjs'

const root = join(dirname(fileURLToPath(import.meta.url)), '..')
const output = join(root, 'android/app/src/test/resources/link-vectors.properties')

const SECRET = b64(new Uint8Array(32).map((_, index) => index + 1))
const EPOCH = 1712345678901
const NONCE = new Uint8Array(16).map((_, index) => 0x20 + index)
const IV = new Uint8Array(16).map((_, index) => 0xf0 - index)

// Derive the same session the watch derives, but with an injected nonce so the vector is stable.
const master = createHash('sha256').update('vitality-link-v1:' + SECRET).digest()
const derive = label => createHmac('sha256', master)
  .update(Buffer.concat([Buffer.from(label), Buffer.from(w64(EPOCH)), Buffer.from(NONCE)])).digest()
const hello = Buffer.concat([Buffer.from(NONCE),
  createHmac('sha256', createHmac('sha256', master).update(Buffer.from('hello')).digest())
    .update(Buffer.concat([Buffer.from(w64(EPOCH)), Buffer.from(NONCE)])).digest()])
const keys = { phoneEnc: derive('phone-enc'), phoneMac: derive('phone-mac'), watchEnc: derive('watch-enc'),
  watchMac: derive('watch-mac'), receiptKey: derive('receipt') }

// Rebuild the phone body exactly as docs/LINK_PROTOCOL.md specifies, then confirm the watch agrees.
const MESSAGE = '{"type":"ping"}'
const MESSAGE_ID = 7
const clean = createCipheriv('aes-256-cbc', keys.phoneEnc, Buffer.from(IV))
const cipherText = Buffer.concat([clean.update(MESSAGE, 'utf8'), clean.final()])
const bodyWithoutMac = Buffer.concat([Buffer.from(IV), cipherText])
const body = Buffer.concat([bodyWithoutMac,
  createHmac('sha256', keys.phoneMac).update(Buffer.concat([Buffer.from(w32(MESSAGE_ID)), bodyWithoutMac])).digest()])

const session = openSession(SECRET, EPOCH, hello)
if (!session) throw new Error('the watch refused its own vector hello')
for (const [name, value] of Object.entries(keys)) {
  if (hex(session[name === 'receiptKey' ? 'receiptKey' : name]) !== value.toString('hex')) throw new Error('key mismatch for ' + name)
}
const iv = replyIv(session, MESSAGE_ID)
const REPLY_TEXT = '{"known":{"2026-10-14":7},"cursor":1,"more":false}'
const replyCipher = (() => {
  const aes = createCipheriv('aes-256-cbc', keys.watchEnc, Buffer.from(iv))
  return Buffer.concat([aes.update(REPLY_TEXT, 'utf8'), aes.final()])
})()
const reply = concatBytes(iv, replyCipher, replyTag(session, MESSAGE_ID, iv, replyCipher))
const head = concatBytes(new Uint8Array([F.STATE.SAVED]), w32(MESSAGE_ID), new Uint8Array([0, 100]))
const receipt = concatBytes(head, receiptTag(session, head))
const greeting = F.greeting(EPOCH, true, 3, 4, 1)

const crc32Vectors = [
  { input: '', expected: crc32(Buffer.alloc(0)) },
  { input: '313233343536373839', expected: crc32(Buffer.from('123456789')) },
  { input: '00ff10', expected: crc32(Buffer.from([0, 255, 16])) },
  { input: hex(new Uint8Array(256).map((_, index) => index)), expected: crc32(Buffer.from(new Uint8Array(256).map((_, index) => index))) }
]
const frames = {
  begin: hex(F.begin(MESSAGE_ID, F.KIND.BODY, 1234, 0xdeadbeef)),
  data: hex(F.data(MESSAGE_ID, 22, new Uint8Array([1, 2, 3, 4, 5]))),
  end: hex(F.end(MESSAGE_ID, 0x01020304)),
  poll: hex(F.poll(MESSAGE_ID, 11, F.MAX_POLL)),
  release: hex(F.release(MESSAGE_ID)),
  reset: hex(F.reset()),
  greeting: hex(greeting),
  receipt: hex(receipt),
  reply: hex(reply)
}
const sessionVectors = {
  hello: hex(hello),
  enrollment: hex(concatBytes(new Uint8Array(Buffer.from(SECRET, 'base64')), hello)),
  phoneEnc: keys.phoneEnc.toString('hex'),
  phoneMac: keys.phoneMac.toString('hex'),
  watchEnc: keys.watchEnc.toString('hex'),
  watchMac: keys.watchMac.toString('hex'),
  receiptKey: keys.receiptKey.toString('hex')
}
const sizes = { attPayload: F.ATT_PAYLOAD, dataPayload: F.DATA_PAYLOAD, replyPayload: F.REPLY_PAYLOAD,
  begin: F.BEGIN_SIZE, end: F.END_SIZE, maxBody: F.MAX_BODY, maxReply: F.MAX_REPLY }

// A flat key=value file keeps the Kotlin side dependency-free (no JSON library on the JVM test
// classpath) while still being readable and diffable.
const lines = []
const put = (key, value) => lines.push(key + '=' + value)
put('note', 'Generated by scripts/generate-link-vectors.mjs. Do not edit by hand.')
put('protocol', F.PROTOCOL_VERSION)
put('service', F.SERVICE)
put('characteristic', F.CHARACTERISTIC)
put('secret', SECRET)
put('epoch', EPOCH)
put('nonce', hex(NONCE))
put('iv', hex(IV))
put('messageId', MESSAGE_ID)
put('message', MESSAGE)
put('replyText', REPLY_TEXT)
crc32Vectors.forEach((entry, index) => {
  put('crc32.' + index + '.input', entry.input)
  put('crc32.' + index + '.expected', entry.expected)
})
put('crc32.count', crc32Vectors.length)
for (const [name, value] of Object.entries(frames)) put('frames.' + name, value)
for (const [name, value] of Object.entries(sessionVectors)) put('session.' + name, value)
put('body', hex(body))
for (const [name, value] of Object.entries(sizes)) put('sizes.' + name, value)

await mkdir(dirname(output), { recursive: true })
await writeFile(output, lines.join('\n') + '\n')
console.log('wrote ' + output)
console.log('crc32("123456789") = 0x' + crc32(Buffer.from('123456789')).toString(16))
console.log('secret = ' + SECRET)
