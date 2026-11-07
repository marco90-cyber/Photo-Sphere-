package com.marco.photosphere

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/** Uno scatto del progetto: file originale, orientamento dai sensori e dall'allineamento, correzioni manuali. */
class ShotRec(var file: String, val R: FloatArray) {
    var enabled = true
    var ar: FloatArray? = null       // rotazione dopo l'allineamento automatico
    var gain: FloatArray? = null     // guadagno RGB di esposizione
    var dyaw = 0f                    // correzione manuale (gradi, nel sistema dello scatto)
    var dpitch = 0f
    var droll = 0f
    var weak = false                 // allineamento incerto
    var sharp = 0f

    fun toJson(): JSONObject = JSONObject().apply {
        put("file", file)
        put("R", arr(R))
        put("enabled", enabled)
        ar?.let { put("ar", arr(it)) }
        gain?.let { put("gain", arr(it)) }
        put("dyaw", dyaw.toDouble()); put("dpitch", dpitch.toDouble()); put("droll", droll.toDouble())
        put("weak", weak)
        put("sharp", sharp.toDouble())
    }

    companion object {
        fun fromJson(o: JSONObject): ShotRec {
            val s = ShotRec(o.getString("file"), farr(o.getJSONArray("R")))
            s.enabled = o.optBoolean("enabled", true)
            o.optJSONArray("ar")?.let { s.ar = farr(it) }
            o.optJSONArray("gain")?.let { s.gain = farr(it) }
            s.dyaw = o.optDouble("dyaw", 0.0).toFloat()
            s.dpitch = o.optDouble("dpitch", 0.0).toFloat()
            s.droll = o.optDouble("droll", 0.0).toFloat()
            s.weak = o.optBoolean("weak", false)
            s.sharp = o.optDouble("sharp", 0.0).toFloat()
            return s
        }
    }
}

/** Toppa: immagine rettilinea incollata sulla sfera, centrata a (lon, lat) con campo visivo orizzontale fov (gradi). */
class PatchRec(var file: String, var lon: Float, var lat: Float, var fov: Float) {
    var rot = 0f
    var shape = 0            // 0 = cerchio, 1 = rettangolo
    var feather = 0.3f       // frazione del raggio usata per la sfumatura del bordo
    var applied = false      // false: estratta ma non ancora reimportata

    fun toJson(): JSONObject = JSONObject().apply {
        put("file", file)
        put("lon", lon.toDouble()); put("lat", lat.toDouble()); put("fov", fov.toDouble())
        put("rot", rot.toDouble()); put("shape", shape); put("feather", feather.toDouble())
        put("applied", applied)
    }

    companion object {
        fun fromJson(o: JSONObject): PatchRec {
            val p = PatchRec(
                o.getString("file"), o.optDouble("lon").toFloat(), o.optDouble("lat").toFloat(),
                o.optDouble("fov", 60.0).toFloat()
            )
            p.rot = o.optDouble("rot", 0.0).toFloat()
            p.shape = o.optInt("shape", 0)
            p.feather = o.optDouble("feather", 0.3).toFloat()
            p.applied = o.optBoolean("applied", false)
            return p
        }
    }
}

class CropInfo(val fullW: Int, val fullH: Int, val cropW: Int, val cropH: Int, val left: Int, val top: Int) {
    val isFull: Boolean get() = cropW == fullW && cropH == fullH && left == 0 && top == 0

    fun toJson(): JSONObject = JSONObject().apply {
        put("fullW", fullW); put("fullH", fullH); put("cropW", cropW); put("cropH", cropH)
        put("left", left); put("top", top)
    }

    companion object {
        fun fromJson(o: JSONObject?): CropInfo? = o?.let {
            CropInfo(it.getInt("fullW"), it.getInt("fullH"), it.getInt("cropW"), it.getInt("cropH"), it.getInt("left"), it.getInt("top"))
        }
    }
}

/** Un progetto = uno scatto 360° con tutti i suoi originali e le modifiche, per poterlo rielaborare. */
class Project(val id: String, val dir: File) {
    var createdAt = System.currentTimeMillis()
    var name = ""
    var fovLong = Math.toRadians(68.0)
    var yaw0 = 0f
    var outW = 4096
    val shots = ArrayList<ShotRec>()
    var sf = 1f
    var vig: FloatArray? = null
    var base: CropInfo? = null       // risultato della cucitura (base.jpg)
    var pre: CropInfo? = null        // base + toppe, senza livellamento (pre.jpg)
    var final: CropInfo? = null      // risultato finale (final.jpg)
    var coverage = 0f
    var levelRoll = 0f
    var levelPitch = 0f
    var hasInitial = false
    var initLon = 0f
    var initLat = 0f
    var initHFov = 75f
    val patches = ArrayList<PatchRec>()
    var resultUri: String? = null
    var lastError: String? = null
    var keepShots = true

    fun file(n: String) = File(dir, n)
    fun shotFile(s: ShotRec) = File(dir, s.file)
    val baseFile get() = file("base.jpg")
    val preFile get() = file("pre.jpg")
    val finalFile get() = file("final.jpg")
    val thumbFile get() = file("thumb.jpg")

    val enabledShots: List<ShotRec> get() = shots.filter { it.enabled }

