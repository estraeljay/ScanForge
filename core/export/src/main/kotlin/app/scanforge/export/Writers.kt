package app.scanforge.export

import app.scanforge.geometry.Mesh
import java.io.BufferedWriter
import java.io.File
import java.io.OutputStream
import java.io.OutputStreamWriter
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale
import kotlin.math.pow

object PlyWriter {
    fun write(m: Mesh, out: OutputStream, includeColors: Boolean, comments: List<String> = emptyList()) {
        val colors = includeColors && m.hasColors
        val header = buildString {
            append("ply\nformat binary_little_endian 1.0\n")
            comments.forEach { append("comment ").append(it.replace('\n', ' ')).append('\n') }
            append("element vertex ${m.vertexCount}\n")
            append("property float x\nproperty float y\nproperty float z\n")
            if (colors) append("property uchar red\nproperty uchar green\nproperty uchar blue\n")
            append("element face ${m.triangleCount}\nproperty list uchar int vertex_indices\nend_header\n")
        }
        out.write(header.toByteArray(Charsets.US_ASCII))
        val vstride = 12 + if (colors) 3 else 0
        val buf = ByteBuffer.allocate(maxOf(vstride, 13) * 4096).order(ByteOrder.LITTLE_ENDIAN)
        fun flushIfNeeded(need: Int) { if (buf.remaining() < need) { out.write(buf.array(), 0, buf.position()); buf.clear() } }
        for (v in 0 until m.vertexCount) {
            flushIfNeeded(vstride)
            buf.putFloat(m.positions[v * 3]); buf.putFloat(m.positions[v * 3 + 1]); buf.putFloat(m.positions[v * 3 + 2])
            if (colors) { val c = m.colors!!; buf.put(c[v * 3]); buf.put(c[v * 3 + 1]); buf.put(c[v * 3 + 2]) }
        }
        for (t in 0 until m.triangleCount) {
            flushIfNeeded(13)
            buf.put(3); buf.putInt(m.indices[t * 3]); buf.putInt(m.indices[t * 3 + 1]); buf.putInt(m.indices[t * 3 + 2])
        }
        out.write(buf.array(), 0, buf.position())
    }
}

object StlWriter {
    fun write(m: Mesh, out: OutputStream, headerText: String = "ScanForge 3D") {
        val header = ByteArray(80)
        headerText.toByteArray(Charsets.US_ASCII).copyInto(header, endIndex = minOf(80, headerText.length))
        // Binary STL headers must not start with "solid" or some readers treat the file as ASCII.
        if (headerText.startsWith("solid", ignoreCase = true)) header[0] = '_'.code.toByte()
        out.write(header)
        val buf = ByteBuffer.allocate(50 * 1024).order(ByteOrder.LITTLE_ENDIAN)
        buf.putInt(m.triangleCount); out.write(buf.array(), 0, 4); buf.clear()
        for (t in 0 until m.triangleCount) {
            if (buf.remaining() < 50) { out.write(buf.array(), 0, buf.position()); buf.clear() }
            val (a, b, c) = m.triangle(t)
            val n = ((m.vertex(b) - m.vertex(a)) cross (m.vertex(c) - m.vertex(a))).let { if (it.length() > 0) it.normalized() else it }
            buf.putFloat(n.x.toFloat()); buf.putFloat(n.y.toFloat()); buf.putFloat(n.z.toFloat())
            for (v in intArrayOf(a, b, c)) { buf.putFloat(m.positions[v * 3]); buf.putFloat(m.positions[v * 3 + 1]); buf.putFloat(m.positions[v * 3 + 2]) }
            buf.putShort(0)
        }
        out.write(buf.array(), 0, buf.position())
    }
}

object ObjWriter {
    /** Writes `<name>.obj` and `<name>.mtl` into [dir]. Returns the OBJ file. */
    fun write(m: Mesh, dir: File, name: String, includeColors: Boolean, comments: List<String> = emptyList()): File {
        val colors = includeColors && m.hasColors
        val mtl = File(dir, "$name.mtl")
        mtl.writeText("# ScanForge 3D material\nnewmtl scan_material\nKd 0.8 0.8 0.8\nKa 0 0 0\nKs 0 0 0\nd 1.0\nillum 1\n")
        val obj = File(dir, "$name.obj")
        BufferedWriter(OutputStreamWriter(obj.outputStream(), Charsets.UTF_8), 1 shl 16).use { w ->
            comments.forEach { w.write("# ${it.replace('\n', ' ')}\n") }
            w.write("mtllib ${mtl.name}\no $name\n")
            for (v in 0 until m.vertexCount) {
                val x = m.positions[v * 3]; val y = m.positions[v * 3 + 1]; val z = m.positions[v * 3 + 2]
                if (colors) {
                    val c = m.colors!!
                    w.write(String.format(Locale.ROOT, "v %.7g %.7g %.7g %.4f %.4f %.4f\n", x, y, z,
                        (c[v * 3].toInt() and 0xff) / 255.0, (c[v * 3 + 1].toInt() and 0xff) / 255.0, (c[v * 3 + 2].toInt() and 0xff) / 255.0))
                } else {
                    w.write(String.format(Locale.ROOT, "v %.7g %.7g %.7g\n", x, y, z))
                }
            }
            w.write("usemtl scan_material\n")
            for (t in 0 until m.triangleCount) {
                w.write("f ${m.indices[t * 3] + 1} ${m.indices[t * 3 + 1] + 1} ${m.indices[t * 3 + 2] + 1}\n")
            }
        }
        return obj
    }
}

