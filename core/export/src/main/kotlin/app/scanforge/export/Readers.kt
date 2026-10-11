package app.scanforge.export

import app.scanforge.geometry.Mesh
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Minimal readers for the subsets ScanForge writes. They exist to validate every export by reading it
 * back (vertex/triangle counts and bounds), and to re-import exported scans in tests.
 */
object MeshReaders {
    fun read(file: File, format: ExportFormat): Mesh = when (format) {
        ExportFormat.PLY -> readPly(file.readBytes())
        ExportFormat.STL -> readStl(file.readBytes())
        ExportFormat.OBJ -> readObj(file)
        ExportFormat.GLB -> readGlb(file.readBytes())
    }

    fun readPly(bytes: ByteArray): Mesh {
        val end = String(bytes, 0, minOf(bytes.size, 4096), Charsets.US_ASCII).indexOf("end_header\n")
        require(end > 0) { "PLY header not found" }
        val header = String(bytes, 0, end, Charsets.US_ASCII).lines()
        require(header[0] == "ply" && header[1] == "format binary_little_endian 1.0") { "Unsupported PLY variant" }
        val nv = header.first { it.startsWith("element vertex") }.split(" ")[2].toInt()
        val nf = header.first { it.startsWith("element face") }.split(" ")[2].toInt()
        val colors = header.any { it == "property uchar red" }
        val buf = ByteBuffer.wrap(bytes, end + "end_header\n".length, bytes.size - end - 11).order(ByteOrder.LITTLE_ENDIAN)
        val pos = FloatArray(nv * 3); val col = if (colors) ByteArray(nv * 3) else null
        for (v in 0 until nv) {
            pos[v * 3] = buf.float; pos[v * 3 + 1] = buf.float; pos[v * 3 + 2] = buf.float
            if (col != null) { col[v * 3] = buf.get(); col[v * 3 + 1] = buf.get(); col[v * 3 + 2] = buf.get() }
        }
        val idx = IntArray(nf * 3)
        for (f in 0 until nf) {
            require(buf.get().toInt() == 3) { "Only triangles supported" }
            idx[f * 3] = buf.int; idx[f * 3 + 1] = buf.int; idx[f * 3 + 2] = buf.int
        }
        return Mesh(pos, idx, col)
    }

    /** Binary STL; vertices are not shared (3 per facet), exactly as stored. */
    fun readStl(bytes: ByteArray): Mesh {
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        buf.position(80)
        val n = buf.int
        require(bytes.size.toLong() == 84L + 50L * n) { "STL size does not match triangle count" }
        val pos = FloatArray(n * 9)
        for (t in 0 until n) {
            buf.position(buf.position() + 12)
            for (k in 0 until 9) pos[t * 9 + k] = buf.float
            buf.short
        }
        return Mesh(pos, IntArray(n * 3) { it })
    }

    fun readObj(file: File): Mesh {
        val pos = ArrayList<Float>(); val col = ArrayList<Byte>(); val idx = ArrayList<Int>()
        file.forEachLine { line ->
            val p = line.trim().split(Regex("\\s+"))
            when (p[0]) {
                "v" -> {
                    pos += p[1].toFloat(); pos += p[2].toFloat(); pos += p[3].toFloat()
                    if (p.size >= 7) for (k in 4..6) col += Math.round(p[k].toDouble() * 255).toInt().toByte()
                }
                "f" -> {
                    val f = p.drop(1).map { it.substringBefore('/').toInt() - 1 }
                    for (k in 1 until f.size - 1) { idx += f[0]; idx += f[k]; idx += f[k + 1] }
                }
            }
        }
        return Mesh(pos.toFloatArray(), idx.toIntArray(), if (col.size == pos.size) col.toByteArray() else null)
    }

    fun readGlb(bytes: ByteArray): Mesh {
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        require(buf.int == 0x46546C67 && buf.int == 2) { "Not a glTF 2.0 binary" }
        require(buf.int == bytes.size) { "GLB length mismatch" }
        val jsonLen = buf.int; require(buf.int == 0x4E4F534A)
        val json = String(bytes, 20, jsonLen, Charsets.UTF_8)
        buf.position(20 + jsonLen)
        val binLen = buf.int; require(buf.int == 0x004E4942)
        val binStart = buf.position()
        require(binStart + binLen <= bytes.size)
        // Our own writer's layout: views 0=POSITION, 1=NORMAL, [2=COLOR_0], last=indices.
        val views = Regex("\\{\"buffer\":0,\"byteOffset\":(\\d+),\"byteLength\":(\\d+)").findAll(json).map { it.groupValues[1].toInt() to it.groupValues[2].toInt() }.toList()
        val pos = ByteBuffer.wrap(bytes, binStart + views[0].first, views[0].second).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().let { b -> FloatArray(b.remaining()).also { b.get(it) } }
        val idxView = views.last()
        val idx = ByteBuffer.wrap(bytes, binStart + idxView.first, idxView.second).order(ByteOrder.LITTLE_ENDIAN).asIntBuffer().let { b -> IntArray(b.remaining()).also { b.get(it) } }
        return Mesh(pos, idx)
    }
}
