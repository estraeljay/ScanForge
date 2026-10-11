package app.scanforge.markers

import app.scanforge.geometry.Mat3
import app.scanforge.geometry.Pose
import app.scanforge.geometry.Quat
import app.scanforge.geometry.Vec3
import kotlinx.serialization.Serializable
import kotlin.math.abs
import kotlin.math.sqrt

/** Pinhole camera; pixel (0,0) is the first pixel center, v grows down (same as ScanForge CameraIntrinsics). */
data class Pinhole(val fx: Double, val fy: Double, val cx: Double, val cy: Double) {
    /** Projects a point given in the OpenGL camera frame (+Y up, looking −Z). Null if behind the camera. */
    fun project(pCam: Vec3): Pair<Double, Double>? {
        val d = -pCam.z
        if (d <= 1e-9) return null
        return (fx * pCam.x / d + cx) to (cy - fy * pCam.y / d)
    }
}

/** One detected marker: its id and the 4 image corners (u0,v0,…,u3,v3) in OpenCV ArUco order. */
@Serializable
data class MarkerObservation(val id: Int, val corners: List<Double>) {
    init { require(corners.size == 8) { "4 corners expected" } }
}

data class PoseEstimate(
    /** Camera-to-world pose, OpenGL camera convention (as ARCore). */
    val pose: Pose,
    val rmsErrorPx: Double,
    val markersUsed: Int,
    val markersRejected: Int,
)

/**
 * Camera pose from markers on the board plane: normalized DLT homography → closed-form pose → Gauss-Newton refinement
 * of the reprojection error, with per-marker outlier rejection (misdetections, a marker hidden by the object, etc.).
 */
object PlanarPose {
    private val FLIP = Mat3(doubleArrayOf(1.0, 0.0, 0.0, 0.0, -1.0, 0.0, 0.0, 0.0, -1.0))

    /** Homography mapping plane coordinates (X, Z) → pixel (u, v), 3×3 row-major, normalized so h[8] = 1. */
    fun homography(plane: List<Pair<Double, Double>>, pixels: List<Pair<Double, Double>>): DoubleArray? {
        val n = plane.size
        if (n < 4) return null
        // Hartley normalization of both point sets.
        fun norm(pts: List<Pair<Double, Double>>): DoubleArray {
            val mx = pts.sumOf { it.first } / n; val my = pts.sumOf { it.second } / n
            val d = pts.sumOf { sqrt((it.first - mx) * (it.first - mx) + (it.second - my) * (it.second - my)) } / n
            val s = if (d > 1e-12) sqrt(2.0) / d else 1.0
            return doubleArrayOf(s, mx, my)
        }
        val tp = norm(plane); val ti = norm(pixels)
        val ata = Array(9) { DoubleArray(9) }
        for (i in 0 until n) {
            val x = (plane[i].first - tp[1]) * tp[0]; val y = (plane[i].second - tp[2]) * tp[0]
            val u = (pixels[i].first - ti[1]) * ti[0]; val v = (pixels[i].second - ti[2]) * ti[0]
            val r1 = doubleArrayOf(-x, -y, -1.0, 0.0, 0.0, 0.0, u * x, u * y, u)
            val r2 = doubleArrayOf(0.0, 0.0, 0.0, -x, -y, -1.0, v * x, v * y, v)
            for (a in 0 until 9) for (b in 0 until 9) ata[a][b] += r1[a] * r1[b] + r2[a] * r2[b]
        }
        val (vals, vecs) = LinAlg.symmetricEigen(ata)
        val k = vals.indices.minBy { vals[it] }
        val hn = DoubleArray(9) { vecs[it][k] }
        // Denormalize: H = Ti^-1 · Hn · Tp
        val tpM = arrayOf(doubleArrayOf(tp[0], 0.0, -tp[0] * tp[1]), doubleArrayOf(0.0, tp[0], -tp[0] * tp[2]), doubleArrayOf(0.0, 0.0, 1.0))
        val tiInv = arrayOf(doubleArrayOf(1 / ti[0], 0.0, ti[1]), doubleArrayOf(0.0, 1 / ti[0], ti[2]), doubleArrayOf(0.0, 0.0, 1.0))
        val hm = Array(3) { r -> DoubleArray(3) { c -> hn[r * 3 + c] } }
        fun mul(a: Array<DoubleArray>, b: Array<DoubleArray>) = Array(3) { r -> DoubleArray(3) { c -> (0 until 3).sumOf { a[r][it] * b[it][c] } } }
        val h = mul(mul(tiInv, hm), tpM)
        if (abs(h[2][2]) < 1e-15) return null
        return DoubleArray(9) { h[it / 3][it % 3] / h[2][2] }
    }

    private fun rodrigues(w: Vec3): Mat3 {
        val th = w.length()
        if (th < 1e-12) return Mat3.IDENTITY
        return Quat.fromAxisAngle(w / th, th).toMatrix()
    }

