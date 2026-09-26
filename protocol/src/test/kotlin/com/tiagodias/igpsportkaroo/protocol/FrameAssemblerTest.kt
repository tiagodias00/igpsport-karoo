package com.tiagodias.igpsportkaroo.protocol

import org.junit.Assert.assertEquals
import org.junit.Test

class FrameAssemblerTest {
    private val battery = Hex.decode("01 6A 06 FF 02 FF FF 00 0A 5D 01 FF FF FF FF FF FF FF FF 81 08 6A 10 02 18 06 7A 02 68 57")
    private val ack = Hex.decode("02 6A 02 FF 01 FF FF 00 00 00 01 FF FF FF FF FF FF FF FF 05")
    private val stateMode2 = Hex.decode("03 6A 02 FF 02 FF FF 02 FF FF 01 FF FF FF FF FF FF FF FF 4D")

    private fun List<ByteArray>.hex() = map(Hex::encode)

    @Test
    fun `whole frame in one fragment`() {
        assertEquals(listOf(battery).hex(), FrameAssembler().push(battery).hex())
    }

    @Test
    fun `frame split at the header boundary`() {
        val asm = FrameAssembler()
        assertEquals(emptyList<String>(), asm.push(battery.copyOfRange(0, 20)).hex())
        assertEquals(listOf(battery).hex(), asm.push(battery.copyOfRange(20, battery.size)).hex())
    }

    @Test
    fun `frame split inside the header`() {
        val asm = FrameAssembler()
        assertEquals(emptyList<String>(), asm.push(battery.copyOfRange(0, 7)).hex())
        assertEquals(listOf(battery).hex(), asm.push(battery.copyOfRange(7, battery.size)).hex())
    }

    @Test
    fun `two frames in one fragment`() {
        assertEquals(listOf(ack, stateMode2).hex(), FrameAssembler().push(ack + stateMode2).hex())
    }

    @Test
    fun `resyncs after a garbage byte`() {
        assertEquals(listOf(battery).hex(), FrameAssembler().push(byteArrayOf(0) + battery).hex())
    }

    @Test
    fun `reset drops a partial frame`() {
        val asm = FrameAssembler()
        asm.push(battery.copyOfRange(0, 20))
        asm.reset()
        assertEquals(listOf(ack).hex(), asm.push(ack).hex())
    }
}
