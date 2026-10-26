package com.marco.photosphere

import android.content.Context
import android.graphics.Bitmap
import android.opengl.GLSurfaceView
import android.view.MotionEvent
import android.view.ScaleGestureDetector

/** Viewer 360°: trascinamento, inerzia, pinch per lo zoom. */
class SphereView(ctx: Context, bitmap: Bitmap, geo: Geo) : GLSurfaceView(ctx) {

    val renderer = SphereRenderer(bitmap, geo)
    private var lastX = 0f
    private var lastY = 0f

    private val scale = ScaleGestureDetector(ctx, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(d: ScaleGestureDetector): Boolean {
            renderer.fov = (renderer.fov / d.scaleFactor).coerceIn(25f, 110f)
            return true
        }
    })

    init {
        setEGLContextClientVersion(2)
        preserveEGLContextOnPause = true
        setRenderer(renderer)
        renderMode = RENDERMODE_CONTINUOUSLY
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        scale.onTouchEvent(e)
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lastX = e.x
                lastY = e.y
                renderer.dragging = true
                renderer.yawVel = 0f
                renderer.pitchVel = 0f
            }
            MotionEvent.ACTION_POINTER_DOWN, MotionEvent.ACTION_POINTER_UP -> {
                val i = if (e.actionIndex == 0) 1 else 0
                if (i < e.pointerCount) {
                    lastX = e.getX(i)
                    lastY = e.getY(i)
                }
            }
            MotionEvent.ACTION_MOVE -> {
                if (e.pointerCount == 1 && !scale.isInProgress) {
                    val k = renderer.fov / height.coerceAtLeast(1)
                    val dx = e.x - lastX
                    val dy = e.y - lastY
                    lastX = e.x
                    lastY = e.y
                    renderer.yaw -= dx * k
                    renderer.yawVel = -dx * k
                    if (!renderer.sensorMode) {
                        renderer.pitch = (renderer.pitch + dy * k).coerceIn(-89f, 89f)
                        renderer.pitchVel = dy * k
                    }
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                renderer.dragging = false
            }
        }
        return true
    }
}
