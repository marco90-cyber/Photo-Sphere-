package com.marco.photosphere

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.net.Uri
import android.os.Bundle
import android.util.LruCache
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.core.content.FileProvider
import org.opencv.android.OpenCVLoader
import org.opencv.core.Mat
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import java.io.File
import java.util.concurrent.Executors
import kotlin.math.atan
import kotlin.math.max
import kotlin.math.min
import kotlin.math.tan

/**
 * Editor di un panorama: scatti (escludi, elimina, sposta), toppa (esporta, modifica altrove, reimporta),
 * livellamento dell'orizzonte, vista iniziale e proiezioni creative.
 */
class EditorActivity : AppCompatActivity() {

    private lateinit var p: Project
    private lateinit var content: FrameLayout
    private lateinit var tabsRow: LinearLayout
    private lateinit var jobOverlay: FrameLayout
    private lateinit var jobText: TextView
    private lateinit var jobBar: ProgressBar
    private val exec = Executors.newSingleThreadExecutor()
    private val previewExec = Executors.newSingleThreadExecutor()
    private var tab = 0
    private var waitingJob = false
    private val listener: () -> Unit = { onJobsChanged() }
    private val tabNames = listOf("Scatti", "Toppa", "Orizzonte", "Vista", "Creativo")

    // visualizzatore GL
    private var sphere: SphereView? = null
    private val glBitmaps = HashMap<Int, Bitmap>()
    private var lastYaw = 0f
    private var lastPitch = 0f
    private var lastFov = 80f

    private val thumbCache = object : LruCache<String, Bitmap>(24 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }

