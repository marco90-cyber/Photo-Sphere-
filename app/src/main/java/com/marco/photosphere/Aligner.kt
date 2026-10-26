package com.marco.photosphere

import org.opencv.android.OpenCVLoader
import org.opencv.android.Utils
import org.opencv.calib3d.Calib3d
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfDMatch
import org.opencv.core.MatOfKeyPoint
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Point
import org.opencv.core.Core
import org.opencv.core.Size
import org.opencv.features2d.BFMatcher
import org.opencv.features2d.SIFT
import org.opencv.imgproc.Imgproc
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

class AlignResult(val R: List<FloatArray>, val sf: Float, val gains: List<FloatArray>)

private class Feat(
    val n: Int,
    val x: FloatArray,
    val y: FloatArray,
    val u: DoubleArray,
    val v: DoubleArray,
    val desc: FloatArray
)

private class PairMatch(
    val i: Int, val j: Int, val k: Int,
    val u1: DoubleArray, val v1: DoubleArray,
    val u2: DoubleArray, val v2: DoubleArray,
    val c1: DoubleArray, val c2: DoubleArray   // colori RGB campionati (3 per match)
)

private class BAOut(val R: Array<DoubleArray>, val cost: Double)

/**
 * Raffina l'orientamento di ogni scatto confrontando i punti in comune (SIFT + RANSAC) e
 * risolvendo un bundle adjustment di sole rotazioni, partendo dai sensori come stima iniziale.
 * Stima anche il campo visivo reale e un guadagno di esposizione per ogni scatto.
 */
object Aligner {

    fun align(shots: List<Shot>, fovLong: Double, progress: (Float) -> Unit): AlignResult? {
        if (shots.size < 2) return null
        if (!OpenCVLoader.initLocal()) return null

        val n = shots.size
        val tanL = tan(fovLong / 2)
        val tanS = tanL * 0.75

        // ---- 1. caratteristiche
        val feats = ArrayList<Feat>()
        val smalls = ArrayList<Mat>()
        val sift = SIFT.create(2500)
        try {
            for ((idx, s) in shots.withIndex()) {
                progress(0.35f * idx / n)
                val bmp = ShotIO.decodeUpright(s.path, 1280)
                val w = bmp.width
                val h = bmp.height
                val rgba = Mat()
                Utils.bitmapToMat(bmp, rgba)
                bmp.recycle()
                val gray = Mat()
                Imgproc.cvtColor(rgba, gray, Imgproc.COLOR_RGBA2GRAY)
                val kp = MatOfKeyPoint()
                val desc = Mat()
                sift.detectAndCompute(gray, Mat(), kp, desc)
                val pts = kp.toArray()
                val cnt = if (desc.empty()) 0 else min(pts.size, desc.rows())
                val da = FloatArray(cnt * 128)
                if (cnt > 0) desc.get(0, 0, da)
                val x = FloatArray(cnt) { pts[it].pt.x.toFloat() }
                val y = FloatArray(cnt) { pts[it].pt.y.toFloat() }
                val u = DoubleArray(cnt) { x[it].toDouble() / (w - 1) * 2.0 - 1.0 }
                val v = DoubleArray(cnt) { 1.0 - y[it].toDouble() / (h - 1) * 2.0 }
                feats.add(Feat(cnt, x, y, u, v, da))
                val small = Mat()
                Imgproc.resize(rgba, small, Size(w / 4.0, h / 4.0), 0.0, 0.0, Imgproc.INTER_AREA)
                smalls.add(small)
                rgba.release(); gray.release(); kp.release(); desc.release()
            }

            // ---- 2. coppie e corrispondenze
            val R0 = Array(n) { i -> DoubleArray(9) { k -> shots[i].R[k].toDouble() } }
            val fwd = Array(n) { i -> doubleArrayOf(-R0[i][2], -R0[i][5], -R0[i][8]) }
            val matcher = BFMatcher(Core.NORM_L2, false)
            val pairs = ArrayList<PairMatch>()
            val maxAngle = fovLong * 1.1
            var done = 0
            val total = n * (n - 1) / 2
            for (i in 0 until n) {
                for (j in i + 1 until n) {
                    done++
                    progress(0.35f + 0.45f * done / total)
                    val dot = fwd[i][0] * fwd[j][0] + fwd[i][1] * fwd[j][1] + fwd[i][2] * fwd[j][2]
                    if (kotlin.math.acos(dot.coerceIn(-1.0, 1.0)) > maxAngle) continue
                    val pm = matchPair(i, j, feats[i], feats[j], R0[i], R0[j], tanS, tanL, matcher, smalls[i], smalls[j])
                    if (pm != null) pairs.add(pm)
                }
            }
            if (pairs.isEmpty()) return null

            // ---- 3. bundle adjustment + stima del campo visivo
            progress(0.82f)
            var bestSf = 1.0
            var best: BAOut? = null
            val coarse = doubleArrayOf(0.96, 0.975, 0.99, 1.0, 1.01, 1.025, 1.04)
            for (sf in coarse) {
                val r = bundle(R0, pairs, tanS, tanL, sf)
                if (best == null || r.cost < best.cost) { best = r; bestSf = sf }
            }
            val center = bestSf
            for (d in doubleArrayOf(-0.006, -0.003, 0.003, 0.006)) {
                val sf = center + d
                val r = bundle(R0, pairs, tanS, tanL, sf)
                if (r.cost < best!!.cost) { best = r; bestSf = sf }
            }
            val finalR = best!!.R

            // ---- 4. guadagni di esposizione
            progress(0.92f)
            val gains = solveGains(n, pairs)

            progress(1f)
            return AlignResult(
                finalR.map { r -> FloatArray(9) { k -> r[k].toFloat() } },
                bestSf.toFloat(),
                gains
            )
        } finally {
            smalls.forEach { it.release() }
        }
    }