    fun sizeBytes(): Long = dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }

    fun save() {
        val o = JSONObject()
        o.put("id", id); o.put("createdAt", createdAt); o.put("name", name)
        o.put("fovLong", fovLong); o.put("yaw0", yaw0.toDouble()); o.put("outW", outW)
        o.put("shots", JSONArray().also { a -> shots.forEach { a.put(it.toJson()) } })
        o.put("sf", sf.toDouble())
        vig?.let { o.put("vig", arr(it)) }
        base?.let { o.put("base", it.toJson()) }
        pre?.let { o.put("pre", it.toJson()) }
        final?.let { o.put("final", it.toJson()) }
        o.put("coverage", coverage.toDouble())
        o.put("levelRoll", levelRoll.toDouble()); o.put("levelPitch", levelPitch.toDouble())
        o.put("hasInitial", hasInitial)
        o.put("initLon", initLon.toDouble()); o.put("initLat", initLat.toDouble()); o.put("initHFov", initHFov.toDouble())
        o.put("patches", JSONArray().also { a -> patches.forEach { a.put(it.toJson()) } })
        resultUri?.let { o.put("resultUri", it) }
        lastError?.let { o.put("lastError", it) }
        o.put("keepShots", keepShots)
        val tmp = File(dir, "project.json.tmp")
        tmp.writeText(o.toString())
        val dst = File(dir, "project.json")
        if (!tmp.renameTo(dst)) {
            dst.writeText(o.toString())
            tmp.delete()
        }
    }

    companion object {
        fun load(dir: File): Project? {
            return try {
                val o = JSONObject(File(dir, "project.json").readText())
                val p = Project(o.getString("id"), dir)
                p.createdAt = o.optLong("createdAt", dir.lastModified())
                p.name = o.optString("name", "")
                p.fovLong = o.optDouble("fovLong", Math.toRadians(68.0))
                p.yaw0 = o.optDouble("yaw0", 0.0).toFloat()
                p.outW = o.optInt("outW", 4096)
                val sa = o.optJSONArray("shots") ?: JSONArray()
                for (i in 0 until sa.length()) p.shots.add(ShotRec.fromJson(sa.getJSONObject(i)))
                p.sf = o.optDouble("sf", 1.0).toFloat()
                o.optJSONArray("vig")?.let { p.vig = farr(it) }
                p.base = CropInfo.fromJson(o.optJSONObject("base"))
                p.pre = CropInfo.fromJson(o.optJSONObject("pre"))
                p.final = CropInfo.fromJson(o.optJSONObject("final"))
                p.coverage = o.optDouble("coverage", 0.0).toFloat()
                p.levelRoll = o.optDouble("levelRoll", 0.0).toFloat()
                p.levelPitch = o.optDouble("levelPitch", 0.0).toFloat()
                p.hasInitial = o.optBoolean("hasInitial", false)
                p.initLon = o.optDouble("initLon", 0.0).toFloat()
                p.initLat = o.optDouble("initLat", 0.0).toFloat()
                p.initHFov = o.optDouble("initHFov", 75.0).toFloat()
                val pa = o.optJSONArray("patches") ?: JSONArray()
                for (i in 0 until pa.length()) p.patches.add(PatchRec.fromJson(pa.getJSONObject(i)))
                if (o.has("resultUri")) p.resultUri = o.getString("resultUri")
                if (o.has("lastError")) p.lastError = o.getString("lastError")
                p.keepShots = o.optBoolean("keepShots", true)
                p
            } catch (e: Exception) {
                null
            }
        }
    }
}

object Projects {
    fun root(ctx: Context): File = File(ctx.filesDir, "projects").apply { mkdirs() }

    fun create(ctx: Context): Project {
        val id = "p" + System.currentTimeMillis()
        val dir = File(root(ctx), id)
        dir.mkdirs()
        val p = Project(id, dir)
        p.name = "PANO_" + java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US).format(java.util.Date())
        return p
    }

    fun list(ctx: Context): List<Project> =
        (root(ctx).listFiles() ?: emptyArray()).filter { it.isDirectory }
            .mapNotNull { Project.load(it) }
            .sortedByDescending { it.createdAt }

    fun get(ctx: Context, id: String): Project? {
        val d = File(root(ctx), id)
        return if (d.isDirectory) Project.load(d) else null
    }

    fun delete(p: Project) {
        p.dir.deleteRecursively()
    }
}

/** Stato di avanzamento in memoria dei lavori in corso (non serve scriverlo su disco). */
object Jobs {
    class State(var progress: Float, var message: String, var queued: Boolean)

    private val states = ConcurrentHashMap<String, State>()
    private val listeners = java.util.concurrent.CopyOnWriteArrayList<() -> Unit>()
    private val main = android.os.Handler(android.os.Looper.getMainLooper())

    fun state(id: String): State? = states[id]
    fun active(): Boolean = states.isNotEmpty()

    fun set(id: String, progress: Float, message: String, queued: Boolean = false) {
        val s = states[id]
        if (s == null) states[id] = State(progress, message, queued) else { s.progress = progress; s.message = message; s.queued = queued }
        fire()
    }

    fun clear(id: String) {
        states.remove(id)
        fire()
    }

    fun addListener(l: () -> Unit) { listeners.add(l) }
    fun removeListener(l: () -> Unit) { listeners.remove(l) }
    private fun fire() { main.post { listeners.forEach { it() } } }
}

internal fun arr(a: FloatArray): JSONArray = JSONArray().also { j -> a.forEach { j.put(it.toDouble()) } }
internal fun farr(j: JSONArray): FloatArray = FloatArray(j.length()) { j.getDouble(it).toFloat() }
