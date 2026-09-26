package com.tiagodias.igpsportkaroo

import android.content.Intent
import android.os.SystemClock
import com.tiagodias.igpsportkaroo.automation.Command
import com.tiagodias.igpsportkaroo.automation.RideAutomation
import com.tiagodias.igpsportkaroo.ble.BleScanner
import com.tiagodias.igpsportkaroo.ble.GattLightLink
import com.tiagodias.igpsportkaroo.ble.ScanMatch
import com.tiagodias.igpsportkaroo.light.LightHub
import com.tiagodias.igpsportkaroo.light.LightSession
import com.tiagodias.igpsportkaroo.protocol.LightModes
import com.tiagodias.igpsportkaroo.ui.CompactLightFieldDataType
import com.tiagodias.igpsportkaroo.ui.LightBatteryDataType
import com.tiagodias.igpsportkaroo.ui.LightFieldDataType
import io.hammerhead.karooext.KarooSystemService
import io.hammerhead.karooext.extension.KarooExtension
import io.hammerhead.karooext.internal.Emitter
import io.hammerhead.karooext.models.BatteryStatus
import io.hammerhead.karooext.models.ConnectionStatus
import io.hammerhead.karooext.models.DataPoint
import io.hammerhead.karooext.models.DataType
import io.hammerhead.karooext.models.Device
import io.hammerhead.karooext.models.DeviceEvent
import io.hammerhead.karooext.models.InRideAlert
import io.hammerhead.karooext.models.OnBatteryStatus
import io.hammerhead.karooext.models.OnConnectionStatus
import io.hammerhead.karooext.models.OnDataPoint
import io.hammerhead.karooext.models.ReleaseBluetooth
import io.hammerhead.karooext.models.RequestBluetooth
import io.hammerhead.karooext.models.RideState
import io.hammerhead.karooext.models.SystemNotification
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber
import java.util.Collections

class IgpsExtension : KarooExtension(EXTENSION_ID, BuildConfig.VERSION_NAME) {

