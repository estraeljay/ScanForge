package app.scanforge.geometry

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.sqrt

/** Oriented bounding box: [axes] are orthonormal columns, [halfExtents] along each axis. */
data class Obb(val center: Vec3, val axes: Mat3, val halfExtents: Vec3) {
    /** Full edge lengths sorted largest-first. */
    val sortedDimensions: List<Double> get() = listOf(halfExtents.x, halfExtents.y, halfExtents.z).map { it * 2 }.sortedDescending()
}

data class CircleFit(
    val center: Vec3,
    val normal: Vec3,
    val radius: Double,
    /** RMS distance of the input points from the fitted circle, meters. */
    val rmsResidual: Double,
    /** Largest angular gap (radians) between consecutive points around the circle. */
    val largestAngularGap: Double,
    val pointCount: Int,
) {
    val diameter get() = radius * 2
    val circumference get() = 2 * PI * radius
}

/** A closed or open polyline produced by slicing a mesh with a plane. */
data class SectionLoop(val points: List<Vec3>, val closed: Boolean) {
    val length: Double
        get() {
            var s = 0.0
            for (i in 1 until points.size) s += points[i].distanceTo(points[i - 1])
            if (closed && points.size > 2) s += points.last().distanceTo(points.first())
            return s
        }
}

object Measurements {

    fun distance(a: Vec3, b: Vec3) = a.distanceTo(b)

    /** Symmetric 3x3 eigen-decomposition by cyclic Jacobi rotations. Returns (eigenvalues, eigenvector columns). */
    fun symmetricEigen(a: Array<DoubleArray>): Pair<DoubleArray, Mat3> {
        val m = Array(3) { a[it].copyOf() }
        val v = Array(3) { i -> DoubleArray(3) { j -> if (i == j) 1.0 else 0.0 } }
        repeat(50) {
            var off = 0.0
            for (p in 0 until 3) for (q in p + 1 until 3) off += m[p][q] * m[p][q]
            if (off < 1e-30) return@repeat
            for (p in 0 until 3) for (q in p + 1 until 3) {
                if (abs(m[p][q]) < 1e-300) continue
                val theta = (m[q][q] - m[p][p]) / (2 * m[p][q])
                val t = (if (theta >= 0) 1.0 else -1.0) / (abs(theta) + sqrt(theta * theta + 1))
                val c = 1 / sqrt(t * t + 1); val s = t * c
                for (k in 0 until 3) {
                    val mkp = m[k][p]; val mkq = m[k][q]
                    m[k][p] = c * mkp - s * mkq; m[k][q] = s * mkp + c * mkq
                }
                for (k in 0 until 3) {
                    val mpk = m[p][k]; val mqk = m[q][k]
                    m[p][k] = c * mpk - s * mqk; m[q][k] = s * mpk + c * mqk
                }
                for (k in 0 until 3) {
                    val vkp = v[k][p]; val vkq = v[k][q]
                    v[k][p] = c * vkp - s * vkq; v[k][q] = s * vkp + c * vkq
                }
            }
        }
        return doubleArrayOf(m[0][0], m[1][1], m[2][2]) to Mat3(doubleArrayOf(v[0][0], v[0][1], v[0][2], v[1][0], v[1][1], v[1][2], v[2][0], v[2][1], v[2][2]))
    }

    fun covariance(points: List<Vec3>): Pair<Vec3, Array<DoubleArray>> {
        require(points.isNotEmpty())
        val mean = points.fold(Vec3.ZERO) { acc, p -> acc + p } / points.size.toDouble()
        val c = Array(3) { DoubleArray(3) }
        for (p in points) {
            val d = p - mean
            for (i in 0 until 3) for (j in 0 until 3) c[i][j] += d[i] * d[j]
        }
        for (i in 0 until 3) for (j in 0 until 3) c[i][j] /= points.size.toDouble()
        return mean to c
    }

