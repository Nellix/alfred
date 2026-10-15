package ai.alfred.app.ui

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.View
import android.view.ViewGroup
import android.widget.TextView

object Palette {
    val Night = Color.rgb(17, 16, 34)
    val Surface = Color.rgb(31, 29, 56)
    val SurfaceBright = Color.rgb(45, 42, 78)
    val Mint = Color.rgb(110, 231, 216)
    val Coral = Color.rgb(255, 126, 139)
    val Violet = Color.rgb(151, 128, 255)
    val Ink = Color.rgb(244, 243, 255)
    val Muted = Color.rgb(184, 181, 211)
}

fun Context.dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

fun rounded(color: Int, radiusDp: Int, context: Context, stroke: Int? = null): GradientDrawable =
    GradientDrawable().apply {
        setColor(color)
        cornerRadius = context.dp(radiusDp).toFloat()
        if (stroke != null) setStroke(context.dp(1), stroke)
    }

fun Context.label(
    text: String,
    size: Float = 15f,
    color: Int = Palette.Ink,
    bold: Boolean = false,
): TextView = TextView(this).apply {
    this.text = text
    textSize = size
    setTextColor(color)
    if (bold) setTypeface(typeface, Typeface.BOLD)
    includeFontPadding = false
}

fun View.pad(all: Int) = setPadding(context.dp(all), context.dp(all), context.dp(all), context.dp(all))

fun View.margins(
    width: Int = ViewGroup.LayoutParams.MATCH_PARENT,
    height: Int = ViewGroup.LayoutParams.WRAP_CONTENT,
    left: Int = 0,
    top: Int = 0,
    right: Int = 0,
    bottom: Int = 0,
): ViewGroup.MarginLayoutParams = ViewGroup.MarginLayoutParams(width, height).also {
    it.setMargins(context.dp(left), context.dp(top), context.dp(right), context.dp(bottom))
}
