package com.tiagodias.igpsportkaroo.ui

import android.content.Context
import android.os.SystemClock
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.glance.appwidget.ExperimentalGlanceRemoteViewsApi
import androidx.glance.appwidget.GlanceRemoteViews
import com.tiagodias.igpsportkaroo.BuildConfig
import com.tiagodias.igpsportkaroo.light.LightHub
import com.tiagodias.igpsportkaroo.protocol.LightState
import com.tiagodias.igpsportkaroo.protocol.LightUpdate
import io.hammerhead.karooext.extension.DataTypeImpl
import io.hammerhead.karooext.internal.ViewEmitter
import io.hammerhead.karooext.models.UpdateGraphicConfig
import io.hammerhead.karooext.models.ViewConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * The view loop shared by the ride-field variants: polls [LightHub.session] every [POLL_MS] and renders [Content]
 * when [FieldRenderGate] says so (on change, plus a periodic refresh in case Karoo dropped an update).
 */
@OptIn(ExperimentalGlanceRemoteViewsApi::class)
abstract class GlanceFieldDataType(extension: String, typeId: String) : DataTypeImpl(extension, typeId) {
    private val glance = GlanceRemoteViews()

    /** [size] is the slot's size in dp; [interactive] is false in preview mode (no click handlers then). */
    @Composable
    protected abstract fun Content(ui: FieldUi, interactive: Boolean, size: DpSize)

    override fun startView(context: Context, config: ViewConfig, emitter: ViewEmitter) {
        emitter.onNext(UpdateGraphicConfig(showHeader = false))
        val density = context.resources.displayMetrics.density.coerceAtLeast(1f)
        val size = DpSize((config.viewSize.first / density).dp, (config.viewSize.second / density).dp)
        val job = CoroutineScope(Dispatchers.IO).launch {
            // One failed render (compose or the Binder call) must not end the loop: it is retried, and logged at most every 30 s.
            val gate = FieldRenderGate<FieldUi>(
                clock = SystemClock::elapsedRealtime,
                onError = { e -> Timber.w(e, "field %s render failed", typeId) },
            )
            while (isActive) {
                val state = if (config.preview) PREVIEW_STATE else LightHub.session?.state?.value ?: LightState()
                val ui = FieldUi.from(state)
                // Poll fast so a tap shows on the next open render window; the gate keeps renders >= 950 ms apart.
                gate.offer(ui) {
                    val views = glance.compose(context, size) { Content(it, interactive = !config.preview, size) }
                    emitter.updateView(views.remoteViews)
                    if (BuildConfig.DEBUG) Timber.d("field %s render: %s", typeId, it.headline)
                }
                delay(POLL_MS)
            }
        }
        emitter.setCancellable { job.cancel() }
    }

    private companion object {
        /** How often the view loop checks the light's state. */
        const val POLL_MS = 100L

        /** HIGH on a VS1200S (MID, HIGH, FLASH HI, FLASH LO), auto light off. */
        val PREVIEW_STATE = LightState(
            connected = true, batteryPercent = 78, remainingMinutes = 200,
            declaredModes = linkedMapOf(2 to true, 1 to true, 4 to true, 5 to true),
        ).apply(LightUpdate(mode = 1))
    }
}
