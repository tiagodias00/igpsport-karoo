package com.tiagodias.igpsportkaroo.protocol

object LightModes {
    const val OFF = 0

    private val LABELS = mapOf(
        0 to "OFF", 1 to "HIGH", 2 to "MID", 3 to "LOW", 4 to "FLASH HI", 5 to "FLASH LO", 6 to "PULSE",
        7 to "HB HIGH", 8 to "HB MID", 9 to "HB LOW", 10 to "LB HIGH", 11 to "LB MID", 12 to "LB LOW",
        16 to "BOOST", 17 to "SOS",
    )

    fun label(mode: Int): String = LABELS[mode] ?: if (mode in 64..75) "CUSTOM ${mode - 63}" else "MODE $mode"

    /** The mode after [current] in [enabled], wrapping around. Off is never part of the cycle. */
    fun next(current: Int?, enabled: List<Int>): Int? {
        val cycle = enabled.filter { it != OFF }
        if (cycle.isEmpty()) return null
        val index = cycle.indexOf(current)
        return if (index < 0) cycle.first() else cycle[(index + 1) % cycle.size]
    }
}
