package com.tiagodias.igpsportkaroo.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceModifier
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

/** Sizes of the two-part field; [REGULAR] is the original "Light controls" look, [SLIM] the "(slim)" variant. */
data class FieldStyle(
    val buttonTextSp: Float,
    val detailTextSp: Float,
    val footerTextSp: Float,
    val footerHeightDp: Float,
    val gapDp: Float,
) {
    companion object {
        val REGULAR = FieldStyle(buttonTextSp = 18f, detailTextSp = 13f, footerTextSp = 16f, footerHeightDp = 30f, gapDp = 2f)
        val SLIM = FieldStyle(buttonTextSp = 14f, detailTextSp = 11f, footerTextSp = 12f, footerHeightDp = 20f, gapDp = 1f)
    }
}

/**
 * ┌───────┬───────┬───────┬───────┐
 * │ SOLID │ FLASH │ AUTO  │  OFF  │   tap SOLID / FLASH again = next level; active = green
 * │ HIGH  │ FL LO │  95%  │       │
 * ├───────┴───────┴───────┴───────┤
 * │     78%  ·  3h 20m left       │   tap while searching = reconnect now
 * └───────────────────────────────┘
 */
@Composable
fun LightField(ui: FieldUi, interactive: Boolean, style: FieldStyle = FieldStyle.REGULAR) {
    val gap = style.gapDp.dp
    Column(modifier = GlanceModifier.fillMaxSize().background(Color.Black).padding(gap)) {
        Row(modifier = GlanceModifier.fillMaxWidth().defaultWeight()) {
            ui.buttons.forEachIndexed { index, button ->
                if (index > 0) Spacer(GlanceModifier.width(gap))
                FieldButton(button, enabled = interactive && ui.connected, style)
            }
        }
        Spacer(GlanceModifier.height(gap))
        var footer = GlanceModifier.fillMaxWidth().height(style.footerHeightDp.dp)
            .background(if (ui.connected) IDLE else SEARCHING)
        // Never clickable in preview mode, same as the buttons (karoo-ext issue #48).
        if (interactive && ui.reconnectable) footer = footer.clickable(actionRunCallback<ReconnectAction>())
        Box(modifier = footer, contentAlignment = Alignment.Center) {
            Text(ui.footer, style = TextStyle(color = WHITE, fontSize = style.footerTextSp.sp, textAlign = TextAlign.Center))
        }
    }
}

@Composable
private fun RowScope.FieldButton(button: FieldUi.Button, enabled: Boolean, style: FieldStyle) {
    var modifier = GlanceModifier.defaultWeight().fillMaxHeight().background(buttonBackground(button))
    // No click handler in preview mode, or it would hijack the Profiles editor (karoo-ext issue #48).
    if (enabled && button.available) modifier = modifier.clickable(tapAction(button))
    val color = if (button.available) WHITE else DIM
    Column(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = button.label,
            style = TextStyle(color = color, fontSize = style.buttonTextSp.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center),
            maxLines = 1,
        )
        if (button.detail.isNotEmpty()) {
            Text(
                text = button.detail,
                style = TextStyle(color = color, fontSize = style.detailTextSp.sp, textAlign = TextAlign.Center),
                maxLines = 1,
            )
        }
    }
}
