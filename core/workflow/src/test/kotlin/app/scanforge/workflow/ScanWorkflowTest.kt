package app.scanforge.workflow

import app.scanforge.geometry.Aabb
import app.scanforge.geometry.LengthUnit
import app.scanforge.geometry.Vec3
import app.scanforge.project.MeasurementRecords
import app.scanforge.project.MeasurementType
import app.scanforge.project.ProjectPackager
import app.scanforge.project.ProjectStatus
import app.scanforge.project.ProjectStore
import app.scanforge.reconstruction.ReconstructionException
import app.scanforge.reconstruction.SdfShape
import app.scanforge.reconstruction.SyntheticScene
import java.io.File
import java.nio.file.Files
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ScanWorkflowTest {
    @Test fun captureToPackageWithMeasurements() {
        val store = ProjectStore(Files.createTempDirectory("wf").toFile())
        val wf = ScanWorkflow(store)
        val scene = SyntheticScene(listOf(SdfShape.Box(Vec3(0.0, 0.04, 0.0), Vec3(0.06, 0.04, 0.03))))
        val capture = scene.orbitCapture(Vec3(0.0, 0.04, 0.0), 0.45, listOf(15.0, 40.0, 70.0), 24,
            Aabb(Vec3(-0.25, -0.05, -0.25), Vec3(0.25, 0.3, 0.25)), SyntheticScene.Options(renderColor = false))
        val p = store.create("Synthetic box")
        val doc = wf.reconstruct(p.id, capture)
        assertEquals(ProjectStatus.RECONSTRUCTED, doc.status)
        assertEquals("external", doc.scale.source)
        val bbox = doc.measurements.single { it.type == MeasurementType.BOUNDING_BOX }
        val mm = MeasurementRecords.calibrated(doc, bbox).map { LengthUnit.MILLIMETER.fromMeters(it) }
        assertTrue(abs(mm[0] - 120) < 9 && abs(mm[2] - 60) < 9, "bbox mm $mm")
        assertTrue(doc.measurements.none { it.type == MeasurementType.VOLUME }, "open mesh must not report a volume")
        val height = doc.measurements.single { it.label == ScanWorkflow.SUPPORT_HEIGHT_LABEL }
        assertTrue(abs(height.rawValues[0] - 0.08) < 0.005, "height above support ${height.rawValues[0]}")
        assertTrue(File(store.dir(p.id), "model/evidence.bin").length() == store.loadModel(p.id).vertexCount.toLong())
        val withDistance = wf.addDistance(p.id, Vec3(0.0, 0.0, 0.0), Vec3(0.0, 0.08, 0.0), "height")
        assertEquals(0.08, withDistance.measurements.last().rawValues[0], 1e-12)
        val pkg = File(store.root, "box.sf3d")
        ProjectPackager.pack(store, p.id, pkg)
        ProjectPackager.verify(pkg)
    }

    @Test fun failedReconstructionIsPersisted() {
        val store = ProjectStore(Files.createTempDirectory("wf").toFile())
        val scene = SyntheticScene(listOf(SdfShape.Sphere(Vec3(0.0, 0.05, 0.0), 0.05)))
        val capture = scene.orbitCapture(Vec3(0.0, 0.05, 0.0), 0.4, listOf(20.0), 4,
            Aabb(Vec3(-0.2, -0.05, -0.2), Vec3(0.2, 0.2, 0.2)), SyntheticScene.Options(renderColor = false))
        val p = store.create("Too short")
        assertFailsWith<ReconstructionException> { ScanWorkflow(store).reconstruct(p.id, capture) }
        val doc = store.load(p.id)
        assertEquals(ProjectStatus.FAILED, doc.status)
        assertEquals("not_enough_frames", doc.failureReason)
    }
}
