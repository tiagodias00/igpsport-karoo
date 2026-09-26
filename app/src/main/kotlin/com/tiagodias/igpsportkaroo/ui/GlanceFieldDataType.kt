package com.tiagodias.igpsportkaroo.ui

import android.content.Context
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

/** The view loop shared by the ride-field variants: polls [LightHub.session] each second and renders [Content]. */
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
            while (isActive) {
                val state = if (config.preview) PREVIEW_STATE else LightHub.session?.state?.value ?: LightState()
                val ui = FieldUi.from(state, settings.slotModes())
                // updateView is rate-limited to ~1 Hz; only re-render when something changed.
                if (ui != lastUi) {
                    val views = glance.compose(context, size) { Content(ui, interactive = !config.preview, size) }
                    emitter.updateView(views.remoteViews)
                    lastUi = ui
                }
                delay(1000)
            }
        }
        emitter.setCancellable { job.cancel() }
    }

    private companion object {
        val PREVIEW_STATE = LightState(connected = true, mode = 1, batteryPercent = 78, remainingMinutes = 200)
    }
}
