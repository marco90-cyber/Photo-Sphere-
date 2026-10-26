package com.marco.photosphere

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import androidx.exifinterface.media.ExifInterface
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
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

class Shot(val path: String, val R: FloatArray)

private class Img(val px: IntArray, val w: Int, val h: Int)

/**
 * Cucitura sferica guidata dai sensori: ogni scatto viene proiettato sulla sfera usando
 * l'orientamento reale del telefono (matrice di rotazione) e il campo visivo dell'obiettivo,
 * poi fuso con pesi sfumati ai bordi. Risultato: immagine equirettangolare 2:1.
 */
class Stitcher(private val outW: Int, private val fovLong: Double) {
    private val outH = outW / 2
    private val tanL = tan(fovLong / 2).toFloat()
    private val tanS = tanL * 0.75f

    fun run(shots: List<Shot>, yaw0: Float, progress: (Float) -> Unit): Bitmap {
        val rgb = ByteArray(outW * outH * 3)
        val wt = ShortArray(outW * outH)

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
                        blendRows(from, to, xs, xe, img, R, latSin, latCos, lonSin, lonCos, rgb, wt)
                    })
                    ys = to + 1
                }
                pool.invokeAll(tasks).forEach { it.get() }
            }
        } finally {
            pool.shutdown()
        }
        progress(1f)
        return toBitmap(rgb, wt)
    }

    private fun blendRows(
        yFrom: Int, yTo: Int, xs: Int, xe: Int, img: Img, R: FloatArray,
        latSin: FloatArray, latCos: FloatArray, lonSin: FloatArray, lonCos: FloatArray,
        rgb: ByteArray, wt: ShortArray
    ) {
        val px = img.px
        val w = img.w
        val h = img.h
        val r0 = R[0]; val r1 = R[1]; val r2 = R[2]
        val r3 = R[3]; val r4 = R[4]; val r5 = R[5]
        val r6 = R[6]; val r7 = R[7]; val r8 = R[8]
        for (y in yFrom..yTo) {
            val lc = latCos[y]
            val u0 = latSin[y]
            for (xi in xs..xe) {
                val x = ((xi % outW) + outW) % outW
                val e = lc * lonSin[x]
                val n = lc * lonCos[x]
                // mondo -> coordinate dispositivo (R trasposta)
                val dz = r2 * e + r5 * n + r8 * u0
                if (dz > -0.05f) continue
                val dx = r0 * e + r3 * n + r6 * u0
                val dy = r1 * e + r4 * n + r7 * u0
                val inv = -1f / dz
                val u = dx * inv / tanS
                val v = dy * inv / tanL
                if (u <= -1f || u >= 1f || v <= -1f || v >= 1f) continue

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
                val r = (((a shr 16) and 255) * w00 + ((b shr 16) and 255) * w10 +
                    ((c shr 16) and 255) * w01 + ((d shr 16) and 255) * w11 + 0.5f).toInt()
                val g = (((a shr 8) and 255) * w00 + ((b shr 8) and 255) * w10 +
                    ((c shr 8) and 255) * w01 + ((d shr 8) and 255) * w11 + 0.5f).toInt()
                val bl = ((a and 255) * w00 + (b and 255) * w10 +
                    (c and 255) * w01 + (d and 255) * w11 + 0.5f).toInt()

                val ww = (1f - abs(u)) * (1f - abs(v))
                val nw = (ww * ww * 255f).toInt() + 1
                val idx = y * outW + x
                val old = wt[idx].toInt()
                val b3 = idx * 3
                if (old == 0) {
                    rgb[b3] = r.toByte()
                    rgb[b3 + 1] = g.toByte()
                    rgb[b3 + 2] = bl.toByte()
                    wt[idx] = nw.toShort()
                } else {
                    val tot = old + nw
                    rgb[b3] = (((rgb[b3].toInt() and 255) * old + r * nw) / tot).toByte()
                    rgb[b3 + 1] = (((rgb[b3 + 1].toInt() and 255) * old + g * nw) / tot).toByte()
                    rgb[b3 + 2] = (((rgb[b3 + 2].toInt() and 255) * old + bl * nw) / tot).toByte()
                    wt[idx] = min(tot, 30000).toShort()
                }
            }
        }
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
        // lo zenit o il nadir sono dentro l'inquadratura?
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
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, o)
        var sample = 1
        while (max(o.outWidth, o.outHeight) / sample > 3000) sample *= 2
        val o2 = BitmapFactory.Options().apply { inSampleSize = sample }
        var bmp = BitmapFactory.decodeFile(path, o2) ?: throw IllegalStateException("Scatto illeggibile")
        val orient = ExifInterface(path).getAttributeInt(
            ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL
        )
        var deg = when (orient) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90
            ExifInterface.ORIENTATION_ROTATE_180 -> 180
            ExifInterface.ORIENTATION_ROTATE_270 -> 270
            else -> 0
        }
        if (deg == 0 && bmp.width > bmp.height) deg = 90
        if (deg != 0) {
            val m = Matrix()
            m.postRotate(deg.toFloat())
            val r = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
            bmp.recycle()
            bmp = r
        }
        val px = IntArray(bmp.width * bmp.height)
        bmp.getPixels(px, 0, bmp.width, 0, 0, bmp.width, bmp.height)
        val img = Img(px, bmp.width, bmp.height)
        bmp.recycle()
        return img
    }

    private fun toBitmap(rgb: ByteArray, wt: ShortArray): Bitmap {
        val bmp = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888)
        val rows = 32
        val buf = IntArray(outW * rows)
        var y = 0
        while (y < outH) {
            val n = min(rows, outH - y)
            for (r in 0 until n) {
                for (x in 0 until outW) {
                    val idx = (y + r) * outW + x
                    buf[r * outW + x] = if (wt[idx].toInt() == 0) {
                        0xFF0E0E0E.toInt()
                    } else {
                        (0xFF shl 24) or
                            ((rgb[idx * 3].toInt() and 255) shl 16) or
                            ((rgb[idx * 3 + 1].toInt() and 255) shl 8) or
                            (rgb[idx * 3 + 2].toInt() and 255)
                    }
                }
            }
            bmp.setPixels(buf, 0, outW, 0, y, outW, n)
            y += n
        }
        return bmp
    }
}
