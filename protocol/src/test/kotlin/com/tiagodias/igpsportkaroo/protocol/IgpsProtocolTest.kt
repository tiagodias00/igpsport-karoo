package com.tiagodias.igpsportkaroo.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
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
        assertEquals(LightUpdate(remainingMinutes = 300, outputOff = false), IgpsProtocol.parseFrame(Hex.decode("03 6A 05 FF 02 FF FF 00 FF FF 01 2C 01 00 00 FF FF FF FF BE")))
    }

    @Test
    fun `parses frames captured from the real VS1200S`() {
        // Captured from a VS1200S: button-press state frames and the declared-modes reply (3 notifications joined)
        assertEquals(LightUpdate(mode = 2), IgpsProtocol.parseFrame(Hex.decode("03 6A 02 FF 01 FF FF 02 FF FF FF FF FF FF FF FF FF FF FF DA")))
        assertEquals(LightUpdate(remainingMinutes = 240, outputOff = false), IgpsProtocol.parseFrame(Hex.decode("03 6A 05 FF 01 FF FF FF FF FF FF F0 00 00 00 FF FF FF FF 38")))
        val declared = Hex.decode(
            "01 6A 01 FF 02 FF FF 00 26 0E 01 FF FF FF FF FF FF FF FF 30 " +
                "08 6A 10 02 18 01 32 04 08 02 18 01 32 04 08 01 18 01 32 04 " +
                "08 04 18 01 32 04 08 05 18 01 32 06 08 40 10 01 18 01",
        )
        assertEquals(linkedMapOf(2 to true, 1 to true, 4 to true, 5 to true, 64 to true), IgpsProtocol.parseFrame(declared)!!.declaredModes)
    }

    @Test
    fun `builds smart-config requests identical to the real light capture`() {
        assertEquals("01 6A 04 FF 02 FF FF 00 06 81 01 FF FF FF FF FF FF FF FF 5C 08 6A 10 02 18 04", hex(IgpsProtocol.readSmartConfigs()))
        assertEquals(
            "01 6A 04 FF 01 FF FF 00 0A 15 01 FF FF FF FF FF FF FF FF 49 08 6A 10 01 18 04 52 02 08 03",
            hex(IgpsProtocol.setSmartConfig(SmartConfig.AUTO_LIGHT, SmartConfig.OFF)),
        )
        assertEquals(
            "01 6A 04 FF 01 FF FF 00 0C 3D 01 FF FF FF FF FF FF FF FF 3B 08 6A 10 01 18 04 52 04 08 03 10 01",
            hex(IgpsProtocol.setSmartConfig(SmartConfig.AUTO_LIGHT, SmartConfig.ON)),
        )
    }

    @Test
    fun `parses the smart-config reply in the light's order`() {
        // Captured from a VS1200S: its reply, joined; status omitted = off, field 3 = timeout seconds.
        val reply = Hex.decode(
            "01 6A 04 FF 02 FF FF 00 30 6C 01 FF FF FF FF FF FF FF FF BE 08 6A 10 02 18 04 4A 02 08 05 " +
                "4A 04 08 03 10 01 4A 04 08 09 10 01 4A 08 08 04 10 01 1A 02 08 3C 4A 08 08 0D 10 01 1A 02 08 1E " +
                "4A 04 08 0F 10 01",
        )
        val configs = IgpsProtocol.parseFrame(reply)!!.smartConfigs!!
        assertEquals(mapOf(5 to 0, 3 to 1, 9 to 1, 4 to 1, 13 to 1, 15 to 1), configs)
        assertEquals(listOf(5, 3, 9, 4, 13, 15), configs.keys.toList())
    }

    @Test
    fun `reads the battery reading from the light's 0x6B sub-7 frames`() {
        assertEquals(
            LightUpdate(auxBatteryPercent = 97),
            IgpsProtocol.parseFrame(Hex.decode("03 6B 07 FF 01 FF FF 61 FF FF FF FF FF FF FF FF FF FF FF 55")),
        )
        assertEquals(
            LightUpdate(auxBatteryPercent = 94),
            IgpsProtocol.parseFrame(Hex.decode("03 6B 07 FF 01 FF FF 5E FF FF FF FF FF FF FF FF FF FF FF 5C")),
        )
    }

    @Test
    fun `a run-time state frame without a run time means the output is off`() {
        // Captured while auto light had switched the VS1200S off in daylight; not 16 777 215 minutes.
        assertEquals(
            LightUpdate(outputOff = true),
            IgpsProtocol.parseFrame(Hex.decode("03 6A 05 FF 01 FF FF FF FF FF FF FF FF FF FF FF FF FF FF 5B")),
        )
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

    // Derived with tools/probe/igps.py (not captured).
    private val customReplySteady = Hex.decode(
        "01 6A 03 FF 02 FF FF 00 24 42 01 FF FF FF FF FF FF FF FF C1 08 6A 10 02 18 03 42 1C 08 40 1A 06 12 04 08 02 10 1E " +
            "1A 10 08 01 12 04 08 02 10 64 1A 02 08 02 22 02 08 1E",
    )
    private val steady30 = CustomPattern(CustomMode.STEADY, listOf(CustomLight(CustomMode.MAIN, 30)))
    private val flash100 = CustomPattern(CustomMode.FLASH, listOf(CustomLight(CustomMode.MAIN, 100)), cycleSeconds = 2, ratioPercent = 30)

    @Test
    fun `builds custom-mode requests like the iGPSPORT app`() {
        assertEquals(
            "01 6A 03 FF 02 FF FF 00 0A 2B 01 FF FF FF FF FF FF FF FF 79 08 6A 10 02 18 03 3A 02 08 40",
            hex(IgpsProtocol.readCustomMode(64)),
        )
        assertEquals(
            "01 6A 03 FF 01 FF FF 00 0A A8 01 FF FF FF FF FF FF FF FF F9 08 6A 10 01 18 03 62 02 08 40",
            hex(IgpsProtocol.modifyCustomMode(64, CustomChange.Pattern(CustomMode.STEADY))),
        )
        assertEquals(
            "01 6A 03 FF 01 FF FF 00 0C B6 01 FF FF FF FF FF FF FF FF C9 08 6A 10 01 18 03 62 04 08 40 10 01",
            hex(IgpsProtocol.modifyCustomMode(64, CustomChange.Pattern(CustomMode.FLASH))),
        )
        assertEquals(
            "01 6A 03 FF 01 FF FF 00 10 24 01 FF FF FF FF FF FF FF FF 36 08 6A 10 01 18 03 62 08 08 40 1A 04 08 02 10 28",
            hex(IgpsProtocol.modifyCustomMode(64, CustomChange.Brightness(CustomMode.STEADY, CustomMode.MAIN, 40))),
        )
        assertEquals(
            "01 6A 03 FF 01 FF FF 00 10 95 01 FF FF FF FF FF FF FF FF 7E 08 6A 10 01 18 03 62 08 08 40 10 01 22 02 08 02",
            hex(IgpsProtocol.modifyCustomMode(64, CustomChange.Cycle(CustomMode.FLASH, 2))),
        )
        assertEquals(
            "01 6A 03 FF 01 FF FF 00 10 B7 01 FF FF FF FF FF FF FF FF 63 08 6A 10 01 18 03 62 08 08 40 10 01 2A 02 08 1E",
            hex(IgpsProtocol.modifyCustomMode(64, CustomChange.Ratio(CustomMode.FLASH, 30))),
        )
    }

    @Test
    fun `leaves zero values out of custom-mode frames like proto3`() {
        // High beam (lightNum 0): the value message only holds pct.
        assertEquals(
            "01 6A 03 FF 01 FF FF 00 0E 20 01 FF FF FF FF FF FF FF FF F2 08 6A 10 01 18 03 62 06 08 40 1A 02 10 28",
            hex(IgpsProtocol.modifyCustomMode(64, CustomChange.Brightness(CustomMode.STEADY, CustomMode.HIGH_BEAM, 40))),
        )
        // 0 %: the value message only holds lightNum.
        assertEquals(
            "01 6A 03 FF 01 FF FF 00 0E E7 01 FF FF FF FF FF FF FF FF D1 08 6A 10 01 18 03 62 06 08 40 1A 02 08 02",
            hex(IgpsProtocol.modifyCustomMode(64, CustomChange.Brightness(CustomMode.STEADY, CustomMode.MAIN, 0))),
        )
    }

    @Test
    fun `rejects values outside the app's ranges`() {
        listOf(
            CustomChange.Brightness(CustomMode.STEADY, CustomMode.MAIN, 101),
            CustomChange.Brightness(CustomMode.STEADY, CustomMode.MAIN, -1),
            CustomChange.Cycle(CustomMode.FLASH, 0),
            CustomChange.Cycle(CustomMode.FLASH, 5),
            CustomChange.Ratio(CustomMode.FLASH, 5),
            CustomChange.Ratio(CustomMode.FLASH, 60),
            CustomChange.Pattern(3),
        ).forEach { change ->
            assertThrows(IllegalArgumentException::class.java) { IgpsProtocol.modifyCustomMode(64, change) }
        }
        assertThrows(IllegalArgumentException::class.java) { IgpsProtocol.readCustomMode(63) }
        assertThrows(IllegalArgumentException::class.java) { IgpsProtocol.modifyCustomMode(76, CustomChange.Pattern(CustomMode.STEADY)) }
    }

    @Test
    fun `parses a custom-mode reply`() {
        assertEquals(
            LightUpdate(customMode = CustomModeConfig(64, CustomMode.STEADY, listOf(steady30, flash100))),
            IgpsProtocol.parseFrame(customReplySteady),
        )
    }

    @Test
    fun `parses an empty custom-mode reply as nothing`() {
        // An empty modeArg (e.g. an undeclared slot) carries no mode: no custom config, not a crash.
        val empty = Hex.decode("01 6A 03 FF 02 FF FF 00 08 9B 01 FF FF FF FF FF FF FF FF 4C 08 6A 10 02 18 03 42 00")
        assertEquals(LightUpdate(), IgpsProtocol.parseFrame(empty))
    }

    @Test
    fun `parses the custom-mode reply captured from the real VS1200S`() {
        // Read with the PC probe before any edit: custom 64 as the light shipped it (its LOW).
        val reply = Hex.decode(
            "01 6A 03 FF 02 FF FF 00 24 F3 01 FF FF FF FF FF FF FF FF 89 08 6A 10 02 18 03 42 1C 08 40 1A 06 12 04 08 02 10 11 " +
                "1A 10 08 01 12 04 08 02 10 14 1A 02 08 04 22 02 08 19",
        )
        assertEquals(
            LightUpdate(
                customMode = CustomModeConfig(
                    64,
                    CustomMode.STEADY,
                    listOf(
                        CustomPattern(CustomMode.STEADY, listOf(CustomLight(CustomMode.MAIN, 17))),
                        CustomPattern(CustomMode.FLASH, listOf(CustomLight(CustomMode.MAIN, 20)), cycleSeconds = 4, ratioPercent = 25),
                    ),
                ),
            ),
            IgpsProtocol.parseFrame(reply),
        )
    }
}
