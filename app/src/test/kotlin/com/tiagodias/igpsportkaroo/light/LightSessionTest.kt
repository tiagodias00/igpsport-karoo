package com.tiagodias.igpsportkaroo.light

import com.tiagodias.igpsportkaroo.ble.LightLink
import com.tiagodias.igpsportkaroo.ble.LinkEvent
import com.tiagodias.igpsportkaroo.protocol.Crc8
import com.tiagodias.igpsportkaroo.protocol.Hex
import com.tiagodias.igpsportkaroo.protocol.IgpsProtocol
import com.tiagodias.igpsportkaroo.protocol.LightModes
import com.tiagodias.igpsportkaroo.protocol.SmartConfig
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LightSessionTest {
    private class FakeLink : LightLink {
        val events = MutableSharedFlow<LinkEvent>(extraBufferCapacity = 64)
        private val sendLock = Any()
        val sent = mutableListOf<String>()
        var accepting = true
        var connectCalls = 0
        /** Link flows currently collected (started and not yet torn down). */
        var openFlows = 0
        /** When set, a cancelled link flow's teardown waits for it (like a GATT close that hasn't run yet). */
        var teardownGate: CompletableDeferred<Unit>? = null
        override fun connect(address: String): Flow<LinkEvent> {
            connectCalls++
            return events
                .onStart { openFlows++ }
                .onCompletion {
                    teardownGate?.let { gate -> withContext(NonCancellable) { gate.await() } }
                    openFlows--
                }
        }
        override fun send(frame: ByteArray): Boolean {
            if (accepting) synchronized(sendLock) { sent += Hex.encode(frame) }
            return accepting
        }
    }

    private val respMode3 = Hex.decode("01 6A 02 FF 02 FF FF 00 0A 9A 01 FF FF FF FF FF FF FF FF 1E 08 6A 10 02 18 02 6A 02 08 03")
    private val respBattery87 = Hex.decode("01 6A 06 FF 02 FF FF 00 0A 5D 01 FF FF FF FF FF FF FF FF 81 08 6A 10 02 18 06 7A 02 68 57")
    private val respDeclared = Hex.decode(
        "01 6A 01 FF 02 FF FF 00 1C 40 01 FF FF FF FF FF FF FF FF D2 08 6A 10 02 18 01 " +
            "32 04 08 01 18 01 32 04 08 03 18 01 32 02 08 04 32 04 08 11 18 01",
    ) // declares 1:on, 3:on, 4:off, 17:on
    private val stateMode2 = Hex.decode("03 6A 02 FF 02 FF FF 02 FF FF 01 FF FF FF FF FF FF FF FF 4D")
    // Captured from the real VS1200S right after an OFF command: it echoes its remembered mode (4).
    private val vs1200sEchoMode4 = Hex.decode("03 6A 02 FF 01 FF FF 04 FF FF FF FF FF FF FF FF FF FF FF B3")
    // Captured from the real VS1200S: button press to MID.
    private val vs1200sButtonMid = Hex.decode("03 6A 02 FF 01 FF FF 02 FF FF FF FF FF FF FF FF FF FF FF DA")

    // Captured from the real VS1200S (docs/vs1200s-findings.md): declares MID, HIGH, FLASH HI, FLASH LO, CUSTOM 1, all enabled.
    private val vs1200sDeclared = Hex.decode(
        "01 6A 01 FF 02 FF FF 00 26 0E 01 FF FF FF FF FF FF FF FF 30 " +
            "08 6A 10 02 18 01 32 04 08 02 18 01 32 04 08 01 18 01 32 04 " +
            "08 04 18 01 32 04 08 05 18 01 32 06 08 40 10 01 18 01",
    )
    // Captured from the real VS1200S: smart configs {5:0, 3:1 (AUTO_LIGHT on), 9:1, 4:1, 13:1, 15:1}.
    private val vs1200sSmartConfigs = Hex.decode(
        "01 6A 04 FF 02 FF FF 00 30 6C 01 FF FF FF FF FF FF FF FF BE 08 6A 10 02 18 04 4A 02 08 05 " +
            "4A 04 08 03 10 01 4A 04 08 09 10 01 4A 08 08 04 10 01 1A 02 08 3C 4A 08 08 0D 10 01 1A 02 08 1E " +
            "4A 04 08 0F 10 01",
    )
    // Captured from the real VS1200S: auto light switched the output off in daylight, then 240 min run time.
    private val vs1200sOutputOff = Hex.decode("03 6A 05 FF 01 FF FF FF FF FF FF FF FF FF FF FF FF FF FF 5B")
    private val vs1200sRunTime240 = Hex.decode("03 6A 05 FF 01 FF FF FF FF FF FF F0 00 00 00 FF FF FF FF 38")
    private val vs1200sAutoBrightness97 = Hex.decode("03 6B 07 FF 01 FF FF 61 FF FF FF FF FF FF FF FF FF FF FF 55")

    /** A mode state frame like the light's own (only the header CRC is computed). */
    private fun stateMode(mode: Int): ByteArray {
        val frame = Hex.decode("03 6A 02 FF 01 FF FF 00 FF FF FF FF FF FF FF FF FF FF FF 00")
        frame[7] = mode.toByte()
        frame[19] = Crc8.maxim(frame, 0, 19).toByte()
        return frame
    }

    private fun hex(b: ByteArray) = Hex.encode(b)

    private fun TestScope.report(link: FakeLink, vararg frames: ByteArray) {
        frames.forEach { link.events.tryEmit(LinkEvent.Fragment(it)) }
        runCurrent()
    }

    private fun TestScope.connectedSession(link: FakeLink): LightSession {
        val session = LightSession(link, "AA:BB:CC:DD:EE:FF", backgroundScope, now = { testScheduler.currentTime })
        session.start()
        runCurrent()
        link.events.tryEmit(LinkEvent.Connected)
        runCurrent()
        return session
    }

    @Test
    fun `requests the full state when the link connects`() = runTest {
        val link = FakeLink()
        val session = connectedSession(link)
        assertTrue(session.state.value.connected)
        assertEquals(
            listOf(
                hex(IgpsProtocol.readSupportedModes()),
                hex(IgpsProtocol.readCurrentMode()),
                hex(IgpsProtocol.readBattery()),
                hex(IgpsProtocol.readRemainingTime()),
                hex(IgpsProtocol.readSmartConfigs()),
            ),
            link.sent,
        )
    }

    @Test
    fun `reassembles fragments into state`() = runTest {
        val link = FakeLink()
        val session = connectedSession(link)
        link.events.tryEmit(LinkEvent.Fragment(respMode3.copyOfRange(0, 20)))
        link.events.tryEmit(LinkEvent.Fragment(respMode3.copyOfRange(20, respMode3.size)))
        link.events.tryEmit(LinkEvent.Fragment(respBattery87))
        runCurrent()
        assertEquals(3, session.state.value.mode)
        assertEquals(87, session.state.value.batteryPercent)
    }

    @Test
    fun `button presses on the light update the mode`() = runTest {
        val link = FakeLink()
        val session = connectedSession(link)
        link.events.tryEmit(LinkEvent.Fragment(stateMode2))
        runCurrent()
        assertEquals(2, session.state.value.mode)
    }

    @Test
    fun `selecting a disabled mode enables it first, then reads back`() = runTest {
        val link = FakeLink()
        val session = connectedSession(link)
        link.events.tryEmit(LinkEvent.Fragment(respDeclared))
        runCurrent()
        link.sent.clear()
        assertTrue(session.selectMode(4))
        assertEquals(
            listOf(
                hex(IgpsProtocol.setModeEnabled(4, true)),
                hex(IgpsProtocol.readSupportedModes()),
                hex(IgpsProtocol.setMode(4)),
                hex(IgpsProtocol.readCurrentMode()),
            ),
            link.sent,
        )
    }

    @Test
    fun `next mode cycles the enabled modes`() = runTest {
        val link = FakeLink()
        val session = connectedSession(link)
        link.events.tryEmit(LinkEvent.Fragment(respDeclared))
        link.events.tryEmit(LinkEvent.Fragment(respMode3))
        runCurrent()
        link.sent.clear()
        assertTrue(session.nextMode()) // enabled [1, 3, 17]; after 3 comes 17
        assertEquals(listOf(hex(IgpsProtocol.setMode(17)), hex(IgpsProtocol.readCurrentMode())), link.sent)
    }

    @Test
    fun `polls battery and run time`() = runTest {
        val link = FakeLink()
        connectedSession(link)
        link.sent.clear()
        advanceTimeBy(60_001)
        assertEquals(listOf(hex(IgpsProtocol.readBattery()), hex(IgpsProtocol.readRemainingTime())), link.sent)
    }

    @Test
    fun `disconnect clears connected and stops polling`() = runTest {
        val link = FakeLink()
        val session = connectedSession(link)
        link.events.tryEmit(LinkEvent.Disconnected)
        runCurrent()
        assertFalse(session.state.value.connected)
        link.sent.clear()
        advanceTimeBy(180_000)
        assertEquals(emptyList<String>(), link.sent)
    }

    @Test
    fun `reconnect now restarts the link while disconnected`() = runTest {
        val link = FakeLink()
        val session = connectedSession(link)
        link.events.tryEmit(LinkEvent.Fragment(respBattery87))
        runCurrent()
        assertEquals(1, link.connectCalls)

        session.reconnectNow() // connected: nothing to do
        runCurrent()
        assertEquals(1, link.connectCalls)

        link.events.tryEmit(LinkEvent.Disconnected)
        runCurrent()
        session.reconnectNow()
        runCurrent()
        assertEquals(2, link.connectCalls)
        assertFalse(session.state.value.connected)
        assertEquals(87, session.state.value.batteryPercent) // last known state is kept

        // The fresh link flow is the one being collected now.
        link.events.tryEmit(LinkEvent.Connected)
        runCurrent()
        assertTrue(session.state.value.connected)

        session.stop()
        session.reconnectNow() // stopped: nothing to do
        runCurrent()
        assertEquals(2, link.connectCalls)
    }

    @Test
    fun `a second reconnect while one is pending is ignored`() = runTest {
        val link = FakeLink()
        var clock = 0L // manual clock, so only the pending reconnect (not the rate limit) can block the second tap
        val session = LightSession(link, "AA:BB:CC:DD:EE:FF", backgroundScope, now = { clock })
        session.start()
        runCurrent()
        link.events.tryEmit(LinkEvent.Connected)
        runCurrent()
        link.events.tryEmit(LinkEvent.Disconnected)
        runCurrent()

        val gate = CompletableDeferred<Unit>()
        link.teardownGate = gate
        session.reconnectNow()
        runCurrent()
        assertEquals(1, link.connectCalls) // waiting for the old link's teardown

        clock += 20_000
        session.reconnectNow() // still pending: must not start a link before the old one is torn down
        runCurrent()
        assertEquals(1, link.connectCalls)

        gate.complete(Unit)
        runCurrent()
        assertEquals(2, link.connectCalls)
        assertEquals(1, link.openFlows)

        clock += 20_000
        session.reconnectNow() // the pending one has started: accepted again
        runCurrent()
        assertEquals(3, link.connectCalls)
        assertEquals(1, link.openFlows)
    }

    @Test
    fun `reconnects are rate limited`() = runTest {
        val link = FakeLink()
        val session = connectedSession(link)
        link.events.tryEmit(LinkEvent.Disconnected)
        runCurrent()
        session.reconnectNow()
        runCurrent()
        assertEquals(2, link.connectCalls)

        advanceTimeBy(5_000)
        session.reconnectNow() // within 10 s of the last one: ignored
        runCurrent()
        assertEquals(2, link.connectCalls)

        advanceTimeBy(6_000)
        session.reconnectNow()
        runCurrent()
        assertEquals(3, link.connectCalls)
    }

    @Test
    fun `selected mode shows immediately before the light confirms`() = runTest {
        val link = FakeLink()
        val session = connectedSession(link)
        link.events.tryEmit(LinkEvent.Fragment(respMode3))
        runCurrent()
        session.selectMode(LightModes.OFF)
        assertTrue(session.selectMode(5)) // no fragments delivered after this
        assertEquals(5, session.state.value.mode)
        assertFalse(session.state.value.poweredOff)
    }

    @Test
    fun `commands report failure when the link refuses`() = runTest {
        val link = FakeLink()
        val session = connectedSession(link)
        link.accepting = false
        assertFalse(session.selectMode(1))
    }

    @Test
    fun `off sends only set-mode-0 and marks the light powered off`() = runTest {
        val link = FakeLink()
        val session = connectedSession(link)
        link.sent.clear()
        assertTrue(session.selectMode(LightModes.OFF))
        assertEquals(listOf(hex(IgpsProtocol.setMode(0))), link.sent)
        assertTrue(session.state.value.poweredOff)
    }

    @Test
    fun `the light's mode echo right after off does not turn it back on`() = runTest {
        val link = FakeLink()
        val session = connectedSession(link)
        session.selectMode(LightModes.OFF)
        advanceTimeBy(500)
        link.events.tryEmit(LinkEvent.Fragment(vs1200sEchoMode4))
        runCurrent()
        assertTrue(session.state.value.poweredOff)
        assertEquals(4, session.state.value.mode)
    }

    @Test
    fun `a mode report after the settle window means the light is on again`() = runTest {
        val link = FakeLink()
        val session = connectedSession(link)
        session.selectMode(LightModes.OFF)
        advanceTimeBy(5_000)
        link.events.tryEmit(LinkEvent.Fragment(vs1200sButtonMid))
        runCurrent()
        assertFalse(session.state.value.poweredOff)
        assertEquals(2, session.state.value.mode)
    }

    @Test
    fun `selecting a mode after off clears powered off`() = runTest {
        val link = FakeLink()
        val session = connectedSession(link)
        session.selectMode(LightModes.OFF)
        assertTrue(session.selectMode(1))
        assertFalse(session.state.value.poweredOff)
    }

    @Test
    fun `next mode while off turns on the first enabled mode`() = runTest {
        val link = FakeLink()
        val session = connectedSession(link)
        link.events.tryEmit(LinkEvent.Fragment(respDeclared))
        link.events.tryEmit(LinkEvent.Fragment(respMode3))
        runCurrent()
        session.selectMode(LightModes.OFF)
        link.sent.clear()
        assertTrue(session.nextMode()) // enabled [1, 3, 17]; off -> first = 1
        assertEquals(listOf(hex(IgpsProtocol.setMode(1)), hex(IgpsProtocol.readCurrentMode())), link.sent)
    }

    @Test
    fun `smart configs and auto brightness are parsed into state`() = runTest {
        val link = FakeLink()
        val session = connectedSession(link)
        report(link, vs1200sSmartConfigs, vs1200sAutoBrightness97)
        assertEquals(mapOf(5 to 0, 3 to 1, 9 to 1, 4 to 1, 13 to 1, 15 to 1), session.state.value.smartConfigs)
        assertTrue(session.state.value.autoLightOn)
        assertEquals(97, session.state.value.autoBrightnessPercent)
    }

    @Test
    fun `an output-off frame sets outputOff and a valid run-time frame clears it`() = runTest {
        val link = FakeLink()
        val session = connectedSession(link)
        report(link, vs1200sOutputOff)
        assertTrue(session.state.value.outputOff)
        report(link, vs1200sRunTime240)
        assertFalse(session.state.value.outputOff)
        assertEquals(240, session.state.value.remainingMinutes)
    }

    @Test
    fun `setSmartConfig writes, updates optimistically and reads back`() = runTest {
        val link = FakeLink()
        val session = connectedSession(link)
        report(link, vs1200sSmartConfigs)
        link.sent.clear()
        assertTrue(session.setSmartConfig(SmartConfig.LUMEN_VARY, on = false))
        assertEquals(
            listOf(hex(IgpsProtocol.setSmartConfig(SmartConfig.LUMEN_VARY, SmartConfig.OFF)), hex(IgpsProtocol.readSmartConfigs())),
            link.sent,
        )
        assertEquals(SmartConfig.OFF, session.state.value.smartConfigs[SmartConfig.LUMEN_VARY])
    }

    @Test
    fun `solid cycles the steady levels when the light is already steady`() = runTest {
        val link = FakeLink()
        val session = connectedSession(link)
        report(link, vs1200sDeclared, stateMode(2)) // steady levels [2, 1]; auto off (no configs reported)
        link.sent.clear()
        assertTrue(session.selectSolid())
        assertEquals(1, session.state.value.mode)
        assertTrue(session.selectSolid())
        assertEquals(2, session.state.value.mode)
        assertEquals(
            listOf(
                hex(IgpsProtocol.setMode(1)), hex(IgpsProtocol.readCurrentMode()),
                hex(IgpsProtocol.setMode(2)), hex(IgpsProtocol.readCurrentMode()),
            ),
            link.sent,
        )
    }

    @Test
    fun `solid from flash goes back to the last steady level`() = runTest {
        val link = FakeLink()
        val session = connectedSession(link)
        report(link, vs1200sDeclared, stateMode(1), stateMode(5))
        link.sent.clear()
        assertTrue(session.selectSolid())
        assertEquals(listOf(hex(IgpsProtocol.setMode(1)), hex(IgpsProtocol.readCurrentMode())), link.sent)
    }

    @Test
    fun `solid without a steady mode yet selects the light's first steady level`() = runTest {
        val link = FakeLink()
        val session = connectedSession(link)
        report(link, vs1200sDeclared, stateMode(5))
        link.sent.clear()
        assertTrue(session.selectSolid())
        assertEquals(listOf(hex(IgpsProtocol.setMode(2)), hex(IgpsProtocol.readCurrentMode())), link.sent)
    }

    @Test
    fun `solid while powered off turns the current steady level back on`() = runTest {
        val link = FakeLink()
        val session = connectedSession(link)
        report(link, vs1200sDeclared, stateMode(2))
        session.selectMode(LightModes.OFF)
        link.sent.clear()
        assertTrue(session.selectSolid()) // off: no cycling, back to MID
        assertEquals(listOf(hex(IgpsProtocol.setMode(2)), hex(IgpsProtocol.readCurrentMode())), link.sent)
        assertFalse(session.state.value.poweredOff)
    }

    @Test
    fun `solid while auto is on disables auto first`() = runTest {
        val link = FakeLink()
        val session = connectedSession(link)
        report(link, vs1200sDeclared, vs1200sSmartConfigs, stateMode(1)) // AUTO_LIGHT on, HIGH
        link.sent.clear()
        assertTrue(session.selectSolid()) // auto on: no cycling, stays on HIGH but manual
        assertEquals(
            listOf(
                hex(IgpsProtocol.setSmartConfig(SmartConfig.AUTO_LIGHT, SmartConfig.OFF)),
                hex(IgpsProtocol.readSmartConfigs()),
                hex(IgpsProtocol.setMode(1)),
                hex(IgpsProtocol.readCurrentMode()),
            ),
            link.sent,
        )
        assertFalse(session.state.value.autoLightOn)
        assertEquals(1, session.state.value.mode)
    }

    @Test
    fun `flash cycles the flash levels and returns to the last one`() = runTest {
        val link = FakeLink()
        val session = connectedSession(link)
        report(link, vs1200sDeclared, stateMode(1))
        link.sent.clear()
        assertTrue(session.selectFlash()) // from steady: first flash level (none used yet)
        assertEquals(4, session.state.value.mode)
        assertTrue(session.selectFlash()) // already flashing: next level
        assertEquals(5, session.state.value.mode)
        assertTrue(session.selectSolid())
        assertTrue(session.selectFlash()) // back to the last flash level
        assertEquals(5, session.state.value.mode)
        assertEquals(
            listOf(4, 5, 1, 5).flatMap { listOf(hex(IgpsProtocol.setMode(it)), hex(IgpsProtocol.readCurrentMode())) },
            link.sent,
        )
    }

    @Test
    fun `auto enables auto light`() = runTest {
        val link = FakeLink()
        val session = connectedSession(link)
        report(link, vs1200sDeclared, stateMode(1))
        link.sent.clear()
        assertTrue(session.selectAuto())
        assertEquals(
            listOf(hex(IgpsProtocol.setSmartConfig(SmartConfig.AUTO_LIGHT, SmartConfig.ON)), hex(IgpsProtocol.readSmartConfigs())),
            link.sent,
        )
        assertTrue(session.state.value.autoLightOn)
    }

    @Test
    fun `auto while powered off also turns the light on`() = runTest {
        val link = FakeLink()
        val session = connectedSession(link)
        report(link, vs1200sDeclared, stateMode(1), stateMode(5)) // last steady level: HIGH
        session.selectMode(LightModes.OFF)
        link.sent.clear()
        assertTrue(session.selectAuto())
        assertEquals(
            listOf(
                hex(IgpsProtocol.setSmartConfig(SmartConfig.AUTO_LIGHT, SmartConfig.ON)),
                hex(IgpsProtocol.readSmartConfigs()),
                hex(IgpsProtocol.setMode(1)),
                hex(IgpsProtocol.readCurrentMode()),
            ),
            link.sent,
        )
        assertTrue(session.state.value.autoLightOn)
        assertFalse(session.state.value.poweredOff)
    }

    @Test
    fun `the light controls report failure when the link refuses`() = runTest {
        val link = FakeLink()
        val session = connectedSession(link)
        report(link, vs1200sDeclared, vs1200sSmartConfigs, stateMode(1))
        link.accepting = false
        assertFalse(session.selectSolid())
        assertFalse(session.selectFlash())
        assertFalse(session.selectAuto())
        assertFalse(session.setSmartConfig(SmartConfig.AUTO_LOW, on = false))
        assertTrue(session.state.value.autoLightOn) // nothing was sent, so nothing changed
        assertEquals(1, session.state.value.mode)
    }

    @Test
    fun `concurrent commands never interleave their frames`() = runBlocking {
        val link = FakeLink()
        val session = LightSession(link, "AA:BB:CC:DD:EE:FF", this)
        val iterations = 200
        val onCommand = launch(Dispatchers.Default) { repeat(iterations) { session.selectMode(1) } }
        val offCommand = launch(Dispatchers.Default) { repeat(iterations) { session.selectMode(LightModes.OFF) } }
        onCommand.join()
        offCommand.join()

        val setMode1 = hex(IgpsProtocol.setMode(1))
        val setModeOff = hex(IgpsProtocol.setMode(LightModes.OFF))
        val readCurrent = hex(IgpsProtocol.readCurrentMode())

        // Every setMode(1) must be immediately followed by its own readCurrentMode: if two selectMode(1)
        // calls ever interleaved, another frame (setModeOff or a second setMode1) would land between them.
        var i = 0
        var setMode1Count = 0
        var setModeOffCount = 0
        while (i < link.sent.size) {
            when (link.sent[i]) {
                setMode1 -> {
                    assertEquals(readCurrent, link.sent[i + 1])
                    setMode1Count++
                    i += 2
                }
                setModeOff -> {
                    setModeOffCount++
                    i += 1
                }
                else -> throw AssertionError("unexpected frame at $i: ${link.sent[i]}")
            }
        }
        assertEquals(iterations, setMode1Count)
        assertEquals(iterations, setModeOffCount)
    }
}
