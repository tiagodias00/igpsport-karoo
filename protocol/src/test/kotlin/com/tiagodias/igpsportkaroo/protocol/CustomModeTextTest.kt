package com.tiagodias.igpsportkaroo.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CustomModeTextTest {
    private val slot = CustomModeConfig(
        64, CustomMode.FLASH,
        listOf(
            CustomPattern(CustomMode.STEADY, listOf(CustomLight(CustomMode.MAIN, 30))),
            CustomPattern(CustomMode.FLASH, listOf(CustomLight(CustomMode.LOW_BEAM, 20), CustomLight(CustomMode.HIGH_BEAM, 100)), 2, 30),
        ),
    )

    @Test
    fun `round-trips a config`() {
        assertEquals("64|1|0/2=30//;1/1=20,0=100/2/30", CustomModeText.encode(slot))
        assertEquals(slot, CustomModeText.decode(CustomModeText.encode(slot)))
    }

    @Test
    fun `round-trips a config without patterns or lights`() {
        val bare = CustomModeConfig(65, CustomMode.STEADY, listOf(CustomPattern(CustomMode.STEADY, emptyList())))
        assertEquals(bare, CustomModeText.decode(CustomModeText.encode(bare)))
        val none = CustomModeConfig(66, CustomMode.STEADY, emptyList())
        assertEquals(none, CustomModeText.decode(CustomModeText.encode(none)))
    }

    @Test
    fun `rejects anything malformed`() {
        listOf(null, "", "64", "64|x|", "64|0|0/2=a//", "64|0|0/2=30/", "a|0|").forEach { assertNull(it, CustomModeText.decode(it)) }
    }
}