/**
 * Minimal glTF 2.0 binary writer: one mesh, POSITION + NORMAL (+ COLOR_0), uint32 indices,
 * a single unlit-friendly PBR material. glTF units are meters and +Y is up by specification.
 */
object GlbWriter {
    /** glTF vertex colors are linear; scan colors are sRGB. */
    fun srgbToLinear(c: Int): Double { val s = c / 255.0; return if (s <= 0.04045) s / 12.92 else ((s + 0.055) / 1.055).pow(2.4) }

    fun write(m: Mesh, out: OutputStream, includeColors: Boolean, generator: String = "ScanForge 3D", extras: Map<String, String> = emptyMap()) {
        val colors = includeColors && m.hasColors
        val normals = m.vertexNormals()
        val v = m.vertexCount
        val posBytes = v * 12; val nrmBytes = v * 12; val colBytes = if (colors) v * 4 else 0; val idxBytes = m.triangleCount * 3 * 4
        val bin = ByteBuffer.allocate(posBytes + nrmBytes + colBytes + idxBytes).order(ByteOrder.LITTLE_ENDIAN)
        val b = m.bounds() ?: app.scanforge.geometry.Aabb(app.scanforge.geometry.Vec3.ZERO, app.scanforge.geometry.Vec3.ZERO)
        for (f in m.positions) bin.putFloat(f)
        for (f in normals) bin.putFloat(f)
        if (colors) for (i in 0 until v) {
            for (c in 0 until 3) bin.put(Math.round(srgbToLinear(m.colors!![i * 3 + c].toInt() and 0xff) * 255).toInt().toByte())
            bin.put(255.toByte())
        }
        for (i in m.indices) bin.putInt(i)
        fun f(x: Double) = String.format(Locale.ROOT, "%.9g", x.toFloat().toDouble())
        val extrasJson = extras.entries.joinToString(",") { (k, value) -> "${jsonString(k)}:${jsonString(value)}" }
        var offset = 0
        val views = mutableListOf<String>()
        fun view(len: Int, target: Int): Int { views += """{"buffer":0,"byteOffset":$offset,"byteLength":$len,"target":$target}"""; offset += len; return views.size - 1 }
        val vPos = view(posBytes, 34962); val vNrm = view(nrmBytes, 34962)
        val vCol = if (colors) view(colBytes, 34962) else -1
        val vIdx = view(idxBytes, 34963)
        val accessors = mutableListOf(
            """{"bufferView":$vPos,"componentType":5126,"count":$v,"type":"VEC3","min":[${f(b.min.x)},${f(b.min.y)},${f(b.min.z)}],"max":[${f(b.max.x)},${f(b.max.y)},${f(b.max.z)}]}""",
            """{"bufferView":$vNrm,"componentType":5126,"count":$v,"type":"VEC3"}""",
        )
        val attrs = mutableListOf(""""POSITION":0""", """"NORMAL":1""")
        if (colors) { accessors += """{"bufferView":$vCol,"componentType":5121,"normalized":true,"count":$v,"type":"VEC4"}"""; attrs += """"COLOR_0":${accessors.size - 1}""" }
        accessors += """{"bufferView":$vIdx,"componentType":5125,"count":${m.indices.size},"type":"SCALAR"}"""
        val idxAccessor = accessors.size - 1
        val json = """{"asset":{"version":"2.0","generator":${jsonString(generator)}},"scene":0,"scenes":[{"nodes":[0]}],""" +
            """"nodes":[{"mesh":0,"name":"scan"${if (extrasJson.isNotEmpty()) ""","extras":{$extrasJson}""" else ""}}],""" +
            """"meshes":[{"name":"scan","primitives":[{"attributes":{${attrs.joinToString(",")}},"indices":$idxAccessor,"material":0,"mode":4}]}],""" +
            """"materials":[{"name":"scan_material","pbrMetallicRoughness":{"baseColorFactor":[1,1,1,1],"metallicFactor":0,"roughnessFactor":0.9},"doubleSided":false}],""" +
            """"accessors":[${accessors.joinToString(",")}],"bufferViews":[${views.joinToString(",")}],"buffers":[{"byteLength":${bin.capacity()}}]}"""
        var jsonBytes = json.toByteArray(Charsets.UTF_8)
        val jsonPad = (4 - jsonBytes.size % 4) % 4
        jsonBytes += ByteArray(jsonPad) { ' '.code.toByte() }
        val binPad = (4 - bin.capacity() % 4) % 4
        val total = 12 + 8 + jsonBytes.size + 8 + bin.capacity() + binPad
        val head = ByteBuffer.allocate(28).order(ByteOrder.LITTLE_ENDIAN)
        head.putInt(0x46546C67); head.putInt(2); head.putInt(total)
        head.putInt(jsonBytes.size); head.putInt(0x4E4F534A)
        out.write(head.array(), 0, 20)
        out.write(jsonBytes)
        val binHead = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putInt(bin.capacity() + binPad).putInt(0x004E4942)
        out.write(binHead.array())
        out.write(bin.array())
        out.write(ByteArray(binPad))
    }

    fun jsonString(s: String): String = buildString {
        append('"')
        for (ch in s) when {
            ch == '"' -> append("\\\""); ch == '\\' -> append("\\\\")
            ch < ' ' -> append(String.format(Locale.ROOT, "\\u%04x", ch.code))
            else -> append(ch)
        }
        append('"')
    }
}
