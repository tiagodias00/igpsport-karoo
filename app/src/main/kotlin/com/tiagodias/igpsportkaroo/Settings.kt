package com.tiagodias.igpsportkaroo

import android.content.Context
import com.tiagodias.igpsportkaroo.automation.AutomationSettings

class Settings(context: Context) {
    private val prefs = context.getSharedPreferences("igps_settings", Context.MODE_PRIVATE)

    var rideStartMode: Int?
        get() = prefs.getInt("ride_start_mode", -1).takeIf { it > 0 }
        set(value) { prefs.edit().putInt("ride_start_mode", value ?: -1).apply() }

    var lowBatteryAlerts: Boolean
        get() = prefs.getBoolean("low_battery_alerts", true)
        set(value) { prefs.edit().putBoolean("low_battery_alerts", value).apply() }

    var controlCenterShortcut: Boolean
        get() = prefs.getBoolean("cc_shortcut", true)
        set(value) { prefs.edit().putBoolean("cc_shortcut", value).apply() }

    fun automation(): AutomationSettings = AutomationSettings(rideStartMode, lowBatteryAlerts)

    companion object {
        /** The VS1200S's declared modes (HIGH, MID, FLASH HI, FLASH LO, CUSTOM 1) plus OFF. */
        val CHOOSABLE_MODES = listOf(1, 2, 4, 5, 64, 0)
    }
}
