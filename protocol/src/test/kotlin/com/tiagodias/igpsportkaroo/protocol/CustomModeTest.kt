package com.tiagodias.igpsportkaroo.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CustomModeTest {
    private val steady30 = CustomPattern(CustomMode.STEADY, listOf(CustomLight(CustomMode.MAIN, 30)))
    private val flash100 = CustomPattern(CustomMode.FLASH, listOf(CustomLight(CustomMode.MAIN, 100)), 2, 30)
    private val slot = CustomModeConfig(64, CustomMode.STEADY, listOf(steady30, flash100))

    @Test
    fun `the active pattern is the selected one`() {
        assertEquals(steady30, slot.active)
        assertEquals(30, slot.brightness)
        assertFalse(slot.blinks)
        val flashing = slot.copy(selected = CustomMode.FLASH)
        assertEquals(flash100, flashing.active)
        assertTrue(flashing.blinks)
        assertTrue(slot.copy(selected = CustomMode.BREATH).blinks)
        assertNull(CustomModeConfig(64, CustomMode.STEADY, emptyList()).active)
    }

    @Test
    fun `main brightness prefers the main or high-beam channel`() {
        val beams = CustomPattern(CustomMode.STEADY, listOf(CustomLight(CustomMode.LOW_BEAM, 20), CustomLight(CustomMode.HIGH_BEAM, 70)))
        assertEquals(70, beams.mainPct)
        assertEquals(20, CustomPattern(CustomMode.STEADY, listOf(CustomLight(CustomMode.LOW_BEAM, 20))).mainPct)
        assertNull(CustomPattern(CustomMode.STEADY, emptyList()).mainPct)
    }

    @Test
    fun `applied changes one value and selects the change's pattern`() {
        assertEquals(
            CustomModeConfig(64, CustomMode.STEADY, listOf(steady30.copy(lights = listOf(CustomLight(CustomMode.MAIN, 45))), flash100)),
            slot.applied(CustomChange.Brightness(CustomMode.STEADY, CustomMode.MAIN, 45)),
        )
        assertEquals(
            CustomModeConfig(64, CustomMode.FLASH, listOf(steady30, flash100.copy(cycleSeconds = 4))),
            slot.applied(CustomChange.Cycle(CustomMode.FLASH, 4)),
        )
        assertEquals(slot.copy(selected = CustomMode.FLASH), slot.applied(CustomChange.Pattern(CustomMode.FLASH)))
        assertEquals(
            CustomModeConfig(64, CustomMode.FLASH, listOf(steady30, flash100.copy(ratioPercent = 50))),
            slot.applied(CustomChange.Ratio(CustomMode.FLASH, 50)),
        )
    }

    @Test
    fun `changesTo is empty when nothing differs`() {
        assertEquals(emptyList<CustomChange>(), slot.changesTo(slot))
    }

    @Test
    fun `changesTo switches only the pattern when only it differs`() {
        assertEquals(listOf(CustomChange.Pattern(CustomMode.STEADY)), slot.copy(selected = CustomMode.FLASH).changesTo(slot))
    }

    @Test
    fun `changesTo writes other patterns first, the target's pattern last`() {
        val edited = CustomModeConfig(
            64, CustomMode.FLASH,
            listOf(steady30.copy(lights = listOf(CustomLight(CustomMode.MAIN, 80))), flash100.copy(cycleSeconds = 1, ratioPercent = 50)),
        )
        assertEquals(
            listOf(
                CustomChange.Cycle(CustomMode.FLASH, 2),
                CustomChange.Ratio(CustomMode.FLASH, 30),
                CustomChange.Brightness(CustomMode.STEADY, CustomMode.MAIN, 30),
                CustomChange.Pattern(CustomMode.STEADY),
            ),
            edited.changesTo(slot),
        )
    }

    @Test
    fun `applying changesTo reaches the target`() {
        val edited = slot.applied(CustomChange.Cycle(CustomMode.FLASH, 4)).applied(CustomChange.Brightness(CustomMode.STEADY, CustomMode.MAIN, 60))
        assertEquals(slot, edited.changesTo(slot).fold(edited) { acc, c -> acc.applied(c) })
    }
}
