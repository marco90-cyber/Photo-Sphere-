package com.marco.photosphere

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.util.LruCache
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.GridView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    private val items = ArrayList<Uri>()
    private lateinit var adapter: GridAdapter
    private lateinit var empty: TextView
    private val scanExec = Executors.newSingleThreadExecutor()
    private val thumbExec = Executors.newFixedThreadPool(3)
    private val cache = object : LruCache<String, Bitmap>(48 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }

    private val readPerm =
        if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_IMAGES
        else Manifest.permission.READ_EXTERNAL_STORAGE

    private val permLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
            if (ok) scan() else empty.text = "Concedi l'accesso alle foto per vedere le tue foto 360°"
        }

    private val openLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) openViewer(uri)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.BLACK)
            setPadding(dp(16), dp(40), dp(16), dp(8))
        }
        root.addView(TextView(this).apply {
            text = "Photo Sphere"
            setTextColor(Color.WHITE); textSize = 30f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        })
        root.addView(TextView(this).apply {
            text = "Scatta e guarda foto a 360°"
            setTextColor(0xFFAAAAAA.toInt()); textSize = 14f
            setPadding(0, dp(2), 0, dp(14))
        })

        val shoot = pill("📷   Scatta foto 360°", 0xFF1A73E8.toInt(), 18f).apply {
            setPadding(dp(18), dp(16), dp(18), dp(16))
        }
        shoot.setOnClickListener { startActivity(Intent(this, CaptureActivity::class.java)) }
        root.addView(shoot, LinearLayout.LayoutParams(-1, -2))

        val open = pill("Apri un file 360°", 0xFF2C2C2E.toInt(), 15f)
        open.setOnClickListener { openLauncher.launch(arrayOf("image/*")) }
        root.addView(open, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })

        root.addView(TextView(this).apply {
            text = "Le tue foto 360°"
            setTextColor(Color.WHITE); textSize = 16f
            setPadding(0, dp(20), 0, dp(8))
        })

        empty = TextView(this).apply {
            setTextColor(0xFF888888.toInt()); textSize = 14f; gravity = Gravity.CENTER
            text = "Nessuna foto 360° trovata"
            setPadding(0, dp(30), 0, 0)
        }
        val grid = GridView(this).apply {
            numColumns = 2
            horizontalSpacing = dp(8)
            verticalSpacing = dp(8)
            stretchMode = GridView.STRETCH_COLUMN_WIDTH
            setSelector(android.R.color.transparent)
        }
        adapter = GridAdapter()
        grid.adapter = adapter
        grid.setOnItemClickListener { _, _, pos, _ -> openViewer(items[pos]) }
        grid.emptyView = empty
        root.addView(grid, LinearLayout.LayoutParams(-1, 0, 1f))
        root.addView(empty, LinearLayout.LayoutParams(-1, -2))

        setContentView(root)
    }

    override fun onResume() {
        super.onResume()
        if (ContextCompat.checkSelfPermission(this, readPerm) == PackageManager.PERMISSION_GRANTED) {
            scan()
        } else {
            permLauncher.launch(readPerm)
        }
    }

    private fun openViewer(uri: Uri) {
        startActivity(Intent(this, ViewerActivity::class.java).setData(uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
    }

    /** Cerca le foto con metadati GPano (anche quelle di Google Camera). Cache degli id già controllati. */
    private fun scan() {
        scanExec.execute {
            val prefs = getSharedPreferences("pano", MODE_PRIVATE)
            val checked = HashSet(prefs.getStringSet("checked", emptySet()) ?: emptySet())
            val panos = HashSet(prefs.getStringSet("panos", emptySet()) ?: emptySet())
            val found = ArrayList<Uri>()
            var newFound = 0
            try {
                contentResolver.query(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                    arrayOf(MediaStore.Images.Media._ID),
                    null, null, MediaStore.Images.Media.DATE_ADDED + " DESC"
                )?.use { c ->
                    var n = 0
                    while (c.moveToNext() && n < 4000) {
                        n++
                        val id = c.getLong(0)
                        val key = id.toString()
                        val uri = Uri.withAppendedPath(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, key)
                        if (key in checked) {
                            if (key in panos) found.add(uri)
                        } else {
                            val h = Gpano.readHeader(this, uri)
                            if (h != null) {
                                checked.add(key)
                                if (Gpano.isPano(h)) {
                                    panos.add(key)
                                    found.add(uri)
                                    newFound++
                                    if (newFound % 5 == 0) publish(found)
                                }
                            }
                        }
                    }
                }
            } catch (_: Exception) {
            }
            prefs.edit().putStringSet("checked", checked).putStringSet("panos", panos).apply()
            publish(found)
        }
    }

    private fun publish(found: List<Uri>) {
        val snapshot = ArrayList(found)
        runOnUiThread {
            items.clear()
            items.addAll(snapshot)
            adapter.notifyDataSetChanged()
        }
    }

    private inner class GridAdapter : BaseAdapter() {
        override fun getCount() = items.size
        override fun getItem(position: Int) = items[position]
        override fun getItemId(position: Int) = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val iv = (convertView as? ImageView) ?: ImageView(this@MainActivity).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                setBackgroundColor(0xFF1C1C1E.toInt())
                layoutParams = android.widget.AbsListView.LayoutParams(-1, dp(120))
            }
            val uri = items[position]
            val key = uri.toString()
            iv.tag = key
            val cached = cache.get(key)
            if (cached != null) {
                iv.setImageBitmap(cached)
            } else {
                iv.setImageDrawable(null)
                thumbExec.execute {
                    val bmp = thumb(uri)
                    if (bmp != null) {
                        cache.put(key, bmp)
                        runOnUiThread { if (iv.tag == key) iv.setImageBitmap(bmp) }
                    }
                }
            }
            return iv
        }
    }

    private fun thumb(uri: Uri): Bitmap? {
        return try {
            val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, o) }
            var s = 1
            while (o.outWidth / (s * 2) >= 640) s *= 2
            val o2 = BitmapFactory.Options().apply { inSampleSize = s }
            contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, o2) }
        } catch (_: Throwable) {
            null
        }
    }
}
