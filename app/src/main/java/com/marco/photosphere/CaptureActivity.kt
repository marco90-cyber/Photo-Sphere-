package com.marco.photosphere

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CaptureRequest
import android.os.Bundle
import android.os.SystemClock
import android.util.Size
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.Surface
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import org.opencv.android.OpenCVLoader
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.camera2.interop.Camera2CameraControl
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.CaptureRequestOptions
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import java.io.ByteArrayOutputStream
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.PI
import kotlin.math.acos
import kotlin.math.atan
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.tan

class CaptureActivity : AppCompatActivity() {

    private enum class S { PREVIEW, CAPTURING, STITCHING }

    private lateinit var hub: SensorHub
    private lateinit var previewView: PreviewView
    private lateinit var overlay: TargetOverlayView
    private lateinit var status: TextView
    private lateinit var counter: TextView
    private lateinit var shutter: ShutterButton
    private lateinit var guide: SphereGuideView
    private lateinit var frame: View
    private lateinit var rootView: FrameLayout
    private lateinit var btnQuality: TextView
    private lateinit var procView: FrameLayout
    private lateinit var procText: TextView
    private lateinit var procBar: ProgressBar

    private var imageCapture: ImageCapture? = null
    private var camera: Camera? = null
    private var fovLong = Math.toRadians(68.0)

    private var state = S.PREVIEW
    private val targets = ArrayList<Target>()
    private val shots = ArrayList<Shot>()
    private var yaw0 = 0f
    private var busy = false          // finestra di esposizione dopo lo scatto
    private var lockUntil = 0L
    private var pending = 0            // scatti in salvataggio
    private var finishWanted = false
    private var dwellStart = 0L
    private var outWidth = 4096

    private val permLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
            if (ok) startCamera() else {
                Toast.makeText(this, "Serve il permesso della fotocamera", Toast.LENGTH_LONG).show()
                finish()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        hub = SensorHub(this)
        if (!hub.available) {
            Toast.makeText(this, "Sensori di movimento non disponibili su questo telefono", Toast.LENGTH_LONG).show()
            finish()
            return
        }
        val defW = if (Runtime.getRuntime().maxMemory() >= 400L * 1024 * 1024) 6144 else 4096
        outWidth = getSharedPreferences("prefs", MODE_PRIVATE).getInt("outWidth", defW)

        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        rootView = root

        // sfondo "mondo": griglia sul pavimento (l'orizzonte si muove) e foto già scattate
        guide = SphereGuideView(this)
        root.addView(guide, FrameLayout.LayoutParams(-1, -1))

        // finestrella della fotocamera, con la dimensione del suo campo visivo reale
        previewView = PreviewView(this).apply {
            scaleType = PreviewView.ScaleType.FILL_CENTER
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        }
        root.addView(previewView, FrameLayout.LayoutParams(dp(150), dp(200), Gravity.CENTER))
        frame = View(this).apply {
            background = android.graphics.drawable.GradientDrawable().apply {
                setStroke(dp(3), Color.WHITE)
                setColor(Color.TRANSPARENT)
            }
        }
        root.addView(frame, FrameLayout.LayoutParams(dp(150), dp(200), Gravity.CENTER))
        root.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> applyBox() }

        overlay = TargetOverlayView(this)
        root.addView(overlay, FrameLayout.LayoutParams(-1, -1))

        status = pill("Tieni il telefono dritto, all'altezza dell'orizzonte, poi premi il pulsante", 0xCC000000.toInt(), 14f)
        root.addView(status, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply {
            topMargin = dp(48); leftMargin = dp(20); rightMargin = dp(20)
        })

        counter = pill("0 / 0", 0x40FFFFFF, 13f)
        counter.visibility = View.GONE
        root.addView(counter, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply {
            bottomMargin = dp(132)
        })

