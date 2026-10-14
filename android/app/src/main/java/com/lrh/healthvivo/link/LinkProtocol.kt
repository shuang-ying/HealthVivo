package com.lrh.healthvivo.link

/**
 * Public wire objects of the watch link: the greeting a phone sees before a session exists, the
 * 20-byte authenticated receipt it polls afterwards, and the chunked reply reader.
 *
 * Kept separate from [LinkSession] because these are pure parsing rules with no key material.
 */
internal object LinkProtocol {

    /** The 20 bytes the watch answers with while no session is bound to this phone. */
    data class Greeting(val epoch: Long, val enrolled: Boolean, val reads: Int, val writes: Int, val surface: Int) {
        val paused get() = surface and 2 != 0
        val requestPending get() = surface and 1 != 0
    }

    /** A session receipt. `value` is a stage code on failure, a length in [STATE_REPLY], else progress. */
    data class Receipt(val state: Int, val id: Int, val rawFlags: Int) {
        val requested get() = rawFlags and LinkFrames.REQUEST_FLAG != 0
        val value get() = rawFlags and LinkFrames.FLAG_MASK
    }

    /** A two-byte handshake rejection: mark, then a stage code that never carries secrets. */
    class Failure(val stage: Int)

    fun isGreeting(value: ByteArray) = value.size == LinkFrames.GREETING_SIZE && value[0].toInt() and 0xff == LinkFrames.GREETING_MARK

    fun isReplyPart(value: ByteArray) = value.size > LinkFrames.REPLY_HEADER && value[0].toInt() and 0xff == LinkFrames.REPLY_MARK

    fun isFailure(value: ByteArray) = value.size == 2 && value[0].toInt() and 0xff == LinkFrames.FAILURE_MARK

    /** A receipt is 20 bytes like a greeting, so the mark byte is what tells them apart. */
    fun isReceipt(value: ByteArray) =
        value.size == LinkFrames.RECEIPT_SIZE && value[0].toInt() and 0xff != LinkFrames.GREETING_MARK

    fun failure(value: ByteArray) = Failure(value[1].toInt() and 0xff)

    /**
     * Parse the public greeting. A version mismatch is reported with a message the phone UI can
     * show directly, because the only remedy is updating the watch app.
     */
    fun greeting(value: ByteArray): Greeting {
        require(value.size == LinkFrames.GREETING_SIZE && value[0].toInt() and 0xff == LinkFrames.GREETING_MARK) {
            "手表未回应直连服务，请打开手表活力记录"
        }
        require(value[1].toInt() and 0xff == LinkFrames.PROTOCOL_VERSION) { "请将手表活力记录更新到 0.7.0 或更新版本" }
        require(value[2].toInt() in 0..1) { "手表连接状态无效" }
        val epoch = LinkBytes.read64(value, 3)
        require(epoch in 1..9007199254740991L) { "手表连接版本无效" }
        return Greeting(epoch, value[2].toInt() == 1, LinkBytes.read32(value, 11), LinkBytes.read32(value, 15), value[19].toInt() and 0xff)
    }

    /** Read the payload of one reply chunk, validating the mark and the running offset. */
    fun replyChunk(value: ByteArray, id: Int, offset: Int, total: Int): ByteArray {
        require(value.size in LinkFrames.REPLY_HEADER + 1..LinkFrames.ATT_PAYLOAD) { "手表索引分段长度无效" }
        require(value[0].toInt() and 0xff == LinkFrames.REPLY_MARK) { "手表索引分段标记无效" }
        require(LinkBytes.read32(value, 1) == id) { "手表索引分段编号不一致" }
        require(LinkBytes.read32(value, 5) == offset) { "手表索引分段偏移不一致" }
        val payload = value.copyOfRange(LinkFrames.REPLY_HEADER, value.size)
        require(offset + payload.size <= total) { "手表索引分段超出范围" }
        return payload
    }
}
