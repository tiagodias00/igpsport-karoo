package com.tiagodias.igpsportkaroo.automation

data class AutomationSettings(val rideStart: RideStart, val lowBatteryAlerts: Boolean)

/** What the extension should do in response to a ride or battery event. */
sealed interface Command {
    data class SelectMode(val mode: Int) : Command
    data object SelectAuto : Command
    data class LowBatteryAlert(val percent: Int) : Command
}

/**
 * Pure ride automation: the ride-start action and the low-battery alerts. [settings] is read on every
 * event, so changes on the app page apply right away. Thread-safe: the ride-state consumer and the device
 * loop call it from different threads.
 */
class RideAutomation(private val settings: () -> AutomationSettings) {
    private var primed = false
    private var recording = false
    private val alerted = mutableSetOf<Int>()

    /**
     * On the transition to recording (a new ride, not a resume), the configured ride-start command. The first
     * event only records the state: already recording then means the extension restarted mid-ride.
     */
    @Synchronized
    fun onRideState(isRecording: Boolean): List<Command> {
        val started = primed && isRecording && !recording
        primed = true
        recording = isRecording
        if (!started) return emptyList()
        return when (val start = settings().rideStart) {
            RideStart.None -> emptyList()
            RideStart.Auto -> listOf(Command.SelectAuto)
            is RideStart.Mode -> listOf(Command.SelectMode(start.mode))
        }
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
    }
}
