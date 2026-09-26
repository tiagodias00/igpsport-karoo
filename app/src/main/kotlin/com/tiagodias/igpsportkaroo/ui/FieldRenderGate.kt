package com.tiagodias.igpsportkaroo.ui

import kotlinx.coroutines.CancellationException

/**
 * Decides when a ride field's view loop renders, [now] being a monotonic clock in ms:
 * - at most once per [minIntervalMs] (Karoo drops updateView calls < ~900 ms apart); a change inside the window
 *   stays pending and renders once the window opens;
 * - when [T] changed since the last successful render, and also every [refreshMs] even when unchanged:
 *   karoo-ext's ViewEmitter drops an update silently (it only logs "ignoring updateView, too soon", on its own
 *   wall-clock gate), so a render can be lost without us knowing; the refresh re-sends it;
 * - a render that throws is reported to [onError], not marked rendered, and retried once the window opens.
 */
class FieldRenderGate<T>(
    private val minIntervalMs: Long = 950,
    private val refreshMs: Long = 5_000,
    private val onError: (Throwable) -> Unit = {},
) {
    private var lastUi: T? = null
    private var lastRenderAt: Long? = null // last successful render; null until the first one
    private var lastAttemptAt: Long? = null // last render attempt, successful or not: the window starts here

    /** Renders [ui] via [render] if due; true if it rendered without throwing. */
    suspend fun offer(ui: T, now: Long, render: suspend (T) -> Unit): Boolean {
        val windowOpen = lastAttemptAt?.let { now - it >= minIntervalMs } ?: true
        if (!windowOpen) return false
        val renderedAt = lastRenderAt
        val due = renderedAt == null || ui != lastUi || now - renderedAt >= refreshMs
        if (!due) return false
        lastAttemptAt = now
        try {
            render(ui)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            onError(e)
            return false
        }
        lastUi = ui
        lastRenderAt = now
        return true
    }
}
