// Vitality Link wire format (protocol version 1).
//
// This module is deliberately pure: it only encodes and decodes bytes. Both the watch
// server and the phone client implement the same layout, and both test suites assert
// the same fixed vectors, so a change here must be mirrored in LinkFrames.kt.
//
// Physical layer: one GATT service with a single characteristic that is both readable
// and writable. The phone writes every frame with an acknowledged write (ATT MTU 23,
// so at most 20 bytes per packet) and reads replies back from the same characteristic.
// The watch answers a write callback before doing any crypto or storage work.

import { bytes, join, u16, u32, u64, read16, read32, crc32 } from './bytes.mjs'

export const SERVICE = '6f2a9c10-8b4d-4e77-9a35-1c0d7e5b3f82'
export const CHARACTERISTIC = '6f2a9c11-8b4d-4e77-9a35-1c0d7e5b3f82'

/** BlueOS CharacteristicProperties enumerates read as 4 and write as 5. */
export const CHARACTERISTIC_PROPERTIES = [4, 5]

export const PROTOCOL_VERSION = 1

/** Phone to watch operations. */
export const OP = { BEGIN: 1, DATA: 2, END: 3, POLL: 4, RELEASE: 5, RESET: 6 }
/** BEGIN kinds: 1 resumes a saved secret, 2 carries an encrypted body, 3 enrols a new secret. */
export const KIND = { HELLO: 1, BODY: 2, ENROLL: 3 }
/** Receipt states, mirroring the table in docs/LINK_PROTOCOL.md. */
export const STATE = { OPEN: 1, READY: 2, RECEIVING: 3, SAVED: 4, FAILED: 5, REJECTED: 6, BUSY: 7, REPLY: 8 }

export const GREETING_MARK = 0xd6
export const REPLY_MARK = 0xd8

/** The ATT payload of a default-MTU link. The watch never negotiates a larger MTU. */
export const ATT_PAYLOAD = 20
/** BEGIN is 14 bytes, so a full packet still fits in one 20-byte ATT write. */
export const BEGIN_SIZE = 14
export const END_SIZE = 9
export const DATA_HEADER = 9
/** Reply part = mark(1) + transfer id(4) + offset(4), then the payload. */
export const REPLY_HEADER = 9
export const DATA_PAYLOAD = ATT_PAYLOAD - DATA_HEADER
export const REPLY_PAYLOAD = ATT_PAYLOAD - REPLY_HEADER
export const MAX_BODY = 200 * 1024
export const MAX_REPLY = 32767
export const MIN_POLL = 1
export const MAX_POLL = REPLY_PAYLOAD
export const ENROLL_SIZE = 32 + 16 + 32
export const HELLO_SIZE = 16 + 32
export const MAX_HANDSHAKES = 3
export const HANDSHAKE_WINDOW = 30000
export const ASSEMBLY_TIMEOUT = 60000
export const RECEIPT_SIZE = 20
export const GREETING_SIZE = 20
export const FAILURE_MARK = 5

/** Reply-length flag: bit 15 asks the phone for an update, bits 0..14 hold a length or progress. */
export const REQUEST_FLAG = 0x8000
export const FLAG_MASK = 0x7fff

export function begin(id, kind, length, crc) { return join(new Uint8Array([OP.BEGIN]), u32(id), new Uint8Array([kind]), u32(length), u32(crc)) }
export function data(id, offset, chunk) { return join(new Uint8Array([OP.DATA]), u32(id), u32(offset), bytes(chunk)) }
export function end(id, crc) { return join(new Uint8Array([OP.END]), u32(id), u32(crc)) }
export function poll(id, offset, size) { return join(new Uint8Array([OP.POLL]), u32(id), u32(offset), new Uint8Array([size])) }
export function release(id) { return join(new Uint8Array([OP.RELEASE]), u32(id)) }
export function reset() { return new Uint8Array([OP.RESET]) }

/** 20-byte authenticated receipt: state, transfer id, flags, then 13 bytes of HMAC. */
export function receipt(state, id, flags, mac) { return join(new Uint8Array([state]), u32(id), u16(flags), bytes(mac).slice(0, 13)) }
/** 20-byte public greeting. Only valid before a session exists for this peer. */
export function greeting(epoch, enrolled, reads, writes, surface) {
  return join(new Uint8Array([GREETING_MARK, PROTOCOL_VERSION, enrolled ? 1 : 0]), u64(epoch), u32(reads), u32(writes), new Uint8Array([surface & 0xff]))
}
/** Chunk of a large reply body, read back by the phone from the same characteristic. */
export function replyPart(id, offset, chunk) { return join(new Uint8Array([REPLY_MARK]), u32(id), u32(offset), bytes(chunk)) }
/** Two-byte handshake rejection: mark, then a stage code that never carries secrets. */
export function failure(code) { return new Uint8Array([FAILURE_MARK, code & 0xff]) }

export function readFlags(value) { const flags = read16(value, 5); return { raw: flags, request: (flags & REQUEST_FLAG) !== 0, value: flags & FLAG_MASK } }
export function isGreeting(value) { return value.length === GREETING_SIZE && value[0] === GREETING_MARK }
export function isReplyPart(value) { return value.length > REPLY_HEADER && value[0] === REPLY_MARK }
/** A receipt is 20 bytes like a greeting, so the mark byte is what tells them apart. */
export function isReceipt(value) { return value.length === RECEIPT_SIZE && value[0] !== GREETING_MARK }

/**
 * Decode one phone write. Returns a plain object; never throws for hostile input,
 * because a malformed frame must be answered with a rejected ATT write, not a crash.
 */
export function decode(frame) {
  const value = bytes(frame)
  if (!value.length) return { op: 0, error: 'empty' }
  const op = value[0]
  if (op === OP.RESET) return value.length === 1 ? { op } : { op, error: 'reset-length' }
  if (value.length < 5) return { op, error: 'short' }
  const id = read32(value, 1)
  if (!id) return { op, error: 'id' }
  if (op === OP.BEGIN) {
    if (value.length !== BEGIN_SIZE) return { op, id, error: 'begin-length' }
    return { op, id, kind: value[5], length: read32(value, 6), crc: read32(value, 10) }
  }
  if (op === OP.DATA) {
    if (value.length <= DATA_HEADER || value.length > ATT_PAYLOAD) return { op, id, error: 'data-length' }
    return { op, id, offset: read32(value, 5), chunk: value.slice(DATA_HEADER) }
  }
  if (op === OP.END) return value.length === END_SIZE ? { op, id, crc: read32(value, 5) } : { op, id, error: 'end-length' }
  if (op === OP.POLL) {
    if (value.length !== 10) return { op, id, error: 'poll-length' }
    return { op, id, offset: read32(value, 5), size: value[9] }
  }
  if (op === OP.RELEASE) return value.length === 5 ? { op, id } : { op, id, error: 'release-length' }
  return { op, id, error: 'op' }
}

/** Split a body into DATA frames that each fit one acknowledged ATT write. */
export function* chunks(id, body) {
  const payload = bytes(body)
  for (let offset = 0; offset < payload.length; offset += DATA_PAYLOAD) {
    yield data(id, offset, payload.slice(offset, Math.min(offset + DATA_PAYLOAD, payload.length)))
  }
}

export { crc32 }
