package com.tiagodias.igpsportkaroo.ui

import androidx.compose.ui.graphics.Color
import androidx.glance.unit.ColorProvider

/** Colours shared by all the ride-field variants. */
internal val ACTIVE = Color(0xFF2E7D32)
internal val IDLE = Color(0xFF37474F)
internal val UNAVAILABLE = Color(0xFF1C1C1C)
internal val SEARCHING = Color(0xFF5D4037)
internal val WHITE = ColorProvider(Color.White)
internal val DIM = ColorProvider(Color(0xFF8A8A8A))

internal fun buttonBackground(button: FieldUi.Button): Color = when {
    !button.available -> UNAVAILABLE
    button.active -> ACTIVE
    else -> IDLE
}
