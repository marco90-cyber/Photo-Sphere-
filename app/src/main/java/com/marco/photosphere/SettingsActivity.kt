package com.marco.photosphere

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.hardware.Sensor
import android.hardware.SensorManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.core.content.ContextCompat
import java.io.File

/** Impostazioni in stile Material You: scatto, permessi, archivio, sensori e informazioni. */
class SettingsActivity : AppCompatActivity() {

    private lateinit var list: LinearLayout
    private val prefs by lazy { getSharedPreferences("prefs", MODE_PRIVATE) }

    private val readPerm =
        if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_IMAGES
        else Manifest.permission.READ_EXTERNAL_STORAGE

    private var asking: String? = null
    private val permLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
            val p = asking
            asking = null
            // negato in modo definitivo: il sistema non chiede più, si passa dalle impostazioni
            if (!ok && p != null && !shouldShowRequestPermissionRationale(p)) openAppSettings()
            build()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Md.bg(this)
        window.navigationBarColor = Md.bg(this)
        val scroll = ScrollView(this).apply { setBackgroundColor(Md.bg(this@SettingsActivity)); isFillViewport = true }
        list = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(36), dp(16), dp(32))
        }
        scroll.addView(list, android.widget.FrameLayout.LayoutParams(-1, -2))
        setContentView(scroll)
        build()
    }

    override fun onResume() {
        super.onResume()
        if (::list.isInitialized) build()
    }

    private fun granted(p: String) = ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED

    private fun openAppSettings() {
        startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)))
    }

    // ------------------------------------------------------------------ costruzione

    private fun build() {
        list.removeAllViews()

        val head = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val back = ImageView(this).apply {
            setImageResource(R.drawable.ic_back)
            setColorFilter(Md.text(this@SettingsActivity))
            setPadding(dp(10), dp(10), dp(10), dp(10))
            background = roundBg(Md.card(this@SettingsActivity), 24)
            setOnClickListener { finish() }
        }
        head.addView(back, LinearLayout.LayoutParams(dp(48), dp(48)))
        head.addView(TextView(this).apply {
            text = "Impostazioni"
            setTextColor(Md.text(context)); textSize = 26f
            setPadding(dp(16), 0, 0, 0)
        })
        list.addView(head)

        // ---- Scatto
        section("Scatto")
        val card1 = card()
        card1.addView(row("Qualità foto", "6K è più nitida ma serve più memoria e tempo", chips(
            listOf("4K" to 4096, "6K" to 6144), prefs.getInt("outWidth", 6144)
        ) { prefs.edit().putInt("outWidth", it).apply() }))
        card1.addView(row("Mini-mappa di copertura", "Mostra quali punti hai già fotografato",
            switch("minimap", true)))
        card1.addView(row("Blocca esposizione e bianco", "Evita differenze di luce tra gli scatti. Consigliato",
            switch("lockAe", true)))
        card1.addView(row("Vibrazione allo scatto", null, switch("haptic", true)))
        card1.addView(row("Apri la foto al termine", "Altrimenti viene solo salvata", switch("autoOpen", true)))

        // ---- Permessi
        section("Permessi")
        val card2 = card()
        card2.addView(permRow("Fotocamera", "Serve per scattare le foto", Manifest.permission.CAMERA))
        card2.addView(permRow("Foto e file", "Serve per mostrare le tue foto 360° nella galleria", readPerm))
        card2.addView(row("Impostazioni di sistema", "Gestisci tutti i permessi dell'app", mdButton("Apri", false).also {
            it.setOnClickListener { openAppSettings() }
        }))

        // ---- Archivio
        section("Archivio")
        val card3 = card()
        card3.addView(row("Cartella di salvataggio", "Immagini/PhotoSphere", null))
        card3.addView(row("Aggiorna elenco foto", "Ripete la ricerca delle foto 360° nella galleria", mdButton("Aggiorna", false).also {
            it.setOnClickListener {
                getSharedPreferences("pano", MODE_PRIVATE).edit().clear().apply()
                Toast.makeText(this, "Elenco azzerato: verrà ricreato all'apertura", Toast.LENGTH_SHORT).show()
            }
        }))
        card3.addView(row("Svuota file temporanei", cacheInfo(), mdButton("Svuota", false).also {
            it.setOnClickListener {
                cacheDir.listFiles()?.forEach { f -> if (f.isFile) f.delete() }
                Toast.makeText(this, "File temporanei eliminati", Toast.LENGTH_SHORT).show()
                build()
            }
        }))

        // ---- Sensori
        section("Sensori del telefono")
        val sm = getSystemService(SENSOR_SERVICE) as SensorManager
        val card4 = card()
        card4.addView(sensorRow(sm, "Giroscopio", Sensor.TYPE_GYROSCOPE, "Misura la rotazione e capisce se sei fermo"))
        card4.addView(sensorRow(sm, "Vettore di rotazione", Sensor.TYPE_GAME_ROTATION_VECTOR, "Orientamento senza disturbi magnetici"))
        card4.addView(sensorRow(sm, "Accelerometro", Sensor.TYPE_ACCELEROMETER, "Direzione della gravità, per tenere dritto l'orizzonte"))

        // ---- Informazioni
        section("Informazioni")
        val card5 = card()
        val ver = try { packageManager.getPackageInfo(packageName, 0).versionName } catch (_: Exception) { "?" }
        card5.addView(row("Photo Sphere", "Versione $ver", null))
        card5.addView(row("Consigli per scattare", "Come ottenere foto senza difetti", mdButton("Leggi", false).also {
            it.setOnClickListener { showTips() }
        }))
        card5.addView(row("Dispositivo", "${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE}", null))
        card5.addView(row("Tecnologie", "CameraX, OpenCV, OpenGL ES. Compatibile con le foto sferiche di Google Camera (GPano)", null))
    }

    // ------------------------------------------------------------------ componenti

    private fun section(title: String) {
        list.addView(TextView(this).apply {
            text = title
            setTextColor(Md.primary(context)); textSize = 14f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(dp(12), dp(24), 0, dp(8))
        })
    }

    private fun card(): LinearLayout {
        val c = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = roundBg(Md.card(this@SettingsActivity), 24)
            setPadding(dp(4), dp(4), dp(4), dp(4))
        }
        list.addView(c, LinearLayout.LayoutParams(-1, -2))
        return c
    }

    private fun row(title: String, sub: String?, trailing: View?): View {
        val r = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(12), dp(12), dp(12))
        }
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(TextView(this).apply { text = title; setTextColor(Md.text(context)); textSize = 16f })
        if (sub != null) col.addView(TextView(this).apply {
            text = sub; setTextColor(Md.sub(context)); textSize = 13f; setPadding(0, dp(2), 0, 0)
        })
        r.addView(col, LinearLayout.LayoutParams(0, -2, 1f))
        if (trailing != null) r.addView(trailing, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = dp(12) })
        return r
    }

    private fun switch(key: String, def: Boolean): SwitchCompat = SwitchCompat(this).apply {
        isChecked = prefs.getBoolean(key, def)
        val on = Md.primary(context)
        val off = Md.sub(context)
        val states = arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf())
        thumbTintList = ColorStateList(states, intArrayOf(Md.onPrimary(context), off))
        trackTintList = ColorStateList(states, intArrayOf(on, Md.container(context)))
        setOnCheckedChangeListener { _, v -> prefs.edit().putBoolean(key, v).apply() }
    }

    private fun chips(opts: List<Pair<String, Int>>, current: Int, onPick: (Int) -> Unit): View {
        val box = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val views = ArrayList<TextView>()
        fun paint(sel: Int) {
            for ((i, v) in views.withIndex()) {
                val on = opts[i].second == sel
                v.setTextColor(if (on) Md.onPrimary(this) else Md.onContainer(this))
                v.background = roundBg(if (on) Md.primary(this) else Md.container(this), 20)
            }
        }
        for ((label, value) in opts) {
            val t = TextView(this).apply {
                text = label; textSize = 14f; gravity = Gravity.CENTER
                setPadding(dp(16), dp(8), dp(16), dp(8))
                setOnClickListener { onPick(value); paint(value) }
            }
            views.add(t)
            box.addView(t, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = dp(6) })
        }
        paint(current)
        return box
    }

    private fun permRow(title: String, sub: String, perm: String): View {
        val ok = granted(perm)
        val right = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.END }
        right.addView(TextView(this).apply {
            text = if (ok) "Concesso" else "Negato"
            setTextColor(if (ok) Md.OK else Md.BAD); textSize = 13f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        })
        if (!ok) right.addView(mdButton("Concedi").also {
            it.setPadding(dp(16), dp(8), dp(16), dp(8))
            it.setOnClickListener {
                asking = perm
                permLauncher.launch(perm)
            }
        }, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(6) })
        return row(title, sub, right)
    }

    private fun sensorRow(sm: SensorManager, name: String, type: Int, sub: String): View {
        val ok = sm.getDefaultSensor(type) != null
        return row(name, sub, TextView(this).apply {
            text = if (ok) "Disponibile" else "Assente"
            setTextColor(if (ok) Md.OK else Md.BAD); textSize = 13f
        })
    }

    private fun cacheInfo(): String {
        var n = 0L
        cacheDir.listFiles()?.forEach { if (it.isFile) n += it.length() }
        return "Occupano %.1f MB".format(n / 1048576.0)
    }

    private fun showTips() {
        AlertDialog.Builder(this)
            .setTitle("Consigli per scattare")
            .setMessage(
                "• Tieni il telefono verticale, vicino al corpo, e ruota su te stesso senza spostarti.\n" +
                "• Tocca l'immagine per mettere a fuoco e regolare l'esposizione prima di iniziare.\n" +
                "• Porta il cerchio su ogni punto e resta fermo: lo scatto parte da solo.\n" +
                "• Riempi tutta la mini-mappa: le zone non fotografate restano nere.\n" +
                "• Evita soggetti in movimento e luce che cambia molto.\n" +
                "• Con poca luce l'immagine può venire mossa: fermati bene su ogni punto."
            )
            .setPositiveButton("Ok", null)
            .show()
    }
}