    /** Projects world points with OpenCV-convention world→camera (R, t) and returns pixels. */
    private fun projectCv(r: Mat3, t: Vec3, k: Pinhole, p: Vec3): Pair<Double, Double>? {
        val c = r * p + t
        if (c.z <= 1e-9) return null
        return (k.fx * c.x / c.z + k.cx) to (k.fy * c.y / c.z + k.cy)
    }

    /**
     * Estimates the camera pose from 2D–3D correspondences of points on the plane Y = 0.
     * [observations] are grouped per marker so outliers are rejected marker by marker.
     */
    fun estimate(observations: List<MarkerObservation>, board: BoardLayout, k: Pinhole, maxIterations: Int = 30): PoseEstimate? {
        var obs = observations.filter { board.contains(it.id) }
        if (obs.size < 2) return null
        var rejected = observations.size - obs.size
        repeat(3) {
            val est = solve(obs, board, k, maxIterations) ?: return null
            val (r, t) = est
            // Per-marker RMS error; drop clear outliers and re-solve.
            val errs = obs.map { m -> markerError(m, board, k, r, t) }
            val med = errs.sorted()[errs.size / 2]
            val limit = maxOf(2.5, 4 * med)
            val keep = obs.filterIndexed { i, _ -> errs[i] <= limit }
            if (keep.size == obs.size || keep.size < 2) {
                val all = obs.flatMap { m -> cornerResiduals(m, board, k, r, t) }
                val rms = sqrt(all.sumOf { it * it } / all.size)
                // OpenCV camera (y down, z forward) → OpenGL camera (y up, looks −z); camera-to-world.
                val rT = r.transpose()
                val pose = Pose(-(rT * t), Quat.fromMatrix(rT * FLIP))
                return PoseEstimate(pose, rms, obs.size, rejected)
            }
            rejected += obs.size - keep.size
            obs = keep
        }
        return null
    }

    private fun cornerResiduals(m: MarkerObservation, board: BoardLayout, k: Pinhole, r: Mat3, t: Vec3): List<Double> {
        val w = board.cornersWorld(m.id)
        return (0 until 4).flatMap { i ->
            val p = projectCv(r, t, k, w[i]) ?: return@flatMap listOf(1e3, 1e3)
            listOf(p.first - m.corners[2 * i], p.second - m.corners[2 * i + 1])
        }
    }

    private fun markerError(m: MarkerObservation, board: BoardLayout, k: Pinhole, r: Mat3, t: Vec3): Double {
        val res = cornerResiduals(m, board, k, r, t)
        return sqrt(res.sumOf { it * it } / 4)
    }

    /** Returns OpenCV-convention world→camera rotation and translation. */
    private fun solve(obs: List<MarkerObservation>, board: BoardLayout, k: Pinhole, maxIterations: Int): Pair<Mat3, Vec3>? {
        val plane = ArrayList<Pair<Double, Double>>(); val pix = ArrayList<Pair<Double, Double>>(); val world = ArrayList<Vec3>()
        for (m in obs) {
            val w = board.cornersWorld(m.id)
            for (i in 0 until 4) { plane += w[i].x to w[i].z; pix += m.corners[2 * i] to m.corners[2 * i + 1]; world += w[i] }
        }
        val h = homography(plane, pix) ?: return null
        // Remove intrinsics: Hn = K^-1 H (in the OpenCV convention, v down).
        fun col(c: Int) = Vec3((h[c] - k.cx * h[6 + c]) / k.fx, (h[3 + c] - k.cy * h[6 + c]) / k.fy, h[6 + c])
        var c0 = col(0); var c2 = col(1); var tt = col(2)
        val lambda = 2.0 / (c0.length() + c2.length())
        c0 *= lambda; c2 *= lambda; tt *= lambda
        if (tt.z < 0) { c0 = -c0; c2 = -c2; tt = -tt }
        // Board axes in camera frame: world X → c0, world Z → c2, world Y = Z × X.
        val x = c0.normalized()
        val z = (c2 - x * (c2 dot x)).normalized()
        val y = z cross x
        var r = Mat3.fromColumns(x, y, z)
        var t = tt
        // Gauss-Newton on (rotation increment ω, translation) with numeric Jacobian.
        var cost = residuals(r, t, k, world, pix).sumOf { it * it }
        for (iter in 0 until maxIterations) {
            val base = residuals(r, t, k, world, pix)
            val jac = Array(base.size) { DoubleArray(6) }
            val eps = 1e-6
            for (p in 0 until 6) {
                val (r2, t2) = perturb(r, t, p, eps)
                val res = residuals(r2, t2, k, world, pix)
                for (i in base.indices) jac[i][p] = (res[i] - base[i]) / eps
            }
            val jtj = Array(6) { a -> DoubleArray(6) { b -> base.indices.sumOf { jac[it][a] * jac[it][b] } } }
            for (d in 0 until 6) jtj[d][d] *= 1.0 + 1e-6
            val jtr = DoubleArray(6) { a -> -base.indices.sumOf { jac[it][a] * base[it] } }
            val delta = LinAlg.solve(jtj, jtr) ?: break
            val rNew = rodrigues(Vec3(delta[0], delta[1], delta[2])) * r
            val tNew = t + Vec3(delta[3], delta[4], delta[5])
            val newCost = residuals(rNew, tNew, k, world, pix).sumOf { it * it }
            if (newCost > cost) break
            val improvement = cost - newCost
            r = rNew; t = tNew; cost = newCost
            if (improvement < 1e-10 * (1 + cost)) break
        }
        return r to t
    }

