package com.tiagodias.igpsportkaroo.protocol

/** What one frame from the light said. Null = not mentioned in this frame. */
data class LightUpdate(
    val mode: Int? = null,
    val batteryPercent: Int? = null,
    val remainingMinutes: Int? = null,
    /** Every mode the light declares, in its order, mapped to whether it is enabled. */
    val declaredModes: Map<Int, Boolean>? = null,
    /** Every smart config the light declares ([SmartConfig] id), in its order, mapped to its status. */
    val smartConfigs: Map<Int, Int>? = null,
    /** The brightness auto light currently drives the light at. */
    val autoBrightnessPercent: Int? = null,
    /** True when a run-time report carries no run time: auto light has switched the output off (daylight). */
    val outputOff: Boolean? = null,
)
