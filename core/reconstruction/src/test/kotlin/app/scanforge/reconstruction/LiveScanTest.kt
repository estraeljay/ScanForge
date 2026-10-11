package app.scanforge.reconstruction

import app.scanforge.geometry.Aabb
import app.scanforge.geometry.Pose
import app.scanforge.geometry.Quat
import app.scanforge.geometry.Vec3
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LiveScanTest {
    @Test fun keyframeSelectionNeedsMotionAndRejectsFastMotion() {
        val k = KeyframeSelector()
        val p0 = Pose(Vec3.ZERO, Quat.IDENTITY)
        assertTrue(k.observe(p0, 0))
        assertFalse(k.observe(Pose(Vec3(0.01, 0.0, 0.0), Quat.IDENTITY), 200_000_000), "too little motion")
        assertTrue(k.observe(Pose(Vec3(0.04, 0.0, 0.0), Quat.IDENTITY), 400_000_000))
        assertFalse(k.observe(Pose(Vec3(0.2, 0.0, 0.0), Quat.IDENTITY), 500_000_000), "1.6 m/s is too fast")
        assertTrue(k.speed > 1.0)
        assertTrue(k.observe(Pose(Vec3(0.2, 0.0, 0.0), Quat.fromAxisAngle(Vec3.Y, Math.toRadians(8.0))), 900_000_000), "rotation alone counts")
    }

    @Test fun livePreviewReportsMeasuredCoverageAndPoints() {
        val roi = Aabb(Vec3(-0.25, -0.05, -0.25), Vec3(0.25, 0.3, 0.25))
        val scene = SyntheticScene(listOf(SdfShape.Box(Vec3(0.0, 0.04, 0.0), Vec3(0.06, 0.04, 0.03))))
        val cap = scene.orbitCapture(Vec3(0.0, 0.04, 0.0), 0.45, listOf(15.0), 24, roi, SyntheticScene.Options(renderColor = false))
        val live = LiveScan(roi)
        assertEquals(0, live.snapshot(null).surfaceVoxels)
        cap.frames.forEach { live.integrate(it, cap.loadDepth(it)) }
        val snap = live.snapshot(cap.frames.first().pose.translation)
        assertEquals(24, snap.keyframes)
        assertTrue(snap.surfaceVoxels > 100)
        assertEquals(snap.points.size / 3, snap.pointClasses.size)
        assertEquals(CoverageHint.RAISE_ANGLE, snap.hint, "only the low ring was captured")
        assertTrue(snap.lastDepthMeters!! in 0.3..0.6)
    }

    @Test fun guidancePrioritisesTrackingThenMotionThenDistanceThenCoverage() {
        fun g(t: TrackingQuality?, speed: Double = 0.1, d: Double? = 0.5, light: Boolean = false, h: CoverageHint? = CoverageHint.CONTINUE_AROUND) =
            LiveScan.guidance(t, speed, 0.5, d, 0.25, 1.5, light, h)
        assertEquals(LiveGuidance.INITIALIZING, g(null))
        assertEquals(LiveGuidance.TRACKING_LOST, g(TrackingQuality.PAUSED, speed = 3.0))
        assertEquals(LiveGuidance.IMPROVE_LIGHT, g(TrackingQuality.TRACKING, light = true))
        assertEquals(LiveGuidance.MOVE_SLOWER, g(TrackingQuality.TRACKING, speed = 0.9))
        assertEquals(LiveGuidance.MOVE_FARTHER, g(TrackingQuality.TRACKING, d = 0.1))
        assertEquals(LiveGuidance.MOVE_CLOSER, g(TrackingQuality.TRACKING, d = 3.0))
        assertEquals(LiveGuidance.CONTINUE_AROUND, g(TrackingQuality.TRACKING))
        assertEquals(LiveGuidance.COMPLETE, g(TrackingQuality.TRACKING, h = CoverageHint.COMPLETE))
    }
}
