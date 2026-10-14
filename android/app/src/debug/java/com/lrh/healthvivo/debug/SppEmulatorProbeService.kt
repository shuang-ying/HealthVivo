package com.lrh.healthvivo.debug

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.UUID

/** Public Android RFCOMM APIs and connectedDevice FGS; synthetic emulator peer only. */
@SuppressLint("MissingPermission")
class SppEmulatorProbeService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var socket: BluetoothSocket? = null
    @Volatile private var stopping = false
    private var task: Job? = null
    private val lock = Any()
    private val events = JSONArray()
    private val report = JSONObject().put("emulatorOnly", true).put("nativeAndroidBluetooth", true)
        .put("transport", "SPP/RFCOMM over netsim").put("foregroundServiceType", "connectedDevice")
        .put("peer", "Bumble virtual SPP server").put("applicationProtocol", "synthetic JSON PING/PONG")
        .put("gt2FirmwareVerified", false).put("healthRecordsRead", 0)
        .put("connections", 0).put("messagesSent", 0).put("responsesReceived", 0).put("events", events)
    private val session = UUID.randomUUID().toString()
    private val started = SystemClock.elapsedRealtime()

    private fun event(name: String, detail: String? = null) = synchronized(lock) {
        val row = JSONObject().put("event", name).put("elapsedMs", SystemClock.elapsedRealtime() - started)
        if (detail != null) row.put("detail", detail.take(160))
        if (events.length() < 200) events.put(row)
        File(cacheDir, "spp-emulator-probe.json").writeText(report.toString(2))
    }
    private fun count(name: String) = synchronized(lock) { report.put(name, report.getInt(name) + 1) }
    private fun installed(packageName: String) = runCatching { packageManager.getPackageInfo(packageName, 0); true }.getOrDefault(false)

    override fun onBind(intent: Intent?): IBinder? = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if ((Build.HARDWARE != "ranchu" && Build.HARDWARE != "goldfish") ||
            (Build.VERSION.SDK_INT >= 31 && checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED)) {
            stopSelf(); return START_NOT_STICKY
        }
        val notifications = getSystemService(NotificationManager::class.java)
        notifications.createNotificationChannel(NotificationChannel("spp-emulator-probe", "虚拟蓝牙诊断", NotificationManager.IMPORTANCE_LOW))
        val notification = Notification.Builder(this, "spp-emulator-probe")
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth).setContentTitle("虚拟 SPP 诊断")
            .setContentText("合成测试消息；不读取健康记录").setOngoing(true).build()
        startForeground(28345, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        if (task?.isActive == true) return START_NOT_STICKY
        report.put("vivoHealthInstalled", installed("com.vivo.health"))
        val duration = intent?.getIntExtra("duration_seconds", 60)?.coerceIn(15, 300) ?: 60
        val peerAddress = intent?.getStringExtra("peer_address") ?: "F0:F1:F2:F3:F4:F5"
        report.put("runId", intent?.getStringExtra("run_id") ?: session).put("virtualPeerAddress", peerAddress)
        if (!Regex("(?:[0-9A-Fa-f]{2}:){5}[0-9A-Fa-f]{2}").matches(peerAddress)) {
            event("invalid_virtual_address"); stopSelf(); return START_NOT_STICKY
        }
        val deadline = SystemClock.elapsedRealtime() + duration * 1000L
        event("started")
        task = scope.launch {
            var sequence = 0
            try {
                while (isActive && !stopping && SystemClock.elapsedRealtime() < deadline) {
                    try {
                        val adapter = getSystemService(BluetoothManager::class.java).adapter
                        if (!adapter.isEnabled) throw IOException("Bluetooth disabled")
                        // Supplied by the virtual peer; never scan or select a bonded physical device.
                        val current = adapter.getRemoteDevice(peerAddress)
                            .createInsecureRfcommSocketToServiceRecord(UUID.fromString("00001101-0000-1000-8000-00805F9B34FB"))
                        socket = current
                        event("connecting")
                        val connected = CompletableDeferred<Unit>()
                        val worker = scope.launch { try { current.connect(); connected.complete(Unit) } catch (e: Exception) { connected.completeExceptionally(e) } }
                        try { withTimeout(12000) { connected.await() } } finally { if (!connected.isCompleted) { current.close(); worker.cancel() } }
                        count("connections"); event("connected")
                        val input = current.inputStream.bufferedReader(Charsets.UTF_8)
                        while (isActive && !stopping && SystemClock.elapsedRealtime() < deadline) {
                            val seq = ++sequence
                            val payload = JSONObject().put("schema", 1).put("type", "ping").put("session", session).put("sequence", seq)
                            current.outputStream.write((payload.toString() + "\n").toByteArray(Charsets.UTF_8))
                            current.outputStream.flush(); count("messagesSent")
                            val reply = CompletableDeferred<String>()
                            val reader = scope.launch { try { reply.complete(input.readLine() ?: throw IOException("Peer closed")) } catch (e: Exception) { reply.completeExceptionally(e) } }
                            val line = try { withTimeout(7000) { reply.await() } } finally { if (!reply.isCompleted) { current.close(); reader.cancel() } }
                            if (line.length > 2048) throw IOException("Oversized test reply")
                            val pong = JSONObject(line)
                            if (pong.optInt("schema") != 1 || pong.optString("type") != "pong" || pong.optString("session") != session || pong.optInt("sequence") != seq) throw IOException("Unexpected test reply")
                            count("responsesReceived"); event("pong", seq.toString())
                            delay(2000)
                        }
                    } catch (error: Exception) {
                        if (error is CancellationException && stopping) break
                        event("disconnected", error.javaClass.simpleName)
                    } finally { runCatching { socket?.close() }; socket = null }
                    if (isActive && !stopping && SystemClock.elapsedRealtime() < deadline) delay(1000)
                }
            } finally { event("finished"); stopSelf() }
        }
        return START_NOT_STICKY
    }
    override fun onDestroy() {
        stopping = true
        runCatching { socket?.close() }; socket = null
        scope.cancel()
        super.onDestroy()
    }
}
