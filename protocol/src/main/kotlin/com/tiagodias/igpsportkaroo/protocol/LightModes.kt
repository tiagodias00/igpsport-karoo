package com.tiagodias.igpsportkaroo.protocol

object LightModes {
    const val OFF = 0

    /** Modes that light continuously (SOLID). Custom modes (64+) are in neither group. */
    val STEADY = setOf(1, 2, 3, 7, 8, 9, 10, 11, 12, 16)

    /** Modes that blink (FLASH). */
    val FLASHING = setOf(4, 5, 6, 17)

    private val LABELS = mapOf(
        0 to "OFF", 1 to "HIGH", 2 to "MID", 3 to "LOW", 4 to "FLASH HI", 5 to "FLASH LO", 6 to "PULSE",
        7 to "HB HIGH", 8 to "HB MID", 9 to "HB LOW", 10 to "LB HIGH", 11 to "LB MID", 12 to "LB LOW",
        16 to "BOOST", 17 to "SOS",
    )

    private val SHORT_LABELS = mapOf(
        0 to "OFF", 1 to "HI", 2 to "MID", 3 to "LO", 4 to "FL HI", 5 to "FL LO", 6 to "PULSE",
        7 to "HB HI", 8 to "HB MID", 9 to "HB LO", 10 to "LB HI", 11 to "LB MID", 12 to "LB LO",
        16 to "BOOST", 17 to "SOS",
    )

    fun label(mode: Int): String = LABELS[mode] ?: if (mode in 64..75) "CUSTOM ${mode - 63}" else "MODE $mode"

    /** A label short enough for a one-row field. */
    fun shortLabel(mode: Int): String = SHORT_LABELS[mode] ?: if (mode in 64..75) "C${mode - 63}" else "M$mode"

    /** The mode after [current] in [enabled], wrapping around. Off is never part of the cycle. */
    fun next(current: Int?, enabled: List<Int>): Int? {
        val cycle = enabled.filter { it != OFF }
        if (cycle.isEmpty()) return null
        val index = cycle.indexOf(current)
        return if (index < 0) cycle.first() else cycle[(index + 1) % cycle.size]
    }

    /** The light's enabled steady modes, in its order. */
    fun steadyLevels(enabled: List<Int>): List<Int> = enabled.filter { it in STEADY }

    /** The light's enabled flash modes, in its order. */
    fun flashLevels(enabled: List<Int>): List<Int> = enabled.filter { it in FLASHING }
}
