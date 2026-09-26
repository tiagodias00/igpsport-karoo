package com.tiagodias.igpsportkaroo.ui

import com.tiagodias.igpsportkaroo.protocol.LightState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FieldUiTest {
    private val slots = listOf(1, 3, 4)

    @Test
    fun `searching state`() {
        val ui = FieldUi.from(LightState(), slots)
        assertFalse(ui.connected)
        assertTrue(ui.reconnectable)
        assertEquals("Searching for light… tap to retry", ui.footer)
        assertEquals(listOf("HIGH", "LOW", "FLASH HI"), ui.buttons.map { it.label })
        assertTrue(ui.buttons.none { it.active })
    }

    @Test
    fun `connected state marks the active mode and shows battery and run time`() {
        val ui = FieldUi.from(LightState(connected = true, mode = 3, batteryPercent = 78, remainingMinutes = 200), slots)
        assertFalse(ui.reconnectable)
        assertEquals(listOf(false, true, false), ui.buttons.map { it.active })
        assertEquals("78%  ·  3h 20m left", ui.footer)
    }

    @Test
    fun `modes the light does not declare are unavailable`() {
        val state = LightState(connected = true, declaredModes = mapOf(1 to true, 3 to true))
        assertEquals(listOf(true, true, false), FieldUi.from(state, slots).buttons.map { it.available })
    }

    @Test
    fun `declared-but-disabled modes stay available (selecting enables them)`() {
        val state = LightState(connected = true, declaredModes = mapOf(1 to true, 3 to true, 4 to false))
        assertTrue(FieldUi.from(state, slots).buttons[2].available)
    }

    @Test
    fun `footer falls back when nothing reported yet`() {
        assertEquals("Connected", FieldUi.from(LightState(connected = true), slots).footer)
    }

    @Test
    fun `off button is always available and active only while powered off`() {
        val vs1200sSlots = listOf(1, 5, 0) // HIGH / FLASH LO / OFF
        val on = LightState(connected = true, mode = 1, batteryPercent = 100, declaredModes = mapOf(1 to true, 5 to true))
        val onUi = FieldUi.from(on, vs1200sSlots)
        assertEquals(listOf("HIGH", "FLASH LO", "OFF"), onUi.buttons.map { it.label })
        assertEquals(listOf(true, true, true), onUi.buttons.map { it.available })
        assertEquals(listOf(true, false, false), onUi.buttons.map { it.active })

        // The light keeps reporting its remembered mode (1) while off.
        val offUi = FieldUi.from(on.copy(poweredOff = true), vs1200sSlots)
        assertEquals(listOf(false, false, true), offUi.buttons.map { it.active })
        assertEquals("Light off  ·  100%", offUi.footer)
    }

    @Test
    fun `formats minutes`() {
        assertEquals("45m", FieldUi.formatMinutes(45))
        assertEquals("1h 0m", FieldUi.formatMinutes(60))
    }
}
