package com.tiagodias.igpsportkaroo.ui

import com.tiagodias.igpsportkaroo.protocol.CustomChange
import com.tiagodias.igpsportkaroo.protocol.CustomLight
import com.tiagodias.igpsportkaroo.protocol.CustomMode
import com.tiagodias.igpsportkaroo.protocol.CustomModeConfig
import com.tiagodias.igpsportkaroo.protocol.CustomPattern
import com.tiagodias.igpsportkaroo.protocol.LightState
import com.tiagodias.igpsportkaroo.protocol.LightUpdate
import com.tiagodias.igpsportkaroo.ui.CustomEditor.Key
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CustomEditorTest {
    private val c1 = CustomModeConfig(
        64, CustomMode.STEADY,
        listOf(
            CustomPattern(CustomMode.STEADY, listOf(CustomLight(CustomMode.MAIN, 30))),
            CustomPattern(CustomMode.FLASH, listOf(CustomLight(CustomMode.MAIN, 100)), 2, 30),
        ),
    )
    private val vs1200s = LightState(connected = true, declaredModes = linkedMapOf(2 to true, 1 to true, 4 to true, 5 to true, 64 to true))
    private val known = vs1200s.apply(LightUpdate(customMode = c1))

    @Test
    fun `lists only the custom slots the light declares`() {
        val editor = CustomEditor.from(known, requestedSlot = null, original = c1)
        assertEquals(listOf(CustomEditor.Slot(64, "CUSTOM 1")), editor.slots)
        assertEquals(64, editor.slot)
        assertEquals(64, CustomEditor.from(known, requestedSlot = 65, original = c1).slot) // not declared: first slot
        assertTrue(CustomEditor.from(LightState(connected = true), null, null).slots.isEmpty())
        assertNull(CustomEditor.from(LightState(connected = true), null, null).slot)
    }

    @Test
    fun `a slot whose config is unknown shows no controls`() {
        val editor = CustomEditor.from(vs1200s, 64, null)
        assertTrue(editor.reading)
        assertTrue(editor.patterns.isEmpty())
        assertTrue(editor.sliders.isEmpty())
        assertFalse(editor.canPreview)
        assertFalse(editor.canRestore)
    }

    @Test
    fun `a steady slot offers its patterns and one brightness slider`() {
        val editor = CustomEditor.from(known, 64, c1)
        assertFalse(editor.reading)
        assertEquals(
            listOf(CustomEditor.PatternChoice(CustomMode.STEADY, "Steady", true), CustomEditor.PatternChoice(CustomMode.FLASH, "Flash", false)),
            editor.patterns,
        )
        assertEquals(listOf(CustomEditor.Slider(Key.Brightness(CustomMode.MAIN), "Brightness", 5..100, 30, "%")), editor.sliders)
        assertTrue(editor.canPreview)
        assertFalse(editor.canRestore) // same as the original
    }

    @Test
    fun `a flash slot adds cycle and lighting-time sliders`() {
        val editor = CustomEditor.from(known.apply(LightUpdate(customMode = c1.copy(selected = CustomMode.FLASH))), 64, c1)
        assertEquals(
            listOf(
                CustomEditor.Slider(Key.Brightness(CustomMode.MAIN), "Brightness", 5..100, 100, "%"),
                CustomEditor.Slider(Key.Cycle, "Cycle", 1..4, 2, " s"),
                CustomEditor.Slider(Key.Ratio, "Lighting time", 10..50, 30, "%"),
            ),
            editor.sliders,
        )
        assertTrue(editor.canRestore) // differs from the original (flash selected)
    }

    @Test
    fun `beam channels get their own labels, in the iGPSPORT app's order`() {
        val beams = c1.copy(patterns = listOf(CustomPattern(CustomMode.STEADY, listOf(CustomLight(CustomMode.LOW_BEAM, 20), CustomLight(CustomMode.HIGH_BEAM, 80)))))
        val editor = CustomEditor.from(vs1200s.apply(LightUpdate(customMode = beams)), 64, beams)
        assertEquals(listOf("Low beam", "High beam"), editor.sliders.map { it.label }) // lightNum descending, like the iGPSPORT app
    }

    @Test
    fun `clamps slider values into their ranges`() {
        val odd = c1.copy(
            selected = CustomMode.FLASH,
            patterns = listOf(CustomPattern(CustomMode.FLASH, listOf(CustomLight(CustomMode.MAIN, 0)), cycleSeconds = 9, ratioPercent = null)),
        )
        val editor = CustomEditor.from(vs1200s.apply(LightUpdate(customMode = odd)), 64, null)
        assertEquals(listOf(5, 4, 10), editor.sliders.map { it.value })
        assertEquals(CustomChange.Brightness(CustomMode.FLASH, CustomMode.MAIN, 100), CustomEditor.changeFor(odd, Key.Brightness(CustomMode.MAIN), 250))
        assertEquals(CustomChange.Cycle(CustomMode.FLASH, 1), CustomEditor.changeFor(odd, Key.Cycle, 0))
        assertEquals(CustomChange.Ratio(CustomMode.FLASH, 50), CustomEditor.changeFor(odd, Key.Ratio, 99))
        assertEquals(CustomChange.Brightness(CustomMode.FLASH, CustomMode.MAIN, 5), CustomEditor.changeFor(odd, Key.Brightness(CustomMode.MAIN), 1))
    }

    @Test
    fun `restore is not offered when the original holds a value the light can't be sent`() {
        // The light stores anything (e.g. 101 %), so a snapshot can be out of the app's ranges: restoring it would fail.
        val stored101 = c1.copy(patterns = listOf(CustomPattern(CustomMode.STEADY, listOf(CustomLight(CustomMode.MAIN, 101)))))
        assertFalse(CustomEditor.from(known, 64, stored101).canRestore)
        val badCycle = c1.copy(
            selected = CustomMode.FLASH,
            patterns = listOf(CustomPattern(CustomMode.FLASH, listOf(CustomLight(CustomMode.MAIN, 100)), cycleSeconds = 9, ratioPercent = 30)),
        )
        assertFalse(CustomEditor.from(known, 64, badCycle).canRestore)
        val fine = c1.copy(patterns = listOf(CustomPattern(CustomMode.STEADY, listOf(CustomLight(CustomMode.MAIN, 60)))))
        assertTrue(CustomEditor.from(known, 64, fine).canRestore)
    }

    @Test
    fun `nothing is possible while disconnected`() {
        val editor = CustomEditor.from(known.copy(connected = false), 64, c1.copy(selected = CustomMode.FLASH))
        assertFalse(editor.canPreview)
        assertFalse(editor.canRestore)
    }
}
