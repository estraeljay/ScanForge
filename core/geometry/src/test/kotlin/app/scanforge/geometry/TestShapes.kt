package app.scanforge.geometry

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** Analytic test meshes with exactly known dimensions. */
object TestShapes {
    /** Closed, outward-facing axis-aligned box with the given full sizes, centered at [center]. */
    fun box(sx: Double, sy: Double, sz: Double, center: Vec3 = Vec3.ZERO): Mesh {
        val h = Vec3(sx / 2, sy / 2, sz / 2)
        val v = (0 until 8).map { i ->
            center + Vec3(if (i and 1 != 0) h.x else -h.x, if (i and 2 != 0) h.y else -h.y, if (i and 4 != 0) h.z else -h.z)
        }
        val quads = listOf(
            intArrayOf(0, 2, 3, 1), intArrayOf(4, 5, 7, 6), // -z, +z
            intArrayOf(0, 1, 5, 4), intArrayOf(2, 6, 7, 3), // -y, +y
            intArrayOf(0, 4, 6, 2), intArrayOf(1, 3, 7, 5), // -x, +x
        )
        val idx = quads.flatMap { q -> listOf(q[0], q[1], q[2], q[0], q[2], q[3]) }.toIntArray()
        return Mesh.fromVertices(v, idx)
    }

    /** UV sphere, closed and outward facing. */
    fun sphere(radius: Double, rings: Int = 32, segments: Int = 64): Mesh {
        val verts = ArrayList<Vec3>()
        verts += Vec3(0.0, radius, 0.0)
        for (r in 1 until rings) {
            val phi = PI * r / rings
            for (s in 0 until segments) {
                val th = 2 * PI * s / segments
                verts += Vec3(radius * sin(phi) * cos(th), radius * cos(phi), radius * sin(phi) * sin(th))
            }
        }
        verts += Vec3(0.0, -radius, 0.0)
        val bottom = verts.size - 1
        val idx = ArrayList<Int>()
        fun ring(r: Int, s: Int) = 1 + (r - 1) * segments + (s % segments)
        for (s in 0 until segments) { idx += 0; idx += ring(1, s + 1); idx += ring(1, s) }
        for (r in 1 until rings - 1) for (s in 0 until segments) {
            val a = ring(r, s); val b = ring(r, s + 1); val c = ring(r + 1, s); val d = ring(r + 1, s + 1)
            idx += a; idx += b; idx += d; idx += a; idx += d; idx += c
        }
        for (s in 0 until segments) { idx += bottom; idx += ring(rings - 1, s); idx += ring(rings - 1, s + 1) }
        return Mesh.fromVertices(verts, idx.toIntArray())
    }
}