    private fun perturb(r: Mat3, t: Vec3, p: Int, eps: Double): Pair<Mat3, Vec3> = when (p) {
        0 -> rodrigues(Vec3(eps, 0.0, 0.0)) * r to t
        1 -> rodrigues(Vec3(0.0, eps, 0.0)) * r to t
        2 -> rodrigues(Vec3(0.0, 0.0, eps)) * r to t
        3 -> r to t + Vec3(eps, 0.0, 0.0)
        4 -> r to t + Vec3(0.0, eps, 0.0)
        else -> r to t + Vec3(0.0, 0.0, eps)
    }

    private fun residuals(r: Mat3, t: Vec3, k: Pinhole, world: List<Vec3>, pix: List<Pair<Double, Double>>): DoubleArray {
        val out = DoubleArray(world.size * 2)
        for (i in world.indices) {
            val p = projectCv(r, t, k, world[i])
            if (p == null) { out[2 * i] = 1e3; out[2 * i + 1] = 1e3 } else { out[2 * i] = p.first - pix[i].first; out[2 * i + 1] = p.second - pix[i].second }
        }
        return out
    }

    /** Reprojects a world point with a camera-to-world pose (GL convention), for diagnostics and tests. */
    fun reproject(pose: Pose, k: Pinhole, world: Vec3) = k.project(pose.inverse().transformPoint(world))

    // Used by FocalEstimation.
    internal fun homographyFor(obs: List<MarkerObservation>, board: BoardLayout): DoubleArray? {
        val plane = ArrayList<Pair<Double, Double>>(); val pix = ArrayList<Pair<Double, Double>>()
        for (m in obs.filter { board.contains(it.id) }) {
            val w = board.cornersWorld(m.id)
            for (i in 0 until 4) { plane += w[i].x to w[i].z; pix += m.corners[2 * i] to m.corners[2 * i + 1] }
        }
        return homography(plane, pix)
    }

}

/**
 * Focal length (square pixels, principal point at the image center, no distortion) from several views of the board:
 * closed-form estimate from Zhang's homography constraints, then a 1-D refinement of the total reprojection error.
 */
object FocalEstimation {
    data class Result(val focalPx: Double, val rmsErrorPx: Double, val viewsUsed: Int)

    fun estimate(views: List<List<MarkerObservation>>, board: BoardLayout, width: Int, height: Int, initialGuess: Double? = null): Result? {
        val cx = (width - 1) / 2.0; val cy = (height - 1) / 2.0
        var num = 0.0; var den = 0.0; var used = 0
        for (v in views) {
            val h = PlanarPose.homographyFor(v, board) ?: continue
            // Shift to principal point: H' = T·H with T = [[1,0,-cx],[0,1,-cy],[0,0,1]].
            val g = DoubleArray(9) { i ->
                val r = i / 3; val c = i % 3
                when (r) { 0 -> h[c] - cx * h[6 + c]; 1 -> h[3 + c] - cy * h[6 + c]; else -> h[6 + c] }
            }
            // ω = diag(x, x, 1), x = 1/f². Constraints: a·x + b = 0.
            val a1 = g[0] * g[1] + g[3] * g[4]; val b1 = g[6] * g[7]
            val a2 = g[0] * g[0] + g[3] * g[3] - g[1] * g[1] - g[4] * g[4]; val b2 = g[6] * g[6] - g[7] * g[7]
            num += a1 * b1 + a2 * b2; den += a1 * a1 + a2 * a2; used++
        }
        val closed = if (den > 0 && -num / den > 0) 1.0 / sqrt(-num / den) else null
        val f0 = closed ?: initialGuess ?: return null
        fun cost(f: Double): Double {
            val k = Pinhole(f, f, cx, cy)
            var s = 0.0; var n = 0
            for (v in views) { val e = PlanarPose.estimate(v, board, k, 15) ?: continue; s += e.rmsErrorPx * e.rmsErrorPx * e.markersUsed; n += e.markersUsed }
            return if (n == 0) Double.MAX_VALUE else s / n
        }
        // Golden-section search on ±30 % around the initial estimate.
        var lo = f0 * 0.7; var hi = f0 * 1.3
        val gr = (sqrt(5.0) - 1) / 2
        var x1 = hi - gr * (hi - lo); var x2 = lo + gr * (hi - lo)
        var f1 = cost(x1); var f2 = cost(x2)
        repeat(30) {
            if (f1 < f2) { hi = x2; x2 = x1; f2 = f1; x1 = hi - gr * (hi - lo); f1 = cost(x1) }
            else { lo = x1; x1 = x2; f1 = f2; x2 = lo + gr * (hi - lo); f2 = cost(x2) }
        }
        val f = (lo + hi) / 2
        return Result(f, sqrt(cost(f)), used)
    }
}
