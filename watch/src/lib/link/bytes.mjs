// Portable binary and hash helpers for the watch link layer.
//
// BlueOS exposes AES through the native cipher module but no JavaScript hashing API,
// so SHA-256 and HMAC-SHA256 are implemented here in plain JavaScript. Everything in
// this module is pure: no native modules, no timers, no global state.

const K = [0x428a2f98, 0x71374491, 0xb5c0fbcf, 0xe9b5dba5, 0x3956c25b, 0x59f111f1, 0x923f82a4, 0xab1c5ed5,
  0xd807aa98, 0x12835b01, 0x243185be, 0x550c7dc3, 0x72be5d74, 0x80deb1fe, 0x9bdc06a7, 0xc19bf174,
  0xe49b69c1, 0xefbe4786, 0x0fc19dc6, 0x240ca1cc, 0x2de92c6f, 0x4a7484aa, 0x5cb0a9dc, 0x76f988da,
  0x983e5152, 0xa831c66d, 0xb00327c8, 0xbf597fc7, 0xc6e00bf3, 0xd5a79147, 0x06ca6351, 0x14292967,
  0x27b70a85, 0x2e1b2138, 0x4d2c6dfc, 0x53380d13, 0x650a7354, 0x766a0abb, 0x81c2c92e, 0x92722c85,
  0xa2bfe8a1, 0xa81a664b, 0xc24b8b70, 0xc76c51a3, 0xd192e819, 0xd6990624, 0xf40e3585, 0x106aa070,
  0x19a4c116, 0x1e376c08, 0x2748774c, 0x34b0bcb5, 0x391c0cb3, 0x4ed8aa4a, 0x5b9cca4f, 0x682e6ff3,
  0x748f82ee, 0x78a5636f, 0x84c87814, 0x8cc70208, 0x90befffa, 0xa4506ceb, 0xbef9a3f7, 0xc67178f2]
const ALPHABET = 'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/'

/** GATT callbacks deliver ArrayBuffer on the watch, Uint8Array in tests and on some firmware. */
export function bytes(value) {
  if (value instanceof Uint8Array) return value
  if (value instanceof ArrayBuffer) return new Uint8Array(value)
  if (value && value.buffer instanceof ArrayBuffer && typeof value.byteLength === 'number') {
    return new Uint8Array(value.buffer, value.byteOffset, value.byteLength)
  }
  if (Array.isArray(value)) return new Uint8Array(value)
  throw new Error('Expected a binary GATT payload')
}

/**
 * UTF-8 encode a string using nothing but `charCodeAt`.
 *
 * The packer injects a fixed set of host globals into each module (console, BroadcastChannel and
 * the four timer functions) and the engine supplies the rest of ECMAScript. The legacy `escape` /
 * `unescape` pair is Annex B rather than core ECMAScript, so it is not relied on here — this used
 * to be `unescape(encodeURIComponent(...))`, which would have broken the handshake on an engine
 * without it.
 */
export function utf8(value) {
  const input = String(value)
  const out = []
  for (let i = 0; i < input.length; i++) {
    let code = input.charCodeAt(i)
    if (code >= 0xd800 && code <= 0xdbff && i + 1 < input.length) {
      const low = input.charCodeAt(i + 1)
      if (low >= 0xdc00 && low <= 0xdfff) { code = 0x10000 + ((code - 0xd800) << 10) + (low - 0xdc00); i++ }
    }
    if (code < 0x80) out.push(code)
    else if (code < 0x800) out.push(0xc0 | (code >> 6), 0x80 | (code & 63))
    else if (code < 0x10000) out.push(0xe0 | (code >> 12), 0x80 | ((code >> 6) & 63), 0x80 | (code & 63))
    else out.push(0xf0 | (code >> 18), 0x80 | ((code >> 12) & 63), 0x80 | ((code >> 6) & 63), 0x80 | (code & 63))
  }
  return new Uint8Array(out)
}

export function text(value) {
  const input = bytes(value)
  let encoded = ''
  for (let i = 0; i < input.length; i++) encoded += '%' + ('0' + input[i].toString(16)).slice(-2)
  return decodeURIComponent(encoded)
}

export function join() {
  const arrays = Array.prototype.slice.call(arguments).map(bytes)
  const output = new Uint8Array(arrays.reduce((sum, array) => sum + array.length, 0))
  let offset = 0
  arrays.forEach(array => { output.set(array, offset); offset += array.length })
  return output
}

export function u16(value) { const out = new Uint8Array(2); new DataView(out.buffer).setUint16(0, value & 0xffff); return out }
export function u32(value) { const out = new Uint8Array(4); new DataView(out.buffer).setUint32(0, value >>> 0); return out }

/**
 * 64-bit big-endian, built from two 32-bit halves on purpose.
 *
 * The BlueOS runtime is a polyfilled engine: the previously working watch transport also avoided
 * BigInt, and `DataView.setBigUint64` / the `BigInt` global are not guaranteed here. Every value
 * this protocol puts in 64 bits (an epoch or a message counter) is far below 2^53, so splitting the
 * number is exact and produces byte-for-byte the same encoding.
 */
