package com.tiagodias.igpsportkaroo.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LightStateTest {
    @Test
    fun `apply merges only the fields a frame mentions`() {
        val s = LightState(connected = true, mode = 1, batteryPercent = 90)
            .apply(LightUpdate(batteryPercent = 85))
            .apply(LightUpdate(remainingMinutes = 120))
        assertEquals(LightState(connected = true, mode = 1, batteryPercent = 85, remainingMinutes = 120), s)
    }

    @Test
    fun `enabled modes keep the light's order`() {
        val s = LightState().apply(LightUpdate(declaredModes = linkedMapOf(3 to true, 4 to false, 1 to true)))
        assertEquals(listOf(3, 1), s.enabledModes)
    }

    @Test
    fun `apply leaves poweredOff alone`() {
        val s = LightState(connected = true, mode = 4, poweredOff = true).apply(LightUpdate(mode = 4))
        assertEquals(true, s.poweredOff)
    }

    @Test
    fun `apply leaves autoDimmed alone`() {
        val s = LightState(connected = true, mode = 1, autoDimmed = true).apply(LightUpdate(remainingMinutes = 235))
        assertTrue(s.autoDimmed)
    }

    @Test
    fun `apply merges smart configs, aux battery and output off`() {
        val s = LightState(connected = true, mode = 1)
            .apply(LightUpdate(smartConfigs = linkedMapOf(5 to 0, 3 to 1)))
            .apply(LightUpdate(auxBatteryPercent = 95))
            .apply(LightUpdate(outputOff = true))
            .apply(LightUpdate(batteryPercent = 80))
        assertEquals(mapOf(5 to 0, 3 to 1), s.smartConfigs)
        assertEquals(95, s.auxBatteryPercent)
        assertTrue(s.outputOff)
        assertFalse(s.apply(LightUpdate(remainingMinutes = 165, outputOff = false)).outputOff)
    }

    @Test
    fun `auto light is on only when the light says so`() {
        assertFalse(LightState().autoLightOn)
        assertTrue(LightState(smartConfigs = mapOf(SmartConfig.AUTO_LIGHT to SmartConfig.ON)).autoLightOn)
        assertFalse(LightState(smartConfigs = mapOf(SmartConfig.AUTO_LIGHT to SmartConfig.OFF)).autoLightOn)
    }

    @Test
    fun `apply remembers the last steady and flash modes`() {
        val s = LightState()
            .apply(LightUpdate(mode = 2))
            .apply(LightUpdate(mode = 5))
            .apply(LightUpdate(mode = 99))
        assertEquals(99, s.mode)
        assertEquals(2, s.lastSteadyMode)
        assertEquals(5, s.lastFlashMode)
        assertEquals(1, s.apply(LightUpdate(mode = 1)).lastSteadyMode)
    }

    @Test
    fun `current levels fall back to the first enabled level of each group`() {
        val vs1200s = LightState(declaredModes = linkedMapOf(2 to true, 1 to true, 4 to true, 5 to true, 64 to true))
        assertEquals(1, vs1200s.steadyLevel) // brightest first
        assertEquals(4, vs1200s.flashLevel)
        val used = vs1200s.apply(LightUpdate(mode = 64)).apply(LightUpdate(mode = 5))
        assertEquals(64, used.steadyLevel)
        assertEquals(5, used.flashLevel)
        assertNull(LightState().steadyLevel)
    }

    @Test
    fun `levels fall back to the declared ones when none of a group is enabled`() {
        // Flash modes ship disabled: FLASH still has levels, since selecting a disabled mode enables it first.
        val flashDisabled = LightState(declaredModes = linkedMapOf(1 to true, 2 to true, 4 to false, 5 to false))
        assertEquals(listOf(1, 2), flashDisabled.steadyLevels)
        assertEquals(listOf(4, 5), flashDisabled.flashLevels)
        assertEquals(4, flashDisabled.flashLevel)
        // Once any level of a group is enabled, only the enabled ones are used (disabled ones stay untouched).
        val oneFlashEnabled = LightState(declaredModes = linkedMapOf(1 to true, 64 to false, 4 to false, 5 to true))
        assertEquals(listOf(1), oneFlashEnabled.steadyLevels)
        assertEquals(listOf(5), oneFlashEnabled.flashLevels)
        assertEquals(5, oneFlashEnabled.flashLevel)
        assertEquals(emptyList<Int>(), LightState().flashLevels)
    }

    private val c1Steady = CustomModeConfig(
        64, CustomMode.STEADY,
        listOf(
            CustomPattern(CustomMode.STEADY, listOf(CustomLight(CustomMode.MAIN, 30))),
            CustomPattern(CustomMode.FLASH, listOf(CustomLight(CustomMode.MAIN, 100)), 2, 30),
        ),
    )
    private val vs1200s = LightState(declaredModes = linkedMapOf(2 to true, 1 to true, 4 to true, 5 to true, 64 to true))

    @Test
    fun `apply stores custom configs by slot`() {
        val s = vs1200s.apply(LightUpdate(customMode = c1Steady)).apply(LightUpdate(batteryPercent = 50))
        assertEquals(mapOf(64 to c1Steady), s.customModes)
        val flashing = c1Steady.copy(selected = CustomMode.FLASH)
        assertEquals(mapOf(64 to flashing), s.apply(LightUpdate(customMode = flashing)).customModes)
    }

    @Test
    fun `a flashing custom mode is remembered as the last flash level`() {
        val s = vs1200s.apply(LightUpdate(customMode = c1Steady.copy(selected = CustomMode.FLASH))).apply(LightUpdate(mode = 64))
        assertEquals(64, s.lastFlashMode)
        assertEquals(64, s.flashLevel)
        assertEquals(1, s.steadyLevel)
        assertEquals(listOf(4, 5, 64), s.flashLevels)
    }

    @Test
    fun `a steady level that starts flashing is no longer the steady level`() {
        val used = vs1200s.apply(LightUpdate(customMode = c1Steady)).apply(LightUpdate(mode = 64))
        assertEquals(64, used.steadyLevel)
        val edited = used.apply(LightUpdate(customMode = c1Steady.copy(selected = CustomMode.FLASH)))
        assertEquals(1, edited.steadyLevel) // falls back to the brightest steady level
        assertTrue(edited.isFlashing(64))
        assertFalse(edited.isSteady(64))
        assertEquals("C1 FLASH", edited.labelOf(64))
        assertEquals("C1", edited.shortLabelOf(64))
    }

    @Test
    fun `a config that arrives after the mode reclassifies the playing slot`() {
        // Connect order: the mode is read before the custom configs, so 64 first counts as steady.
        val beforeConfig = vs1200s.copy(poweredOff = true, autoDimmed = true).apply(LightUpdate(mode = 1)).apply(LightUpdate(mode = 64))
        assertEquals(64, beforeConfig.lastSteadyMode)
        val s = beforeConfig.apply(LightUpdate(customMode = c1Steady.copy(selected = CustomMode.FLASH)))
        assertEquals(64, s.lastFlashMode)
        assertEquals(64, s.flashLevel)
        assertEquals(1, s.steadyLevel) // 64 no longer steady: the brightest steady level
        assertTrue(s.poweredOff) // apply still leaves these alone
        assertTrue(s.autoDimmed)
    }

    @Test
    fun `switching the playing slot's pattern moves it between the steady and flash levels`() {
        val steady = vs1200s.apply(LightUpdate(customMode = c1Steady)).apply(LightUpdate(mode = 64))
        val flashing = steady.apply(LightUpdate(customMode = c1Steady.copy(selected = CustomMode.FLASH)))
        assertEquals(64, flashing.flashLevel)
        val back = flashing.apply(LightUpdate(customMode = c1Steady))
        assertEquals(64, back.steadyLevel)
        assertEquals(64, back.lastFlashMode) // kept, but no longer flashing: FLASH falls back to its first level
        assertEquals(4, back.flashLevel)
    }

    @Test
    fun `a config for a slot that isn't playing leaves the last levels alone`() {
        val s = vs1200s.apply(LightUpdate(mode = 5)).apply(LightUpdate(customMode = c1Steady.copy(selected = CustomMode.FLASH)))
        assertEquals(5, s.lastFlashMode)
        assertNull(s.lastSteadyMode)
    }

    @Test
    fun `a config without its selected pattern's data counts as unknown`() {
        val noData = c1Steady.copy(selected = CustomMode.BREATH) // no breath pattern in it
        assertEquals(emptyMap<Int, CustomModeConfig>(), vs1200s.apply(LightUpdate(customMode = noData)).customModes)
        // It also replaces a config known before: the light no longer says what the slot does.
        val playing = vs1200s.apply(LightUpdate(customMode = c1Steady)).apply(LightUpdate(mode = 64))
        val s = playing.apply(LightUpdate(customMode = noData))
        assertEquals(emptyMap<Int, CustomModeConfig>(), s.customModes)
        assertTrue(s.isSteady(64)) // unknown counts as steady
        assertFalse(s.isFlashing(64))
        assertEquals("LOW", s.labelOf(64))
        val empty = CustomModeConfig(64, CustomMode.FLASH, emptyList())
        assertFalse(vs1200s.apply(LightUpdate(customMode = empty)).customModes.containsKey(64))
    }
}
