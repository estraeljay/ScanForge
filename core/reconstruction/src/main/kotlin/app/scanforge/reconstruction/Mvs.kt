package app.scanforge.reconstruction

import app.scanforge.geometry.Aabb
import app.scanforge.geometry.Pose
import app.scanforge.geometry.Vec3
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** Single-channel float image (0..255), row-major. */
class GrayImage(val width: Int, val height: Int, val data: FloatArray) {
    init { require(data.size == width * height) }

    fun bilinear(x: Double, y: Double): Float {
        if (x < 0 || y < 0 || x > width - 1 || y > height - 1) return Float.NaN
        val x0 = floor(x).toInt().coerceAtMost(width - 2).coerceAtLeast(0); val y0 = floor(y).toInt().coerceAtMost(height - 2).coerceAtLeast(0)
        val fx = (x - x0).toFloat(); val fy = (y - y0).toFloat()
        val i = y0 * width + x0
        val a = data[i] + (data[i + 1] - data[i]) * fx
        val b = data[i + width] + (data[i + width + 1] - data[i + width]) * fx
        return a + (b - a) * fy
    }

    /** Box-filter downsample by an integer factor. */
    fun downsample(f: Int): GrayImage {
        if (f <= 1) return this
        val w = width / f; val h = height / f
        val out = FloatArray(w * h)
        for (y in 0 until h) for (x in 0 until w) {
            var s = 0f
            for (dy in 0 until f) for (dx in 0 until f) s += data[(y * f + dy) * width + x * f + dx]
            out[y * w + x] = s / (f * f)
        }
        return GrayImage(w, h, out)
    }

    companion object {
        fun fromRgb(img: RgbImage) = GrayImage(img.width, img.height, FloatArray(img.width * img.height) { i ->
            0.299f * (img.rgb[i * 3].toInt() and 0xff) + 0.587f * (img.rgb[i * 3 + 1].toInt() and 0xff) + 0.114f * (img.rgb[i * 3 + 2].toInt() and 0xff)
        })
    }
}

class PosedView(val image: GrayImage, val k: CameraIntrinsics, val pose: Pose) {
    val forward: Vec3 get() = pose.rotateVector(Vec3(0.0, 0.0, -1.0))
}

data class MvsParams(
    /** Planes swept between the region's near and far depth (uniform in inverse depth); 160 measured best on synthetic tests. */
    val depthSamples: Int = 160,
    val neighbors: Int = 4,
    /** Half-size of the NCC window in pixels (window = (2r+1)²). */
    val windowRadius: Int = 3,
    val minNcc: Double = 0.55,
    /** Reject windows with almost no texture (std of gray values). */
    val minTextureStd: Double = 3.0,
    val minBaselineDeg: Double = 5.0,
    val maxBaselineDeg: Double = 40.0,
    /** Views that must agree (relative depth difference ≤ consistencyTolerance) for a depth to be kept. */
    val minConsistentViews: Int = 2,
    val consistencyTolerance: Double = 0.01,
)

/**
 * Plane-sweep multi-view stereo with known camera poses (from the marker board). For each reference view, neighbor
 * images are warped onto fronto-parallel depth planes; matching cost is zero-mean NCC computed with summed-area tables.
 * Depths are kept only if they are well textured, unambiguous, inside the region of interest and geometrically
 * consistent with other views. The output is ordinary depth + confidence frames, fused by the existing TSDF pipeline.
 */
class PlaneSweepStereo(private val p: MvsParams = MvsParams()) {

    private fun angleDeg(a: Vec3, b: Vec3) = Math.toDegrees(acos((a.normalized() dot b.normalized()).coerceIn(-1.0, 1.0)))

    fun neighborsOf(ref: Int, views: List<PosedView>, center: Vec3): List<Int> {
        val r = views[ref]
        val toR = r.pose.translation - center
        return views.indices.filter { it != ref }
            .map { it to angleDeg(toR, views[it].pose.translation - center) }
            .filter { it.second in p.minBaselineDeg..p.maxBaselineDeg }
            .sortedBy { abs(it.second - 15.0) }
            .take(p.neighbors).map { it.first }
    }

    private fun depthRange(v: PosedView, roi: Aabb): Pair<Double, Double>? {
        val inv = v.pose.inverse()
        var lo = Double.MAX_VALUE; var hi = 0.0
        for (i in 0 until 8) {
            val c = Vec3(if (i and 1 == 0) roi.min.x else roi.max.x, if (i and 2 == 0) roi.min.y else roi.max.y, if (i and 4 == 0) roi.min.z else roi.max.z)
            val d = -inv.transformPoint(c).z
            lo = min(lo, d); hi = max(hi, d)
        }
        lo = max(lo, 0.05)
        return if (hi > lo) lo to hi else null
    }

