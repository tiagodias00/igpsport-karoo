package com.tiagodias.igpsportkaroo.light

import com.tiagodias.igpsportkaroo.ble.LightLink
import com.tiagodias.igpsportkaroo.ble.LinkEvent
import com.tiagodias.igpsportkaroo.protocol.Hex
import com.tiagodias.igpsportkaroo.protocol.IgpsProtocol
import com.tiagodias.igpsportkaroo.protocol.LightModes
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

    private fun hex(b: ByteArray) = Hex.encode(b)

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
