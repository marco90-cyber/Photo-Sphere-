package com.marco.photosphere

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Handler
import android.os.Looper
import android.os.Bundle
import android.os.SystemClock
import android.util.Size
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
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
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.camera2.interop.CaptureRequestOptions
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.Camera
import androidx.camera.core.CameraInfo
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
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
import java.util.concurrent.TimeUnit
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
    private lateinit var focusRing: FocusRingView
    private lateinit var evSlider: EvSliderView
    private val hideEv = Runnable { if (::evSlider.isInitialized) evSlider.visibility = View.GONE }
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
    private var yaw0 = 0f
    private var busy = false          // finestra di esposizione dopo lo scatto
    private var lockUntil = 0L
    private var pending = 0            // scatti in salvataggio
    private var finishWanted = false
    private var dwellStart = 0L
    private var outWidth = 4096
    private val prefs by lazy { getSharedPreferences("prefs", MODE_PRIVATE) }

    // scatti presi finora (per annullare/rifare e per il controllo di nitidezza)
    private class Taken(val target: Target, val file: File, val R: FloatArray) {
        var sharp = 0.0
    }
    private val taken = ArrayList<Taken>()
    private val sharpValues = ArrayList<Double>()
    private var handedOff = false
    private var paused = false
    private var opencvOk = false

    // messa a fuoco e fermo durante l'esposizione
    @Volatile private var afState: Int? = null
    @Volatile private var exposureNs = 0L
    private var focusDone = false
    private var focusStarted = false
    private var peakSpeed = 0f
    private var exposing = false
    private val ui = Handler(Looper.getMainLooper())
    private var tone: ToneGenerator? = null
    private lateinit var btnUndo: TextView
    private lateinit var btnPause: TextView
    private var countdown = 0

    private val notifLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { _ -> startCapturing() }

    private val permLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
            if (ok) ensureCamera() else showCameraDenied()
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
        Thread { opencvOk = try { OpenCVLoader.initLocal() } catch (_: Throwable) { false } }.start()
        try { tone = ToneGenerator(AudioManager.STREAM_NOTIFICATION, 70) } catch (_: Throwable) {}
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
        previewView.setOnTouchListener { v, ev ->
            if (ev.actionMasked == MotionEvent.ACTION_UP) {
                v.performClick()
                focusAt(ev.x, ev.y)
            }
            true
        }
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

        status = pill("Tocca l'immagine per fuoco ed esposizione. Poi tieni il telefono dritto e premi il pulsante", 0xCC000000.toInt(), 14f)
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

        btnUndo = pill("↩ Annulla", 0x66000000, 13f)
        btnUndo.visibility = View.GONE
        btnUndo.setOnClickListener { undoLast() }
        root.addView(btnUndo, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.END).apply {
            bottomMargin = dp(62); rightMargin = dp(20)
        })
        btnPause = pill("⏸ Pausa", 0x66000000, 13f)
        btnPause.visibility = View.GONE
        btnPause.setOnClickListener { togglePause() }
        root.addView(btnPause, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.END).apply {
            bottomMargin = dp(118); rightMargin = dp(20)
        })

        focusRing = FocusRingView(this)
        root.addView(focusRing, FrameLayout.LayoutParams(-1, -1))

        evSlider = EvSliderView(this)
        evSlider.visibility = View.GONE
        evSlider.onChange = { p -> setEv(p) }
        evSlider.onTouching = { evSlider.removeCallbacks(hideEv); evSlider.postDelayed(hideEv, 4000) }
        root.addView(evSlider, FrameLayout.LayoutParams(dp(46), dp(190), Gravity.END or Gravity.CENTER_VERTICAL).apply {
            rightMargin = dp(8)
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
            ensureCamera()
        } else {
            permLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    private var camStarted = false

    private fun ensureCamera() {
        if (camStarted) return
        camStarted = true
        startCamera()
    }

    private fun showCameraDenied() {
        if (isFinishing) return
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Permesso fotocamera negato")
            .setMessage("Per scattare foto a 360° serve la fotocamera. Puoi concederla dalle impostazioni dell'app.")
            .setPositiveButton("Impostazioni") { _, _ ->
                startActivity(Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    android.net.Uri.fromParts("package", packageName, null)))
            }
            .setNegativeButton("Indietro") { _, _ -> finish() }
            .setCancelable(false)
            .show()
    }

    override fun onResume() {
        super.onResume()
        if (::hub.isInitialized) {
            hub.start()
            if (!camStarted && ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) ensureCamera()
        }
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
            val pb = Preview.Builder()
                .setResolutionSelector(ResolutionSelector.Builder().setAspectRatioStrategy(ratio).build())
            // stato della messa a fuoco e tempo di esposizione, aggiornati a ogni fotogramma
            Camera2Interop.Extender(pb).setSessionCaptureCallback(object : CameraCaptureSession.CaptureCallback() {
                override fun onCaptureCompleted(
                    session: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult
                ) {
                    afState = result.get(CaptureResult.CONTROL_AF_STATE)
                    exposureNs = result.get(CaptureResult.SENSOR_EXPOSURE_TIME) ?: 0L
                }
            })
            val preview = pb.build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
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
            val cam = provider.bindToLifecycle(this, selectorFor(), preview, capture)
            camera = cam
            computeFov(cam)
            applyBox()
        }, ContextCompat.getMainExecutor(this))
    }

    /** Obiettivo principale oppure, se richiesto e presente, il più grandangolare. */
    @OptIn(ExperimentalCamera2Interop::class)
    private fun selectorFor(): CameraSelector {
        if (prefs.getInt("lens", 0) == 1) {
            try {
                return CameraSelector.Builder()
                    .requireLensFacing(CameraSelector.LENS_FACING_BACK)
                    .addCameraFilter { infos ->
                        val best = infos.maxByOrNull { fovOf(it) }
                        if (best != null) listOf(best) else infos
                    }
                    .build()
            } catch (_: Exception) {
            }
        }
        return CameraSelector.DEFAULT_BACK_CAMERA
    }

    @OptIn(ExperimentalCamera2Interop::class)
    private fun fovOf(info: CameraInfo): Double {
        return try {
            val ci = Camera2CameraInfo.from(info)
            val focal = ci.getCameraCharacteristic(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.firstOrNull()
            val size = ci.getCameraCharacteristic(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)
            if (focal != null && size != null && focal > 0f) 2.0 * atan(size.width.toDouble() / (2.0 * focal)) else 0.0
        } catch (_: Exception) {
            0.0
        }
    }

    /** Tocco sull'immagine: messa a fuoco e, prima dell'inizio, anche esposizione e bilanciamento del bianco. */
    private fun focusAt(x: Float, y: Float) {
        val cam = camera ?: return
        if (state == S.STITCHING) return
        val preview = state == S.PREVIEW
        try {
            val point = previewView.meteringPointFactory.createPoint(x, y)
            val flags = if (preview) {
                FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AE or FocusMeteringAction.FLAG_AWB
            } else {
                // durante lo scatto l'esposizione resta bloccata: tutti gli scatti devono avere la stessa luce
                FocusMeteringAction.FLAG_AF
            }
            val b = FocusMeteringAction.Builder(point, flags)
            if (preview) b.disableAutoCancel() else b.setAutoCancelDuration(3, TimeUnit.SECONDS)
            focusRing.show(previewView.left + x, previewView.top + y)
            val fut = cam.cameraControl.startFocusAndMetering(b.build())
            fut.addListener({
                try {
                    focusRing.result(fut.get().isFocusSuccessful)
                } catch (_: Exception) {
                    focusRing.result(false)
                }
            }, ContextCompat.getMainExecutor(this))
            if (preview) showEvSlider()
        } catch (_: Exception) {
        }
    }

    private fun showEvSlider() {
        val cam = camera ?: return
        val es = cam.cameraInfo.exposureState
        if (!es.isExposureCompensationSupported) return
        val r = es.exposureCompensationRange
        val span = r.upper - r.lower
        if (span <= 0) return
        evSlider.progress = (es.exposureCompensationIndex - r.lower).toFloat() / span
        evSlider.visibility = View.VISIBLE
        evSlider.removeCallbacks(hideEv)
        evSlider.postDelayed(hideEv, 4000)
    }

    private fun setEv(p: Float) {
        val cam = camera ?: return
        val r = cam.cameraInfo.exposureState.exposureCompensationRange
        val idx = (r.lower + p * (r.upper - r.lower)).toInt().coerceIn(r.lower, r.upper)
        try {
            cam.cameraControl.setExposureCompensationIndex(idx)
        } catch (_: Exception) {
        }
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
            S.PREVIEW -> {
                if (counting) {            // secondo tocco durante il conto alla rovescia: annulla
                    counting = false
                    ui.removeCallbacksAndMessages(null)
                    status.text = "Tocca l'immagine per fuoco ed esposizione. Poi tieni il telefono dritto e premi il pulsante"
                    return
                }
                if (android.os.Build.VERSION.SDK_INT >= 33 && !prefs.getBoolean("askedNotif", false) &&
                    ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
                ) {
                    prefs.edit().putBoolean("askedNotif", true).apply()
                    notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    return
                }
                startWithTimer()
            }
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

    private var counting = false

    /** Timer di avvio (utile col treppiede o per allontanare la mano dal telefono). */
    private fun startWithTimer() {
        val t = prefs.getInt("timer", 0)
        if (t <= 0) { startCapturing(); return }
        counting = true
        countdown = t
        tickCountdown()
    }

    private fun tickCountdown() {
        if (!counting) return
        if (countdown <= 0) {
            counting = false
            startCapturing()
            return
        }
        status.text = "Si parte tra $countdown… (tocca per annullare)"
        tone?.startTone(ToneGenerator.TONE_PROP_BEEP, 80)
        countdown--
        ui.postDelayed({ tickCountdown() }, 1000)
    }

    private fun startCapturing() {
        if (imageCapture == null || state != S.PREVIEW) return
        val m = hub.matrix
        // direzione di partenza = orientamento attuale sul piano orizzontale
        val e = -m[2]
        val n = -m[5]
        val up = -m[8]
        yaw0 = if (kotlin.math.abs(up) > 0.8f) 0f else atan2(e, n)

        targets.clear()
        targets.addAll(buildTargets())
        taken.clear()
        sharpValues.clear()
        paused = false
        finishWanted = false
        overlay.targets = targets
        overlay.yaw0 = yaw0
        overlay.capturing = true
        overlay.showMap = prefs.getBoolean("minimap", true)
        overlay.showLevel = prefs.getBoolean("level", true)
        if (prefs.getBoolean("lockAe", true)) lockExposure(true)
        state = S.CAPTURING
        shutter.capturing = true
        evSlider.visibility = View.GONE
        guide.clearTiles()
        btnQuality.visibility = View.GONE
        btnUndo.visibility = View.VISIBLE
        btnPause.visibility = View.VISIBLE
        btnPause.text = "⏸ Pausa"
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
        if (exposing) peakSpeed = max(peakSpeed, hub.speed)
        if (state == S.CAPTURING) {
            updateGuide(m)
        }
        overlay.invalidate()
    }

    /** Lancia la messa a fuoco al centro: lo scatto parte solo a fuoco raggiunto (o dopo un'attesa massima). */
    private fun startAutoFocus() {
        val cam = camera
        if (cam == null) { focusDone = true; return }
        try {
            val pt = androidx.camera.core.SurfaceOrientedMeteringPointFactory(1f, 1f).createPoint(0.5f, 0.5f)
            val action = FocusMeteringAction.Builder(pt, FocusMeteringAction.FLAG_AF)
                .setAutoCancelDuration(2, TimeUnit.SECONDS)
                .build()
            val f = cam.cameraControl.startFocusAndMetering(action)
            f.addListener({ focusDone = true }, ContextCompat.getMainExecutor(this))
        } catch (_: Exception) {
            focusDone = true
        }
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
        if (paused) {
            dwellStart = 0L
            overlay.dwell = 0f
            overlay.moving = false
            return
        }
        if (best < 0) {
            if (pending == 0) requestFinish()
            return
        }
        val angle = acos(bestDot.coerceIn(-1f, 1f))
        val tol = Math.toRadians(5.5).toFloat()
        val now = SystemClock.elapsedRealtime()
        if (now < lockUntil) {
            dwellStart = 0L
            overlay.dwell = 0f
            overlay.moving = false
            return
        }
        busy = false
        val still = hub.speed < 0.22f
        overlay.moving = angle < tol * 2.5f && !still
        if (angle < tol && still) {
            if (dwellStart == 0L) {
                dwellStart = now
                focusDone = false
                if (prefs.getBoolean("waitFocus", true)) startAutoFocus() else focusDone = true
            }
            // con poca luce l'esposizione è lunga: serve restare fermi più a lungo
            val need = max(250f, min(1200f, exposureNs / 1e6f + 150f))
            val p = (now - dwellStart) / need
            overlay.dwell = p
            val focusOk = focusDone || (now - dwellStart) > 1800L
            overlay.waiting = p >= 1f && !focusOk
            if (p >= 1f && focusOk) {
                dwellStart = 0L
                overlay.dwell = 0f
                overlay.waiting = false
                capture(targets[best])
            } else if (p >= 1f) {
                status.text = "Metto a fuoco…"
            }
        } else {
            dwellStart = 0L
            overlay.dwell = 0f
            overlay.waiting = false
        }
    }

    private fun capture(t: Target) {
        val ic = imageCapture ?: return
        busy = true
        val expMs = exposureNs / 1_000_000L
        lockUntil = SystemClock.elapsedRealtime() + max(500L, min(1500L, expMs + 350L))   // il tempo di esposizione: tieni fermo
        pending++
        t.done = true
        updateCounter()
        if (prefs.getBoolean("haptic", true)) previewView.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        if (prefs.getBoolean("sound", true)) tone?.startTone(ToneGenerator.TONE_PROP_BEEP, 60)
        peakSpeed = hub.speed
        exposing = true
        status.text = "Fermo…"
        val file = File(cacheDir, "shot_${System.nanoTime()}.jpg")
        val r = hub.matrix.copyOf()
        ic.takePicture(
            ImageCapture.OutputFileOptions.Builder(file).build(),
            ContextCompat.getMainExecutor(this),
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                    exposing = false
                    val moved = peakSpeed
                    val tk = Taken(t, file, r)
                    // controllo di nitidezza fuori dal thread principale
                    Thread {
                        var sharp = 0.0
                        if (opencvOk) {
                            try { sharp = Sharpness.measure(file.path) } catch (_: Throwable) {}
                        }
                        runOnUiThread { afterSaved(tk, sharp, moved) }
                    }.start()
                }

                override fun onError(exception: ImageCaptureException) {
                    exposing = false
                    pending--
                    t.done = false
                    updateCounter()
                    Toast.makeText(this@CaptureActivity, "Errore di scatto, riprova", Toast.LENGTH_SHORT).show()
                }
            }
        )
    }

    private fun median(v: List<Double>): Double {
        if (v.isEmpty()) return 0.0
        val s = v.sorted()
        return s[s.size / 2]
    }

    /** Scatto salvato: se è mosso o sfocato rispetto agli altri si rifà una volta, altrimenti si tiene. */
    private fun afterSaved(tk: Taken, sharp: Double, moved: Float) {
        tk.sharp = sharp
        if (state != S.CAPTURING) {
            pending--
            tk.file.delete()
            return
        }
        val med = if (sharpValues.size >= 3) median(sharpValues) else 0.0
        val blurry = sharp > 0.0 && med > 0.0 &&
            (sharp < 0.30 * med || (moved > 0.35f && sharp < 0.65 * med))
        if (blurry && tk.target.tries < 1 && prefs.getBoolean("autoRetake", true)) {
            tk.target.tries++
            tk.target.done = false
            tk.file.delete()
            pending--
            finishWanted = false
            updateCounter()
            lockUntil = SystemClock.elapsedRealtime() + 400
            status.text = "Foto mossa: la rifaccio, resta fermo"
            if (prefs.getBoolean("haptic", true)) previewView.performHapticFeedback(HapticFeedbackConstants.REJECT)
            return
        }
        taken.add(tk)
        if (sharp > 0.0) sharpValues.add(sharp)
        pending--
        Thread {
            try {
                val th = ShotIO.decodeUpright(tk.file.path, 240)
                runOnUiThread { guide.addTile(th, tk.R) }
            } catch (_: Exception) {
            }
        }.start()
        status.text = "Perfetto! Vai al punto successivo"
        if (finishWanted || targets.all { it.done }) requestFinish()
    }

    /** Toglie l'ultimo scatto e lo riporta tra i punti da fare. */
    private fun undoLast() {
        if (state != S.CAPTURING || taken.isEmpty() || pending > 0) return
        val tk = taken.removeAt(taken.size - 1)
        tk.file.delete()
        tk.target.done = false
        sharpValues.remove(tk.sharp)
        guide.removeTile(tk.R)
        finishWanted = false
        updateCounter()
        status.text = "Ultimo scatto annullato: rifallo"
    }

    private fun togglePause() {
        if (state != S.CAPTURING) return
        paused = !paused
        btnPause.text = if (paused) "▶ Riprendi" else "⏸ Pausa"
        status.text = if (paused) "In pausa: gli scatti automatici sono fermi" else "Muovi il telefono: porta il cerchio sui punti, resta fermo"
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

    /** Salva gli scatti in un progetto e affida l'unione al servizio in background (con notifica). */
    private fun finishCapture() {
        if (state == S.STITCHING) return
        if (taken.isEmpty()) {
            finishWanted = false
            Toast.makeText(this, "Nessuno scatto effettuato", Toast.LENGTH_SHORT).show()
            return
        }
        state = S.STITCHING
        hub.stop()
        overlay.capturing = false
        procText.text = "Salvo gli scatti…"
        procView.visibility = View.VISIBLE
        val list = ArrayList(taken)
        val fov = fovLong
        val y0 = yaw0
        val w = outWidth
        Thread {
            try {
                val p = Projects.create(this)
                p.fovLong = fov
                p.yaw0 = y0
                p.outW = w
                p.keepShots = prefs.getBoolean("keepShots", true)
                for ((i, t) in list.withIndex()) {
                    val dst = File(p.dir, "s$i.jpg")
                    if (!t.file.renameTo(dst)) {
                        t.file.copyTo(dst, overwrite = true)
                        t.file.delete()
                    }
                    p.shots.add(ShotRec(dst.name, t.R).also { it.sharp = t.sharp.toFloat() })
                }
                p.save()
                handedOff = true
                StitchService.enqueue(this, p.id, Pipeline.FULL)
                runOnUiThread {
                    Toast.makeText(this, "Unisco le immagini in background: ti avviso con una notifica", Toast.LENGTH_LONG).show()
                    startActivity(Intent(this, ProjectsActivity::class.java))
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
        ui.removeCallbacksAndMessages(null)
        try { tone?.release() } catch (_: Exception) {}
        if (!handedOff) {
            taken.forEach { it.file.delete() }
        }
    }
}
