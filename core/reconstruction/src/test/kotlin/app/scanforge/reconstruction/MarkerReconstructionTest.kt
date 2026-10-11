package app.scanforge.reconstruction

import app.scanforge.geometry.Vec3
import app.scanforge.markers.BoardLayout
import app.scanforge.markers.BoardSpec
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class MarkerReconstructionTest {
    private fun close(e: Double, a: Double, eps: Double, what: String) = assertTrue(abs(e - a) <= eps, "$what: expected $e ± $eps, got $a")

    private val k = CameraIntrinsics(520.0, 520.0, 319.5, 239.5, 640, 480)
    private val board = BoardLayout(BoardSpec.A3)
    private val box = SdfShape.Box(Vec3(0.0, 0.035, 0.0), Vec3(0.05, 0.035, 0.025))

    @Test fun markerCaptureReconstructsBoxWithoutArcore() {
        val capture = SyntheticMarkerCapture.generate(listOf(box), board, k, Vec3(0.0, 0.03, 0.0), 0.5, listOf(35.0, 55.0, 75.0), 12)
        val stages = mutableListOf<Stage>()
        val t0 = System.nanoTime()
        val prepared = MarkerReconstruction.prepare(capture) { stages += it.stage }
        val tMvs = (System.nanoTime() - t0) / 1e9
        val r = prepared.report
        close(520.0, r.focalPx, 520 * 0.01, "focal")
        assertTrue(r.meanRmsPx < 0.6, "pose rms ${r.meanRmsPx}")
        assertEquals(36, r.framesWithPose)
        val result = ReconstructionPipeline(MarkerReconstruction.settingsFor(QualityPreset.DRAFT)).run(prepared.source) { stages += it.stage }
        val b = result.mesh.bounds()!!
        println("marker pipeline: f=%.1f rms=%.2fpx depthFrames=%d mvs=%.1fs size=%.1f x %.1f x %.1f mm (truth 100 x 70 x 50) tris=%d holes=%d".format(
            r.focalPx, r.meanRmsPx, r.depthFramesWithData, tMvs, b.size.x * 1000, b.max.y * 1000, b.size.z * 1000, result.mesh.triangleCount, result.coverage.holeCount))
        val m = result.mesh
        val out = (0 until m.vertexCount).map { m.vertex(it) }.filter { kotlin.math.abs(it.z) > 0.027 || kotlin.math.abs(it.x) > 0.052 }
        println("bounds min=${b.min} max=${b.max}; ${out.size}/${m.vertexCount} vertices outside box+2mm; y of those: " +
            out.map { (it.y * 1000).toInt() }.sorted().let { if (it.isEmpty()) "-" else "${it.first()}..${it.last()} median ${it[it.size / 2]}" } +
            "; z range of those: " + out.map { (it.z * 1000).toInt() }.sorted().let { if (it.isEmpty()) "-" else "${it.first()}..${it.last()}" })
        close(0.100, b.size.x, 0.006, "width")
        close(0.050, b.size.z, 0.006, "depth")
        close(0.070, b.max.y, 0.006, "height above board")
        assertTrue(Stage.TRACKING in stages && Stage.MATCHING in stages && Stage.INTEGRATING in stages)
    }

    @Test fun printScaleCorrectionScalesTheModel() {
        // Board printed at 98 % and the user measured the 100 mm bar as 98 mm: geometry must still be true size.
        val printed = BoardLayout(BoardSpec.A3, 0.98)
        val capture = SyntheticMarkerCapture.generate(listOf(box), printed, k, Vec3(0.0, 0.03, 0.0), 0.5, listOf(40.0, 65.0), 12, seed = 2)
        val prepared = MarkerReconstruction.prepare(capture)
        val b = ReconstructionPipeline(MarkerReconstruction.settingsFor(QualityPreset.DRAFT)).run(prepared.source).mesh.bounds()!!
        close(0.100, b.size.x, 0.007, "width with scale correction")
    }

    @Test fun tooFewBoardViewsFailsClearly() {
        val capture = SyntheticMarkerCapture.generate(listOf(box), board, k, Vec3(0.0, 0.03, 0.0), 0.5, listOf(45.0), 5)
        val e = assertFailsWith<ReconstructionException> { MarkerReconstruction.prepare(capture) }
        assertEquals(ReconstructionFailure.BOARD_NOT_FOUND, e.failure)
    }
}
