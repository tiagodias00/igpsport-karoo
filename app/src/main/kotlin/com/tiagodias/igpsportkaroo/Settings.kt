package com.tiagodias.igpsportkaroo

import android.content.Context
import com.tiagodias.igpsportkaroo.automation.AutomationSettings
import com.tiagodias.igpsportkaroo.automation.RideStart

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
    var lastRecording: Boolean
        get() = prefs.getBoolean("last_recording", false)
        set(value) { prefs.edit().putBoolean("last_recording", value).apply() }

    fun automation(): AutomationSettings = AutomationSettings(rideStart, lowBatteryAlerts, offAtRideEnd)

    companion object {
        /** The VS1200S's declared modes (HIGH, MID, FLASH HI, FLASH LO, CUSTOM 1) plus OFF. */
        val CHOOSABLE_MODES = listOf(1, 2, 4, 5, 64, 0)
    }
}
