package com.marco.photosphere

import android.graphics.Bitmap
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

class LegacyResult(
    val bmp: Bitmap,
    val fullW: Int, val fullH: Int,
    val cropW: Int, val cropH: Int,
    val left: Int, val top: Int
)

private class Img(
    val px: IntArray, val w: Int, val h: Int,
    val low: FloatArray, val lw: Int, val lh: Int, val f: Int
)

/**
 * Cucitura sferica a due bande (Burt-Adelson semplificata):
 *  - basse frequenze: fuse con pesi morbidi su tutta la sovrapposizione (niente giunture di luce)
 *  - alte frequenze (dettagli): prese quasi tutte da un solo scatto (niente doppioni)
 * In più: guadagno di esposizione per scatto, ritaglio automatico dell'area coperta
 * e riempimento dei piccoli buchi.
 */
class LegacyStitcher(
    private val outW: Int,
    private val fovLong: Double,
    private val sf: Float = 1f,
    private val gains: List<FloatArray>? = null,
    private val vig: FloatArray? = null
) {
    private val outH = outW / 2
    private val tanL = (tan(fovLong / 2) * sf).toFloat()
    private val tanS = tanL * 0.75f

    fun run(shots: List<Shot>, yaw0: Float, progress: (Float) -> Unit): LegacyResult {
        val n = outW * outH
        val lowRgb = ByteArray(n * 3)
        val highRgb = ByteArray(n * 3)
        val wtL = ShortArray(n)
        val wtH = ShortArray(n)

        val latSin = FloatArray(outH)
        val latCos = FloatArray(outH)
        for (y in 0 until outH) {
            val lat = (0.5 - (y + 0.5) / outH) * PI
            latSin[y] = sin(lat).toFloat()
            latCos[y] = cos(lat).toFloat()
        }
        val lonSin = FloatArray(outW)
        val lonCos = FloatArray(outW)
        for (x in 0 until outW) {
            val lon = ((x + 0.5) / outW - 0.5) * 2.0 * PI + yaw0
            lonSin[x] = sin(lon).toFloat()
            lonCos[x] = cos(lon).toFloat()
        }

        val threads = max(2, Runtime.getRuntime().availableProcessors())
        val pool = Executors.newFixedThreadPool(threads)
        try {
            shots.forEachIndexed { idx, shot ->
                progress(idx.toFloat() / shots.size)
                val img = load(shot.path)
                val g = gains?.getOrNull(idx) ?: floatArrayOf(1f, 1f, 1f)
                val R = shot.R
                val b = bounds(R, yaw0)
                val y0 = b[0]
                val y1 = b[1]
                val xs = b[2]
                val xe = b[3]
                val rowsPerTask = max(8, (y1 - y0 + 1) / (threads * 3) + 1)
                val tasks = ArrayList<Callable<Unit>>()
                var ys = y0
                while (ys <= y1) {
                    val from = ys
                    val to = min(y1, ys + rowsPerTask - 1)
                    tasks.add(Callable {
                        blendRows(from, to, xs, xe, img, R, g, latSin, latCos, lonSin, lonCos, lowRgb, highRgb, wtL, wtH)
                    })
                    ys = to + 1
                }
                pool.invokeAll(tasks).forEach { it.get() }
            }
        } finally {
            pool.shutdown()
        }
        progress(1f)
        return finish(lowRgb, highRgb, wtL)
    }

    private fun blendRows(
        yFrom: Int, yTo: Int, xs: Int, xe: Int, img: Img, R: FloatArray, gain: FloatArray,
        latSin: FloatArray, latCos: FloatArray, lonSin: FloatArray, lonCos: FloatArray,
        lowRgb: ByteArray, highRgb: ByteArray, wtL: ShortArray, wtH: ShortArray
    ) {
        val px = img.px
        val w = img.w
        val h = img.h
        val low = img.low
        val lw = img.lw
        val lh = img.lh
        val f = img.f.toFloat()
        val ga = gain[0]; val gga = gain[1]; val gba = gain[2]
        val va = vig?.get(0) ?: 0f
        val vb = vig?.get(1) ?: 0f
        val useVig = va != 0f || vb != 0f
        val r0 = R[0]; val r1 = R[1]; val r2 = R[2]
        val r3 = R[3]; val r4 = R[4]; val r5 = R[5]
        val r6 = R[6]; val r7 = R[7]; val r8 = R[8]
        for (y in yFrom..yTo) {
            val lc = latCos[y]
            val up = latSin[y]
            for (xi in xs..xe) {
                val x = ((xi % outW) + outW) % outW
                val e = lc * lonSin[x]
                val n = lc * lonCos[x]
                val dz = r2 * e + r5 * n + r8 * up
                if (dz > -0.05f) continue
                val dx = r0 * e + r3 * n + r6 * up
                val dy = r1 * e + r4 * n + r7 * up
                val inv = -1f / dz
                val u = dx * inv / tanS
                val v = dy * inv / tanL
                if (u <= -1f || u >= 1f || v <= -1f || v >= 1f) continue

                var gr = ga; var gg = gga; var gb = gba
                if (useVig) {
                    val q = (u * u + v * v) * 0.5f
                    val vf = kotlin.math.exp(-(va * q + vb * q * q))
                    gr *= vf; gg *= vf; gb *= vf
                }
                val sx = (u + 1f) * 0.5f * (w - 1)
                val sy = (1f - v) * 0.5f * (h - 1)
                val ix = sx.toInt()
                val iy = sy.toInt()
                val fx = sx - ix
                val fy = sy - iy
                val ix1 = min(ix + 1, w - 1)
                val iy1 = min(iy + 1, h - 1)
                val a = px[iy * w + ix]
                val b = px[iy * w + ix1]
                val c = px[iy1 * w + ix]
                val d = px[iy1 * w + ix1]
                val w00 = (1f - fx) * (1f - fy)
                val w10 = fx * (1f - fy)
                val w01 = (1f - fx) * fy
                val w11 = fx * fy
                val or = (((a shr 16) and 255) * w00 + ((b shr 16) and 255) * w10 +
                    ((c shr 16) and 255) * w01 + ((d shr 16) and 255) * w11) * gr
                val og = (((a shr 8) and 255) * w00 + ((b shr 8) and 255) * w10 +
                    ((c shr 8) and 255) * w01 + ((d shr 8) and 255) * w11) * gg
                val ob = ((a and 255) * w00 + (b and 255) * w10 +
                    (c and 255) * w01 + (d and 255) * w11) * gb

                // banda bassa: interpolazione bilineare della versione ridotta
                var lx = (sx + 0.5f) / f - 0.5f
                var ly = (sy + 0.5f) / f - 0.5f
                if (lx < 0f) lx = 0f else if (lx > lw - 1f) lx = lw - 1f
                if (ly < 0f) ly = 0f else if (ly > lh - 1f) ly = lh - 1f
                val lx0 = lx.toInt()
                val ly0 = ly.toInt()
                val lx1 = min(lx0 + 1, lw - 1)
                val ly1 = min(ly0 + 1, lh - 1)
                val lfx = lx - lx0
                val lfy = ly - ly0
                val q00 = (ly0 * lw + lx0) * 3
                val q10 = (ly0 * lw + lx1) * 3
                val q01 = (ly1 * lw + lx0) * 3
                val q11 = (ly1 * lw + lx1) * 3
                val m00 = (1f - lfx) * (1f - lfy)
                val m10 = lfx * (1f - lfy)
                val m01 = (1f - lfx) * lfy
                val m11 = lfx * lfy
                val lr = (low[q00] * m00 + low[q10] * m10 + low[q01] * m01 + low[q11] * m11) * gr
                val lg = (low[q00 + 1] * m00 + low[q10 + 1] * m10 + low[q01 + 1] * m01 + low[q11 + 1] * m11) * gg
                val lb = (low[q00 + 2] * m00 + low[q10 + 2] * m10 + low[q01 + 2] * m01 + low[q11 + 2] * m11) * gb

                val lrI = clamp255(lr)
                val lgI = clamp255(lg)
                val lbI = clamp255(lb)
                val hrI = clampH(or - lr)
                val hgI = clampH(og - lg)
                val hbI = clampH(ob - lb)

                val ww = (1f - abs(u)) * (1f - abs(v))
                val ww2 = ww * ww
                val nwL = (ww * 255f).toInt() + 1
                val nwH = (ww2 * ww2 * ww2 * 255f).toInt() + 1
                val idx = y * outW + x
                val b3 = idx * 3

                val oldL = wtL[idx].toInt()
                if (oldL == 0) {
                    lowRgb[b3] = lrI.toByte(); lowRgb[b3 + 1] = lgI.toByte(); lowRgb[b3 + 2] = lbI.toByte()
                    wtL[idx] = nwL.toShort()
                } else {
                    val tot = oldL + nwL
                    lowRgb[b3] = (((lowRgb[b3].toInt() and 255) * oldL + lrI * nwL) / tot).toByte()
                    lowRgb[b3 + 1] = (((lowRgb[b3 + 1].toInt() and 255) * oldL + lgI * nwL) / tot).toByte()
                    lowRgb[b3 + 2] = (((lowRgb[b3 + 2].toInt() and 255) * oldL + lbI * nwL) / tot).toByte()
                    wtL[idx] = min(tot, 30000).toShort()
                }
                val oldH = wtH[idx].toInt()
                if (oldH == 0) {
                    highRgb[b3] = hrI.toByte(); highRgb[b3 + 1] = hgI.toByte(); highRgb[b3 + 2] = hbI.toByte()
                    wtH[idx] = nwH.toShort()
                } else {
                    val tot = oldH + nwH
                    highRgb[b3] = ((highRgb[b3].toInt() * oldH + hrI * nwH) / tot).toByte()
                    highRgb[b3 + 1] = ((highRgb[b3 + 1].toInt() * oldH + hgI * nwH) / tot).toByte()
                    highRgb[b3 + 2] = ((highRgb[b3 + 2].toInt() * oldH + hbI * nwH) / tot).toByte()
                    wtH[idx] = min(tot, 30000).toShort()
                }
            }
        }
    }

    private fun clamp255(v: Float): Int {
        val i = (v + 0.5f).toInt()
        return if (i < 0) 0 else if (i > 255) 255 else i
    }

    private fun clampH(v: Float): Int {
        val i = if (v >= 0f) (v + 0.5f).toInt() else -((-v + 0.5f).toInt())
        return if (i < -128) -128 else if (i > 127) 127 else i
    }

    /** Rettangolo (righe e colonne) dell'equirettangolare toccato da uno scatto: [y0, y1, xs, xe] */
    private fun bounds(R: FloatArray, yaw0: Float): IntArray {
        var latMin = PI / 2
        var latMax = -PI / 2
        val lonC = atan2(-R[2].toDouble(), -R[5].toDouble())
        var dMin = 0.0
        var dMax = 0.0
        val steps = 24
        for (side in 0 until 4) {
            for (k in 0..steps) {
                val a = -1f + 2f * k / steps
                val u: Float
                val v: Float
                when (side) {
                    0 -> { u = a; v = 1f }
                    1 -> { u = a; v = -1f }
                    2 -> { u = 1f; v = a }
                    else -> { u = -1f; v = a }
                }
                val cx = u * tanS
                val cy = v * tanL
                val cz = -1f
                val e = (R[0] * cx + R[1] * cy + R[2] * cz).toDouble()
                val n = (R[3] * cx + R[4] * cy + R[5] * cz).toDouble()
                val up = (R[6] * cx + R[7] * cy + R[8] * cz).toDouble()
                val len = sqrt(e * e + n * n + up * up)
                val lat = asin(up / len)
                var d = atan2(e, n) - lonC
                while (d > PI) d -= 2 * PI
                while (d < -PI) d += 2 * PI
                if (lat < latMin) latMin = lat
                if (lat > latMax) latMax = lat
                if (d < dMin) dMin = d
                if (d > dMax) dMax = d
            }
        }
        var full = (dMax - dMin) > PI * 1.5
        if (containsPole(R, 1f)) { latMax = PI / 2; full = true }
        if (containsPole(R, -1f)) { latMin = -PI / 2; full = true }

        val y0 = max(0, floor((0.5 - latMax / PI) * outH).toInt() - 2)
        val y1 = min(outH - 1, ceil((0.5 - latMin / PI) * outH).toInt() + 2)
        var xs: Int
        var xe: Int
        if (full) {
            xs = 0
            xe = outW - 1
        } else {
            val left = lonC + dMin - yaw0
            val right = lonC + dMax - yaw0
            xs = floor((left / (2 * PI) + 0.5) * outW).toInt() - 2
            xe = ceil((right / (2 * PI) + 0.5) * outW).toInt() + 2
            if (xe - xs + 1 >= outW) {
                xs = 0
                xe = outW - 1
            }
        }
        return intArrayOf(y0, y1, xs, xe)
    }

    private fun containsPole(R: FloatArray, sign: Float): Boolean {
        val dx = sign * R[6]
        val dy = sign * R[7]
        val dz = sign * R[8]
        if (dz >= -0.01f) return false
        val u = dx / (-dz) / tanS
        val v = dy / (-dz) / tanL
        return abs(u) < 1f && abs(v) < 1f
    }

    private fun load(path: String): Img {
        val bmp = ShotIO.decodeUpright(path, 3300)
        val w = bmp.width
        val h = bmp.height
        val px = IntArray(w * h)
        bmp.getPixels(px, 0, w, 0, 0, w, h)
        bmp.recycle()

        // la banda bassa taglia a circa 8 pixel dell'immagine finale
        val srcPerOut = (h / fovLong) / (outW / (2 * PI))
        val f = (8 * srcPerOut).roundToInt().coerceIn(4, 64)
        val lw = max(2, w / f)
        val lh = max(2, h / f)
        val low = FloatArray(lw * lh * 3)
        val cnt = IntArray(lw * lh)
        for (y in 0 until h) {
            val ly = min(lh - 1, y / f)
            for (x in 0 until w) {
                val lx = min(lw - 1, x / f)
                val c = px[y * w + x]
                val o = (ly * lw + lx) * 3
                low[o] += ((c shr 16) and 255).toFloat()
                low[o + 1] += ((c shr 8) and 255).toFloat()
                low[o + 2] += (c and 255).toFloat()
                cnt[ly * lw + lx]++
            }
        }
        for (i in 0 until lw * lh) {
            val k = max(1, cnt[i]).toFloat()
            low[i * 3] /= k; low[i * 3 + 1] /= k; low[i * 3 + 2] /= k
        }
        return Img(px, w, h, low, lw, lh, f)
    }

    // ------------------------------------------------------------------ finitura

    private fun finish(low: ByteArray, high: ByteArray, wt: ShortArray): LegacyResult {
        val rowHas = BooleanArray(outH)
        val colHas = BooleanArray(outW)
        for (y in 0 until outH) {
            val base = y * outW
            for (x in 0 until outW) {
                if (wt[base + x].toInt() != 0) {
                    rowHas[y] = true
                    colHas[x] = true
                }
            }
        }
        val top = rowHas.indexOfFirst { it }
        val bottom = rowHas.indexOfLast { it }
        if (top < 0) throw IllegalStateException("Nessuno scatto utilizzabile")

        // colonne: cerca il tratto vuoto più lungo (circolare) e ritaglia
        var bestLen = 0
        var bestStart = 0
        var run = 0
        for (k in 0 until 2 * outW) {
            if (!colHas[k % outW]) {
                run++
                if (run > outW - 1) break
                if (run > bestLen) {
                    bestLen = run
                    bestStart = (k - run + 1) % outW
                }
            } else {
                run = 0
            }
        }
        val start: Int
        val cropW: Int
        if (bestLen >= outW / 50) {
            start = (bestStart + bestLen) % outW
            cropW = outW - bestLen
        } else {
            start = 0
            cropW = outW
        }
        val cropH = bottom - top + 1

        val C = 8
        val gw = (cropW + C - 1) / C
        val gh = (cropH + C - 1) / C
        val sum = FloatArray(gw * gh * 3)
        val cnt = IntArray(gw * gh)
        var holes = 0L

        val bmp = Bitmap.createBitmap(cropW, cropH, Bitmap.Config.ARGB_8888)
        val rows = 32
        val buf = IntArray(cropW * rows)
        var y0 = 0
        while (y0 < cropH) {
            val nr = min(rows, cropH - y0)
            for (r in 0 until nr) {
                val yy = top + y0 + r
                val cy = (y0 + r) / C
                for (x in 0 until cropW) {
                    val idx = yy * outW + (start + x) % outW
                    if (wt[idx].toInt() == 0) {
                        buf[r * cropW + x] = 0
                        holes++
                    } else {
                        val b3 = idx * 3
                        val rr = clampPix((low[b3].toInt() and 255) + high[b3])
                        val gg = clampPix((low[b3 + 1].toInt() and 255) + high[b3 + 1])
                        val bb = clampPix((low[b3 + 2].toInt() and 255) + high[b3 + 2])
                        buf[r * cropW + x] = (0xFF shl 24) or (rr shl 16) or (gg shl 8) or bb
                        val ci = cy * gw + x / C
                        sum[ci * 3] += rr.toFloat(); sum[ci * 3 + 1] += gg.toFloat(); sum[ci * 3 + 2] += bb.toFloat()
                        cnt[ci]++
                    }
                }
            }
            bmp.setPixels(buf, 0, cropW, 0, y0, cropW, nr)
            y0 += nr
        }

        if (holes > 0) fillHoles(bmp, sum, cnt, gw, gh, C, cropW, cropH)
        return LegacyResult(bmp, outW, outH, cropW, cropH, 0, top)
    }

    private fun clampPix(v: Int) = if (v < 0) 0 else if (v > 255) 255 else v

    /** Riempie i buchi con un'estensione morbida dei colori vicini (pull-push multi-risoluzione). */
    private var near: BooleanArray = BooleanArray(0)

    private fun fillHoles(bmp: Bitmap, sum: FloatArray, cnt: IntArray, gw: Int, gh: Int, C: Int, w: Int, h: Int) {
        val cells = gw * gh
        val col0 = FloatArray(cells * 3)
        val wt0 = FloatArray(cells)
        val full = (C * C).toFloat()
        for (i in 0 until cells) {
            if (cnt[i] > 0) {
                col0[i * 3] = sum[i * 3] / cnt[i]
                col0[i * 3 + 1] = sum[i * 3 + 1] / cnt[i]
                col0[i * 3 + 2] = sum[i * 3 + 2] / cnt[i]
                wt0[i] = min(1f, cnt[i] / full)
            }
        }
        // pull: piramide con peso
        val cols = ArrayList<FloatArray>()
        val wts = ArrayList<FloatArray>()
        val dw = ArrayList<Int>()
        val dh = ArrayList<Int>()
        cols.add(col0); wts.add(wt0); dw.add(gw); dh.add(gh)
        while (dw.last() > 1 || dh.last() > 1) {
            val pw = dw.last(); val ph = dh.last()
            val nw = (pw + 1) / 2; val nh = (ph + 1) / 2
            val pc = cols.last(); val pwt = wts.last()
            val nc = FloatArray(nw * nh * 3)
            val nwt = FloatArray(nw * nh)
            for (y in 0 until nh) for (x in 0 until nw) {
                var sw = 0f; var s0 = 0f; var s1 = 0f; var s2 = 0f
                for (dy in 0..1) for (dx in 0..1) {
                    val yy = y * 2 + dy; val xx = x * 2 + dx
                    if (yy >= ph || xx >= pw) continue
                    val i = yy * pw + xx
                    val ww = pwt[i]
                    if (ww <= 0f) continue
                    sw += ww; s0 += ww * pc[i * 3]; s1 += ww * pc[i * 3 + 1]; s2 += ww * pc[i * 3 + 2]
                }
                val o = y * nw + x
                if (sw > 0f) {
                    nc[o * 3] = s0 / sw; nc[o * 3 + 1] = s1 / sw; nc[o * 3 + 2] = s2 / sw
                    nwt[o] = min(1f, sw)
                }
            }
            cols.add(nc); wts.add(nwt); dw.add(nw); dh.add(nh)
        }
        // vicinanza alla zona coperta (livello ~ 32 celle): oltre, il buco resta vuoto
        val nearLv = min(5, cols.size - 1)
        val nearW = wts[nearLv]
        val nearDw = dw[nearLv]
        val nearDh = dh[nearLv]
        val shift = nearLv
        near = BooleanArray(cells) { i ->
            val cx = (i % gw) shr shift
            val cy = (i / gw) shr shift
            var ok = false
            for (dy in -1..1) for (dx in -1..1) {
                val x = cx + dx; val y = cy + dy
                if (x in 0 until nearDw && y in 0 until nearDh && nearW[y * nearDw + x] > 0f) ok = true
            }
            ok
        }
        // push: dall'alto in basso
        var res = cols.last().copyOf()
        if (wts.last()[0] <= 0f) { res[0] = 14f; res[1] = 14f; res[2] = 14f }
        for (lv in cols.size - 2 downTo 0) {
            val cw = dw[lv]; val ch = dh[lv]
            val pw = dw[lv + 1]; val ph = dh[lv + 1]
            val out = FloatArray(cw * ch * 3)
            val c = cols[lv]; val wt = wts[lv]
            for (y in 0 until ch) {
                val py = ((y + 0.5f) / 2f - 0.5f).coerceIn(0f, ph - 1f)
                val py0 = py.toInt(); val py1 = min(py0 + 1, ph - 1); val fy = py - py0
                for (x in 0 until cw) {
                    val px = ((x + 0.5f) / 2f - 0.5f).coerceIn(0f, pw - 1f)
                    val px0 = px.toInt(); val px1 = min(px0 + 1, pw - 1); val fx = px - px0
                    val i = y * cw + x
                    val a = (py0 * pw + px0) * 3; val b = (py0 * pw + px1) * 3
                    val cc = (py1 * pw + px0) * 3; val d = (py1 * pw + px1) * 3
                    val w00 = (1 - fx) * (1 - fy); val w10 = fx * (1 - fy)
                    val w01 = (1 - fx) * fy; val w11 = fx * fy
                    val ww = wt[i]
                    for (k in 0 until 3) {
                        val up = res[a + k] * w00 + res[b + k] * w10 + res[cc + k] * w01 + res[d + k] * w11
                        out[i * 3 + k] = if (ww >= 1f) c[i * 3 + k] else ww * c[i * 3 + k] + (1f - ww) * up
                    }
                }
            }
            res = out
        }
        val col = res

        val rows = 32
        val buf = IntArray(w * rows)
        var y0 = 0
        while (y0 < h) {
            val nr = min(rows, h - y0)
            bmp.getPixels(buf, 0, w, 0, y0, w, nr)
            for (r in 0 until nr) {
                val gy = ((y0 + r + 0.5f) / C - 0.5f).coerceIn(0f, gh - 1f)
                val gy0 = gy.toInt()
                val gy1 = min(gy0 + 1, gh - 1)
                val fy = gy - gy0
                for (x in 0 until w) {
                    if ((buf[r * w + x] ushr 24) != 0) continue
                    if (!near[min(gh - 1, (y0 + r) / C) * gw + min(gw - 1, x / C)]) {
                        buf[r * w + x] = 0xFF0E0E0E.toInt()
                        continue
                    }
                    val gx = ((x + 0.5f) / C - 0.5f).coerceIn(0f, gw - 1f)
                    val gx0 = gx.toInt()
                    val gx1 = min(gx0 + 1, gw - 1)
                    val fx = gx - gx0
                    val a = (gy0 * gw + gx0) * 3
                    val b = (gy0 * gw + gx1) * 3
                    val c = (gy1 * gw + gx0) * 3
                    val d = (gy1 * gw + gx1) * 3
                    val w00 = (1 - fx) * (1 - fy); val w10 = fx * (1 - fy)
                    val w01 = (1 - fx) * fy; val w11 = fx * fy
                    val rr = clampPix((col[a] * w00 + col[b] * w10 + col[c] * w01 + col[d] * w11 + 0.5f).toInt())
                    val gg = clampPix((col[a + 1] * w00 + col[b + 1] * w10 + col[c + 1] * w01 + col[d + 1] * w11 + 0.5f).toInt())
                    val bb = clampPix((col[a + 2] * w00 + col[b + 2] * w10 + col[c + 2] * w01 + col[d + 2] * w11 + 0.5f).toInt())
                    buf[r * w + x] = (0xFF shl 24) or (rr shl 16) or (gg shl 8) or bb
                }
            }
            bmp.setPixels(buf, 0, w, 0, y0, w, nr)
            y0 += nr
        }
    }
}
