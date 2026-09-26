package com.tiagodias.igpsportkaroo.automation

import com.tiagodias.igpsportkaroo.automation.Command.LowBatteryAlert
import com.tiagodias.igpsportkaroo.automation.Command.SelectAuto
import com.tiagodias.igpsportkaroo.automation.Command.SelectMode
import org.junit.Assert.assertEquals
import org.junit.Test

class RideAutomationTest {
    private var settings = AutomationSettings(RideStart.Mode(1), lowBatteryAlerts = true)
    private val automation = RideAutomation { settings }

    @Test
    fun `ride start selects the configured mode once`() {
        assertEquals(listOf(SelectMode(1)), automation.onRideState(isRecording = true))
        // Resumed / still recording: nothing new.
        assertEquals(emptyList<Command>(), automation.onRideState(isRecording = true))
        assertEquals(emptyList<Command>(), automation.onRideState(isRecording = false))
        // A new ride after going idle selects the mode again.
        assertEquals(listOf(SelectMode(1)), automation.onRideState(isRecording = true))
    }

    @Test
    fun `ride start can select auto`() {
        settings = settings.copy(rideStart = RideStart.Auto)
        assertEquals(listOf(SelectAuto), automation.onRideState(isRecording = true))
    }

    @Test
    fun `no ride-start command when not configured`() {
        settings = settings.copy(rideStart = RideStart.None)
        assertEquals(emptyList<Command>(), automation.onRideState(isRecording = false))
        assertEquals(emptyList<Command>(), automation.onRideState(isRecording = true))
    }

    @Test
    fun `alerts once per threshold`() {
        assertEquals(emptyList<Command>(), automation.onBattery(25))
        assertEquals(listOf(LowBatteryAlert(20)), automation.onBattery(20))
        assertEquals(emptyList<Command>(), automation.onBattery(18))
        assertEquals(emptyList<Command>(), automation.onBattery(11))
        assertEquals(listOf(LowBatteryAlert(10)), automation.onBattery(10))
        assertEquals(emptyList<Command>(), automation.onBattery(5))
    }

    @Test
    fun `first reading below both thresholds alerts once`() {
        assertEquals(listOf(LowBatteryAlert(8)), automation.onBattery(8))
        assertEquals(emptyList<Command>(), automation.onBattery(7))
    }

    @Test
    fun `charging above 30 percent re-arms the alerts`() {
        assertEquals(listOf(LowBatteryAlert(15)), automation.onBattery(15))
        // Charging a little is not enough.
        assertEquals(emptyList<Command>(), automation.onBattery(30))
        assertEquals(emptyList<Command>(), automation.onBattery(19))
        assertEquals(emptyList<Command>(), automation.onBattery(31))
        assertEquals(listOf(LowBatteryAlert(19)), automation.onBattery(19))
    }

    @Test
    fun `alerts can be disabled`() {
        settings = settings.copy(lowBatteryAlerts = false)
        assertEquals(emptyList<Command>(), automation.onBattery(20))
        assertEquals(emptyList<Command>(), automation.onBattery(5))
    }
}
