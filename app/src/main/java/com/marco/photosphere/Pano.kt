package com.marco.photosphere

import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfByte
import org.opencv.core.MatOfInt
import org.opencv.core.Rect
import org.opencv.core.Scalar
import org.opencv.imgcodecs.Imgcodecs
import org.opencv.imgproc.Imgproc
import java.io.File
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * Operazioni sul panorama equirettangolare (immagini BGR di OpenCV).
 * Convenzione delle direzioni, uguale a quella del visualizzatore:
 *   dir(lon, lat) = (cos lat * sin lon, sin lat, -cos lat * cos lon), lon = 0 al centro dell'immagine.
 */
object Pano {
    const val FILL = 14.0
    private const val STRIP = 128

    fun dir(lon: Double, lat: Double): DoubleArray {
        val cl = cos(lat)
        return doubleArrayOf(cl * sin(lon), sin(lat), -cl * cos(lon))
    }

    /** Matrice 3x3 (row-major) del livellamento: rollio attorno all'asse -z dopo l'inclinazione attorno a x. */
    fun levelMatrix(rollDeg: Float, pitchDeg: Float): DoubleArray {
        val p = Math.toRadians(pitchDeg.toDouble())
        val r = Math.toRadians(rollDeg.toDouble())
        val cp = cos(p); val sp = sin(p); val cr = cos(r); val sr = sin(r)
        // Rz(r) * Rx(p)
        return doubleArrayOf(
            cr, -sr * cp, sr * sp,
            sr, cr * cp, -cr * sp,
            0.0, sp, cp
        )
    }

    fun readBgr(f: File): Mat {
        val m = Imgcodecs.imread(f.path, Imgcodecs.IMREAD_COLOR)
        if (m.empty()) throw IllegalStateException("Immagine illeggibile: " + f.name)
        return m
    }

    fun writeJpeg(m: Mat, f: File, quality: Int = 94) {
        val buf = MatOfByte()
        Imgcodecs.imencode(".jpg", m, buf, MatOfInt(Imgcodecs.IMWRITE_JPEG_QUALITY, quality))
        f.writeBytes(buf.toArray())
        buf.release()
    }

    fun encodeJpeg(m: Mat, quality: Int = 92): ByteArray {
        val buf = MatOfByte()
        Imgcodecs.imencode(".jpg", m, buf, MatOfInt(Imgcodecs.IMWRITE_JPEG_QUALITY, quality))
        val b = buf.toArray()
        buf.release()
        return b
    }

    /** Porta l'immagine (anche ritagliata) sulla tela intera 360x180; le zone mancanti sono grigio scuro. */
    fun toCanvas(src: Mat, info: CropInfo): Mat {
        if (info.isFull && src.cols() == info.fullW && src.rows() == info.fullH) return src
        val canvas = Mat(info.fullH, info.fullW, CvType.CV_8UC3, Scalar(FILL, FILL, FILL))
        val w = min(src.cols(), info.fullW - info.left)
        val h = min(src.rows(), info.fullH - info.top)
        if (w > 0 && h > 0) {
            val part = src.submat(Rect(0, 0, w, h))
            part.copyTo(canvas.submat(Rect(info.left, info.top, w, h)))
            part.release()
        }
        src.release()
        return canvas
    }

    // ------------------------------------------------------------------ livellamento

