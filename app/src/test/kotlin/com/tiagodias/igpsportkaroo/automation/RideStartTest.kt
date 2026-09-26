package com.tiagodias.igpsportkaroo.automation

import org.junit.Assert.assertEquals
import org.junit.Test

class RideStartTest {
    @Test
    fun `encodes to the stored strings`() {
        assertEquals("none", RideStart.None.encode())
        assertEquals("auto", RideStart.Auto.encode())
        assertEquals("mode:64", RideStart.Mode(64).encode())
    }

    @Test
    fun `decodes what it encodes`() {
        listOf(RideStart.None, RideStart.Auto, RideStart.Mode(1), RideStart.Mode(5), RideStart.Mode(64)).forEach {
            assertEquals(it, RideStart.decode(it.encode()))
        }
    }

    @Test
    fun `garbage decodes to none`() {
        listOf(null, "", "AUTO", "mode:", "mode:abc", "mode:0", "mode:-3", "mode:1:2", "slot:1").forEach {
            assertEquals("decode($it)", RideStart.None, RideStart.decode(it))
        }
    }

    @Test
    fun `choices are don't change, auto, then steady and flash levels`() {
        // The VS1200S choosable modes: HIGH, MID, FLASH HI, FLASH LO, CUSTOM 1 (= LOW), OFF.
        assertEquals(
            listOf(
                RideStart.None, RideStart.Auto,
                RideStart.Mode(1), RideStart.Mode(2), RideStart.Mode(64), RideStart.Mode(4), RideStart.Mode(5),
            ),
            RideStart.choices(listOf(1, 2, 4, 5, 64, 0)),
        )
    }
}
