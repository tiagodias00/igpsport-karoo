package com.tiagodias.igpsportkaroo.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LightModesTest {
    @Test
    fun `labels known, custom and unknown modes`() {
        assertEquals("HIGH", LightModes.label(1))
        assertEquals("FLASH HI", LightModes.label(4))
        assertEquals("SOS", LightModes.label(17))
        assertEquals("LOW", LightModes.label(64)) // CUSTOM 1 ships as the VS1200S's low steady level; unknown config
        assertEquals("CUSTOM 2", LightModes.label(65))
        assertEquals("MODE 99", LightModes.label(99))
    }

    @Test
    fun `short labels known, custom and unknown modes`() {
        assertEquals("OFF", LightModes.shortLabel(0))
        assertEquals("HI", LightModes.shortLabel(1))
        assertEquals("FL LO", LightModes.shortLabel(5))
        assertEquals("HB MID", LightModes.shortLabel(8))
        assertEquals("SOS", LightModes.shortLabel(17))
        assertEquals("LO", LightModes.shortLabel(64))
        assertEquals("C2", LightModes.shortLabel(65))
        assertEquals("C12", LightModes.shortLabel(75))
        assertEquals("M99", LightModes.shortLabel(99))
    }

    @Test
    fun `next cycles through enabled modes and wraps`() {
        assertEquals(2, LightModes.next(1, listOf(1, 2, 3)))
        assertEquals(1, LightModes.next(3, listOf(1, 2, 3)))
    }

    @Test
    fun `next starts at the first mode when current is unknown or not in the list`() {
        assertEquals(1, LightModes.next(null, listOf(1, 3)))
        assertEquals(1, LightModes.next(5, listOf(1, 3)))
    }

    @Test
    fun `next never selects off and returns null with nothing to cycle`() {
        assertEquals(2, LightModes.next(1, listOf(0, 1, 2)))
        assertNull(LightModes.next(1, emptyList()))
        assertNull(LightModes.next(1, listOf(0)))
    }

    @Test
    fun `steady levels are brightest first and include the low custom level`() {
        val vs1200s = listOf(2, 1, 4, 5, 64) // captured from a VS1200S: declared and enabled, in the light's order
        assertEquals(listOf(1, 2, 64), LightModes.steadyLevels(vs1200s)) // HIGH, MID, LOW
        assertEquals(listOf(16, 1, 7, 10, 2, 8, 11, 3, 9, 12, 64, 65, 75), LightModes.steadyLevels(listOf(75, 65, 64, 12, 11, 10, 9, 8, 7, 3, 2, 1, 16)))
    }

    @Test
    fun `flash levels follow the flash rank and skip custom modes`() {
        assertEquals(listOf(4, 5), LightModes.flashLevels(listOf(2, 1, 5, 4, 64)))
        assertEquals(listOf(4, 5, 6, 17), LightModes.flashLevels(listOf(17, 6, 5, 4)))
        assertEquals(emptyList<Int>(), LightModes.flashLevels(listOf(1, 64)))
    }

    @Test
    fun `mode groups`() {
        // Without configs, custom slots count as steady.
        assertTrue((listOf(1, 2, 3, 7, 8, 9, 10, 11, 12, 16) + (64..75)).all { LightModes.isSteady(it) })
        assertTrue(listOf(4, 5, 6, 17).all { LightModes.isFlashing(it) })
        assertTrue(listOf(0, 13, 63, 76).none { LightModes.isSteady(it) || LightModes.isFlashing(it) })
        assertTrue((64..75).none { LightModes.isFlashing(it) })
    }

    private val steadyC1 = CustomModeConfig(
        64, CustomMode.STEADY,
        listOf(
            CustomPattern(CustomMode.STEADY, listOf(CustomLight(CustomMode.MAIN, 30))),
            CustomPattern(CustomMode.FLASH, listOf(CustomLight(CustomMode.MAIN, 100)), 2, 30),
            CustomPattern(CustomMode.BREATH, listOf(CustomLight(CustomMode.MAIN, 60))),
        ),
    )
    private val flashC1 = steadyC1.copy(selected = CustomMode.FLASH)

    @Test
    fun `a custom mode is steady until it is known to blink`() {
        assertTrue(LightModes.isSteady(64))
        assertTrue(LightModes.isSteady(64, mapOf(64 to steadyC1)))
        assertFalse(LightModes.isSteady(64, mapOf(64 to flashC1)))
        assertTrue(LightModes.isFlashing(64, mapOf(64 to flashC1)))
        assertFalse(LightModes.isFlashing(64))
        assertTrue(LightModes.isSteady(1, mapOf(64 to flashC1)))
        assertTrue(LightModes.isFlashing(4))
        assertFalse(LightModes.isSteady(0) || LightModes.isFlashing(0))
    }

    @Test
    fun `a flashing custom mode moves from the steady levels to the flash levels`() {
        val vs1200s = listOf(2, 1, 4, 5, 64)
        assertEquals(listOf(1, 2, 64), LightModes.steadyLevels(vs1200s, mapOf(64 to steadyC1)))
        assertEquals(listOf(1, 2), LightModes.steadyLevels(vs1200s, mapOf(64 to flashC1)))
        assertEquals(listOf(4, 5, 64), LightModes.flashLevels(vs1200s, mapOf(64 to flashC1)))
        assertEquals(listOf(4, 5), LightModes.flashLevels(vs1200s, mapOf(64 to steadyC1)))
    }

    @Test
    fun `labels a known custom mode by what it does`() {
        assertEquals("C1 30%", LightModes.label(64, mapOf(64 to steadyC1)))
        assertEquals("C1 FLASH", LightModes.label(64, mapOf(64 to flashC1)))
        assertEquals("C1 BREATH", LightModes.label(64, mapOf(64 to steadyC1.copy(selected = CustomMode.BREATH))))
        // Without the selected pattern's data the config says nothing: labelled as unknown.
        assertEquals("LOW", LightModes.label(64, mapOf(64 to CustomModeConfig(64, CustomMode.STEADY, emptyList()))))
        assertEquals("LO", LightModes.shortLabel(64, mapOf(64 to CustomModeConfig(64, CustomMode.FLASH, emptyList()))))
        assertEquals("C1", LightModes.shortLabel(64, mapOf(64 to steadyC1)))
        assertEquals("LOW", LightModes.label(64, emptyMap())) // unknown: as before
        assertEquals("LO", LightModes.shortLabel(64, emptyMap()))
        assertEquals("HIGH", LightModes.label(1, mapOf(64 to steadyC1)))
    }
}
