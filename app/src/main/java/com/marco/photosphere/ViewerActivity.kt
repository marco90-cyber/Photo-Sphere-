package com.marco.photosphere

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.hardware.SensorManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.Surface
import android.widget.FrameLayout
import android.widget.ProgressBar
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

class ViewerActivity : AppCompatActivity() {

    private lateinit var root: FrameLayout
    private var sphere: SphereView? = null
    private var hub: SensorHub? = null
    private var gyroOn = false
    private var uri: Uri? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val u = intent.data
        if (u == null) {
            finish()
            return
        }
        uri = u

        WindowCompat.setDecorFitsSystemWindows(window, false)
        root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        setContentView(root)
        WindowInsetsControllerCompat(window, root).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }

        val spinner = ProgressBar(this)
        root.addView(spinner, FrameLayout.LayoutParams(dp(48), dp(48), Gravity.CENTER))

        Thread {
            try {
                val header = Gpano.readHeader(this, u)
                val pano = header?.let { Gpano.parse(it) }
                val bmp = decode(u)
                val geo = Geo.from(pano, bmp.width, bmp.height)
                runOnUiThread { show(bmp, geo, spinner) }
            } catch (e: Throwable) {
                runOnUiThread {
                    Toast.makeText(this, "Impossibile aprire la foto", Toast.LENGTH_LONG).show()
                    finish()
                }
            }
        }.start()
    }

    private fun decode(u: Uri): Bitmap {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        contentResolver.openInputStream(u)?.use { BitmapFactory.decodeStream(it, null, o) }
        var sample = 1
        while (o.outWidth / sample > 8192 || o.outHeight / sample > 8192) sample *= 2
        val o2 = BitmapFactory.Options().apply { inSampleSize = sample }
        return contentResolver.openInputStream(u)?.use { BitmapFactory.decodeStream(it, null, o2) }
            ?: throw IllegalStateException("decode")
    }

    private fun show(bmp: Bitmap, geo: Geo, spinner: android.view.View) {
        root.removeView(spinner)
        val v = SphereView(this, bmp, geo)
        sphere = v
        root.addView(v, 0, FrameLayout.LayoutParams(-1, -1))

        val back = pill("←  Indietro")
        back.setOnClickListener { finish() }
        root.addView(back, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.START).apply {
            topMargin = dp(36); leftMargin = dp(14)
        })

        val share = pill("Condividi")
        share.setOnClickListener {
            val i = Intent(Intent.ACTION_SEND).apply {
                type = "image/jpeg"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(i, "Condividi foto 360°"))
        }
        root.addView(share, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.END).apply {
            topMargin = dp(36); rightMargin = dp(14)
        })

        val h = SensorHub(this)
        if (h.available) {
            hub = h
            val gyro = pill("Giroscopio: OFF")
            gyro.setOnClickListener {
                gyroOn = !gyroOn
                gyro.text = if (gyroOn) "Giroscopio: ON" else "Giroscopio: OFF"
                gyro.setPillColor(if (gyroOn) 0xFF1A73E8.toInt() else 0x99000000.toInt())
                v.renderer.sensorMode = gyroOn
                v.renderer.yaw = 0f
                v.renderer.pitch = 0f
                v.renderer.yawVel = 0f
                v.renderer.pitchVel = 0f
                if (gyroOn) h.start() else h.stop()
            }
            root.addView(gyro, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply {
                bottomMargin = dp(36)
            })
            h.onUpdate = { v.renderer.sensorR = remap(h.matrix) }
        }
    }

    @Suppress("DEPRECATION")
    private fun displayRotation(): Int =
        if (Build.VERSION.SDK_INT >= 30) (display?.rotation ?: Surface.ROTATION_0)
        else windowManager.defaultDisplay.rotation

    private fun remap(r: FloatArray): FloatArray {
        val out = FloatArray(9)
        when (displayRotation()) {
            Surface.ROTATION_90 -> SensorManager.remapCoordinateSystem(r, SensorManager.AXIS_Y, SensorManager.AXIS_MINUS_X, out)
            Surface.ROTATION_180 -> SensorManager.remapCoordinateSystem(r, SensorManager.AXIS_MINUS_X, SensorManager.AXIS_MINUS_Y, out)
            Surface.ROTATION_270 -> SensorManager.remapCoordinateSystem(r, SensorManager.AXIS_MINUS_Y, SensorManager.AXIS_X, out)
            else -> r.copyInto(out)
        }
        return out
    }

    override fun onResume() {
        super.onResume()
        sphere?.onResume()
        if (gyroOn) hub?.start()
    }

    override fun onPause() {
        super.onPause()
        sphere?.onPause()
        hub?.stop()
    }
}
