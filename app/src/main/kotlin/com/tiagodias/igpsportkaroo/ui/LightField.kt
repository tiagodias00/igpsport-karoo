package com.tiagodias.igpsportkaroo.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceModifier
import androidx.glance.action.actionParametersOf
import androidx.glance.action.clickable
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.RowScope
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxHeight
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextAlign
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider

private val ACTIVE = Color(0xFF2E7D32)
private val IDLE = Color(0xFF37474F)
private val UNAVAILABLE = Color(0xFF1C1C1C)
private val SEARCHING = Color(0xFF5D4037)
private val WHITE = ColorProvider(Color.White)
private val DIM = ColorProvider(Color(0xFF8A8A8A))

/**
 * ┌──────────┬──────────┬──────────┐
 * │  HIGH ●  │   LOW    │ FLASH HI │   tap = select that mode
 * ├──────────┴──────────┴──────────┤
 * │       78%  ·  3h 20m left      │   tap while searching = reconnect now
 * └────────────────────────────────┘
 */
@Composable
fun LightField(ui: FieldUi, interactive: Boolean) {
    Column(modifier = GlanceModifier.fillMaxSize().background(Color.Black).padding(2.dp)) {
        Row(modifier = GlanceModifier.fillMaxWidth().defaultWeight()) {
            ui.buttons.forEachIndexed { index, button ->
                if (index > 0) Spacer(GlanceModifier.width(2.dp))
                ModeButton(button, enabled = interactive && ui.connected)
            }
        }
        Spacer(GlanceModifier.height(2.dp))
        var footer = GlanceModifier.fillMaxWidth().height(30.dp).background(if (ui.connected) IDLE else SEARCHING)
        // Never clickable in preview mode, same as the buttons (karoo-ext issue #48).
        if (interactive && ui.reconnectable) footer = footer.clickable(actionRunCallback<ReconnectAction>())
        Box(modifier = footer, contentAlignment = Alignment.Center) {
            Text(ui.footer, style = TextStyle(color = WHITE, fontSize = 16.sp, textAlign = TextAlign.Center))
        }
    }
}

@Composable
private fun RowScope.ModeButton(button: FieldUi.Button, enabled: Boolean) {
    val background = when {
        !button.available -> UNAVAILABLE
        button.active -> ACTIVE
        else -> IDLE
    }
    var modifier = GlanceModifier.defaultWeight().fillMaxHeight().background(background)
    // No click handler in preview mode, or it would hijack the Profiles editor (karoo-ext issue #48).
    if (enabled && button.available) {
        modifier = modifier.clickable(
            actionRunCallback<SelectSlotAction>(actionParametersOf(SelectSlotAction.SLOT to button.slot)),
        )
    }
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Text(
            text = if (button.active) "${button.label} ●" else button.label,
            style = TextStyle(
                color = if (button.available) WHITE else DIM,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
            ),
        )
    }
}