    fun rotateLevel(src: Mat, rollDeg: Float, pitchDeg: Float): Mat {
        val W = src.cols()
        val H = src.rows()
        val M = levelMatrix(rollDeg, pitchDeg)
        val dst = Mat(H, W, src.type())
        val sinLon = DoubleArray(W)
        val cosLon = DoubleArray(W)
        for (x in 0 until W) {
            val lon = ((x + 0.5) / W - 0.5) * 2 * PI
            sinLon[x] = sin(lon); cosLon[x] = cos(lon)
        }
        var y0 = 0
        while (y0 < H) {
            val rows = min(STRIP, H - y0)
            val mx = FloatArray(rows * W)
            val my = FloatArray(rows * W)
            for (r in 0 until rows) {
                val lat = (0.5 - (y0 + r + 0.5) / H) * PI
                val cl = cos(lat)
                val sl = sin(lat)
                for (x in 0 until W) {
                    val ox = cl * sinLon[x]
                    val oy = sl
                    val oz = -cl * cosLon[x]
                    // s = M^T o
                    val sx = M[0] * ox + M[3] * oy + M[6] * oz
                    val sy = M[1] * ox + M[4] * oy + M[7] * oz
                    val sz = M[2] * ox + M[5] * oy + M[8] * oz
                    val lonS = atan2(sx, -sz)
                    val latS = asin(sy.coerceIn(-1.0, 1.0))
                    mx[r * W + x] = ((lonS / (2 * PI) + 0.5) * W - 0.5).toFloat()
                    my[r * W + x] = ((0.5 - latS / PI) * H - 0.5).coerceIn(0.0, H - 1.0).toFloat()
                }
            }
            val mapX = Mat(rows, W, CvType.CV_32F)
            val mapY = Mat(rows, W, CvType.CV_32F)
            mapX.put(0, 0, mx)
            mapY.put(0, 0, my)
            val tmp = Mat()
            Imgproc.remap(src, tmp, mapX, mapY, Imgproc.INTER_CUBIC, Core.BORDER_WRAP)
            val roi = dst.submat(Rect(0, y0, W, rows))
            tmp.copyTo(roi)
            roi.release(); tmp.release(); mapX.release(); mapY.release()
            y0 += rows
        }
        return dst
    }

    // ------------------------------------------------------------------ toppe

    private class Frame(lon: Double, lat: Double, rotDeg: Double) {
        val f = dir(lon, lat)
        val right: DoubleArray
        val up: DoubleArray

        init {
            val r0 = doubleArrayOf(cos(lon), 0.0, sin(lon))
            val u0 = doubleArrayOf(
                r0[1] * f[2] - r0[2] * f[1],
                r0[2] * f[0] - r0[0] * f[2],
                r0[0] * f[1] - r0[1] * f[0]
            )
            val a = Math.toRadians(rotDeg)
            val c = cos(a); val s = sin(a)
            right = doubleArrayOf(r0[0] * c + u0[0] * s, r0[1] * c + u0[1] * s, r0[2] * c + u0[2] * s)
            up = doubleArrayOf(-r0[0] * s + u0[0] * c, -r0[1] * s + u0[1] * c, -r0[2] * s + u0[2] * c)
        }
    }

    /** Immagine rettilinea (come una foto normale) dalla tela equirettangolare. fovDeg = campo visivo orizzontale. */
    fun rectilinear(equi: Mat, lonDeg: Double, latDeg: Double, fovDeg: Double, w: Int, h: Int, rotDeg: Double = 0.0): Mat {
        val W = equi.cols()
        val H = equi.rows()
        val fr = Frame(Math.toRadians(lonDeg), Math.toRadians(latDeg), rotDeg)
        val focal = (w / 2.0) / tan(Math.toRadians(fovDeg) / 2)
        val cx = (w - 1) / 2.0
        val cy = (h - 1) / 2.0
        val mx = FloatArray(w * h)
        val my = FloatArray(w * h)
        for (py in 0 until h) {
            for (px in 0 until w) {
                val a = (px - cx) / focal
                val b = (cy - py) / focal
                var dx = fr.f[0] + a * fr.right[0] + b * fr.up[0]
                var dy = fr.f[1] + a * fr.right[1] + b * fr.up[1]
                var dz = fr.f[2] + a * fr.right[2] + b * fr.up[2]
                val l = sqrt(dx * dx + dy * dy + dz * dz)
                dx /= l; dy /= l; dz /= l
                val lon = atan2(dx, -dz)
                val lat = asin(dy.coerceIn(-1.0, 1.0))
                mx[py * w + px] = ((lon / (2 * PI) + 0.5) * W - 0.5).toFloat()
                my[py * w + px] = ((0.5 - lat / PI) * H - 0.5).coerceIn(0.0, H - 1.0).toFloat()
            }
        }
        return remapMaps(equi, mx, my, w, h)
    }

