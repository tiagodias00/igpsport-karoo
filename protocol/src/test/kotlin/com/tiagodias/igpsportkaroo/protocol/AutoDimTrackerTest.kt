package com.tiagodias.igpsportkaroo.protocol

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoDimTrackerTest {
    @Test
    fun `dimmed once remaining time jumps well above the shortest seen for the mode`() {
        val tracker = AutoDimTracker()
        assertFalse(tracker.update(mode = 1, autoOn = true, remainingMinutes = 155)) // establishes the reference
        assertTrue(tracker.update(mode = 1, autoOn = true, remainingMinutes = 235))
        assertFalse(tracker.update(mode = 1, autoOn = true, remainingMinutes = 155)) // back to full: not dimmed
        assertFalse(tracker.update(mode = 1, autoOn = true, remainingMinutes = 150)) // a new, lower reference
        assertTrue(tracker.update(mode = 1, autoOn = true, remainingMinutes = 230))
    }

    @Test
    fun `a mode change resets the reference`() {
        val tracker = AutoDimTracker()
        assertFalse(tracker.update(mode = 1, autoOn = true, remainingMinutes = 150)) // establishes the reference
        assertTrue(tracker.update(mode = 1, autoOn = true, remainingMinutes = 230))
        // 300 would be "dimmed" against the old HIGH reference, but the mode changed: it becomes the new
        // reference for MID instead, so it is not dimmed yet.
        assertFalse(tracker.update(mode = 2, autoOn = true, remainingMinutes = 300))
        assertTrue(tracker.update(mode = 2, autoOn = true, remainingMinutes = 400))
    }

    @Test
    fun `auto off reports not dimmed and forgets the reference`() {
        val tracker = AutoDimTracker()
        tracker.update(mode = 1, autoOn = true, remainingMinutes = 150)
        assertFalse(tracker.update(mode = 1, autoOn = false, remainingMinutes = 230))
        // The reference was forgotten: back on, 230 becomes the new reference rather than looking dimmed.
        assertFalse(tracker.update(mode = 1, autoOn = true, remainingMinutes = 230))
    }

    @Test
    fun `unknown mode or remaining time reports not dimmed`() {
        val tracker = AutoDimTracker()
        tracker.update(mode = 1, autoOn = true, remainingMinutes = 150)
        assertFalse(tracker.update(mode = null, autoOn = true, remainingMinutes = 230))
        assertFalse(tracker.update(mode = 1, autoOn = true, remainingMinutes = null))
    }

    @Test
    fun `no reference yet reports not dimmed`() {
        val tracker = AutoDimTracker()
        assertFalse(tracker.update(mode = 1, autoOn = true, remainingMinutes = 999))
    }

    @Test
    fun `reset forgets the reference`() {
        val tracker = AutoDimTracker()
        tracker.update(mode = 1, autoOn = true, remainingMinutes = 150)
        tracker.reset()
        // 230 would be "dimmed" against the old reference, but reset() forgot it: it becomes the new one.
        assertFalse(tracker.update(mode = 1, autoOn = true, remainingMinutes = 230))
    }
}
