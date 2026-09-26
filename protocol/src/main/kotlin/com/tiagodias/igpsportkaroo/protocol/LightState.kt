package com.tiagodias.igpsportkaroo.protocol

/** Everything currently known about the light. Null = not reported yet. */
data class LightState(
    val connected: Boolean = false,
    val mode: Int? = null,
    val batteryPercent: Int? = null,
    val remainingMinutes: Int? = null,
    val declaredModes: Map<Int, Boolean> = emptyMap(),
    /** True after we switched the light off: the light itself keeps reporting its remembered mode. */
    val poweredOff: Boolean = false,
    /** [SmartConfig] id to status, in the light's order. */
    val smartConfigs: Map<Int, Int> = emptyMap(),
    val autoBrightnessPercent: Int? = null,
    /** Auto light has switched the output off (daylight); the light still reports its mode as before. */
    val outputOff: Boolean = false,
    /** The last [LightModes.STEADY] / [LightModes.FLASHING] mode reported, so SOLID / FLASH go back to it. */
    val lastSteadyMode: Int? = null,
    val lastFlashMode: Int? = null,
    /**
     * True while auto light looks like it dimmed itself (docs/vs1200s-findings.md), from [AutoDimTracker].
     * Like [poweredOff], `apply()` must leave this untouched: it is owned and recomputed by the session.
     */
    val autoDimmed: Boolean = false,
) {
    /** Modes that can be selected right now (enabled on the light), in the light's order. */
    val enabledModes: List<Int> get() = declaredModes.filterValues { it }.keys.toList()

    val autoLightOn: Boolean get() = smartConfigs[SmartConfig.AUTO_LIGHT] == SmartConfig.ON

    /** The steady level SOLID goes to: the last one used, else the light's first. Null if none is known. */
    val steadyLevel: Int? get() = lastSteadyMode ?: LightModes.steadyLevels(enabledModes).firstOrNull()

    /** The flash level FLASH goes to: the last one used, else the light's first. Null if none is known. */
    val flashLevel: Int? get() = lastFlashMode ?: LightModes.flashLevels(enabledModes).firstOrNull()

    fun apply(update: LightUpdate): LightState {
        val newMode = update.mode
        return copy(
            mode = newMode ?: mode,
            batteryPercent = update.batteryPercent ?: batteryPercent,
            remainingMinutes = update.remainingMinutes ?: remainingMinutes,
            declaredModes = update.declaredModes ?: declaredModes,
            smartConfigs = update.smartConfigs ?: smartConfigs,
            autoBrightnessPercent = update.autoBrightnessPercent ?: autoBrightnessPercent,
            outputOff = update.outputOff ?: outputOff,
            lastSteadyMode = newMode?.takeIf { it in LightModes.STEADY } ?: lastSteadyMode,
            lastFlashMode = newMode?.takeIf { it in LightModes.FLASHING } ?: lastFlashMode,
        )
    }
}
