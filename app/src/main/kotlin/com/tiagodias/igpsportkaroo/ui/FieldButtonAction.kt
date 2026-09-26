package com.tiagodias.igpsportkaroo.ui

import android.content.Context
import androidx.glance.GlanceId
import androidx.glance.action.Action
import androidx.glance.action.ActionParameters
import androidx.glance.action.actionParametersOf
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
import com.tiagodias.igpsportkaroo.light.LightHub
import com.tiagodias.igpsportkaroo.protocol.LightModes
import timber.log.Timber

/** Tap on one of the field's SOLID / FLASH / AUTO / OFF buttons (runs in our process via Glance's broadcast receiver). */
class FieldButtonAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val kind = parameters[KIND]?.let { name -> FieldUi.Kind.entries.firstOrNull { it.name == name } } ?: return
        val session = LightHub.session
        val sent = when (kind) {
            FieldUi.Kind.SOLID -> session?.selectSolid()
            FieldUi.Kind.FLASH -> session?.selectFlash()
            FieldUi.Kind.AUTO -> session?.selectAuto()
            FieldUi.Kind.OFF -> session?.selectMode(LightModes.OFF)
        } ?: false
        Timber.i("Field tap: %s (sent=%b)", kind, sent)
    }

    companion object {
        /** The [FieldUi.Kind] name of the tapped button. */
        val KIND = ActionParameters.Key<String>("kind")
    }
}

/** The click action for [button], shared by every field variant. */
internal fun tapAction(button: FieldUi.Button): Action =
    actionRunCallback<FieldButtonAction>(actionParametersOf(FieldButtonAction.KIND to button.kind.name))