    /** Summed-area table over a w×h float array (NaN treated as 0, with a separate validity count). */
    private class Sat(w: Int, h: Int, f: (Int) -> Float) {
        val w1 = w + 1
        val s = DoubleArray((w + 1) * (h + 1))
        init {
            for (y in 0 until h) { var row = 0.0; for (x in 0 until w) { val v = f(y * w + x); row += if (v.isNaN()) 0.0 else v.toDouble(); s[(y + 1) * w1 + x + 1] = s[y * w1 + x + 1] + row } }
        }
        fun box(x0: Int, y0: Int, x1: Int, y1: Int) = s[(y1 + 1) * w1 + x1 + 1] - s[y0 * w1 + x1 + 1] - s[(y1 + 1) * w1 + x0] + s[y0 * w1 + x0]
    }

    class Result(val depth: FloatArray, val score: FloatArray, val k: CameraIntrinsics)

    /** Row-major 3×3 matrix M with X_neighbor = M·(x, y, 1) for ref pixels on the plane at ref depth [d]. */
    private fun planeMatrix(r: PosedView, n: PosedView, d: Double): DoubleArray {
        val rr = r.pose.rotation.toMatrix(); val rn = n.pose.rotation.toMatrix().transpose()
        val rnr = rn * rr
        val tnr = rn * (r.pose.translation - n.pose.translation)
        // A = [[1/fx, 0, −cx/fx], [0, −1/fy, cy/fy], [0, 0, −1]]
        val a = doubleArrayOf(1 / r.k.fx, 0.0, -r.k.cx / r.k.fx, 0.0, -1 / r.k.fy, r.k.cy / r.k.fy, 0.0, 0.0, -1.0)
        val out = DoubleArray(9)
        for (i in 0 until 3) for (j in 0 until 3) {
            var s = 0.0
            for (q in 0 until 3) s += rnr[i, q] * a[q * 3 + j]
            out[i * 3 + j] = d * s + if (j == 2) tnr[i] else 0.0
        }
        return out
    }

    /** Raw (unfiltered by consistency) depth map of view [ref] at its image resolution. */
    fun sweep(ref: Int, views: List<PosedView>, roi: Aabb): Result? {
        val rv = views[ref]
        val w = rv.image.width; val h = rv.image.height
        val (dMin, dMax) = depthRange(rv, roi) ?: return null
        val nbs = neighborsOf(ref, views, roi.center)
        if (nbs.size < 2) return null
        val r = p.windowRadius
        val n = ((2 * r + 1) * (2 * r + 1)).toDouble()
        val ref0 = rv.image.data
        val satR = Sat(w, h) { ref0[it] }; val satRR = Sat(w, h) { ref0[it] * ref0[it] }
        // Peaks closer than this (in samples) belong to the same match; farther ones count as competing matches.
        val sep = maxOf(2, p.depthSamples / 40)
        val best = FloatArray(w * h) { -1f }; val bestIdx = IntArray(w * h) { -1 }
        val second = FloatArray(w * h) { -1f }
        val scores = Array(p.depthSamples) { FloatArray(0) }
        val perNeighbor = Array(nbs.size) { FloatArray(w * h) }
        val warped = FloatArray(w * h)
        for (di in 0 until p.depthSamples) {
            // Uniform in inverse depth.
            val inv = 1 / dMax + (1 / dMin - 1 / dMax) * di / (p.depthSamples - 1)
            val d = 1 / inv
            for ((ni, nb) in nbs.withIndex()) {
                val nv = views[nb]
                // Plane-induced mapping: X_n = (d·R_nr·A + t_nr·e3ᵀ)·(x, y, 1), with A mapping pixels to ref-camera rays
                // at unit depth (OpenGL camera: looks −Z, +Y up) and (R_nr, t_nr) the ref→neighbor camera transform.
                val m = planeMatrix(rv, nv, d)
                for (y in 0 until h) {
                    var X = m[1] * y + m[2]; var Y = m[4] * y + m[5]; var Z = m[7] * y + m[8]
                    val row = y * w
                    for (x in 0 until w) {
                        warped[row + x] = if (Z >= -1e-9) Float.NaN else nv.image.bilinear(nv.k.fx * X / -Z + nv.k.cx, nv.k.cy - nv.k.fy * Y / -Z)
                        X += m[0]; Y += m[3]; Z += m[6]
                    }
                }
                val satN = Sat(w, h) { warped[it] }; val satNN = Sat(w, h) { warped[it] * warped[it] }
                val satRN = Sat(w, h) { warped[it] * ref0[it] }
                val valid = Sat(w, h) { if (warped[it].isNaN()) 0f else 1f }
                val out = perNeighbor[ni]
                for (y in r until h - r) for (x in r until w - r) {
                    val x0 = x - r; val y0 = y - r; val x1 = x + r; val y1 = y + r
                    if (valid.box(x0, y0, x1, y1) < n - 0.5) { out[y * w + x] = -1f; continue }
                    val sr = satR.box(x0, y0, x1, y1); val sn = satN.box(x0, y0, x1, y1)
                    val varR = satRR.box(x0, y0, x1, y1) - sr * sr / n
                    val varN = satNN.box(x0, y0, x1, y1) - sn * sn / n
                    val cov = satRN.box(x0, y0, x1, y1) - sr * sn / n
                    out[y * w + x] = if (varR <= 1e-6 || varN <= 1e-6) -1f else (cov / sqrt(varR * varN)).toFloat()
                }
            }
            // Robust aggregation: mean of the best half of neighbors (tolerates occlusion in some views).
            val agg = FloatArray(w * h)
            val keep = max(2, (nbs.size + 1) / 2)
            val tmp = FloatArray(nbs.size)
            for (i in 0 until w * h) {
                for (ni in nbs.indices) tmp[ni] = perNeighbor[ni][i]
                tmp.sortDescending()
                var s = 0f; for (q in 0 until keep) s += tmp[q]
                agg[i] = s / keep
            }
            scores[di] = agg
            for (i in 0 until w * h) {
                val v = agg[i]
                if (v > best[i]) {
                    if (bestIdx[i] < 0 || abs(bestIdx[i] - di) > sep) second[i] = max(second[i], best[i])
                    best[i] = v; bestIdx[i] = di
                } else if (abs(bestIdx[i] - di) > sep && v > second[i]) second[i] = v
            }
        }
        val depth = FloatArray(w * h); val score = FloatArray(w * h)
        val minVar = p.minTextureStd * p.minTextureStd * n
        for (y in r until h - r) for (x in r until w - r) {
            val i = y * w + x
            val di = bestIdx[i]
            if (di < 0 || best[i] < p.minNcc) continue
            if (best[i] - second[i] < 0.05f) continue // ambiguous (repetitive texture)
            val sr = satR.box(x - r, y - r, x + r, y + r)
            if (satRR.box(x - r, y - r, x + r, y + r) - sr * sr / n < minVar) continue
            // Parabolic sub-sample refinement in inverse depth.
            var off = 0.0
            if (di in 1 until p.depthSamples - 1) {
                val a = scores[di - 1][i]; val b = scores[di][i]; val c = scores[di + 1][i]
                val den = a - 2 * b + c
                if (den < 0) off = (0.5 * (a - c) / den).coerceIn(-0.5, 0.5)
            }
            val inv = 1 / dMax + (1 / dMin - 1 / dMax) * (di + off) / (p.depthSamples - 1)
            val d = 1 / inv
            val world = rv.pose.transformPoint(rv.k.unproject(x.toDouble(), y.toDouble(), d))
            if (!roi.contains(world)) continue
            depth[i] = d.toFloat(); score[i] = best[i]
        }
        return Result(depth, score, rv.k)
    }

