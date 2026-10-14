package com.lrh.healthvivo.link

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import com.lrh.healthvivo.ai.DailyAdviceCoordinator
import com.lrh.healthvivo.fitness.FitnessRepository
import com.lrh.healthvivo.watch.WatchDeltaSource
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.util.concurrent.atomic.AtomicBoolean

/** A watch the user can pick. */
data class LinkDevice(val address: String, val name: String)

/**
 * Everything the connection screen needs, without exposing any key material or health record.
 * A transfer is never started by a repository change; only `pending` (a user request) or a
 * request flag from the watch can start one.
 */
data class LinkStatus(
    val enabled: Boolean = false,
    val ready: Boolean = false,
    val busy: Boolean = false,
    val scanning: Boolean = false,
    val active: Boolean = false,
    val devices: List<LinkDevice> = emptyList(),
    val message: String = "打开手表活力记录，在手机搜索并选择手表即可连接。",
    val stage: String = "idle",
    val progress: Int = 0,
    val pending: Boolean = false,
    val selectedName: String = "",
    val lastSavedAt: Long = 0,
    val lastSavedDay: String = "",
    val peerState: Int = -1,
    val watchAdvertising: Boolean = false,
    val savedDays: Int = 0,
    val attempts: Int = 0,
    val probeResult: String = "")

/**
 * Owns the long-lived BLE connection to the watch.
 *
 * Persistence is the whole point of this class:
 *   * one coroutine owns the connection for the lifetime of the foreground service, and nothing
 *     else is allowed to create or close it;
 *   * any failure — timeout, GATT error, service change, session loss — is handled by rebuilding
 *     the connection with an exponential backoff capped at 30 s, so a transient problem never
 *     needs user action;
 *   * a lost *session* is repaired on the existing physical link by re-running the handshake,
 *     which is much faster than a full Bluetooth reconnect;
 *   * a manual update request is persisted before it is attempted, so it survives a disconnect,
 *     an app restart and a battery change, and it resumes on the next successful connection.
 */
/**
 * The day-revision ledger, migrated once off the retired relay's preference file.
 *
 * `WatchDeltaSource` stores `revision`, `hash.<day>`, `version.<day>` and `time.<day>` here. Those
 * values are what let the phone send only the days the watch does not already have, so they are
 * carried over verbatim rather than reset. The old file is left on disk untouched.
 */
private fun revisionLedger(context: Context): SharedPreferences {
    val current = context.getSharedPreferences("watch_revision_ledger", Context.MODE_PRIVATE)
    if (current.all.isNotEmpty()) return current
    val legacy = context.getSharedPreferences("orbitv_companion", Context.MODE_PRIVATE)
    val previous = legacy.all
    if (previous.isEmpty()) return current
    val edit = current.edit()
    previous.forEach { (key, value) ->
        when (value) {
            is String -> edit.putString(key, value)
            is Long -> edit.putLong(key, value)
            is Int -> edit.putInt(key, value)
            is Boolean -> edit.putBoolean(key, value)
            is Float -> edit.putFloat(key, value)
        }
    }
    edit.commit()
    return current
}

@SuppressLint("MissingPermission")
class LinkManager(context: Context, repository: FitnessRepository, advice: DailyAdviceCoordinator) {
    private val context = context.applicationContext
    private val store = LinkStore(context)
    // The per-day revision ledger describes health records, not the transport. It used to live in
    // the retired bridge's preference file, so it is moved once and then kept: losing it would make
    // every stored day look changed and force a full re-upload on the next sync.
    private val source = WatchDeltaSource(repository, advice, revisionLedger(context))
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val mutable = MutableStateFlow(LinkStatus(enabled = store.enabled, active = false,
        pending = store.pending, selectedName = store.name, lastSavedAt = store.lastSavedAt, lastSavedDay = store.lastSavedDay))
    val status: StateFlow<LinkStatus> = mutable.asStateFlow()

    private val adapter get() = context.getSystemService(BluetoothManager::class.java)?.adapter
    private var scanner: BluetoothLeScanner? = null
    private var scanTimeout: Job? = null
    private var connection: Job? = null
    private var link: LinkGatt? = null
    private var connectionGeneration = 0
    private val probeRequested = AtomicBoolean(false)