    private fun remapMaps(src: Mat, mx: FloatArray, my: FloatArray, w: Int, h: Int): Mat {
        val mapX = Mat(h, w, CvType.CV_32F)
        val mapY = Mat(h, w, CvType.CV_32F)
        mapX.put(0, 0, mx)
        mapY.put(0, 0, my)
        val out = Mat()
        Imgproc.remap(src, out, mapX, mapY, Imgproc.INTER_CUBIC, Core.BORDER_WRAP)
        mapX.release(); mapY.release()
        return out
    }

    /** Incolla la toppa sulla tela (in place), con bordo sfumato. */
    fun applyPatch(canvas: Mat, patch: Mat, rec: PatchRec) {
        val W = canvas.cols()
        val H = canvas.rows()
        val wp = patch.cols()
        val hp = patch.rows()
        val fr = Frame(Math.toRadians(rec.lon.toDouble()), Math.toRadians(rec.lat.toDouble()), rec.rot.toDouble())
        val tx = tan(Math.toRadians(rec.fov.toDouble()) / 2)
        val focal = (wp / 2.0) / tx
        val cx = (wp - 1) / 2.0
        val cy = (hp - 1) / 2.0
        val rho = atan(sqrt(tx * tx + (tx * hp / wp) * (tx * hp / wp)))
        val lat0 = Math.toRadians(rec.lat.toDouble())
        val allRows = abs(lat0) + rho + 0.05 >= PI / 2
        val latHi = lat0 + rho + 0.05
        val latLo = lat0 - rho - 0.05
        val feather = rec.feather.coerceIn(0.02f, 1f).toDouble()
        val sinLon = DoubleArray(W)
        val cosLon = DoubleArray(W)
        for (x in 0 until W) {
            val lon = ((x + 0.5) / W - 0.5) * 2 * PI
            sinLon[x] = sin(lon); cosLon[x] = cos(lon)
        }
        var y0 = 0
        while (y0 < H) {
            val rows = min(STRIP, H - y0)
            val latTop = (0.5 - y0.toDouble() / H) * PI
            val latBot = (0.5 - (y0 + rows).toDouble() / H) * PI
            if (!allRows && (latBot > latHi || latTop < latLo)) { y0 += rows; continue }
            val mx = FloatArray(rows * W)
            val my = FloatArray(rows * W)
            val al = FloatArray(rows * W)
            var any = false
            for (r in 0 until rows) {
                val lat = (0.5 - (y0 + r + 0.5) / H) * PI
                val cl = cos(lat)
                val sl = sin(lat)
                for (x in 0 until W) {
                    val ox = cl * sinLon[x]
                    val oy = sl
                    val oz = -cl * cosLon[x]
                    val t = ox * fr.f[0] + oy * fr.f[1] + oz * fr.f[2]
                    val k = r * W + x
                    if (t < 0.05) { mx[k] = -1f; my[k] = -1f; continue }
                    val px = cx + (ox * fr.right[0] + oy * fr.right[1] + oz * fr.right[2]) / t * focal
                    val py = cy - (ox * fr.up[0] + oy * fr.up[1] + oz * fr.up[2]) / t * focal
                    val nx = (px - cx) / (wp / 2.0)
                    val ny = (py - cy) / (hp / 2.0)
                    val a: Double = if (rec.shape == 0) {
                        val rr = sqrt(nx * nx + ny * ny)
                        ((1.0 - rr) / feather)
                    } else {
                        min(1.0 - abs(nx), 1.0 - abs(ny)) / feather
                    }
                    if (a <= 0.0) { mx[k] = -1f; my[k] = -1f; continue }
                    mx[k] = px.toFloat(); my[k] = py.toFloat()
                    al[k] = a.coerceAtMost(1.0).toFloat()
                    any = true
                }
            }
            if (any) {
                val mapX = Mat(rows, W, CvType.CV_32F)
                val mapY = Mat(rows, W, CvType.CV_32F)
                mapX.put(0, 0, mx)
                mapY.put(0, 0, my)
                val warped = Mat()
                Imgproc.remap(patch, warped, mapX, mapY, Imgproc.INTER_LINEAR, Core.BORDER_REPLICATE)
                val roi = canvas.submat(Rect(0, y0, W, rows))
                val cb = ByteArray(rows * W * 3)
                val wb = ByteArray(rows * W * 3)
                roi.get(0, 0, cb)
                warped.get(0, 0, wb)
                for (k in 0 until rows * W) {
                    val a = al[k]
                    if (a <= 0f) continue
                    for (c in 0 until 3) {
                        val o = k * 3 + c
                        val cv = cb[o].toInt() and 0xFF
                        val wv = wb[o].toInt() and 0xFF
                        cb[o] = (cv + (wv - cv) * a + 0.5f).toInt().coerceIn(0, 255).toByte()
                    }
                }
                roi.put(0, 0, cb)
                roi.release(); mapX.release(); mapY.release(); warped.release()
            }
            y0 += rows
        }
    }

