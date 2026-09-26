package com.tiagodias.igpsportkaroo

import android.content.Context
import android.graphics.Typeface
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.LinearLayout
import android.widget.TextView

/** The small view helpers the app's pages share, so they keep the same text sizes and spacing. */
internal fun Context.dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

internal fun Context.pageText(size: Float, bold: Boolean = false) = TextView(this).apply {
    textSize = size
    if (bold) setTypeface(typeface, Typeface.BOLD)
    layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
}

internal fun Context.pageSection(title: Int) = pageText(size = 20f, bold = true).apply {
    setText(title)
    setPadding(0, dp(24), 0, dp(4))
}

/** A page's vertical column, with the app page's padding. */
internal fun Context.pageColumn() = LinearLayout(this).apply {
    orientation = LinearLayout.VERTICAL
    setPadding(dp(16), dp(16), dp(16), dp(16))
}
