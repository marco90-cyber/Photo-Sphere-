package com.marco.photosphere

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.core.content.FileProvider
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/**
 * Aggiornamenti in app dalle Release di GitHub (stesso motore in tutte le app di Marco).
 *
 * Controlla l'ultima release, scarica l'APK con avanzamento, Pausa / Riprendi / Annulla
 * (la ripresa continua da dove si era fermata grazie alle richieste HTTP "Range"),
 * verifica che il file sia davvero un aggiornamento di questa app e apre l'installatore di Android.
 * Lo schermo resta acceso durante il download (lo gestisce la finestra di aggiornamento).
 */
object AppUpdate {
    const val REPO = "marco90-cyber/Photo-Sphere-"
    const val APP = "PhotoSphere"
    const val RELEASES_PAGE = "https://github.com/$REPO/releases"
    private const val API = "https://api.github.com/repos/$REPO/releases/latest"
    private const val AUTHORITY_SUFFIX = ".appupdate"
    /** true se il numero di versione si ricava dal tag come x.y.z -> x*10000 + y*100 + z. */
    private const val SEMVER_CODE = false

    class Release(
        val code: Long,
        val name: String,
        val apkUrl: String,
        val size: Long,
        val pageUrl: String,
        val notes: String,
    )

    sealed class State {
        object Idle : State()
        object Checking : State()
        class UpToDate(val installed: String) : State()
        class Available(val release: Release) : State()
        class Downloading(val release: Release, val done: Long, val total: Long, val speed: Long) : State()
        class Paused(val release: Release, val done: Long, val total: Long) : State()
        class Ready(val release: Release, val file: File) : State()
        /** [release] non nullo: il download si può riprendere. */
        class Failed(val message: String, val release: Release?, val done: Long = 0, val total: Long = 0) : State()
    }

    fun interface Listener { fun onChanged() }

    @Volatile var state: State = State.Idle
        private set
    /** Finestra di aggiornamento visibile (la mostra [AppUpdateHost]). */
    @Volatile var visible: Boolean = false
        private set

    private val listeners = CopyOnWriteArrayList<Listener>()
    private val main = Handler(Looper.getMainLooper())
    private val run = AtomicInteger()
    @Volatile private var conn: HttpURLConnection? = null
    @Volatile private var cancelledRun = -1

    fun addListener(l: Listener) { listeners += l }
    fun removeListener(l: Listener) { listeners -= l }

    private val lock = Any()

    private fun fire() = main.post { listeners.forEach { it.onChanged() } }

    private fun set(s: State) {
        state = s
        fire()
    }

    /** Aggiorna lo stato solo se il download [id] è ancora quello attivo. */
    private fun setIf(id: Int, s: State) = synchronized(lock) { if (run.get() == id) set(s) }

    // ------------------------------------------------------------ versioni

    fun installedName(ctx: Context): String = try {
        ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: ""
    } catch (e: Exception) { "" }

    fun installedCode(ctx: Context): Long = try {
        val pi = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
        if (Build.VERSION.SDK_INT >= 28) pi.longVersionCode else @Suppress("DEPRECATION") pi.versionCode.toLong()
    } catch (e: Exception) { 0L }

    private fun tagToCode(tag: String): Long {
        val nums = Regex("\\d+").findAll(tag).map { it.value.toLong() }.toList()
        if (nums.isEmpty()) return 0
        if (SEMVER_CODE) {
            val p = nums + listOf(0L, 0L, 0L)
            return p[0] * 10000 + p[1] * 100 + p[2]
        }
        return nums.last()
    }

    // ------------------------------------------------------------ azioni

    /** Apre la finestra e, se serve, controlla subito. */
    fun open(ctx: Context) {
        visible = true
        when (val s = state) {
            is State.Downloading, is State.Paused, State.Checking -> fire()
            is State.Ready -> if (s.file.exists()) fire() else check(ctx)
            is State.Failed -> if (s.release != null) fire() else check(ctx)
            else -> check(ctx)
        }
    }

    /** Chiude la finestra (non durante il download). */
    fun hide() {
        if (state is State.Downloading) return
        visible = false
        fire()
    }

