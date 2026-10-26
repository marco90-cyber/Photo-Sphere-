package com.marco.photosphere

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlin.math.acos
import kotlin.math.sqrt

/**
 * Orientamento del telefono: rotation vector "game" (giroscopio + accelerometro, niente bussola,
 * quindi stabile e senza disturbi magnetici) + giroscopio per misurare la velocità angolare.
 * matrix: matrice 3x3 row-major dispositivo -> mondo (X=est, Y=nord, Z=su).
 */
class SensorHub(ctx: Context) : SensorEventListener {
    private val sm = ctx.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val rot: Sensor? = sm.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR)
        ?: sm.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
    private val gyro: Sensor? = sm.getDefaultSensor(Sensor.TYPE_GYROSCOPE)

    val available: Boolean get() = rot != null

    @Volatile
    var matrix: FloatArray = floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f)
        private set

    /** Velocità angolare in rad/s */
    @Volatile
    var speed: Float = 0f
        private set

    var onUpdate: (() -> Unit)? = null

    private val tmp = FloatArray(9)
    private var lastFwd: FloatArray? = null
    private var lastTs = 0L

    fun start() {
        rot?.let { sm.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
        gyro?.let { sm.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
    }

    fun stop() {
        sm.unregisterListener(this)
    }

    override fun onSensorChanged(e: SensorEvent) {
        when (e.sensor.type) {
            Sensor.TYPE_GAME_ROTATION_VECTOR, Sensor.TYPE_ROTATION_VECTOR -> {
                SensorManager.getRotationMatrixFromVector(tmp, e.values)
                val m = tmp.copyOf()
                matrix = m
                if (gyro == null) deriveSpeed(m, e.timestamp)
                onUpdate?.invoke()
            }
            Sensor.TYPE_GYROSCOPE -> {
                val x = e.values[0]
                val y = e.values[1]
                val z = e.values[2]
                speed = sqrt(x * x + y * y + z * z)
            }
        }
    }

    // Senza giroscopio la velocità si ricava dalla variazione della direzione di puntamento
    private fun deriveSpeed(m: FloatArray, ts: Long) {
        val fwd = floatArrayOf(-m[2], -m[5], -m[8])
        val prev = lastFwd
        if (prev != null && lastTs != 0L) {
            val dt = (ts - lastTs) / 1e9f
            if (dt > 0f) {
                val dot = (prev[0] * fwd[0] + prev[1] * fwd[1] + prev[2] * fwd[2]).coerceIn(-1f, 1f)
                speed = acos(dot) / dt
            }
        }
        lastFwd = fwd
        lastTs = ts
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
}