    /** Depth frames for all views after multi-view geometric consistency filtering. */
    fun run(views: List<PosedView>, roi: Aabb, onProgress: (Int, Int) -> Unit = { _, _ -> }, cancelled: () -> Boolean = { false }): List<DepthFrame?> {
        // Reference views are independent: process them in parallel on all cores.
        val done = java.util.concurrent.atomic.AtomicInteger()
        val pool = java.util.concurrent.Executors.newFixedThreadPool(maxOf(1, Runtime.getRuntime().availableProcessors()))
        val raw = try {
            views.indices.map { i -> pool.submit<Result?> {
                if (cancelled()) null else sweep(i, views, roi).also { onProgress(done.incrementAndGet(), views.size) }
            } }.map { it.get() }
        } finally { pool.shutdown() }
        if (cancelled()) throw ReconstructionException(ReconstructionFailure.CANCELLED)
        return views.indices.map { i ->
            val res = raw[i] ?: return@map null
            val v = views[i]; val w = v.image.width; val h = v.image.height
            val mm = ShortArray(w * h); val conf = ByteArray(w * h)
            val nbs = neighborsOf(i, views, roi.center).filter { raw[it] != null }
            for (y in 0 until h) for (x in 0 until w) {
                val idx = y * w + x
                val d = res.depth[idx].toDouble()
                if (d <= 0) continue
                val world = v.pose.transformPoint(v.k.unproject(x.toDouble(), y.toDouble(), d))
                var agree = 0
                for (nb in nbs) {
                    val o = views[nb]; val od = raw[nb]!!
                    val pc = o.pose.inverse().transformPoint(world)
                    val pr = o.k.project(pc) ?: continue
                    val ux = pr.x.roundToInt(); val uy = pr.y.roundToInt()
                    if (ux < 0 || uy < 0 || ux >= o.image.width || uy >= o.image.height) continue
                    val dd = od.depth[uy * o.image.width + ux]
                    if (dd > 0 && abs(dd - pr.z) / pr.z <= p.consistencyTolerance) agree++
                }
                if (agree < p.minConsistentViews) continue
                mm[idx] = (d * 1000).roundToInt().coerceIn(1, 65535).toShort()
                conf[idx] = (((res.score[idx] - p.minNcc) / (1 - p.minNcc)).coerceIn(0.0, 1.0) * 155 + 100).toInt().toByte()
            }
            DepthFrame(w, h, mm, conf)
        }
    }
}
