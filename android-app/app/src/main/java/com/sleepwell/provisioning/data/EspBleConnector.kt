package com.sleepwell.provisioning.data

import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.*
import android.content.Context
import android.os.ParcelUuid
import android.util.Log
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/** One foreground GATT session; all blocking operations run on Dispatchers.IO. */
@SuppressLint("MissingPermission")
@Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
class EspBleConnector(private val context: Context) {
    companion object {
        private const val TAG = "EspBleProvisioning"
        val SERVICE: UUID = UUID.fromString("a8e10001-73bd-4d74-a613-0b9c78d2f001")
        private val RX = UUID.fromString("a8e10002-73bd-4d74-a613-0b9c78d2f001")
        private val TX = UUID.fromString("a8e10003-73bd-4d74-a613-0b9c78d2f001")
        private val CCCD = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    }

    @Volatile private var gatt: BluetoothGatt? = null
    @Volatile private var rx: BluetoothGattCharacteristic? = null
    private var ready = CompletableFuture<Unit>()
    private val writes = LinkedBlockingQueue<Int>()
    private val incoming = LinkedBlockingQueue<ByteArray>()
    @Volatile private var disconnectReason: String? = null

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (g !== gatt) return
            Log.i(TAG, "Connection state=$newState status=$status")
            if (status == BluetoothGatt.GATT_SUCCESS && newState == BluetoothProfile.STATE_CONNECTED) {
                if (!g.discoverServices()) fail("无法读取蓝牙服务，请重新连接")
            } else if (status != BluetoothGatt.GATT_SUCCESS || newState == BluetoothProfile.STATE_DISCONNECTED) {
                fail("蓝牙连接已断开，请返回首页重新连接（状态 $status）")
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            if (g !== gatt) return
            Log.i(TAG, "Service discovery status=$status")
            val service = g.getService(SERVICE)
            val tx = service?.getCharacteristic(TX)
            rx = service?.getCharacteristic(RX)
            val descriptor = tx?.getDescriptor(CCCD)
            if (status != BluetoothGatt.GATT_SUCCESS || rx == null || tx == null || descriptor == null) {
                fail("设备不支持蓝牙配网，请先更新板子固件")
                return
            }
            if (!g.setCharacteristicNotification(tx, true)) {
                fail("无法开启蓝牙配网响应")
                return
            }
            descriptor.value = BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
            if (!g.writeDescriptor(descriptor)) fail("无法订阅蓝牙配网响应")
        }

        override fun onDescriptorWrite(g: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            if (g !== gatt || descriptor.uuid != CCCD) return
            Log.i(TAG, "Indication subscription status=$status")
            if (status == BluetoothGatt.GATT_SUCCESS) ready.complete(Unit)
            else fail("蓝牙响应订阅失败（状态 $status）")
        }

        override fun onCharacteristicWrite(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            if (g === gatt) Log.i(TAG, "Write callback status=$status characteristic=${characteristic.uuid}")
            if (g === gatt && characteristic.uuid == RX) writes.offer(status)
        }

        override fun onCharacteristicChanged(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            if (g === gatt && characteristic.uuid == TX) incoming.offer(characteristic.value.copyOf())
        }

