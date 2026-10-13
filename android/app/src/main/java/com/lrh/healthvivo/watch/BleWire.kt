package com.lrh.healthvivo.watch

import android.util.Base64
import org.json.JSONObject
import java.nio.ByteBuffer
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.spec.MGF1ParameterSpec
import java.security.spec.X509EncodedKeySpec
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.OAEPParameterSpec
import javax.crypto.spec.PSource
import javax.crypto.spec.SecretKeySpec

/** CBC encryption + encrypt-then-HMAC, matching the cipher modes exposed by BlueOS. */
internal class BleWire(publicKey: String) {
    private val random = SecureRandom()
    private val key = ByteArray(32).also(random::nextBytes)
    private val nonce = ByteArray(16).also(random::nextBytes)
    private fun b64(bytes: ByteArray) = Base64.encodeToString(bytes, Base64.NO_WRAP)
    private val keyText = b64(key)
    private val nonceText = b64(nonce)
    private val macKey = MessageDigest.getInstance("SHA-256").digest("$keyText:vitality-mac:$nonceText".toByteArray(Charsets.UTF_8))
    val code = MessageDigest.getInstance("SHA-256").digest("$keyText:$nonceText".toByteArray(Charsets.UTF_8)).take(3).joinToString("") { "%02X".format(it) }
    val hello: ByteArray = Cipher.getInstance("RSA/ECB/OAEPPadding").run {
        val identity = KeyFactory.getInstance("RSA").generatePublic(X509EncodedKeySpec(Base64.decode(publicKey, Base64.DEFAULT)))
        init(Cipher.ENCRYPT_MODE, identity, OAEPParameterSpec("SHA-256", "MGF1", MGF1ParameterSpec.SHA256, PSource.PSpecified.DEFAULT))
        doFinal(JSONObject().put("key", keyText).put("nonce", nonceText).toString().toByteArray(Charsets.UTF_8))
    }
    fun id(): Int = random.nextInt().let { if(it == 0) 1 else it }
    private fun mac(bytes: ByteArray): ByteArray = Mac.getInstance("HmacSHA256").run { init(SecretKeySpec(macKey, "HmacSHA256")); doFinal(bytes) }
    fun encrypt(text: String, id: Int): ByteArray {
        val iv = ByteArray(16).also(random::nextBytes)
        val encrypted = Cipher.getInstance("AES/CBC/PKCS5Padding").run { init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv)); doFinal(text.toByteArray(Charsets.UTF_8)) }
        val body = iv + encrypted
        return body + mac(ByteBuffer.allocate(4).putInt(id).array() + body)
    }
    data class Receipt(val state: Int, val id: Int, val progress: Int)
    fun receipt(bytes: ByteArray): Receipt {
        require(bytes.size == 20) { "手表未返回有效的加密会话状态" }
        val body = bytes.copyOfRange(0, 7)
        require(MessageDigest.isEqual(mac(body).copyOf(13), bytes.copyOfRange(7, 20))) { "手机与手表会话校验未通过" }
        return Receipt(body[0].toInt() and 255, ByteBuffer.wrap(body, 1, 4).int, (body[5].toInt() and 255) * 256 + (body[6].toInt() and 255))
    }
    companion object {
        const val MAX_PAYLOAD = 200 * 1024
        fun begin(id: Int, kind: Int, length: Int) = ByteBuffer.allocate(10).put(1).putInt(id).put(kind.toByte()).putInt(length).array()
        fun part(id: Int, offset: Int, bytes: ByteArray) = ByteBuffer.allocate(9 + bytes.size).put(2).putInt(id).putInt(offset).put(bytes).array()
        fun end(id: Int) = ByteBuffer.allocate(5).put(3).putInt(id).array()
    }
}
