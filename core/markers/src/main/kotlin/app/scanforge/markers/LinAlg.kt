package app.scanforge.markers

import kotlin.math.abs
import kotlin.math.sqrt

internal object LinAlg {
    /** Eigen-decomposition of a symmetric n×n matrix by cyclic Jacobi. Returns (eigenvalues, eigenvectors as columns). */
    fun symmetricEigen(a0: Array<DoubleArray>): Pair<DoubleArray, Array<DoubleArray>> {
        val n = a0.size
        val a = Array(n) { a0[it].copyOf() }
        val v = Array(n) { i -> DoubleArray(n) { j -> if (i == j) 1.0 else 0.0 } }
        for (sweep in 0 until 100) {
            var off = 0.0
            for (p in 0 until n) for (q in p + 1 until n) off += a[p][q] * a[p][q]
            if (off < 1e-30) break
            for (p in 0 until n) for (q in p + 1 until n) {
                if (abs(a[p][q]) < 1e-300) continue
                val theta = (a[q][q] - a[p][p]) / (2 * a[p][q])
                val t = (if (theta >= 0) 1.0 else -1.0) / (abs(theta) + sqrt(theta * theta + 1))
                val c = 1 / sqrt(t * t + 1); val s = t * c
                for (k in 0 until n) { val akp = a[k][p]; val akq = a[k][q]; a[k][p] = c * akp - s * akq; a[k][q] = s * akp + c * akq }
                for (k in 0 until n) { val apk = a[p][k]; val aqk = a[q][k]; a[p][k] = c * apk - s * aqk; a[q][k] = s * apk + c * aqk }
                for (k in 0 until n) { val vkp = v[k][p]; val vkq = v[k][q]; v[k][p] = c * vkp - s * vkq; v[k][q] = s * vkp + c * vkq }
            }
        }
        return DoubleArray(n) { a[it][it] } to v
    }

    /** Solves the n×n system A x = b by Gaussian elimination with partial pivoting; null if singular. */
    fun solve(a0: Array<DoubleArray>, b0: DoubleArray): DoubleArray? {
        val n = b0.size
        val a = Array(n) { a0[it].copyOf() }; val b = b0.copyOf()
        for (c in 0 until n) {
            val p = (c until n).maxBy { abs(a[it][c]) }
            if (abs(a[p][c]) < 1e-14) return null
            val tr = a[p]; a[p] = a[c]; a[c] = tr; val tb = b[p]; b[p] = b[c]; b[c] = tb
            for (r in c + 1 until n) {
                val f = a[r][c] / a[c][c]
                if (f == 0.0) continue
                for (k in c until n) a[r][k] -= f * a[c][k]
                b[r] -= f * b[c]
            }
        }
        val x = DoubleArray(n)
        for (r in n - 1 downTo 0) {
            var s = b[r]
            for (k in r + 1 until n) s -= a[r][k] * x[k]
            x[r] = s / a[r][r]
        }
        return x
    }
}
