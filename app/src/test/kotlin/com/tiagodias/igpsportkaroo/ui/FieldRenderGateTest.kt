package com.tiagodias.igpsportkaroo.ui

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FieldRenderGateTest {
    private val gate = FieldRenderGate<String>()
    private val rendered = mutableListOf<Pair<Long, String>>()

    /** Offers [ui] every 100 ms (the view loop's poll) from [from] until [to], inclusive. */
    private suspend fun poll(ui: String, from: Long, to: Long) {
        var t = from
        while (t <= to) {
            gate.offer(ui, t) { rendered += t to it }
            t += 100
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

    @Test
    fun `a render that throws is not marked rendered and is retried`() = runTest {
        var failNext = true
        var t = 0L
        while (t <= 2_000) {
            val at = t
            gate.offer("A", at) {
                if (failNext) {
                    failNext = false
                    throw IllegalStateException("compose failed")
                }
                rendered += at to it
            }
            t += 100
        }
        assertEquals(listOf(1_000L to "A"), rendered) // retried once the window after the failed attempt opened
    }

    @Test
    fun `a throwing render reports the error instead of propagating it`() = runTest {
        val errors = mutableListOf<Throwable>()
        val gate = FieldRenderGate<String>(onError = { errors += it })
        assertFalse(gate.offer("A", 0) { throw IllegalStateException("boom") })
        assertEquals(1, errors.size)
    }
}
