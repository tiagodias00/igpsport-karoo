package com.tiagodias.igpsportkaroo

import android.content.Context
import com.tiagodias.igpsportkaroo.automation.AutomationSettings
import com.tiagodias.igpsportkaroo.automation.RideStart
import com.tiagodias.igpsportkaroo.protocol.CustomModeConfig
import com.tiagodias.igpsportkaroo.protocol.CustomModeText

class Settings(context: Context) {
    private val prefs = context.getSharedPreferences("igps_settings", Context.MODE_PRIVATE)

    var rideStart: RideStart
        get() = RideStart.decode(prefs.getString("ride_start", null))
        set(value) { prefs.edit().putString("ride_start", value.encode()).apply() }

    var lowBatteryAlerts: Boolean
        get() = prefs.getBoolean("low_battery_alerts", true)
        set(value) { prefs.edit().putBoolean("low_battery_alerts", value).apply() }

    var offAtRideEnd: Boolean
        get() = prefs.getBoolean("off_at_ride_end", true)
        set(value) { prefs.edit().putBoolean("off_at_ride_end", value).apply() }

    /** Whether a ride was recording at the last ride-state event: tells a restart mid-ride from a ride start. */
    val lastRecording: Boolean
        get() = prefs.getBoolean("last_recording", false)

    /** When [lastRecording] was last written (wall clock, ms), null if never: an old one is not trusted. */
    val lastRecordingAt: Long?
        get() = prefs.getLong("last_recording_at", -1L).takeIf { it >= 0 }

    /** Persists one ride-state event: [recording] and [at] (wall clock, ms) together. */
    fun saveRecording(recording: Boolean, at: Long) {
        prefs.edit().putBoolean("last_recording", recording).putLong("last_recording_at", at).apply()
    }

    /** The first config ever read for custom slot [mode]: "Restore original" goes back to it (plan decision D3). */
    fun customOriginal(mode: Int): CustomModeConfig? = CustomModeText.decode(prefs.getString("custom_original_$mode", null))

    /** Keeps [config] as its slot's original, unless one is already kept. */
    fun rememberCustomOriginal(config: CustomModeConfig) {
        val key = "custom_original_${config.mode}"
        if (!prefs.contains(key)) prefs.edit().putString(key, CustomModeText.encode(config)).apply()
    }

    fun automation(): AutomationSettings = AutomationSettings(rideStart, lowBatteryAlerts, offAtRideEnd)

    companion object {
        /**
         * The modes offered as a ride-start choice: the VS1200S's declared modes (HIGH, MID, FLASH HI,
         * FLASH LO, CUSTOM 1 = LOW). OFF is not a ride-start choice.
         */
        val CHOOSABLE_MODES = listOf(1, 2, 4, 5, 64)
    }
}
