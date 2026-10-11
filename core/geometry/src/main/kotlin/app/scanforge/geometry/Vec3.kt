package app.scanforge.geometry

import kotlin.math.sqrt

/** Immutable 3D vector in double precision. All lengths in ScanForge are meters. */
data class Vec3(val x: Double, val y: Double, val z: Double) {
    operator fun plus(o: Vec3) = Vec3(x + o.x, y + o.y, z + o.z)
    operator fun minus(o: Vec3) = Vec3(x - o.x, y - o.y, z - o.z)
    operator fun times(s: Double) = Vec3(x * s, y * s, z * s)
    operator fun div(s: Double) = Vec3(x / s, y / s, z / s)
    operator fun unaryMinus() = Vec3(-x, -y, -z)
    operator fun get(axis: Int): Double = when (axis) {
        0 -> x; 1 -> y; 2 -> z
        else -> throw IndexOutOfBoundsException("axis $axis")
    }

    infix fun dot(o: Vec3) = x * o.x + y * o.y + z * o.z
    infix fun cross(o: Vec3) = Vec3(y * o.z - z * o.y, z * o.x - x * o.z, x * o.y - y * o.x)
    fun lengthSquared() = this dot this
    fun length() = sqrt(lengthSquared())
    fun distanceTo(o: Vec3) = (this - o).length()

    fun normalized(): Vec3 {
        val l = length()
        require(l > 0.0) { "Cannot normalize a zero-length vector" }
        return this / l
    }

    fun toFloatArray() = floatArrayOf(x.toFloat(), y.toFloat(), z.toFloat())

    companion object {
        val ZERO = Vec3(0.0, 0.0, 0.0)
        val X = Vec3(1.0, 0.0, 0.0)
        val Y = Vec3(0.0, 1.0, 0.0)
        val Z = Vec3(0.0, 0.0, 1.0)
        fun min(a: Vec3, b: Vec3) = Vec3(minOf(a.x, b.x), minOf(a.y, b.y), minOf(a.z, b.z))
        fun max(a: Vec3, b: Vec3) = Vec3(maxOf(a.x, b.x), maxOf(a.y, b.y), maxOf(a.z, b.z))
    }
}

/** Axis-aligned bounding box. */
data class Aabb(val min: Vec3, val max: Vec3) {
    val size: Vec3 get() = max - min
    val center: Vec3 get() = (min + max) * 0.5
    fun contains(p: Vec3) =
        p.x >= min.x && p.x <= max.x && p.y >= min.y && p.y <= max.y && p.z >= min.z && p.z <= max.z

    fun expanded(margin: Double) = Aabb(min - Vec3(margin, margin, margin), max + Vec3(margin, margin, margin))

    companion object {
        fun of(points: Iterable<Vec3>): Aabb? {
            var lo: Vec3? = null
            var hi: Vec3? = null
            for (p in points) {
                lo = if (lo == null) p else Vec3.min(lo, p)
                hi = if (hi == null) p else Vec3.max(hi, p)
            }
            return if (lo != null && hi != null) Aabb(lo, hi) else null
        }
    }
}
