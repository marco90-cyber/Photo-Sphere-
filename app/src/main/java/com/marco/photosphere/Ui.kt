package com.marco.photosphere

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.os.Build
import android.widget.TextView
import androidx.core.content.ContextCompat

fun Context.dp(v: Int): Int = (v * resources.displayMetrics.density + 0.5f).toInt()

fun Context.pill(text: String, bg: Int = 0x99000000.toInt(), sizeSp: Float = 15f): TextView {
    return TextView(this).apply {
        this.text = text
        setTextColor(Color.WHITE)
        textSize = sizeSp
        gravity = Gravity.CENTER
        setPadding(dp(18), dp(10), dp(18), dp(10))
        background = GradientDrawable().apply {
            cornerRadius = dp(26).toFloat()
            setColor(bg)
        }
        isClickable = true
    }
}

fun TextView.setPillColor(color: Int) {
    (background as? GradientDrawable)?.setColor(color)
}

/** Colori "Material You": usano i colori dinamici del telefono (Android 12+), altrimenti una tavolozza blu. */
object Md {
    private fun dyn(c: Context, res: Int, fallback: Long): Int =
        if (Build.VERSION.SDK_INT >= 31) ContextCompat.getColor(c, res) else fallback.toInt()

    fun bg(c: Context) = dyn(c, if (Build.VERSION.SDK_INT >= 31) android.R.color.system_neutral1_900 else 0, 0xFF111318)
    fun card(c: Context) = dyn(c, if (Build.VERSION.SDK_INT >= 31) android.R.color.system_neutral2_800 else 0, 0xFF1D2024)
    fun text(c: Context) = dyn(c, if (Build.VERSION.SDK_INT >= 31) android.R.color.system_neutral1_100 else 0, 0xFFE2E2E9)
    fun sub(c: Context) = dyn(c, if (Build.VERSION.SDK_INT >= 31) android.R.color.system_neutral2_300 else 0, 0xFFC4C6D0)
    fun primary(c: Context) = dyn(c, if (Build.VERSION.SDK_INT >= 31) android.R.color.system_accent1_200 else 0, 0xFFA8C7FA)
    fun onPrimary(c: Context) = dyn(c, if (Build.VERSION.SDK_INT >= 31) android.R.color.system_accent1_800 else 0, 0xFF062E6F)
    fun container(c: Context) = dyn(c, if (Build.VERSION.SDK_INT >= 31) android.R.color.system_accent2_700 else 0, 0xFF3B4858)
    fun onContainer(c: Context) = dyn(c, if (Build.VERSION.SDK_INT >= 31) android.R.color.system_accent2_100 else 0, 0xFFD7E3F8)
    const val OK = 0xFF7FD99A.toInt()
    const val BAD = 0xFFF2B8B5.toInt()
}

fun Context.roundBg(color: Int, radiusDp: Int): GradientDrawable =
    GradientDrawable().apply { cornerRadius = dp(radiusDp).toFloat(); setColor(color) }

/** Pulsante Material You: pieno (primario) o tonale. */
fun Context.mdButton(text: String, filled: Boolean = true): TextView = TextView(this).apply {
    this.text = text
    textSize = 15f
    gravity = Gravity.CENTER
    setTypeface(typeface, android.graphics.Typeface.BOLD)
    setTextColor(if (filled) Md.onPrimary(context) else Md.onContainer(context))
    setPadding(dp(22), dp(14), dp(22), dp(14))
    background = roundBg(if (filled) Md.primary(context) else Md.container(context), 28)
    isClickable = true
}
