package com.tiagodias.igpsportkaroo.protocol

import org.junit.Assert.assertEquals
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
}
