package com.lrh.healthvivo.watch

import android.annotation.SuppressLint
import android.bluetooth.*
import android.content.Context
import android.os.Build
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID

@SuppressLint("MissingPermission")
internal class BleGattLink(private val context: Context, private val device: BluetoothDevice, private val scope: CoroutineScope,
    private val stage: (String, String) -> Unit = { _, _ -> }) {
    private var gatt: BluetoothGatt? = null
    private val connected = CompletableDeferred<Unit>()
    private val discovery = CompletableDeferred<Unit>()
    private val mtuResult = CompletableDeferred<Int>()
    private var write: CompletableDeferred<Unit>? = null
    private var read: CompletableDeferred<ByteArray>? = null
    private val mutex = Mutex()
    private var rx: BluetoothGattCharacteristic? = null
    private var status: BluetoothGattCharacteristic? = null
    private var disconnected = false
    var payloadSize = 20; private set
    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, code: Int, state: Int) { scope.launch {
            if(gatt !== g) return@launch
            if(code == BluetoothGatt.GATT_SUCCESS && state == BluetoothProfile.STATE_CONNECTED) connected.complete(Unit)
            else if(state == BluetoothProfile.STATE_DISCONNECTED || code != BluetoothGatt.GATT_SUCCESS) {
                disconnected = true
                val failure = IllegalStateException("手表蓝牙通道已断开（$code），请打开手表应用后重连")
                connected.completeExceptionally(failure); discovery.completeExceptionally(failure)
                write?.completeExceptionally(failure); read?.completeExceptionally(failure)
            }
        } }
        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, code: Int) { scope.launch {
            if(gatt === g) mtuResult.complete(if(code == 0) mtu else 23)
        } }
        override fun onServicesDiscovered(g: BluetoothGatt, code: Int) { scope.launch {
            if(gatt !== g) return@launch
            if(code == 0) discovery.complete(Unit) else discovery.completeExceptionally(IllegalStateException("读取手表服务失败（$code）"))
        } }
        override fun onCharacteristicWrite(g: BluetoothGatt, c: BluetoothGattCharacteristic, code: Int) { scope.launch {
            if(gatt !== g || c.uuid != RX) return@launch
            if(code == 0) write?.complete(Unit) else write?.completeExceptionally(IllegalStateException("手表未接受数据（$code）"))
        } }
        @Deprecated("Legacy callback")
        override fun onCharacteristicRead(g: BluetoothGatt, c: BluetoothGattCharacteristic, code: Int) { deliver(g, c.uuid, c.value ?: byteArrayOf(), code) }
        override fun onCharacteristicRead(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray, code: Int) { deliver(g, c.uuid, value.copyOf(), code) }
    }
    private fun deliver(g: BluetoothGatt, uuid: UUID, bytes: ByteArray, code: Int) { scope.launch {
        if(gatt !== g || uuid != STATUS) return@launch
        if(code == 0) read?.complete(bytes) else read?.completeExceptionally(IllegalStateException("读取手表确认失败（$code）"))
    } }
    suspend fun connect() {
        stage("connect", "正在连接手表蓝牙链路…")
        gatt = device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE) ?: error("无法建立蓝牙连接")
        withTimeout(20000) { connected.await() }
        stage("mtu", "蓝牙已连接，正在准备传输通道…")
        val connection = gatt ?: error("连接已断开")
        val mtu = if(connection.requestMtu(247)) withTimeoutOrNull(4000) { mtuResult.await() } ?: 23 else 23
        payloadSize = (mtu - 3).coerceIn(20, 180)
        stage("services", "蓝牙已连接，正在查找手表应用服务…")
        check(connection.discoverServices()) { "无法读取手表应用服务" }
        withTimeout(15000) { discovery.await() }
        val service = connection.getService(SERVICE) ?: error("没有发现活力记录直连服务，请更新并打开手表应用")
        rx = service.getCharacteristic(RX) ?: error("手表数据服务不完整")
        status = service.getCharacteristic(STATUS) ?: error("手表确认服务不完整")
    }
    suspend fun write(bytes: ByteArray) = mutex.withLock {
        check(!disconnected && bytes.size <= payloadSize) { "蓝牙已断开或数据包过大" }
        val connection = gatt ?: error("蓝牙已断开")
        val characteristic = rx ?: error("数据通道未就绪")
        val result = CompletableDeferred<Unit>(); write = result
        try {
            val accepted = if(Build.VERSION.SDK_INT >= 33) connection.writeCharacteristic(characteristic, bytes, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) == BluetoothStatusCodes.SUCCESS
                else { characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT; characteristic.value = bytes; connection.writeCharacteristic(characteristic) }
            check(accepted) { "蓝牙忙，请重新连接" }
            withTimeout(10000) { result.await() }
        } finally { write = null }
    }
    suspend fun read(): ByteArray = mutex.withLock {
        check(!disconnected) { "蓝牙已断开，请重新连接" }
        val result = CompletableDeferred<ByteArray>(); read = result
        try { check(gatt?.readCharacteristic(status) == true) { "无法读取同步状态" }; withTimeout(10000) { result.await() } }
        finally { read = null }
    }
    fun close() {
        disconnected = true
        val connection = gatt; gatt = null
        runCatching { connection?.disconnect() }; runCatching { connection?.close() }
    }
    companion object {
        val SERVICE: UUID = UUID.fromString("9f7c7200-1771-42b9-bc36-d88bfb0952a0")
        val RX: UUID = UUID.fromString("9f7c7201-1771-42b9-bc36-d88bfb0952a0")
        val STATUS: UUID = UUID.fromString("9f7c7202-1771-42b9-bc36-d88bfb0952a0")
    }
}
