package com.marco.photosphere

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.widget.TextView

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
