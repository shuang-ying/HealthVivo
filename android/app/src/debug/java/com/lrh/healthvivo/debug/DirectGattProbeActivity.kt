package com.lrh.healthvivo.debug

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.bluetooth.*
import android.bluetooth.le.*
import android.content.pm.PackageManager
import android.os.*
import android.view.WindowManager
import android.widget.ScrollView
import android.widget.TextView
import org.json.JSONObject
import org.json.JSONArray
import java.io.File
import java.util.UUID

/** Bounded native GATT interoperability check. Never reads the health database. */
@SuppressLint("MissingPermission")
class DirectGattProbeActivity : Activity() {
    private val handler = Handler(Looper.getMainLooper())
    private val serviceId = UUID.fromString("fa954400-48d6-4a12-b74b-4c5b5170360e")
    private val characteristicId = UUID.fromString("fa954401-48d6-4a12-b74b-4c5b5170360e")
    private val session = UUID.randomUUID().toString().replace("-", "").take(6).uppercase()
    private val report = JSONObject().put("runId", UUID.randomUUID().toString())
        .put("transport", "native BLE GATT").put("healthRecordsRead", 0)
        .put("syntheticOnly", true).put("pongMatches", 0).put("completed", false)
    private var scanner: BluetoothLeScanner? = null
    private var gatt: BluetoothGatt? = null
    private var characteristic: BluetoothGattCharacteristic? = null
    private var finished = false
    private var found = false
    private var sequence = 0
    private var expected = "PROBE:1"
    private var stage = "initial"
    private lateinit var output: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        output = TextView(this).apply { textSize = 16f; setPadding(32, 64, 32, 40) }
        setContentView(ScrollView(this).apply { addView(output) })
        if (Build.VERSION.SDK_INT >= 31 && listOf(Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT).any { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }) {
            finishProbe("附近设备权限未授予"); return
        }
        val adapter = getSystemService(BluetoothManager::class.java)?.adapter
        handler.postDelayed({ if (!finished) finishProbe("GATT 探测总时限 60 秒已到") }, 60_000)
        if (intent.getStringExtra("strategy") == "bonded") {
            val watch = adapter?.bondedDevices?.firstOrNull {
                Regex("watch\\s*gt\\s*2", RegexOption.IGNORE_CASE).containsMatchIn(it.name.orEmpty())
            }
            report.put("strategy", "bonded GT2 address; no scan")
            if (watch == null) { finishProbe("未找到已配对 GT2"); return }
            connect(watch)
            return
        }
        report.put("strategy", "test service advertisement")
        scanner = adapter?.bluetoothLeScanner
        if (scanner == null) { finishProbe("蓝牙未开启或扫描器不可用"); return }
        stage = "scan"
        save()
        try {
            scanner?.startScan(listOf(ScanFilter.Builder().setServiceUuid(ParcelUuid(serviceId)).build()),
                ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(), scanCallback)
            handler.postDelayed({ if (!found && !finished) finishProbe("20 秒内未发现独立测试服务。请打开手表的通信接口验证。") }, 20_000)
        } catch (error: Exception) { finishProbe(error.message ?: error.javaClass.simpleName) }
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            handler.post {
                if (finished || found) return@post
                found = true
                scanner?.stopScan(this)
                report.put("serviceAdvertisementFound", true).put("rssi", result.rssi)
                connect(result.device)
            }
        }
        override fun onScanFailed(errorCode: Int) { handler.post { finishProbe("扫描失败：$errorCode") } }
    }

    private fun connect(device: BluetoothDevice) {
        stage = "connect"; save()
        try { gatt = device.connectGatt(this, false, callbacks, BluetoothDevice.TRANSPORT_LE) }
        catch (error: Exception) { finishProbe(error.message ?: "connectGatt failed") }
    }

    private val callbacks = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(current: BluetoothGatt, status: Int, newState: Int) {
            handler.post {
                if (finished) return@post
                report.put("connectionStatus", status).put("connectionState", newState)
                if (status != BluetoothGatt.GATT_SUCCESS) { finishProbe("GATT 连接错误：$status"); return@post }
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    if (intent.getBooleanExtra("refresh_cache", false)) {
                        // Diagnostic opt-in only. AOSP marks this metadata-cache operation @hide.
                        // It is not a supported production connection or reconnection strategy.
                        report.put("cacheRefreshPublicApi", false)
                        stage = "refreshCache"; save()
                        val refreshed = try {
                            current.javaClass.getMethod("refresh").invoke(current) == true
                        } catch (error: Exception) {
                            report.put("cacheRefreshErrorType", error.javaClass.simpleName)
                            false
                        }
                        report.put("cacheRefreshReturn", refreshed)
                        if (!refreshed) { finishProbe("诊断缓存刷新接口不可用"); return@post }
                        handler.postDelayed({ if (!finished) discover(current) }, 1500)
                    } else discover(current)
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) finishProbe("测试完成前连接断开")
            }
        }
        override fun onServicesDiscovered(current: BluetoothGatt, status: Int) {
            handler.post {
                if (finished) return@post
                report.put("discoveryStatus", status)
                report.put("serviceUuids", JSONArray(current.services.map { it.uuid.toString() }))
                characteristic = current.getService(serviceId)?.getCharacteristic(characteristicId)
                if (status != BluetoothGatt.GATT_SUCCESS || characteristic == null) {
                    finishProbe("未发现独立测试特征：$status"); return@post
                }
                report.put("testCharacteristicFound", true)
                stage = "readGreeting"; readNext(current)
            }
        }
        override fun onCharacteristicRead(current: BluetoothGatt, item: BluetoothGattCharacteristic, value: ByteArray, status: Int) {
            handleRead(current, item, value, status)
        }
        @Deprecated("API 26-32 callback")
        override fun onCharacteristicRead(current: BluetoothGatt, item: BluetoothGattCharacteristic, status: Int) {
            if (Build.VERSION.SDK_INT < 33) handleRead(current, item, item.value ?: byteArrayOf(), status)
        }
        override fun onCharacteristicWrite(current: BluetoothGatt, item: BluetoothGattCharacteristic, status: Int) {
            handler.post {
                if (finished || item.uuid != characteristicId) return@post
                report.put("writeStatus", status)
                if (status != BluetoothGatt.GATT_SUCCESS) { finishProbe("写入返回错误：$status"); return@post }
                stage = "readPong"; readNext(current)
            }
        }
    }

    private fun discover(current: BluetoothGatt) {
        stage = "discover"; save()
        if (!current.discoverServices()) finishProbe("discoverServices 未启动")
    }

    private fun handleRead(current: BluetoothGatt, item: BluetoothGattCharacteristic, value: ByteArray, status: Int) {
        handler.post {
            if (finished || item.uuid != characteristicId) return@post
            report.put("readStatus", status)
            if (status != BluetoothGatt.GATT_SUCCESS) { finishProbe("读取返回错误：$status"); return@post }
            val received = value.toString(Charsets.US_ASCII)
            // Only persist expected synthetic protocol data, never an arbitrary device payload.
            val greeting = stage == "readGreeting"
            // Another test client can leave its last PONG in the server's readable value.
            // Accept only that exact synthetic shape as a greeting; it never counts as our ACK.
            val previousPong = greeting && Regex("PONG:[0-9A-F]{6}:[0-9]{2}").matches(received)
            val matches = received == expected || previousPong
            report.put("lastReadBytes", value.size).put("lastReadMatches", matches)
            if (!matches) { finishProbe("测试回应不匹配（$stage），长度 ${value.size}"); return@post }
            if (greeting) report.put("greetingVerified", true).put("previousPongGreeting", previousPong)
            else {
                report.put("pongMatches", sequence)
                if (sequence >= 2) { report.put("applicationCallbackVerified", true); finishProbe(null); return@post }
            }
            sequence++
            val number = sequence.toString().padStart(2, '0')
            val payload = "PING:$session:$number".toByteArray(Charsets.US_ASCII)
            expected = "PONG:$session:$number"
            stage = "writePing"; save()
            val target = characteristic ?: return@post finishProbe("测试特征丢失")
            val started = if (Build.VERSION.SDK_INT >= 33) {
                current.writeCharacteristic(target, payload, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) == BluetoothStatusCodes.SUCCESS
            } else {
                target.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                target.value = payload
                current.writeCharacteristic(target)
            }
            if (!started) finishProbe("writeCharacteristic 未启动")
        }
    }

    private fun readNext(current: BluetoothGatt) {
        save()
        val target = characteristic
        if (target == null || !current.readCharacteristic(target)) finishProbe("readCharacteristic 未启动")
    }
    private fun save() {
        report.put("stage", stage).put("observedAtMs", System.currentTimeMillis())
        File(cacheDir, "direct-gatt-probe.json").writeText(report.toString(2))
        output.text = "独立 GATT 验证\n\n" + report.toString(2) + "\n\n仅收发测试 PING/PONG，不读取健康记录。"
    }
    private fun finishProbe(error: String?) {
        if (finished) return
        finished = true
        report.put("completed", true).put("passed", error == null)
        if (error != null) report.put("error", error)
        save()
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        handler.removeCallbacksAndMessages(null)
        runCatching { scanner?.stopScan(scanCallback) }
        runCatching { gatt?.disconnect(); gatt?.close() }; gatt = null
    }
    override fun onDestroy() {
        if (!finished) finishProbe("探测页面已关闭")
        super.onDestroy()
    }
}
