package com.tiagodias.igpsportkaroo.ui

import android.content.Context
import androidx.glance.GlanceId
import androidx.glance.action.ActionParameters
import androidx.glance.appwidget.action.ActionCallback
import com.tiagodias.igpsportkaroo.light.LightHub
import timber.log.Timber

/** Tap on the field's "Searching for light…" footer: skip the backoff and search for the light right away. */
class ReconnectAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val session = LightHub.session
        Timber.i("Field tap: reconnect now (session=%b)", session != null)
        session?.reconnectNow()
    }
}
