package app.scanforge.reconstruction

import app.scanforge.geometry.Aabb
import app.scanforge.geometry.Pose
import app.scanforge.geometry.Vec3
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/** Signed-distance primitive used to synthesize ground-truth captures. */
sealed interface SdfShape {
    fun distance(p: Vec3): Double

    data class Box(val center: Vec3, val halfExtents: Vec3) : SdfShape {
        override fun distance(p: Vec3): Double {
            val q = Vec3(abs(p.x - center.x) - halfExtents.x, abs(p.y - center.y) - halfExtents.y, abs(p.z - center.z) - halfExtents.z)
            val outside = Vec3(max(q.x, 0.0), max(q.y, 0.0), max(q.z, 0.0)).length()
            return outside + min(max(q.x, max(q.y, q.z)), 0.0)
        }
    }

    data class Sphere(val center: Vec3, val radius: Double) : SdfShape {
        override fun distance(p: Vec3) = p.distanceTo(center) - radius
    }

    /** Upright cylinder resting with its base at [baseCenter]. */
    data class Cylinder(val baseCenter: Vec3, val radius: Double, val height: Double) : SdfShape {
        override fun distance(p: Vec3): Double {
            val dx = p.x - baseCenter.x; val dz = p.z - baseCenter.z
            val dr = sqrt(dx * dx + dz * dz) - radius
            val dy = abs(p.y - (baseCenter.y + height / 2)) - height / 2
            return min(max(dr, dy), 0.0) + Vec3(max(dr, 0.0), max(dy, 0.0), 0.0).length()
        }
    }

    /** Horizontal ground plane at height [y] (solid below). */
    data class Ground(val y: Double) : SdfShape {
        override fun distance(p: Vec3) = p.y - y
    }
}

/**
 * Generates synthetic captures by sphere-tracing an analytic scene. Used for automated tests and as the
 * documented sample project; every dataset produced here is labelled `source = "synthetic"`.
 */
class SyntheticScene(val objects: List<SdfShape>, val ground: SdfShape.Ground? = SdfShape.Ground(0.0)) {
    fun distance(p: Vec3): Double {
        var d = ground?.distance(p) ?: Double.MAX_VALUE
        for (o in objects) d = min(d, o.distance(p))
        return d
    }

    private fun objectDistance(p: Vec3): Double = objects.minOfOrNull { it.distance(p) } ?: Double.MAX_VALUE

    /** Procedural texture: checker on objects (helps photogrammetry tests), gray ground. */
    fun colorAt(p: Vec3, out: IntArray) {
        if (objectDistance(p) < 1e-3) {
            val c = (floor(p.x / 0.02) + floor(p.y / 0.02) + floor(p.z / 0.02)).toInt() and 1
            if (c == 0) { out[0] = 220; out[1] = 90; out[2] = 40 } else { out[0] = 240; out[1] = 220; out[2] = 200 }
        } else {
            out[0] = 110; out[1] = 110; out[2] = 115
        }
    }

    /** Returns the ray parameter of the first hit, or null. */
    fun trace(origin: Vec3, dir: Vec3, maxDist: Double = 6.0): Double? {
        var t = 0.01
        repeat(256) {
            val d = distance(origin + dir * t)
            if (d < 1e-5) return t
            t += d
            if (t > maxDist) return null
        }
        return null
    }

    data class Options(
        val depthIntrinsics: CameraIntrinsics = CameraIntrinsics(130.0, 130.0, 79.5, 59.5, 160, 120),
        val colorIntrinsics: CameraIntrinsics = CameraIntrinsics(260.0, 260.0, 159.5, 119.5, 320, 240),
        /** Depth noise standard deviation at 1 m; scales with depth squared like stereo/ToF error. */
        val noiseAt1m: Double = 0.004,
        val dropoutFraction: Double = 0.03,
        val seed: Int = 1,
        val renderColor: Boolean = true,
    )

    fun renderDepth(pose: Pose, k: CameraIntrinsics, noiseAt1m: Double, dropout: Double, rnd: Random): DepthFrame {
        val depth = ShortArray(k.width * k.height)
        val conf = ByteArray(k.width * k.height)
        for (v in 0 until k.height) for (u in 0 until k.width) {
            val dc = k.unproject(u.toDouble(), v.toDouble(), 1.0) // z = -1
            val dirCam = dc.normalized()
            val t = trace(pose.translation, pose.rotateVector(dirCam)) ?: continue
            if (rnd.nextDouble() < dropout) continue
            val z = t * -dirCam.z
            val noisy = z + gaussian(rnd) * noiseAt1m * z * z
            depth[v * k.width + u] = (noisy * 1000).toInt().coerceIn(0, 65535).toShort()
            conf[v * k.width + u] = (180 + rnd.nextInt(76)).toByte()
        }
        return DepthFrame(k.width, k.height, depth, conf)
    }

