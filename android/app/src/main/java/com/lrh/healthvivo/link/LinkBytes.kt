package com.lrh.healthvivo.link

import java.util.zip.CRC32

/**
 * Binary helpers shared by the phone side of the watch link.
 *
 * This file is deliberately free of Android APIs so the whole link protocol can be exercised by
 * ordinary JVM unit tests. Every encoding matches `watch/src/lib/link/bytes.mjs`; the
 * cross-language vectors in `src/test/resources/link-vectors.json` pin the two together.
 */
internal object LinkBytes {
    fun u16(value: Int) = byteArrayOf(((value ushr 8) and 0xff).toByte(), (value and 0xff).toByte())

    fun u32(value: Int) = byteArrayOf(
        ((value ushr 24) and 0xff).toByte(), ((value ushr 16) and 0xff).toByte(),
        ((value ushr 8) and 0xff).toByte(), (value and 0xff).toByte())

    fun u64(value: Long) = ByteArray(8) { index -> ((value ushr (56 - index * 8)) and 0xff).toByte() }

    fun read16(value: ByteArray, offset: Int) =
        ((value[offset].toInt() and 0xff) shl 8) or (value[offset + 1].toInt() and 0xff)

    fun read32(value: ByteArray, offset: Int): Int =
        ((value[offset].toInt() and 0xff) shl 24) or ((value[offset + 1].toInt() and 0xff) shl 16) or
            ((value[offset + 2].toInt() and 0xff) shl 8) or (value[offset + 3].toInt() and 0xff)

    fun read64(value: ByteArray, offset: Int): Long {
        var result = 0L
        for (index in 0 until 8) result = (result shl 8) or (value[offset + index].toLong() and 0xff)
        return result
    }

    fun concat(vararg parts: ByteArray): ByteArray {
        val output = ByteArray(parts.sumOf { it.size })
        var offset = 0
        for (part in parts) { part.copyInto(output, offset); offset += part.size }
        return output
    }

    /** CRC-32/ISO-HDLC, identical to `zlib.crc32` on the watch and to the JDK implementation. */
    fun crc32(value: ByteArray): Int {
        val crc = CRC32()
        crc.update(value)
        return crc.value.toInt()
    }

    fun hex(value: ByteArray) = value.joinToString("") { "%02x".format(it) }

    fun fromHex(value: String): ByteArray {
        require(value.length % 2 == 0) { "hex length" }
        return ByteArray(value.length / 2) { index -> value.substring(index * 2, index * 2 + 2).toInt(16).toByte() }
    }

    /** Length-independent comparison; never short-circuits on the first differing byte. */
    fun equal(left: ByteArray, right: ByteArray): Boolean {
        if (left.size != right.size) return false
        var difference = 0
        for (index in left.indices) difference = difference or (left[index].toInt() xor right[index].toInt())
        return difference == 0
    }
}
