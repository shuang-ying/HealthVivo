package com.lrh.healthvivo.debug

import android.annotation.SuppressLint
import android.app.Activity
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.os.Bundle
import android.view.WindowManager
import android.widget.TextView
import com.lrh.healthvivo.HealthApplication
import kotlinx.coroutines.*
import org.json.JSONObject
import java.io.File
import java.util.UUID

/** Interoperability probe only: standard bonded SPP, no RPC or fitness bytes sent. */
@SuppressLint("MissingPermission")
class ClassicSppProbeActivity : Activity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var socket: BluetoothSocket? = null
    private lateinit var text: TextView
    private val report = JSONObject().put("transport", "SPP/RFCOMM").put("bytesSent", 0)
    private fun save() {
        File(cacheDir, "classic-spp-probe.json").writeText(report.toString(2))
        text.text = "OrbitV 连接方式验证\n\n" + report.toString(2) + "\n\n本次只验证蓝牙串口连接，不发送应用指令或健康记录。"
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        text = TextView(this).apply { textSize = 16f; setPadding(36, 72, 36, 36) }; setContentView(text)
        val bridge = (application as HealthApplication).watch
        bridge.disconnect()
        if(!bridge.hasPermissions()) { report.put("error", "附近设备权限未授予"); save(); return }
        val adapter = getSystemService(BluetoothManager::class.java)?.adapter
        val device = adapter?.bondedDevices?.firstOrNull { Regex("watch\\s*gt\\s*2", RegexOption.IGNORE_CASE).containsMatchIn(it.name.orEmpty()) }
        if(device == null) { report.put("error", "未找到已配对 GT2"); save(); return }
        report.put("device", "iQOO/vivo WATCH GT2").put("strategy", "secure-standard").put("connected", false)
        save()
        scope.launch {
            val started = System.currentTimeMillis()
            try {
                adapter.cancelDiscovery()
                val current = device.createRfcommSocketToServiceRecord(UUID.fromString("00001101-0000-1000-8000-00805F9B34FB"))
                socket = current
                val completion = CompletableDeferred<Unit>()
                // Detached IO coroutine allows the timeout to close a blocking socket.
                scope.launch(Dispatchers.IO) { try { current.connect(); completion.complete(Unit) } catch(error: Exception) { completion.completeExceptionally(error) } }
                withTimeout(15000) { completion.await() }
                report.put("connected", current.isConnected).put("connectMs", System.currentTimeMillis() - started)
                save()
                delay(1000)
                report.put("passiveAvailableBytes", withContext(Dispatchers.IO) { current.inputStream.available() })
                report.put("applicationProtocolVerified", false); save()
            } catch(error: Exception) {
                report.put("error", if(error is TimeoutCancellationException) "经典蓝牙连接超时" else error.message ?: error.javaClass.simpleName)
                report.put("elapsedMs", System.currentTimeMillis() - started); save()
            } finally { runCatching { socket?.close() }; socket = null }
        }
    }
    override fun onDestroy() { runCatching { socket?.close() }; socket = null; scope.cancel(); super.onDestroy() }
}
