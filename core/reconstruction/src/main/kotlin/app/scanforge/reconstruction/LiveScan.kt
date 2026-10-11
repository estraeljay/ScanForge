package app.scanforge.reconstruction

import app.scanforge.geometry.Aabb
import app.scanforge.geometry.Pose
import app.scanforge.geometry.Vec3
import kotlin.math.acos
import kotlin.math.abs

/**
 * Decides which camera frames become keyframes: enough motion since the last keyframe to add new
 * viewpoints, but not so much (or so fast) that images blur.
 */
class KeyframeSelector(
    val minTranslation: Double = 0.03,
    val minRotationDeg: Double = 5.0,
    val minIntervalNs: Long = 150_000_000L,
    /** Faster than this (m/s) the frame is likely motion-blurred and is rejected. */
    val maxSpeed: Double = 0.5,
) {
    private var lastPose: Pose? = null
    private var lastTimestamp = 0L
    private var prevPose: Pose? = null
    private var prevTimestamp = 0L

    /** Most recent camera speed estimate (m/s), for "move more slowly" guidance. */
    var speed: Double = 0.0
        private set

    fun reset() { lastPose = null; prevPose = null; speed = 0.0 }

    fun observe(pose: Pose, timestampNs: Long): Boolean {
        prevPose?.let { p ->
            val dt = (timestampNs - prevTimestamp) / 1e9
            if (dt > 0) speed = p.translation.distanceTo(pose.translation) / dt
        }
        prevPose = pose; prevTimestamp = timestampNs
        val last = lastPose
        val accept = when {
            last == null -> true
            timestampNs - lastTimestamp < minIntervalNs -> false
            speed > maxSpeed -> false
            else -> last.translation.distanceTo(pose.translation) >= minTranslation || rotationDeg(last, pose) >= minRotationDeg
        }
        if (accept) { lastPose = pose; lastTimestamp = timestampNs }
        return accept
    }

    private fun rotationDeg(a: Pose, b: Pose): Double {
        val d = (a.rotation.x * b.rotation.x + a.rotation.y * b.rotation.y + a.rotation.z * b.rotation.z + a.rotation.w * b.rotation.w)
        return Math.toDegrees(2 * acos(abs(d).coerceAtMost(1.0)))
    }
}

/** Guidance shown on the scanning HUD; every value is derived from measured state. */
enum class LiveGuidance { INITIALIZING, TRACKING_LOST, MOVE_SLOWER, MOVE_CLOSER, MOVE_FARTHER, CONTINUE_AROUND, RAISE_ANGLE, CAPTURE_TOP, IMPROVE_LIGHT, COMPLETE }

data class LiveSnapshot(
    val keyframes: Int,
    val viewFraction: Double,
    val hint: CoverageHint,
    val surfaceVoxels: Int,
    val strongSurfaceFraction: Double,
    val points: FloatArray,
    val pointClasses: ByteArray,
    val nearestGapRadians: Double?,
    val lastDepthMeters: Double?,
)

/**
 * Low-resolution fusion running during capture: provides the live point preview and measured coverage.
 * It never feeds the final model, which is reconstructed from the saved capture at full quality.
 * Thread-safe: integrate() runs on a background executor, snapshot() may be called from the UI.
 */
class LiveScan(val roi: Aabb, voxelSize: Double = 0.008) {
    private val volume = TsdfVolume(voxelSize, voxelSize * 4, roi)
    val coverage = ViewCoverage(roi.center)
    private var keyframes = 0
    private var lastDepth: Double? = null

    @Synchronized fun integrate(frame: FrameRecord, depth: DepthFrame?) {
        keyframes++
        coverage.addView(frame.pose)
        if (depth != null && frame.depthIntrinsics != null) {
            volume.integrate(depth, frame.depthIntrinsics, frame.pose, params = IntegrationParams(allocationStride = 2))
            lastDepth = centerDepth(depth)
        }
    }

    private fun centerDepth(d: DepthFrame): Double? {
        val values = ArrayList<Double>()
        for (v in d.height * 2 / 5 until d.height * 3 / 5) for (u in d.width * 2 / 5 until d.width * 3 / 5) {
            val z = d.depthMetersAt(u, v); if (z > 0) values += z
        }
        return values.sorted().getOrNull(values.size / 2)
    }

    @Synchronized fun snapshot(cameraPosition: Vec3?, maxPoints: Int = 20_000): LiveSnapshot {
        val (surface, strong) = volume.surfacePointsCount()
        val (pts, cls) = volume.surfacePoints(maxPoints)
        return LiveSnapshot(
            keyframes, coverage.observedFraction(), coverage.hint(), surface,
            if (surface == 0) 0.0 else strong.toDouble() / surface, pts, cls,
            cameraPosition?.let { coverage.nearestGap(it) }, lastDepth,
        )
    }

    private fun TsdfVolume.surfacePointsCount() = surfaceObservationStats(3)

    companion object {
        /**
         * Chooses the single most useful instruction. Tracking and motion problems take priority over coverage,
         * and distance advice uses the measured median depth at the screen center.
         */
        fun guidance(
            tracking: TrackingQuality?,
            speed: Double,
            maxSpeed: Double,
            depth: Double?,
            minDistance: Double,
            maxDistance: Double,
            lowLight: Boolean,
            hint: CoverageHint?,
        ): LiveGuidance = when {
            tracking == null -> LiveGuidance.INITIALIZING
            tracking != TrackingQuality.TRACKING -> LiveGuidance.TRACKING_LOST
            lowLight -> LiveGuidance.IMPROVE_LIGHT
            speed > maxSpeed -> LiveGuidance.MOVE_SLOWER
            depth != null && depth < minDistance -> LiveGuidance.MOVE_FARTHER
            depth != null && depth > maxDistance -> LiveGuidance.MOVE_CLOSER
            hint == CoverageHint.CONTINUE_AROUND -> LiveGuidance.CONTINUE_AROUND
            hint == CoverageHint.RAISE_ANGLE -> LiveGuidance.RAISE_ANGLE
            hint == CoverageHint.CAPTURE_TOP -> LiveGuidance.CAPTURE_TOP
            hint == CoverageHint.COMPLETE -> LiveGuidance.COMPLETE
            else -> LiveGuidance.INITIALIZING
        }
    }
}
