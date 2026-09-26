package com.tiagodias.igpsportkaroo.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceModifier
import androidx.glance.action.clickable
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Row
import androidx.glance.layout.RowScope
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxHeight
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.padding
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextAlign
import androidx.glance.text.TextStyle

private val GAP = 1.dp

/** Share of the row taken by the battery / reconnect cell. */
private const val STATUS_WIDTH = 0.18f

/**
 * ┌────────┬────────┬──────────┬─────┬──────┐
 * │ SOL HI │ FLS LO │ AUTO HI  │ OFF │ 100% │   tap = as on the regular field; tap ↻ (while searching) = reconnect now
 * └────────┴────────┴──────────┴─────┴──────┘
 * Text scales with the slot height so the row fits a normal 1-row slot; a button drops its detail
 * ("SOL HI" becomes "SOL") when it would not fit, e.g. at half width.
 */
@Composable
fun CompactLightField(ui: FieldUi, interactive: Boolean, size: DpSize) {
    val fontSp = (size.height.value * 0.32f).coerceIn(11f, 18f)
    val statusWidth = size.width * STATUS_WIDTH
    val buttonWidthDp = (size.width - statusWidth).value / ui.buttons.size.coerceAtLeast(1)
    // Bold capitals and digits are about 0.62 em wide.
    val maxChars = (buttonWidthDp / (fontSp * 0.62f)).toInt()
    Row(modifier = GlanceModifier.fillMaxSize().background(Color.Black).padding(GAP)) {
        ui.buttons.forEachIndexed { index, button ->
            if (index > 0) Spacer(GlanceModifier.width(GAP))
            CompactButton(button, enabled = interactive && ui.connected, fontSp, maxChars)
        }
        Spacer(GlanceModifier.width(GAP))
        var status = GlanceModifier.width(statusWidth).fillMaxHeight()
            .background(if (ui.connected) IDLE else SEARCHING)
        // Never clickable in preview mode (karoo-ext issue #48).
        if (interactive && ui.reconnectable) status = status.clickable(actionRunCallback<ReconnectAction>())
        Box(modifier = status, contentAlignment = Alignment.Center) {
            Text(
                ui.statusShort,
                style = TextStyle(color = WHITE, fontSize = (fontSp * 0.85f).sp, textAlign = TextAlign.Center),
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun RowScope.CompactButton(button: FieldUi.Button, enabled: Boolean, fontSp: Float, maxChars: Int) {
    var modifier = GlanceModifier.defaultWeight().fillMaxHeight().background(buttonBackground(button))
    // No click handler in preview mode, or it would hijack the Profiles editor (karoo-ext issue #48).
    if (enabled && button.available) modifier = modifier.clickable(tapAction(button))
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Text(
            text = button.compactText(maxChars),
            style = TextStyle(
                color = if (button.available) WHITE else DIM,
                fontSize = fontSp.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
            ),
            maxLines = 1,
        )
    }
}
