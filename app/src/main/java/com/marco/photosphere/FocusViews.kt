package com.marco.photosphere

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.MotionEvent
import android.view.View
import kotlin.math.cos
import kotlin.math.sin

/** Anello che appare dove tocchi l'immagine: bianco = messa a fuoco in corso, verde = ok, rosso = non riuscita. */
class FocusRingView(ctx: Context) : View(ctx) {
    private var cx = 0f
    private var cy = 0f
    private var ok: Boolean? = null
    private var scale = 1f
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = ctx.dp(2).toFloat()
    }
    private val radius = ctx.dp(34).toFloat()
    private val hide = Runnable { animate().alpha(0f).setDuration(350).start() }

    init { alpha = 0f }

    fun show(x: Float, y: Float) {
        cx = x; cy = y; ok = null
        removeCallbacks(hide)
        animate().cancel()
        alpha = 1f
        ValueAnimator.ofFloat(1.45f, 1f).apply {
            duration = 220
            addUpdateListener { scale = it.animatedValue as Float; invalidate() }
            start()
        }
        postDelayed(hide, 2500)
    }

    fun result(success: Boolean) {
        ok = success
        invalidate()
        removeCallbacks(hide)
        postDelayed(hide, 900)
    }

    override fun onDraw(c: Canvas) {
        paint.color = when (ok) {
            null -> Color.WHITE
            true -> 0xFF34C759.toInt()
            false -> 0xFFFF453A.toInt()
        }
        c.drawCircle(cx, cy, radius * scale, paint)
        c.drawCircle(cx, cy, context.dp(3).toFloat(), paint.apply { style = Paint.Style.FILL })
        paint.style = Paint.Style.STROKE
    }
}

/** Cursore verticale della luminosità (sole): in alto più chiaro, in basso più scuro. */
class EvSliderView(ctx: Context) : View(ctx) {
    var progress = 0.5f
        set(v) { field = v.coerceIn(0f, 1f); invalidate() }
    var onChange: ((Float) -> Unit)? = null
    var onTouching: (() -> Unit)? = null

    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xAAFFFFFF.toInt()
        strokeWidth = ctx.dp(2).toFloat()
        strokeCap = Paint.Cap.ROUND
    }
    private val sun = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFD60A.toInt()
        strokeWidth = ctx.dp(2).toFloat()
        strokeCap = Paint.Cap.ROUND
    }
    private val pad = ctx.dp(16).toFloat()

    override fun onDraw(c: Canvas) {
        val cx = width / 2f
        val top = pad
        val bot = height - pad
        c.drawLine(cx, top, cx, bot, line)
        val y = bot - progress * (bot - top)
        val r = context.dp(7).toFloat()
        sun.style = Paint.Style.FILL
        c.drawCircle(cx, y, r, sun)
        sun.style = Paint.Style.STROKE
        for (i in 0 until 8) {
            val a = i * Math.PI / 4
            val x0 = cx + (r + context.dp(3)) * cos(a).toFloat()
            val y0 = y + (r + context.dp(3)) * sin(a).toFloat()
            val x1 = cx + (r + context.dp(7)) * cos(a).toFloat()
            val y1 = y + (r + context.dp(7)) * sin(a).toFloat()
            c.drawLine(x0, y0, x1, y1, sun)
        }
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                val top = pad
                val bot = height - pad
                progress = (bot - e.y) / (bot - top)
                onChange?.invoke(progress)
                onTouching?.invoke()
            }
            MotionEvent.ACTION_UP -> performClick()
        }
        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }
}
