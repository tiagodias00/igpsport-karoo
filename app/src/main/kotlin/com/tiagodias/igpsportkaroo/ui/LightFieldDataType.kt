package com.tiagodias.igpsportkaroo.ui

import android.content.Context
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

@OptIn(ExperimentalGlanceRemoteViewsApi::class)
class LightFieldDataType(extension: String) : DataTypeImpl(extension, TYPE_ID) {
    private val glance = GlanceRemoteViews()

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
                    val views = glance.compose(context, size) { LightField(ui, interactive = !config.preview) }
                    emitter.updateView(views.remoteViews)
                    lastUi = ui
                }
                delay(1000)
            }
        }
        emitter.setCancellable { job.cancel() }
    }

    companion object {
        const val TYPE_ID = "light-control"
        private val PREVIEW_STATE = LightState(connected = true, mode = 1, batteryPercent = 78, remainingMinutes = 200)
    }
}
