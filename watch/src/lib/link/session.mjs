// Session establishment and message protection for the watch link layer.
//
// Design notes, and why each choice is safe:
//   * The watch has no HMAC API (the cipher module exposes rsa/aes/base64/crc32/hash only),
//     so HMAC-SHA256 comes from ./bytes.mjs in plain JavaScript.
//   * The phone contributes a fresh 16-byte nonce on every connection; the watch contributes
//     its persistent, monotonically increasing epoch. Together they make each session's key
//     material unique, so a replayed hello from an older connection can never be accepted.
//   * Reply IVs are derived from a per-session counter instead of a random source, because a
//     watch-side random number generator is not guaranteed and an IV must never repeat under
//     the same key. The counter starts from zero in every session, which the epoch resets.
//   * The caller installs a session only after the secret is durably stored, so a power loss
//     can never leave the two sides disagreeing about which secret is current.

import { bytes, utf8, join, u32, u64, b64, unb64, sha256, hmac, equal } from './bytes.mjs'
import { HELLO_SIZE, ENROLL_SIZE } from './frames.mjs'

const DOMAIN = 'vitality-link-v1:'

/** A stored credential is always exactly 32 random bytes, base64 encoded without wrapping. */
export function validSecret(value) {
  try { return typeof value === 'string' && unb64(value).length === 32 } catch (_) { return false }
}

function master(secret) { return sha256(utf8(DOMAIN + secret)) }
function derive(key, label, epoch, nonce) { return hmac(key, join(utf8(label), u64(epoch), bytes(nonce))) }

/**
 * Verify a phone hello against the stored secret and the watch's current epoch.
 * Returns session keys, or null when the proof does not match.
 */
export function openSession(secret, epoch, hello) {
  const value = bytes(hello)
  if (value.length !== HELLO_SIZE) return null
  const key = master(secret), nonce = value.slice(0, 16)
  if (!equal(value.slice(16), hmac(hmac(key, utf8('hello')), join(u64(epoch), nonce)))) return null
  return { epoch, nonce, counter: 0,
    phoneEnc: derive(key, 'phone-enc', epoch, nonce),
    phoneMac: derive(key, 'phone-mac', epoch, nonce),
    watchEnc: derive(key, 'watch-enc', epoch, nonce),
    watchMac: derive(key, 'watch-mac', epoch, nonce),
    receiptKey: derive(key, 'receipt', epoch, nonce) }
}

/** The phone half of enrollment: 32 secret bytes followed by a hello computed on the new secret. */
export function splitEnrollment(payload) {
  const value = bytes(payload)
  if (value.length !== ENROLL_SIZE) return null
  return { secret: b64(value.slice(0, 32)), hello: value.slice(32) }
}

/** 13-byte tag that authenticates a receipt or greeting head. */
export function receiptTag(session, head) { return hmac(session.receiptKey, bytes(head)).slice(0, 13) }

/** Authenticate an encrypted phone body. Returns its IV and ciphertext, or null. */
export function openBody(session, id, body) {
  const value = bytes(body)
  if (value.length < 64 || (value.length - 16 - 32) % 16 !== 0) return null
  const data = value.slice(0, value.length - 32)
  if (!equal(value.slice(value.length - 32), hmac(session.phoneMac, join(u32(id), data)))) return null
  return { iv: data.slice(0, 16), cipher: data.slice(16) }
}

/** Derived, never repeated IV for the nth reply of this session. */
export function replyIv(session, id) { return hmac(session.watchMac, join(u64(++session.counter), u32(id))).slice(0, 16) }
/** MAC over the reply transfer id and its IV+ciphertext. */
export function replyTag(session, id, iv, cipher) { return hmac(session.watchMac, join(u32(id), bytes(iv), bytes(cipher))) }

export { b64, unb64 }
