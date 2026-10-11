package app.scanforge.export

import app.scanforge.geometry.LengthUnit
import app.scanforge.geometry.Mesh
import app.scanforge.geometry.Vec3
import java.nio.file.Files
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ExportTest {
    // Tetrahedron with a known extent: x 0..0.1, y 0..0.2 (up), z 0..0.05.
    private val mesh = Mesh.fromVertices(
        listOf(Vec3(0.0, 0.0, 0.0), Vec3(0.1, 0.0, 0.0), Vec3(0.0, 0.2, 0.0), Vec3(0.0, 0.0, 0.05)),
        intArrayOf(0, 2, 1, 0, 1, 3, 0, 3, 2, 1, 2, 3),
        byteArrayOf(-1, 0, 0, 0, -1, 0, 0, 0, -1, 10, 20, 30),
    )

    @Test fun everyFormatRoundTripsAndValidates() {
        val dir = Files.createTempDirectory("exp").toFile()
        for (format in ExportFormat.entries) {
            val r = MeshExporter.export(mesh, dir, "scan_${format.extension}", ExportOptions(format))
            assertTrue(r.validation.ok, "$format: ${r.validation.problems}")
            assertEquals(4, r.triangleCount)
            val back = MeshReaders.read(r.primaryFile, format)
            val b = back.bounds()!!
            assertTrue(abs(b.size.y - 0.2) < 1e-6, "$format height ${b.size.y}")
            val estimate = estimateExportBytes(mesh, ExportOptions(format))
            when (format) {
                ExportFormat.STL -> assertEquals(estimate, r.primaryFile.length(), "STL size estimate is exact")
                ExportFormat.PLY, ExportFormat.GLB -> assertTrue(abs(estimate - r.primaryFile.length()) < 1024, "$format estimate $estimate vs ${r.primaryFile.length()}")
                ExportFormat.OBJ -> assertTrue(r.primaryFile.length() in estimate / 3..estimate * 3, "OBJ estimate")
            }
        }
        assertTrue(java.io.File(dir, "scan_obj.mtl").isFile)
    }

    @Test fun zUpAndMillimeterConversion() {
        val dir = Files.createTempDirectory("exp").toFile()
        val r = MeshExporter.export(mesh, dir, "zup", ExportOptions(ExportFormat.PLY, AxisConvention.Z_UP, LengthUnit.MILLIMETER))
        val b = MeshReaders.read(r.primaryFile, ExportFormat.PLY).bounds()!!
        // Scan +Y (height 0.2 m) becomes file +Z = 200 mm; scan +Z (0.05 m) becomes file -Y.
        assertTrue(abs(b.max.z - 200.0) < 1e-3, "z ${b.max.z}")
        assertTrue(abs(b.min.y + 50.0) < 1e-3, "y ${b.min.y}")
        assertTrue(abs(b.max.x - 100.0) < 1e-3)
        // GLB always uses meters and +Y up regardless of requested options.
        val g = MeshExporter.export(mesh, dir, "g", ExportOptions(ExportFormat.GLB, AxisConvention.Z_UP, LengthUnit.MILLIMETER))
        assertTrue(abs(MeshReaders.read(g.primaryFile, ExportFormat.GLB).bounds()!!.size.y - 0.2) < 1e-6)
    }

    @Test fun glbIsWellFormed() {
        val dir = Files.createTempDirectory("exp").toFile()
        val f = MeshExporter.export(mesh, dir, "g", ExportOptions(ExportFormat.GLB)).primaryFile
        val bytes = f.readBytes()
        assertEquals(0, bytes.size % 4)
        val json = String(bytes, 20, java.nio.ByteBuffer.wrap(bytes, 12, 4).order(java.nio.ByteOrder.LITTLE_ENDIAN).int)
        assertTrue(json.contains("\"COLOR_0\"") && json.contains("\"version\":\"2.0\""))
        assertEquals(0.0, GlbWriter.srgbToLinear(0)); assertEquals(1.0, GlbWriter.srgbToLinear(255), 1e-12)
    }
}