    // ------------------------------------------------------------------ proiezioni creative

    const val PLANET = 0
    const val TUNNEL = 1
    const val VIEW = 2
    const val CYL = 3

    /**
     * Proiezioni creative dalla tela equirettangolare.
     * PLANET/TUNNEL: quadrato, a = ampiezza (gradi, 90..170), yawDeg = rotazione.
     * VIEW: vista normale verso (yawDeg, pitchDeg), a = campo visivo orizzontale.
     * CYL: striscia cilindrica centrata su yawDeg, a = ampiezza orizzontale (gradi), pitchDeg = latitudine massima.
     */
    fun creative(equi: Mat, type: Int, yawDeg: Double, pitchDeg: Double, a: Double, outW: Int): Mat {
        val W = equi.cols()
        val H = equi.rows()
        when (type) {
            VIEW -> return rectilinear(equi, yawDeg, pitchDeg, a.coerceIn(30.0, 140.0), outW, outW * 3 / 4)
            CYL -> {
                val span = Math.toRadians(a.coerceIn(60.0, 360.0))
                val maxLat = Math.toRadians(pitchDeg.coerceIn(20.0, 80.0))
                val w = outW
                val half = (w / span) * tan(maxLat)
                val h = min((2 * half).toInt().coerceAtLeast(16), w)
                val scale = w / span
                val mx = FloatArray(w * h)
                val my = FloatArray(w * h)
                val lon0 = Math.toRadians(yawDeg)
                for (y in 0 until h) {
                    val lat = atan(((h - 1) / 2.0 - y) / scale)
                    val yy = ((0.5 - lat / PI) * H - 0.5).coerceIn(0.0, H - 1.0).toFloat()
                    for (x in 0 until w) {
                        val lon = lon0 + (x.toDouble() / (w - 1) - 0.5) * span
                        mx[y * w + x] = ((lon / (2 * PI) + 0.5) * W - 0.5).toFloat()
                        my[y * w + x] = yy
                    }
                }
                return remapMaps(equi, mx, my, w, h)
            }
            else -> {
                val n = outW
                val thMax = Math.toRadians(a.coerceIn(90.0, 170.0))
                val k = 2.0 * tan(thMax / 2)
                val c = (n - 1) / 2.0
                val mx = FloatArray(n * n)
                val my = FloatArray(n * n)
                val yaw = Math.toRadians(yawDeg)
                for (y in 0 until n) {
                    for (x in 0 until n) {
                        val u = (x - c) / c
                        val v = (c - y) / c
                        val r = sqrt(u * u + v * v)
                        val th = 2.0 * atan(r * k / 2.0)
                        var lat = if (type == PLANET) -PI / 2 + th else PI / 2 - th
                        var lon = yaw + atan2(u, v)
                        if (lat > PI / 2) { lat = PI - lat; lon += PI }
                        if (lat < -PI / 2) { lat = -PI - lat; lon += PI }
                        mx[y * n + x] = ((lon / (2 * PI) + 0.5) * W - 0.5).toFloat()
                        my[y * n + x] = ((0.5 - lat / PI) * H - 0.5).coerceIn(0.0, H - 1.0).toFloat()
                    }
                }
                return remapMaps(equi, mx, my, n, n)
            }
        }
    }

    fun maxOf3(a: Int, b: Int, c: Int) = max(a, max(b, c))
}
