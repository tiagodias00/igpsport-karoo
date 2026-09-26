package com.tiagodias.igpsportkaroo.protocol

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ProtoWireTest {
    @Test
    fun `encodes varints`() {
        assertEquals("6A", Hex.encode(ProtoWire.varint(106)))
        assertEquals("AC 02", Hex.encode(ProtoWire.varint(300)))
    }

    @Test
    fun `encodes fields`() {
        assertEquals("08 6A", Hex.encode(ProtoWire.varintField(1, 106)))
        assertEquals("6A 02 08 0C", Hex.encode(ProtoWire.messageField(13, ProtoWire.varintField(1, 12))))
        assertEquals("6A 00", Hex.encode(ProtoWire.messageField(13, ByteArray(0))))
    }

    @Test
    fun `parses varints and nested messages`() {
        val fields = ProtoWire.parse(Hex.decode("08 6A 10 02 18 02 6A 02 08 03"))!!
        assertEquals(106L, fields.varint(1))
        assertEquals(2L, fields.varint(3))
        assertArrayEquals(Hex.decode("08 03"), fields.message(13))
        assertEquals(3L, ProtoWire.parse(fields.message(13)!!)!!.varint(1))
    }

    @Test
    fun `keeps repeated fields in order`() {
        val fields = ProtoWire.parse(Hex.decode("32 02 08 01 32 02 08 03"))!!
        assertEquals(listOf("08 01", "08 03"), fields.messages(6).map(Hex::encode))
    }

    @Test
    fun `rejects truncated and unsupported input`() {
        assertNull(ProtoWire.parse(Hex.decode("6A 05 08"))) // length runs past end
        assertNull(ProtoWire.parse(Hex.decode("0D 00 00 00 00"))) // wire type 5 (fixed32)
        assertNull(ProtoWire.parse(Hex.decode("08 FF"))) // unterminated varint
    }

    @Test
    fun `rejects oversized length fields without throwing`() {
        assertNull(ProtoWire.parse(Hex.decode("0A FF FF FF FF 07 00"))) // Int.MAX_VALUE: pos + len overflows
        assertNull(ProtoWire.parse(Hex.decode("0A 85 80 80 80 10 00"))) // 2^32 + 5: truncates to 5 as an Int
        assertNull(ProtoWire.parse(Hex.decode("0A FF FF FF FF FF FF FF FF FF 01"))) // -1 as a 64-bit varint
    }
}