    fun check(ctx: Context) {
        val s = state
        if (s is State.Checking || s is State.Downloading) return
        val app = ctx.applicationContext
        set(State.Checking)
        Thread {
            try {
                val r = fetchLatest(app)
                val installed = installedCode(app)
                if (r.code <= installed || r.apkUrl.isBlank()) {
                    set(State.UpToDate(installedName(app)))
                } else {
                    val apk = finalFile(app, r)
                    val part = partFile(app, r)
                    set(
                        when {
                            apk.exists() && apk.length() > 0 -> State.Ready(r, apk)
                            part.exists() && part.length() > 0 -> State.Paused(r, part.length(), r.size)
                            else -> State.Available(r)
                        }
                    )
                }
            } catch (e: Exception) {
                set(State.Failed("Impossibile controllare gli aggiornamenti. Controlla la connessione.", null))
            }
        }.apply { name = "AppUpdate-check"; isDaemon = true }.start()
    }

    /** Avvia o riprende il download. */
    fun download(ctx: Context) {
        val r = when (val s = state) {
            is State.Available -> s.release
            is State.Paused -> s.release
            is State.Failed -> s.release
            else -> null
        } ?: return
        val app = ctx.applicationContext
        val startDone = partFile(app, r).length()
        val id = synchronized(lock) {
            val n = run.incrementAndGet()
            set(State.Downloading(r, startDone, r.size, 0))
            n
        }
        Thread { downloadLoop(app, r, id) }.apply { name = "AppUpdate-download"; isDaemon = true }.start()
    }

    fun pause() {
        synchronized(lock) {
            val s = state as? State.Downloading ?: return
            run.incrementAndGet()
            set(State.Paused(s.release, s.done, s.total))
        }
        try { conn?.disconnect() } catch (_: Exception) {}
    }

    fun cancel(ctx: Context) {
        val r = when (val s = state) {
            is State.Downloading -> s.release
            is State.Paused -> s.release
            is State.Failed -> s.release
            is State.Ready -> s.release
            else -> null
        } ?: return
        synchronized(lock) {
            cancelledRun = run.get()
            run.incrementAndGet()
            set(State.Available(r))
        }
        try { conn?.disconnect() } catch (_: Exception) {}
        partFile(ctx, r).delete()
        finalFile(ctx, r).delete()
    }

    fun canInstall(ctx: Context): Boolean =
        Build.VERSION.SDK_INT < 26 || ctx.packageManager.canRequestPackageInstalls()

