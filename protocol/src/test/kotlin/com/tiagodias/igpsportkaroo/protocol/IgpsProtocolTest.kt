package com.tiagodias.igpsportkaroo.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class IgpsProtocolTest {
    private fun hex(b: ByteArray) = Hex.encode(b)

    @Test
    fun `builds frames identical to the iGPSPORT app capture`() {
        assertEquals("01 6A 02 FF 02 FF FF 00 06 5C 01 FF FF FF FF FF FF FF FF 72 08 6A 10 02 18 02", hex(IgpsProtocol.readCurrentMode()))
        assertEquals("01 6A 02 FF 01 FF FF 00 0A 9C 01 FF FF FF FF FF FF FF FF 11 08 6A 10 01 18 02 6A 02 08 0C", hex(IgpsProtocol.setMode(12)))
    }

    @Test
    fun `builds the other requests`() {
        assertEquals("01 6A 01 FF 02 FF FF 00 06 BE 01 FF FF FF FF FF FF FF FF 65 08 6A 10 02 18 01", hex(IgpsProtocol.readSupportedModes()))
        assertEquals("01 6A 06 FF 02 FF FF 00 06 3D 01 FF FF FF FF FF FF FF FF B1 08 6A 10 02 18 06", hex(IgpsProtocol.readBattery()))
        assertEquals("01 6A 05 FF 02 FF FF 00 06 DF 01 FF FF FF FF FF FF FF FF A6 08 6A 10 02 18 05", hex(IgpsProtocol.readRemainingTime()))
        assertEquals("01 6A 02 FF 01 FF FF 00 0A 61 01 FF FF FF FF FF FF FF FF 45 08 6A 10 01 18 02 6A 02 08 01", hex(IgpsProtocol.setMode(1)))
        assertEquals("01 6A 02 FF 01 FF FF 00 08 C9 01 FF FF FF FF FF FF FF FF 1A 08 6A 10 01 18 02 6A 00", hex(IgpsProtocol.setMode(0)))
        assertEquals("01 6A 07 FF 01 FF FF 00 0C A1 01 FF FF FF FF FF FF FF FF 61 08 6A 10 01 18 07 5A 04 08 04 10 01", hex(IgpsProtocol.setModeEnabled(4, true)))
    }

    @Test
    fun `parses data responses`() {
        assertEquals(LightUpdate(batteryPercent = 87), IgpsProtocol.parseFrame(Hex.decode("01 6A 06 FF 02 FF FF 00 0A 5D 01 FF FF FF FF FF FF FF FF 81 08 6A 10 02 18 06 7A 02 68 57")))
        assertEquals(LightUpdate(mode = 3), IgpsProtocol.parseFrame(Hex.decode("01 6A 02 FF 02 FF FF 00 0A 9A 01 FF FF FF FF FF FF FF FF 1E 08 6A 10 02 18 02 6A 02 08 03")))
        assertEquals(LightUpdate(remainingMinutes = 200), IgpsProtocol.parseFrame(Hex.decode("01 6A 05 FF 02 FF FF 00 0B EE 01 FF FF FF FF FF FF FF FF E8 08 6A 10 02 18 05 72 03 08 C8 01")))
    }

    @Test
    fun `parses declared modes with enabled flags in order`() {
        val frame = Hex.decode(
            "01 6A 01 FF 02 FF FF 00 1C 40 01 FF FF FF FF FF FF FF FF D2 08 6A 10 02 18 01 " +
                "32 04 08 01 18 01 32 04 08 03 18 01 32 02 08 04 32 04 08 11 18 01",
        )
        val update = IgpsProtocol.parseFrame(frame)!!
        assertEquals(mapOf(1 to true, 3 to true, 4 to false, 17 to true), update.declaredModes)
        assertEquals(listOf(1, 3, 4, 17), update.declaredModes!!.keys.toList())
    }

    @Test
    fun `parses spontaneous state frames from the light's button`() {
        assertEquals(LightUpdate(mode = 2), IgpsProtocol.parseFrame(Hex.decode("03 6A 02 FF 02 FF FF 02 FF FF 01 FF FF FF FF FF FF FF FF 4D")))
        assertEquals(LightUpdate(remainingMinutes = 300), IgpsProtocol.parseFrame(Hex.decode("03 6A 05 FF 02 FF FF 00 FF FF 01 2C 01 00 00 FF FF FF FF BE")))
    }

    @Test
    fun `parses frames captured from the real VS1200S`() {
        // docs/vs1200s-findings.md: button-press state frames and the declared-modes reply (3 notifications joined)
        assertEquals(LightUpdate(mode = 2), IgpsProtocol.parseFrame(Hex.decode("03 6A 02 FF 01 FF FF 02 FF FF FF FF FF FF FF FF FF FF FF DA")))
        assertEquals(LightUpdate(remainingMinutes = 240), IgpsProtocol.parseFrame(Hex.decode("03 6A 05 FF 01 FF FF FF FF FF FF F0 00 00 00 FF FF FF FF 38")))
        val declared = Hex.decode(
            "01 6A 01 FF 02 FF FF 00 26 0E 01 FF FF FF FF FF FF FF FF 30 " +
                "08 6A 10 02 18 01 32 04 08 02 18 01 32 04 08 01 18 01 32 04 " +
                "08 04 18 01 32 04 08 05 18 01 32 06 08 40 10 01 18 01",
        )
        assertEquals(linkedMapOf(2 to true, 1 to true, 4 to true, 5 to true, 64 to true), IgpsProtocol.parseFrame(declared)!!.declaredModes)
    }

    @Test
    fun `ignores frames from other services`() {
        // The VS1200S also emits type-03 frames for service 0x6B; a sub-2 one must not be read as a mode change.
        assertNull(IgpsProtocol.parseFrame(Hex.decode("03 6B 02 FF 01 FF FF 03 FF FF FF FF FF FF FF FF FF FF FF D0")))
        assertNull(IgpsProtocol.parseFrame(Hex.decode("03 6B 03 FF 01 FF FF 03 FF FF FF FF FF FF FF FF FF FF FF FF")))
    }

    @Test
    fun `ignores acks, corrupt and short frames`() {
        assertNull(IgpsProtocol.parseFrame(Hex.decode("02 6A 02 FF 01 FF FF 00 00 00 01 FF FF FF FF FF FF FF FF 05")))
        val corrupt = Hex.decode("01 6A 02 FF 02 FF FF 00 0A 9A 01 FF FF FF FF FF FF FF FF 1E 08 6A 10 02 18 02 6A 02 08 04")
        assertNull(IgpsProtocol.parseFrame(corrupt)) // payload CRC mismatch
        assertNull(IgpsProtocol.parseFrame(Hex.decode("01 6A 02")))
    }

    @Test
    fun `splits writes into 20-byte chunks`() {
        val frame = IgpsProtocol.setMode(12)
        val chunks = IgpsProtocol.chunks(frame)
        assertEquals(listOf(20, 10), chunks.map { it.size })
        assertTrue(chunks.reduce { a, b -> a + b }.contentEquals(frame))
    }

    @Test
    fun `expected length only trusts byte 8 on data frames`() {
        assertEquals(30, IgpsProtocol.expectedLength(IgpsProtocol.setMode(12)))
        assertEquals(20, IgpsProtocol.expectedLength(Hex.decode("03 6A 05 FF 02 FF FF 00 FF FF 01 2C 01 00 00 FF FF FF FF BE")))
    }
}
