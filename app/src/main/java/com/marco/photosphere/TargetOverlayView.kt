package com.marco.photosphere

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.View
import kotlin.math.PI
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.tan

/** Punto da fotografare sulla sfera: yaw relativo alla direzione di partenza, pitch (rad). */
class Target(val yaw: Float, val pitch: Float, var done: Boolean = false) {
    /** Direzione nel mondo (est, nord, su) */
    fun dir(yaw0: Float): FloatArray {
        val a = yaw + yaw0
        val cp = cos(pitch)
        return floatArrayOf(cp * sin(a), cp * cos(a), sin(pitch))
    }
}

class TargetOverlayView(ctx: Context) : View(ctx) {
    var targets: List<Target> = emptyList()
    var yaw0 = 0f
    var tanHalfLong = tan(Math.toRadians(34.0)).toFloat()
    var capturing = false
    var activeIdx = -1
    var dwell = 0f
    var showMap = true

    @Volatile
    var R: FloatArray = floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f)

    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; color = Color.WHITE; strokeWidth = ctx.dp(3).toFloat()
    }
    private val fillDone = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL; color = 0xCC34C759.toInt()
    }
    private val fillTodo = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL; color = 0x33FFFFFF
    }
    private val strokeTodo = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; color = Color.WHITE; strokeWidth = ctx.dp(2).toFloat()
    }
    private val strokeActive = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; color = 0xFFFFD60A.toInt(); strokeWidth = ctx.dp(4).toFloat()
    }
    private val arc = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; color = 0xFF34C759.toInt(); strokeWidth = ctx.dp(5).toFloat()
        strokeCap = Paint.Cap.ROUND
    }
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; color = Color.WHITE }
    private val arrow = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; color = 0xFFFFD60A.toInt() }
    private val path = Path()
    private val rect = RectF()

    override fun onDraw(c: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val cx = w / 2f
        val cy = h / 2f
        val rr = context.dp(26).toFloat()

        // mirino centrale
        c.drawCircle(cx, cy, rr, ring)
        c.drawCircle(cx, cy, context.dp(3).toFloat(), dot)
        if (dwell > 0f) {
            rect.set(cx - rr - context.dp(7), cy - rr - context.dp(7), cx + rr + context.dp(7), cy + rr + context.dp(7))
            c.drawArc(rect, -90f, 360f * dwell.coerceIn(0f, 1f), false, arc)
        }
        if (!capturing) return

        val f = w * GUIDE_FOCAL
        val m = R
        val tr = context.dp(13).toFloat()
        var activeVisible = false
        var adx = 0f
        var ady = 0f

        for ((i, t) in targets.withIndex()) {
            val d = t.dir(yaw0)
            val dx = m[0] * d[0] + m[3] * d[1] + m[6] * d[2]
            val dy = m[1] * d[0] + m[4] * d[1] + m[7] * d[2]
            val dz = m[2] * d[0] + m[5] * d[1] + m[8] * d[2]
            if (i == activeIdx) { adx = dx; ady = dy }
            if (dz < -0.05f) {
                val sx = cx + dx / (-dz) * f
                val sy = cy - dy / (-dz) * f
                if (sx > -tr && sx < w + tr && sy > -tr && sy < h + tr) {
                    if (!t.done) {
                        c.drawCircle(sx, sy, tr, fillTodo)
                        c.drawCircle(sx, sy, tr, if (i == activeIdx) strokeActive else strokeTodo)
                        c.drawCircle(sx, sy, context.dp(2).toFloat(), dot)
                    }
                    if (i == activeIdx) activeVisible = true
                }
            }
        }

        if (showMap) drawMiniMap(c, m)

        // freccia verso il prossimo punto quando è fuori dallo schermo
        if (activeIdx >= 0 && !activeVisible) {
            val ang = atan2(-ady, adx)
            val dist = min(w, h) * 0.34f
            val ax = cx + cos(ang) * dist
            val ay = cy + sin(ang) * dist
            val sz = context.dp(18).toFloat()
            path.reset()
            path.moveTo(ax + cos(ang) * sz, ay + sin(ang) * sz)
            path.lineTo(ax + cos(ang + 2.5f) * sz, ay + sin(ang + 2.5f) * sz)
            path.lineTo(ax + cos(ang - 2.5f) * sz, ay + sin(ang - 2.5f) * sz)
            path.close()
            c.drawPath(path, arrow)
        }
    }

    private val mapBg = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; color = 0x88000000.toInt() }
    private val mapFrame = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; color = 0x88FFFFFF.toInt(); strokeWidth = context.dp(1).toFloat()
    }
    private val mapTodo = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; color = Color.WHITE; strokeWidth = context.dp(1).toFloat()
    }
    private val mapDone = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; color = 0xFF34C759.toInt() }
    private val mapActive = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; color = 0xFFFFD60A.toInt(); strokeWidth = context.dp(2).toFloat()
    }
    private val mapHead = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; color = 0xFF0A84FF.toInt(); strokeWidth = context.dp(2).toFloat()
    }

    /** Mappa equirettangolare in miniatura: punti fatti (verde), da fare (vuoti), punto attivo e direzione attuale. */
    private fun drawMiniMap(c: Canvas, m: FloatArray) {
        val mw = context.dp(132).toFloat()
        val mh = mw / 2f
        val x0 = context.dp(12).toFloat()
        val y0 = context.dp(96).toFloat()
        rect.set(x0, y0, x0 + mw, y0 + mh)
        c.drawRoundRect(rect, context.dp(6).toFloat(), context.dp(6).toFloat(), mapBg)
        c.drawRoundRect(rect, context.dp(6).toFloat(), context.dp(6).toFloat(), mapFrame)
        c.drawLine(x0, y0 + mh / 2f, x0 + mw, y0 + mh / 2f, mapFrame)
        val twoPi = (2 * PI).toFloat()
        val r = context.dp(3).toFloat()
        for ((i, t) in targets.withIndex()) {
            var y = t.yaw
            while (y > PI) y -= twoPi
            while (y < -PI) y += twoPi
            val px = x0 + (y + PI.toFloat()) / twoPi * mw
            val py = y0 + (PI.toFloat() / 2f - t.pitch) / PI.toFloat() * mh
            if (t.done) c.drawCircle(px, py, r, mapDone)
            else {
                c.drawCircle(px, py, r, mapTodo)
                if (i == activeIdx) c.drawCircle(px, py, r + context.dp(2), mapActive)
            }
        }
        // direzione attuale (asse ottico = -z del dispositivo)
        val e = -m[2]; val n = -m[5]; val u = -m[8]
        var hy = atan2(e, n) - yaw0
        while (hy > PI) hy -= twoPi
        while (hy < -PI) hy += twoPi
        val hp = asin(u.coerceIn(-1f, 1f))
        val hx = x0 + (hy + PI.toFloat()) / twoPi * mw
        val hyy = y0 + (PI.toFloat() / 2f - hp) / PI.toFloat() * mh
        c.drawCircle(hx, hyy, context.dp(5).toFloat(), mapHead)
    }
}
