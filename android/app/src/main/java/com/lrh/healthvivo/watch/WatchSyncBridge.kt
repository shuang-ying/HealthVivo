package com.lrh.healthvivo.watch

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.*
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.ParcelUuid
import androidx.core.content.ContextCompat
import com.lrh.healthvivo.BuildConfig
import com.lrh.healthvivo.ai.DailyAdviceCoordinator
import com.lrh.healthvivo.fitness.FitnessRepository
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import java.time.LocalDate

data class NearbyWatch(val address: String, val name: String)
data class WatchSyncStatus(val configured: Boolean, val enabled: Boolean, val ready: Boolean = false,
    val busy: Boolean = false, val message: String, val lastSavedAt: Long = 0, val lastSavedDay: String = "",
    val scanning: Boolean = false, val devices: List<NearbyWatch> = emptyList(), val pairingCode: String = "",
    val progress: Int = 0, val stage: String = "idle", val peerReceiptState: Int = -1, val peerReceiptBytes: Int = 0)

/** Foreground watch BLE service; application-level receipt is read independently of writes. */
@OptIn(FlowPreview::class)
@SuppressLint("MissingPermission")
class WatchSyncBridge(context: Context, private val repository: FitnessRepository, private val advice: DailyAdviceCoordinator) {
    private val context = context.applicationContext
    private val preferences = context.getSharedPreferences("watch_dashboard", Context.MODE_PRIVATE)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val signals = Channel<Unit>(Channel.CONFLATED)
    private val configured = BuildConfig.WATCH_BLE_PUBLIC_KEY.isNotBlank()
    private val mutable = MutableStateFlow(WatchSyncStatus(configured, preferences.getBoolean("enabled", false),
        message = "打开手表端活力记录，然后搜索附近手表。",
        lastSavedAt = preferences.getLong("lastSavedAt", 0), lastSavedDay = preferences.getString("lastSavedDay", "").orEmpty()))
    val status = mutable.asStateFlow()
    private val adapter get() = context.getSystemService(BluetoothManager::class.java)?.adapter
    private var scanner: BluetoothLeScanner? = null
    private var scanTimeout: Job? = null
    private var connection: Job? = null
    private var link: BleGattLink? = null
    // Some paired watches expose GATT services without advertising the service UUID.
    // Service discovery and pairing still verify the peer before sending a snapshot.
    internal fun knownWatches(): List<NearbyWatch> {
        if(!hasPermissions()) return emptyList()
        return runCatching {
            val manager = context.getSystemService(BluetoothManager::class.java)
            ((adapter?.bondedDevices?.toList() ?: emptyList()) + (manager?.getConnectedDevices(BluetoothProfile.GATT) ?: emptyList()))
                .distinctBy { it.address }.mapNotNull { device ->
                    val name = device.name.orEmpty()
                    if(!Regex("watch|手表|gt\\s*2", RegexOption.IGNORE_CASE).containsMatchIn(name)) null
                    else NearbyWatch(device.address, "已配对 · $name · " + device.address.takeLast(5))
                }.take(12)
        }.getOrDefault(emptyList())
    }
    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(type: Int, result: ScanResult) { scope.launch {
            if(!status.value.scanning) return@launch
            val device = result.device
            val title = device.name?.takeIf { it.isNotBlank() } ?: result.scanRecord?.deviceName ?: "活力记录手表"
            val item = NearbyWatch(device.address, title + " · " + device.address.takeLast(5))
            mutable.value = status.value.copy(devices = (status.value.devices.filterNot { it.address == item.address } + item).take(12),
                message = "已发现手表，点击连接并在手表核对确认码。")
        } }
        override fun onScanFailed(code: Int) { scope.launch { stopScan(); mutable.value = status.value.copy(message = "搜索失败（$code），请确认手机蓝牙已开启。") } }
    }
    init {
        scope.launch { repository.data.debounce(1200).collect { if(status.value.enabled && status.value.ready) sync() } }
        scope.launch { advice.states.map { it[LocalDate.now().toString()]?.saved?.generatedAt ?: 0L }.distinctUntilChanged().collect {
            if(it > 0 && status.value.enabled && status.value.ready) sync()
        } }
    }
    fun permissions(): Array<String> = if(Build.VERSION.SDK_INT >= 31) arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
    fun hasPermissions() = permissions().all { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED }
    fun resume() { if(status.value.enabled && link == null) scan() }
    fun enable(value: Boolean) {
        preferences.edit().putBoolean("enabled", value).apply()
        mutable.value = status.value.copy(enabled = value)
        if(value) scan() else {
            stopScan(); disconnect()
            mutable.value = status.value.copy(message = "已关闭直连同步，手表保留上次缓存。")
        }
    }
    fun permissionDenied() { mutable.value = status.value.copy(message = "请允许“附近设备”权限后搜索手表。") }
    private fun stopScan() {
        scanTimeout?.cancel(); scanTimeout = null
        runCatching { scanner?.stopScan(scanCallback) }; scanner = null
        mutable.value = status.value.copy(scanning = false)
    }
    fun scan() {
        if(!status.value.enabled || !configured) return
        if(!hasPermissions()) { permissionDenied(); return }
        if(status.value.ready || status.value.busy) return
        stopScan()
        try {
            check(adapter?.isEnabled == true) { "请先开启手机蓝牙" }
            scanner = adapter?.bluetoothLeScanner ?: error("手机蓝牙搜索不可用")
            mutable.value = status.value.copy(scanning = true, devices = knownWatches(), message = "正在搜索；也可选择已配对手表尝试连接，保持手表应用在前台…")
            scanner?.startScan(listOf(ScanFilter.Builder().setServiceUuid(ParcelUuid(BleGattLink.SERVICE)).build()),
                ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(), scanCallback)
            scanTimeout = scope.launch { delay(20000); stopScan()
                mutable.value = status.value.copy(message = if(status.value.devices.isEmpty()) "未发现应用直连服务，请打开新版手表端后重试；系统蓝牙配对不等于应用连接。" else "搜索结束，选择手表连接；已配对设备仍需提供活力记录服务。")
            }
        } catch(failure: Exception) { stopScan(); mutable.value = status.value.copy(message = failure.message ?: "无法搜索手表") }
    }
    fun disconnect() {
        connection?.cancel(); connection = null
        link?.close(); link = null
        mutable.value = status.value.copy(ready = false, busy = false, pairingCode = "", progress = 0)
    }
    fun connect(address: String) {
        if(!status.value.enabled || !hasPermissions() || status.value.devices.none { it.address == address }) return
        stopScan(); disconnect()
        val current = BleGattLink(context, adapter?.getRemoteDevice(address) ?: return, scope) { phase, message ->
            mutable.value = status.value.copy(stage = phase, message = message)
        }; link = current
        mutable.value = status.value.copy(busy = true, pairingCode = "", peerReceiptState = -1, peerReceiptBytes = 0,
            lastSavedAt = if(preferences.getString("lastSavedAddress", "") == address) preferences.getLong("lastSavedAt", 0) else 0,
            lastSavedDay = if(preferences.getString("lastSavedAddress", "") == address) preferences.getString("lastSavedDay", "").orEmpty() else "",
            message = "正在连接手表应用…")
        connection = scope.launch {
            try {
                current.connect()
                mutable.value = status.value.copy(stage = "hello", message = "手表服务已找到，正在建立加密会话…")
                val wire = withContext(Dispatchers.Default) { BleWire(BuildConfig.WATCH_BLE_PUBLIC_KEY) }
                val helloId = wire.id()
                send(current, helloId, 1, wire.hello)
                mutable.value = status.value.copy(stage = "hello", message = "连接请求已发送，等待手表解密回应…")
                val helloDeadline = System.currentTimeMillis() + 20000
                withTimeout(120000) {
                    while(true) {
                        val bytes = current.read()
                        mutable.value = status.value.copy(peerReceiptBytes = bytes.size, peerReceiptState = if(bytes.size == 1) bytes[0].toInt() and 255 else -1)
                        check(bytes.size != 1 || bytes[0].toInt() != 5) { "手表无法解密连接请求，请核对配套安装包及 BlueOS 加密接口支持" }
                        if(bytes.size == 20) {
                            val receipt = wire.receipt(bytes)
                            mutable.value = status.value.copy(peerReceiptState = receipt.state)
                            if(receipt.id == helloId && receipt.state == 1) mutable.value = status.value.copy(stage = "pairing", pairingCode = wire.code,
                                message = "手表已回应；核对两端确认码，并在手表点击“允许这台手机”。")
                            if(receipt.id == helloId && receipt.state == 2) break
                            check(receipt.state != 7 && receipt.state != 5) { "手表拒绝连接或不支持加密，请重新打开手表应用后连接" }
                        }
                        check(status.value.stage == "pairing" || System.currentTimeMillis() < helloDeadline) {
                            "手表服务已连接，但连接请求未得到有效回应（${bytes.size} 字节）。请重开手表应用或更新手表端。"
                        }
                        delay(700)
                    }
                }
                mutable.value = status.value.copy(stage = "ready", ready = true, busy = false, pairingCode = "", message = "已建立蓝牙直连，正在同步今日数据。")
                signals.trySend(Unit)
                var pullHandled = false
                while(isActive && status.value.enabled && link === current) {
                    if(signals.tryReceive().isSuccess) sendSnapshot(current, wire, address)
                    val receipt = wire.receipt(current.read())
                    val pulled = receipt.state == 6 || receipt.state in setOf(2, 4, 5) && (receipt.progress and 0x8000) != 0
                    if(pulled) { if(!pullHandled) signals.trySend(Unit); pullHandled = true } else pullHandled = false
                    delay(900)
                }
            } catch(timeout: TimeoutCancellationException) {
                if(link === current) mutable.value = status.value.copy(message = when(status.value.stage) {
                    "connect" -> "手表蓝牙连接超时，请保持手表应用在前台后重试。"
                    "services" -> "读取手表应用服务超时，请重新打开手表应用后连接。"
                    "pairing" -> "手表连接确认超时，请核对代码并在手表允许连接。"
                    "hello" -> "手表连接请求回应超时，请重开手表应用或更新手表端。"
                    "saving" -> "手表保存确认超时，请保持手表应用在前台后重试。"
                    else -> "直连超时，请重新打开手表应用后连接。"
                })
            } catch(cancel: CancellationException) { throw cancel }
            catch(failure: Exception) {
                if(link === current) mutable.value = status.value.copy(message = failure.message ?: "直连同步失败，请重新连接。")
            } finally {
                current.close()
                if(link === current) { link = null; mutable.value = status.value.copy(ready = false, busy = false, pairingCode = "", progress = 0) }
            }
        }
    }
    fun sync() { if(status.value.enabled) { if(status.value.ready) signals.trySend(Unit) else scan() } }
    private suspend fun send(current: BleGattLink, id: Int, kind: Int, bytes: ByteArray) {
        require(bytes.size in 1..BleWire.MAX_PAYLOAD) { "今日摘要过大，无法同步" }
        current.write(BleWire.begin(id, kind, bytes.size))
        val chunk = current.payloadSize - 9
        var offset = 0
        while(offset < bytes.size) {
            val end = minOf(offset + chunk, bytes.size)
            current.write(BleWire.part(id, offset, bytes.copyOfRange(offset, end)))
            offset = end
            if(kind == 2) {
                val progress = offset * 100 / bytes.size
                if(status.value.progress != progress) mutable.value = status.value.copy(progress = progress, message = "蓝牙直连发送 $progress%")
            }
        }
        current.write(BleWire.end(id))
    }
    private suspend fun sendSnapshot(current: BleGattLink, wire: BleWire, address: String) {
        val day = LocalDate.now().toString()
        mutable.value = status.value.copy(stage = "sending", busy = true, progress = 0, message = "正在准备今日摘要…")
        try {
            val revision = maxOf(System.currentTimeMillis(), preferences.getLong("revision", 0) + 1)
            check(withContext(Dispatchers.IO) { preferences.edit().putLong("revision", revision).commit() }) { "手机同步版本保存失败" }
            val text = withContext(Dispatchers.IO) { WatchSnapshot.create(repository.snapshot(), day, revision, advice.load(day)).toString() }
            val id = wire.id()
            val encrypted = withContext(Dispatchers.Default) { wire.encrypt(text, id) }
            send(current, id, 2, encrypted)
            mutable.value = status.value.copy(stage = "saving", message = "数据已传送，等待手表解密和本地保存确认…")
            withTimeout(30000) {
                while(true) {
                    val receipt = wire.receipt(current.read())
                    if(receipt.id == id && receipt.state == 4) break
                    check(receipt.id != id || receipt.state != 5) { "手表解密、校验或保存失败（" + (receipt.progress and 0x7fff) + "），旧缓存已保留" }
                    delay(600)
                }
            }
            val now = System.currentTimeMillis()
            check(withContext(Dispatchers.IO) { preferences.edit().putLong("lastSavedAt", now).putString("lastSavedDay", day)
                .putString("lastSavedAddress", address).commit() }) { "手表已保存，但手机确认记录保存失败" }
            mutable.value = status.value.copy(stage = "ready", lastSavedAt = now, lastSavedDay = day, message = "手表已确认保存今日数据。")
        } finally { mutable.value = status.value.copy(busy = false) }
    }
}
