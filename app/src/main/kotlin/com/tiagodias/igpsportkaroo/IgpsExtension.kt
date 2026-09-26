package com.tiagodias.igpsportkaroo

import com.tiagodias.igpsportkaroo.ble.BleScanner
import com.tiagodias.igpsportkaroo.ble.GattLightLink
import com.tiagodias.igpsportkaroo.ble.ScanMatch
import com.tiagodias.igpsportkaroo.light.LightHub
import com.tiagodias.igpsportkaroo.light.LightSession
import com.tiagodias.igpsportkaroo.ui.LightBatteryDataType
import io.hammerhead.karooext.KarooSystemService
import io.hammerhead.karooext.extension.KarooExtension
import io.hammerhead.karooext.internal.Emitter
import io.hammerhead.karooext.models.BatteryStatus
import io.hammerhead.karooext.models.ConnectionStatus
import io.hammerhead.karooext.models.DataPoint
import io.hammerhead.karooext.models.DataType
import io.hammerhead.karooext.models.Device
import io.hammerhead.karooext.models.DeviceEvent
import io.hammerhead.karooext.models.OnBatteryStatus
import io.hammerhead.karooext.models.OnConnectionStatus
import io.hammerhead.karooext.models.OnDataPoint
import io.hammerhead.karooext.models.ReleaseBluetooth
import io.hammerhead.karooext.models.RequestBluetooth
import io.hammerhead.karooext.models.SystemNotification
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import timber.log.Timber
import java.util.Collections

class IgpsExtension : KarooExtension(EXTENSION_ID, BuildConfig.VERSION_NAME) {

    private lateinit var karooSystem: KarooSystemService
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override val types by lazy { listOf(LightBatteryDataType(extension)) }

    override fun onCreate() {
        super.onCreate()
        if (BuildConfig.DEBUG && Timber.forest().isEmpty()) Timber.plant(Timber.DebugTree())
        karooSystem = KarooSystemService(applicationContext)
        karooSystem.connect { connected ->
            if (!connected) return@connect
            Timber.i("Karoo system connected; requesting Bluetooth")
            karooSystem.dispatch(RequestBluetooth(extension))
            if (Permissions.missing(applicationContext).isNotEmpty()) {
                karooSystem.dispatch(
                    SystemNotification(
                        id = "igps-permissions",
                        message = getString(R.string.perm_needed),
                        action = getString(R.string.open_settings),
                        actionIntent = SETTINGS_ACTION,
                    ),
                )
            }
        }
    }

    /** Sensors → Add sensor: report every iGPSPORT light we can hear. */
    override fun startScan(emitter: Emitter<Device>) {
        val seen = Collections.synchronizedSet(mutableSetOf<String>())
        val job = scope.launch {
            BleScanner(applicationContext).scan().collect { s ->
                if (ScanMatch.isIgpsLight(s.name, s.serviceUuids) && seen.add(s.address)) {
                    Timber.i("Found iGPSPORT light %s (%s, rssi=%d)", s.address, s.name, s.rssi)
                    emitter.onNext(
                        Device(
                            extension,
                            UID_PREFIX + s.address,
                            listOf(DataType.dataTypeId(extension, LightBatteryDataType.TYPE_ID)),
                            s.name ?: "iGPSPORT light",
                        ),
                    )
                }
            }
        }
        emitter.setCancellable { job.cancel() }
    }

    /** Called by the Karoo for a paired light (also after reboots): rebuild everything from the uid. */
    override fun connectDevice(uid: String, emitter: Emitter<DeviceEvent>) {
        val address = uid.removePrefix(UID_PREFIX)
        Timber.i("connectDevice %s", address)
        val session = LightSession(GattLightLink(applicationContext), address, scope)
        LightHub.session?.stop()
        LightHub.session = session
        session.start()
        val batteryTypeId = DataType.dataTypeId(extension, LightBatteryDataType.TYPE_ID)
        val job = scope.launch {
            var lastConnected: Boolean? = null
            var lastBattery: Int? = null
            while (isActive) {
                val s = session.state.value
                if (s.connected != lastConnected) {
                    emitter.onNext(OnConnectionStatus(if (s.connected) ConnectionStatus.CONNECTED else ConnectionStatus.SEARCHING))
                    lastConnected = s.connected
                }
                s.batteryPercent?.let { pct ->
                    if (pct != lastBattery) {
                        emitter.onNext(OnBatteryStatus(BatteryStatus.fromPercentage(pct)))
                        lastBattery = pct
                    }
                    // Karoo treats a sensor as idle without ~1 Hz data points, even if unchanged.
                    emitter.onNext(OnDataPoint(DataPoint(batteryTypeId, mapOf(DataType.Field.SINGLE to pct.toDouble()), uid)))
                }
                delay(1000)
            }
        }
        emitter.setCancellable {
            job.cancel()
            session.stop()
            if (LightHub.session === session) LightHub.session = null
        }
    }

    override fun onDestroy() {
        LightHub.session?.stop()
        LightHub.session = null
        karooSystem.dispatch(ReleaseBluetooth(extension))
        karooSystem.disconnect()
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        const val EXTENSION_ID = "igpsport"
        const val UID_PREFIX = "igps-"
        const val SETTINGS_ACTION = "com.tiagodias.igpsportkaroo.SETTINGS"
        const val CONTROL_ACTION = "com.tiagodias.igpsportkaroo.CONTROL"
    }
}
