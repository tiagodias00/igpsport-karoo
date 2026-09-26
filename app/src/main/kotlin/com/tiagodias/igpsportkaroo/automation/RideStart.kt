package com.tiagodias.igpsportkaroo.automation

import com.tiagodias.igpsportkaroo.protocol.LightModes

/** What the light does when a ride starts. Stored as "none", "auto" or "mode:<n>" ([encode] / [decode]). */
sealed interface RideStart {
    data object None : RideStart
    data object Auto : RideStart
    data class Mode(val mode: Int) : RideStart

    fun encode(): String = when (this) {
        None -> NONE
        Auto -> AUTO
        is Mode -> MODE_PREFIX + mode
    }

    companion object {
        private const val NONE = "none"
        private const val AUTO = "auto"
        private const val MODE_PREFIX = "mode:"

        /** Anything unrecognised (including OFF or a non-positive mode) is [None]. */
        fun decode(value: String?): RideStart = when {
            value == AUTO -> Auto
            value != null && value.startsWith(MODE_PREFIX) ->
                value.removePrefix(MODE_PREFIX).toIntOrNull()?.takeIf { it > 0 }?.let(::Mode) ?: None
            else -> None
        }

        /** The app page's options: don't change, AUTO, then the steady levels and the flash levels of [modes]. */
        fun choices(modes: List<Int>): List<RideStart> =
            listOf(None, Auto) + (LightModes.steadyLevels(modes) + LightModes.flashLevels(modes)).map(::Mode)
    }
}