    private var pendingImportRec: PatchRec? = null
    private val pickLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) importPatch(uri) }

    // ------------------------------------------------------------------ ciclo di vita

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val id = intent.getStringExtra("id")
        val pr = id?.let { Projects.get(this, it) }
        if (pr == null || pr.base == null) {
            Toast.makeText(this, "Progetto non disponibile", Toast.LENGTH_LONG).show()
            finish()
            return
        }
        p = pr
        try { OpenCVLoader.initLocal() } catch (_: Throwable) {}
        window.statusBarColor = Md.bg(this)
        window.navigationBarColor = Md.bg(this)

        val root = FrameLayout(this).apply { setBackgroundColor(Md.bg(this@EditorActivity)) }
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        val head = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(36), dp(16), dp(8))
        }
        head.addView(ImageView(this).apply {
            setImageResource(R.drawable.ic_back)
            setColorFilter(Md.text(this@EditorActivity))
            setPadding(dp(10), dp(10), dp(10), dp(10))
            background = roundBg(Md.card(this@EditorActivity), 24)
            setOnClickListener { finish() }
        }, LinearLayout.LayoutParams(dp(48), dp(48)))
        head.addView(TextView(this).apply {
            text = "Modifica"
            setTextColor(Md.text(context)); textSize = 22f
            setPadding(dp(16), 0, 0, 0)
        }, LinearLayout.LayoutParams(0, -2, 1f))
        head.addView(mdButton("Condividi", false).apply {
            setPadding(dp(16), dp(8), dp(16), dp(8))
            setOnClickListener { shareResult() }
        })
        col.addView(head)

        content = FrameLayout(this)
        col.addView(content, LinearLayout.LayoutParams(-1, 0, 1f))

        val hs = HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false }
        tabsRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(8), dp(8), dp(8), dp(12))
        }
        hs.addView(tabsRow)
        col.addView(hs, LinearLayout.LayoutParams(-1, -2))
        root.addView(col, FrameLayout.LayoutParams(-1, -1))

        // schermata di lavoro in corso
        jobOverlay = FrameLayout(this).apply {
            setBackgroundColor(0xEE000000.toInt())
            visibility = View.GONE
            isClickable = true
        }
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER }
        jobText = TextView(this).apply { setTextColor(0xFFFFFFFF.toInt()); textSize = 18f; gravity = Gravity.CENTER; text = "Elaboro…" }
        jobBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { max = 100 }
        box.addView(jobText)
        box.addView(jobBar, LinearLayout.LayoutParams(dp(260), -2).apply { topMargin = dp(20) })
        box.addView(TextView(this).apply {
            text = "Puoi uscire: continua in background e ti avviso con una notifica."
            setTextColor(0xFFBBBBBB.toInt()); textSize = 12f; gravity = Gravity.CENTER
            setPadding(dp(24), dp(14), dp(24), 0)
        })
        jobOverlay.addView(box, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))
        root.addView(jobOverlay, FrameLayout.LayoutParams(-1, -1))
        setContentView(root)

        buildTabs()
        selectTab(0)
        if (Jobs.state(p.id) != null) { waitingJob = true; onJobsChanged() }
    }

    override fun onResume() {
        super.onResume()
        Jobs.addListener(listener)
        sphere?.onResume()
    }

    override fun onPause() {
        super.onPause()
        Jobs.removeListener(listener)
        sphere?.onPause()
    }

    override fun onDestroy() {
        super.onDestroy()
        exec.shutdown()
        previewExec.shutdown()
        glBitmaps.clear()
    }

    // ------------------------------------------------------------------ schede

    private fun buildTabs() {
        tabsRow.removeAllViews()
        for ((i, n) in tabNames.withIndex()) {
            val sel = i == tab
            tabsRow.addView(TextView(this).apply {
                text = n
                textSize = 14f
                gravity = Gravity.CENTER
                setTypeface(typeface, if (sel) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
                setTextColor(if (sel) Md.onPrimary(context) else Md.onContainer(context))
                background = roundBg(if (sel) Md.primary(context) else Md.container(context), 20)
                setPadding(dp(18), dp(10), dp(18), dp(10))
                setOnClickListener { if (!waitingJob) selectTab(i) }
            }, LinearLayout.LayoutParams(-2, -2).apply { rightMargin = dp(8) })
        }
    }

    private fun selectTab(i: Int) {
        saveView()
        tab = i
        buildTabs()
        sphere?.onPause()
        sphere = null
        content.removeAllViews()
        when (i) {
            0 -> tabShots()
            1 -> tabPatch()
            2 -> tabLevel()
            3 -> tabView()
            else -> tabCreative()
        }
    }

    private fun saveView() {
        sphere?.let {
            lastYaw = it.renderer.yaw
            lastPitch = it.renderer.pitch
            lastFov = it.renderer.fov
        }
    }

    // ------------------------------------------------------------------ componenti comuni

    private fun small(text: String, filled: Boolean = false, onClick: () -> Unit): TextView =
        mdButton(text, filled).apply {
            setPadding(dp(14), dp(9), dp(14), dp(9))
            textSize = 14f
            setOnClickListener { onClick() }
        }

    private class Sl(val view: View, val set: (Float) -> Unit)

    private fun slider(title: String, min: Float, max: Float, init: Float, unit: String, onChange: (Float) -> Unit): Sl {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, dp(4), 0, dp(4)) }
        val tv = TextView(this).apply { setTextColor(Md.sub(context)); textSize = 13f }
        val sb = SeekBar(this).apply {
            this.max = 1000
            progressTintList = ColorStateList.valueOf(Md.primary(context))
            thumbTintList = ColorStateList.valueOf(Md.primary(context))
        }
        fun show(v: Float) { tv.text = title + ": " + String.format(java.util.Locale.ITALY, "%.1f", v) + unit }
        fun toVal(pr: Int) = min + pr / 1000f * (max - min)
        fun toProg(v: Float) = (((v - min) / (max - min)) * 1000f).toInt().coerceIn(0, 1000)
        sb.progress = toProg(init)
        show(init)
        sb.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar, pr: Int, fromUser: Boolean) {
                val v = toVal(pr)
                show(v)
                if (fromUser) onChange(v)
            }
            override fun onStartTrackingTouch(s: SeekBar) {}
            override fun onStopTrackingTouch(s: SeekBar) {}
        })
        box.addView(tv)
        box.addView(sb)
        return Sl(box) { v -> sb.progress = toProg(v); show(v) }
    }

    private fun panel(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = roundBg(Md.card(this@EditorActivity), 24)
        setPadding(dp(16), dp(12), dp(16), dp(12))
    }

    private fun chip(text: String, sel: Boolean, onClick: () -> Unit): TextView = TextView(this).apply {
        this.text = text
        textSize = 13f
        gravity = Gravity.CENTER
        setTextColor(if (sel) Md.onPrimary(context) else Md.onContainer(context))
        background = roundBg(if (sel) Md.primary(context) else Md.container(context), 18)
        setPadding(dp(14), dp(7), dp(14), dp(7))
        setOnClickListener { onClick() }
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()

    private fun shareResult() {
        val u = p.resultUri?.let { Uri.parse(it) }
            ?: FileProvider.getUriForFile(this, "$packageName.files", p.finalFile)
        val i = Intent(Intent.ACTION_SEND).apply {
            type = "image/jpeg"
            putExtra(Intent.EXTRA_STREAM, u)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(i, "Condividi foto 360°"))
    }

    // ------------------------------------------------------------------ lavori in background

    private fun startJob(mode: Int) {
        if (Jobs.state(p.id) != null) return
        p.save()
        waitingJob = true
        StitchService.enqueue(this, p.id, mode)
        onJobsChanged()
    }

    private fun onJobsChanged() {
        val st = Jobs.state(p.id)
        if (st != null) {
            jobOverlay.visibility = View.VISIBLE
            jobText.text = if (st.queued) "In coda…" else st.message
            jobBar.progress = (st.progress * 100).toInt()
        } else if (waitingJob) {
            waitingJob = false
            jobOverlay.visibility = View.GONE
            reloadProject()
        }
    }

    private fun reloadProject() {
        exec.execute {
            val np = Projects.get(this, p.id)
            runOnUiThread {
                if (np != null) p = np
                glBitmaps.clear()
                thumbCache.evictAll()
                p.lastError?.let { toast("Non riuscita: $it") }
                selectTab(tab)
            }
        }
    }

    // ------------------------------------------------------------------ visualizzatore GL

    /** kind 0 = prima del livellamento (base + toppe), 1 = risultato finale. */
    private fun ensureGl(kind: Int, host: FrameLayout, ready: (SphereView) -> Unit) {
        val spinner = ProgressBar(this)
        host.addView(spinner, FrameLayout.LayoutParams(dp(48), dp(48), Gravity.CENTER))
        exec.execute {
            try {
                var bmp = glBitmaps[kind]
                val info = if (kind == 0) p.pre ?: p.base else p.final ?: p.pre ?: p.base
                val f = if (kind == 0 && p.preFile.exists()) p.preFile else if (kind == 1 && p.finalFile.exists()) p.finalFile else p.baseFile
                if (bmp == null) {
                    val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeFile(f.path, o)
                    var sample = 1
                    while (o.outWidth / sample > 4096) sample *= 2
                    val o2 = BitmapFactory.Options().apply { inSampleSize = sample }
                    bmp = BitmapFactory.decodeFile(f.path, o2) ?: throw IllegalStateException("decode")
                    glBitmaps[kind] = bmp
                }
                val b = bmp!!
                val pano = info?.let { PanoInfo(it.fullW, it.fullH, it.cropW, it.cropH, it.left, it.top) }
                val geo = Geo.from(pano, b.width, b.height)
                runOnUiThread {
                    host.removeView(spinner)
                    val v = SphereView(this, b, geo)
                    v.renderer.yaw = lastYaw
                    v.renderer.pitch = lastPitch
                    v.renderer.fov = lastFov
                    host.addView(v, 0, FrameLayout.LayoutParams(-1, -1))
                    sphere = v
                    ready(v)
                }
            } catch (e: Throwable) {
                runOnUiThread { host.removeView(spinner); toast("Impossibile caricare l'immagine") }
            }
        }
    }

    private fun viewLon(): Float {
        val y = sphere?.renderer?.yaw ?: 0f
        return ((y % 360f) + 540f) % 360f - 180f
    }

    private fun viewLat(): Float = (sphere?.renderer?.pitch ?: 0f).coerceIn(-89f, 89f)

    // ------------------------------------------------------------------ scheda: scatti

    private fun tabShots() {
        val scroll = ScrollView(this)
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(8), dp(16), dp(16)) }
        scroll.addView(col)
        content.addView(scroll, FrameLayout.LayoutParams(-1, -1))

        val avail = p.shots.filter { p.shotFile(it).exists() }
        if (avail.isEmpty()) {
            col.addView(TextView(this).apply {
                text = "Gli scatti originali non sono più disponibili: la foto non si può rielaborare, ma toppe, orizzonte, vista iniziale e proiezioni funzionano."
                setTextColor(Md.sub(context)); textSize = 14f
            })
            return
        }
        col.addView(TextView(this).apply {
            text = "Tocca uno scatto per escluderlo, spostarlo o eliminarlo. Poi rifai l'unione."
            setTextColor(Md.sub(context)); textSize = 13f
            setPadding(0, 0, 0, dp(10))
        })
        val perRow = 3
        val cellW = (resources.displayMetrics.widthPixels - dp(32) - dp(8) * (perRow - 1)) / perRow
        var row: LinearLayout? = null
        for ((i, s) in p.shots.withIndex()) {
            if (!p.shotFile(s).exists()) continue
            if (row == null || row.childCount == perRow) {
                row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
                col.addView(row, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
            }
            row.addView(shotCell(i, s, cellW), LinearLayout.LayoutParams(cellW, -2).apply { if (row.childCount > 0) leftMargin = dp(8) })
        }

        val bar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(16), 0, 0) }
        bar.addView(small("Rifai unione", true) { startJob(Pipeline.RESTITCH) })
        bar.addView(small("Rifai anche l'allineamento") { startJob(Pipeline.FULL) }, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = dp(8) })
        col.addView(bar)
    }

    private fun shotCell(i: Int, s: ShotRec, w: Int): View {
        val cell = FrameLayout(this).apply { background = roundBg(Md.card(this@EditorActivity), 14) }
        val iv = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            alpha = if (s.enabled) 1f else 0.3f
            clipToOutline = true
            background = roundBg(Md.card(this@EditorActivity), 14)
        }
        cell.addView(iv, FrameLayout.LayoutParams(w, w * 4 / 3))
        val key = p.id + s.file
        val c = thumbCache.get(key)
        if (c != null) iv.setImageBitmap(c) else exec.execute {
            try {
                val b = ShotIO.decodeUpright(p.shotFile(s).path, 240)
                thumbCache.put(key, b)
                runOnUiThread { iv.setImageBitmap(b) }
            } catch (_: Exception) {}
        }
        val moved = s.dyaw != 0f || s.dpitch != 0f || s.droll != 0f
        val tag = buildString {
            append(i + 1)
            if (!s.enabled) append(" · escluso")
            if (moved) append(" · spostato")
            if (s.weak) append(" ⚠")
        }
        cell.addView(TextView(this).apply {
            text = tag
            setTextColor(0xFFFFFFFF.toInt()); textSize = 11f
            background = roundBg(0x99000000.toInt(), 10)
            setPadding(dp(6), dp(2), dp(6), dp(2))
        }, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.START).apply { setMargins(dp(6), 0, 0, dp(6)) })
        cell.setOnClickListener { shotDialog(i) }
        return cell
    }

    private fun shotDialog(i: Int) {
        val s = p.shots[i]
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(8), dp(20), 0) }
        val sw = SwitchCompat(this).apply { text = "Usa questo scatto"; isChecked = s.enabled }
        box.addView(sw)
        var dy = s.dyaw; var dp_ = s.dpitch; var dr = s.droll
        val sy = slider("Sposta a sinistra/destra", -8f, 8f, dy, "°") { dy = it }
        val sp = slider("Sposta su/giù", -8f, 8f, dp_, "°") { dp_ = it }
        val sr = slider("Ruota", -8f, 8f, dr, "°") { dr = it }
        box.addView(sy.view); box.addView(sp.view); box.addView(sr.view)
        box.addView(small("Azzera correzioni") { dy = 0f; dp_ = 0f; dr = 0f; sy.set(0f); sp.set(0f); sr.set(0f) },
            LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(6) })
        AlertDialog.Builder(this)
            .setTitle("Scatto ${i + 1}")
            .setView(box)
            .setPositiveButton("Ok") { _, _ ->
                s.enabled = sw.isChecked
                s.dyaw = dy; s.dpitch = dp_; s.droll = dr
                p.save()
                selectTab(0)
            }
            .setNeutralButton("Elimina") { _, _ ->
                AlertDialog.Builder(this)
                    .setMessage("Eliminare definitivamente questo scatto?")
                    .setPositiveButton("Elimina") { _, _ ->
                        p.shotFile(s).delete()
                        p.shots.removeAt(i)
                        p.save()
                        selectTab(0)
                    }
                    .setNegativeButton("Annulla", null)
                    .show()
            }
            .setNegativeButton("Annulla", null)
            .show()
    }

    // ------------------------------------------------------------------ scheda: toppa

    private class PatchOverlay(val ed: EditorActivity) : View(ed) {
        var fov = 50f
        var shape = 0
        var feather = 0.3f
        var vfov: () -> Float = { 80f }
        private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; color = 0xFFFFFFFF.toInt(); strokeWidth = ed.dp(2).toFloat()
        }
        private val inner = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; color = 0x99FFD60A.toInt(); strokeWidth = ed.dp(1).toFloat()
        }
        private val cross = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; color = 0xFFFFD60A.toInt(); strokeWidth = ed.dp(2).toFloat()
        }

        override fun onDraw(c: Canvas) {
            val cx = width / 2f
            val cy = height / 2f
            val halfH = height / 2f
            val tv = tan(Math.toRadians(vfov().toDouble()) / 2)
            val ta = tan(Math.toRadians(fov.toDouble()) / 2)
            val r = min((ta / tv * halfH).toFloat(), width * 3f)
            val ri = r * (1f - feather)
            if (shape == 0) {
                c.drawCircle(cx, cy, r, line)
                c.drawCircle(cx, cy, ri, inner)
            } else {
                c.drawRect(cx - r, cy - r, cx + r, cy + r, line)
                c.drawRect(cx - ri, cy - ri, cx + ri, cy + ri, inner)
            }
            val k = ed.dp(10).toFloat()
            c.drawLine(cx - k, cy, cx + k, cy, cross)
            c.drawLine(cx, cy - k, cx, cy + k, cross)
        }
    }

    private var pFov = 50f
    private var pShape = 0
    private var pFeather = 0.3f

    private fun tabPatch() {
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val host = FrameLayout(this)
        col.addView(host, LinearLayout.LayoutParams(-1, 0, 1f))
        val ov = PatchOverlay(this)
        host.addView(ov, FrameLayout.LayoutParams(-1, -1))
        ov.fov = pFov; ov.shape = pShape; ov.feather = pFeather
        content.addView(col, FrameLayout.LayoutParams(-1, -1))
        ensureGl(0, host) { v -> ov.vfov = { v.renderer.fov }; host.removeView(ov); host.addView(ov, FrameLayout.LayoutParams(-1, -1)) }

        val pn = panel()
        pn.addView(TextView(this).apply {
            text = "Porta al centro la zona da correggere (piedi, treppiede, un oggetto, un buco) e regola dimensione e bordo."
            setTextColor(Md.sub(context)); textSize = 12f
        })
        pn.addView(slider("Dimensione", 20f, 120f, pFov, "°") { pFov = it; ov.fov = it; ov.invalidate() }.view)
        pn.addView(slider("Bordo sfumato", 5f, 100f, pFeather * 100f, "%") { pFeather = it / 100f; ov.feather = pFeather; ov.invalidate() }.view)
        val shapes = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(4), 0, dp(4)) }
        val chips = ArrayList<TextView>()
        fun paintChips() {
            for ((i, c) in chips.withIndex()) {
                val sel = i == pShape
                c.setTextColor(if (sel) Md.onPrimary(this) else Md.onContainer(this))
                c.background = roundBg(if (sel) Md.primary(this) else Md.container(this), 18)
            }
        }
        for ((i, n) in listOf("Cerchio", "Rettangolo").withIndex()) {
            val c = chip(n, i == pShape) { pShape = i; ov.shape = i; ov.invalidate(); paintChips() }
            chips.add(c)
            shapes.addView(c, LinearLayout.LayoutParams(-2, -2).apply { rightMargin = dp(8) })
        }
        pn.addView(shapes)
        val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(4), 0, 0) }
        actions.addView(small("Esporta per modificare", true) { extractPatch() })
        actions.addView(small("Importa") { startImport(null) }, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = dp(8) })
        pn.addView(actions)

        // toppe esistenti
        val pending = p.patches.filter { !it.applied }
        for (r in pending) {
            pn.addView(patchRow("In attesa della versione modificata", r, true))
        }
        for ((n, r) in p.patches.filter { it.applied }.withIndex()) {
            pn.addView(patchRow("Toppa ${n + 1} · ${r.fov.toInt()}°", r, false))
        }
        col.addView(pn, LinearLayout.LayoutParams(-1, -2).apply { setMargins(dp(12), dp(8), dp(12), dp(8)) })
    }

    private fun patchRow(label: String, r: PatchRec, pending: Boolean): View {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(6), 0, 0) }
        row.addView(TextView(this).apply { text = label; setTextColor(Md.text(context)); textSize = 13f }, LinearLayout.LayoutParams(0, -2, 1f))
        if (pending) {
            row.addView(small("Importa modificata", true) { startImport(r) })
            row.addView(small("Altro editor") { exportDialog(r) }, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = dp(6) })
        }
        row.addView(small(if (pending) "✕" else "Rimuovi") { removePatch(r, !pending) }, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = dp(6) })
        return row
    }

    private fun removePatch(r: PatchRec, recompose: Boolean) {
        p.file(r.file).delete()
        p.patches.remove(r)
        p.save()
        if (recompose) startJob(Pipeline.COMPOSE) else selectTab(1)
    }

    /** Estrae la zona al centro come immagine normale (JPEG), per modificarla con un'altra app. */
    private fun extractPatch() {
        val lon = viewLon()
        val lat = viewLat()
        val fov = pFov
        val shape = pShape
        val feather = pFeather
        val pre = p.pre ?: p.base ?: return
        jobText.text = "Estraggo la toppa…"; jobBar.progress = 0
        jobOverlay.visibility = View.VISIBLE
        exec.execute {
            try {
                val canvas = Pano.toCanvas(Pano.readBgr(if (p.preFile.exists()) p.preFile else p.baseFile), pre)
                val rect = Pano.rectilinear(canvas, lon.toDouble(), lat.toDouble(), fov.toDouble(), 2048, 2048)
                canvas.release()
                val f = File(p.dir, "patch_${System.currentTimeMillis()}.jpg")
                Pano.writeJpeg(rect, f, 96)
                rect.release()
                val rec = PatchRec(f.name, lon, lat, fov)
                rec.shape = shape; rec.feather = feather
                p.patches.add(rec)
                p.save()
                try { Saver.saveJpeg(this, f.readBytes(), "Toppa_" + p.name + ".jpg") } catch (_: Exception) {}
                runOnUiThread {
                    jobOverlay.visibility = View.GONE
                    selectTab(1)
                    toast("Toppa salvata anche in Immagini/PhotoSphere")
                    sendPatch(rec)
                }
            } catch (e: Throwable) {
                runOnUiThread { jobOverlay.visibility = View.GONE; toast("Errore: " + e.message) }
            }
        }
    }

    private fun patchUri(r: PatchRec): Uri = FileProvider.getUriForFile(this, "$packageName.files", p.file(r.file))

    /** Mostra subito l'elenco delle app con cui aprire/modificare la toppa. */
    private fun sendPatch(r: PatchRec) {
        val i = Intent(Intent.ACTION_SEND).apply {
            type = "image/jpeg"
            putExtra(Intent.EXTRA_STREAM, patchUri(r))
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(i, "Apri la toppa con"))
    }

    private fun exportDialog(r: PatchRec) {
        AlertDialog.Builder(this)
            .setTitle("Modifica la toppa con…")
            .setItems(arrayOf("Apri in un editor", "Invia a un'altra app", "Salva una copia in galleria", "Più tardi")) { _, which ->
                when (which) {
                    0 -> {
                        val i = Intent(Intent.ACTION_EDIT).setDataAndType(patchUri(r), "image/jpeg")
                            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                        try { startActivity(Intent.createChooser(i, "Modifica con")) }
                        catch (_: ActivityNotFoundException) { toast("Nessun editor trovato: usa \"Invia a un'altra app\"") }
                    }
                    1 -> {
                        val i = Intent(Intent.ACTION_SEND).apply {
                            type = "image/jpeg"
                            putExtra(Intent.EXTRA_STREAM, patchUri(r))
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                        startActivity(Intent.createChooser(i, "Invia la toppa a"))
                    }
                    2 -> {
                        try {
                            Saver.saveJpeg(this, p.file(r.file).readBytes(), "Toppa_" + p.name + ".jpg")
                            toast("Copia salvata in Immagini/PhotoSphere")
                        } catch (e: Exception) { toast("Errore: " + e.message) }
                    }
                }
            }
            .show()
    }

    private fun startImport(r: PatchRec?) {
        pendingImportRec = r
        pickLauncher.launch(arrayOf("image/*"))
    }

    private fun importPatch(uri: Uri) {
        val existing = pendingImportRec
        val lon = viewLon(); val lat = viewLat()
        val fov = pFov; val shape = pShape; val feather = pFeather
        jobText.text = "Importo la toppa…"; jobBar.progress = 0
        jobOverlay.visibility = View.VISIBLE
        exec.execute {
            try {
                val rec = existing ?: PatchRec("patch_${System.currentTimeMillis()}.jpg", lon, lat, fov).also {
                    it.shape = shape; it.feather = feather
                    p.patches.add(it)
                }
                val dst = p.file(rec.file)
                contentResolver.openInputStream(uri)?.use { ins -> dst.outputStream().use { out -> ins.copyTo(out) } }
                    ?: throw IllegalStateException("file non leggibile")
                if (BitmapFactory.decodeFile(dst.path, BitmapFactory.Options().apply { inJustDecodeBounds = true }).let { true } &&
                    BitmapFactory.Options().also { BitmapFactory.decodeFile(dst.path, it.apply { inJustDecodeBounds = true }) }.outWidth <= 0
                ) throw IllegalStateException("non è un'immagine valida")
                rec.applied = true
                p.save()
                runOnUiThread { jobOverlay.visibility = View.GONE; startJob(Pipeline.COMPOSE) }
            } catch (e: Throwable) {
                runOnUiThread { jobOverlay.visibility = View.GONE; toast("Errore: " + e.message) }
            }
        }
    }

    // ------------------------------------------------------------------ scheda: orizzonte

    private fun tabLevel() {
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val host = FrameLayout(this)
        col.addView(host, LinearLayout.LayoutParams(-1, 0, 1f))
        content.addView(col, FrameLayout.LayoutParams(-1, -1))
        var roll = p.levelRoll
        var pitch = p.levelPitch
        fun apply() {
            val m = Pano.levelMatrix(roll, pitch)
            sphere?.renderer?.model = FloatArray(9) { m[it].toFloat() }
        }
        ensureGl(0, host) { apply() }
        // linea guida orizzontale al centro
        host.addView(object : View(this) {
            private val pt = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x88FFFFFF.toInt(); strokeWidth = dp(1).toFloat() }
            override fun onDraw(c: Canvas) { c.drawLine(0f, height / 2f, width.toFloat(), height / 2f, pt) }
        }, FrameLayout.LayoutParams(-1, -1))

        val pn = panel()
        pn.addView(TextView(this).apply {
            text = "Guarda l'orizzonte al centro e raddrizzalo: la linea bianca è la riga perfettamente orizzontale."
            setTextColor(Md.sub(context)); textSize = 12f
        })
        val sr = slider("Inclinazione laterale", -20f, 20f, roll, "°") { roll = it; apply() }
        val sp = slider("Inclinazione avanti/indietro", -20f, 20f, pitch, "°") { pitch = it; apply() }
        pn.addView(sr.view); pn.addView(sp.view)
        val bar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(6), 0, 0) }
        bar.addView(small("Applica", true) {
            p.levelRoll = roll; p.levelPitch = pitch
            startJob(Pipeline.COMPOSE)
        })
        bar.addView(small("Azzera") { roll = 0f; pitch = 0f; sr.set(0f); sp.set(0f); apply() }, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = dp(8) })
        pn.addView(bar)
        col.addView(pn, LinearLayout.LayoutParams(-1, -2).apply { setMargins(dp(12), dp(8), dp(12), dp(8)) })
    }

    // ------------------------------------------------------------------ scheda: vista iniziale

    private fun tabView() {
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val host = FrameLayout(this)
        col.addView(host, LinearLayout.LayoutParams(-1, 0, 1f))
        content.addView(col, FrameLayout.LayoutParams(-1, -1))
        if (lastYaw == 0f && lastPitch == 0f && p.hasInitial) {
            lastYaw = p.initLon; lastPitch = p.initLat
        }
        ensureGl(1, host) { v ->
            if (p.hasInitial && lastYaw == p.initLon && lastPitch == p.initLat) {
                val aspect = max(1f, host.width.toFloat()) / max(1, host.height)
                val hf = Math.toRadians(p.initHFov.toDouble())
                v.renderer.fov = Math.toDegrees(2.0 * atan(tan(hf / 2) / aspect)).toFloat().coerceIn(25f, 110f)
            }
        }
        host.addView(object : View(this) {
            private val pt = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = 0xFFFFD60A.toInt(); strokeWidth = dp(2).toFloat() }
            override fun onDraw(c: Canvas) {
                val cx = width / 2f; val cy = height / 2f; val k = dp(14).toFloat()
                c.drawCircle(cx, cy, k, pt)
                c.drawLine(cx - 2 * k, cy, cx - k, cy, pt); c.drawLine(cx + k, cy, cx + 2 * k, cy, pt)
                c.drawLine(cx, cy - 2 * k, cx, cy - k, pt); c.drawLine(cx, cy + k, cx, cy + 2 * k, pt)
            }
        }, FrameLayout.LayoutParams(-1, -1))

        val pn = panel()
        pn.addView(TextView(this).apply {
            text = if (p.hasInitial) "Vista iniziale impostata. Spostati e zooma per sceglierne un'altra."
            else "Trascina e zooma fino all'inquadratura con cui vuoi che si apra la foto (anche in Google Foto)."
            setTextColor(Md.sub(context)); textSize = 12f
        })
        val bar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(8), 0, 0) }
        bar.addView(small("Imposta questa vista", true) {
            val r = sphere?.renderer ?: return@small
            val aspect = max(1f, host.width.toFloat()) / max(1, host.height)
            val hf = Math.toDegrees(2.0 * atan(tan(Math.toRadians(r.fov.toDouble()) / 2) * aspect)).toFloat()
            p.initLon = viewLon(); p.initLat = viewLat(); p.initHFov = hf.coerceIn(30f, 120f)
            p.hasInitial = true
            savePublish("Vista iniziale impostata")
        })
        bar.addView(small("Azzera") {
            p.hasInitial = false
            savePublish("Vista iniziale azzerata")
        }, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = dp(8) })
        pn.addView(bar)
        col.addView(pn, LinearLayout.LayoutParams(-1, -2).apply { setMargins(dp(12), dp(8), dp(12), dp(8)) })
    }

    /** Aggiorna solo i metadati della foto salvata (veloce, senza rielaborare l'immagine). */
    private fun savePublish(msg: String) {
        jobText.text = "Salvo…"; jobBar.progress = 0
        jobOverlay.visibility = View.VISIBLE
        exec.execute {
            try {
                p.save()
                Pipeline.publish(this, p)
                runOnUiThread { jobOverlay.visibility = View.GONE; toast(msg) }
            } catch (e: Throwable) {
                runOnUiThread { jobOverlay.visibility = View.GONE; toast("Errore: " + e.message) }
            }
        }
    }

    // ------------------------------------------------------------------ scheda: creativo

    private var cType = 0
    private val cYaw = floatArrayOf(0f, 0f, 0f, 0f)
    private val cPitch = floatArrayOf(0f, 0f, 0f, 55f)
    private val cAmp = floatArrayOf(140f, 140f, 80f, 200f)
    private var equiFull: Mat? = null
    private var equiSmall: Mat? = null
    private var previewSeq = 0

    private fun tabCreative() {
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val iv = ImageView(this).apply { scaleType = ImageView.ScaleType.FIT_CENTER; setBackgroundColor(0xFF000000.toInt()) }
        col.addView(iv, LinearLayout.LayoutParams(-1, 0, 1f))
        content.addView(col, FrameLayout.LayoutParams(-1, -1))

        val pn = panel()
        val chipRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val sliders = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val chips = ArrayList<TextView>()
        val names = listOf("Pianeta", "Tunnel", "Vista", "Cilindrica")

        fun refresh() {
            val seq = ++previewSeq
            previewExec.execute {
                if (seq != previewSeq) return@execute
                try {
                    val small = ensureEqui()
                    val out = Pano.creative(small, cType, cYaw[cType].toDouble(), cPitch[cType].toDouble(), cAmp[cType].toDouble(),
                        if (cType == 3) 900 else 640)
                    val bmp = Bitmap.createBitmap(out.cols(), out.rows(), Bitmap.Config.ARGB_8888)
                    val rgba = Mat()
                    Imgproc.cvtColor(out, rgba, Imgproc.COLOR_BGR2RGBA)
                    org.opencv.android.Utils.matToBitmap(rgba, bmp)
                    rgba.release(); out.release()
                    if (seq == previewSeq) runOnUiThread { iv.setImageBitmap(bmp) }
                } catch (e: Throwable) {
                    runOnUiThread { toast("Anteprima non riuscita: " + e.message) }
                }
            }
        }

        fun buildSliders() {
            sliders.removeAllViews()
            val t = cType
            sliders.addView(slider(if (t == 3 || t == 2) "Direzione" else "Rotazione", -180f, 180f, cYaw[t], "°") { cYaw[t] = it; refresh() }.view)
            if (t == 2) sliders.addView(slider("Inclinazione", -80f, 80f, cPitch[t], "°") { cPitch[t] = it; refresh() }.view)
            if (t == 3) sliders.addView(slider("Altezza", 20f, 80f, cPitch[t], "°") { cPitch[t] = it; refresh() }.view)
            val (lo, hi, label) = when (t) {
                2 -> Triple(40f, 120f, "Campo visivo")
                3 -> Triple(90f, 360f, "Ampiezza")
                else -> Triple(90f, 170f, "Ampiezza")
            }
            sliders.addView(slider(label, lo, hi, cAmp[t], "°") { cAmp[t] = it; refresh() }.view)
        }

        fun paintChips() {
            for ((i, c) in chips.withIndex()) {
                val sel = i == cType
                c.setTextColor(if (sel) Md.onPrimary(this) else Md.onContainer(this))
                c.background = roundBg(if (sel) Md.primary(this) else Md.container(this), 18)
            }
        }
        for ((i, n) in names.withIndex()) {
            val c = chip(n, i == cType) { cType = i; paintChips(); buildSliders(); refresh() }
            chips.add(c)
            chipRow.addView(c, LinearLayout.LayoutParams(-2, -2).apply { rightMargin = dp(8) })
        }
        val hs = HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false }
        hs.addView(chipRow)
        pn.addView(hs)
        pn.addView(sliders)
        val bar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(6), 0, 0) }
        bar.addView(small("Salva", true) { exportCreative(false) })
        bar.addView(small("Condividi") { exportCreative(true) }, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = dp(8) })
        pn.addView(bar)
        col.addView(pn, LinearLayout.LayoutParams(-1, -2).apply { setMargins(dp(12), dp(8), dp(12), dp(8)) })
        buildSliders()
        refresh()
    }

    /** Immagine ridotta per l'anteprima (la tela intera resta per l'esportazione). */
    private fun ensureEqui(): Mat {
        equiSmall?.let { return it }
        val fin = p.final ?: p.pre ?: p.base ?: throw IllegalStateException("immagine mancante")
        val f = if (p.finalFile.exists()) p.finalFile else p.baseFile
        val full = Pano.toCanvas(Pano.readBgr(f), fin)
        equiFull = full
        val s = Mat()
        val w = min(full.cols(), 3072).toDouble()
        Imgproc.resize(full, s, Size(w, w / 2), 0.0, 0.0, Imgproc.INTER_AREA)
        equiSmall = s
        return s
    }

    private fun exportCreative(share: Boolean) {
        val type = cType
        val yaw = cYaw[type].toDouble(); val pitch = cPitch[type].toDouble(); val amp = cAmp[type].toDouble()
        jobText.text = "Creo l'immagine…"; jobBar.progress = 0
        jobOverlay.visibility = View.VISIBLE
        exec.execute {
            try {
                ensureEqui()
                val src = equiFull ?: equiSmall!!
                val out = Pano.creative(src, type, yaw, pitch, amp, if (type == 3) 4000 else 2400)
                val bytes = Pano.encodeJpeg(out, 92)
                out.release()
                val name = "PS_" + listOf("pianeta", "tunnel", "vista", "cilindrica")[type] + "_" +
                    java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US).format(java.util.Date()) + ".jpg"
                if (share) {
                    val dir = File(cacheDir, "share").apply { mkdirs() }
                    val f = File(dir, name)
                    f.writeBytes(bytes)
                    val uri = FileProvider.getUriForFile(this, "$packageName.files", f)
                    runOnUiThread {
                        jobOverlay.visibility = View.GONE
                        val i = Intent(Intent.ACTION_SEND).apply {
                            this.type = "image/jpeg"
                            putExtra(Intent.EXTRA_STREAM, uri)
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                        startActivity(Intent.createChooser(i, "Condividi"))
                    }
                } else {
                    Saver.saveJpeg(this, bytes, name)
                    runOnUiThread { jobOverlay.visibility = View.GONE; toast("Salvata in Immagini/PhotoSphere") }
                }
            } catch (e: Throwable) {
                runOnUiThread { jobOverlay.visibility = View.GONE; toast("Errore: " + e.message) }
            }
        }
    }
}
