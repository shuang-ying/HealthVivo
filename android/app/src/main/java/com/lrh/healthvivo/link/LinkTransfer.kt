package com.lrh.healthvivo.link

import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout

/**
 * Framed transfer over one GATT connection.
 *
 * The rules that make the transfer reliable, and why:
 *   * every frame is written with an acknowledged write, so the watch has already accepted the
 *     bytes when the call returns;
 *   * the watch answers a write before it runs crypto or storage work, so a slow save shows up as
 *     a *receipt* that is still pending rather than a failed write;
 *   * a transfer is only complete when a receipt arrives whose transfer id and state both match,
 *     and that receipt is authenticated with the session key;
 *   * a reply body is pulled with explicit POLL frames and released with RELEASE, so the watch
 *     never has to buffer an unbounded amount of data.
 */
internal class LinkTransfer(private val gatt: LinkGatt, private val trace: (String) -> Unit = {}) {

    /** The phone always opens a connection by asking the watch to forget any previous session. */
    suspend fun reset() = gatt.write(LinkFrames.reset())

    /** Read the public greeting that tells us whether the watch already knows this phone. */
    suspend fun greet(): LinkProtocol.Greeting {
        var value = gatt.read()
        // A session left over from a previous connection can briefly answer with a receipt.
        repeat(GREETING_ATTEMPTS) {
            if (LinkProtocol.isGreeting(value)) return LinkProtocol.greeting(value)
            if (LinkProtocol.isFailure(value)) throw IllegalStateException(failureText(value))
            delay(POLL_INTERVAL)
            value = gatt.read()
        }
        throw IllegalStateException("手表未回应直连服务，请打开手表活力记录 0.7.0 或更新版本")
    }

    /** BEGIN + DATA chunks + END, each frame one acknowledged ATT write. */
    suspend fun send(id: Int, kind: Int, body: ByteArray, progress: ((Int) -> Unit)? = null) {
        require(body.size in 1..LinkFrames.MAX_BODY) { "日期摘要过大，无法传输" }
        val checksum = LinkBytes.crc32(body)
        gatt.write(LinkFrames.begin(id, kind, body.size, checksum))
        var offset = 0
        for (frame in LinkFrames.chunks(id, body)) {
            gatt.write(frame)
            offset += frame.size - LinkFrames.DATA_HEADER
            progress?.invoke(offset * 100 / body.size)
        }
        gatt.write(LinkFrames.end(id, checksum))
    }

    /**
     * Poll reads until the watch answers with the expected state for this transfer.
     * State FAILED is turned into an exception so the caller stops waiting immediately.
     */
    suspend fun await(session: LinkSession, id: Int, expected: Int, timeoutMs: Long = RECEIPT_TIMEOUT,
        onReceipt: (LinkProtocol.Receipt) -> Unit = {}): LinkProtocol.Receipt = withTimeout(timeoutMs) {
        while (true) {
            val value = gatt.read()
            if (LinkProtocol.isFailure(value)) throw IllegalStateException(failureText(value))
            if (LinkProtocol.isGreeting(value)) { delay(POLL_INTERVAL); continue }
            if (LinkProtocol.isReplyPart(value)) { delay(POLL_INTERVAL); continue }
            val receipt = session.receipt(value)
            onReceipt(receipt)
            if (receipt.id == id && receipt.state == expected) return@withTimeout receipt
            if (receipt.id == id && receipt.state == LinkFrames.STATE_FAILED) {
                throw IllegalStateException(stageText(receipt.value))
            }
            if (receipt.id == id && receipt.state == LinkFrames.STATE_REJECTED) {
                throw IllegalStateException("手表拒绝了本次连接，请在手表连接管理清除旧手机连接")
            }
            delay(POLL_INTERVAL)
        }
        @Suppress("UNREACHABLE_CODE") error("会话超时")
    }

    /** Pull a reply body with POLL frames, then release the watch-side buffer. */
    suspend fun reply(session: LinkSession, receipt: LinkProtocol.Receipt): ByteArray {
        val size = receipt.value
        require(size in 1..LinkFrames.MAX_REPLY) { "手表日期索引回应过大" }
        val buffer = ByteArray(size)
        var offset = 0
        while (offset < size) {
            val size2 = minOf(LinkFrames.REPLY_PAYLOAD, size - offset)
            gatt.write(LinkFrames.poll(receipt.id, offset, size2))
            val chunk = LinkProtocol.replyChunk(gatt.read(), receipt.id, offset, size)
            chunk.copyInto(buffer, offset)
            offset += chunk.size
        }
        gatt.write(LinkFrames.release(receipt.id))
        trace("正在读取手表索引 ${size} 字节")
        return buffer
    }

    /** A cheap authenticated liveness check: the watch echoes the transfer id with state READY. */
    suspend fun probe(session: LinkSession, id: Int): LinkProtocol.Receipt {
        send(id, LinkFrames.KIND_BODY, session.encrypt("""{"type":"ping"}""", id))
        return await(session, id, LinkFrames.STATE_READY, PROBE_TIMEOUT)
    }

    /**
     * Turn a stage code into something a user can act on. Stage 2 is the one that needs an action
     * on the watch, so it says so explicitly instead of looking like a generic failure.
     */
    private fun stageText(stage: Int) = when (stage) {
        1 -> "手表无法接收本次数据（分段或长度无效）"
        2 -> "手表不认识本机的连接凭据，请在手表“连接管理”清除旧手机连接后重试"
        3 -> "手表保存连接信息失败，请重新打开手表活力记录"
        4 -> "手表校验数据失败，已保存的日期仍保留"
        5 -> "手表解密数据失败，已保存的日期仍保留"
        6 -> "手表保存日期失败，已保存的日期仍保留"
        7 -> "手表构造索引回应失败，已保存的日期仍保留"
        else -> "手表拒绝了本次操作（阶段 $stage）"
    }

    private fun failureText(value: ByteArray) = stageText(LinkProtocol.failure(value).stage)

    companion object {
        const val RECEIPT_TIMEOUT = 45000L
        const val PROBE_TIMEOUT = 15000L
        const val POLL_INTERVAL = 250L
        const val GREETING_ATTEMPTS = 8
    }
}
