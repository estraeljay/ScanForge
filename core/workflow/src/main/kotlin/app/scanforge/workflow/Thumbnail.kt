package app.scanforge.workflow

import app.scanforge.geometry.Mesh
import app.scanforge.geometry.Pose
import app.scanforge.geometry.Vec3
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * Software-rasterized preview image of a mesh (z-buffered, Lambert-shaded, vertex colors), so thumbnails
 * reflect the actual reconstruction on every platform without a GPU context.
 */
object Thumbnail {
    /** Returns ARGB pixels (row-major, size×size). */
    fun render(mesh: Mesh, size: Int = 256, background: Int = 0xFF1B1E22.toInt()): IntArray {
        val px = IntArray(size * size) { background }
        val b = mesh.bounds() ?: return px
        val cam = Pose.lookAt(b.center + Vec3(0.8, 0.65, 1.0).normalized() * (b.size.length() * 1.5 + 1e-6), b.center)
        val inv = cam.inverse()
        val n = mesh.vertexCount
        val sx = DoubleArray(n); val sy = DoubleArray(n); val sz = DoubleArray(n)
        var minX = Double.MAX_VALUE; var maxX = -Double.MAX_VALUE; var minY = Double.MAX_VALUE; var maxY = -Double.MAX_VALUE
        for (i in 0 until n) {
            val p = inv.transformPoint(mesh.vertex(i)) // orthographic view
            sx[i] = p.x; sy[i] = p.y; sz[i] = p.z
            minX = min(minX, p.x); maxX = max(maxX, p.x); minY = min(minY, p.y); maxY = max(maxY, p.y)
        }
        val scale = (size * 0.88) / max(maxX - minX, maxY - minY).coerceAtLeast(1e-9)
        val ox = size / 2.0 - (minX + maxX) / 2 * scale; val oy = size / 2.0 + (minY + maxY) / 2 * scale
        for (i in 0 until n) { sx[i] = sx[i] * scale + ox; sy[i] = oy - sy[i] * scale }
        val zbuf = DoubleArray(size * size) { -Double.MAX_VALUE }
        val light = Vec3(0.3, 0.5, 1.0).normalized()
        for (t in 0 until mesh.triangleCount) {
            val (a, bb, c) = mesh.triangle(t)
            val nrm = (mesh.vertex(bb) - mesh.vertex(a)) cross (mesh.vertex(c) - mesh.vertex(a))
            if (nrm.length() == 0.0) continue
            val shade = 0.3 + 0.7 * abs(inv.rotateVector(nrm.normalized()) dot light)
            val base = mesh.colors?.let { col ->
                IntArray(3) { k -> ((col[a * 3 + k].toInt() and 0xff) + (col[bb * 3 + k].toInt() and 0xff) + (col[c * 3 + k].toInt() and 0xff)) / 3 }
            } ?: intArrayOf(190, 196, 204)
            val color = (0xFF shl 24) or ((base[0] * shade).toInt().coerceIn(0, 255) shl 16) or ((base[1] * shade).toInt().coerceIn(0, 255) shl 8) or (base[2] * shade).toInt().coerceIn(0, 255)
            val x0 = floor(min(sx[a], min(sx[bb], sx[c]))).toInt().coerceAtLeast(0); val x1 = ceil(max(sx[a], max(sx[bb], sx[c]))).toInt().coerceAtMost(size - 1)
            val y0 = floor(min(sy[a], min(sy[bb], sy[c]))).toInt().coerceAtLeast(0); val y1 = ceil(max(sy[a], max(sy[bb], sy[c]))).toInt().coerceAtMost(size - 1)
            val area = (sx[bb] - sx[a]) * (sy[c] - sy[a]) - (sx[c] - sx[a]) * (sy[bb] - sy[a])
            if (abs(area) < 1e-12) continue
            for (y in y0..y1) for (x in x0..x1) {
                val px0 = x + 0.5; val py0 = y + 0.5
                val w0 = ((sx[bb] - px0) * (sy[c] - py0) - (sx[c] - px0) * (sy[bb] - py0)) / area
                val w1 = ((sx[c] - px0) * (sy[a] - py0) - (sx[a] - px0) * (sy[c] - py0)) / area
                val w2 = 1 - w0 - w1
                if (w0 < 0 || w1 < 0 || w2 < 0) continue
                val z = w0 * sz[a] + w1 * sz[bb] + w2 * sz[c]
                val idx = y * size + x
                if (z > zbuf[idx]) { zbuf[idx] = z; px[idx] = color }
            }
        }
        return px
    }
}
