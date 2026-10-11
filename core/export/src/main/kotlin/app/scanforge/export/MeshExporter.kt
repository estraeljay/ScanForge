package app.scanforge.export

import app.scanforge.geometry.Mesh
import app.scanforge.geometry.MeshOps
import java.io.File
import kotlin.math.abs

data class ExportResult(
    val files: List<File>,
    val primaryFile: File,
    val bytes: Long,
    val vertexCount: Int,
    val triangleCount: Int,
    val validation: ExportValidation,
)

data class ExportValidation(val ok: Boolean, val problems: List<String>)

/**
 * Exports a scan mesh with explicit axis/unit conversion, then validates the written file by reading
 * it back and comparing counts and bounds.
 */
object MeshExporter {
    fun export(
        mesh: Mesh,
        dir: File,
        baseName: String,
        options: ExportOptions,
        comments: List<String> = emptyList(),
        onProgress: (String) -> Unit = {},
    ): ExportResult {
        dir.mkdirs()
        var m = mesh
        options.targetTriangles?.let { target -> onProgress("Simplifying"); m = MeshOps.simplifyToTarget(m, target) }
        m = CoordinateConversion.convert(m, options.effectiveAxis, options.effectiveUnit)
        val colors = options.includeColors && options.format.supportsColor
        val meta = comments + listOf("units ${options.effectiveUnit.symbol}", "up_axis ${if (options.effectiveAxis == AxisConvention.Y_UP) "+Y" else "+Z"}")
        onProgress("Writing ${options.format.extension.uppercase()}")
        val files = mutableListOf<File>()
        val primary = when (options.format) {
            ExportFormat.PLY -> File(dir, "$baseName.ply").also { f -> f.outputStream().buffered().use { PlyWriter.write(m, it, colors, meta) } }
            ExportFormat.STL -> File(dir, "$baseName.stl").also { f -> f.outputStream().buffered().use { StlWriter.write(m, it, "ScanForge 3D ${options.effectiveUnit.symbol}") } }
            ExportFormat.OBJ -> ObjWriter.write(m, dir, baseName, colors, meta).also { files += File(dir, "$baseName.mtl") }
            ExportFormat.GLB -> File(dir, "$baseName.glb").also { f ->
                f.outputStream().buffered().use { GlbWriter.write(m, it, colors, extras = mapOf("units" to "m", "source" to "ScanForge 3D")) }
            }
        }
        files.add(0, primary)
        onProgress("Validating")
        val validation = validate(primary, options.format, m)
        return ExportResult(files, primary, files.sumOf { it.length() }, m.vertexCount, m.triangleCount, validation)
    }

    fun validate(file: File, format: ExportFormat, expected: Mesh): ExportValidation {
        val problems = mutableListOf<String>()
        val read = runCatching { MeshReaders.read(file, format) }.getOrElse { return ExportValidation(false, listOf("Could not read back file: ${it.message}")) }
        if (read.triangleCount != expected.triangleCount) problems += "Triangle count ${read.triangleCount} != ${expected.triangleCount}"
        if (format != ExportFormat.STL && read.vertexCount != expected.vertexCount) problems += "Vertex count ${read.vertexCount} != ${expected.vertexCount}"
        val a = read.bounds(); val b = expected.bounds()
        if (a != null && b != null) {
            val tol = 1e-4 * maxOf(1.0, b.size.length())
            if (abs(a.min.x - b.min.x) > tol || abs(a.max.y - b.max.y) > tol || abs(a.max.z - b.max.z) > tol) problems += "Bounds differ after read-back"
        }
        return ExportValidation(problems.isEmpty(), problems)
    }
}