        val btnExit = pill("Esci", 0x40FFFFFF)
        btnExit.setOnClickListener { finish() }
        root.addView(btnExit, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.START).apply {
            bottomMargin = dp(62); leftMargin = dp(20)
        })

        shutter = ShutterButton(this)
        shutter.setOnClickListener { onMainClick() }
        root.addView(shutter, FrameLayout.LayoutParams(dp(78), dp(78), Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply {
            bottomMargin = dp(40)
        })

        btnQuality = pill(if (outWidth >= 6144) "6K" else "4K", 0x40FFFFFF)
        btnQuality.setOnClickListener {
            outWidth = if (outWidth >= 6144) 4096 else 6144
            btnQuality.text = if (outWidth >= 6144) "6K" else "4K"
            getSharedPreferences("prefs", MODE_PRIVATE).edit().putInt("outWidth", outWidth).apply()
        }
        root.addView(btnQuality, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.END).apply {
            bottomMargin = dp(62); rightMargin = dp(20)
        })

        // schermata di elaborazione
        procView = FrameLayout(this).apply {
            setBackgroundColor(0xEE000000.toInt())
            visibility = View.GONE
            isClickable = true
        }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
        }
        procText = TextView(this).apply {
            setTextColor(Color.WHITE); textSize = 18f; gravity = Gravity.CENTER
            text = "Creo la tua foto 360°…"
        }
        procBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            isIndeterminate = false
        }
        box.addView(procText)
        box.addView(procBar, LinearLayout.LayoutParams(dp(260), -2).apply { topMargin = dp(20) })
        procView.addView(box, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))
        root.addView(procView, FrameLayout.LayoutParams(-1, -1))

        setContentView(root)

        hub.onUpdate = { onSensor() }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            startCamera()
        } else {
            permLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    override fun onResume() {
        super.onResume()
        if (::hub.isInitialized) hub.start()
    }

    override fun onPause() {
        super.onPause()
        if (::hub.isInitialized) hub.stop()
    }

    // ------------------------------------------------------------------ fotocamera

    @OptIn(ExperimentalCamera2Interop::class)
    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            val provider = future.get()
            val ratio = AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY
            val preview = Preview.Builder()
                .setResolutionSelector(ResolutionSelector.Builder().setAspectRatioStrategy(ratio).build())
                .build()
                .also { it.setSurfaceProvider(previewView.surfaceProvider) }
            val capture = ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                .setTargetRotation(Surface.ROTATION_0)
                .setResolutionSelector(
                    ResolutionSelector.Builder()
                        .setAspectRatioStrategy(ratio)
                        .setResolutionStrategy(
                            ResolutionStrategy(Size(3264, 2448), ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER)
                        )
                        .build()
                )
                .build()
            imageCapture = capture
            provider.unbindAll()
            val cam = provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview, capture)
            camera = cam
            computeFov(cam)
            applyBox()
        }, ContextCompat.getMainExecutor(this))
    }

    /** La finestrella ha le proporzioni del campo visivo reale, nella vista virtuale larga. */
    private fun applyBox() {
        val w = rootView.width
        if (w == 0) return
        val fv = w * GUIDE_FOCAL
        val tanLc = tan(fovLong / 2).toFloat()
        val tanSc = tanLc * 0.75f
        val bw = (2f * fv * tanSc).toInt()
        val bh = (2f * fv * tanLc).toInt()
        val lp = previewView.layoutParams
        if (lp.width != bw || lp.height != bh) {
            previewView.layoutParams = FrameLayout.LayoutParams(bw, bh, Gravity.CENTER)
            frame.layoutParams = FrameLayout.LayoutParams(bw, bh, Gravity.CENTER)
        }
        guide.tanS = tanSc
        guide.tanL = tanLc
    }

    /** Campo visivo reale del lato lungo, da lunghezza focale e dimensione fisica del sensore. */
    @OptIn(ExperimentalCamera2Interop::class)
    private fun computeFov(cam: Camera) {
        try {
            val info = Camera2CameraInfo.from(cam.cameraInfo)
            val focal = info.getCameraCharacteristic(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.firstOrNull()
            val size = info.getCameraCharacteristic(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)
            val active = info.getCameraCharacteristic(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
            if (focal != null && size != null && focal > 0f) {
                var wLong = size.width.toDouble()
                if (active != null) {
                    val ar = active.width().toDouble() / active.height()
                    if (ar > 4.0 / 3.0 + 0.01) wLong *= (4.0 / 3.0) / ar
                }
                val f = 2.0 * atan(wLong / (2.0 * focal))
                if (f > Math.toRadians(40.0) && f < Math.toRadians(110.0)) fovLong = f
            }
        } catch (_: Exception) {
        }
    }

    @OptIn(ExperimentalCamera2Interop::class)
    private fun lockExposure(lock: Boolean) {
        val c = camera ?: return
        try {
            Camera2CameraControl.from(c.cameraControl).setCaptureRequestOptions(
                CaptureRequestOptions.Builder()
                    .setCaptureRequestOption(CaptureRequest.CONTROL_AE_LOCK, lock)
                    .setCaptureRequestOption(CaptureRequest.CONTROL_AWB_LOCK, lock)
                    .build()
            )
        } catch (_: Exception) {
        }
    }

    // ------------------------------------------------------------------ logica di scatto

    private fun onMainClick() {
        when (state) {
            S.PREVIEW -> startCapturing()
            S.CAPTURING -> {
                val missing = targets.count { !it.done }
                if (missing > 0) {
                    androidx.appcompat.app.AlertDialog.Builder(this)
                        .setTitle("Mancano $missing punti")
                        .setMessage("Per una foto completa scatta anche i punti rimasti (le zone non fotografate restano vuote). Vuoi terminare comunque?")
                        .setPositiveButton("Termina") { _, _ -> requestFinish() }
                        .setNegativeButton("Continua", null)
                        .show()
                } else {
                    requestFinish()
                }
            }
            S.STITCHING -> {}
        }
    }

    private fun startCapturing() {
        if (imageCapture == null) return
        val m = hub.matrix
        // direzione di partenza = orientamento attuale sul piano orizzontale
        val e = -m[2]
        val n = -m[5]
        val up = -m[8]
        yaw0 = if (kotlin.math.abs(up) > 0.8f) 0f else atan2(e, n)

        targets.clear()
        targets.addAll(buildTargets())
        shots.clear()
        overlay.targets = targets
        overlay.yaw0 = yaw0
        overlay.capturing = true
        lockExposure(true)
        state = S.CAPTURING
        shutter.capturing = true
        guide.clearTiles()
        btnQuality.visibility = View.GONE
        counter.visibility = View.VISIBLE
        updateCounter()
        status.text = "Muovi il telefono: porta il cerchio sui punti, resta fermo"
    }

    /** Griglia di punti con ~28% di sovrapposizione, adattata al campo visivo reale. */
    private fun buildTargets(): List<Target> {
        val fovShort = 2.0 * atan(tan(fovLong / 2) * 0.75)
        val stepPitch = fovLong * 0.72
        val rows = ceil(PI / stepPitch).toInt().coerceAtLeast(2)
        val list = ArrayList<Target>()
        for (k in 0..rows) {
            val p = -PI / 2 + k * PI / rows
            if (k == 0 || k == rows) {
                list.add(Target(0f, p.toFloat()))
            } else {
                val cols = max(1, ceil(2 * PI * cos(p) / (fovShort * 0.72)).toInt())
                val offset = if (k % 2 == 0) 0.0 else 0.5
                for (j in 0 until cols) {
                    list.add(Target(((j + offset) * 2 * PI / cols).toFloat(), p.toFloat()))
                }
            }
        }
        // prima l'orizzonte, poi le file alte, poi quelle basse
        return list.sortedBy { kotlin.math.abs(it.pitch) }
    }

    private fun updateCounter() {
        val done = targets.count { it.done }
        counter.text = "$done / ${targets.size}"
        shutter.progress = if (targets.isEmpty()) 0f else done.toFloat() / targets.size
    }

    private fun onSensor() {
        val m = hub.matrix
        overlay.R = m
        guide.R = m
        guide.invalidate()
        if (state == S.CAPTURING) {
            updateGuide(m)
        }
        overlay.invalidate()
    }

    private fun updateGuide(m: FloatArray) {
        val fx = -m[2]
        val fy = -m[5]
        val fz = -m[8]
        var best = -1
        var bestDot = -2f
        for ((i, t) in targets.withIndex()) {
            if (t.done) continue
            val d = t.dir(yaw0)
            val dot = fx * d[0] + fy * d[1] + fz * d[2]
            if (dot > bestDot) { bestDot = dot; best = i }
        }
        overlay.activeIdx = best
        if (best < 0) {
            requestFinish()
            return
        }
        val angle = acos(bestDot.coerceIn(-1f, 1f))
        val tol = Math.toRadians(5.5).toFloat()
        val now = SystemClock.elapsedRealtime()
        if (now < lockUntil) {
            dwellStart = 0L
            overlay.dwell = 0f
            return
        }
        busy = false
        if (angle < tol && hub.speed < 0.22f) {
            if (dwellStart == 0L) dwellStart = now
            val p = (now - dwellStart) / 250f
            overlay.dwell = p
            if (p >= 1f) {
                dwellStart = 0L
                overlay.dwell = 0f
                capture(targets[best])
            }
        } else {
            dwellStart = 0L
            overlay.dwell = 0f
        }
    }

    private fun capture(t: Target) {
        val ic = imageCapture ?: return
        busy = true
        lockUntil = SystemClock.elapsedRealtime() + 500   // il tempo di esposizione: tieni fermo
        pending++
        t.done = true
        updateCounter()
        previewView.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        status.text = "Fermo…"
        val file = File(cacheDir, "shot_${System.nanoTime()}.jpg")
        val r = hub.matrix.copyOf()
        ic.takePicture(
            ImageCapture.OutputFileOptions.Builder(file).build(),
            ContextCompat.getMainExecutor(this),
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                    shots.add(Shot(file.path, r))
                    pending--
                    Thread {
                        try {
                            val th = ShotIO.decodeUpright(file.path, 240)
                            runOnUiThread { guide.addTile(th, r) }
                        } catch (_: Exception) {
                        }
                    }.start()
                    if (state == S.CAPTURING) status.text = "Perfetto! Vai al punto successivo"
                    if (finishWanted || targets.all { it.done }) requestFinish()
                }

                override fun onError(exception: ImageCaptureException) {
                    pending--
                    t.done = false
                    updateCounter()
                    Toast.makeText(this@CaptureActivity, "Errore di scatto, riprova", Toast.LENGTH_SHORT).show()
                }
            }
        )
    }

    /** Termina appena tutti gli scatti in corso sono stati salvati. */
    private fun requestFinish() {
        if (state != S.CAPTURING) return
        finishWanted = true
        if (pending > 0) {
            status.text = "Salvo l'ultimo scatto…"
            return
        }
        finishCapture()
    }

    // ------------------------------------------------------------------ elaborazione

    /** Riduce la risoluzione se la memoria libera non basta per la fusione (la fusione è in memoria nativa). */
    private fun safeWidth(requested: Int, nShots: Int, fov: Double): Int {
        val am = getSystemService(ACTIVITY_SERVICE) as android.app.ActivityManager
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

    private fun finishCapture() {
        if (state == S.STITCHING) return
        if (shots.isEmpty()) {
            finishWanted = false
            Toast.makeText(this, "Nessuno scatto effettuato", Toast.LENGTH_SHORT).show()
            return
        }
        state = S.STITCHING
        hub.stop()
        overlay.capturing = false
        procView.visibility = View.VISIBLE
        val list = ArrayList(shots)
        val fov = fovLong
        val width = safeWidth(outWidth, list.size, fov)
        val y0 = yaw0

        Thread {
            try {
                if (!OpenCVLoader.initLocal()) throw IllegalStateException("libreria OpenCV non disponibile")
                if (width < outWidth) {
                    runOnUiThread {
                        Toast.makeText(this, "Memoria libera limitata: risoluzione ridotta", Toast.LENGTH_LONG).show()
                    }
                }
                runOnUiThread { procText.text = "Allineo le immagini…" }
                val al = try {
                    Aligner.align(list, fov) { p -> runOnUiThread { procBar.progress = (p * 40).toInt() } }
                } catch (t: Exception) {
                    null
                } catch (t: LinkageError) {
                    null
                }
                val aligned = list.mapIndexed { i, s -> Shot(s.path, al?.R?.get(i) ?: s.R) }
                runOnUiThread { procText.text = "Unisco le immagini…"; procBar.progress = 40 }
                val res: StitchResult = try {
                    Stitcher(width, fov, al?.sf ?: 1f, al?.gains, al?.vig).run(aligned, y0) { p ->
                        runOnUiThread { procBar.progress = 40 + (p * 54).toInt() }
                    }
                } catch (e: Exception) {
                    // metodo alternativo, più semplice ma sicuro
                    runOnUiThread { procText.text = "Uso il metodo alternativo…"; procBar.progress = 45 }
                    val lr = LegacyStitcher(min(width, 4096), fov, al?.sf ?: 1f, al?.gains, al?.vig).run(aligned, y0) { p ->
                        runOnUiThread { procBar.progress = 45 + (p * 49).toInt() }
                    }
                    val baos = ByteArrayOutputStream(8 * 1024 * 1024)
                    lr.bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 93, baos)
                    lr.bmp.recycle()
                    StitchResult(baos.toByteArray(), lr.fullW, lr.fullH, lr.cropW, lr.cropH, lr.left, lr.top, 1f)
                }
                runOnUiThread { procText.text = "Salvo la foto…"; procBar.progress = 96 }
                val jpeg = Gpano.inject(
                    res.jpeg,
                    Gpano.xmp(res.fullW, res.fullH, res.cropW, res.cropH, res.left, res.top)
                )
                val name = "PANO_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + ".jpg"
                val uri = Saver.saveJpeg(this, jpeg, name)
                list.forEach { File(it.path).delete() }
                runOnUiThread {
                    val pct = (res.coverage * 100).toInt()
                    if (pct < 97) {
                        Toast.makeText(
                            this,
                            "Copertura $pct%: le zone non fotografate restano scure",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                    startActivity(Intent(this, ViewerActivity::class.java).setData(uri))
                    finish()
                }
            } catch (oom: OutOfMemoryError) {
                runOnUiThread {
                    Toast.makeText(this, "Memoria insufficiente: scegli 4K e riprova", Toast.LENGTH_LONG).show()
                    finish()
                }
            } catch (e: Exception) {
                runOnUiThread {
                    Toast.makeText(this, "Errore: " + e.message, Toast.LENGTH_LONG).show()
                    finish()
                }
            }
        }.start()
    }

    override fun onDestroy() {
        super.onDestroy()
        if (state != S.STITCHING) {
            shots.forEach { File(it.path).delete() }
        }
    }
}
