package com.marco.photosphere

import android.content.Context
import org.opencv.android.OpenCVLoader
import org.opencv.core.Mat
import org.opencv.core.Size
import org.opencv.imgcodecs.Imgcodecs
import org.opencv.imgproc.Imgproc
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.math.PI
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.tan

/**
 * Elaborazione completa di un progetto, eseguita dal servizio in background:
 *   FULL     = allineamento + cucitura + toppe + livellamento
 *   RESTITCH = cucitura (con allineamento già calcolato) + toppe + livellamento
 *   COMPOSE  = solo toppe + livellamento (parte dalla cucitura già fatta)
 */
object Pipeline {
    const val FULL = 0
    const val RESTITCH = 1
    const val COMPOSE = 2

    fun run(ctx: Context, p: Project, mode: Int, progress: (Float, String) -> Unit) {
        if (!OpenCVLoader.initLocal()) throw IllegalStateException("libreria OpenCV non disponibile")
        var m = mode
        if (p.base == null || !p.baseFile.exists()) m = if (m == COMPOSE) RESTITCH else m
        if (m <= RESTITCH && p.enabledShots.size < 1) throw IllegalStateException("Nessuno scatto attivo")

        if (m <= RESTITCH) {
            val enabled = p.enabledShots
            val list = enabled.map { Shot(p.shotFile(it).path, it.R) }
            if (m == FULL) {
                progress(0f, "Allineo le immagini…")
                val al = try {
                    Aligner.align(list, p.fovLong) { v -> progress(v * 0.38f, "Allineo le immagini…") }
                } catch (e: Exception) {
                    null
                } catch (e: LinkageError) {
                    null
                }
                if (al != null) {
                    enabled.forEachIndexed { i, s ->
                        s.ar = al.R[i]; s.gain = al.gains[i]; s.weak = al.weak.getOrElse(i) { false }
                    }
                    p.sf = al.sf
                    p.vig = al.vig
                } else {
                    enabled.forEach { it.ar = null; it.gain = null; it.weak = false }
                    p.sf = 1f
                    p.vig = null
                }
                p.save()
            }
            val aligned = enabled.mapIndexed { i, s ->
                val base = s.ar ?: s.R
                Shot(list[i].path, applyDelta(base, s.dyaw, s.dpitch, s.droll))
            }
            val gains: List<FloatArray>? = if (enabled.all { it.gain != null }) enabled.map { it.gain!! } else null
            val width = safeWidth(ctx, p.outW, aligned.size, p.fovLong)
            progress(0.4f, "Unisco le immagini…")
            val res: StitchResult = try {
                Stitcher(width, p.fovLong, p.sf, gains, p.vig).run(aligned, p.yaw0) { v ->
                    progress(0.4f + v * 0.45f, "Unisco le immagini…")
                }
            } catch (e: OutOfMemoryError) {
                throw e
            } catch (e: Exception) {
                progress(0.45f, "Uso il metodo alternativo…")
                val lr = LegacyStitcher(min(width, 4096), p.fovLong, p.sf, gains, p.vig).run(aligned, p.yaw0) { v ->
                    progress(0.45f + v * 0.4f, "Uso il metodo alternativo…")
                }
                val baos = ByteArrayOutputStream(8 * 1024 * 1024)
                lr.bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 93, baos)
                lr.bmp.recycle()
                StitchResult(baos.toByteArray(), lr.fullW, lr.fullH, lr.cropW, lr.cropH, lr.left, lr.top, 1f)
            }
            p.baseFile.writeBytes(res.jpeg)
            p.base = CropInfo(res.fullW, res.fullH, res.cropW, res.cropH, res.left, res.top)
            p.coverage = res.coverage
            p.save()
        }

