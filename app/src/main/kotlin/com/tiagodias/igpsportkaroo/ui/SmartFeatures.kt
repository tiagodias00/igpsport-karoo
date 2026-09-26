package com.tiagodias.igpsportkaroo.ui

import androidx.annotation.StringRes
import com.tiagodias.igpsportkaroo.R
import com.tiagodias.igpsportkaroo.protocol.LightState
import com.tiagodias.igpsportkaroo.protocol.SmartConfig

/**
 * The app page's "Light features" switches: which of the light's [SmartConfig]s to show, in what order, labelled
 * how. LUMEN_VARY (speed-based brightness) is left out: it needs speed from an iGPSPORT computer, so it does
 * nothing on a Karoo; the light's own setting is left as it is. SYNC_OFF is also left out: the Ride section's
 * "turn off the light when the ride ends" switch replaces it, since the light's own SYNC_OFF did not reliably
 * switch off on a Karoo link drop; the light's own setting is left as it is too.
 */
object SmartFeatures {
    data class Feature(val id: Int, @StringRes val label: Int, @StringRes val subtitle: Int?)

    private val ALL = listOf(
        Feature(SmartConfig.AUTO_LIGHT, R.string.feature_auto_light, R.string.feature_auto_light_subtitle),
        Feature(SmartConfig.AUTO_SLEEP, R.string.feature_auto_sleep, R.string.feature_auto_sleep_subtitle),
        Feature(SmartConfig.AUTO_LOW, R.string.feature_auto_low, null),
        Feature(SmartConfig.AUTO_LOWBAT, R.string.feature_auto_lowbat, null),
    )

    /** The features the light declares in [configs] (id to status), in the app page's order; unknown ids are hidden. */
    fun visible(configs: Map<Int, Int>): List<Feature> = ALL.filter { it.id in configs }

    /**
     * Whether feature [id]'s switch shows off only because the app paused it while the light is off (auto sleep,
     * [LightState.autoSleepPaused]): the page says so under the switch. Not once the light reports it on again.
     */
    fun pausedWhileOff(id: Int, state: LightState): Boolean =
        id == SmartConfig.AUTO_SLEEP && state.autoSleepPaused && state.smartConfigs[id] == SmartConfig.OFF
}
