package com.tiagodias.igpsportkaroo

import android.content.SharedPreferences
import com.tiagodias.igpsportkaroo.light.SleepPause

/**
 * Each light's [SleepPause], one key per address, so a restart or a reboot still switches a paused auto sleep back
 * on instead of leaving it off for good, and one light's record never replaces another's.
 *
 * A light that is unpaired while paused keeps its key. Paired again later, it is assumed off at the first connect
 * and its auto sleep is switched on at the first mode, even if the user has since switched it off elsewhere: from
 * here, that can't be told apart from our own pause.
 *
 * Writes use `commit()`: callers never hold the session's command lock, and the record must survive the Karoo
 * powering off right after a ride-end OFF.
 */
class SleepPauseStore(private val prefs: SharedPreferences) {
    fun load(address: String): SleepPause {
        migrate()
        val name = prefs.getString(key(address), null) ?: return SleepPause.NONE
        return SleepPause.entries.firstOrNull { it.name == name } ?: SleepPause.NONE
    }

    fun save(address: String, pause: SleepPause) {
        migrate()
        val edit = prefs.edit()
        if (pause == SleepPause.NONE) edit.remove(key(address)) else edit.putString(key(address), pause.name)
        edit.commit()
    }

    /** The first version kept one light's address under [LEGACY_KEY]: it becomes that light's own record. */
    private fun migrate() {
        val legacy = prefs.getString(LEGACY_KEY, null) ?: return
        val edit = prefs.edit().remove(LEGACY_KEY)
        if (!prefs.contains(key(legacy))) edit.putString(key(legacy), SleepPause.PAUSED.name)
        edit.commit()
    }

    private fun key(address: String) = "sleep_pause_$address"

    private companion object {
        const val LEGACY_KEY = "sleep_paused_for"
    }
}
