package com.marco.photosphere

import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfByte
import org.opencv.core.MatOfInt
import org.opencv.core.Rect
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgcodecs.Imgcodecs
import org.opencv.imgproc.Imgproc
import org.opencv.photo.Photo
import java.util.concurrent.Callable
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

class Shot(val path: String, val R: FloatArray)

class StitchResult(
    val jpeg: ByteArray,
    val fullW: Int, val fullH: Int,
    val cropW: Int, val cropH: Int,
    val left: Int, val top: Int,
    val coverage: Float
)

private class Work(val img: Mat, val R: FloatArray, val latLo: Double, val latHi: Double)

private class Cand(val idx: Int, val rect: Rect)

/**
 * Cucitura sferica a più bande (Laplacian pyramid blending), a strisce orizzontali:
 *  - ogni scatto viene ridotto con filtro (INTER_AREA) alla risoluzione del risultato (niente aliasing);
 *  - per ogni punto della sfera, i dettagli (alte frequenze) vengono da UN solo scatto, il più centrato:
 *    niente doppioni né sfocature da lieve disallineamento;
 *  - le basse frequenze (colore, luce) vengono invece mescolate su tutta la sovrapposizione con pesi morbidi:
 *    niente giunture di esposizione;
 *  - vignettatura e guadagno di esposizione sono compensati prima;
 *  - il lavoro procede a strisce con margine, quindi la memoria resta limitata anche a 6K.
 * Provato su scatti sintetici: stessi risultati con strisce da 256 e 512 righe (differenza max 0,3/255).
 */