export function u64(value) {
  const out = new Uint8Array(8)
  const high = Math.floor(value / 4294967296), low = value - high * 4294967296
  out[0] = (high >>> 24) & 255; out[1] = (high >>> 16) & 255; out[2] = (high >>> 8) & 255; out[3] = high & 255
  out[4] = (low >>> 24) & 255; out[5] = (low >>> 16) & 255; out[6] = (low >>> 8) & 255; out[7] = low & 255
  return out
}
export function read16(value, offset) { return new DataView(value.buffer, value.byteOffset, value.byteLength).getUint16(offset) }
export function read32(value, offset) { return new DataView(value.buffer, value.byteOffset, value.byteLength).getUint32(offset) }
/**
 * The inverse of u64. Both sides validate the range (1 .. 2^53-1) after reading, so an
 * out-of-range field is rejected rather than silently rounded.
 */
export function read64(value, offset) { return (read32(value, offset) >>> 0) * 4294967296 + (read32(value, offset + 4) >>> 0) }

export function b64(value) {
  const input = bytes(value); let out = ''
  for (let i = 0; i < input.length; i += 3) {
    const word = (input[i] << 16) | ((input[i + 1] || 0) << 8) | (input[i + 2] || 0)
    out += ALPHABET[(word >>> 18) & 63] + ALPHABET[(word >>> 12) & 63] +
      (i + 1 < input.length ? ALPHABET[(word >>> 6) & 63] : '=') + (i + 2 < input.length ? ALPHABET[word & 63] : '=')
  }
  return out
}

export function unb64(value) {
  if (typeof value !== 'string' || value.length % 4 || !/^[A-Za-z0-9+/]*={0,2}$/.test(value)) throw new Error('Invalid base64')
  const out = []; let word = 0, count = 0
  for (let i = 0; i < value.length && value[i] !== '='; i++) {
    word = (word << 6) | ALPHABET.indexOf(value[i]); count += 6
    if (count >= 8) { count -= 8; out.push((word >>> count) & 255) }
  }
  return new Uint8Array(out)
}

export function sha256(value) {
  const input = bytes(value), length = Math.ceil((input.length + 9) / 64) * 64
  const data = new Uint8Array(length); data.set(input); data[input.length] = 128
  const view = new DataView(data.buffer); view.setUint32(length - 4, input.length * 8)
  const hash = [0x6a09e667, 0xbb67ae85, 0x3c6ef372, 0xa54ff53a, 0x510e527f, 0x9b05688c, 0x1f83d9ab, 0x5be0cd19]
  const rotate = (n, amount) => (n >>> amount) | (n << (32 - amount))
  for (let offset = 0; offset < length; offset += 64) {
    const w = new Uint32Array(64)
    for (let i = 0; i < 16; i++) w[i] = view.getUint32(offset + i * 4)
    for (let i = 16; i < 64; i++) {
      const a = w[i - 15], b = w[i - 2]
      w[i] = (w[i - 16] + (rotate(a, 7) ^ rotate(a, 18) ^ (a >>> 3)) + w[i - 7] + (rotate(b, 17) ^ rotate(b, 19) ^ (b >>> 10))) >>> 0
    }
    let a = hash[0], b = hash[1], c = hash[2], d = hash[3], e = hash[4], f = hash[5], g = hash[6], h = hash[7]
    for (let i = 0; i < 64; i++) {
      const t1 = (h + (rotate(e, 6) ^ rotate(e, 11) ^ rotate(e, 25)) + ((e & f) ^ ((~e) & g)) + K[i] + w[i]) >>> 0
      const t2 = ((rotate(a, 2) ^ rotate(a, 13) ^ rotate(a, 22)) + ((a & b) ^ (a & c) ^ (b & c))) >>> 0
      h = g; g = f; f = e; e = (d + t1) >>> 0; d = c; c = b; b = a; a = (t1 + t2) >>> 0
    }
    const next = [a, b, c, d, e, f, g, h]; for (let i = 0; i < 8; i++) hash[i] = (hash[i] + next[i]) >>> 0
  }
  const output = new Uint8Array(32), result = new DataView(output.buffer)
  hash.forEach((word, i) => result.setUint32(i * 4, word))
  return output
}

export function hmac(key, value) {
  let block = bytes(key); if (block.length > 64) block = sha256(block)
  const inner = new Uint8Array(64), outer = new Uint8Array(64)
  for (let i = 0; i < 64; i++) { inner[i] = (block[i] || 0) ^ 0x36; outer[i] = (block[i] || 0) ^ 0x5c }
  return sha256(join(outer, sha256(join(inner, value))))
}

/** Length-independent comparison; never short-circuits on the first differing byte. */
export function equal(a, b) {
  const left = bytes(a), right = bytes(b)
  if (left.length !== right.length) return false
  let difference = 0
  for (let i = 0; i < left.length; i++) difference |= left[i] ^ right[i]
  return difference === 0
}

export function hex(value) { return Array.prototype.map.call(bytes(value), n => ('0' + n.toString(16)).slice(-2)).join('') }

const CRC_TABLE = (() => {
  const table = new Uint32Array(256)
  for (let n = 0; n < 256; n++) {
    let c = n
    for (let k = 0; k < 8; k++) c = c & 1 ? 0xedb88320 ^ (c >>> 1) : c >>> 1
    table[n] = c >>> 0
  }
  return table
})()

/** CRC-32/ISO-HDLC (the zlib/PNG polynomial), matching java.util.zip.CRC32. */
export function crc32(value) {
  const input = bytes(value)
  let crc = 0xffffffff
  for (let i = 0; i < input.length; i++) crc = CRC_TABLE[(crc ^ input[i]) & 255] ^ (crc >>> 8)
  return (crc ^ 0xffffffff) >>> 0
}
