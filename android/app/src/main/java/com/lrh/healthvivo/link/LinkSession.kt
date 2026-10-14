package com.lrh.healthvivo.link

/**
 * Thrown when the watch no longer recognises this session's keys — typically because it was
 * restarted, another phone took the link, or the user cleared the connection on the watch.
 * The manager treats this as "re-run the handshake on the same link", not as a Bluetooth fault.
 */
internal class LinkSessionLost(message: String) : IllegalArgumentException(message)

/**
 * Session establishment and message protection, phone half of `watch/src/lib/link/session.mjs`.
 *
 * The phone contributes a fresh random 16-byte nonce per connection and the watch contributes its
 * persistent, monotonically increasing epoch, so the key material is unique per session and a
 * replayed hello from an older connection can never be accepted.
 *
 * Reply IVs are not random: they are derived from a per-session counter, because a repeated IV
 * under the same AES key would be a real weakness and the phone can compute the sequence
 * deterministically. The counter starts at zero in every session.
 *
 * Nothing here touches Android APIs, so the whole handshake is covered by JVM unit tests.
 */
internal class LinkSession(secret: String, private val epoch: Long,
    nonce: ByteArray = ByteArray(16).also(java.security.SecureRandom()::nextBytes)) {

    private val random = java.security.SecureRandom()
    private val key = nonce.copyOf()
    private val master = sha256("vitality-link-v1:$secret".toByteArray(Charsets.UTF_8))
    private val sendKey = derive("phone-enc")
    private val sendMac = derive("phone-mac")
    private val receiveKey = derive("watch-enc")
    private val receiveMac = derive("watch-mac")
    private val receiptKey = derive("receipt")
    private var counter = 0

    /** 48 bytes: the nonce the watch will derive from, plus a proof computed on the stored secret. */
    val hello: ByteArray = key + hmac(hmac(master, "hello".toByteArray()), LinkBytes.u64(epoch) + key)

    /** 80 bytes: the credential the watch must persist, followed by a proof computed on it. */
    val enrollment: ByteArray = java.util.Base64.getDecoder().decode(secret) + hello

    private fun derive(label: String) = hmac(master, label.toByteArray(Charsets.UTF_8) + LinkBytes.u64(epoch) + key)

    /** iv || AES-256-CBC(PKCS5) || HMAC-SHA256(phoneMac, id || iv || ciphertext) */
    fun encrypt(text: String, id: Int, iv: ByteArray? = null): ByteArray {
        val vector = iv ?: ByteArray(16).also(random::nextBytes)
        require(vector.size == 16) { "初始向量长度错误" }
        val cipher = javax.crypto.Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(javax.crypto.Cipher.ENCRYPT_MODE, javax.crypto.spec.SecretKeySpec(sendKey, "AES"),
            javax.crypto.spec.IvParameterSpec(vector))
        val body = vector + cipher.doFinal(text.toByteArray(Charsets.UTF_8))
        return body + hmac(sendMac, LinkBytes.u32(id) + body)
    }

    /**
     * Verify, then verify the derived IV, then decrypt. Order matters: the watch must never see a
     * ciphertext whose MAC was not checked first.
     */
    fun decrypt(value: ByteArray, id: Int): String {
        require(value.size >= 64 && (value.size - 48) % 16 == 0) { "手表回应格式无效" }
        val body = value.copyOfRange(0, value.size - 32)
        val mac = value.copyOfRange(value.size - 32, value.size)
        if (!LinkBytes.equal(mac, hmac(receiveMac, LinkBytes.u32(id) + body))) throw LinkSessionLost("手表回应校验失败")
        val iv = hmac(receiveMac, LinkBytes.u64((++counter).toLong()) + LinkBytes.u32(id)).copyOf(16)
        if (!LinkBytes.equal(iv, body.copyOf(16))) throw LinkSessionLost("手表回应序号不一致")
        val cipher = javax.crypto.Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(javax.crypto.Cipher.DECRYPT_MODE, javax.crypto.spec.SecretKeySpec(receiveKey, "AES"),
            javax.crypto.spec.IvParameterSpec(iv))
        return cipher.doFinal(body.copyOfRange(16, body.size)).toString(Charsets.UTF_8)
    }

    /** Authenticate a 20-byte receipt against the same key the watch used to build it. */
    fun receipt(value: ByteArray): LinkProtocol.Receipt {
        require(value.size == LinkFrames.RECEIPT_SIZE) { "手表未返回会话状态" }
        val head = value.copyOf(7)
        if (!LinkBytes.equal(hmac(receiptKey, head).copyOf(13), value.copyOfRange(7, 20))) throw LinkSessionLost("手表会话已失效，正在重新连接")
        return LinkProtocol.Receipt(head[0].toInt() and 0xff, LinkBytes.read32(head, 1), LinkBytes.read16(head, 5))
    }

    private fun sha256(value: ByteArray) = java.security.MessageDigest.getInstance("SHA-256").digest(value)

    private fun hmac(key: ByteArray, value: ByteArray): ByteArray =
        javax.crypto.Mac.getInstance("HmacSHA256").run {
            init(javax.crypto.spec.SecretKeySpec(key, "HmacSHA256"))
            doFinal(value)
        }
}
