package app.scanforge.android.viewer

import android.annotation.SuppressLint
import android.content.Context
import android.opengl.GLSurfaceView
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import app.scanforge.geometry.Vec3

/** GLSurfaceView with orbit (1 finger), pan (2 fingers), pinch zoom and tap-to-pick. */
@SuppressLint("ViewConstructor", "ClickableViewAccessibility")
class MeshViewerView(context: Context, private val onTapRay: (Vec3, Vec3) -> Unit) : GLSurfaceView(context) {
    val meshRenderer = MeshRenderer()
    private var lastX = 0f; private var lastY = 0f; private var lastPointers = 0

    init {
        setEGLContextClientVersion(3)
        setRenderer(meshRenderer)
        renderMode = RENDERMODE_CONTINUOUSLY
        contentDescription = "3D model viewer. Drag to rotate, two fingers to pan, pinch to zoom, tap to place a measurement point."
    }

    private val scale = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(d: ScaleGestureDetector): Boolean { queueEvent { meshRenderer.camera.zoom(d.scaleFactor) }; return true }
    })

    private val gestures = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onSingleTapUp(e: MotionEvent): Boolean {
            val (w, h) = meshRenderer.viewSize()
            val (o, d) = meshRenderer.camera.ray(e.x, e.y, w, h)
            onTapRay(o, d)
            return true
        }
        override fun onDoubleTap(e: MotionEvent): Boolean { queueEvent { meshRenderer.camera.reset() }; return true }
    })

    override fun onTouchEvent(e: MotionEvent): Boolean {
        scale.onTouchEvent(e)
        gestures.onTouchEvent(e)
        val x = (0 until e.pointerCount).map { e.getX(it) }.average().toFloat()
        val y = (0 until e.pointerCount).map { e.getY(it) }.average().toFloat()
        if (e.actionMasked == MotionEvent.ACTION_MOVE && e.pointerCount == lastPointers && !scale.isInProgress) {
            val dx = x - lastX; val dy = y - lastY
            val pointers = e.pointerCount // MotionEvent is recycled after this call; never read it inside queueEvent
            val h = height
            queueEvent { if (pointers == 1) meshRenderer.camera.orbit(dx, dy) else meshRenderer.camera.pan(dx, dy, h) }
        }
        lastX = x; lastY = y; lastPointers = e.pointerCount
        return true
    }
}
