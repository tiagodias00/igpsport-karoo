package com.tiagodias.igpsportkaroo.ui

import com.tiagodias.igpsportkaroo.protocol.CustomLight
import com.tiagodias.igpsportkaroo.protocol.CustomMode
import com.tiagodias.igpsportkaroo.protocol.CustomModeConfig
import com.tiagodias.igpsportkaroo.protocol.CustomPattern
import com.tiagodias.igpsportkaroo.protocol.LightState
import com.tiagodias.igpsportkaroo.protocol.LightUpdate
import com.tiagodias.igpsportkaroo.protocol.SmartConfig
import com.tiagodias.igpsportkaroo.ui.FieldUi.Kind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FieldUiTest {
    /** The VS1200S: MID, HIGH, FLASH HI, FLASH LO, CUSTOM 1 = LOW, as it declares them. */
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
        // Declared but disabled still counts: selecting a disabled mode enables it first.
        val flashDisabled = LightState(connected = true, declaredModes = mapOf(1 to true, 4 to false, 5 to false))
        assertEquals(listOf(true, true), FieldUi.from(flashDisabled).buttons.take(2).map { it.available })
        assertEquals("FL HI", FieldUi.from(flashDisabled).button(Kind.FLASH).detail)

        val noAuto = LightState(connected = true, smartConfigs = mapOf(SmartConfig.AUTO_SLEEP to SmartConfig.ON))
        assertFalse(FieldUi.from(noAuto).button(Kind.AUTO).available)
    }

    @Test
    fun `everything stays available while the light's modes and configs are unknown`() {
        assertEquals(listOf(true, true, true, true), FieldUi.from(LightState(connected = true)).buttons.map { it.available })
    }

    @Test
    fun `auto detail shows only the running mode`() {
        // The light doesn't reliably report daylight auto-off, and the 0x6B reading is a battery value, so
        // neither is shown here regardless of what the light last reported for them.
        val running = FieldUi.from(autoOn.copy(auxBatteryPercent = 95)).button(Kind.AUTO)
        assertEquals("HIGH", running.detail)
        assertEquals("HI", running.shortDetail)

        val outputOff = FieldUi.from(autoOn.copy(outputOff = true, auxBatteryPercent = 95)).button(Kind.AUTO)
        assertEquals("HIGH", outputOff.detail)
        assertEquals("HI", outputOff.shortDetail)

        val unknownMode = FieldUi.from(autoOn.copy(mode = null, auxBatteryPercent = 95)).button(Kind.AUTO)
        assertEquals("", unknownMode.detail)
        assertEquals("", unknownMode.shortDetail)
    }

    @Test
    fun `auto detail shows DIMMED when the light has dimmed itself`() {
        val dimmed = FieldUi.from(autoOn.copy(autoDimmed = true)).button(Kind.AUTO)
        assertEquals("HIGH DIMMED", dimmed.detail)
        assertEquals("HI DIM", dimmed.shortDetail)

        val notDimmed = FieldUi.from(autoOn.copy(autoDimmed = false)).button(Kind.AUTO)
        assertEquals("HIGH", notDimmed.detail)
        assertEquals("HI", notDimmed.shortDetail)

        // Blank while auto is off, even if a stale autoDimmed = true lingers in state.
        val autoOff = FieldUi.from(high.copy(autoDimmed = true)).button(Kind.AUTO)
        assertEquals("", autoOff.detail)
        assertEquals("", autoOff.shortDetail)

        // No mode known yet: no label to suffix, even while dimmed.
        val unknownMode = FieldUi.from(autoOn.copy(mode = null, autoDimmed = true)).button(Kind.AUTO)
        assertEquals("", unknownMode.detail)
        assertEquals("", unknownMode.shortDetail)

        assertEquals("AUTO · HIGH DIMMED", FieldUi.from(autoOn.copy(autoDimmed = true)).headline)
    }

    @Test
    fun `auto detail is blank while auto is off`() {
        val stale = FieldUi.from(high.copy(outputOff = true, auxBatteryPercent = 95)).button(Kind.AUTO)
        assertEquals("", stale.detail)
        assertEquals("", stale.shortDetail)
        assertEquals("AUTO", stale.compactText(maxChars = 8))
    }

    @Test
    fun `footer never mentions daylight auto-off`() {
        // The light doesn't reliably report this (seen on a VS1200S): the normal battery/run-time
        // footer is shown regardless of outputOff.
        assertEquals("78%  ·  3h 20m left", FieldUi.from(autoOn.copy(outputOff = true)).footer)
        assertEquals("3h 20m left", FieldUi.from(autoOn.copy(outputOff = true, batteryPercent = null)).footer)
        assertEquals("78%  ·  3h 20m left", FieldUi.from(high.copy(outputOff = true)).footer)
    }

    @Test
    fun `footer falls back when nothing reported yet`() {
        assertEquals("Connected", FieldUi.from(LightState(connected = true)).footer)
    }

    @Test
    fun `compact labels include the detail when it fits`() {
        val ui = FieldUi.from(autoOn.copy(auxBatteryPercent = 95).apply(LightUpdate(mode = 5)))
        assertEquals(listOf("SOL", "FLS", "AUTO", "OFF"), ui.buttons.map { it.shortLabel })
        assertEquals(listOf("HI", "LO", "FL LO", ""), ui.buttons.map { it.shortDetail })
        assertEquals(listOf("SOL HI", "FLS LO", "AUTO", "OFF"), ui.buttons.map { it.compactText(maxChars = 8) })
        assertEquals("AUTO FL LO", ui.button(Kind.AUTO).compactText(maxChars = 10))
        assertEquals(listOf("SOL", "FLS", "AUTO", "OFF"), ui.buttons.map { it.compactText(maxChars = 5) })
    }

    @Test
    fun `headline shows the active button`() {
        assertEquals("SOLID · HIGH", FieldUi.from(high).headline)
        // The 0x6B battery reading never appears in the headline.
        assertEquals("AUTO · HIGH", FieldUi.from(autoOn.copy(auxBatteryPercent = 95)).headline)
        assertEquals("OFF", FieldUi.from(high.copy(poweredOff = true)).headline)
    }

    @Test
    fun `headline while searching or before a mode is known`() {
        assertEquals("Searching for light…", FieldUi.from(LightState(batteryPercent = 78)).headline)
        assertEquals("Connected", FieldUi.from(LightState(connected = true)).headline)
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

    private val c1 = CustomModeConfig(
        64, CustomMode.STEADY,
        listOf(
            CustomPattern(CustomMode.STEADY, listOf(CustomLight(CustomMode.MAIN, 30))),
            CustomPattern(CustomMode.FLASH, listOf(CustomLight(CustomMode.MAIN, 100)), 2, 30),
        ),
    )

    @Test
    fun `a known steady custom mode shows its brightness on SOLID`() {
        val ui = FieldUi.from(high.apply(LightUpdate(customMode = c1)).apply(LightUpdate(mode = 64)))
        assertTrue(ui.button(Kind.SOLID).active)
        assertEquals("C1 30%", ui.button(Kind.SOLID).detail)
        assertEquals("SOL C1", ui.button(Kind.SOLID).compactText(maxChars = 8))
    }

    @Test
    fun `a flashing custom mode marks FLASH`() {
        val ui = FieldUi.from(high.apply(LightUpdate(customMode = c1.copy(selected = CustomMode.FLASH))).apply(LightUpdate(mode = 64)))
        assertEquals(listOf(false, true, false, false), ui.buttons.map { it.active })
        assertEquals("C1", ui.button(Kind.FLASH).detail)
        assertEquals("HIGH", ui.button(Kind.SOLID).detail) // SOLID falls back to the last steady level
    }

    @Test
    fun `auto detail labels a known custom mode, keeping the DIMMED suffix`() {
        val custom = autoOn.apply(LightUpdate(customMode = c1)).apply(LightUpdate(mode = 64))
        assertEquals("C1 30%", FieldUi.from(custom).button(Kind.AUTO).detail)
        val dimmed = FieldUi.from(custom.copy(autoDimmed = true)).button(Kind.AUTO)
        assertEquals("C1 30% DIMMED", dimmed.detail)
        assertEquals("C1 DIM", dimmed.shortDetail)
    }

    @Test
    fun `a flash config that arrives after the mode shows the custom slot under FLASH`() {
        val ui = FieldUi.from(high.apply(LightUpdate(mode = 64)).apply(LightUpdate(customMode = c1.copy(selected = CustomMode.FLASH))))
        assertEquals(listOf(false, true, false, false), ui.buttons.map { it.active })
        assertEquals("C1", ui.button(Kind.FLASH).detail)
        assertEquals("FLS C1", ui.button(Kind.FLASH).compactText(maxChars = 8))
        assertEquals("HIGH", ui.button(Kind.SOLID).detail)
    }

    @Test
    fun `switching the playing custom slot's pattern moves its label between SOLID and FLASH`() {
        val steady = high.apply(LightUpdate(customMode = c1)).apply(LightUpdate(mode = 64))
        val flashing = FieldUi.from(steady.apply(LightUpdate(customMode = c1.copy(selected = CustomMode.FLASH))))
        assertEquals("C1", flashing.button(Kind.FLASH).detail)
        assertEquals("HIGH", flashing.button(Kind.SOLID).detail)

        val wasFlashing = high.apply(LightUpdate(customMode = c1.copy(selected = CustomMode.FLASH))).apply(LightUpdate(mode = 64))
        val nowSteady = FieldUi.from(wasFlashing.apply(LightUpdate(customMode = c1)))
        assertTrue(nowSteady.button(Kind.SOLID).active)
        assertEquals("C1 30%", nowSteady.button(Kind.SOLID).detail)
        assertEquals("FL HI", nowSteady.button(Kind.FLASH).detail)
    }

    @Test
    fun `auto detail labels a breathing custom mode`() {
        val breath = autoOn.apply(LightUpdate(customMode = c1.copy(selected = CustomMode.BREATH))).apply(LightUpdate(mode = 64))
        val dimmed = FieldUi.from(breath.copy(autoDimmed = true)).button(Kind.AUTO)
        assertEquals("C1 BREATH DIMMED", dimmed.detail)
        assertEquals("C1 DIM", dimmed.shortDetail)
    }
}
