package com.tiagodias.igpsportkaroo.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.DpSize

/** The two-part field (mode buttons over a battery/run-time footer): "Light controls", or "(slim)" with [FieldStyle.SLIM]. */
class LightFieldDataType(
    extension: String,
    typeId: String = TYPE_ID,
    private val style: FieldStyle = FieldStyle.REGULAR,
) : GlanceFieldDataType(extension, typeId) {

    @Composable
    override fun Content(ui: FieldUi, interactive: Boolean, size: DpSize) {
        LightField(ui, interactive, style)
    }

    companion object {
        const val TYPE_ID = "light-control"
        const val SLIM_TYPE_ID = "light-control-slim"

        fun slim(extension: String) = LightFieldDataType(extension, SLIM_TYPE_ID, FieldStyle.SLIM)
    }
}