    private lateinit var karooSystem: KarooSystemService
    /** Stray failures in any child are logged and contained so they can't take the extension process down. */
    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.IO + CoroutineExceptionHandler { _, e -> Timber.e(e, "Uncaught coroutine failure") },
    )

    /** The per-connectDevice loop translating session.state into DeviceEvents; owned the same way as [LightHub.session]. */
    @Volatile
    private var deviceJob: Job? = null

    private val settings by lazy { Settings(applicationContext) }

    /** Ride-start action and low-battery alerts; fed from the RideState consumer and the device loop. */
    private val automation by lazy {
        RideAutomation(
            settings = settings::automation,
            // Wall clock on purpose: the persisted state must survive reboots.
            initialRecording = RideAutomation.restoredRecording(
                lastRecording = settings.lastRecording,
                lastRecordingAt = settings.lastRecordingAt,
                now = System.currentTimeMillis(),
            ),
            onRecordingChanged = { recording -> settings.saveRecording(recording, System.currentTimeMillis()) },
        )
    }
    private var rideStateConsumer: String? = null

    override val types by lazy {
        listOf(
            LightFieldDataType(extension),
            LightFieldDataType.slim(extension),
            CompactLightFieldDataType(extension),
            LightBatteryDataType(extension),
        )
    }

    override fun onCreate() {
        super.onCreate()
        if (BuildConfig.DEBUG && Timber.forest().isEmpty()) Timber.plant(Timber.DebugTree())
        karooSystem = KarooSystemService(applicationContext)
        karooSystem.connect { connected ->
            if (!connected) return@connect
            Timber.i("Karoo system connected; requesting Bluetooth")
            karooSystem.dispatch(RequestBluetooth(extension))
            if (rideStateConsumer == null) {
                rideStateConsumer = karooSystem.addConsumer { state: RideState ->
                    val commands = automation.onRideState(isRecording = state !is RideState.Idle)
                    Timber.i("Ride state %s -> %s", state, commands)
                    execute(commands)
                }
            }
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
        val frameLog: ((String) -> Unit)? = if (BuildConfig.DEBUG) { line -> Timber.d(line) } else null
        // Monotonic: the OFF settle window and the reconnect rate limit must not jump with a wall-clock resync.
        val session = LightSession(
            GattLightLink(applicationContext), address, scope,
            now = { SystemClock.elapsedRealtime() },
            frameLog = frameLog,
        )
        deviceJob?.cancel()
        LightHub.session?.stop()
        LightHub.session = session
        session.start()
        val batteryTypeId = DataType.dataTypeId(extension, LightBatteryDataType.TYPE_ID)
        val job = scope.launch {
            var everConnected = false
            var lastReportedConnected: Boolean? = null
            var lastBattery: Int? = null
            while (isActive) {
                val s = session.state.value
                if (s.connected && !everConnected) {
                    everConnected = true
                }
                // Karoo fails a device after 120s SEARCHING (no auto-retry) but the VS1200S motion-sleeps longer than that, so once connected we keep reporting CONNECTED; GattLightLink reconnects on its own underneath.
                val reportedConnected = s.connected || everConnected
                if (reportedConnected != lastReportedConnected) {
                    emitter.onNext(OnConnectionStatus(if (reportedConnected) ConnectionStatus.CONNECTED else ConnectionStatus.SEARCHING))
                    lastReportedConnected = reportedConnected
                }
                s.batteryPercent?.let { pct ->
                    if (pct != lastBattery) {
                        emitter.onNext(OnBatteryStatus(BatteryStatus.fromPercentage(pct)))
                        lastBattery = pct
                        execute(automation.onBattery(pct))
                    }
                    // Karoo treats a sensor as idle without ~1 Hz data points, even if unchanged.
                    emitter.onNext(OnDataPoint(DataPoint(batteryTypeId, mapOf(DataType.Field.SINGLE to pct.toDouble()), uid)))
                }
                delay(1000)
            }
        }
        deviceJob = job
        emitter.setCancellable {
            job.cancel()
            if (deviceJob === job) deviceJob = null
            session.stop()
            if (LightHub.session === session) LightHub.session = null
        }
    }

    /** A hardware button (Karoo "bonus action") mapped in Settings → Controls; ids are in extension_info.xml. */
    override fun onBonusAction(actionId: String) {
        if (actionId == ACTION_OPEN_CONTROLS) {
            openControls()
            return
        }
        val session = LightHub.session
        val sent = when (actionId) {
            ACTION_NEXT_MODE -> session?.nextMode()
            ACTION_SOLID -> session?.selectSolid()
            ACTION_FLASH -> session?.selectFlash()
            ACTION_AUTO -> session?.selectAuto()
            ACTION_LIGHT_OFF -> session?.selectMode(LightModes.OFF)
            else -> {
                Timber.w("Unknown bonus action %s", actionId)
                return
            }
        } ?: false
        Timber.i("Bonus action %s (sent=%b)", actionId, sent)
    }

    /**
     * Opens the app page. Android 12 may silently block an activity start from a service in the background,
     * so if the page isn't on screen shortly after, a Karoo notification offers to open it instead.
     */
    private fun openControls() {
        val started = runCatching {
            startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
        Timber.i("Bonus action %s: startActivity %s", ACTION_OPEN_CONTROLS, if (started.isSuccess) "requested" else "failed: ${started.exceptionOrNull()}")
        scope.launch {
            delay(OPEN_CONTROLS_CHECK_MS)
            if (MainActivity.onScreen) {
                Timber.i("App page is on screen")
                return@launch
            }
            Timber.w("App page not on screen after %d ms (background start blocked?); offering a notification", OPEN_CONTROLS_CHECK_MS)
            karooSystem.dispatch(
                SystemNotification(
                    id = "igps-open-controls",
                    message = getString(R.string.control_title),
                    action = getString(R.string.open_settings),
                    actionIntent = SETTINGS_ACTION,
                ),
            )
        }
    }

    /**
     * Runs automation [commands]. Mode changes wait (up to [RIDE_START_WAIT_MS]) for the light to be connected
     * with its modes known: a ride usually starts right after the Karoo boots, before the light has connected.
     */
    private fun execute(commands: List<Command>) {
        commands.forEach { command ->
            when (command) {
                // Manual: with auto light on (the VS1200S default) a plain mode change would do nothing visible.
                is Command.SelectMode -> whenLightReady(command) { it.selectManualMode(command.mode) }
                Command.SelectAuto -> whenLightReady(command) { it.selectAuto() }
                Command.TurnOff -> {
                    // Ride end: don't wait for the light like whenLightReady does for a ride start, only act if
                    // it's connected right now.
                    val session = LightHub.session?.takeIf { it.state.value.connected }
                    if (session == null) {
                        Timber.w("%s dropped: the light is not connected", command)
                    } else {
                        Timber.i("%s (sent=%b)", command, session.selectMode(LightModes.OFF))
                    }
                }
                is Command.LowBatteryAlert -> {
                    Timber.i("Low battery alert: %d%%", command.percent)
                    karooSystem.dispatch(
                        InRideAlert(
                            id = "igps-low-battery",
                            icon = R.drawable.ic_light_glyph,
                            title = getString(R.string.low_battery_title, command.percent),
                            detail = getString(R.string.low_battery_detail),
                            autoDismissMs = 10_000L,
                            backgroundColor = R.color.alert_background,
                            textColor = R.color.alert_text,
                        ),
                    )
                }
            }
        }
    }

    private fun whenLightReady(command: Command, action: (LightSession) -> Boolean) {
        scope.launch {
            val session = withTimeoutOrNull(RIDE_START_WAIT_MS) {
                var ready = readySession()
                while (ready == null) {
                    delay(1000)
                    ready = readySession()
                }
                ready
            }
            if (session == null) {
                Timber.w("%s dropped: the light was not ready within %d ms", command, RIDE_START_WAIT_MS)
                return@launch
            }
            Timber.i("%s (sent=%b)", command, action(session))
        }
    }

    /** The current session once its light is connected and has reported its modes, else null. */
    private fun readySession(): LightSession? =
        LightHub.session?.takeIf { session -> session.state.value.let { it.connected && it.declaredModes.isNotEmpty() } }

    override fun onDestroy() {
        rideStateConsumer?.let(karooSystem::removeConsumer)
        rideStateConsumer = null
        deviceJob?.cancel()
        deviceJob = null
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
        const val RIDE_START_WAIT_MS = 60_000L
        const val OPEN_CONTROLS_CHECK_MS = 1_000L

        // Bonus action ids: must match extension_info.xml.
        const val ACTION_NEXT_MODE = "next-mode"
        const val ACTION_SOLID = "solid"
        const val ACTION_FLASH = "flash"
        const val ACTION_AUTO = "auto"
        const val ACTION_LIGHT_OFF = "light-off"
        const val ACTION_OPEN_CONTROLS = "open-controls"
    }
}
