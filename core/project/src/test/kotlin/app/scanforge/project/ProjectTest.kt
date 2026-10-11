package app.scanforge.project

import app.scanforge.geometry.LengthUnit
import app.scanforge.geometry.Mesh
import app.scanforge.geometry.MeshOps
import app.scanforge.geometry.Vec3
import java.io.File
import java.nio.file.Files
import java.time.Instant
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ProjectTest {
    private var tick = 0L
    private fun store() = ProjectStore(Files.createTempDirectory("store").toFile()) { Instant.ofEpochSecond(1_800_000_000 + tick++) }

    private val tetra = Mesh.fromVertices(
        listOf(Vec3(0.0, 0.0, 0.0), Vec3(0.1, 0.0, 0.0), Vec3(0.0, 0.2, 0.0), Vec3(0.0, 0.0, 0.05)),
        intArrayOf(0, 2, 1, 0, 1, 3, 0, 3, 2, 1, 2, 3),
        ByteArray(12) { 100 },
    )

    @Test fun createListRenameArchiveDuplicateDelete() {
        val s = store()
        val a = s.create("Mug", ObjectCategory.HOUSEHOLD_OBJECT, listOf("kitchen"))
        val b = s.create("Bracket", ObjectCategory.MECHANICAL_COMPONENT)
        assertEquals(listOf(b.id, a.id), s.list().map { it.id })
        assertEquals("Mug 2", s.rename(a.id, " Mug 2 ").name)
        s.setArchived(b.id, true)
        assertEquals(1, s.list().size); assertEquals(2, s.list(includeArchived = true).size)
        val c = s.duplicate(a.id)
        assertEquals("Mug 2 (copy)", c.name)
        s.delete(c.id)
        assertFalse(s.exists(c.id))
        assertFailsWith<ProjectNotFoundException> { s.load(c.id) }
        assertFailsWith<IllegalArgumentException> { s.create("  ") }
    }

    @Test fun rejectsNewerSchema() {
        val s = store()
        val p = s.create("x")
        val f = File(s.dir(p.id), "project.json")
        f.writeText(f.readText().replace("\"schema_version\": 1", "\"schema_version\": 99"))
        assertFailsWith<UnsupportedSchemaException> { s.load(p.id) }
        assertTrue(s.list().isEmpty(), "unreadable projects are skipped in the library")
    }

    @Test fun modelVersionsAreNonDestructiveWithUndoRedo() {
        val s = store()
        val p = s.create("Part")
        s.addModelVersion(p.id, tetra, "reconstruction")
        val smaller = MeshOps.deleteTriangles(tetra, setOf(0))
        var doc = s.addModelVersion(p.id, smaller, "delete_triangles", mapOf("count" to "1"))
        assertEquals(2, doc.model.currentVersion)
        assertEquals(3, s.loadModel(p.id).triangleCount)
        assertEquals(4, s.loadModel(p.id, 1).triangleCount)
        doc = s.undo(p.id)
        assertEquals(4, s.loadModel(p.id).triangleCount)
        assertTrue(s.canRedo(doc))
        s.redo(p.id)
        s.undo(p.id)
        // A new edit after undo discards the redo branch but never version 1.
        doc = s.addModelVersion(p.id, MeshOps.transform(tetra, app.scanforge.geometry.Pose.IDENTITY, 2.0), "scale")
        assertEquals(listOf(1, 2), doc.model.versions.map { it.version })
        assertEquals("scale", doc.model.current!!.operation)
        assertTrue(abs(doc.model.source!!.boundsMax[1] - 0.2) < 1e-6)
        assertFalse(doc.model.source!!.watertight && doc.model.source!!.holeCount > 0)
        assertFailsWith<IllegalArgumentException> { s.undo(p.id); s.undo(p.id) }
    }

    @Test fun calibrationScalesMeasurementsAndReportsSpread() {
        val s = store()
        var doc = s.create("Box")
        s.addModelVersion(doc.id, tetra, "reconstruction")
        doc = s.load(doc.id)
        val q = app.scanforge.geometry.MeasurementQuality(app.scanforge.geometry.ConfidenceLevel.MEDIUM, listOf("r"), 0.004)
        doc = doc.copy(measurements = listOf(
            MeasurementRecords.create(doc, MeasurementType.DISTANCE, "height", listOf(listOf(0.0, 0.0, 0.0), listOf(0.0, 0.2, 0.0)), listOf(0.2), 1, false, q, s.now()),
            MeasurementRecords.create(doc, MeasurementType.SURFACE_AREA, "area", emptyList(), listOf(0.01), 2, false, q, s.now()),
        ))
        doc = Calibration.addReference(doc, CalibrationMethod.REFERENCE_LENGTH, 0.2, 0.21, emptyList(), s.now())
        assertEquals("reference_length", doc.scale.source)
        assertEquals(0.21, MeasurementRecords.calibrated(doc, doc.measurements[0])[0], 1e-12)
        assertEquals(0.01 * 1.05 * 1.05, MeasurementRecords.calibrated(doc, doc.measurements[1])[0], 1e-12)
        doc = Calibration.addReference(doc, CalibrationMethod.REFERENCE_LENGTH, 0.1, 0.103, emptyList(), s.now())
        assertEquals((1.05 + 1.03) / 2, doc.scale.factor, 1e-12)
        assertNotNull(doc.scale.relativeSpread)
        doc = Calibration.setActive(doc, doc.scale.calibrations[0].id, false)
        doc = Calibration.setActive(doc, doc.scale.calibrations[1].id, false)
        assertEquals("device_tracking", doc.scale.source); assertEquals(1.0, doc.scale.factor)
        assertFailsWith<IllegalArgumentException> { Calibration.addReference(doc, CalibrationMethod.USER_ENTERED, 0.2, 2.0, emptyList(), s.now()) }
        val csv = MeasurementReport.csv(doc, LengthUnit.MILLIMETER)
        assertTrue(csv.lines()[1].contains("200.000000,mm"), csv)
        assertTrue(csv.lines()[2].contains("10000.000000,mm^2"), csv)
        assertTrue(MeasurementReport.json(doc, LengthUnit.CENTIMETER).toString().contains("\"unit\":\"cm\""))
    }

    @Test fun packageRoundTripAndTamperDetection() {
        val s = store()
        val p = s.create("Widget")
        s.addModelVersion(p.id, tetra, "reconstruction")
        s.addModelVersion(p.id, MeshOps.deleteTriangles(tetra, setOf(1)), "delete_triangles")
        File(s.captureDir(p.id), "session.json").writeText("{}")
        val out = File(s.root, "out/widget.sf3d")
        val manifest = ProjectPackager.pack(s, p.id, out)
        assertEquals("model/v002.ply", manifest.primaryModel)
        assertTrue(manifest.files.any { it.path == "model/v001.ply" })
        assertTrue(manifest.files.none { it.path.startsWith("capture/") })
        ProjectPackager.verify(out)
        val imported = ProjectPackager.unpack(out, s)
        assertTrue(imported.id != p.id)
        assertEquals(3, s.loadModel(imported.id).triangleCount)
        // Tamper: rewrite one file inside the zip.
        val bad = File(s.root, "out/bad.sf3d")
        ZipFile(out).use { zin ->
            ZipOutputStream(bad.outputStream()).use { zout ->
                for (e in zin.entries()) {
                    zout.putNextEntry(ZipEntry(e.name))
                    val bytes = zin.getInputStream(e).readBytes()
                    if (e.name == "model/v002.ply") bytes[bytes.size - 1] = (bytes.last() + 1).toByte()
                    zout.write(bytes); zout.closeEntry()
                }
            }
        }
        assertFailsWith<PackageValidationException> { ProjectPackager.verify(bad) }
        assertFailsWith<PackageValidationException> { ProjectPackager.checkSafePath("../etc/passwd") }
        val withCapture = ProjectPackager.pack(s, p.id, File(s.root, "out/full.sf3d"), includeCapture = true)
        assertTrue(withCapture.files.any { it.path == "capture/session.json" })
        assertTrue(s.deleteCapture(p.id) > 0)
        assertFalse(s.load(p.id).capture?.retained ?: false)
    }
}
