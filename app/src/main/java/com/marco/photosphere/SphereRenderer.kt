package com.marco.photosphere

import android.graphics.Bitmap
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.GLUtils
import android.opengl.Matrix
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.ShortBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

/** Porzione di sfera coperta dall'immagine (radianti). */
class Geo(val lonL: Float, val lonR: Float, val latTop: Float, val latBottom: Float) {
    companion object {
        fun from(p: PanoInfo?, bw: Int, bh: Int): Geo {
            if (p != null && p.fullW > 0 && p.fullH > 0) {
                val twoPi = (2 * PI).toFloat()
                val pi = PI.toFloat()
                val l = (p.left.toFloat() / p.fullW - 0.5f) * twoPi
                val r = ((p.left + p.cropW).toFloat() / p.fullW - 0.5f) * twoPi
                val t = (0.5f - p.top.toFloat() / p.fullH) * pi
                val b = (0.5f - (p.top + p.cropH).toFloat() / p.fullH) * pi
                return Geo(l, r, t, b)
            }
            val aspect = bw.toFloat() / bh
            val lonSpan: Float
            val latSpan: Float
            if (aspect >= 2f) {
                lonSpan = (2 * PI).toFloat(); latSpan = (2 * PI).toFloat() / aspect
            } else {
                latSpan = PI.toFloat(); lonSpan = PI.toFloat() * aspect
            }
            return Geo(-lonSpan / 2, lonSpan / 2, latSpan / 2, -latSpan / 2)
        }
    }
}

class SphereRenderer(private val bitmap: Bitmap, private val geo: Geo) : GLSurfaceView.Renderer {

    @Volatile var yaw = 0f
    @Volatile var pitch = 0f
    @Volatile var fov = 80f
    @Volatile var sensorMode = false
    @Volatile var sensorR: FloatArray? = null
    @Volatile var yawVel = 0f
    @Volatile var pitchVel = 0f
    @Volatile var dragging = false

    private var program = 0
    private var aPos = 0
    private var aTex = 0
    private var uMvp = 0
    private var uTex = 0
    private var tex = 0
    private lateinit var vb: FloatBuffer
    private lateinit var ib: ShortBuffer
    private var indexCount = 0
    private var aspect = 1f

    private val proj = FloatArray(16)
    private val view = FloatArray(16)
    private val mvp = FloatArray(16)
    private val rx = FloatArray(16)
    private val ry = FloatArray(16)
    private val sm = FloatArray(16)

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        GLES20.glDisable(GLES20.GL_DEPTH_TEST)
        GLES20.glDisable(GLES20.GL_CULL_FACE)
        program = link(VS, FS)
        aPos = GLES20.glGetAttribLocation(program, "aPos")
        aTex = GLES20.glGetAttribLocation(program, "aTex")
        uMvp = GLES20.glGetUniformLocation(program, "uMvp")
        uTex = GLES20.glGetUniformLocation(program, "uTex")
        buildMesh()