class Stitcher(
    private val outW: Int,
    private val fovLong: Double,
    private val sf: Float = 1f,
    private val gains: List<FloatArray>? = null,
    private val vig: FloatArray? = null
) {
    private val W = outW
    private val H = outW / 2
    private val L = 6
    private val MULT = 1 shl L
    private val MARGIN = 2 * MULT
    private val CORE = 256
    private val CW = W + 2 * MARGIN
    private val CH = CORE + 2 * MARGIN
    private val RATIO = 1.5
    private val tanL = (tan(fovLong / 2) * sf).toFloat()
    private var aspect = 0.75f
    private var tanS = tanL * aspect

    /** Esponente dei pesi morbidi per ogni livello della piramide (0 = maschera netta). */
    private val levelExp = floatArrayOf(0f, 0f, 0f, 4f, 4f, 2f, 1f)
    private val exps = floatArrayOf(4f, 2f, 1f)

    private val threads = max(2, min(4, Runtime.getRuntime().availableProcessors()))

    private val lonSin = FloatArray(CW)
    private val lonCos = FloatArray(CW)
    private val latSin = FloatArray(CH)
    private val latCos = FloatArray(CH)

    fun run(shots: List<Shot>, yaw0: Float, progress: (Float) -> Unit): StitchResult {
        require(W % MULT == 0) { "Larghezza non valida" }
        val pool = Executors.newFixedThreadPool(threads)
        val works = ArrayList<Work>()
        val out = Mat.zeros(H, W, CvType.CV_8UC3)
        val cov = Mat.zeros(H, W, CvType.CV_8UC1)
        try {
            for (x in 0 until CW) {
                val lon = (((x - MARGIN) + 0.5) / W - 0.5) * 2.0 * PI + yaw0
                lonSin[x] = sin(lon).toFloat()
                lonCos[x] = cos(lon).toFloat()
            }

            // ---------------------------------------------------- 1. preparazione degli scatti
            val workLong = (2.0 * atan(tanL.toDouble()) * W / (2.0 * PI) * RATIO).roundToInt().coerceIn(400, 4000)
            var vigMap: Mat? = null
            for ((i, s) in shots.withIndex()) {
                progress(0.15f * i / shots.size)
                val rgb = ShotIO.decodeRgbMat(s.path, workLong)
                if (i == 0) {
                    aspect = (rgb.cols().toFloat() / rgb.rows()).coerceIn(0.5f, 1f)
                    tanS = tanL * aspect
                    val a = vig?.getOrNull(0) ?: 0f
                    val b = vig?.getOrNull(1) ?: 0f
                    vigMap = vignetteMat(rgb.cols(), rgb.rows(), a, b)
                }
                val g = gains?.getOrNull(i) ?: floatArrayOf(1f, 1f, 1f)
                val comp = compensate(rgb, g, vigMap)
                rgb.release()
                val lat = latRange(s.R)
                works.add(Work(comp, s.R, lat[0], lat[1]))
            }
            vigMap?.release()

            // ---------------------------------------------------- 2. strisce
            val wdFull = FloatArray(CW * CH)
            val roiTmp = FloatArray(CW * CH)
            val sxArr = FloatArray(CW * CH)
            val syArr = FloatArray(CW * CH)
            val wdArr = FloatArray(CW * CH)
            val strips = (H + CORE - 1) / CORE
            for (si in 0 until strips) {
                val y0 = si * CORE
                progress(0.15f + 0.70f * si / strips)
                stripRun(y0, works, pool, out, cov, wdFull, roiTmp, sxArr, syArr, wdArr)
            }
        } catch (t: Throwable) {
            out.release(); cov.release()
            throw t
        } finally {
            pool.shutdown()
            works.forEach { it.img.release() }
        }
        progress(0.86f)
        return finish(out, cov, progress)
    }

    // ------------------------------------------------------------------ una striscia

    private fun stripRun(
        y0: Int, works: List<Work>, pool: ExecutorService, out: Mat, cov: Mat,
        wdFull: FloatArray, roiTmp: FloatArray, sxArr: FloatArray, syArr: FloatArray, wdArr: FloatArray
    ) {
        val rowStart = y0 - MARGIN
        for (r in 0 until CH) {
            val g = (rowStart + r).coerceIn(0, H - 1)
            val lat = (0.5 - (g + 0.5) / H) * PI
            latSin[r] = sin(lat).toFloat()
            latCos[r] = cos(lat).toFloat()
        }
        val topLat = (0.5 - max(rowStart, 0).toDouble() / H) * PI
        val botLat = (0.5 - (min(rowStart + CH - 1, H - 1) + 1).toDouble() / H) * PI
        val candIdx = works.indices.filter { works[it].latHi >= botLat - 0.02 && works[it].latLo <= topLat + 0.02 }

        val best = Mat.zeros(CH, CW, CvType.CV_32F)
        val owner = Mat(CH, CW, CvType.CV_32F, Scalar(-1.0))
        val S = exps.map { Mat.zeros(CH, CW, CvType.CV_32F) }
        val cands = ArrayList<Cand>()

        // ---- passo 1: pesi di bordo, proprietario di ogni pixel, somme dei pesi morbidi
        for (i in candIdx) {
            val w = works[i]
            project(w.R, 0, 0, CW, CH, false, w.img.cols(), w.img.rows(), wdFull, null, null, pool)
            var xmin = CW; var xmax = -1; var ymin = CH; var ymax = -1
            for (r in 0 until CH) {
                val base = r * CW
                var first = -1
                var last = -1
                for (c in 0 until CW) {
                    if (wdFull[base + c] > 0f) {
                        if (first < 0) first = c
                        last = c
                    }
                }
                if (first >= 0) {
                    if (first < xmin) xmin = first
                    if (last > xmax) xmax = last
                    if (r < ymin) ymin = r
                    ymax = r
                }
            }
            if (xmax < 0) continue
            val xa = (xmin / MULT) * MULT
            val ya = (ymin / MULT) * MULT
            val xb = min(CW, ((xmax + 1 + MULT - 1) / MULT) * MULT)
            val yb = min(CH, ((ymax + 1 + MULT - 1) / MULT) * MULT)
            val rect = Rect(xa, ya, xb - xa, yb - ya)
            cands.add(Cand(i, rect))

            for (r in 0 until rect.height) System.arraycopy(wdFull, (ya + r) * CW + xa, roiTmp, r * rect.width, rect.width)
            val wdRoi = Mat(rect.height, rect.width, CvType.CV_32F)
            wdRoi.put(0, 0, roiTmp)
            val bestRoi = best.submat(rect)
            val ownerRoi = owner.submat(rect)
            val better = Mat()
            Core.compare(wdRoi, bestRoi, better, Core.CMP_GT)
            ownerRoi.setTo(Scalar(i.toDouble()), better)
            wdRoi.copyTo(bestRoi, better)
            better.release(); bestRoi.release(); ownerRoi.release()
            val tmp = Mat()
            for ((ei, e) in exps.withIndex()) {
                Core.pow(wdRoi, e.toDouble(), tmp)
                val sRoi = S[ei].submat(rect)
                Core.add(sRoi, tmp, sRoi)
                sRoi.release()
            }
            tmp.release(); wdRoi.release()
        }

        // ---- passo 2: piramidi e fusione
        val dst = (0..L).map { k -> Mat.zeros(CH shr k, CW shr k, CvType.CV_32FC3) }
        val dw = (0..L).map { k -> Mat.zeros(CH shr k, CW shr k, CvType.CV_32F) }
        for (c in cands) {
            val w = works[c.idx]
            val rect = c.rect
            project(w.R, rect.x, rect.y, rect.width, rect.height, true, w.img.cols(), w.img.rows(), wdArr, sxArr, syArr, pool)

            val map1 = Mat(rect.height, rect.width, CvType.CV_32F)
            val map2 = Mat(rect.height, rect.width, CvType.CV_32F)
            map1.put(0, 0, sxArr)
            map2.put(0, 0, syArr)
            val img8 = Mat()
            Imgproc.remap(w.img, img8, map1, map2, Imgproc.INTER_CUBIC, Core.BORDER_REPLICATE)
            map1.release(); map2.release()
            val img32 = Mat()
            img8.convertTo(img32, CvType.CV_32F)
            img8.release()

            val gp = ArrayList<Mat>()
            gp.add(img32)
            for (k in 0 until L) {
                val d = Mat()
                Imgproc.pyrDown(gp[k], d)
                gp.add(d)
            }

            // basi delle maschere (a risoluzione piena della ROI)
            val wdRoi = Mat(rect.height, rect.width, CvType.CV_32F)
            wdRoi.put(0, 0, wdArr)
            val ownerRoi = owner.submat(rect)
            val hard8 = Mat()
            Core.inRange(ownerRoi, Scalar(c.idx - 0.5), Scalar(c.idx + 0.5), hard8)
            ownerRoi.release()
            val hard = Mat()
            hard8.convertTo(hard, CvType.CV_32F, 1.0 / 255.0)
            hard8.release()

            val chains = HashMap<Int, ArrayList<Mat>>()   // 0 = netta, altrimenti indice esponente + 1
            fun chainFor(key: Int, base: Mat, upTo: Int): ArrayList<Mat> {
                val ch = ArrayList<Mat>()
                ch.add(base)
                for (k in 0 until upTo) {
                    val d = Mat()
                    Imgproc.pyrDown(ch[k], d)
                    ch.add(d)
                }
                chains[key] = ch
                return ch
            }
            var hardTop = 0
            val softTop = IntArray(exps.size) { -1 }
            for (k in 0..L) {
                val e = levelExp[k]
                if (e == 0f) hardTop = k else {
                    val ei = exps.indexOf(e)
                    softTop[ei] = k
                }
            }
            chainFor(0, hard, hardTop)
            for ((ei, e) in exps.withIndex()) {
                if (softTop[ei] < 0) continue
                val pw = Mat()
                Core.pow(wdRoi, e.toDouble(), pw)
                val sRoi = S[ei].submat(rect)
                val den = Mat()
                Core.max(sRoi, Scalar(1e-9), den)
                sRoi.release()
                val base = Mat()
                Core.divide(pw, den, base)
                pw.release(); den.release()
                chainFor(ei + 1, base, softTop[ei])
            }
            wdRoi.release()

            for (k in 0..L) {
                val e = levelExp[k]
                val key = if (e == 0f) 0 else exps.indexOf(e) + 1
                val m = chains[key]!![k]
                val band: Mat
                if (k < L) {
                    val up = Mat()
                    Imgproc.pyrUp(gp[k + 1], up, Size(gp[k].cols().toDouble(), gp[k].rows().toDouble()))
                    band = Mat()
                    Core.subtract(gp[k], up, band)
                    up.release()
                } else {
                    band = gp[k]
                }
                val m3 = Mat()
                Imgproc.cvtColor(m, m3, Imgproc.COLOR_GRAY2BGR)
                Core.multiply(band, m3, band)
                m3.release()
                val rk = Rect(rect.x shr k, rect.y shr k, rect.width shr k, rect.height shr k)
                val dSub = dst[k].submat(rk)
                Core.add(dSub, band, dSub)
                dSub.release()
                val wSub = dw[k].submat(rk)
                Core.add(wSub, m, wSub)
                wSub.release()
                if (k < L) band.release()
            }
            gp.forEach { it.release() }
            chains.values.forEach { l -> l.forEach { it.release() } }
        }

        // ---- normalizzazione e ricostruzione
        for (k in 0..L) {
            val d = Mat()
            Core.max(dw[k], Scalar(1e-4), d)
            val d3 = Mat()
            Imgproc.cvtColor(d, d3, Imgproc.COLOR_GRAY2BGR)
            Core.divide(dst[k], d3, dst[k])
            d.release(); d3.release()
        }
        var res = dst[L].clone()
        for (k in L - 1 downTo 0) {
            val up = Mat()
            Imgproc.pyrUp(res, up, Size(dst[k].cols().toDouble(), dst[k].rows().toDouble()))
            res.release()
            Core.add(up, dst[k], up)
            res = up
        }
        val res8 = Mat()
        res.convertTo(res8, CvType.CV_8U)
        res.release()

        val rowsN = min(CORE, H - y0)
        val coreRect = Rect(MARGIN, MARGIN, W, rowsN)
        val srcSub = res8.submat(coreRect)
        val dstRect = Rect(0, y0, W, rowsN)
        val outSub = out.submat(dstRect)
        srcSub.copyTo(outSub)
        val ownCore = owner.submat(coreRect)
        val covTmp = Mat()
        Core.inRange(ownCore, Scalar(-0.5), Scalar(1.0e6), covTmp)
        val covSub = cov.submat(dstRect)
        covTmp.copyTo(covSub)
        covSub.release(); covTmp.release(); ownCore.release()
        outSub.release(); srcSub.release(); res8.release()

        dst.forEach { it.release() }
        dw.forEach { it.release() }
        S.forEach { it.release() }
        best.release(); owner.release()
    }

    // ------------------------------------------------------------------ proiezione

    /**
     * Per la regione [x0,x0+rw) x [y0,y0+rh) del canvas della striscia calcola il peso di bordo wd
     * (0 = fuori dallo scatto) e, se richiesto, le coordinate di campionamento nello scatto.
     * Gli array sono indicizzati r*rw + c rispetto alla regione.
     */
    private fun project(
        R: FloatArray, x0: Int, y0: Int, rw: Int, rh: Int, withMaps: Boolean,
        srcW: Int, srcH: Int, wd: FloatArray, sx: FloatArray?, sy: FloatArray?, pool: ExecutorService
    ) {
        val r0 = R[0]; val r1 = R[1]; val r2 = R[2]
        val r3 = R[3]; val r4 = R[4]; val r5 = R[5]
        val r6 = R[6]; val r7 = R[7]; val r8 = R[8]
        val half = 0.5f
        val kx = (srcW - 1) * half
        val ky = (srcH - 1) * half
        val chunk = max(8, (rh + threads * 3 - 1) / (threads * 3))
        val tasks = ArrayList<Callable<Unit>>()
        var rs = 0
        while (rs < rh) {
            val from = rs
            val to = min(rh, rs + chunk)
            tasks.add(Callable {
                for (r in from until to) {
                    val lc = latCos[y0 + r]
                    val up = latSin[y0 + r]
                    val base = r * rw
                    for (c in 0 until rw) {
                        val ci = x0 + c
                        val e = lc * lonSin[ci]
                        val n = lc * lonCos[ci]
                        val dz = r2 * e + r5 * n + r8 * up
                        if (dz > -0.05f) {
                            wd[base + c] = 0f
                            if (withMaps) { sx!![base + c] = kx; sy!![base + c] = ky }
                            continue
                        }
                        val dx = r0 * e + r3 * n + r6 * up
                        val dy = r1 * e + r4 * n + r7 * up
                        val inv = -1f / dz
                        val u = dx * inv / tanS
                        val v = dy * inv / tanL
                        val au = abs(u)
                        val av = abs(v)
                        wd[base + c] = if (au < 1f && av < 1f) min((1f - au) * aspect, 1f - av) else 0f
                        if (withMaps) {
                            sx!![base + c] = (u + 1f) * kx
                            sy!![base + c] = (1f - v) * ky
                        }
                    }
                }
            })
            rs = to
        }
        pool.invokeAll(tasks).forEach { it.get() }
    }

    /** Intervallo di latitudine coperto da uno scatto: [min, max] in radianti. */
    private fun latRange(R: FloatArray): DoubleArray {
        var lo = PI / 2
        var hi = -PI / 2
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
                val e = (R[0] * cx + R[1] * cy - R[2]).toDouble()
                val n = (R[3] * cx + R[4] * cy - R[5]).toDouble()
                val up = (R[6] * cx + R[7] * cy - R[8]).toDouble()
                val lat = asin(up / sqrt(e * e + n * n + up * up))
                if (lat < lo) lo = lat
                if (lat > hi) hi = lat
            }
        }
        for (s in intArrayOf(1, -1)) {
            val dz = s * R[8]
            if (dz < -0.01f) {
                val u = s * R[6] / (-dz) / tanS
                val v = s * R[7] / (-dz) / tanL
                if (abs(u) < 1f && abs(v) < 1f) {
                    if (s > 0) hi = PI / 2 else lo = -PI / 2
                }
            }
        }
        return doubleArrayOf(lo, hi)
    }

    // ------------------------------------------------------------------ esposizione

    private fun vignetteMat(w: Int, h: Int, a: Float, b: Float): Mat? {
        if (a == 0f && b == 0f) return null
        val arr = FloatArray(w * h * 3)
        for (y in 0 until h) {
            val v = 1f - y.toFloat() / (h - 1) * 2f
            for (x in 0 until w) {
                val u = x.toFloat() / (w - 1) * 2f - 1f
                val q = (u * u + v * v) * 0.5f
                val f = exp(-(a * q + b * q * q))
                val o = (y * w + x) * 3
                arr[o] = f; arr[o + 1] = f; arr[o + 2] = f
            }
        }
        val m = Mat(h, w, CvType.CV_32FC3)
        m.put(0, 0, arr)
        return m
    }

    private fun compensate(rgb: Mat, g: FloatArray, vigMap: Mat?): Mat {
        val f = Mat()
        rgb.convertTo(f, CvType.CV_32F)
        if (vigMap != null && vigMap.cols() == f.cols() && vigMap.rows() == f.rows()) Core.multiply(f, vigMap, f)
        Core.multiply(f, Scalar(g[0].toDouble(), g[1].toDouble(), g[2].toDouble()), f)
        val out = Mat()
        f.convertTo(out, CvType.CV_8U)
        f.release()
        return out
    }

    // ------------------------------------------------------------------ finitura

    private fun finish(out: Mat, cov: Mat, progress: (Float) -> Unit): StitchResult {
        val rowMax = Mat()
        Core.reduce(cov, rowMax, 1, Core.REDUCE_MAX, CvType.CV_8U)
        val colMax = Mat()
        Core.reduce(cov, colMax, 0, Core.REDUCE_MAX, CvType.CV_8U)
        val rowHas = ByteArray(H)
        val colHas = ByteArray(W)
        rowMax.get(0, 0, rowHas)
        colMax.get(0, 0, colHas)
        rowMax.release(); colMax.release()

        var top = -1
        var bottom = -1
        for (y in 0 until H) if (rowHas[y].toInt() != 0) { if (top < 0) top = y; bottom = y }
        if (top < 0) {
            out.release(); cov.release()
            throw IllegalStateException("Nessuno scatto utilizzabile")
        }
        val coveredPx = Core.countNonZero(cov).toDouble()

        // colonne: cerca il tratto vuoto più lungo (circolare) e ritaglia
        var bestLen = 0
        var bestStart = 0
        var run = 0
        for (k in 0 until 2 * W) {
            if (colHas[k % W].toInt() == 0) {
                run++
                if (run > W - 1) break
                if (run > bestLen) {
                    bestLen = run
                    bestStart = (k - run + 1) % W
                }
            } else {
                run = 0
            }
        }
        val start: Int
        val cropW: Int
        if (bestLen >= W / 50) {
            start = (bestStart + bestLen) % W
            cropW = W - bestLen
        } else {
            start = 0
            cropW = W
        }
        val cropH = bottom - top + 1

        val img = cropWrap(out, start, top, cropW, cropH)
        val cv = cropWrap(cov, start, top, cropW, cropH)
        out.release(); cov.release()
        progress(0.90f)

        val holes = Mat()
        Core.bitwise_not(cv, holes)
        cv.release()
        if (Core.countNonZero(holes) > 0) {
            val filled = fillSmallHoles(img, holes)
            val remaining = Mat()
            if (filled != null) {
                val nf = Mat()
                Core.bitwise_not(filled, nf)
                Core.bitwise_and(holes, nf, remaining)
                nf.release(); filled.release()
            } else {
                holes.copyTo(remaining)
            }
            img.setTo(Scalar(14.0, 14.0, 14.0), remaining)
            remaining.release()
        }
        holes.release()
        progress(0.95f)

        val bgr = Mat()
        Imgproc.cvtColor(img, bgr, Imgproc.COLOR_RGB2BGR)
        img.release()
        val buf = MatOfByte()
        Imgcodecs.imencode(".jpg", bgr, buf, MatOfInt(Imgcodecs.IMWRITE_JPEG_QUALITY, 94))
        bgr.release()
        val bytes = buf.toArray()
        buf.release()
        progress(1f)
        return StitchResult(
            bytes, W, H, cropW, cropH, 0, top,
            (coveredPx / (W.toDouble() * H)).toFloat()
        )
    }

    private fun cropWrap(src: Mat, start: Int, top: Int, cw: Int, ch: Int): Mat {
        if (start + cw <= W) {
            val s = src.submat(Rect(start, top, cw, ch))
            val c = s.clone()
            s.release()
            return c
        }
        val first = W - start
        val a = src.submat(Rect(start, top, first, ch))
        val b = src.submat(Rect(0, top, cw - first, ch))
        val dst = Mat()
        Core.hconcat(listOf(a, b), dst)
        a.release(); b.release()
        return dst
    }

    /**
     * Riempie con Telea solo i buchi sottili (fessure fra scatti, nadir/zenit piccoli).
     * Le zone grandi non fotografate restano scure: meglio vederle che avere sbavature finte.
     * Restituisce la maschera riempita, o null se non ha riempito nulla.
     */
    private fun fillSmallHoles(img: Mat, holes: Mat): Mat? {
        val s = 4
        val cw = img.cols()
        val ch = img.rows()
        val sw = max(2, cw / s)
        val sh = max(2, ch / s)
        val hs = Mat()
        Imgproc.resize(holes, hs, Size(sw.toDouble(), sh.toDouble()), 0.0, 0.0, Imgproc.INTER_AREA)
        Imgproc.threshold(hs, hs, 0.0, 255.0, Imgproc.THRESH_BINARY)

        val labels = Mat()
        val stats = Mat()
        val cent = Mat()
        val nLab = Imgproc.connectedComponentsWithStats(hs, labels, stats, cent, 8, CvType.CV_32S)
        val dt = Mat()
        Imgproc.distanceTransform(hs, dt, Imgproc.DIST_L2, 3)
        val labArr = IntArray(sw * sh)
        val dtArr = FloatArray(sw * sh)
        labels.get(0, 0, labArr)
        dt.get(0, 0, dtArr)
        labels.release(); stats.release(); cent.release(); dt.release(); hs.release()
        val maxD = FloatArray(nLab)
        for (i in labArr.indices) {
            val l = labArr[i]
            if (l > 0 && dtArr[i] > maxD[l]) maxD[l] = dtArr[i]
        }
        val limit = (W / 120f) / s
        val fm = ByteArray(sw * sh)
        var any = false
        for (i in labArr.indices) {
            val l = labArr[i]
            if (l > 0 && maxD[l] <= limit) { fm[i] = 255.toByte(); any = true }
        }
        if (!any) return null

        val fillSmall = Mat(sh, sw, CvType.CV_8UC1)
        fillSmall.put(0, 0, fm)
        Imgproc.dilate(fillSmall, fillSmall, Mat())

        val small = Mat()
        Imgproc.resize(img, small, Size(sw.toDouble(), sh.toDouble()), 0.0, 0.0, Imgproc.INTER_AREA)
        val inp = Mat()
        Photo.inpaint(small, fillSmall, inp, 4.0, Photo.INPAINT_TELEA)
        small.release()
        val big = Mat()
        Imgproc.resize(inp, big, Size(cw.toDouble(), ch.toDouble()), 0.0, 0.0, Imgproc.INTER_CUBIC)
        inp.release()
        val fullMask = Mat()
        Imgproc.resize(fillSmall, fullMask, Size(cw.toDouble(), ch.toDouble()), 0.0, 0.0, Imgproc.INTER_NEAREST)
        fillSmall.release()
        val kernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(7.0, 7.0))
        val holesD = Mat()
        Imgproc.dilate(holes, holesD, kernel)
        kernel.release()
        Core.bitwise_and(fullMask, holesD, fullMask)
        holesD.release()
        big.copyTo(img, fullMask)
        big.release()
        return fullMask
    }
}
