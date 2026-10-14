package com.lrh.healthvivo.link

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Connection state and per-watch credentials for the link.
 *
 * The credential is a 32-byte secret the phone generates once per watch. It is stored wrapped by
 * an Android Keystore AES-GCM key, so the plaintext never reaches the app's private files. The
 * watch receives it during the first handshake and proves possession of it on every later one.
 *
 * All storage keys are new (the previous direct-link protocol used different ones), because a
 * credential is only meaningful together with the protocol that derives keys from it.
 */
internal class LinkStore(context: Context) {
    val preferences: SharedPreferences = context.getSharedPreferences("link_connection", Context.MODE_PRIVATE)
    private val secrets = context.getSharedPreferences("link_secret", Context.MODE_PRIVATE)
    private val alias = "vitality-link-watch-v1"

    private fun entry(address: String) = "auto.v1." + MessageDigest.getInstance("SHA-256")
        .digest(address.toByteArray()).joinToString("") { "%02x".format(it) }

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
            generateKey()
        }
    }

    private fun read(address: String): String? {
        val name = entry(address)
        val raw = secrets.getString("$name.secret", null) ?: return null
        val iv = secrets.getString("$name.iv", null) ?: error("手机连接信息不可读，请重新连接手表")
        val value = Cipher.getInstance("AES/GCM/NoPadding").run {
            init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, Base64.decode(iv, Base64.NO_WRAP)))
            doFinal(Base64.decode(raw, Base64.NO_WRAP)).toString(Charsets.UTF_8)
        }
        require(Base64.decode(value, Base64.NO_WRAP).size == 32) { "手机连接信息格式无效" }
        return value
    }

    /** Returns the stored credential, generating and persisting one on first use. */
    fun getOrCreate(address: String): String {
        read(address)?.let { return it }
        val value = Base64.encodeToString(ByteArray(32).also(SecureRandom()::nextBytes), Base64.NO_WRAP)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        val encrypted = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        val name = entry(address)
        check(secrets.edit().putString("$name.iv", Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
            .putString("$name.secret", Base64.encodeToString(encrypted, Base64.NO_WRAP)).commit()) { "手机连接信息保存失败" }
        return value
    }

    /** Forget one watch's credential; used when the watch reports that it no longer knows us. */
    fun clear(address: String) {
        val name = entry(address)
        secrets.edit().remove("$name.secret").remove("$name.iv").apply()
    }

    val address: String get() = preferences.getString("address", "").orEmpty()
    val name: String get() = preferences.getString("name", "").orEmpty()
    val enabled: Boolean get() = preferences.getBoolean("enabled", false)
    val request: Long get() = preferences.getLong("request", 0)
    val completed: Long get() = preferences.getLong("completed", 0)
    val lastSavedAt: Long get() = preferences.getLong("lastSavedAt", 0)
    val lastSavedDay: String get() = preferences.getString("lastSavedDay", "").orEmpty()
    val pending: Boolean get() = request > completed

    /**
     * Select a watch. A different address must not inherit the previous watch's completion
     * markers, otherwise a queued update would look already delivered.
     */
    fun select(address: String, name: String): Boolean {
        val changed = this.address != address
        val edit = preferences.edit().putString("address", address).putString("name", name)
        if (changed) edit.remove("lastSavedAt").remove("lastSavedDay")
        return edit.commit()
    }

    fun setEnabled(value: Boolean) = preferences.edit().putBoolean("enabled", value).apply()

    /** Register a manual update request. Monotonic so a stale request can never look newer. */
    fun requestUpdate(): Long {
        val next = maxOf(System.currentTimeMillis(), request + 1)
        check(preferences.edit().putLong("request", next).commit()) { "更新请求保存失败，请重试" }
        return next
    }

    fun completeUpdate(request: Long, at: Long, day: String) = preferences.edit()
        .putLong("completed", request).putLong("lastSavedAt", at).putString("lastSavedDay", day).commit()
}
