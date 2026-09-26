package com.tiagodias.igpsportkaroo.automation

import com.tiagodias.igpsportkaroo.automation.Command.LowBatteryAlert
import com.tiagodias.igpsportkaroo.automation.Command.SelectAuto
import com.tiagodias.igpsportkaroo.automation.Command.SelectMode
import com.tiagodias.igpsportkaroo.automation.Command.TurnOff
import org.junit.Assert.assertEquals
import org.junit.Test

class RideAutomationTest {
    private var settings = AutomationSettings(RideStart.Mode(1), lowBatteryAlerts = true, offAtRideEnd = true)
    private val automation = RideAutomation { settings }

    @Test
    fun `ride start selects the configured mode once`() {
        settings = settings.copy(offAtRideEnd = false) // ride end is covered separately below
        assertEquals(emptyList<Command>(), automation.onRideState(isRecording = false)) // consumer registered while idle
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
        automation.onRideState(isRecording = false)
        assertEquals(listOf(SelectAuto), automation.onRideState(isRecording = true))
    }

    @Test
    fun `a ride already recording at the first event is not a ride start`() {
        settings = settings.copy(offAtRideEnd = false) // ride end is covered separately below
        // The extension restarted mid-ride: the rider may have changed the light since the real start.
        assertEquals(emptyList<Command>(), automation.onRideState(isRecording = true))
        assertEquals(emptyList<Command>(), automation.onRideState(isRecording = true))
        assertEquals(emptyList<Command>(), automation.onRideState(isRecording = false))
        assertEquals(listOf(SelectMode(1)), automation.onRideState(isRecording = true))
    }

    @Test
    fun `no ride-start command when not configured`() {
        settings = settings.copy(rideStart = RideStart.None)
        assertEquals(emptyList<Command>(), automation.onRideState(isRecording = false))
        assertEquals(emptyList<Command>(), automation.onRideState(isRecording = true))
    }

    @Test
    fun `ride end turns the light off when enabled`() {
        automation.onRideState(isRecording = false) // primed (idle)
        automation.onRideState(isRecording = true) // ride start
        assertEquals(listOf(TurnOff), automation.onRideState(isRecording = false)) // ride end
    }

    @Test
    fun `ride end does nothing when disabled`() {
        settings = settings.copy(offAtRideEnd = false)
        automation.onRideState(isRecording = false)
        automation.onRideState(isRecording = true)
        assertEquals(emptyList<Command>(), automation.onRideState(isRecording = false))
    }

    @Test
    fun `pausing is not a ride end`() {
        automation.onRideState(isRecording = false)
        automation.onRideState(isRecording = true) // ride start
        // Paused rides are reported as still recording, so this is not a recording -> idle transition.
        assertEquals(emptyList<Command>(), automation.onRideState(isRecording = true))
    }

    @Test
    fun `first event after restart does not turn off`() {
        // The extension restarted right after the ride ended: the first event reports idle, but it must only
        // record the state (same priming rule as ride start).
        assertEquals(emptyList<Command>(), automation.onRideState(isRecording = false))
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
