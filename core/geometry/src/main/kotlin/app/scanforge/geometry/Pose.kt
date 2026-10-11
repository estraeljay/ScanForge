package app.scanforge.geometry

import kotlin.math.sqrt

/**
 * Row-major 3x3 matrix.
 */
class Mat3(val m: DoubleArray) {
    init { require(m.size == 9) }

    operator fun get(r: Int, c: Int) = m[r * 3 + c]
    operator fun times(v: Vec3) = Vec3(
        m[0] * v.x + m[1] * v.y + m[2] * v.z,
        m[3] * v.x + m[4] * v.y + m[5] * v.z,
        m[6] * v.x + m[7] * v.y + m[8] * v.z,
    )

    operator fun times(o: Mat3): Mat3 {
        val r = DoubleArray(9)
        for (i in 0 until 3) for (j in 0 until 3) {
            var s = 0.0
            for (k in 0 until 3) s += this[i, k] * o[k, j]
            r[i * 3 + j] = s
        }
        return Mat3(r)
    }

    fun transpose() = Mat3(doubleArrayOf(m[0], m[3], m[6], m[1], m[4], m[7], m[2], m[5], m[8]))
    fun column(c: Int) = Vec3(m[c], m[3 + c], m[6 + c])

    fun determinant() =
        m[0] * (m[4] * m[8] - m[5] * m[7]) - m[1] * (m[3] * m[8] - m[5] * m[6]) + m[2] * (m[3] * m[7] - m[4] * m[6])

    companion object {
        val IDENTITY = Mat3(doubleArrayOf(1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0))
        fun fromColumns(a: Vec3, b: Vec3, c: Vec3) = Mat3(doubleArrayOf(a.x, b.x, c.x, a.y, b.y, c.y, a.z, b.z, c.z))
    }
}

/** Unit quaternion (x, y, z, w) using the same component order as ARCore's Pose. */
data class Quat(val x: Double, val y: Double, val z: Double, val w: Double) {
    fun normalized(): Quat {
        val n = sqrt(x * x + y * y + z * z + w * w)
        require(n > 0.0) { "Zero quaternion" }
        return Quat(x / n, y / n, z / n, w / n)
    }

    fun conjugate() = Quat(-x, -y, -z, w)

    operator fun times(o: Quat) = Quat(
        w * o.x + x * o.w + y * o.z - z * o.y,
        w * o.y - x * o.z + y * o.w + z * o.x,
        w * o.z + x * o.y - y * o.x + z * o.w,
        w * o.w - x * o.x - y * o.y - z * o.z,
    )

    fun rotate(v: Vec3): Vec3 = toMatrix() * v

    fun toMatrix(): Mat3 {
        val xx = x * x; val yy = y * y; val zz = z * z
        val xy = x * y; val xz = x * z; val yz = y * z
        val wx = w * x; val wy = w * y; val wz = w * z
        return Mat3(
            doubleArrayOf(
                1 - 2 * (yy + zz), 2 * (xy - wz), 2 * (xz + wy),
                2 * (xy + wz), 1 - 2 * (xx + zz), 2 * (yz - wx),
                2 * (xz - wy), 2 * (yz + wx), 1 - 2 * (xx + yy),
            ),
        )
    }

    companion object {
        val IDENTITY = Quat(0.0, 0.0, 0.0, 1.0)

        fun fromAxisAngle(axis: Vec3, radians: Double): Quat {
            val a = axis.normalized()
            val s = kotlin.math.sin(radians / 2)
            return Quat(a.x * s, a.y * s, a.z * s, kotlin.math.cos(radians / 2))
        }

        /** Converts a proper rotation matrix to a quaternion (Shepperd's method). */
        fun fromMatrix(r: Mat3): Quat {
            val tr = r[0, 0] + r[1, 1] + r[2, 2]
            return when {
                tr > 0 -> {
                    val s = sqrt(tr + 1.0) * 2
                    Quat((r[2, 1] - r[1, 2]) / s, (r[0, 2] - r[2, 0]) / s, (r[1, 0] - r[0, 1]) / s, 0.25 * s)
                }
                r[0, 0] > r[1, 1] && r[0, 0] > r[2, 2] -> {
                    val s = sqrt(1.0 + r[0, 0] - r[1, 1] - r[2, 2]) * 2
                    Quat(0.25 * s, (r[0, 1] + r[1, 0]) / s, (r[0, 2] + r[2, 0]) / s, (r[2, 1] - r[1, 2]) / s)
                }
                r[1, 1] > r[2, 2] -> {
                    val s = sqrt(1.0 + r[1, 1] - r[0, 0] - r[2, 2]) * 2
                    Quat((r[0, 1] + r[1, 0]) / s, 0.25 * s, (r[1, 2] + r[2, 1]) / s, (r[0, 2] - r[2, 0]) / s)
                }
                else -> {
                    val s = sqrt(1.0 + r[2, 2] - r[0, 0] - r[1, 1]) * 2
                    Quat((r[0, 2] + r[2, 0]) / s, (r[1, 2] + r[2, 1]) / s, 0.25 * s, (r[1, 0] - r[0, 1]) / s)
                }
            }.normalized()
        }
    }
}

/**
 * Rigid transform. When used as a camera pose it maps camera-space points to world space,
 * matching ARCore's `Camera.getPose()` semantics.
 */
data class Pose(val translation: Vec3, val rotation: Quat) {
    fun transformPoint(p: Vec3): Vec3 = rotation.rotate(p) + translation
    fun rotateVector(v: Vec3): Vec3 = rotation.rotate(v)

    fun inverse(): Pose {
        val inv = rotation.conjugate()
        return Pose(-inv.rotate(translation), inv)
    }

    /** Returns `this * other` (apply [other] first). */
    fun compose(other: Pose) = Pose(transformPoint(other.translation), (rotation * other.rotation).normalized())

    companion object {
        val IDENTITY = Pose(Vec3.ZERO, Quat.IDENTITY)

        /**
         * Builds a camera pose (OpenGL/ARCore convention: camera looks down -Z, +Y up) at [eye] looking at [target].
         */
        fun lookAt(eye: Vec3, target: Vec3, up: Vec3 = Vec3.Y): Pose {
            val back = (eye - target).normalized() // camera +Z
            var right = up cross back
            if (right.length() < 1e-9) right = Vec3.X cross back
            right = right.normalized()
            val trueUp = back cross right
            return Pose(eye, Quat.fromMatrix(Mat3.fromColumns(right, trueUp, back)))
        }
    }
}
