package app.scanforge.reconstruction

import app.scanforge.geometry.Pose
import app.scanforge.geometry.Vec3
import java.nio.file.Files
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ColmapExportTest {
    @Test fun colmapPosesReprojectIdenticallyToScanForgeConvention() {
        val k = CameraIntrinsics(500.0, 505.0, 319.5, 239.5, 640, 480)
        val pose = Pose.lookAt(Vec3(0.4, 0.3, 0.5), Vec3(0.0, 0.05, 0.0))
        val f = FrameRecord(0, 0, pose.toArray(), TrackingQuality.TRACKING, "color/000000.jpg", k)
        val (q, t) = ColmapExport.worldToColmap(f)
        for (w in listOf(Vec3(0.0, 0.05, 0.0), Vec3(0.03, 0.08, -0.02), Vec3(-0.05, 0.0, 0.04))) {
            val ours = k.project(pose.inverse().transformPoint(w))!!
            val (cx, cy) = ColmapExport.projectColmap(q, t, k, w)
            // COLMAP pixel coordinates are ours + 0.5 (pixel-center convention).
            assertTrue(abs(cx - (ours.x + 0.5)) < 1e-9 && abs(cy - (ours.y + 0.5)) < 1e-9, "($cx,$cy) vs (${ours.x},${ours.y})")
        }
    }

    @Test fun writesModelFilesForCapture() {
        val dir = Files.createTempDirectory("cap").toFile()
        val k = CameraIntrinsics(260.0, 260.0, 159.5, 119.5, 320, 240)
        val session = CaptureSession(sessionId = "s", startedAt = "2026-01-01T00:00:00Z", captureMode = CaptureMode.PHOTO, qualityPreset = QualityPreset.STANDARD)
        CaptureWriter(dir, session).use { w ->
            repeat(3) { i -> w.append(FrameRecord(i, i.toLong(), Pose.lookAt(Vec3(0.5, 0.2, i * 0.1), Vec3.ZERO).toArray(), TrackingQuality.TRACKING, colorIntrinsics = k), null, byteArrayOf(1, 2, 3)) }
        }
        val out = Files.createTempDirectory("colmap").toFile()
        val r = ColmapExport.export(dir, out)
        assertEquals(3, r.images); assertEquals(1, r.cameras)
        assertTrue(java.io.File(out, "sparse/0/cameras.txt").readText().contains("1 PINHOLE 320 240 260 260 160 120"))
        assertEquals(3, java.io.File(out, "images").listFiles()!!.size)
    }
}
