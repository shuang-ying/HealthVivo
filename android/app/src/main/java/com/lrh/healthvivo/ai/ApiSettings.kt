package com.lrh.healthvivo.ai

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.net.URI
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

data class ApiSettings(val endpoint: String = "", val model: String = "", val key: String = "") {
    fun validateConnection() {
        validateUrl(endpoint)
        require(key.isNotBlank() && key.length <= 4096 && !key.contains('\n') && !key.contains('\r')) { "请填写有效 API 密钥" }
    }
    fun validate() {
        validateConnection()
        require(model.isNotBlank() && model.length <= 200 && model.none { it.isISOControl() }) { "请选择或填写有效模型名称" }
    }
    fun normalized() = copy(endpoint = chatUrl(endpoint), model = model.trim(), key = key.trim())
    fun modelsUrl(): String = chatUrl(endpoint).removeSuffix("/chat/completions") + "/models"
    companion object {
        fun validateUrl(value: String) {
            val uri = runCatching { URI(value.trim()) }.getOrNull()
            require(uri != null && uri.scheme == "https" && !uri.host.isNullOrBlank() && uri.userInfo == null && uri.fragment == null && uri.query == null && value.length <= 2048) { "请填写 HTTPS 服务地址，不包含密钥或查询参数" }
        }
        fun chatUrl(value: String): String {
            validateUrl(value)
            val trimmed = value.trim().trimEnd('/')
            val uri = URI(trimmed)
            return when {
                uri.path.endsWith("/chat/completions") -> trimmed
                uri.path.isBlank() -> "$trimmed/v1/chat/completions"
                else -> "$trimmed/chat/completions"
            }
        }
    }
}
class ApiSettingsStore(context: Context, preferencesName: String = "recognition_api", private val alias: String = "vitality-recognition-api") {
    private val preferences = context.getSharedPreferences(preferencesName, Context.MODE_PRIVATE)
    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
    fun load(): ApiSettings {
        val secret = runCatching {
            val saved = preferences.getString("secret", null) ?: return@runCatching ""
            val iv = preferences.getString("iv", null) ?: return@runCatching ""
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, Base64.decode(iv, Base64.NO_WRAP)))
            String(cipher.doFinal(Base64.decode(saved, Base64.NO_WRAP)), Charsets.UTF_8)
        }.getOrDefault("")
        return ApiSettings(preferences.getString("endpoint", "") ?: "", preferences.getString("model", "") ?: "", secret)
    }
    fun save(settings: ApiSettings) {
        val normalized = settings.normalized().also { it.validate() }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        val encrypted = cipher.doFinal(normalized.key.toByteArray(Charsets.UTF_8))
        check(preferences.edit().putString("endpoint", normalized.endpoint).putString("model", normalized.model)
            .putString("iv", Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
            .putString("secret", Base64.encodeToString(encrypted, Base64.NO_WRAP)).commit()) { "API 配置保存失败" }
    }
    fun clear() { check(preferences.edit().clear().commit()) }
}
