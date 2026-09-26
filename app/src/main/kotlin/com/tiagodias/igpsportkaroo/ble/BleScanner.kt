package com.tiagodias.igpsportkaroo.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import timber.log.Timber

@SuppressLint("MissingPermission")
class BleScanner(private val context: Context) {
    data class Seen(val address: String, val name: String?, val serviceUuids: List<String>, val rssi: Int)

    /** Unfiltered scan: the light doesn't advertise its UART service, so matching happens in [ScanMatch]. */
    fun scan(): Flow<Seen> = callbackFlow {
        val scanner = (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)
            ?.adapter?.bluetoothLeScanner
        if (scanner == null) {
            Timber.w("No BLE scanner (Bluetooth off?)")
            close()
            return@callbackFlow
        }
        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val record = result.scanRecord
                trySend(
                    Seen(
                        address = result.device.address,
                        name = record?.deviceName,
                        serviceUuids = record?.serviceUuids?.map { it.uuid.toString() } ?: emptyList(),
                        rssi = result.rssi,
                    ),
                )
            }

            override fun onScanFailed(errorCode: Int) {
                Timber.w("BLE scan failed: %d", errorCode)
            }
        }
        // BALANCED, not LOW_LATENCY: aggressive scans starve the Karoo's other sensor links.
        scanner.startScan(null, ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_BALANCED).build(), callback)
        awaitClose { runCatching { scanner.stopScan(callback) } }
    }
}
