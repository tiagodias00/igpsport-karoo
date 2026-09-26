package com.tiagodias.igpsportkaroo.ui

import androidx.annotation.StringRes
import com.tiagodias.igpsportkaroo.R
import com.tiagodias.igpsportkaroo.protocol.SmartConfig

/**
 * The app page's "Light features" switches: which of the light's [SmartConfig]s to show, in what order, labelled
 * how. LUMEN_VARY (speed-based brightness) is left out: it needs speed from an iGPSPORT computer, so it does
 * nothing on a Karoo; the light's own setting is left as it is.
 */
object SmartFeatures {
    data class Feature(val id: Int, @StringRes val label: Int, @StringRes val subtitle: Int?)

    private val ALL = listOf(
        Feature(SmartConfig.AUTO_LIGHT, R.string.feature_auto_light, R.string.feature_auto_light_subtitle),
        Feature(SmartConfig.AUTO_SLEEP, R.string.feature_auto_sleep, R.string.feature_auto_sleep_subtitle),
        Feature(SmartConfig.AUTO_LOW, R.string.feature_auto_low, null),
        Feature(SmartConfig.AUTO_LOWBAT, R.string.feature_auto_lowbat, null),
        Feature(SmartConfig.SYNC_OFF, R.string.feature_sync_off, R.string.feature_sync_off_subtitle),
    )

    /** The features the light declares in [configs] (id to status), in the app page's order; unknown ids are hidden. */
    fun visible(configs: Map<Int, Int>): List<Feature> = ALL.filter { it.id in configs }
}
