package com.tiagodias.igpsportkaroo.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.DpSize

/** "Light controls (compact)": one slim row of mode buttons plus a battery / reconnect cell. */
class CompactLightFieldDataType(extension: String) : GlanceFieldDataType(extension, TYPE_ID) {

    @Composable
    override fun Content(ui: FieldUi, interactive: Boolean, size: DpSize) {
        CompactLightField(ui, interactive, size)
    }

    companion object {
        const val TYPE_ID = "light-control-compact"
    }
}
