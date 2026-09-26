package com.tiagodias.igpsportkaroo.ui

import android.content.Context
import android.os.SystemClock
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.glance.appwidget.ExperimentalGlanceRemoteViewsApi
import androidx.glance.appwidget.GlanceRemoteViews
import com.tiagodias.igpsportkaroo.Settings
import com.tiagodias.igpsportkaroo.light.LightHub
import com.tiagodias.igpsportkaroo.protocol.LightState
import io.hammerhead.karooext.extension.DataTypeImpl
import io.hammerhead.karooext.internal.ViewEmitter
import io.hammerhead.karooext.models.UpdateGraphicConfig
import io.hammerhead.karooext.models.ViewConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** The view loop shared by the ride-field variants: polls [LightHub.session] every [POLL_MS] and renders [Content] when it changes. */
@OptIn(ExperimentalGlanceRemoteViewsApi::class)
abstract class GlanceFieldDataType(extension: String, typeId: String) : DataTypeImpl(extension, typeId) {
    private val glance = GlanceRemoteViews()

    /** [size] is the slot's size in dp; [interactive] is false in preview mode (no click handlers then). */
    @Composable
    protected abstract fun Content(ui: FieldUi, interactive: Boolean, size: DpSize)

    override fun startView(context: Context, config: ViewConfig, emitter: ViewEmitter) {
        emitter.onNext(UpdateGraphicConfig(showHeader = false))
        val settings = Settings(context)
        val density = context.resources.displayMetrics.density.coerceAtLeast(1f)
        val size = DpSize((config.viewSize.first / density).dp, (config.viewSize.second / density).dp)
        val job = CoroutineScope(Dispatchers.IO).launch {
            var lastUi: FieldUi? = null
            var lastRenderAt: Long? = null // null until the first render, which happens right away
            while (isActive) {
                val state = if (config.preview) PREVIEW_STATE else LightHub.session?.state?.value ?: LightState()
                val ui = FieldUi.from(state, settings.slotModes())
                // Poll fast so a tap shows on the next open render window, but render only when something changed
                // and at most once per MIN_RENDER_INTERVAL_MS: Karoo drops updateView calls < ~900 ms apart.
                // A change inside the window stays pending (lastUi untouched) and renders once the window opens.
                val now = SystemClock.elapsedRealtime()
                val windowOpen = lastRenderAt?.let { now - it >= MIN_RENDER_INTERVAL_MS } ?: true
                if (ui != lastUi && windowOpen) {
                    val views = glance.compose(context, size) { Content(ui, interactive = !config.preview, size) }
                    emitter.updateView(views.remoteViews)
                    lastUi = ui
                    lastRenderAt = now
                }
                delay(POLL_MS)
            }
        }
        emitter.setCancellable { job.cancel() }
    }

    private companion object {
        /** How often the view loop checks the light's state. */
        const val POLL_MS = 100L

        /** Minimum gap between updateView calls (Karoo's ViewEmitter drops updates < ~900 ms apart). */
        const val MIN_RENDER_INTERVAL_MS = 950L

        val PREVIEW_STATE = LightState(connected = true, mode = 1, batteryPercent = 78, remainingMinutes = 200)
    }
}
