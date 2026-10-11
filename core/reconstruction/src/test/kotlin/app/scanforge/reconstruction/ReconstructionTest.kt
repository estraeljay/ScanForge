package app.scanforge.reconstruction

import app.scanforge.geometry.Aabb
import app.scanforge.geometry.MeshAnalysis
import app.scanforge.geometry.Pose
import app.scanforge.geometry.Vec3
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ReconstructionTest {
    private fun close(expected: Double, actual: Double, eps: Double, what: String) =
        assertTrue(abs(expected - actual) <= eps, "$what: expected $expected ± $eps but was $actual")

    private val boxScene = SyntheticScene(listOf(SdfShape.Box(Vec3(0.0, 0.04, 0.0), Vec3(0.06, 0.04, 0.03))))
    private val roi = Aabb(Vec3(-0.25, -0.05, -0.25), Vec3(0.25, 0.3, 0.25))

    @Test fun intrinsicsProjectionRoundTrip() {
        val k = CameraIntrinsics(500.0, 510.0, 320.0, 240.0, 640, 480)
        val p = k.unproject(100.0, 50.0, 0.7)
        val back = k.project(p)!!
        close(100.0, back.x, 1e-9, "u"); close(50.0, back.y, 1e-9, "v"); close(0.7, back.z, 1e-12, "depth")
        val s = k.scaledTo(160, 120)
        close(125.0, s.fx, 1e-9, "scaled fx")
        close(79.625, s.cx, 1e-9, "scaled cx") // (320.5 / 4) - 0.5
    }

    @Test fun depthCodecRoundTrip() {
        val f = DepthFrame(3, 2, shortArrayOf(1, 2, 3, 400, 0, -1), byteArrayOf(0, 10, 20, 30, 40, -1))
        val bytes = ByteArrayOutputStream().also { DepthFrameCodec.write(f, it) }.toByteArray()
        val g = DepthFrameCodec.read(ByteArrayInputStream(bytes))
        assertEquals(3, g.width); assertTrue(f.depthMm.contentEquals(g.depthMm)); assertTrue(f.confidence!!.contentEquals(g.confidence!!))
        close(65.535, g.depthMetersAt(2, 1), 1e-9, "u16 depth is unsigned")
        assertFailsWith<IllegalArgumentException> { DepthFrameCodec.read(ByteArrayInputStream(ByteArray(16))) }
    }

    @Test fun reconstructsBoxDimensionsFromNoisyDepth() {
        val capture = boxScene.orbitCapture(Vec3(0.0, 0.04, 0.0), 0.45, listOf(15.0, 40.0, 70.0), 24, roi)
        val settings = ReconstructionSettings.forPreset(QualityPreset.STANDARD)
        val stages = mutableListOf<Stage>()
        val result = ReconstructionPipeline(settings).run(capture) { stages += it.stage }
        val b = result.mesh.bounds()!!
        // Expected tolerance: about one voxel plus depth noise.
        val tol = settings.voxelSize * 1.5 + 0.002
        close(0.12, b.size.x, tol, "width")
        close(0.06, b.size.z, tol, "depth")
        // Height is measured from the ground cut, which removes up to groundCutVoxels above the table.
        close(0.08, b.size.y, tol + settings.voxelSize * settings.groundCutVoxels, "height")
        close(0.08, b.max.y, tol, "top surface height")
        assertTrue(result.coverage.viewFraction > 0.9, "view coverage ${result.coverage.viewFraction}")
        assertTrue(result.coverage.strongVertexFraction > 0.8)
        assertEquals(1, MeshAnalysis.topology(result.mesh).componentCount)
        // Bottom is open (object rests on the table), so the mesh must not claim to be watertight.
        assertTrue(!result.coverage.watertight && result.coverage.holeCount >= 1)
        assertTrue(result.mesh.hasColors)
        assertEquals(listOf(Stage.VALIDATING, Stage.INTEGRATING, Stage.EXTRACTING, Stage.CLEANING, Stage.ANALYZING, Stage.DONE), stages.distinct())
    }

    @Test fun reconstructsCylinderDiameter() {
        val scene = SyntheticScene(listOf(SdfShape.Cylinder(Vec3(0.0, 0.0, 0.0), 0.04, 0.10)))
        val capture = scene.orbitCapture(Vec3(0.0, 0.05, 0.0), 0.4, listOf(20.0, 50.0, 75.0), 20, roi, SyntheticScene.Options(renderColor = false))
        val result = ReconstructionPipeline(ReconstructionSettings.forPreset(QualityPreset.STANDARD)).run(capture)
        val b = result.mesh.bounds()!!
        close(0.08, b.size.x, 0.008, "diameter x")
        close(0.08, b.size.z, 0.008, "diameter z")
    }

    @Test fun refusesPartialCoverage() {
        // Camera only ever looks at one side.
        val full = boxScene.orbitCapture(Vec3(0.0, 0.04, 0.0), 0.45, listOf(20.0), 64, roi, SyntheticScene.Options(renderColor = false))
        val oneSide = InMemoryCaptureSource(full.session, full.frames.take(3), full.frames.associate { it.index to full.loadDepth(it)!! }, emptyMap())
        val e = assertFailsWith<ReconstructionException> {
            ReconstructionPipeline(ReconstructionSettings.forPreset(QualityPreset.DRAFT).copy(minFrames = 3)).run(oneSide)
        }
        assertEquals(ReconstructionFailure.INSUFFICIENT_COVERAGE, e.failure)
        val tooFew = assertFailsWith<ReconstructionException> { ReconstructionPipeline(ReconstructionSettings.forPreset(QualityPreset.STANDARD)).run(oneSide) }
        assertEquals(ReconstructionFailure.NOT_ENOUGH_FRAMES, tooFew.failure)
    }

    @Test fun cancellationAndCheckpointResume() {
        val capture = boxScene.orbitCapture(Vec3(0.0, 0.04, 0.0), 0.45, listOf(15.0, 45.0, 70.0), 24, roi, SyntheticScene.Options(renderColor = false))
        val settings = ReconstructionSettings.forPreset(QualityPreset.DRAFT).copy(checkpointEveryFrames = 5)
        val dir = Files.createTempDirectory("ckpt").toFile()
        val store = CheckpointStore(dir)
        val token = CancellationToken()
        val e = assertFailsWith<ReconstructionException> {
            ReconstructionPipeline(settings).run(capture, store, token) { if (it.stage == Stage.INTEGRATING && it.completed == 12) token.cancel() }
        }
        assertEquals(ReconstructionFailure.CANCELLED, e.failure)
        val resumed = ReconstructionPipeline(settings).run(capture, store)
        assertEquals(10, resumed.resumedFromFrame)
        val fresh = ReconstructionPipeline(settings).run(capture)
        assertEquals(fresh.mesh.triangleCount, resumed.mesh.triangleCount)
        assertTrue(!java.io.File(dir, "tsdf.ckpt").exists(), "checkpoint removed after success")
    }

    @Test fun captureWriterIsCrashTolerantAndSupportsUndo() {
        val dir = Files.createTempDirectory("cap").toFile()
        val session = CaptureSession(sessionId = "s", startedAt = "2026-01-01T00:00:00Z", captureMode = CaptureMode.DEPTH, qualityPreset = QualityPreset.DRAFT)
        val depth = DepthFrame(2, 2, shortArrayOf(500, 500, 500, 500), null)
        CaptureWriter(dir, session).use { w ->
            repeat(5) { w.append(FrameRecord(it, it.toLong(), Pose.IDENTITY.toArray(), TrackingQuality.TRACKING), depth, byteArrayOf(1, 2, 3)) }
            w.discardLast(2)
            assertEquals(3, w.frameCount)
        }
        // Simulate a crash mid-line.
        java.io.File(dir, "frames.jsonl").appendText("{\"index\":3,\"timest")
        val src = DirectoryCaptureSource(dir, null)
        assertEquals(3, src.frames.size)
        assertEquals(500, src.loadDepth(src.frames[2])!!.depthMm[0].toInt())
        assertTrue(!java.io.File(dir, "depth/000004.sfd").exists())
        CaptureWriter(dir, session).use { w -> assertEquals(3, w.nextIndex()) }
    }

    @Test fun viewCoverageGuidance() {
        val c = ViewCoverage(Vec3.ZERO)
        assertEquals(CoverageHint.CONTINUE_AROUND, c.hint())
        for (i in 0 until 24) {
            val a = 2 * Math.PI * i / 24
            c.addView(Pose.lookAt(Vec3(0.5 * Math.cos(a), 0.1, 0.5 * Math.sin(a)), Vec3.ZERO))
        }
        assertEquals(CoverageHint.RAISE_ANGLE, c.hint())
        // A camera looking away from the object must not count.
        assertTrue(!c.addView(Pose.lookAt(Vec3(0.5, 0.5, 0.0), Vec3(2.0, 0.5, 0.0))))
        for (i in 0 until 24) {
            val a = 2 * Math.PI * i / 24
            c.addView(Pose.lookAt(Vec3(0.35 * Math.cos(a), 0.35, 0.35 * Math.sin(a)), Vec3.ZERO))
            c.addView(Pose.lookAt(Vec3(0.1 * Math.cos(a), 0.5, 0.1 * Math.sin(a)), Vec3.ZERO))
        }
        assertEquals(CoverageHint.COMPLETE, c.hint())
    }
}
