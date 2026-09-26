package com.tiagodias.igpsportkaroo.ui

import android.content.Context
import androidx.glance.GlanceId
import androidx.glance.action.ActionParameters
import androidx.glance.appwidget.action.ActionCallback
import com.tiagodias.igpsportkaroo.Settings
import com.tiagodias.igpsportkaroo.light.LightHub
import timber.log.Timber

/** Tap on one of the field's mode buttons (runs in our process via Glance's broadcast receiver). */
class SelectSlotAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val slot = parameters[SLOT] ?: return
        val mode = Settings(context).slotModes().getOrNull(slot) ?: return
        val sent = LightHub.session?.selectMode(mode) ?: false
        Timber.i("Field tap: slot %d -> mode %d (sent=%b)", slot, mode, sent)
    }

    companion object {
        val SLOT = ActionParameters.Key<Int>("slot")
    }
}
