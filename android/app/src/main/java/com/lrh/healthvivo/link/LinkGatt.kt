package com.lrh.healthvivo.link

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.content.Context
import android.os.Build
import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import java.util.UUID

/**
 * Serial GATT client for the watch link.
 *
 * This is the only class that talks to `BluetoothGatt`, and it exists to make one connection
 * survive for as long as possible:
 *
 *   * every ATT operation is serialised through a mutex, because concurrent GATT calls on one
 *     connection are the usual cause of a stuck link;
 *   * a non-zero status is surfaced together with its code instead of looking like a timeout, so
 *     the reconnect log says what actually happened;
 *   * `onServiceChanged` only invalidates service metadata; the next operation re-discovers the
 *     services on the same physical connection instead of dropping it;
 *   * only the public API is used, and `close()` always releases the client interface.
 *
 * The link deliberately never requests an MTU: the protocol is built around the default ATT
 * payload of 20 bytes, which is what the watch firmware was validated with.
 */
@SuppressLint("MissingPermission")
internal class LinkGatt(private val context: Context, private val device: BluetoothDevice,
    private val scope: CoroutineScope, private val trace: (String, String) -> Unit = { _, _ -> }) {

    private var gatt: BluetoothGatt? = null
    private var characteristic: BluetoothGattCharacteristic? = null
    private var connection: CompletableDeferred<Unit>? = null
    private var discovery: CompletableDeferred<Unit>? = null
    private var write: CompletableDeferred<Unit>? = null
    private var read: CompletableDeferred<ByteArray>? = null
    private val mutex = Mutex()
    private var serviceVersion = 0
    private var discoveredVersion = -1
    private var broken: Throwable? = null

    private fun checkOpen() {
        broken?.let { throw IllegalStateException(it.message ?: "蓝牙连接已断开，正在重连", it) }
    }

    private fun <T> settle(value: T, status: Int, target: CompletableDeferred<T>?, message: String) {
        if (status == BluetoothGatt.GATT_SUCCESS) target?.complete(value)
        else {
            Log.i(TAG, "$message status=$status")
            target?.completeExceptionally(IllegalStateException("$message（GATT $status）"))
        }
    }

    // The client interface must read via the deprecated overload on API 26-32 and via the new one
    // on API 33+; both are implemented and the runtime picks the right one.
    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(callbackGatt: BluetoothGatt, status: Int, state: Int) {
            scope.launch {
                if (gatt !== callbackGatt) return@launch
                Log.i(TAG, "connection status=$status state=$state")
                if (status == BluetoothGatt.GATT_SUCCESS && state == BluetoothProfile.STATE_CONNECTED) {
                    connection?.complete(Unit)
                    return@launch
                }
                // A failed status or a disconnect invalidates everything this client was waiting on.
                val reason = when {
                    status == BluetoothGatt.GATT_SUCCESS -> "手表蓝牙已断开，正在重连"
                    status == 133 -> "手表蓝牙连接被占用（GATT 133），正在重连"
                    else -> "手表蓝牙连接失败（GATT $status），正在重连"
                }
                fail(IllegalStateException(reason))
            }
        }

        override fun onServicesDiscovered(callbackGatt: BluetoothGatt, status: Int) {
            scope.launch {
                if (gatt !== callbackGatt) return@launch
                Log.i(TAG, "services status=$status")
                settle(Unit, status, discovery, "读取手表服务失败")
            }
        }

        override fun onServiceChanged(callbackGatt: BluetoothGatt) {
            scope.launch {
                if (gatt !== callbackGatt) return@launch
                serviceVersion++
                Log.i(TAG, "service changed; the next operation re-discovers services")
            }
        }

        override fun onCharacteristicWrite(callbackGatt: BluetoothGatt, value: BluetoothGattCharacteristic, status: Int) {
            scope.launch {
                if (gatt !== callbackGatt || value.uuid != LinkFrames.CHARACTERISTIC) return@launch
                settle(Unit, status, write, "手表拒绝数据分段")
            }
        }

        @Deprecated("only used below API 33")
        override fun onCharacteristicRead(callbackGatt: BluetoothGatt, value: BluetoothGattCharacteristic, status: Int) {
            if (Build.VERSION.SDK_INT < 33) deliver(callbackGatt, value.uuid, value.value ?: ByteArray(0), status)
        }

        override fun onCharacteristicRead(callbackGatt: BluetoothGatt, value: BluetoothGattCharacteristic, data: ByteArray, status: Int) {
            deliver(callbackGatt, value.uuid, data.copyOf(), status)
        }
    }

    private fun deliver(callbackGatt: BluetoothGatt, uuid: UUID, value: ByteArray, status: Int) {
        scope.launch {
            if (gatt !== callbackGatt || uuid != LinkFrames.CHARACTERISTIC) return@launch
            settle(value, status, read, "读取手表回应失败")
        }
    }

    /** Fail every pending operation once and mark the link unusable. */
    private fun fail(cause: Throwable) {
        if (broken == null) broken = cause
        val error = broken!!
        connection?.completeExceptionally(error)
        discovery?.completeExceptionally(error)
        write?.completeExceptionally(error)
        read?.completeExceptionally(error)
    }

    suspend fun connect() {
        trace("connect", "正在建立手表蓝牙连接…")
        broken = null
        characteristic = null
        discoveredVersion = -1
        val pending = CompletableDeferred<Unit>()
        connection = pending
        gatt = device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE) ?: error("无法建立蓝牙连接")
        try { withTimeout(CONNECT_TIMEOUT) { pending.await() } }
        catch (_: TimeoutCancellationException) { error("建立蓝牙连接超时，请保持手表活力记录打开") }
        mutex.withLock { discover() }
    }

    private suspend fun discover() {
        checkOpen()
        val connection = gatt ?: error("蓝牙连接已关闭")
        repeat(DISCOVER_ATTEMPTS) {
            // A Service Changed event while discovery ran means the metadata is already stale.
            val version = serviceVersion
            val pending = CompletableDeferred<Unit>()
            discovery = pending
            trace("services", "正在读取手表应用服务…")
            check(connection.discoverServices()) { "手表服务发现未启动，正在重连" }
            try { withTimeout(DISCOVER_TIMEOUT) { pending.await() } }
            catch (_: TimeoutCancellationException) { error("读取手表应用服务超时，请重新打开手表活力记录") }
            val service = connection.getService(LinkFrames.SERVICE)
            // Log the whole catalogue: when the cached attribute database is stale this list is the
            // only evidence of what the phone believes the watch exposes.
            val discovered = connection.services.map { it.uuid.toString().lowercase() }
            Log.i(TAG, "appServicePresent=${service != null} serviceCount=${discovered.size} services=$discovered")
            checkNotNull(service) {
                "未发现活力记录直连服务（手表暴露 ${discovered.size} 个服务）。请把手表活力记录更新到 0.7.1；" +
                    "若已更新，请关闭再打开手机蓝牙以清除蓝牙服务缓存后重试"
            }
            characteristic = service.getCharacteristic(LinkFrames.CHARACTERISTIC) ?: error(
                "活力记录服务已发现但缺少数据特征，通常是蓝牙服务缓存过期；请关闭再打开手机蓝牙后重试")
            discoveredVersion = version
            if (serviceVersion == version) return
        }
        check(discoveredVersion == serviceVersion) { "手表服务正在变化，稍后重连" }
    }

    private suspend fun ready() {
        checkOpen()
        if (discoveredVersion != serviceVersion) discover()
    }

    /** One acknowledged write. The payload must fit the default ATT MTU. */
    @Suppress("DEPRECATION")
    suspend fun write(value: ByteArray) = mutex.withLock {
        checkOpen()
        require(value.size in 1..LinkFrames.ATT_PAYLOAD) { "蓝牙分段长度错误（${value.size}/${LinkFrames.ATT_PAYLOAD} 字节）" }
        ready()
        val connection = gatt ?: error("蓝牙连接已关闭")
        val target = characteristic ?: error("数据通道未就绪")
        val pending = CompletableDeferred<Unit>()
        write = pending
        try {
            val accepted = if (Build.VERSION.SDK_INT >= 33) {
                connection.writeCharacteristic(target, value, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) == BluetoothStatusCodes.SUCCESS
            } else {
                target.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                target.value = value
                connection.writeCharacteristic(target)
            }
            check(accepted) { "蓝牙写入未启动，正在重连" }
            try { withTimeout(WRITE_TIMEOUT) { pending.await() } }
            catch (_: TimeoutCancellationException) { error("手表未回应蓝牙写入，请保持手表应用打开") }
        } finally { write = null }
    }

    suspend fun read(): ByteArray = mutex.withLock {
        ready()
        val target = characteristic ?: error("数据通道未就绪")
        val pending = CompletableDeferred<ByteArray>()
        read = pending
        try {
            check(gatt?.readCharacteristic(target) == true) { "蓝牙读取未启动，正在重连" }
            try { withTimeout(READ_TIMEOUT) { pending.await() } }
            catch (_: TimeoutCancellationException) { error("读取手表回应超时，请保持手表应用打开") }
        } finally { read = null }
    }

    /** Ask the controller for a short connection interval while a transfer is running. */
    fun prioritize(active: Boolean) {
        runCatching {
            gatt?.requestConnectionPriority(if (active) BluetoothGatt.CONNECTION_PRIORITY_HIGH else BluetoothGatt.CONNECTION_PRIORITY_BALANCED)
        }
    }

    fun close() {
        if (broken == null) broken = kotlinx.coroutines.CancellationException("蓝牙连接已关闭")
        val current = gatt
        gatt = null
        characteristic = null
        runCatching { current?.disconnect() }
        runCatching { current?.close() }
        val error = broken!!
        connection?.completeExceptionally(error)
        discovery?.completeExceptionally(error)
        write?.completeExceptionally(error)
        read?.completeExceptionally(error)
    }

    companion object {
        const val TAG = "VitalityLink"
        const val CONNECT_TIMEOUT = 25000L
        const val DISCOVER_TIMEOUT = 20000L
        const val WRITE_TIMEOUT = 12000L
        const val READ_TIMEOUT = 12000L
        const val DISCOVER_ATTEMPTS = 2
    }
}
