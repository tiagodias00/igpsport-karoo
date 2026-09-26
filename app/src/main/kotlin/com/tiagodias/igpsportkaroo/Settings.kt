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

    var controlCenterShortcut: Boolean
        get() = prefs.getBoolean("cc_shortcut", true)
        set(value) { prefs.edit().putBoolean("cc_shortcut", value).apply() }

    fun automation(): AutomationSettings = AutomationSettings(rideStart, lowBatteryAlerts)

    companion object {
        /** The VS1200S's declared modes (HIGH, MID, FLASH HI, FLASH LO, CUSTOM 1) plus OFF. */
        val CHOOSABLE_MODES = listOf(1, 2, 4, 5, 64, 0)
    }
}
