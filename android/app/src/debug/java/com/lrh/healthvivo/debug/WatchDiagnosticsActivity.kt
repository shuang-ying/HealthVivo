package com.lrh.healthvivo.debug

import android.annotation.SuppressLint
import android.app.Activity
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.*
import android.os.Bundle
import android.view.WindowManager
import android.widget.TextView
import com.lrh.healthvivo.HealthApplication
import com.lrh.healthvivo.link.LinkFrames
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/** ADB-only debug entry. Reads Bluetooth metadata; never edits fitness data or invokes AI. */
@SuppressLint("MissingPermission")
class WatchDiagnosticsActivity : Activity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var scanner: BluetoothLeScanner? = null
    private lateinit var text: TextView
    private val seen = mutableMapOf<String, JSONObject>()
    private val report = JSONObject().put("debugOnly", true).put("startedAtMs", System.currentTimeMillis())
    private val events = ArrayDeque<JSONObject>()
    private val watchPattern = Regex("watch|手表|gt\\s*2", RegexOption.IGNORE_CASE)
    private fun tag(address: String) = MessageDigest.getInstance("SHA-256").digest(address.toByteArray()).take(6).joinToString("") { "%02x".format(it) }
    private fun save() {
        report.put("observedAtMs", System.currentTimeMillis())
        File(cacheDir, "watch-diagnostics.json").writeText(report.toString(2))
        text.text = "蓝牙连接诊断\n\n" + report.toString(2)
    }
    private val callback = object : ScanCallback() {
        override fun onScanResult(type: Int, value: ScanResult) {
            val name = value.scanRecord?.deviceName ?: value.device.name.orEmpty()
            val ownService = value.scanRecord?.serviceUuids?.any { it.uuid == LinkFrames.SERVICE } == true
            // No other nearby devices' names or addresses are written to the report.
            if(!watchPattern.containsMatchIn(name) && !ownService) return
            seen[tag(value.device.address)] = JSONObject().put("deviceTag", tag(value.device.address))
                .put("name", if(watchPattern.containsMatchIn(name)) name else "活力记录服务")
                .put("advertisesAppService", ownService)
        }
        override fun onScanFailed(code: Int) { report.put("scanError", code); save() }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        text = TextView(this).apply { textSize = 14f; setPadding(36, 72, 36, 36) }
        setContentView(text)
        val bridge = (application as HealthApplication).link
        val manager = getSystemService(BluetoothManager::class.java)
        report.put("permissionsGranted", bridge.hasPermissions()).put("bluetoothOn", manager?.adapter?.isEnabled == true)
        if(!bridge.hasPermissions()) { save(); return }
        val known = bridge.knownWatches()
        report.put("bondedWatchTypes", JSONArray(manager?.adapter?.bondedDevices?.filter { watchPattern.containsMatchIn(it.name.orEmpty()) }?.map {
            JSONObject().put("deviceTag", tag(it.address)).put("type", it.type)
        } ?: emptyList<JSONObject>()))
        report.put("connectedGattDevices", JSONArray(manager?.getConnectedDevices(BluetoothProfile.GATT)?.map {
            JSONObject().put("deviceTag", tag(it.address)).put("watchName", if(watchPattern.containsMatchIn(it.name.orEmpty())) it.name else JSONObject.NULL)
        } ?: emptyList<JSONObject>()))
        report.put("knownWatches", JSONArray(known.map { JSONObject().put("deviceTag", tag(it.address)).put("name", it.name.substringBeforeLast(" · ")) }))
        save()
        scope.launch {
            try {
                scanner = manager?.adapter?.bluetoothLeScanner
                scanner?.startScan(emptyList(), ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(), callback)
                delay(10000); scanner?.stopScan(callback); scanner = null
                report.put("watchAdvertisements", JSONArray(seen.values.toList())); save()
                if(intent.getBooleanExtra("attempt_gt2", false)) {
                    val candidate = known.firstOrNull { it.name.contains("GT", ignoreCase = true) } ?: known.singleOrNull()
                    if(candidate == null) { report.put("connectionResult", "没有可尝试的已配对手表"); save(); return@launch }
                    if(!bridge.status.value.enabled) { report.put("connectionResult", "同步开关已关闭"); save(); return@launch }
                    bridge.disconnect(); bridge.scan(); bridge.connect(candidate.address)
                    val started = System.currentTimeMillis()
                    var syncRequested = false
                    val synchronize = intent.getBooleanExtra("sync_once", false)
                    report.put("syncRequestedByDebugUser", synchronize).put("protocolVersion", 1)
                    val result = withTimeoutOrNull(160000) {
                        bridge.status.first { state ->
                            events.addLast(JSONObject().put("atMs", System.currentTimeMillis())
                                .put("stage", state.stage).put("message", state.message)
                                .put("ready", state.ready).put("busy", state.busy))
                            while(events.size > 50) events.removeFirst()
                            report.put("events", JSONArray(events.toList()))
                            if(state.stage == "reconnecting") report.put("lastFailure", state.message)
                            report.put("connectionMessage", state.message).put("ready", state.ready).put("busy", state.busy)
                                .put("connectionStage", state.stage)
                                .put("peerState", state.peerState).put("savedDays", state.savedDays)
                                .put("lastSavedAt", state.lastSavedAt)
                            save()
                            if(state.ready && synchronize && !syncRequested) {
                                syncRequested = true; bridge.sync()
                            }
                            if(synchronize) syncRequested && state.lastSavedAt > started && !state.busy && !state.pending else state.ready
                        }
                    }
                    report.put("completed", true).put("passed", result != null)
                    save()
                }
            } catch(failure: Exception) { report.put("diagnosticError", failure.message ?: failure.javaClass.simpleName); save() }
        }
    }
    override fun onDestroy() {
        runCatching { scanner?.stopScan(callback) }; scope.cancel(); super.onDestroy()
    }
}
