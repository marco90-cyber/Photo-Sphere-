package com.marco.photosphere

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import androidx.exifinterface.media.ExifInterface
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.Mat
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import kotlin.math.max

object ShotIO {
    /** Decodifica uno scatto già dritto (verticale), con lato lungo <= maxDim. */
    fun decodeUpright(path: String, maxDim: Int): Bitmap {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, o)
        var sample = 1
        while (max(o.outWidth, o.outHeight) / (sample * 2) >= maxDim) sample *= 2
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
        // porta al lato lungo richiesto con una scala esatta
        val big = max(bmp.width, bmp.height)
        if (big > maxDim * 1.05) {
            val sc = maxDim.toFloat() / big
            val r = Bitmap.createScaledBitmap(bmp, (bmp.width * sc).toInt(), (bmp.height * sc).toInt(), true)
            bmp.recycle()
            bmp = r
        }
        return bmp
    }

    /**
     * Decodifica uno scatto in una Mat RGB 8 bit, dritta (verticale), ridotta con filtro INTER_AREA
     * (media sui pixel: niente aliasing) in modo che il lato lungo sia al massimo targetLong.
     */
    fun decodeRgbMat(path: String, targetLong: Int): Mat {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, o)
        var sample = 1
        while (max(o.outWidth, o.outHeight) / (sample * 2) >= targetLong) sample *= 2
        val o2 = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val bmp = BitmapFactory.decodeFile(path, o2) ?: throw IllegalStateException("Scatto illeggibile")
        val rgba = Mat()
        Utils.bitmapToMat(bmp, rgba)
        bmp.recycle()
        var cur = Mat()
        Imgproc.cvtColor(rgba, cur, Imgproc.COLOR_RGBA2RGB)
        rgba.release()

        val orient = ExifInterface(path).getAttributeInt(
            ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL
        )
        var deg = when (orient) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90
            ExifInterface.ORIENTATION_ROTATE_180 -> 180
            ExifInterface.ORIENTATION_ROTATE_270 -> 270
            else -> 0
        }
        if (deg == 0 && cur.cols() > cur.rows()) deg = 90
        val code = when (deg) {
            90 -> Core.ROTATE_90_CLOCKWISE
            180 -> Core.ROTATE_180
            270 -> Core.ROTATE_90_COUNTERCLOCKWISE
            else -> -1
        }
        if (code >= 0) {
            val r = Mat()
            Core.rotate(cur, r, code)
            cur.release()
            cur = r
        }
        val longSide = max(cur.cols(), cur.rows())
        if (longSide > targetLong) {
            val sc = targetLong.toDouble() / longSide
            val rs = Mat()
            Imgproc.resize(cur, rs, Size(), sc, sc, Imgproc.INTER_AREA)
            cur.release()
            cur = rs
        }
        return cur
    }
}
