package com.tiagodias.igpsportkaroo.ui

import kotlinx.coroutines.CancellationException

/**
 * Decides when a ride field's view loop renders, [clock] being a monotonic clock in ms:
 * - at most once per [minIntervalMs] (Karoo drops updateView calls < ~900 ms apart), counted from when the last
 *   render attempt returned, since Karoo's own gate starts when updateView ran, at the end of a possibly slow
 *   compose; a change inside the window stays pending and renders once the window opens;
 * - when [T] changed since the last successful render, and also every [refreshMs] even when unchanged:
 *   karoo-ext's ViewEmitter drops an update silently (it only logs "ignoring updateView, too soon", on its own
 *   wall-clock gate), so a render can be lost without us knowing; the refresh re-sends it;
 * - a render that throws is not marked rendered and is retried once the window opens; it is reported to
 *   [onError] at most once per [errorLogIntervalMs], so a persistent failure does not flood the log.
 */
class FieldRenderGate<T>(
    private val clock: () -> Long,
    private val minIntervalMs: Long = 950,
    private val refreshMs: Long = 5_000,
    private val errorLogIntervalMs: Long = 30_000,
    private val onError: (Throwable) -> Unit = {},
) {
    private var lastUi: T? = null
    private var lastRenderAt: Long? = null // when the last successful render returned; null until the first one
    private var lastAttemptAt: Long? = null // when the last render attempt returned, successful or not
    private var lastErrorLoggedAt: Long? = null

    /** Renders [ui] via [render] if due; true if it rendered without throwing. */
    suspend fun offer(ui: T, render: suspend (T) -> Unit): Boolean {
        val now = clock()
        val windowOpen = lastAttemptAt?.let { now - it >= minIntervalMs } ?: true
        if (!windowOpen) return false
        val renderedAt = lastRenderAt
        val due = renderedAt == null || ui != lastUi || now - renderedAt >= refreshMs
        if (!due) return false
        try {
            render(ui)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val failedAt = clock()
            lastAttemptAt = failedAt
            if (lastErrorLoggedAt?.let { failedAt - it >= errorLogIntervalMs } != false) {
                lastErrorLoggedAt = failedAt
                onError(e)
            }
            return false
        }
        val doneAt = clock()
        lastAttemptAt = doneAt
        lastUi = ui
        lastRenderAt = doneAt
        return true
    }
}
