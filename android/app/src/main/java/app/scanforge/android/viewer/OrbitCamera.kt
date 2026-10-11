package app.scanforge.android.viewer

import android.opengl.Matrix
import app.scanforge.geometry.Aabb
import app.scanforge.geometry.Vec3
import kotlin.math.cos
import kotlin.math.sin

/** Orbit/pan/zoom camera around a target point (scan space, +Y up). */
class OrbitCamera {
    var target = Vec3.ZERO
    var distance = 0.5
    var yaw = Math.toRadians(35.0)
    var pitch = Math.toRadians(25.0)
    var orthographic = false
    private var aspect = 1.0
    private var home = Triple(Vec3.ZERO, 0.5, 0.0)

    val view = FloatArray(16)
    val proj = FloatArray(16)
    val viewProj = FloatArray(16)
    private val inv = FloatArray(16)

    fun frame(bounds: Aabb) {
        target = bounds.center
        distance = maxOf(bounds.size.length() * 1.6, 0.05)
        yaw = Math.toRadians(35.0); pitch = Math.toRadians(25.0)
        home = Triple(target, distance, yaw)
    }

    fun reset() { target = home.first; distance = home.second; yaw = home.third; pitch = Math.toRadians(25.0) }

    fun eye(): Vec3 = target + Vec3(cos(pitch) * sin(yaw), sin(pitch), cos(pitch) * cos(yaw)) * distance

    fun orbit(dxPixels: Float, dyPixels: Float) {
        yaw -= dxPixels * 0.008
        pitch = (pitch + dyPixels * 0.008).coerceIn(-1.5, 1.5)
    }

    fun pan(dxPixels: Float, dyPixels: Float, viewHeight: Int) {
        val scale = distance * 1.2 / viewHeight
        val fwd = (target - eye()).normalized()
        val right = (fwd cross Vec3.Y).normalized()
        val up = right cross fwd
        target = target - right * (dxPixels * scale) + up * (dyPixels * scale)
    }

    fun zoom(factor: Float) { distance = (distance / factor).coerceIn(0.01, 50.0) }

    fun update(width: Int, height: Int) {
        aspect = width.toDouble() / maxOf(1, height)
        val e = eye()
        Matrix.setLookAtM(view, 0, e.x.toFloat(), e.y.toFloat(), e.z.toFloat(), target.x.toFloat(), target.y.toFloat(), target.z.toFloat(), 0f, 1f, 0f)
        val near = (distance * 0.01).toFloat(); val far = (distance * 100).toFloat()
        if (orthographic) {
            val h = (distance * 0.5).toFloat(); val w = (h * aspect).toFloat()
            Matrix.orthoM(proj, 0, -w, w, -h, h, near, far)
        } else {
            Matrix.perspectiveM(proj, 0, 45f, aspect.toFloat(), near, far)
        }
        Matrix.multiplyMM(viewProj, 0, proj, 0, view, 0)
        Matrix.invertM(inv, 0, viewProj, 0)
    }

    /** World-space ray through a pixel: (origin, direction). */
    fun ray(px: Float, py: Float, width: Int, height: Int): Pair<Vec3, Vec3> {
        val x = 2f * px / width - 1f; val y = 1f - 2f * py / height
        fun unproject(z: Float): Vec3 {
            val out = FloatArray(4)
            Matrix.multiplyMV(out, 0, inv, 0, floatArrayOf(x, y, z, 1f), 0)
            return Vec3((out[0] / out[3]).toDouble(), (out[1] / out[3]).toDouble(), (out[2] / out[3]).toDouble())
        }
        val near = unproject(-1f); val far = unproject(1f)
        return near to (far - near).normalized()
    }
}