        progress(0.86f, "Applico le modifiche…")
        compose(p) { v -> progress(0.86f + v * 0.08f, "Applico le modifiche…") }
        progress(0.95f, "Salvo la foto…")
        publish(ctx, p)
        if (!p.keepShots) {
            p.shots.forEach { p.shotFile(it).delete() }
            p.shots.clear()
        }
        p.lastError = null
        p.save()
        progress(1f, "Fatto")
    }

    /** Rotazione manuale nel sistema dello scatto: yaw attorno a y, pitch attorno a x, roll attorno a z (gradi). */
    fun applyDelta(R: FloatArray, dyaw: Float, dpitch: Float, droll: Float): FloatArray {
        if (dyaw == 0f && dpitch == 0f && droll == 0f) return R
        val y = Math.toRadians(dyaw.toDouble()); val x = Math.toRadians(dpitch.toDouble()); val z = Math.toRadians(droll.toDouble())
        val ry = doubleArrayOf(cos(y), 0.0, sin(y), 0.0, 1.0, 0.0, -sin(y), 0.0, cos(y))
        val rx = doubleArrayOf(1.0, 0.0, 0.0, 0.0, cos(x), -sin(x), 0.0, sin(x), cos(x))
        val rz = doubleArrayOf(cos(z), -sin(z), 0.0, sin(z), cos(z), 0.0, 0.0, 0.0, 1.0)
        val d = mul(ry, mul(rx, rz))
        val r = DoubleArray(9) { R[it].toDouble() }
        val o = mul(r, d)
        return FloatArray(9) { o[it].toFloat() }
    }

    private fun mul(a: DoubleArray, b: DoubleArray): DoubleArray {
        val o = DoubleArray(9)
        for (i in 0 until 3) for (j in 0 until 3) {
            var s = 0.0
            for (k in 0 until 3) s += a[i * 3 + k] * b[k * 3 + j]
            o[i * 3 + j] = s
        }
        return o
    }

    /** Toppe e livellamento a partire da base.jpg; scrive pre.jpg, final.jpg e la miniatura. */
    fun compose(p: Project, progress: (Float) -> Unit) {
        val base = p.base ?: throw IllegalStateException("Cucitura mancante")
        val applied = p.patches.filter { it.applied && p.file(it.file).exists() }
        val level = p.levelRoll != 0f || p.levelPitch != 0f
        var canvas: Mat? = null
        try {
            if (applied.isEmpty()) {
                p.baseFile.copyTo(p.preFile, overwrite = true)
                p.pre = base
            } else {
                canvas = Pano.toCanvas(Pano.readBgr(p.baseFile), base)
                for ((i, pr) in applied.withIndex()) {
                    progress(0.5f * i / applied.size)
                    val pm = Pano.readBgr(p.file(pr.file))
                    Pano.applyPatch(canvas, pm, pr)
                    pm.release()
                }
                Pano.writeJpeg(canvas, p.preFile)
                p.pre = CropInfo(canvas.cols(), canvas.rows(), canvas.cols(), canvas.rows(), 0, 0)
            }
            progress(0.55f)
            if (!level) {
                p.preFile.copyTo(p.finalFile, overwrite = true)
                p.final = p.pre
            } else {
                val src = canvas ?: Pano.toCanvas(Pano.readBgr(p.preFile), p.pre!!)
                canvas = src
                val rot = Pano.rotateLevel(src, p.levelRoll, p.levelPitch)
                Pano.writeJpeg(rot, p.finalFile)
                p.final = CropInfo(rot.cols(), rot.rows(), rot.cols(), rot.rows(), 0, 0)
                rot.release()
            }
            progress(0.9f)
            // miniatura
            val th = Imgcodecs.imread(p.finalFile.path, Imgcodecs.IMREAD_REDUCED_COLOR_4)
            if (!th.empty()) {
                val out = Mat()
                val w = 640.0
                Imgproc.resize(th, out, Size(w, th.rows() * w / th.cols()), 0.0, 0.0, Imgproc.INTER_AREA)
                Pano.writeJpeg(out, p.thumbFile, 85)
                out.release()
            }
            th.release()
        } finally {
            canvas?.release()
        }
        p.save()
    }

    /** Scrive il JPEG con i metadati GPano (e la vista iniziale) in Immagini/PhotoSphere, sostituendo la versione precedente. */
    fun publish(ctx: Context, p: Project): android.net.Uri {
        val fin = p.final ?: throw IllegalStateException("Risultato mancante")
        val raw = p.finalFile.readBytes()
        val xmp = Gpano.xmp(
            fin.fullW, fin.fullH, fin.cropW, fin.cropH, fin.left, fin.top,
            if (p.hasInitial) p.initLon else 0f, if (p.hasInitial) p.initLat else 0f, if (p.hasInitial) p.initHFov else 75f
        )
        val jpeg = Gpano.inject(raw, xmp)
        val old = p.resultUri
        val uri = Saver.saveJpeg(ctx, jpeg, p.name + ".jpg")
        p.resultUri = uri.toString()
        if (old != null && old != p.resultUri) Saver.delete(ctx, old)
        p.save()
        return uri
    }

    /** Riduce la risoluzione se la memoria libera non basta per la fusione (la fusione è in memoria nativa). */
    fun safeWidth(ctx: Context, requested: Int, nShots: Int, fov: Double): Int {
        val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
        val mi = android.app.ActivityManager.MemoryInfo()
        am.getMemoryInfo(mi)
        val avail = mi.availMem.toDouble()
        var w = requested
        while (w > 3072) {
            val wl = 2.0 * atan(tan(fov / 2)) * w / (2.0 * PI) * 1.5
            val work = nShots * wl * wl * 0.75 * 3.0
            val need = work + 110.0 * w * 1024.0
            if (need < avail * 0.75) break
            w = if (w >= 6144) 4096 else 3072
        }
        return w
    }
}