        val ids = IntArray(1)
        GLES20.glGenTextures(1, ids, 0)
        tex = ids[0]
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, tex)
        val maxTex = IntArray(1)
        GLES20.glGetIntegerv(GLES20.GL_MAX_TEXTURE_SIZE, maxTex, 0)
        var b = bitmap
        val big = max(b.width, b.height)
        if (big > maxTex[0]) {
            val sc = maxTex[0].toFloat() / big
            b = Bitmap.createScaledBitmap(b, (b.width * sc).toInt().coerceAtLeast(1), (b.height * sc).toInt().coerceAtLeast(1), true)
        }
        val pot = isPot(b.width) && isPot(b.height)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER,
            if (pot) GLES20.GL_LINEAR_MIPMAP_LINEAR else GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, b, 0)
        if (pot) GLES20.glGenerateMipmap(GLES20.GL_TEXTURE_2D)
        if (b !== bitmap) b.recycle()
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        GLES20.glViewport(0, 0, width, height)
        aspect = width.toFloat() / max(1, height)
    }

    override fun onDrawFrame(gl: GL10?) {
        // inerzia
        if (!dragging) {
            if (yawVel != 0f || pitchVel != 0f) {
                yaw += yawVel
                if (!sensorMode) pitch = (pitch + pitchVel).coerceIn(-89f, 89f)
                yawVel *= 0.93f
                pitchVel *= 0.93f
                if (kotlin.math.abs(yawVel) < 0.005f) yawVel = 0f
                if (kotlin.math.abs(pitchVel) < 0.005f) pitchVel = 0f
            }
        }

        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        Matrix.perspectiveM(proj, 0, fov, aspect, 0.1f, 10f)

        Matrix.setRotateM(ry, 0, yaw, 0f, 1f, 0f)
        val r = sensorR
        if (sensorMode && r != null) {
            // matrice vista = R^T * T^-1 (mondo ENU -> spazio vista), poi offset di yaw
            java.util.Arrays.fill(sm, 0f)
            for (i in 0 until 3) {
                sm[0 * 4 + i] = r[i]
                sm[1 * 4 + i] = r[6 + i]
                sm[2 * 4 + i] = -r[3 + i]
            }
            sm[15] = 1f
            Matrix.multiplyMM(view, 0, sm, 0, ry, 0)
        } else {
            Matrix.setRotateM(rx, 0, -pitch, 1f, 0f, 0f)
            Matrix.multiplyMM(view, 0, rx, 0, ry, 0)
        }
        Matrix.multiplyMM(mvp, 0, proj, 0, view, 0)

        GLES20.glUseProgram(program)
        GLES20.glUniformMatrix4fv(uMvp, 1, false, mvp, 0)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, tex)
        GLES20.glUniform1i(uTex, 0)
        vb.position(0)
        GLES20.glVertexAttribPointer(aPos, 3, GLES20.GL_FLOAT, false, 20, vb)
        GLES20.glEnableVertexAttribArray(aPos)
        vb.position(3)
        GLES20.glVertexAttribPointer(aTex, 2, GLES20.GL_FLOAT, false, 20, vb)
        GLES20.glEnableVertexAttribArray(aTex)
        GLES20.glDrawElements(GLES20.GL_TRIANGLES, indexCount, GLES20.GL_UNSIGNED_SHORT, ib)
    }

    private fun buildMesh() {
        val stacks = 48
        val slices = 96
        val verts = FloatArray((stacks + 1) * (slices + 1) * 5)
        var o = 0
        for (i in 0..stacks) {
            val t = i.toFloat() / stacks
            val lat = geo.latTop + (geo.latBottom - geo.latTop) * t
            val cl = cos(lat)
            val sl = sin(lat)
            for (j in 0..slices) {
                val u = j.toFloat() / slices
                val lon = geo.lonL + (geo.lonR - geo.lonL) * u
                verts[o++] = cl * sin(lon)
                verts[o++] = sl
                verts[o++] = -cl * cos(lon)
                verts[o++] = u
                verts[o++] = t
            }
        }
        val idx = ShortArray(stacks * slices * 6)
        var k = 0
        for (i in 0 until stacks) {
            for (j in 0 until slices) {
                val a = (i * (slices + 1) + j).toShort()
                val b = (i * (slices + 1) + j + 1).toShort()
                val c = ((i + 1) * (slices + 1) + j).toShort()
                val d = ((i + 1) * (slices + 1) + j + 1).toShort()
                idx[k++] = a; idx[k++] = c; idx[k++] = b
                idx[k++] = b; idx[k++] = c; idx[k++] = d
            }
        }
        indexCount = idx.size
        vb = ByteBuffer.allocateDirect(verts.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
        vb.put(verts).position(0)
        ib = ByteBuffer.allocateDirect(idx.size * 2).order(ByteOrder.nativeOrder()).asShortBuffer()
        ib.put(idx).position(0)
    }

    private fun isPot(v: Int) = v > 0 && (v and (v - 1)) == 0

    private fun link(vs: String, fs: String): Int {
        val v = compile(GLES20.GL_VERTEX_SHADER, vs)
        val f = compile(GLES20.GL_FRAGMENT_SHADER, fs)
        val p = GLES20.glCreateProgram()
        GLES20.glAttachShader(p, v)
        GLES20.glAttachShader(p, f)
        GLES20.glLinkProgram(p)
        return p
    }

    private fun compile(type: Int, src: String): Int {
        val s = GLES20.glCreateShader(type)
        GLES20.glShaderSource(s, src)
        GLES20.glCompileShader(s)
        return s
    }

    companion object {
        private const val VS = "uniform mat4 uMvp;\n" +
            "attribute vec4 aPos;\n" +
            "attribute vec2 aTex;\n" +
            "varying vec2 vTex;\n" +
            "void main() {\n" +
            "  gl_Position = uMvp * aPos;\n" +
            "  vTex = aTex;\n" +
            "}\n"
        private const val FS = "precision mediump float;\n" +
            "uniform sampler2D uTex;\n" +
            "varying vec2 vTex;\n" +
            "void main() {\n" +
            "  gl_FragColor = texture2D(uTex, vTex);\n" +
            "}\n"
    }
}
