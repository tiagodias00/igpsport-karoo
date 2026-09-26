package com.tiagodias.igpsportkaroo.protocol

/** What one frame from the light said. Null = not mentioned in this frame. */
data class LightUpdate(
    val mode: Int? = null,
    val batteryPercent: Int? = null,
    val remainingMinutes: Int? = null,
    /** Every mode the light declares, in its order, mapped to whether it is enabled. */
    val declaredModes: Map<Int, Boolean>? = null,
)
