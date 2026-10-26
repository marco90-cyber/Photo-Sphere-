package com.marco.photosphere

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.view.View
import kotlin.math.min
import kotlin.math.sqrt

/** Lunghezza focale della vista virtuale, in frazione della larghezza dello schermo. */
const val GUIDE_FOCAL = 0.45f

/**
 * Sfondo "mondo" come in Google Camera: griglia di "+" sul pavimento (l'orizzonte si muove con il
 * telefono), punti sul soffitto e le foto già scattate appoggiate sulla sfera.
 */
class SphereGuideView(ctx: Context) : View(ctx) {

    @Volatile
    var R: FloatArray = floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f)
    var tanS = 0.5f
    var tanL = 0.67f

    private class Tile(val bmp: Bitmap, val R: FloatArray)

    private val tiles = ArrayList<Tile>()

    // punti del mondo (E, N, U)
    private val gx: FloatArray
    private val gy: FloatArray
    private val gz: FloatArray
    private val gAlphaScale: FloatArray

    private val plus = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.WHITE
        strokeWidth = ctx.dp(1).toFloat().coerceAtLeast(1.5f)
        strokeCap = Paint.Cap.ROUND
    }
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.WHITE
    }
    private val border = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.WHITE
        strokeWidth = ctx.dp(2).toFloat()
    }
    private val bmpPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val mtx = Matrix()
    private val path = Path()
    private val src = FloatArray(8)
    private val dst = FloatArray(8)

    init {
        val xs = ArrayList<Float>()
        val ys = ArrayList<Float>()
        val zs = ArrayList<Float>()
        val al = ArrayList<Float>()
        // pavimento: griglia ogni 2,5 m fino a 50 m, 1,6 m sotto gli occhi
        var e = -50f
        while (e <= 50f) {
            var n = -50f
            while (n <= 50f) {
                if (kotlin.math.abs(e) > 1f || kotlin.math.abs(n) > 1f) {
                    xs.add(e); ys.add(n); zs.add(-1.6f); al.add(1f)
                }
                n += 2.5f
            }
            e += 2.5f
        }
        // soffitto: punti più radi e più tenui
        e = -40f
        while (e <= 40f) {
            var n = -40f
            while (n <= 40f) {
                xs.add(e); ys.add(n); zs.add(2.2f); al.add(0.45f)
                n += 5f
            }
            e += 5f
        }
        gx = xs.toFloatArray(); gy = ys.toFloatArray(); gz = zs.toFloatArray(); gAlphaScale = al.toFloatArray()
    }

    fun addTile(b: Bitmap, r: FloatArray) {
        synchronized(tiles) { tiles.add(Tile(b, r)) }
        invalidate()
    }

    fun clearTiles() {
        synchronized(tiles) { tiles.forEach { it.bmp.recycle() }; tiles.clear() }
        invalidate()
    }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val cx = w / 2f
        val cy = h / 2f
        val f = w * GUIDE_FOCAL
        val m = R
        val maxPlus = context.dp(8).toFloat()
        val margin = 20f

        for (i in gx.indices) {
            val e = gx[i]; val n = gy[i]; val u = gz[i]
            val depth = -(m[2] * e + m[5] * n + m[8] * u)
            if (depth < 0.3f) continue
            val dx = m[0] * e + m[3] * n + m[6] * u
            val dy = m[1] * e + m[4] * n + m[7] * u
            val sx = cx + dx / depth * f
            val sy = cy - dy / depth * f
            if (sx < -margin || sx > w + margin || sy < -margin || sy > h + margin) continue
            val dist = sqrt(e * e + n * n + u * u)
            val a = ((1f - dist / 75f) * 235f * gAlphaScale[i]).toInt()
            if (a <= 6) continue
            val s = min(maxPlus, f * 0.14f / depth)
            if (s < 2.2f) {
                dot.alpha = a
                c.drawCircle(sx, sy, 1.3f, dot)
            } else {
                plus.alpha = a
                c.drawLine(sx - s, sy, sx + s, sy, plus)
                c.drawLine(sx, sy - s, sx, sy + s, plus)
            }
        }

        // foto già scattate appoggiate sulla sfera
        synchronized(tiles) {
            for (t in tiles) drawTile(c, t, m, cx, cy, f)
        }
    }

    private fun drawTile(c: Canvas, t: Tile, m: FloatArray, cx: Float, cy: Float, f: Float) {
        val r = t.R
        val cornersX = floatArrayOf(-tanS, tanS, tanS, -tanS)
        val cornersY = floatArrayOf(tanL, tanL, -tanL, -tanL)
        for (k in 0 until 4) {
            val x = cornersX[k]
            val y = cornersY[k]
            // dispositivo (scatto) -> mondo
            val e = r[0] * x + r[1] * y - r[2]
            val n = r[3] * x + r[4] * y - r[5]
            val u = r[6] * x + r[7] * y - r[8]
            // mondo -> dispositivo (ora)
            val depth = -(m[2] * e + m[5] * n + m[8] * u)
            if (depth < 0.15f) return
            val dx = m[0] * e + m[3] * n + m[6] * u
            val dy = m[1] * e + m[4] * n + m[7] * u
            dst[k * 2] = cx + dx / depth * f
            dst[k * 2 + 1] = cy - dy / depth * f
        }
        val tw = t.bmp.width.toFloat()
        val th = t.bmp.height.toFloat()
        src[0] = 0f; src[1] = 0f
        src[2] = tw; src[3] = 0f
        src[4] = tw; src[5] = th
        src[6] = 0f; src[7] = th
        if (!mtx.setPolyToPoly(src, 0, dst, 0, 4)) return
        c.drawBitmap(t.bmp, mtx, bmpPaint)
        path.reset()
        path.moveTo(dst[0], dst[1])
        path.lineTo(dst[2], dst[3])
        path.lineTo(dst[4], dst[5])
        path.lineTo(dst[6], dst[7])
        path.close()
        border.alpha = 150
        c.drawPath(path, border)
    }
}

/** Pulsante di scatto tondo, come quello di Google Camera. */
class ShutterButton(ctx: Context) : View(ctx) {
    var capturing = false
        set(v) { field = v; invalidate() }
    var progress = 0f
        set(v) { field = v; invalidate() }

    private val arc = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = 0xFF34C759.toInt()
        strokeWidth = ctx.dp(5).toFloat()
        strokeCap = Paint.Cap.ROUND
    }
    private val oval = android.graphics.RectF()

    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.WHITE
        strokeWidth = ctx.dp(4).toFloat()
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.WHITE
    }

    override fun onDraw(c: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val r = min(width, height) / 2f - context.dp(4)
        c.drawCircle(cx, cy, r, ring)
        if (capturing && progress > 0f) {
            oval.set(cx - r, cy - r, cx + r, cy + r)
            c.drawArc(oval, -90f, 360f * progress.coerceIn(0f, 1f), false, arc)
        }
        if (!capturing) {
            c.drawCircle(cx, cy, r - context.dp(8), fill)
        } else {
            val s = r * 0.42f
            c.drawRoundRect(cx - s, cy - s, cx + s, cy + s, context.dp(5).toFloat(), context.dp(5).toFloat(), fill)
        }
    }
}
