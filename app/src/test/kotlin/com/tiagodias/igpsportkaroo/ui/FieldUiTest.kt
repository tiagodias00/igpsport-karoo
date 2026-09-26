package com.tiagodias.igpsportkaroo.ui

import com.tiagodias.igpsportkaroo.protocol.LightState
import com.tiagodias.igpsportkaroo.protocol.LightUpdate
import com.tiagodias.igpsportkaroo.protocol.SmartConfig
import com.tiagodias.igpsportkaroo.ui.FieldUi.Kind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FieldUiTest {
    /** The VS1200S: MID, HIGH, FLASH HI, FLASH LO, CUSTOM 1 = LOW (docs/vs1200s-findings.md). */
    private val vs1200sModes = linkedMapOf(2 to true, 1 to true, 4 to true, 5 to true, 64 to true)
    private val vs1200sConfigs = linkedMapOf(5 to 0, 3 to 0, 9 to 1, 4 to 1, 13 to 1, 15 to 1) // AUTO_LIGHT off
    private val high = LightState(
        connected = true, batteryPercent = 78, remainingMinutes = 200,
        declaredModes = vs1200sModes, smartConfigs = vs1200sConfigs,
    ).apply(LightUpdate(mode = 1))
    private val autoOn = high.copy(smartConfigs = vs1200sConfigs + (SmartConfig.AUTO_LIGHT to SmartConfig.ON))

    private fun FieldUi.button(kind: Kind) = buttons.single { it.kind == kind }

    @Test
    fun `four fixed buttons in order`() {
        val ui = FieldUi.from(high)
        assertEquals(listOf(Kind.SOLID, Kind.FLASH, Kind.AUTO, Kind.OFF), ui.buttons.map { it.kind })
        assertEquals(listOf("SOLID", "FLASH", "AUTO", "OFF"), ui.buttons.map { it.label })
    }

    @Test
    fun `searching state`() {
        val ui = FieldUi.from(LightState())
        assertFalse(ui.connected)
        assertTrue(ui.reconnectable)
        assertEquals("Searching for light… tap to retry", ui.footer)
        assertTrue(ui.buttons.none { it.active })
    }

    @Test
    fun `steady light marks SOLID and shows battery and run time`() {
        val ui = FieldUi.from(high)
        assertFalse(ui.reconnectable)
        assertEquals(listOf(true, false, false, false), ui.buttons.map { it.active })
        assertEquals(listOf("HIGH", "FL HI", "", ""), ui.buttons.map { it.detail })
        assertEquals("78%  ·  3h 20m left", ui.footer)
    }

    @Test
    fun `SOLID shows the low custom level as LOW`() {
        val ui = FieldUi.from(high.apply(LightUpdate(mode = 64)))
        assertTrue(ui.button(Kind.SOLID).active)
        assertEquals("LOW", ui.button(Kind.SOLID).detail)
        assertEquals("SOL LO", ui.button(Kind.SOLID).compactText(maxChars = 8))
    }

    @Test
    fun `flashing light marks FLASH and SOLID keeps the last steady level`() {
        val ui = FieldUi.from(high.apply(LightUpdate(mode = 5)))
        assertEquals(listOf(false, true, false, false), ui.buttons.map { it.active })
        assertEquals("HIGH", ui.button(Kind.SOLID).detail)
        assertEquals("FL LO", ui.button(Kind.FLASH).detail)
    }

    @Test
    fun `SOLID shows the light's first steady level before any was used`() {
        val ui = FieldUi.from(LightState(connected = true, declaredModes = vs1200sModes).apply(LightUpdate(mode = 5)))
        assertEquals("HIGH", ui.button(Kind.SOLID).detail) // brightest first
    }

    @Test
    fun `auto light marks only AUTO, even though the light reports a steady mode`() {
        val ui = FieldUi.from(autoOn)
        assertEquals(listOf(false, false, true, false), ui.buttons.map { it.active })
    }

    @Test
    fun `OFF is always available and active only while powered off`() {
        val off = high.copy(poweredOff = true, batteryPercent = 100)
        val ui = FieldUi.from(off)
        assertEquals(listOf(false, false, false, true), ui.buttons.map { it.active })
        assertEquals("Light off  ·  100%", ui.footer)
        assertEquals(listOf(false, false, false, true), FieldUi.from(autoOn.copy(poweredOff = true)).buttons.map { it.active })
        assertTrue(FieldUi.from(LightState(connected = true, declaredModes = mapOf(64 to true))).button(Kind.OFF).available)
    }

    @Test
    fun `availability follows what the light declares`() {
        assertEquals(listOf(true, true, true, true), FieldUi.from(high).buttons.map { it.available })

        val steadyOnly = LightState(connected = true, declaredModes = mapOf(1 to true, 2 to true))
        assertEquals(listOf(true, false), FieldUi.from(steadyOnly).buttons.take(2).map { it.available })
        val flashOnly = LightState(connected = true, declaredModes = mapOf(4 to true))
        assertEquals(listOf(false, true), FieldUi.from(flashOnly).buttons.take(2).map { it.available })

        val noAuto = LightState(connected = true, smartConfigs = mapOf(SmartConfig.AUTO_SLEEP to SmartConfig.ON))
        assertFalse(FieldUi.from(noAuto).button(Kind.AUTO).available)
    }

    @Test
    fun `everything stays available while the light's modes and configs are unknown`() {
        assertEquals(listOf(true, true, true, true), FieldUi.from(LightState(connected = true)).buttons.map { it.available })
    }

    @Test
    fun `AUTO detail shows daylight off, then brightness, then the mode`() {
        val daylight = FieldUi.from(autoOn.copy(outputOff = true, autoBrightnessPercent = 95)).button(Kind.AUTO)
        assertEquals("off (day)", daylight.detail)
        assertEquals("day", daylight.shortDetail)

        val dimmed = FieldUi.from(autoOn.copy(autoBrightnessPercent = 95)).button(Kind.AUTO)
        assertEquals("95%", dimmed.detail)
        assertEquals("95%", dimmed.shortDetail)

        val unknown = FieldUi.from(autoOn).button(Kind.AUTO)
        assertEquals("HIGH", unknown.detail)
        assertEquals("HI", unknown.shortDetail)
    }

    @Test
    fun `auto detail is blank while auto is off`() {
        val stale = FieldUi.from(high.copy(outputOff = true, autoBrightnessPercent = 95)).button(Kind.AUTO)
        assertEquals("", stale.detail)
        assertEquals("", stale.shortDetail)
        assertEquals("AUTO", stale.compactText(maxChars = 8))
    }

    @Test
    fun `footer says when auto light has switched the output off`() {
        assertEquals("Auto off (daylight)  ·  78%", FieldUi.from(autoOn.copy(outputOff = true)).footer)
        assertEquals("Auto off (daylight)", FieldUi.from(autoOn.copy(outputOff = true, batteryPercent = null)).footer)
        // A stale output-off without auto light is not shown.
        assertEquals("78%  ·  3h 20m left", FieldUi.from(high.copy(outputOff = true)).footer)
    }

    @Test
    fun `footer falls back when nothing reported yet`() {
        assertEquals("Connected", FieldUi.from(LightState(connected = true)).footer)
    }

    @Test
    fun `compact labels include the detail when it fits`() {
        val ui = FieldUi.from(autoOn.copy(autoBrightnessPercent = 95).apply(LightUpdate(mode = 5)))
        assertEquals(listOf("SOL", "FLS", "AUTO", "OFF"), ui.buttons.map { it.shortLabel })
        assertEquals(listOf("HI", "LO", "95%", ""), ui.buttons.map { it.shortDetail })
        assertEquals(listOf("SOL HI", "FLS LO", "AUTO 95%", "OFF"), ui.buttons.map { it.compactText(maxChars = 8) })
        assertEquals(listOf("SOL", "FLS", "AUTO", "OFF"), ui.buttons.map { it.compactText(maxChars = 5) })
    }

    @Test
    fun `compact status shows battery when connected`() {
        assertEquals("78%", FieldUi.from(high).statusShort)
        assertEquals("78%", FieldUi.from(high.copy(poweredOff = true)).statusShort)
        assertEquals("--", FieldUi.from(high.copy(batteryPercent = null)).statusShort)
    }

    @Test
    fun `compact status offers reconnect while searching`() {
        val ui = FieldUi.from(LightState(batteryPercent = 78))
        assertTrue(ui.reconnectable)
        assertEquals("↻", ui.statusShort)
    }

    @Test
    fun `formats minutes`() {
        assertEquals("45m", FieldUi.formatMinutes(45))
        assertEquals("1h 0m", FieldUi.formatMinutes(60))
    }
}
