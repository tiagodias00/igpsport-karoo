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
    fun `apply merges smart configs, auto brightness and output off`() {
        val s = LightState(connected = true, mode = 1)
            .apply(LightUpdate(smartConfigs = linkedMapOf(5 to 0, 3 to 1)))
            .apply(LightUpdate(autoBrightnessPercent = 95))
            .apply(LightUpdate(outputOff = true))
            .apply(LightUpdate(batteryPercent = 80))
        assertEquals(mapOf(5 to 0, 3 to 1), s.smartConfigs)
        assertEquals(95, s.autoBrightnessPercent)
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
}
