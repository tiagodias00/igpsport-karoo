package com.tiagodias.igpsportkaroo.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import com.tiagodias.igpsportkaroo.protocol.IgpsProtocol
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import timber.log.Timber
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * Raw android.bluetooth link to the light's Nordic UART service.
 *
 * - Scan-then-connect: a blind connectGatt on a busy Karoo radio often hangs; connecting right
 *   after an advertisement lands in the light's listen window (pattern from KarooB54).
 * - One GATT operation in flight: chunks are written one at a time, each after the previous ack.
 * - Reconnects with 1/2/4/8 s backoff until the flow is cancelled.
 */
@SuppressLint("MissingPermission")
class GattLightLink(private val context: Context) : LightLink {

    private val adapter: BluetoothAdapter? by lazy {
        (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
    }

    private val lock = Any()
    private var gatt: BluetoothGatt? = null
    private var writeChar: BluetoothGattCharacteristic? = null
    private val pending = ArrayDeque<ByteArray>()
    private var writeInFlight = false

    override fun send(frame: ByteArray): Boolean {
        synchronized(lock) {
            if (gatt == null || writeChar == null) return false
            IgpsProtocol.chunks(frame).forEach { pending.addLast(it) }
            pumpLocked()
        }
        return true
    }

    @Suppress("DEPRECATION") // Karoo 3 is Android 12: the API 33 write overload doesn't exist there.
    private fun pumpLocked() {
        if (writeInFlight) return
        val g = gatt ?: return
        val c = writeChar ?: return
        val next = pending.removeFirstOrNull() ?: return
        c.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        c.value = next
        writeInFlight = g.writeCharacteristic(c)
        if (!writeInFlight) {
            Timber.w("GATT write rejected; dropping %d queued chunks", pending.size)
            pending.clear()
        }
    }

    private fun clearLocked() {
        gatt = null
        writeChar = null
        pending.clear()
        writeInFlight = false
    }

    override fun connect(address: String): Flow<LinkEvent> = callbackFlow {
        val bt = adapter
        if (bt == null || !BluetoothAdapter.checkBluetoothAddress(address)) {
            Timber.w("Cannot connect to %s (no adapter or bad address)", address)
            close()
            return@callbackFlow
        }
        val device = bt.getRemoteDevice(address)
        val active = AtomicBoolean(true)
        val attempt = AtomicInteger(0)
        val scanCallbackRef = AtomicReference<ScanCallback?>(null)
        val pendingWriteChar = AtomicReference<BluetoothGattCharacteristic?>(null)
        var startConnect: () -> Unit = {}

        fun scheduleRetry() {
            if (!active.get()) return
            val backoffMs = minOf(1000L shl attempt.getAndIncrement().coerceAtMost(3), 8000L)
            launch {
                delay(backoffMs)
                if (active.get()) startConnect()
            }
        }

        val callback = object : BluetoothGattCallback() {
            override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
                when (newState) {
                    BluetoothProfile.STATE_CONNECTED -> {
                        Timber.i("GATT connected (status=%d); discovering services", status)
                        g.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH)
                        g.discoverServices()
                    }
                    BluetoothProfile.STATE_DISCONNECTED -> {
                        Timber.i("GATT disconnected (status=%d)", status)
                        synchronized(lock) { if (gatt === g) clearLocked() }
                        g.close()
                        trySend(LinkEvent.Disconnected)
                        scheduleRetry()
                    }
                }
            }

            @Suppress("DEPRECATION")
            override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
                val service = g.getService(UART_SERVICE)
                val write = service?.getCharacteristic(UART_WRITE)
                val notify = service?.getCharacteristic(UART_NOTIFY)
                val cccd = notify?.getDescriptor(CCCD)
                if (write == null || notify == null || cccd == null) {
                    Timber.w("UART service missing on %s (see docs/vs1200s-findings.md)", address)
                    g.disconnect()
                    return
                }
                g.setCharacteristicNotification(notify, true)
                cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                pendingWriteChar.set(write)
                g.writeDescriptor(cccd)
            }

            override fun onDescriptorWrite(g: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
                if (descriptor.uuid != CCCD) return
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    Timber.w("Enabling notifications failed: %d", status)
                    g.disconnect()
                    return
                }
                synchronized(lock) { writeChar = pendingWriteChar.getAndSet(null) }
                attempt.set(0)
                trySend(LinkEvent.Connected)
            }

            override fun onCharacteristicWrite(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
                if (status != BluetoothGatt.GATT_SUCCESS) Timber.w("Chunk write failed: %d", status)
                synchronized(lock) {
                    writeInFlight = false
                    pumpLocked()
                }
            }

            // Only the pre-API-33 callback: Karoo 3 is Android 12, and API 33+ still calls this one too
            // (overriding both would deliver every notification twice there).
            @Deprecated("Deprecated in Java")
            @Suppress("DEPRECATION")
            override fun onCharacteristicChanged(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
                if (characteristic.uuid == UART_NOTIFY) {
                    characteristic.value?.let { trySend(LinkEvent.Fragment(it.copyOf())) }
                }
            }
        }

        startConnect = {
            val scanner = bt.bluetoothLeScanner
            if (scanner == null) {
                Timber.w("No BLE scanner (Bluetooth off?); retrying")
                scheduleRetry()
            } else {
                val scanCallback = object : ScanCallback() {
                    override fun onScanResult(callbackType: Int, result: ScanResult) {
                        if (!scanCallbackRef.compareAndSet(this, null)) return
                        runCatching { scanner.stopScan(this) }
                        Timber.i("Found %s (rssi=%d); connecting", address, result.rssi)
                        val g = device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
                        synchronized(lock) { gatt = g }
                    }

                    override fun onScanFailed(errorCode: Int) {
                        Timber.w("Connect scan failed: %d", errorCode)
                        scanCallbackRef.compareAndSet(this, null)
                        scheduleRetry()
                    }
                }
                scanCallbackRef.getAndSet(scanCallback)?.let { old -> runCatching { scanner.stopScan(old) } }
                scanner.startScan(
                    listOf(ScanFilter.Builder().setDeviceAddress(address).build()),
                    ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_BALANCED).build(),
                    scanCallback,
                )
            }
        }
        startConnect()

        awaitClose {
            active.set(false)
            scanCallbackRef.getAndSet(null)?.let { cb -> runCatching { bt.bluetoothLeScanner?.stopScan(cb) } }
            synchronized(lock) {
                gatt?.let { g -> runCatching { g.disconnect(); g.close() } }
                clearLocked()
            }
        }
    }

    private companion object {
        val UART_SERVICE: UUID = UUID.fromString(IgpsProtocol.UART_SERVICE)
        val UART_WRITE: UUID = UUID.fromString(IgpsProtocol.UART_WRITE)
        val UART_NOTIFY: UUID = UUID.fromString(IgpsProtocol.UART_NOTIFY)
        val CCCD: UUID = UUID.fromString(IgpsProtocol.CCCD)
    }
}
