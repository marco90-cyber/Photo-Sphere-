package com.marco.photosphere

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.text.format.DateFormat
import android.util.LruCache
import android.view.Gravity
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.FileProvider
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import java.util.Date
import java.util.concurrent.Executors

/** Elenco dei progetti: foto 360° con i loro scatti originali, modificabili e rielaborabili. */
class ProjectsActivity : AppCompatActivity() {

    private lateinit var list: LinearLayout
    private val exec = Executors.newFixedThreadPool(2)
    private val cache = object : LruCache<String, Bitmap>(16 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }
    private val listener: () -> Unit = { refresh() }

    private val importLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) importPhoto(uri) }

    /** Crea un progetto da una foto 360° già esistente (senza scatti originali). L'originale non viene toccato. */
    private fun importPhoto(uri: Uri) {
        Toast.makeText(this, "Importo la foto…", Toast.LENGTH_SHORT).show()
        exec.execute {
            try {
                val p = Pipeline.importPhoto(this, uri)
                runOnUiThread { startActivity(Intent(this, EditorActivity::class.java).putExtra("id", p.id)) }
            } catch (e: Throwable) {
                runOnUiThread { Toast.makeText(this, "Importazione non riuscita: " + e.message, Toast.LENGTH_LONG).show() }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Md.bg(this)
        window.navigationBarColor = Md.bg(this)
        val scroll = ScrollView(this).apply { setBackgroundColor(Md.bg(this@ProjectsActivity)) }
        list = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(36), dp(16), dp(32))
        }
        scroll.addView(list, android.widget.FrameLayout.LayoutParams(-1, -2))
        setContentView(scroll)
    }

    override fun onResume() {
        super.onResume()
        Jobs.addListener(listener)
        refresh()
    }

    override fun onPause() {
        super.onPause()
        Jobs.removeListener(listener)
    }

    private fun refresh() {
        exec.execute {
            val ps = Projects.list(this)
            runOnUiThread { build(ps) }
        }
    }

    private fun build(ps: List<Project>) {
        list.removeAllViews()
        val head = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val back = ImageView(this).apply {
            setImageResource(R.drawable.ic_back)
            setColorFilter(Md.text(this@ProjectsActivity))
            setPadding(dp(10), dp(10), dp(10), dp(10))
            background = roundBg(Md.card(this@ProjectsActivity), 24)
            setOnClickListener { finish() }
        }
        head.addView(back, LinearLayout.LayoutParams(dp(48), dp(48)))
        head.addView(TextView(this).apply {
            text = "Progetti"
            setTextColor(Md.text(context)); textSize = 26f
            setPadding(dp(16), 0, 0, 0)
        })
        list.addView(head)
        list.addView(mdButton("Importa una foto 360° esistente", false).also {
            it.setOnClickListener { importLauncher.launch(arrayOf("image/*")) }
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(14) })

        if (ps.isEmpty()) {
            list.addView(TextView(this).apply {
                text = "Nessun progetto. Scatta una foto 360°: qui troverai gli scatti originali per modificarla, togliere uno scatto, applicare toppe e livellare l'orizzonte."
                setTextColor(Md.sub(context)); textSize = 14f
                setPadding(dp(8), dp(32), dp(8), 0)
            })
            return
        }
        for (p in ps) list.addView(card(p), LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })
    }

    private fun card(p: Project): LinearLayout {
        val job = Jobs.state(p.id)
        val c = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = roundBg(Md.card(this@ProjectsActivity), 24)
            setPadding(dp(12), dp(12), dp(12), dp(12))
        }
        val top = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val iv = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            background = roundBg(Md.bg(this@ProjectsActivity), 12)
            clipToOutline = true
        }
        top.addView(iv, LinearLayout.LayoutParams(dp(128), dp(64)))
        loadThumb(p, iv)

        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12), 0, 0, 0) }
        col.addView(TextView(this).apply {
            text = DateFormat.format("d MMM yyyy, HH:mm", Date(p.createdAt)).toString()
            setTextColor(Md.text(context)); textSize = 16f
        })
        val sub = when {
            job != null -> if (job.queued) "In coda…" else job.message
            p.lastError != null -> "Non riuscita: " + p.lastError
            p.shots.isEmpty() -> "Foto importata · %.0f MB".format(p.sizeBytes() / 1048576.0)
            else -> "${p.shots.count { it.enabled }} scatti" +
                (if (p.coverage > 0f) " · copertura ${(p.coverage * 100).toInt()}%" else "") +
                " · %.0f MB".format(p.sizeBytes() / 1048576.0)
        }
        col.addView(TextView(this).apply {
            text = sub
            setTextColor(if (job == null && p.lastError != null) Md.BAD else Md.sub(context)); textSize = 13f
            setPadding(0, dp(2), 0, 0)
        })
        top.addView(col, LinearLayout.LayoutParams(0, -2, 1f))
        c.addView(top)

        if (job != null) {
            c.addView(ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
                max = 100
                progress = (job.progress * 100).toInt()
                isIndeterminate = job.queued
                progressTintList = android.content.res.ColorStateList.valueOf(Md.primary(context))
            }, LinearLayout.LayoutParams(-1, dp(6)).apply { topMargin = dp(10) })
            c.addView(TextView(this).apply {
                text = "Puoi chiudere l'app: ti avviso con una notifica."
                setTextColor(Md.sub(context)); textSize = 12f
                setPadding(0, dp(6), 0, 0)
            })
            return c
        }

        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(10), 0, 0) }
        if (p.lastError != null || p.base == null) {
            row.addView(mdButton("Riprova").also { b ->
                b.setPadding(dp(14), dp(8), dp(14), dp(8))
                b.setOnClickListener { StitchService.enqueue(this, p.id, Pipeline.FULL); refresh() }
            })
        } else {
            row.addView(mdButton("Apri 360°").also { b ->
                b.setPadding(dp(14), dp(8), dp(14), dp(8))
                b.setOnClickListener {
                    val u = p.resultUri?.let { Uri.parse(it) }
                        ?: FileProvider.getUriForFile(this, "$packageName.files", p.finalFile)
                    startActivity(Intent(this, ViewerActivity::class.java).setData(u).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
                }
            })
            row.addView(mdButton("Modifica", false).also { b ->
                b.setPadding(dp(14), dp(8), dp(14), dp(8))
                b.setOnClickListener { startActivity(Intent(this, EditorActivity::class.java).putExtra("id", p.id)) }
            }, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = dp(8) })
        }
        row.addView(mdButton("Elimina", false).also { b ->
            b.setPadding(dp(14), dp(8), dp(14), dp(8))
            b.setOnClickListener { confirmDelete(p) }
        }, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = dp(8) })
        c.addView(row)
        c.setOnClickListener {
            if (p.base != null) startActivity(Intent(this, EditorActivity::class.java).putExtra("id", p.id))
        }
        return c
    }

    private fun loadThumb(p: Project, iv: ImageView) {
        val key = p.id + p.thumbFile.lastModified()
        cache.get(key)?.let { iv.setImageBitmap(it); return }
        if (!p.thumbFile.exists()) return
        exec.execute {
            val bmp = BitmapFactory.decodeFile(p.thumbFile.path)
            if (bmp != null) {
                cache.put(key, bmp)
                runOnUiThread { iv.setImageBitmap(bmp) }
            }
        }
    }

    private fun confirmDelete(p: Project) {
        AlertDialog.Builder(this)
            .setTitle("Elimina progetto")
            .setMessage("Vuoi eliminare tutto, anche la foto nella galleria, oppure solo gli scatti originali (la foto resta ma non sarà più modificabile)?")
            .setPositiveButton("Elimina tutto") { _, _ ->
                Saver.delete(this, p.resultUri)
                Projects.delete(p)
                refresh()
            }
            .setNeutralButton("Solo gli scatti") { _, _ ->
                p.shots.forEach { p.shotFile(it).delete() }
                p.shots.clear()
                p.save()
                refresh()
            }
            .setNegativeButton("Annulla", null)
            .show()
    }
}