    fun renderColor(pose: Pose, k: CameraIntrinsics): RgbImage {
        val rgb = ByteArray(k.width * k.height * 3)
        val c = IntArray(3)
        for (v in 0 until k.height) for (u in 0 until k.width) {
            val dir = pose.rotateVector(k.unproject(u.toDouble(), v.toDouble(), 1.0).normalized())
            val t = trace(pose.translation, dir)
            if (t == null) { c[0] = 30; c[1] = 30; c[2] = 34 } else colorAt(pose.translation + dir * t, c)
            val o = (v * k.width + u) * 3
            rgb[o] = c[0].toByte(); rgb[o + 1] = c[1].toByte(); rgb[o + 2] = c[2].toByte()
        }
        return RgbImage(k.width, k.height, rgb)
    }

    /**
     * Orbits the camera around [target] on rings at the given elevations, mimicking a user walking
     * around an object, and returns an in-memory capture.
     */
    fun orbitCapture(
        target: Vec3,
        radius: Double,
        elevationsDeg: List<Double>,
        framesPerRing: Int,
        roi: Aabb,
        options: Options = Options(),
        preset: QualityPreset = QualityPreset.STANDARD,
    ): InMemoryCaptureSource {
        val rnd = Random(options.seed)
        val frames = ArrayList<FrameRecord>()
        val depth = HashMap<Int, DepthFrame>()
        val color = HashMap<Int, RgbImage>()
        var index = 0
        for (el in elevationsDeg) for (i in 0 until framesPerRing) {
            val az = 2 * PI * i / framesPerRing + Math.toRadians(el) // stagger rings
            val e = Math.toRadians(el)
            val eye = target + Vec3(radius * cos(e) * cos(az), radius * sin(e), radius * cos(e) * sin(az))
            val pose = Pose.lookAt(eye, target)
            depth[index] = renderDepth(pose, options.depthIntrinsics, options.noiseAt1m, options.dropoutFraction, rnd)
            if (options.renderColor) color[index] = renderColor(pose, options.colorIntrinsics)
            frames += FrameRecord(
                index = index,
                timestampNs = 1_000_000_000L + index * 66_000_000L,
                cameraPose = pose.toArray(),
                tracking = TrackingQuality.TRACKING,
                colorFile = if (options.renderColor) "color/%06d.jpg".format(index) else null,
                colorIntrinsics = options.colorIntrinsics,
                depthFile = "depth/%06d.sfd".format(index),
                depthIntrinsics = options.depthIntrinsics,
                meanDepthConfidence = 0.85,
            )
            index++
        }
        val session = CaptureSession(
            sessionId = "synthetic-${options.seed}",
            startedAt = "2026-01-01T00:00:00Z",
            captureMode = CaptureMode.DEPTH,
            qualityPreset = preset,
            roi = RegionOfInterest.of(roi),
            groundPlane = ground?.let { GroundPlane(listOf(0.0, it.y, 0.0), listOf(0.0, 1.0, 0.0)) },
            device = mapOf("model" to "synthetic"),
            source = "synthetic",
        )
        return InMemoryCaptureSource(session, frames, depth, color)
    }

    companion object {
        fun gaussian(rnd: Random): Double {
            // Box–Muller transform.
            val u1 = rnd.nextDouble().coerceAtLeast(1e-12); val u2 = rnd.nextDouble()
            return sqrt(-2 * kotlin.math.ln(u1)) * cos(2 * PI * u2)
        }
    }
}

/** Deterministic 3-D value noise in [0, 1), used as a matchable surface texture for synthetic MVS tests. */
object ValueNoise {
    private fun hash(x: Int, y: Int, z: Int): Double {
        var h = x * 374761393 + y * 668265263 + z * 1274126177
        h = (h xor (h ushr 13)) * 1274126177
        return ((h xor (h ushr 16)) and 0x7fffffff) / 2147483648.0
    }

    fun at(p: Vec3, cell: Double): Double {
        val fx = p.x / cell; val fy = p.y / cell; val fz = p.z / cell
        val x0 = floor(fx).toInt(); val y0 = floor(fy).toInt(); val z0 = floor(fz).toInt()
        val tx = fx - x0; val ty = fy - y0; val tz = fz - z0
        fun lerp(a: Double, b: Double, t: Double) = a + (b - a) * (t * t * (3 - 2 * t))
        val c00 = lerp(hash(x0, y0, z0), hash(x0 + 1, y0, z0), tx); val c10 = lerp(hash(x0, y0 + 1, z0), hash(x0 + 1, y0 + 1, z0), tx)
        val c01 = lerp(hash(x0, y0, z0 + 1), hash(x0 + 1, y0, z0 + 1), tx); val c11 = lerp(hash(x0, y0 + 1, z0 + 1), hash(x0 + 1, y0 + 1, z0 + 1), tx)
        return lerp(lerp(c00, c10, ty), lerp(c01, c11, ty), tz)
    }
}

