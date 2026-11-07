package com.marco.photosphere

import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfDouble
import org.opencv.imgproc.Imgproc

object Sharpness {
    /** Nitidezza = varianza del Laplaciano su una versione ridotta (più alta = più nitida). */
    fun measure(path: String): Double {
        val rgb = ShotIO.decodeRgbMat(path, 640)
        val gray = Mat()
        Imgproc.cvtColor(rgb, gray, Imgproc.COLOR_RGB2GRAY)
        val lap = Mat()
        Imgproc.Laplacian(gray, lap, CvType.CV_64F)
        val mean = MatOfDouble()
        val std = MatOfDouble()
        Core.meanStdDev(lap, mean, std)
        val s = std.toArray()
        rgb.release(); gray.release(); lap.release(); mean.release(); std.release()
        return if (s.isNotEmpty()) s[0] * s[0] else 0.0
    }
}