    fun permissions(): Array<String> = if (Build.VERSION.SDK_INT >= 31)
        arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
    else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)

    fun hasPermissions() = permissions().all { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED }

    fun permissionDenied() { publish(message = "请允许附近设备权限后连接手表。") }

    private fun publish(update: LinkStatus = mutable.value, message: String? = null) {
        mutable.value = if (message == null) update else update.copy(message = message)
    }

    /** Bonded and currently connected watches, so a user never has to wait for a scan. */
    internal fun knownWatches(): List<LinkDevice> {
        if (!hasPermissions()) return emptyList()
        return runCatching {
            val manager = context.getSystemService(BluetoothManager::class.java)
            ((adapter?.bondedDevices?.toList() ?: emptyList()) + (manager?.getConnectedDevices(BluetoothProfile.GATT) ?: emptyList()))
                .distinctBy { it.address }.mapNotNull { device ->
                    val name = device.name.orEmpty()
                    if (!WATCH_NAME.containsMatchIn(name)) null
                    else LinkDevice(device.address, name + " · " + device.address.takeLast(5))
                }.take(12)
        }.getOrDefault(emptyList())
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(type: Int, result: ScanResult) { scope.launch {
            if (!mutable.value.scanning) return@launch
            val name = result.scanRecord?.deviceName ?: result.device.name.orEmpty()
            // The watch firmware does not always put the service UUID in the advertisement, so
            // match either the advertised service or the device name.
            if (result.scanRecord?.serviceUuids?.any { it.uuid == LinkFrames.SERVICE } != true && !WATCH_NAME.containsMatchIn(name)) return@launch
            val item = LinkDevice(result.device.address, (name.ifBlank { "活力记录手表" }) + " · " + result.device.address.takeLast(5))
            publish(mutable.value.copy(devices = (mutable.value.devices.filterNot { it.address == item.address } + item).take(12)))
        } }
        override fun onScanFailed(code: Int) { scope.launch { stopScan(); publish(message = "搜索失败（$code），请确认蓝牙已开启。") } }
    }

    private fun stopScan() {
        scanTimeout?.cancel(); scanTimeout = null
        runCatching { scanner?.stopScan(scanCallback) }; scanner = null
        publish(mutable.value.copy(scanning = false))
    }

    fun scan() {
        if (!hasPermissions()) { permissionDenied(); return }
        stopScan()
        try {
            check(adapter?.isEnabled == true) { "请先开启手机蓝牙" }
            scanner = adapter?.bluetoothLeScanner ?: error("手机蓝牙搜索不可用")
            publish(mutable.value.copy(scanning = true, devices = knownWatches()), "打开手表活力记录，选择附近或已配对的 GT2。")
            scanner?.startScan(null, ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(), scanCallback)
            scanTimeout = scope.launch { delay(SCAN_TIMEOUT); stopScan() }
        } catch (failure: Exception) { stopScan(); publish(message = failure.message ?: "搜索不可用") }
    }

    fun enable(value: Boolean) {
        if (value && !hasPermissions()) { permissionDenied(); return }
        store.setEnabled(value)
        publish(mutable.value.copy(enabled = value))
        if (value) {
            try { ContextCompat.startForegroundService(context, Intent(context, LinkService::class.java)) }
            catch (_: Exception) { publish(message = "请回到手机应用开启持续连接。") }
        } else {
            stopScan(); stopPersistent()
            context.stopService(Intent(context, LinkService::class.java))
        }
    }

    /** Called when the app becomes visible again, so a killed service is restarted. */
    fun resume() { if (mutable.value.enabled) enable(true) }

    fun connect(address: String) {
        if (!hasPermissions()) { permissionDenied(); return }
        val device = mutable.value.devices.firstOrNull { it.address == address }
            ?: knownWatches().firstOrNull { it.address == address } ?: return
        stopScan(); disconnect()
        if (!store.select(address, device.name)) return
        publish(mutable.value.copy(selectedName = device.name, lastSavedAt = store.lastSavedAt, lastSavedDay = store.lastSavedDay))
        enable(true)
    }

    fun disconnect() {
        connectionGeneration++
        connection?.cancel(); connection = null
        link?.close(); link = null
        publish(mutable.value.copy(ready = false, busy = false, progress = 0, stage = "idle"))
    }

    internal fun stopPersistent() {
        disconnect(); stopScan()
        publish(mutable.value.copy(active = false, message = "持续连接已暂停，手表保留本地记录。"))
    }

    internal fun startPersistent() {
        publish(mutable.value.copy(active = true))
        if (connection?.isActive == true) return
        val address = store.address
        if (address.isEmpty()) { publish(mutable.value.copy(devices = knownWatches(), message = "请选择要连接的手表。")); return }
        val generation = ++connectionGeneration
        connection = scope.launch {
            var backoff = INITIAL_BACKOFF
            var attempts = 0
            while (isActive && mutable.value.enabled && generation == connectionGeneration) {
                var current: LinkGatt? = null
                try {
                    check(hasPermissions()) { "附近设备权限未授权" }
                    check(adapter?.isEnabled == true) { "请开启手机蓝牙" }
                    val secret = withContext(Dispatchers.IO) { store.getOrCreate(address) }
                    current = LinkGatt(context, adapter!!.getRemoteDevice(address), scope) { phase, message ->
                        if (generation == connectionGeneration) publish(mutable.value.copy(stage = phase), message)
                    }
                    link = current
                    current.connect()
                    val transfer = LinkTransfer(current) { Log.i(LinkGatt.TAG, it) }
                    backoff = INITIAL_BACKOFF
                    attempts = 0
                    publish(mutable.value.copy(ready = false, stage = "session"), "正在自动建立手机与手表会话…")
                    var sequence = 0
                    while (isActive && mutable.value.enabled && generation == connectionGeneration) {
                        val session = handshake(current, transfer, secret)
                        sequence = 1
                        Log.i(LinkGatt.TAG, "session ready")
                        publish(mutable.value.copy(ready = true, stage = "ready"), "手表已连接 · 等待手动更新")
                        try {
                            while (isActive && mutable.value.enabled && generation == connectionGeneration) {
                                val receipt = session.receipt(current.read())
                                if (probeRequested.compareAndSet(true, false)) {
                                    val id = ++sequence
                                    try {
                                        transfer.probe(session, id)
                                        publish(mutable.value.copy(peerState = receipt.state,
                                            probeResult = "自检通过 · 手表回应了本次会话的加密往返"))
                                    } catch (failure: Exception) {
                                        publish(mutable.value.copy(probeResult = "自检失败：" + (failure.message ?: "未知原因")))
                                    }
                                }
                                if (store.pending || receipt.requested) sequence = synchronize(current, transfer, session, sequence)
                                delay(HEARTBEAT)
                            }
                        } catch (lost: LinkSessionLost) {
                            // The physical link is fine; only the session key material is gone.
                            Log.i(LinkGatt.TAG, "session lost, re-handshaking on the same link")
                            publish(mutable.value.copy(ready = false), "手表会话已失效 · 正在重新建立会话")
                        }
                    }
                } catch (timeout: TimeoutCancellationException) {
                    if (generation != connectionGeneration) return@launch
                    Log.i(LinkGatt.TAG, "timeout phase=${mutable.value.stage}")
                    publish(mutable.value.copy(ready = false, busy = false, stage = "reconnecting"),
                        "手表连接或保存确认超时 · 稍后重连，更新请求仍保留")
                } catch (cancel: CancellationException) { throw cancel }
                catch (failure: Exception) {
                    if (generation != connectionGeneration) return@launch
                    Log.i(LinkGatt.TAG, "failure phase=${mutable.value.stage} type=${failure.javaClass.simpleName}")
                    publish(mutable.value.copy(ready = false, busy = false, stage = "reconnecting"),
                        (failure.message ?: "手表连接中断") + " · 稍后重连")
                } finally {
                    current?.close()
                    if (link === current) link = null
                    mutable.value = mutable.value.copy(ready = false, busy = false)
                }
                attempts++
                publish(mutable.value.copy(attempts = attempts))
                delay(backoff)
                backoff = (backoff * 2).coerceAtMost(MAX_BACKOFF)
            }
        }
    }

    /** Reset, greet, then enrol or resume. Returns a session usable on this link. */
    private suspend fun handshake(gatt: LinkGatt, transfer: LinkTransfer, secret: String): LinkSession {
        transfer.reset()
        val greeting = transfer.greet()
        mutable.value = mutable.value.copy(watchAdvertising = true)
        Log.i(LinkGatt.TAG, "greeting enrolled=${greeting.enrolled} pending=${greeting.requestPending}")
        val session = LinkSession(secret, greeting.epoch)
        val id = HANDSHAKE_ID
        transfer.send(id, if (greeting.enrolled) LinkFrames.KIND_HELLO else LinkFrames.KIND_ENROLL,
            if (greeting.enrolled) session.hello else session.enrollment)
        transfer.await(session, id, LinkFrames.STATE_READY, HANDSHAKE_TIMEOUT)
        return session
    }

    private fun pendingRequest() = store.pending

    /** Register a manual update request; the connection loop will deliver it. */
    fun sync() {
        if (store.address.isBlank()) { publish(message = "请先搜索并选择手表。"); scan(); return }
        try { store.requestUpdate() } catch (failure: Exception) { publish(message = failure.message ?: "更新请求保存失败，请重试"); return }
        publish(mutable.value.copy(pending = true), if (mutable.value.ready) "更新请求已登记" else "更新请求已保留，连接后传输")
        if (hasPermissions()) enable(true) else permissionDenied()
    }

    /** Ask the connection loop to run an authenticated round trip on the next heartbeat. */
    fun probe() {
        if (!mutable.value.ready) { publish(message = "请先连接手表，再进行连接自检。"); return }
        probeRequested.set(true)
        publish(message = "正在向手表发送连接自检…")
    }

    private suspend fun synchronize(gatt: LinkGatt, transfer: LinkTransfer, session: LinkSession, sequence: Int): Int {
        var next = sequence
        gatt.prioritize(true)
        val request = store.request
        publish(mutable.value.copy(busy = true, stage = "inventory", progress = 0), "正在比较已保存日期…")
        try {
            val known = inventory(transfer, session) { ++next }
            val changes = withContext(Dispatchers.IO) { source.changed(known) }
            val batches = if (changes.isEmpty()) listOf(emptyList()) else changes.map { listOf(it) }
            batches.forEachIndexed { index, days ->
                val id = ++next
                val message = JSONObject().put("type", "days").put("days", JSONArray(days))
                    .put("final", index == batches.lastIndex).toString()
                publish(mutable.value.copy(stage = "sending"), "正在更新日期 ${index + 1}/${batches.size}")
                transfer.send(id, LinkFrames.KIND_BODY, session.encrypt(message, id)) { percent ->
                    if (percent != mutable.value.progress) publish(mutable.value.copy(progress = percent))
                }
                publish(mutable.value.copy(stage = "saving"), "等待手表保存确认…")
                transfer.await(session, id, LinkFrames.STATE_SAVED)
                publish(mutable.value.copy(progress = (index + 1) * 100 / batches.size))
            }
            val now = System.currentTimeMillis()
            val day = LocalDate.now().toString()
            check(withContext(Dispatchers.IO) { store.completeUpdate(request, now, day) }) { "手表已保存，手机确认记录保存失败" }
            publish(mutable.value.copy(lastSavedAt = now, lastSavedDay = day, pending = pendingRequest(), stage = "ready"),
                if (changes.isEmpty()) "手表已确认 · 数据已是最新" else "手表已确认保存 ${changes.size} 个更新日期")
        } finally {
            gatt.prioritize(false)
            mutable.value = mutable.value.copy(busy = false)
        }
        return next
    }

    /** Read the watch's date index, in pages, until it reports that nothing more is known. */
    private suspend fun inventory(transfer: LinkTransfer, session: LinkSession, nextId: () -> Int): JSONObject {
        val known = JSONObject(); var cursor = 0
        do {
            val id = nextId()
            val query = JSONObject().put("type", "inventory").put("cursor", cursor).toString()
            transfer.send(id, LinkFrames.KIND_BODY, session.encrypt(query, id))
            val receipt = transfer.await(session, id, LinkFrames.STATE_REPLY)
            val page = JSONObject(session.decrypt(transfer.reply(session, receipt), id))
            val versions = page.getJSONObject("known")
            require(versions.length() <= 100 && known.length() + versions.length() <= MAX_DAYS) { "手表日期索引过大" }
            versions.keys().forEach { day ->
                LocalDate.parse(day)
                require(!known.has(day)) { "手表日期索引重复" }
                known.put(day, versions.getLong(day).also { require(it > 0) { "手表日期版本无效" } })
            }
            val next = page.getInt("cursor")
            require(next == cursor + versions.length()) { "手表日期游标不一致" }
            if (!page.getBoolean("more")) break
            require(next > cursor) { "手表日期游标未前进" }
            cursor = next
        } while (true)
        return known
    }

    companion object {
        const val TAG = LinkGatt.TAG
        const val INITIAL_BACKOFF = 1000L
        const val MAX_BACKOFF = 30000L
        const val HEARTBEAT = 2000L
        const val HANDSHAKE_TIMEOUT = 20000L
        const val HANDSHAKE_ID = 1
        const val SCAN_TIMEOUT = 20000L
        const val MAX_DAYS = 5000
        private val WATCH_NAME = Regex("watch|手表|gt\\s*2", RegexOption.IGNORE_CASE)
    }
}
