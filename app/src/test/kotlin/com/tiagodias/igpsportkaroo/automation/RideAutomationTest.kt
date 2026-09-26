package com.tiagodias.igpsportkaroo.automation

import com.tiagodias.igpsportkaroo.automation.Command.LowBatteryAlert
import com.tiagodias.igpsportkaroo.automation.Command.SelectAuto
import com.tiagodias.igpsportkaroo.automation.Command.SelectMode
import com.tiagodias.igpsportkaroo.automation.Command.TurnOff
import org.junit.Assert.assertEquals
import org.junit.Test

class RideAutomationTest {
    private var settings = AutomationSettings(RideStart.Mode(1), lowBatteryAlerts = true, offAtRideEnd = true)
    private val automation = RideAutomation(settings = { settings })

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

    // Device log 13:46:20 (device-log-1346.txt): the extension had been (re)installed while idle, Karoo sent no
    // state on registration, and the first event was the real Idle -> Recording: it must start the ride.
    @Test
    fun `the first event can be a genuine ride start`() {
        settings = settings.copy(rideStart = RideStart.Auto)
        assertEquals(listOf(SelectAuto), automation.onRideState(isRecording = true))
    }

    @Test
    fun `ride start can select auto`() {
        settings = settings.copy(rideStart = RideStart.Auto)
        automation.onRideState(isRecording = false)
        assertEquals(listOf(SelectAuto), automation.onRideState(isRecording = true))
    }

    @Test
    fun `a restart mid-ride does not re-apply the ride start`() {
        settings = settings.copy(offAtRideEnd = false) // ride end is covered separately below
        // The extension restarted mid-ride (persisted: recording): the rider may have changed the light since.
        val restarted = RideAutomation(settings = { settings }, initialRecording = true)
        assertEquals(emptyList<Command>(), restarted.onRideState(isRecording = true))
        assertEquals(emptyList<Command>(), restarted.onRideState(isRecording = false))
        assertEquals(listOf(SelectMode(1)), restarted.onRideState(isRecording = true))
    }

    @Test
    fun `a restart while idle still applies the next ride start`() {
        val restarted = RideAutomation(settings = { settings }, initialRecording = false)
        assertEquals(listOf(SelectMode(1)), restarted.onRideState(isRecording = true))
    }

    @Test
    fun `every ride state is persisted`() {
        val persisted = mutableListOf<Boolean>()
        val automation = RideAutomation(settings = { settings }, onRecordingChanged = { persisted += it })
        automation.onRideState(isRecording = true)
        automation.onRideState(isRecording = true)
        automation.onRideState(isRecording = false)
        assertEquals(listOf(true, true, false), persisted)
    }

    @Test
    fun `no ride-start command when not configured`() {
        settings = settings.copy(rideStart = RideStart.None)
        assertEquals(emptyList<Command>(), automation.onRideState(isRecording = false))
        assertEquals(emptyList<Command>(), automation.onRideState(isRecording = true))
    }

    @Test
    fun `ride end turns the light off when enabled`() {
        automation.onRideState(isRecording = false) // idle
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
    fun `idle while not recording does not turn off`() {
        // Nothing was recording (fresh install, or persisted idle): an idle event is not a ride end.
        assertEquals(emptyList<Command>(), automation.onRideState(isRecording = false))
        val restarted = RideAutomation(settings = { settings }, initialRecording = false)
        assertEquals(emptyList<Command>(), restarted.onRideState(isRecording = false))
    }

    @Test
    fun `a ride end after a restart mid-ride turns off`() {
        val restarted = RideAutomation(settings = { settings }, initialRecording = true)
        assertEquals(listOf(TurnOff), restarted.onRideState(isRecording = false))
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