    // ------------------------------------------------------------------ matching

    private fun candidates(f: Feat, Ra: DoubleArray, Rb: DoubleArray, tanS: Double, tanL: Double): IntArray {
        val out = IntArray(f.n)
        var c = 0
        for (k in 0 until f.n) {
            val cx = f.u[k] * tanS
            val cy = f.v[k] * tanL
            val e = Ra[0] * cx + Ra[1] * cy - Ra[2]
            val nn = Ra[3] * cx + Ra[4] * cy - Ra[5]
            val up = Ra[6] * cx + Ra[7] * cy - Ra[8]
            val dx = Rb[0] * e + Rb[3] * nn + Rb[6] * up
            val dy = Rb[1] * e + Rb[4] * nn + Rb[7] * up
            val dz = Rb[2] * e + Rb[5] * nn + Rb[8] * up
            if (dz < -0.05) {
                val u = dx / (-dz) / tanS
                val v = dy / (-dz) / tanL
                if (abs(u) < 1.12 && abs(v) < 1.12) out[c++] = k
            }
        }
        return out.copyOf(c)
    }

    private fun subMat(f: Feat, idx: IntArray): Mat {
        val arr = FloatArray(idx.size * 128)
        for ((r, k) in idx.withIndex()) System.arraycopy(f.desc, k * 128, arr, r * 128, 128)
        val m = Mat(idx.size, 128, CvType.CV_32F)
        m.put(0, 0, arr)
        return m
    }

