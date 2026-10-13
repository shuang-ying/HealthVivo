package com.lrh.healthvivo.watch

import android.content.Context
import com.lrh.healthvivo.ai.DailyAdviceCoordinator
import com.lrh.healthvivo.fitness.FitnessRepository
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.LocalDate

data class OrbitBridgeStatus(val active: Boolean = false, val expiresAt: Long = 0,
    val message: String = "开启本机同步，再到 OrbitV 打开活力记录同步扩展程序。",
    val lastSavedAt: Long = 0, val lastSavedDay: String = "",
    val extensionConnected: Boolean = false, val transportReady: Boolean = false,
    val watchAppConnected: Boolean = false, val watchVersion: String = "")

/** User-enabled loopback connection; health transfer still requires a manual update command. */
class OrbitLocalBridge(context: Context, private val repository: FitnessRepository, private val advice: DailyAdviceCoordinator) {
    companion object { const val PORT = 28341; const val SESSION_MS = 15 * 60 * 1000L; const val HEARTBEAT_MS = 20_000L }
    private val preferences = context.getSharedPreferences("orbitv_companion", Context.MODE_PRIVATE)
    private val token = preferences.getString("token", null) ?: ByteArray(32).also { SecureRandom().nextBytes(it) }
        .joinToString("") { "%02x".format(it) }.also { preferences.edit().putString("token", it).apply() }
    private val mutable = MutableStateFlow(OrbitBridgeStatus(lastSavedAt = preferences.getLong("savedAt", 0),
        lastSavedDay = preferences.getString("savedDay", "").orEmpty()))
    val status = mutable.asStateFlow()
    fun connectionEnabled() = preferences.getBoolean("connectionEnabled", false)
    fun setConnectionEnabled(enabled: Boolean) { preferences.edit().putBoolean("connectionEnabled", enabled).apply() }
    @Volatile private var extensionSeen = 0L
    @Volatile private var watchSeen = 0L
    private var server: ServerSocket? = null
    private var job: Job? = null
    private var activeSocket: Socket? = null
    private val delta = WatchDeltaSource(repository, advice, preferences)
    private data class Session(val id: String, val days: List<JSONObject>, val command: Long, val created: Long = System.currentTimeMillis(), val acknowledged: MutableSet<String> = mutableSetOf())
    private data class Receipt(val session: Session, val days: List<JSONObject>, var accepted: Boolean = false)
    private val sessions = linkedMapOf<String, Session>()
    private val receipts = linkedMapOf<String, Receipt>()
    @Synchronized fun requestUpdate(): Long {
        val command = maxOf(System.currentTimeMillis(), preferences.getLong("requestId", 0) + 1)
        check(preferences.edit().putLong("requestId", command).commit())
        mutable.value = status.value.copy(message = if(status.value.extensionConnected && status.value.transportReady && status.value.watchAppConnected)
            "更新已请求，等待手表保存确认。" else "更新已排队，连接就绪后才传输，请打开扩展程序与手表应用。")
        return command
    }
    private fun control() = JSONObject().put("requestId", preferences.getLong("requestId", 0))
        .put("lastSavedAt", status.value.lastSavedAt).put("lastSavedDay", status.value.lastSavedDay)
        .put("phoneVersion", com.lrh.healthvivo.BuildConfig.VERSION_NAME).put("bodyAxes", true)
        .put("extensionConnected", status.value.extensionConnected).put("transportReady", status.value.transportReady)
        .put("watchAppConnected", status.value.watchAppConnected).put("watchVersion", status.value.watchVersion)
        .put("message", status.value.message)
    private fun heartbeat(value: JSONObject) {
        val now = System.currentTimeMillis(); extensionSeen = now
        val transport = value.optBoolean("transportReady", false)
        if(!transport) watchSeen = 0
        else if(value.optBoolean("watchAppReady", false)) watchSeen = now
        val version = value.optString("watchVersion").takeIf { it.matches(Regex("[0-9]+\\.[0-9]+\\.[0-9]+")) }
        mutable.value = status.value.copy(extensionConnected = true, transportReady = transport,
            watchAppConnected = transport && watchSeen > 0 && now - watchSeen < HEARTBEAT_MS,
            watchVersion = version ?: status.value.watchVersion)
    }
    private fun expireHeartbeats() {
        val now = System.currentTimeMillis(); val extension = extensionSeen > 0 && now - extensionSeen < HEARTBEAT_MS
        mutable.value = status.value.copy(extensionConnected = extension,
            transportReady = extension && status.value.transportReady,
            watchAppConnected = extension && status.value.transportReady && watchSeen > 0 && now - watchSeen < HEARTBEAT_MS)
    }
    private suspend fun sync(request: JSONObject): JSONObject {
        sessions.entries.removeAll { System.currentTimeMillis() - it.value.created > SESSION_MS }
        val session = if(request.has("sessionId")) sessions[request.getString("sessionId")] ?: error("同步会话已到期")
            else {
                val command = preferences.getLong("requestId", 0)
                Session(java.util.UUID.randomUUID().toString(), delta.changed(request.optJSONObject("known") ?: JSONObject()), command).also {
                sessions[it.id] = it; while(sessions.size > 4) sessions.remove(sessions.keys.first())
                }
            }
        val start = request.optInt("cursor", 0); require(start in 0..session.days.size)
        val page = mutableListOf<JSONObject>(); var bytes = 0; var cursor = start
        while(cursor < session.days.size && page.size < 12) {
            val item = session.days[cursor]; val size = item.toString().toByteArray(Charsets.UTF_8).size
            require(size <= 96 * 1024) { "单日摘要过大" }
            if(page.isNotEmpty() && bytes + size > 48 * 1024) break
            page.add(item); bytes += size; cursor++
        }
        val id = java.util.UUID.randomUUID().toString(); receipts[id] = Receipt(session, page)
        while(receipts.size > 32) receipts.remove(receipts.keys.first())
        return JSONObject().put("schema", 3).put("sessionId", session.id).put("receiptId", id).put("command", session.command)
            .put("days", org.json.JSONArray(page)).put("more", cursor < session.days.size).put("cursor", cursor).put("total", session.days.size)
    }
    fun pairingConfiguration(): String = JSONObject().put("schema", 1).put("appId", WatchSnapshot.CLIENT)
        .put("baseUrl", "http://127.0.0.1:$PORT").put("token", token).toString()