        override fun onCharacteristicChanged(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) {
            if (g === gatt && characteristic.uuid == TX) incoming.offer(value.copyOf())
        }
    }

    fun connect(name: String) {
        disconnect()
        ready = CompletableFuture()
        disconnectReason = null
        writes.clear()
        incoming.clear()
        val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter
            ?: error("这台手机不支持蓝牙")
        check(adapter.isEnabled) { "请先开启手机蓝牙，再重试" }
        val scanner = adapter.bluetoothLeScanner ?: error("无法启动蓝牙扫描")
        val found = CompletableFuture<BluetoothDevice>()
        val scan = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                if (result.scanRecord?.deviceName == name || result.device.name == name) found.complete(result.device)
            }
            override fun onScanFailed(errorCode: Int) {
                found.completeExceptionally(IllegalStateException("蓝牙扫描失败（$errorCode），请稍后重试"))
            }
        }
        try {
            Log.i(TAG, "Starting device scan")
            scanner.startScan(
                listOf(ScanFilter.Builder().setServiceUuid(ParcelUuid(SERVICE)).build()),
                ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(), scan,
            )
            val device = try {
                found.get(20, TimeUnit.SECONDS)
            } finally {
                scanner.stopScan(scan)
            }
            gatt = device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
                ?: error("无法建立蓝牙连接")
            ready.get(20, TimeUnit.SECONDS)
        } catch (e: Exception) {
            disconnect()
            if (e is InterruptedException) throw e
            throw IllegalStateException(e.cause?.message ?: e.message ?: "未找到设备或连接超时，请确认名称、蓝色呼吸灯及手机蓝牙；Android 10–11 还需开启定位", e)
        }
    }

    @Synchronized
    fun request(method: String, path: String, token: String?, body: JSONObject?): JSONObject {
        val connection = gatt ?: error("蓝牙连接已断开，请重新连接")
        val characteristic = rx ?: error("蓝牙服务尚未就绪")
        val payload = (JSONObject().put("method", method).put("path", path)
            .put("token", token ?: "").put("body", body ?: JSONObject()).toString() + "\n")
            .toByteArray(Charsets.UTF_8)
        require(payload.size <= 1024) { "配网数据过长" }
        try {
            Log.i(TAG, "Request $method $path bytes=${payload.size}")
            for ((index, chunk) in payload.toList().chunked(20).withIndex()) {
                Log.i(TAG, "Write $path fragment=$index bytes=${chunk.size}")
                check(gatt === connection) { "蓝牙连接已断开" }
                characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                characteristic.value = chunk.toByteArray()
                check(connection.writeCharacteristic(characteristic)) { "蓝牙写入失败，请重新连接" }
                check(writes.poll(5, TimeUnit.SECONDS) == BluetoothGatt.GATT_SUCCESS) { "蓝牙写入超时或失败" }
            }
            val frame = BleResponseFrame()
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(45)
            while (true) {
                val remaining = deadline - System.nanoTime()
                check(remaining > 0) { "蓝牙响应超时，请重新连接设备" }
                val fragment = incoming.poll(remaining, TimeUnit.NANOSECONDS) ?: error("蓝牙响应超时")
                if (fragment.isEmpty()) error(disconnectReason ?: "蓝牙连接已关闭，请重新连接设备")
                val completed = frame.append(fragment)
                if (completed != null) {
                    // Decode only after reassembly: UTF-8 characters may span ATT packets.
                    val envelope = JSONObject(completed)
                    Log.i(TAG, "Response $path status=${envelope.optInt("status")} bytes=${completed.toByteArray(Charsets.UTF_8).size}")
                    val response = envelope.getJSONObject("body")
                    if (envelope.getInt("status") !in 200..299) {
                        val error = response.optJSONObject("error")
                        throw ProvisioningApiException(error?.optString("code") ?: "BLE_ERROR", error?.optString("message") ?: "设备请求失败")
                    }
                    return response
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Request $path failed: ${e.javaClass.simpleName}: ${e.message}")
            if (e !is ProvisioningApiException) disconnect()
            throw e
        }
    }

    private fun fail(message: String) {
        disconnectReason = message
        Log.w(TAG, message)
        ready.completeExceptionally(IllegalStateException(message))
        disconnect()
    }

    fun disconnect() {
        val previous = gatt
        gatt = null
        rx = null
        ready.completeExceptionally(IllegalStateException("蓝牙连接已关闭"))
        writes.offer(-1)
        incoming.offer(byteArrayOf())
        runCatching { previous?.disconnect() }
        runCatching { previous?.close() }
    }
}
