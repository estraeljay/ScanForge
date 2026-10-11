package app.scanforge.geometry

import kotlin.math.sqrt

/**
 * Indexed triangle mesh. Positions are meters in the ScanForge scan frame
 * (right-handed, +Y up, see docs/spec/coordinate-systems.md).
 *
 * Meshes are treated as immutable values: every editing operation returns a new mesh so
 * the original scan can always be preserved for undo and provenance.
 */
class Mesh(
    val positions: FloatArray,
    val indices: IntArray,
    /** Optional per-vertex sRGB colors, 3 bytes per vertex. */
    val colors: ByteArray? = null,
) {
    init {
        require(positions.size % 3 == 0) { "positions must be xyz triplets" }
        require(indices.size % 3 == 0) { "indices must be triangles" }
        require(colors == null || colors.size == positions.size) { "colors must be rgb per vertex" }
        val n = vertexCount
        for (i in indices) require(i in 0 until n) { "index $i out of range (vertexCount=$n)" }
    }

    val vertexCount get() = positions.size / 3
    val triangleCount get() = indices.size / 3
    val hasColors get() = colors != null

    fun vertex(i: Int) = Vec3(positions[i * 3].toDouble(), positions[i * 3 + 1].toDouble(), positions[i * 3 + 2].toDouble())

    fun triangle(t: Int) = Triple(indices[t * 3], indices[t * 3 + 1], indices[t * 3 + 2])

    fun bounds(): Aabb? {
        if (vertexCount == 0) return null
        var minX = Double.POSITIVE_INFINITY; var minY = minX; var minZ = minX
        var maxX = Double.NEGATIVE_INFINITY; var maxY = maxX; var maxZ = maxX
        for (i in 0 until vertexCount) {
            val x = positions[i * 3].toDouble(); val y = positions[i * 3 + 1].toDouble(); val z = positions[i * 3 + 2].toDouble()
            if (x < minX) minX = x; if (y < minY) minY = y; if (z < minZ) minZ = z
            if (x > maxX) maxX = x; if (y > maxY) maxY = y; if (z > maxZ) maxZ = z
        }
        return Aabb(Vec3(minX, minY, minZ), Vec3(maxX, maxY, maxZ))
    }

    /** Area-weighted per-vertex normals. Degenerate vertices get +Y. */
    fun vertexNormals(): FloatArray {
        val acc = DoubleArray(positions.size)
        for (t in 0 until triangleCount) {
            val (a, b, c) = triangle(t)
            val n = (vertex(b) - vertex(a)) cross (vertex(c) - vertex(a))
            for (v in intArrayOf(a, b, c)) {
                acc[v * 3] += n.x; acc[v * 3 + 1] += n.y; acc[v * 3 + 2] += n.z
            }
        }
        val out = FloatArray(positions.size)
        for (v in 0 until vertexCount) {
            val x = acc[v * 3]; val y = acc[v * 3 + 1]; val z = acc[v * 3 + 2]
            val l = sqrt(x * x + y * y + z * z)
            if (l > 1e-20) {
                out[v * 3] = (x / l).toFloat(); out[v * 3 + 1] = (y / l).toFloat(); out[v * 3 + 2] = (z / l).toFloat()
            } else {
                out[v * 3 + 1] = 1f
            }
        }
        return out
    }

    /** Approximate in-memory / raw payload size in bytes. */
    fun payloadBytes(): Long = positions.size * 4L + indices.size * 4L + (colors?.size ?: 0)

    companion object {
        val EMPTY = Mesh(FloatArray(0), IntArray(0))

        fun fromVertices(vertices: List<Vec3>, triangles: IntArray, colors: ByteArray? = null): Mesh {
            val p = FloatArray(vertices.size * 3)
            vertices.forEachIndexed { i, v -> p[i * 3] = v.x.toFloat(); p[i * 3 + 1] = v.y.toFloat(); p[i * 3 + 2] = v.z.toFloat() }
            return Mesh(p, triangles, colors)
        }
    }
}
