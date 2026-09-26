package com.tiagodias.igpsportkaroo.ui

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FieldRenderGateTest {
    private var clock = 0L
    private val errors = mutableListOf<Throwable>()
    private val gate = FieldRenderGate<String>(clock = { clock }, onError = { errors += it })
    private val rendered = mutableListOf<Pair<Long, String>>()

    /** Offers [ui] every 100 ms (the view loop's poll) from [from] until [to], inclusive. */
    private suspend fun poll(ui: String, from: Long, to: Long) {
        clock = from
        while (clock <= to) {
            gate.offer(ui) { rendered += clock to it }
            clock += 100
        }
    }

    @Test
    fun `renders the first ui right away, then on change`() = runTest {
        poll("A", 0, 0)
        poll("B", 2_000, 2_000)
        assertEquals(listOf(0L to "A", 2_000L to "B"), rendered)
    }

    @Test
    fun `a change inside the render window waits for it to open`() = runTest {
        poll("A", 0, 0)
        poll("B", 300, 1_500)
        assertEquals(listOf(0L to "A", 1_000L to "B"), rendered)
    }

    @Test
    fun `an unchanged ui is not re-rendered within the refresh interval`() = runTest {
        poll("A", 0, 4_900)
        assertEquals(listOf(0L to "A"), rendered)
    }

    // Device log 13:46 (device-log-1346.txt): after an AUTO tap the field's ui changes exactly once (the
    // optimistic update; the light's read-back is identical and it sends no run-time frame). If Karoo drops that
    // one updateView (karoo-ext's ViewEmitter drops silently on its own wall-clock gate, which the log shows
    // firing despite our 950 ms monotonic gate), the field stays on FLASH for good while the app page, which
    // re-renders every 500 ms, shows AUTO.
    @Test
    fun `a render Karoo silently dropped is sent again`() = runTest {
        poll("FLASH", 0, 0)
        poll("AUTO", 20_000, 20_000) // dropped by Karoo: updateView returns normally, nothing shows
        poll("AUTO", 20_100, 30_000)
        val autoRenders = rendered.filter { it.second == "AUTO" }.map { it.first }
        assertTrue("AUTO rendered at $autoRenders", autoRenders.size >= 2)
        assertTrue("AUTO rendered at $autoRenders", autoRenders[1] <= 25_000)
    }

    @Test
    fun `a periodic refresh never breaks the render window`() = runTest {
        poll("A", 0, 20_000)
        val times = rendered.map { it.first }
        assertTrue(times.zipWithNext().all { (a, b) -> b - a >= 950 })
    }

    // Karoo's own gate counts from when updateView ran, which ends a slow compose: the window must start there.
    @Test
    fun `the render window starts when the render returns, not when it starts`() = runTest {
        val returnedAt = mutableListOf<Long>()
        gate.offer("A") {
            clock += 400 // a slow compose
            returnedAt += clock
        }
        clock = 1_000 // 1000 ms after the start, only 600 ms after updateView
        assertFalse(gate.offer("B") { returnedAt += clock })
        clock = 1_350 // 950 ms after updateView
        assertTrue(gate.offer("B") { returnedAt += clock })
        assertEquals(listOf(400L, 1_350L), returnedAt)
    }

    @Test
    fun `a render that throws is not marked rendered and is retried`() = runTest {
        var failNext = true
        clock = 0
        while (clock <= 2_000) {
            gate.offer("A") {
                if (failNext) {
                    failNext = false
                    throw IllegalStateException("compose failed")
                }
                rendered += clock to it
            }
            clock += 100
        }
        assertEquals(listOf(1_000L to "A"), rendered) // retried once the window after the failed attempt opened
    }

    @Test
    fun `a throwing render reports the error instead of propagating it`() = runTest {
        assertFalse(gate.offer("A") { throw IllegalStateException("boom") })
        assertEquals(1, errors.size)
    }

    @Test
    fun `render errors are reported at most once per 30 s`() = runTest {
        clock = 0
        while (clock <= 60_000) {
            gate.offer("A") { throw IllegalStateException("boom") }
            clock += 100
        }
        assertEquals(3, errors.size) // at 0, ~30 s and ~60 s, though a render was attempted about every second
    }
}