    fun openInstallPermission(ctx: Context) {
        try {
            ctx.startActivity(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + ctx.packageName))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        } catch (_: Exception) {}
    }

    /** Apre l'installatore (o prima l'impostazione "Installa app sconosciute"). */
    fun install(ctx: Context) {
        val s = state as? State.Ready ?: return
        if (!s.file.exists()) { set(State.Available(s.release)); return }
        if (!canInstall(ctx)) { openInstallPermission(ctx); return }
        try {
            val uri = FileProvider.getUriForFile(ctx, ctx.packageName + AUTHORITY_SUFFIX, s.file)
            ctx.startActivity(
                Intent(Intent.ACTION_VIEW)
                    .setDataAndType(uri, "application/vnd.android.package-archive")
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        } catch (e: Exception) {
            set(State.Failed("Impossibile aprire l'installatore: ${e.message ?: e.javaClass.simpleName}", null))
        }
    }

    fun openPage(ctx: Context, url: String = RELEASES_PAGE) {
        try {
            ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: Exception) {}
    }

    // ------------------------------------------------------------ rete

    private fun connect(url: String, ua: String): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15000
            readTimeout = 30000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", ua)
        }

    private fun ua(ctx: Context) = "$APP/${installedName(ctx)} (Android)"

    private fun fetchLatest(ctx: Context): Release {
        val c = connect(API, ua(ctx))
        c.setRequestProperty("Accept", "application/vnd.github+json")
        try {
            if (c.responseCode != 200) throw IOException("GitHub ha risposto ${c.responseCode}")
            val o = JSONObject(c.inputStream.bufferedReader().use { it.readText() })
            val tag = o.optString("tag_name")
            var url = ""
            var size = 0L
            val assets = o.optJSONArray("assets")
            if (assets != null) for (i in 0 until assets.length()) {
                val a = assets.getJSONObject(i)
                if (a.optString("name").endsWith(".apk", ignoreCase = true)) {
                    url = a.optString("browser_download_url")
                    size = a.optLong("size")
                    break
                }
            }
            return Release(
                code = tagToCode(tag),
                name = o.optString("name").ifBlank { tag },
                apkUrl = url,
                size = size,
                pageUrl = o.optString("html_url").ifBlank { RELEASES_PAGE },
                notes = o.optString("body"),
            )
        } finally {
            c.disconnect()
        }
    }

    private fun dir(ctx: Context) = File(ctx.cacheDir, "updates").apply { mkdirs() }
    private fun partFile(ctx: Context, r: Release) = File(dir(ctx), "$APP-${r.code}.apk.part")
    private fun finalFile(ctx: Context, r: Release) = File(dir(ctx), "$APP-${r.code}.apk")

    private fun downloadLoop(ctx: Context, r: Release, id: Int) {
        val part = partFile(ctx, r)
        // via i file di versioni vecchie
        dir(ctx).listFiles()?.forEach { if (it.name != part.name) it.delete() }
        var done = part.length()
        var total = r.size
        try {
            if (total > 0 && done > total) { part.delete(); done = 0 }
            if (total <= 0 || done < total) {
                val c = connect(r.apkUrl, ua(ctx))
                c.setRequestProperty("Accept", "application/octet-stream")
                if (done > 0) c.setRequestProperty("Range", "bytes=$done-")
                conn = c
                try {
                    val code = c.responseCode
                    if (run.get() != id) return
                    val append = when (code) {
                        206 -> true
                        200 -> { done = 0; false }
                        416 -> if (total > 0 && done >= total) null else throw IOException("HTTP 416")
                        else -> throw IOException("HTTP $code")
                    }
                    if (append != null) {
                        if (total <= 0) {
                            val len = c.contentLengthLong
                            total = if (len > 0) len + (if (append) done else 0L) else -1L
                        }
                        FileOutputStream(part, append).use { out ->
                            c.inputStream.use { input ->
                                val buf = ByteArray(64 * 1024)
                                var lastUi = 0L
                                var speedMark = System.nanoTime()
                                var speedBytes = 0L
                                var speed = 0L
                                while (run.get() == id) {
                                    val n = input.read(buf)
                                    if (n < 0) break
                                    if (run.get() != id) break
                                    out.write(buf, 0, n)
                                    done += n
                                    speedBytes += n
                                    val now = System.nanoTime()
                                    if (now - speedMark >= 1_000_000_000L) {
                                        speed = speedBytes * 1_000_000_000L / (now - speedMark)
                                        speedMark = now
                                        speedBytes = 0
                                    }
                                    if (now - lastUi >= 150_000_000L) {
                                        lastUi = now
                                        setIf(id, State.Downloading(r, done, total, speed))
                                    }
                                }
                            }
                        }
                    }
                } finally {
                    conn = null
                    c.disconnect()
                }
            }
            if (run.get() != id) {
                if (cancelledRun == id) part.delete()
                return
            }
            if (total > 0 && part.length() < total) throw IOException("Download interrotto")
            // Controllo che sia davvero un aggiornamento di questa app
            val info = ctx.packageManager.getPackageArchiveInfo(part.absolutePath, 0)
            val newCode = when {
                info == null -> -1L
                Build.VERSION.SDK_INT >= 28 -> info.longVersionCode
                else -> @Suppress("DEPRECATION") info.versionCode.toLong()
            }
            if (info == null || info.packageName != ctx.packageName) {
                part.delete()
                setIf(id, State.Failed("Il file scaricato non è valido. Riprova.", r))
                return
            }
            if (newCode <= installedCode(ctx)) {
                part.delete()
                setIf(id, State.UpToDate(installedName(ctx)))
                return
            }
            val apk = finalFile(ctx, r)
            apk.delete()
            if (!part.renameTo(apk)) throw IOException("Impossibile salvare il file")
            setIf(id, State.Ready(r, apk))
            main.post { if (visible && canInstall(ctx)) install(ctx) }
        } catch (e: Exception) {
            if (run.get() != id) {
                if (cancelledRun == id) part.delete()
                return
            }
            setIf(id, State.Failed("Download interrotto. Controlla la connessione e tocca «Riprendi».", r, part.length(), total))
        }
    }

    // ------------------------------------------------------------ testo

    fun mb(bytes: Long): String = String.format(java.util.Locale.ITALY, "%.1f MB", bytes / 1048576.0)

    fun progressText(done: Long, total: Long, speed: Long = -1): String {
        val sb = StringBuilder()
        if (total > 0) {
            sb.append(mb(done)).append(" di ").append(mb(total))
            sb.append(" · ").append((done * 100 / total).coerceIn(0, 100)).append('%')
        } else sb.append(mb(done))
        if (speed > 0) sb.append(" · ").append(mb(speed)).append("/s")
        return sb.toString()
    }
}
