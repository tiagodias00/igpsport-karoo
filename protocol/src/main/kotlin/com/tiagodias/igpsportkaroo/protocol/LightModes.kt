package com.tiagodias.igpsportkaroo.protocol

object LightModes {
    const val OFF = 0

    /** CUSTOM 1: on the VS1200S it is the low steady level (~700 min run time; docs/vs1200s-findings.md). */
    const val CUSTOM_LOW = 64

    private val CUSTOM = 64..75

    /** Steady modes, brightest first: the SOLID cycle order. Custom modes (dimmest) follow, ascending. */
    private val STEADY_RANK = listOf(16, 1, 7, 10, 2, 8, 11, 3, 9, 12) + CUSTOM

    /** Flash modes in FLASH cycle order. */
    private val FLASH_RANK = listOf(4, 5, 6, 17)

    /** Modes that light continuously (SOLID). Custom modes count: they are steady on this light family. */
    val STEADY: Set<Int> = STEADY_RANK.toSet()

    /** Modes that blink (FLASH). */
    val FLASHING: Set<Int> = FLASH_RANK.toSet()

    private val LABELS = mapOf(
        0 to "OFF", 1 to "HIGH", 2 to "MID", 3 to "LOW", 4 to "FLASH HI", 5 to "FLASH LO", 6 to "PULSE",
        7 to "HB HIGH", 8 to "HB MID", 9 to "HB LOW", 10 to "LB HIGH", 11 to "LB MID", 12 to "LB LOW",
        16 to "BOOST", 17 to "SOS", CUSTOM_LOW to "LOW",
    )

    private val SHORT_LABELS = mapOf(
        0 to "OFF", 1 to "HI", 2 to "MID", 3 to "LO", 4 to "FL HI", 5 to "FL LO", 6 to "PULSE",
        7 to "HB HI", 8 to "HB MID", 9 to "HB LO", 10 to "LB HI", 11 to "LB MID", 12 to "LB LO",
        16 to "BOOST", 17 to "SOS", CUSTOM_LOW to "LO",
    )

    fun label(mode: Int): String = LABELS[mode] ?: if (mode in CUSTOM) "CUSTOM ${mode - 63}" else "MODE $mode"

    /** A label short enough for a one-row field. */
    fun shortLabel(mode: Int): String = SHORT_LABELS[mode] ?: if (mode in CUSTOM) "C${mode - 63}" else "M$mode"

    /** The mode after [current] in [enabled], wrapping around. Off is never part of the cycle. */
    fun next(current: Int?, enabled: List<Int>): Int? {
        val cycle = enabled.filter { it != OFF }
        if (cycle.isEmpty()) return null
        val index = cycle.indexOf(current)
        return if (index < 0) cycle.first() else cycle[(index + 1) % cycle.size]
    }

    /** The light's enabled steady modes, brightest first (VS1200S: HIGH, MID, LOW). */
    fun steadyLevels(enabled: List<Int>): List<Int> = STEADY_RANK.filter { it in enabled }

    /** The light's enabled flash modes, in [FLASH_RANK] order. */
    fun flashLevels(enabled: List<Int>): List<Int> = FLASH_RANK.filter { it in enabled }
}