    /**
     * Smallest box with one axis fixed to [up]: the footprint rectangle is found by sweeping the rotation about
     * [up] (1° steps, then 0.05° refinement), which is exact to within the refinement step for the 2D minimum-area
     * rectangle. Use with the support-plane normal for objects resting on a surface.
     */
    fun uprightBoundingBox(points: List<Vec3>, up: Vec3): Obb? {
        if (points.isEmpty()) return null
        val n = up.normalized()
        val seed = if (kotlin.math.abs(n.x) < 0.9) Vec3.X else Vec3.Y
        val e1 = (seed - n * (seed dot n)).normalized()
        val e2 = n cross e1
        fun extents(angle: Double): DoubleArray {
            val u = e1 * kotlin.math.cos(angle) + e2 * kotlin.math.sin(angle)
            val w = n cross u
            var minU = Double.MAX_VALUE; var maxU = -Double.MAX_VALUE; var minW = Double.MAX_VALUE; var maxW = -Double.MAX_VALUE
            for (p in points) {
                val a = p dot u; val b = p dot w
                if (a < minU) minU = a; if (a > maxU) maxU = a; if (b < minW) minW = b; if (b > maxW) maxW = b
            }
            return doubleArrayOf(minU, maxU, minW, maxW, (maxU - minU) * (maxW - minW))
        }
        var best = 0.0; var bestArea = Double.MAX_VALUE
        for (deg in 0 until 90) { val a = Math.toRadians(deg.toDouble()); val e = extents(a)[4]; if (e < bestArea) { bestArea = e; best = a } }
        var step = Math.toRadians(0.05); var a = best - Math.toRadians(1.0)
        while (a <= best + Math.toRadians(1.0)) { val e = extents(a)[4]; if (e < bestArea) { bestArea = e; best = a }; a += step }
        val u = e1 * kotlin.math.cos(best) + e2 * kotlin.math.sin(best)
        val w = n cross u
        val ex = extents(best)
        var minN = Double.MAX_VALUE; var maxN = -Double.MAX_VALUE
        for (p in points) { val h = p dot n; if (h < minN) minN = h; if (h > maxN) maxN = h }
        val center = u * ((ex[0] + ex[1]) / 2) + w * ((ex[2] + ex[3]) / 2) + n * ((minN + maxN) / 2)
        return Obb(center, Mat3.fromColumns(u, w, n), Vec3((ex[1] - ex[0]) / 2, (ex[3] - ex[2]) / 2, (maxN - minN) / 2))
    }

    /**
     * Approximate minimum-volume oriented bounding box: tries each principal axis and each world axis as the
     * fixed axis of [uprightBoundingBox] and keeps the smallest. Plain PCA boxes are often far from minimal.
     */
    fun orientedBoundingBox(mesh: Mesh): Obb? {
        if (mesh.vertexCount == 0) return null
        val pts = (0 until mesh.vertexCount).map { mesh.vertex(it) }
        val (_, cov) = covariance(pts)
        val (_, vecs) = symmetricEigen(cov)
        val candidates = listOf(vecs.column(0), vecs.column(1), vecs.column(2), Vec3.X, Vec3.Y, Vec3.Z)
        return candidates.mapNotNull { uprightBoundingBox(pts, it) }
            .minBy { it.halfExtents.x * it.halfExtents.y * it.halfExtents.z }
    }

    /**
     * Fits a circle to points that approximately lie on a planar circle (e.g. points picked on a rim).
     * Plane by PCA, then the algebraic (Kåsa) least-squares circle fit in that plane.
     * Returns null when fewer than 3 points or the points are collinear.
     */
    fun fitCircle(points: List<Vec3>): CircleFit? {
        if (points.size < 3) return null
        val (mean, cov) = covariance(points)
        val (vals, vecs) = symmetricEigen(cov)
        val order = vals.indices.sortedBy { vals[it] }
        val normal = vecs.column(order[0]).normalized()
        val u = vecs.column(order[2]).normalized()
        val w = (normal cross u).normalized()
        if (vals[order[1]] < 1e-14) return null // collinear
        // Solve [x y 1][a b c]^T = -(x²+y²) in least squares via normal equations.
        val ata = Array(3) { DoubleArray(3) }
        val atb = DoubleArray(3)
        val pts2 = points.map { val d = it - mean; doubleArrayOf(d dot u, d dot w) }
        for (p in pts2) {
            val row = doubleArrayOf(p[0], p[1], 1.0)
            val rhs = -(p[0] * p[0] + p[1] * p[1])
            for (i in 0 until 3) { for (j in 0 until 3) ata[i][j] += row[i] * row[j]; atb[i] += row[i] * rhs }
        }
        val sol = solve3(ata, atb) ?: return null
        val cx = -sol[0] / 2; val cy = -sol[1] / 2
        val r2 = cx * cx + cy * cy - sol[2]
        if (r2 <= 0) return null
        val r = sqrt(r2)
        var ss = 0.0
        val angles = pts2.map { p ->
            val dr = sqrt((p[0] - cx) * (p[0] - cx) + (p[1] - cy) * (p[1] - cy)) - r
            ss += dr * dr
            atan2(p[1] - cy, p[0] - cx)
        }.sorted()
        var gap = 2 * PI - (angles.last() - angles.first())
        for (i in 1 until angles.size) gap = maxOf(gap, angles[i] - angles[i - 1])
        return CircleFit(mean + u * cx + w * cy, normal, r, sqrt(ss / points.size), gap, points.size)
    }

