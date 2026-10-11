package app.scanforge.export

import app.scanforge.geometry.LengthUnit
import app.scanforge.geometry.Mesh

enum class ExportFormat(val extension: String, val supportsColor: Boolean, val description: String) {
    PLY("ply", true, "Binary PLY mesh with vertex colors"),
    OBJ("obj", true, "Wavefront OBJ + MTL (vertex colors as OBJ color extension)"),
    STL("stl", false, "Binary STL, geometry only"),
    GLB("glb", true, "glTF 2.0 binary with vertex colors"),
}

/**
 * Target axis convention. Scan data is natively +Y up (same as glTF/ARCore).
 * [Z_UP] applies (x, y, z) → (x, −z, y), the same rotation Blender's glTF importer applies,
 * so a Z-up file opens upright in Blender/CAD tools without importer axis options.
 */
enum class AxisConvention { Y_UP, Z_UP }

data class ExportOptions(
    val format: ExportFormat,
    val axis: AxisConvention = AxisConvention.Y_UP,
    /** Unit that one file unit represents. glTF requires meters, so GLB ignores this. */
    val unit: LengthUnit = LengthUnit.METER,
    val includeColors: Boolean = true,
    /** Optional simplification target; null keeps full resolution. */
    val targetTriangles: Int? = null,
) {
    val effectiveUnit get() = if (format == ExportFormat.GLB) LengthUnit.METER else unit
    val effectiveAxis get() = if (format == ExportFormat.GLB) AxisConvention.Y_UP else axis
}

/** Applies axis and unit conversion to positions. Winding is preserved (the rotation is proper). */
object CoordinateConversion {
    fun convert(m: Mesh, axis: AxisConvention, unit: LengthUnit): Mesh {
        val s = 1.0 / unit.metersPerUnit
        val p = FloatArray(m.positions.size)
        for (i in 0 until m.vertexCount) {
            val x = m.positions[i * 3]; val y = m.positions[i * 3 + 1]; val z = m.positions[i * 3 + 2]
            when (axis) {
                AxisConvention.Y_UP -> { p[i * 3] = (x * s).toFloat(); p[i * 3 + 1] = (y * s).toFloat(); p[i * 3 + 2] = (z * s).toFloat() }
                AxisConvention.Z_UP -> { p[i * 3] = (x * s).toFloat(); p[i * 3 + 1] = (-z * s).toFloat(); p[i * 3 + 2] = (y * s).toFloat() }
            }
        }
        return Mesh(p, m.indices, m.colors)
    }
}

/** Estimated output size in bytes: exact for STL, within the header size (< 1 KB) for PLY/GLB, approximate for OBJ text. */
fun estimateExportBytes(m: Mesh, options: ExportOptions): Long {
    val v = m.vertexCount.toLong(); val t = m.triangleCount.toLong()
    val colors = options.includeColors && m.hasColors && options.format.supportsColor
    return when (options.format) {
        ExportFormat.STL -> 84 + 50 * t
        ExportFormat.PLY -> 300 + v * (12 + if (colors) 3 else 0) + t * 13
        ExportFormat.OBJ -> 120 + v * (if (colors) 60 else 36) + t * 24
        ExportFormat.GLB -> 2000 + v * (12 + 12 + if (colors) 4 else 0) + t * 12
    }
}
