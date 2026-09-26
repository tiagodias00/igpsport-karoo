package com.tiagodias.igpsportkaroo.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LightModesTest {
    @Test
    fun `labels known, custom and unknown modes`() {
        assertEquals("HIGH", LightModes.label(1))
        assertEquals("FLASH HI", LightModes.label(4))
        assertEquals("SOS", LightModes.label(17))
        assertEquals("CUSTOM 2", LightModes.label(65))
        assertEquals("MODE 99", LightModes.label(99))
    }

    @Test
    fun `next cycles through enabled modes and wraps`() {
        assertEquals(2, LightModes.next(1, listOf(1, 2, 3)))
        assertEquals(1, LightModes.next(3, listOf(1, 2, 3)))
    }

    @Test
    fun `next starts at the first mode when current is unknown or not in the list`() {
        assertEquals(1, LightModes.next(null, listOf(1, 3)))
        assertEquals(1, LightModes.next(5, listOf(1, 3)))
    }

    @Test
    fun `next never selects off and returns null with nothing to cycle`() {
        assertEquals(2, LightModes.next(1, listOf(0, 1, 2)))
        assertNull(LightModes.next(1, emptyList()))
        assertNull(LightModes.next(1, listOf(0)))
    }
}
