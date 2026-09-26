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
) {
    /** Modes that can be selected right now (enabled on the light), in the light's order. */
    val enabledModes: List<Int> get() = declaredModes.filterValues { it }.keys.toList()

    fun apply(update: LightUpdate): LightState = copy(
        mode = update.mode ?: mode,
        batteryPercent = update.batteryPercent ?: batteryPercent,
        remainingMinutes = update.remainingMinutes ?: remainingMinutes,
        declaredModes = update.declaredModes ?: declaredModes,
    )
}