    private fun matchPair(
        i: Int, j: Int, fi: Feat, fj: Feat, Ri: DoubleArray, Rj: DoubleArray,
        tanS: Double, tanL: Double, matcher: BFMatcher, si: Mat, sj: Mat
    ): PairMatch? {
        val ci = candidates(fi, Ri, Rj, tanS, tanL)
        val cj = candidates(fj, Rj, Ri, tanS, tanL)
        if (ci.size < 20 || cj.size < 20) return null
        val a = subMat(fi, ci)
        val b = subMat(fj, cj)
        val knn = ArrayList<MatOfDMatch>()
        try {
            matcher.knnMatch(a, b, knn, 2)
        } finally {
            a.release(); b.release()
        }
        val src = ArrayList<Point>()
        val dst = ArrayList<Point>()
        val qi = ArrayList<Int>()
        val ti = ArrayList<Int>()
        for (m in knn) {
            val arr = m.toArray()
            if (arr.size == 2 && arr[0].distance < 0.8f * arr[1].distance) {
                val p = ci[arr[0].queryIdx]
                val q = cj[arr[0].trainIdx]
                src.add(Point(fi.x[p].toDouble(), fi.y[p].toDouble()))
                dst.add(Point(fj.x[q].toDouble(), fj.y[q].toDouble()))
                qi.add(p); ti.add(q)
            }
            m.release()
        }
        if (src.size < 15) return null
        val ms = MatOfPoint2f()
        ms.fromList(src)
        val md = MatOfPoint2f()
        md.fromList(dst)
        val mask = Mat()
        val H = Calib3d.findHomography(ms, md, Calib3d.RANSAC, 4.0, mask)
        ms.release(); md.release()
        if (H.empty()) { mask.release(); return null }
        H.release()
        val mk = ByteArray(src.size)
        mask.get(0, 0, mk)
        mask.release()
        val sel = ArrayList<Int>()
        for (k in mk.indices) if (mk[k].toInt() != 0) sel.add(k)
        if (sel.size < 12) return null
        // al massimo 150 corrispondenze per coppia, distribuite
        val step = max(1, sel.size / 150)
        val use = ArrayList<Int>()
        var k = 0
        while (k < sel.size) { use.add(sel[k]); k += step }
        val cnt = use.size
        val u1 = DoubleArray(cnt); val v1 = DoubleArray(cnt)
        val u2 = DoubleArray(cnt); val v2 = DoubleArray(cnt)
        val c1 = DoubleArray(cnt * 3); val c2 = DoubleArray(cnt * 3)
        for ((r, s) in use.withIndex()) {
            val p = qi[s]
            val q = ti[s]
            u1[r] = fi.u[p]; v1[r] = fi.v[p]
            u2[r] = fj.u[q]; v2[r] = fj.v[q]
            sample(si, fi.x[p] / 4f, fi.y[p] / 4f, c1, r * 3)
            sample(sj, fj.x[q] / 4f, fj.y[q] / 4f, c2, r * 3)
        }
        return PairMatch(i, j, cnt, u1, v1, u2, v2, c1, c2)
    }

    private fun sample(m: Mat, x: Float, y: Float, out: DoubleArray, o: Int) {
        val cx = x.toInt().coerceIn(0, m.cols() - 1)
        val cy = y.toInt().coerceIn(0, m.rows() - 1)
        val px = m.get(cy, cx)
        out[o] = px[0]; out[o + 1] = px[1]; out[o + 2] = px[2]
    }

    // ------------------------------------------------------------------ bundle adjustment

    private const val TAU = 0.004      // soglia Huber (rad)
    private const val LAMBDA = 0.002   // peso della stima dei sensori

    private fun rays(u: DoubleArray, v: DoubleArray, tanS: Double, tanL: Double, sf: Double): DoubleArray {
        val out = DoubleArray(u.size * 3)
        for (k in u.indices) {
            val x = u[k] * tanS * sf
            val y = v[k] * tanL * sf
            val l = sqrt(x * x + y * y + 1.0)
            out[3 * k] = x / l; out[3 * k + 1] = y / l; out[3 * k + 2] = -1.0 / l
        }
        return out
    }

    private fun mulV(R: DoubleArray, d: DoubleArray, k: Int, out: DoubleArray) {
        val x = d[3 * k]; val y = d[3 * k + 1]; val z = d[3 * k + 2]
        out[0] = R[0] * x + R[1] * y + R[2] * z
        out[1] = R[3] * x + R[4] * y + R[5] * z
        out[2] = R[6] * x + R[7] * y + R[8] * z
    }

