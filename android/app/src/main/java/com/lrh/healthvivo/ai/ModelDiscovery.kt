package com.lrh.healthvivo.ai

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

fun interface ModelsTransport { fun get(settings: ApiSettings, url: String): String }
class HttpsModelsTransport : ModelsTransport {
    override fun get(settings: ApiSettings, url: String): String {
        settings.validateConnection()
        // The key must only go to the same URL derived from the user-entered endpoint.
        require(url == settings.modelsUrl())
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "GET"; connection.instanceFollowRedirects = false
            connection.connectTimeout = 10000; connection.readTimeout = 15000
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("Authorization", "Bearer ${settings.key}")
            val status = connection.responseCode
            require(status in 200..299) { when(status) {
                401, 403 -> "密钥或模型列表权限无效，请检查配置"
                404, 405 -> "此服务未开放模型列表，可手动填写模型"
                429 -> "模型检测请求受限，请稍后重试"
                in 300..399 -> "模型地址重定向，请填写服务商的最终地址"
                else -> "模型列表返回 HTTP $status，可手动填写模型"
            } }
            return connection.inputStream.use { input ->
                val output = java.io.ByteArrayOutputStream(); val buffer = ByteArray(8192)
                while(true) { val count = input.read(buffer); if(count < 0) break; require(output.size() + count <= 1024 * 1024) { "模型列表过大，请手动填写模型" }; output.write(buffer, 0, count) }
                output.toByteArray().toString(Charsets.UTF_8)
            }
        } finally { connection.disconnect() }
    }
}
class ModelDiscovery(private val transport: ModelsTransport = HttpsModelsTransport()) {
    suspend fun discover(settings: ApiSettings): List<String> = withContext(Dispatchers.IO) {
        settings.validateConnection()
        decode(transport.get(settings, settings.modelsUrl()))
    }
    fun decode(response: String): List<String> {
        val data = JSONObject(response).getJSONArray("data")
        require(data.length() <= 5000) { "模型列表过大，请手动填写" }
        val models = (0 until data.length()).mapNotNull { index ->
            val id = data.getJSONObject(index).opt("id")
            (id as? String)?.trim()?.takeIf { it.isNotBlank() && it.length <= 200 && it.none { char -> char.isISOControl() } }
        }.distinct().sorted()
        require(models.isNotEmpty()) { "没有检测到模型，请检查密钥权限或手动填写" }
        return models
    }
}