    fun start(scope: CoroutineScope, expired: () -> Unit) {
        // Repeated update taps must not close a socket or invalidate an in-flight page.
        if(status.value.active && job?.isActive == true && server?.isClosed == false) return
        try {
            val socket = ServerSocket().apply { reuseAddress = true; bind(java.net.InetSocketAddress(InetAddress.getByName("127.0.0.1"), PORT), 4); soTimeout = 1000 }
            server = socket
            mutable.value = status.value.copy(active = true, expiresAt = 0, message = "持续连接已开启，等待 OrbitV 与手表应用。")
            job = scope.launch(Dispatchers.IO) {
                try {
                    while(isActive) {
                        expireHeartbeats()
                        val client = try { socket.accept() } catch(_: java.net.SocketTimeoutException) { continue }
                        activeSocket = client
                        client.use { handle(it) }; activeSocket = null
                    }
                } catch(_: Exception) {
                    if(isActive && server === socket) mutable.value = status.value.copy(message = "本机同步接口已中断，请重新开启。")
                } finally {
                    if(server === socket) { stop(); withContext(NonCancellable + Dispatchers.Main) { expired() } }
                }
            }
        } catch(_: Exception) { stop(); mutable.value = status.value.copy(message = "本机同步接口无法开启，请稍后重试。") }
    }
    fun stop(message: String? = null) {
        val current = server; server = null
        runCatching { activeSocket?.close() }; activeSocket = null
        runCatching { current?.close() }; job?.cancel(); job = null
        extensionSeen = 0; watchSeen = 0
        mutable.value = status.value.copy(active = false, expiresAt = 0, extensionConnected = false, transportReady = false,
            watchAppConnected = false, watchVersion = "", message = message ?: status.value.message)
    }
    private suspend fun handle(socket: Socket) {
        try {
            socket.soTimeout = 4000
            val input = socket.getInputStream().buffered()
            var headerBytes = 0
            fun line(): String {
                val bytes = java.io.ByteArrayOutputStream()
                while(true) {
                    val next = input.read(); require(next >= 0 && ++headerBytes <= 16384)
                    if(next == 10) return bytes.toString("US-ASCII").trimEnd('\r')
                    bytes.write(next)
                }
            }
            val first = line().split(' '); require(first.size == 3 && first[2] == "HTTP/1.1")
            val headers = mutableMapOf<String, String>()
            while(true) {
                val row = line(); if(row.isEmpty()) break
                val separator = row.indexOf(':'); require(separator > 0)
                val key = row.substring(0, separator).lowercase(); require(key !in headers)
                headers[key] = row.substring(separator + 1).trim()
            }
            if(first[0] == "OPTIONS") { respond(socket, 204, ""); return }
            val expected = "Bearer $token".toByteArray(Charsets.UTF_8)
            if(!MessageDigest.isEqual(expected, headers["authorization"].orEmpty().toByteArray(Charsets.UTF_8))) {
                respond(socket, 401, "{\"error\":\"UNAUTHORIZED\"}"); return
            }
            require("transfer-encoding" !in headers)
            val length = headers["content-length"]?.toInt() ?: 0; require(length in 0..512 * 1024)
            fun body(): JSONObject {
                if(length == 0) return JSONObject()
                val bytes = ByteArray(length); var received = 0
                while(received < length) { val count = input.read(bytes, received, length - received); require(count > 0); received += count }
                return JSONObject(bytes.toString(Charsets.UTF_8))
            }
            when {
                first[0] == "GET" && first[1] == "/control" -> respond(socket, 200, control().toString())
                first[0] == "POST" && first[1] == "/heartbeat" -> { heartbeat(body()); respond(socket, 200, control().toString()) }
                first[0] == "POST" && first[1] == "/request" -> { requestUpdate(); respond(socket, 200, control().toString()) }
                first[0] == "POST" && first[1] == "/sync" -> respond(socket, 200, sync(body()).toString())
                first[0] == "POST" && first[1] == "/saved" -> {
                    val acknowledgement = body()
                    val batch = receipts[acknowledgement.optString("receiptId")]
                    if(batch != null) {
                        val received = acknowledgement.optJSONArray("days") ?: org.json.JSONArray()
                        val expected = batch.days.map { it.getString("day") to it.getLong("revision") }
                        val actual = (0 until received.length()).map { received.getJSONObject(it).let { row -> row.getString("day") to row.getLong("revision") } }
                        if(actual != expected) { respond(socket, 409, "{\"error\":\"STALE_RECEIPT\"}"); return }
                        batch.accepted = true; batch.session.acknowledged.addAll(expected.map { it.first })
                        val complete = batch.session.acknowledged.size == batch.session.days.size
                        if(complete) {
                            val now = System.currentTimeMillis(); val day = batch.session.days.lastOrNull()?.getString("day") ?: LocalDate.now().toString()
                            preferences.edit().putLong("savedAt", now).putString("savedDay", day).apply()
                            mutable.value = status.value.copy(lastSavedAt = now, lastSavedDay = day, message = "手表已确认同步完成，更新 ${batch.session.days.size} 个日期。")
                        } else mutable.value = status.value.copy(message = "手表已保存 ${batch.session.acknowledged.size}/${batch.session.days.size} 个更新日期。")
                        respond(socket, 200, "{\"ok\":true}"); return
                    }
                    respond(socket, 409, "{\"error\":\"STALE_RECEIPT\"}")

                }
                else -> respond(socket, 404, "{\"error\":\"NOT_FOUND\"}")
            }
        } catch(_: Exception) { runCatching { respond(socket, 400, "{\"error\":\"INVALID_REQUEST\"}") } }
    }
    private fun respond(socket: Socket, status: Int, body: String) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        val reason = when(status) { 200 -> "OK"; 204 -> "No Content"; 401 -> "Unauthorized"; 404 -> "Not Found"; 409 -> "Conflict"; else -> "Bad Request" }
        val head = "HTTP/1.1 $status $reason\r\nContent-Type: application/json; charset=utf-8\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\nCache-Control: no-store\r\nAccess-Control-Allow-Origin: *\r\nAccess-Control-Allow-Methods: GET, POST, OPTIONS\r\nAccess-Control-Allow-Headers: Authorization, Content-Type\r\nAccess-Control-Allow-Private-Network: true\r\nX-Content-Type-Options: nosniff\r\n\r\n"
        socket.getOutputStream().apply { write(head.toByteArray(Charsets.US_ASCII)); write(bytes); flush() }
    }
}