    private fun huber(rn: Double): Double = if (rn < TAU) 0.5 * rn * rn else TAU * (rn - 0.5 * TAU)

    private fun bundle(R0: Array<DoubleArray>, pairs: List<PairMatch>, tanS: Double, tanL: Double, sf: Double): BAOut {
        val n = R0.size
        val dim = 3 * n
        var R = Array(n) { R0[it].copyOf() }
        var c = DoubleArray(dim)
        val d1 = pairs.map { rays(it.u1, it.v1, tanS, tanL, sf) }
        val d2 = pairs.map { rays(it.u2, it.v2, tanS, tanL, sf) }
        val w1 = DoubleArray(3)
        val w2 = DoubleArray(3)

        fun cost(Rc: Array<DoubleArray>, cc: DoubleArray): Double {
            var s = 0.0
            for ((pi, p) in pairs.withIndex()) {
                for (k in 0 until p.k) {
                    mulV(Rc[p.i], d1[pi], k, w1)
                    mulV(Rc[p.j], d2[pi], k, w2)
                    val rx = w1[0] - w2[0]; val ry = w1[1] - w2[1]; val rz = w1[2] - w2[2]
                    s += huber(sqrt(rx * rx + ry * ry + rz * rz))
                }
            }
            var pr = 0.0
            for (x in cc) pr += x * x
            return s + 0.5 * LAMBDA * pr
        }

        var cur = cost(R, c)
        var mu = 1e-3
        for (iter in 0 until 25) {
            val H = DoubleArray(dim * dim)
            val g = DoubleArray(dim)
            for ((pi, p) in pairs.withIndex()) {
                val bi = 3 * p.i
                val bj = 3 * p.j
                for (k in 0 until p.k) {
                    mulV(R[p.i], d1[pi], k, w1)
                    mulV(R[p.j], d2[pi], k, w2)
                    val rx = w1[0] - w2[0]; val ry = w1[1] - w2[1]; val rz = w1[2] - w2[2]
                    val rn = sqrt(rx * rx + ry * ry + rz * rz)
                    val wt = if (rn < TAU) 1.0 else TAU / rn
                    val dotw = w1[0] * w2[0] + w1[1] * w2[1] + w1[2] * w2[2]
                    for (a in 0 until 3) {
                        for (b in 0 until 3) {
                            val id = if (a == b) 1.0 else 0.0
                            H[(bi + a) * dim + bi + b] += wt * (id - w1[a] * w1[b])
                            H[(bj + a) * dim + bj + b] += wt * (id - w2[a] * w2[b])
                            val hij = wt * (w2[a] * w1[b] - dotw * id)
                            H[(bi + a) * dim + bj + b] += hij
                            H[(bj + b) * dim + bi + a] += hij
                        }
                    }
                    // g_i += w1 x r ; g_j -= w2 x r
                    g[bi] += wt * (w1[1] * rz - w1[2] * ry)
                    g[bi + 1] += wt * (w1[2] * rx - w1[0] * rz)
                    g[bi + 2] += wt * (w1[0] * ry - w1[1] * rx)
                    g[bj] -= wt * (w2[1] * rz - w2[2] * ry)
                    g[bj + 1] -= wt * (w2[2] * rx - w2[0] * rz)
                    g[bj + 2] -= wt * (w2[0] * ry - w2[1] * rx)
                }
            }
            for (d in 0 until dim) {
                H[d * dim + d] += LAMBDA
                g[d] += LAMBDA * c[d]
            }

            var improved = false
            var maxStep = 0.0
            for (attempt in 0 until 8) {
                val A = H.copyOf()
                for (d in 0 until dim) A[d * dim + d] += mu * (H[d * dim + d] + 1e-6)
                val rhs = DoubleArray(dim) { -g[it] }
                if (!solve(A, rhs, dim)) { mu *= 10; continue }
                val Rn = Array(n) { rotateLeft(R[it], rhs[3 * it], rhs[3 * it + 1], rhs[3 * it + 2]) }
                val cn = DoubleArray(dim) { c[it] + rhs[it] }
                val cNew = cost(Rn, cn)
                if (cNew < cur) {
                    maxStep = rhs.maxOf { abs(it) }
                    R = Rn; c = cn; cur = cNew
                    mu = max(mu / 3, 1e-7)
                    improved = true
                    break
                } else {
                    mu *= 4
                }
            }
            if (!improved || maxStep < 1e-6) break
        }
        return BAOut(R, cur)
    }

