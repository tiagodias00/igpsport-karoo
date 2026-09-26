package com.tiagodias.igpsportkaroo.protocol

import org.junit.Assert.assertEquals
import org.junit.Test

class Crc8Test {
    @Test
    fun `standard CRC-8 MAXIM check value`() {
        assertEquals(0xA1, Crc8.maxim("123456789".toByteArray()))
    }

    @Test
    fun `matches captured read-current-mode frame`() {
        val frame = Hex.decode("01 6A 02 FF 02 FF FF 00 06 5C 01 FF FF FF FF FF FF FF FF 72 08 6A 10 02 18 02")
        assertEquals(0x5C, Crc8.maxim(frame, 20, frame.size)) // payload CRC (header byte 9)
        assertEquals(0x72, Crc8.maxim(frame, 0, 19)) // header CRC (header byte 19)
    }

    @Test
    fun `hex round trip`() {
        assertEquals("01 AB FF", Hex.encode(Hex.decode("01abff")))
    }
}
