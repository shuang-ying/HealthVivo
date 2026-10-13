package com.lrh.healthvivo.debug

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.lrh.healthvivo.HealthApplication
import com.lrh.healthvivo.watch.OrbitLocalBridge
import com.lrh.healthvivo.watch.OrbitSyncService
import kotlinx.coroutines.*
import org.json.JSONObject
import java.io.File
import java.net.Socket

/** Reads real summary in memory, checks rejection paths; never sends a valid save confirmation. */
class OrbitBridgeProbeActivity : Activity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val view = TextView(this).apply { textSize = 18f; text = "正在验证本机同步接口…"; setPadding(32, 80, 32, 32) }; setContentView(view)
        val app = application as HealthApplication
        ContextCompat.startForegroundService(this, Intent(this, OrbitSyncService::class.java))
        scope.launch {
            val report = JSONObject().put("validSaveReceiptSent", false).put("healthRecordsModified", false)
            try {
                withTimeout(5000) { while(!app.orbitBridge.status.value.active) delay(50) }
                val token = JSONObject(app.orbitBridge.pairingConfiguration()).getString("token")
                suspend fun request(method: String, path: String, auth: Boolean, body: String = ""): Pair<Int, String> = withContext(Dispatchers.IO) {
                    Socket("127.0.0.1", OrbitLocalBridge.PORT).use { socket ->
                        socket.soTimeout = 8000
                        val bytes = body.toByteArray(Charsets.UTF_8)
                        val headers = "$method $path HTTP/1.1\r\nHost: 127.0.0.1\r\n" +
                            (if(auth) "Authorization: Bearer $token\r\n" else "") + "Content-Length: ${bytes.size}\r\nConnection: close\r\n\r\n"
                        socket.getOutputStream().apply { write(headers.toByteArray(Charsets.US_ASCII)); write(bytes); flush() }
                        val text = socket.getInputStream().readBytes().toString(Charsets.UTF_8)
                        text.lineSequence().first().split(' ')[1].toInt() to text.substringAfter("\r\n\r\n")
                    }
                }
                val denied = request("GET", "/control", false); check(denied.first == 401); report.put("unauthorizedStatus", denied.first)
                val cors = request("OPTIONS", "/sync", false); check(cors.first == 204); report.put("preflightStatus", cors.first)
                val control = request("GET", "/control", true); check(control.first == 200)
                val command = JSONObject(control.second).getLong("requestId")
                if(intent.getBooleanExtra("connection_only", false)) {
                    check(app.orbitBridge.status.value.expiresAt == 0L)
                    report.put("noFifteenMinuteDeadline", true)
                    val pulse = JSONObject().put("transportReady", true).put("watchAppReady", true).put("watchVersion", "0.0.0")
                    val online = JSONObject(request("POST", "/heartbeat", true, pulse.toString()).second)
                    check(online.getBoolean("extensionConnected") && online.getBoolean("transportReady") && online.getBoolean("watchAppConnected"))
                    report.put("threeReadinessFlags", true)
                    val offline = JSONObject(request("POST", "/heartbeat", true, "{\"transportReady\":false}").second)
                    check(!offline.getBoolean("transportReady") && !offline.getBoolean("watchAppConnected"))
                    report.put("disconnectClearsWatchReadiness", true)
                    withContext(Dispatchers.IO) {
                        Socket("127.0.0.1", OrbitLocalBridge.PORT).use { held ->
                            held.soTimeout = 8000
                            held.getOutputStream().apply { write("GET /control HTTP/1.1\r\n".toByteArray()); flush() }
                            delay(100)
                            withContext(Dispatchers.Main) { app.orbitBridge.start(scope) {} }
                            held.getOutputStream().apply { write("Host: 127.0.0.1\r\nAuthorization: Bearer $token\r\nContent-Length: 0\r\n\r\n".toByteArray()); flush() }
                            check(held.getInputStream().readBytes().toString(Charsets.UTF_8).startsWith("HTTP/1.1 200"))
                        }
                    }
                    report.put("repeatedStartPreservesInFlightSocket", true)
                    check(JSONObject(request("GET", "/control", true).second).getLong("requestId") == command)
                    report.put("noUpdateCommandCreated", true).put("healthSummaryRequests", 0).put("success", true)
                    return@launch
                }
                val first = request("POST", "/sync", true, "{\"known\":{}} "); check(first.first == 200)
                val packet = JSONObject(first.second); check(packet.getInt("schema") == 3)
                val known = JSONObject(); var page = packet; var count = 0
                while(true) {
                    val days = page.getJSONArray("days")
                    for(i in 0 until days.length()) { val day = days.getJSONObject(i); check(day.getInt("schema") == 2); known.put(day.getString("day"), day.getLong("revision")); count++ }
                    if(!page.getBoolean("more")) break
                    val next = request("POST", "/sync", true, JSONObject().put("sessionId", packet.getString("sessionId")).put("cursor", page.getInt("cursor")).toString()); check(next.first == 200); page = JSONObject(next.second)
                }
                val second = request("POST", "/sync", true, JSONObject().put("known", known).toString()); check(second.first == 200)
                check(JSONObject(second.second).getJSONArray("days").length() == 0)
                check(JSONObject(request("GET", "/control", true).second).getLong("requestId") == command)
                report.put("summaryBytes", first.second.toByteArray(Charsets.UTF_8).size).put("historyDays", count).put("unchangedDaysTransferred", 0).put("noUpdateCommandCreated", true)
                val wrong = request("POST", "/saved", true, "{\"receiptId\":\"invalid-probe-receipt\",\"days\":[]}")
                check(wrong.first == 409); report.put("incorrectReceiptStatus", wrong.first)
                check(request("GET", "/other", true).first == 404); report.put("unknownRouteStatus", 404)
                val compact = com.lrh.healthvivo.ai.DailyAdviceClient.decode(JSONObject().put("food", "下一餐配主食、蛋白质和蔬菜").put("exercise", "适度步行与恢复"))
                check(compact.overview.isEmpty() && compact.energy.isEmpty() && compact.missing.isEmpty()); report.put("compactAdviceDecode", true)
                report.put("foregroundServiceActive", app.orbitBridge.status.value.active).put("success", true)
            } catch(_: Exception) { report.put("success", false) }
            finally {
                stopService(Intent(this@OrbitBridgeProbeActivity, OrbitSyncService::class.java))
                File(cacheDir, "orbitv-bridge-probe.json").writeText(report.toString(2)); view.text = report.toString(2)
            }
        }
    }
    override fun onDestroy() { scope.cancel(); super.onDestroy() }
}