    private fun rotateLeft(R: DoubleArray, x: Double, y: Double, z: Double): DoubleArray {
        val th = sqrt(x * x + y * y + z * z)
        val E: DoubleArray
        if (th < 1e-12) {
            return R.copyOf()
        } else {
            val kx = x / th; val ky = y / th; val kz = z / th
            val s = sin(th); val cs = cos(th); val t = 1 - cs
            E = doubleArrayOf(
                cs + kx * kx * t, kx * ky * t - kz * s, kx * kz * t + ky * s,
                ky * kx * t + kz * s, cs + ky * ky * t, ky * kz * t - kx * s,
                kz * kx * t - ky * s, kz * ky * t + kx * s, cs + kz * kz * t
            )
        }
        val out = DoubleArray(9)
        for (r in 0 until 3) for (cc in 0 until 3) {
            var s = 0.0
            for (k in 0 until 3) s += E[r * 3 + k] * R[k * 3 + cc]
            out[r * 3 + cc] = s
        }
        return out
    }

    /** Risolve A x = b (A dim x dim) in place: Gauss con pivot parziale. */
    private fun solve(A: DoubleArray, b: DoubleArray, n: Int): Boolean {
        for (col in 0 until n) {
            var piv = col
            var best = abs(A[col * n + col])
            for (r in col + 1 until n) {
                val v = abs(A[r * n + col])
                if (v > best) { best = v; piv = r }
            }
            if (best < 1e-12) return false
            if (piv != col) {
                for (k in 0 until n) {
                    val t = A[col * n + k]; A[col * n + k] = A[piv * n + k]; A[piv * n + k] = t
                }
                val t = b[col]; b[col] = b[piv]; b[piv] = t
            }
            val d = A[col * n + col]
            for (r in col + 1 until n) {
                val f = A[r * n + col] / d
                if (f == 0.0) continue
                for (k in col until n) A[r * n + k] -= f * A[col * n + k]
                b[r] -= f * b[col]
            }
        }
        for (r in n - 1 downTo 0) {
            var s = b[r]
            for (k in r + 1 until n) s -= A[r * n + k] * b[k]
            b[r] = s / A[r * n + r]
        }
        return true
    }

    // ------------------------------------------------------------------ esposizione

    private fun solveGains(n: Int, pairs: List<PairMatch>): List<FloatArray> {
        val out = List(n) { FloatArray(3) { 1f } }
        for (ch in 0 until 3) {
            val H = DoubleArray(n * n)
            val rhs = DoubleArray(n)
            val selfSum = DoubleArray(n)
            for (p in pairs) {
                var saa = 0.0; var sbb = 0.0; var sab = 0.0
                for (k in 0 until p.k) {
                    val a = p.c1[k * 3 + ch]
                    val b = p.c2[k * 3 + ch]
                    saa += a * a; sbb += b * b; sab += a * b
                }
                H[p.i * n + p.i] += saa
                H[p.j * n + p.j] += sbb
                H[p.i * n + p.j] -= sab
                H[p.j * n + p.i] -= sab
                selfSum[p.i] += saa
                selfSum[p.j] += sbb
            }
            for (i in 0 until n) {
                val pr = 0.05 * selfSum[i] + 1e-3
                H[i * n + i] += pr
                rhs[i] = pr
            }
            if (!solve(H, rhs, n)) continue
            for (i in 0 until n) out[i][ch] = rhs[i].coerceIn(0.75, 1.25).toFloat()
        }
        return out
    }
}