/**
 * Synthetic marker-board captures: the scene's objects stand on a printed board; color images are rendered and marker
 * corners are projected analytically (with visibility test and pixel noise). Real detection is covered by the markers
 * module's OpenCV tests; this generator exercises everything after detection. Labelled `source = "synthetic"`.
 */
object SyntheticMarkerCapture {
    fun generate(
        objects: List<SdfShape>,
        board: app.scanforge.markers.BoardLayout,
        k: CameraIntrinsics,
        target: Vec3,
        radius: Double,
        elevationsDeg: List<Double>,
        framesPerRing: Int,
        preset: QualityPreset = QualityPreset.DRAFT,
        cornerNoisePx: Double = 0.3,
        seed: Int = 1,
    ): InMemoryCaptureSource {
        val scene = SyntheticScene(objects, SdfShape.Ground(0.0))
        val rnd = Random(seed)
        val frames = ArrayList<FrameRecord>(); val color = HashMap<Int, RgbImage>()
        var index = 0
        for (el in elevationsDeg) for (i in 0 until framesPerRing) {
            val az = 2 * PI * i / framesPerRing + Math.toRadians(el)
            val e = Math.toRadians(el)
            val pose = Pose.lookAt(target + Vec3(radius * cos(e) * cos(az), radius * sin(e), radius * cos(e) * sin(az)), target)
            val rgb = ByteArray(k.width * k.height * 3)
            for (v in 0 until k.height) for (u in 0 until k.width) {
                val dir = pose.rotateVector(k.unproject(u.toDouble(), v.toDouble(), 1.0).normalized())
                val t = scene.trace(pose.translation, dir)
                val o = (v * k.width + u) * 3
                val g: Int; val c: IntArray
                if (t == null) { c = intArrayOf(40, 42, 46) } else {
                    val p = pose.translation + dir * t
                    c = if (objects.any { it.distance(p) < 1e-3 }) {
                        val n = 0.45 * ValueNoise.at(p, 0.008) + 0.35 * ValueNoise.at(p, 0.02) + 0.2 * ValueNoise.at(p, 0.05)
                        intArrayOf((60 + 170 * n).toInt(), (50 + 140 * n).toInt(), (40 + 110 * n).toInt())
                    } else {
                        g = if (board.isBlackAtWorld(p.x, p.z)) 25 else if (onPaper(board, p)) 235 else 120
                        intArrayOf(g, g, g)
                    }
                }
                rgb[o] = c[0].toByte(); rgb[o + 1] = c[1].toByte(); rgb[o + 2] = c[2].toByte()
            }
            val obs = board.markers.mapNotNull { m ->
                val corners = board.cornersWorld(m.id)
                val px = corners.map { w ->
                    val camP = pose.inverse().transformPoint(w)
                    val pr = k.project(camP) ?: return@mapNotNull null
                    if (pr.x < 2 || pr.y < 2 || pr.x > k.width - 3 || pr.y > k.height - 3) return@mapNotNull null
                    val dir = (w - pose.translation)
                    val hit = scene.trace(pose.translation, dir.normalized()) ?: return@mapNotNull null
                    if (hit < dir.length() - 1e-3) return@mapNotNull null // hidden by the object
                    pr
                }
                app.scanforge.markers.MarkerObservation(m.id, px.flatMap { listOf(it.x + rnd.nextDouble(-cornerNoisePx, cornerNoisePx), it.y + rnd.nextDouble(-cornerNoisePx, cornerNoisePx)) })
            }
            color[index] = RgbImage(k.width, k.height, rgb)
            frames += FrameRecord(index, 1_000_000_000L + index * 100_000_000L, pose.toArray(), TrackingQuality.TRACKING,
                colorFile = "color/%06d.jpg".format(index), colorIntrinsics = k, markers = obs)
            index++
        }
        val session = CaptureSession(sessionId = "synthetic-marker-$seed", startedAt = "2026-01-01T00:00:00Z", captureMode = CaptureMode.MARKER,
            qualityPreset = preset, device = mapOf("model" to "synthetic"), source = "synthetic", board = board.spec.code, boardScale = board.scale)
        return InMemoryCaptureSource(session, frames, emptyMap(), color)
    }

    private fun onPaper(board: app.scanforge.markers.BoardLayout, p: Vec3): Boolean {
        val (cx, cy) = board.centerMm
        val xMm = p.x * 1000 / board.scale + cx; val yMm = p.z * 1000 / board.scale + cy
        return xMm in 0.0..board.spec.paper.widthMm && yMm in 0.0..board.spec.paper.heightMm
    }
}