    private fun solve3(a: Array<DoubleArray>, b: DoubleArray): DoubleArray? {
        val m = Mat3(doubleArrayOf(a[0][0], a[0][1], a[0][2], a[1][0], a[1][1], a[1][2], a[2][0], a[2][1], a[2][2]))
        val det = m.determinant()
        if (abs(det) < 1e-24) return null
        fun replaced(col: Int) = Mat3(DoubleArray(9) { i -> if (i % 3 == col) b[i / 3] else m.m[i] }).determinant()
        return doubleArrayOf(replaced(0) / det, replaced(1) / det, replaced(2) / det)
    }

    /**
     * Intersects the mesh with the plane n·p = d and chains the segments into polylines.
     * Closed loops give cross-section perimeters (circumference estimates).
     */
    fun crossSection(mesh: Mesh, planeNormal: Vec3, planeOffset: Double, weldTolerance: Double = 1e-6): List<SectionLoop> {
        val n = planeNormal.normalized()
        val segments = ArrayList<Pair<Vec3, Vec3>>()
        for (t in 0 until mesh.triangleCount) {
            val (a, b, c) = mesh.triangle(t)
            val vs = arrayOf(mesh.vertex(a), mesh.vertex(b), mesh.vertex(c))
            val ds = DoubleArray(3) { (n dot vs[it]) - planeOffset }
            val crossings = ArrayList<Vec3>(2)
            for (i in 0 until 3) {
                val j = (i + 1) % 3
                if ((ds[i] < 0) != (ds[j] < 0)) {
                    val s = ds[i] / (ds[i] - ds[j])
                    crossings.add(vs[i] + (vs[j] - vs[i]) * s)
                }
            }
            // Vertices exactly on the plane count as positive; this can yield zero-length segments,
            // which carry no perimeter and would break chaining, so they are dropped.
            if (crossings.size == 2 && crossings[0].distanceTo(crossings[1]) > weldTolerance) {
                segments.add(crossings[0] to crossings[1])
            }
        }
        // Chain segments by matching endpoints on a quantized grid.
        fun key(p: Vec3) = Triple(Math.round(p.x / weldTolerance), Math.round(p.y / weldTolerance), Math.round(p.z / weldTolerance))
        val adj = HashMap<Triple<Long, Long, Long>, MutableList<Int>>()
        segments.forEachIndexed { i, (p, q) -> adj.getOrPut(key(p)) { mutableListOf() }.add(i); adj.getOrPut(key(q)) { mutableListOf() }.add(i) }
        val used = BooleanArray(segments.size)
        val loops = ArrayList<SectionLoop>()
        for (start in segments.indices) {
            if (used[start]) continue
            used[start] = true
            val chain = ArrayDeque<Vec3>().apply { add(segments[start].first); add(segments[start].second) }
            // Extend forward, then backward.
            for (forward in listOf(true, false)) {
                while (true) {
                    val end = if (forward) chain.last() else chain.first()
                    val next = adj[key(end)]?.firstOrNull { !used[it] } ?: break
                    used[next] = true
                    val (p, q) = segments[next]
                    val other = if (key(p) == key(end)) q else p
                    if (forward) chain.addLast(other) else chain.addFirst(other)
                }
            }
            val closed = chain.size > 3 && key(chain.first()) == key(chain.last())
            if (closed) chain.removeLast()
            loops.add(SectionLoop(chain.toList(), closed))
        }
        return loops
    }
}
