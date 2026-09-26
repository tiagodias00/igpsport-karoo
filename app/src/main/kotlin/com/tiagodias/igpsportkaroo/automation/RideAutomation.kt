package com.tiagodias.igpsportkaroo.automation

import kotlin.math.abs

data class AutomationSettings(val rideStart: RideStart, val lowBatteryAlerts: Boolean, val offAtRideEnd: Boolean)

/** What the extension should do in response to a ride or battery event. */
sealed interface Command {
    data class SelectMode(val mode: Int) : Command
    data object SelectAuto : Command
    data object TurnOff : Command
    data class LowBatteryAlert(val percent: Int) : Command
}

/**
 * Pure ride automation: the ride-start action and the low-battery alerts. [settings] is read on every
 * event, so changes on the app page apply right away. Thread-safe: the ride-state consumer and the device
 * loop call it from different threads.
 *
 * Whether a ride is recording survives extension restarts: [initialRecording] is the last state persisted,
 * and [onRecordingChanged] persists every ride state seen. Karoo does not send the current ride state when
 * the consumer registers (device log 13:46), so the first event can be a real ride start and is not ignored;
 * a restart mid-ride is told apart by the persisted state instead (see [restoredRecording] for a stale one).
 */
class RideAutomation(
    private val settings: () -> AutomationSettings,
    initialRecording: Boolean = false,
    private val onRecordingChanged: (Boolean) -> Unit = {},
) {
    private var recording = initialRecording
    private val alerted = mutableSetOf<Int>()

    /**
     * On the transition to recording (a new ride, not a resume), the configured ride-start command; on the
     * transition back to idle (a ride end, not a pause: paused rides are still reported as recording),
     * [Command.TurnOff] when configured.
     */
    @Synchronized
    fun onRideState(isRecording: Boolean): List<Command> {
        val started = isRecording && !recording
        val ended = !isRecording && recording
        recording = isRecording
        onRecordingChanged(isRecording)
        val commands = mutableListOf<Command>()
        if (started) {
            when (val start = settings().rideStart) {
                RideStart.None -> Unit
                RideStart.Auto -> commands += Command.SelectAuto
                is RideStart.Mode -> commands += Command.SelectMode(start.mode)
            }
        }
        if (ended && settings().offAtRideEnd) commands += Command.TurnOff
        return commands
    }

    /** One alert when [percent] first reaches one or more of [THRESHOLDS]; charging above [RESET_ABOVE] re-arms them. */
    @Synchronized
    fun onBattery(percent: Int): List<Command> {
        if (percent > RESET_ABOVE) {
            alerted.clear()
            return emptyList()
        }
        if (!settings().lowBatteryAlerts) return emptyList()
        val crossed = THRESHOLDS.filter { percent <= it && it !in alerted }
        if (crossed.isEmpty()) return emptyList()
        alerted += crossed
        return listOf(Command.LowBatteryAlert(percent))
    }

    companion object {
        val THRESHOLDS = listOf(20, 10)
        const val RESET_ABOVE = 30

        /** How long a persisted "recording" is trusted: an older one most likely missed its ride's end. */
        const val RECORDING_STATE_MAX_AGE_MS = 24 * 60 * 60 * 1000L

        /**
         * The [initialRecording] to seed from the persisted state: [lastRecording], saved at [lastRecordingAt]
         * (wall clock, so it survives reboots), is trusted only when saved within [RECORDING_STATE_MAX_AGE_MS] of
         * [now], either way (a clock resynced backwards is tolerated as much as time passing). Otherwise the
         * ride's Idle event was probably missed (e.g. the Karoo powered off mid-ride), and trusting it would
         * swallow the next real ride start.
         */
        fun restoredRecording(lastRecording: Boolean, lastRecordingAt: Long?, now: Long): Boolean =
            lastRecording && lastRecordingAt != null && abs(now - lastRecordingAt) < RECORDING_STATE_MAX_AGE_MS
    }
}
