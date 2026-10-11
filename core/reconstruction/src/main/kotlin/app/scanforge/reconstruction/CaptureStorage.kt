package app.scanforge.reconstruction

import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileOutputStream

val CaptureJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    explicitNulls = false
    prettyPrint = false
}

/** Read access to a capture dataset, from disk or memory. */
interface CaptureSource {
    val session: CaptureSession
    val frames: List<FrameRecord>
    fun loadDepth(frame: FrameRecord): DepthFrame?
    fun loadColor(frame: FrameRecord): RgbImage?
}

/**
 * Capture directory layout:
 * ```
 * capture/
 *   session.json        CaptureSession
 *   frames.jsonl        one FrameRecord per line (append-only, crash tolerant)
 *   depth/000123.sfd    SFD1 depth + confidence
 *   color/000123.jpg    JPEG keyframe
 * ```
 */
class DirectoryCaptureSource(private val dir: File, private val decoder: ImageDecoder?) : CaptureSource {
    override val session: CaptureSession = CaptureJson.decodeFromString(File(dir, SESSION_FILE).readText())
    override val frames: List<FrameRecord> = readFrames(dir)

    override fun loadDepth(frame: FrameRecord): DepthFrame? =
        frame.depthFile?.let { f -> File(dir, f).takeIf { it.isFile }?.inputStream()?.buffered()?.use(DepthFrameCodec::read) }

    override fun loadColor(frame: FrameRecord): RgbImage? {
        val d = decoder ?: return null
        return frame.colorFile?.let { f -> File(dir, f).takeIf { it.isFile }?.readBytes()?.let(d::decode) }
    }

    companion object {
        const val SESSION_FILE = "session.json"
        const val FRAMES_FILE = "frames.jsonl"

        /** Reads frame records, ignoring a truncated final line left by a crash. */
        fun readFrames(dir: File): List<FrameRecord> {
            val f = File(dir, FRAMES_FILE)
            if (!f.isFile) return emptyList()
            return f.readLines().mapNotNull { line ->
                if (line.isBlank()) null else runCatching { CaptureJson.decodeFromString<FrameRecord>(line) }.getOrNull()
            }
        }
    }
}

/**
 * Writes a capture dataset incrementally. Every frame's files are written before its record is appended,
 * so after a crash the dataset is always consistent up to the last complete line.
 */
class CaptureWriter(val dir: File, session: CaptureSession) : AutoCloseable {
    private val framesFile = File(dir, DirectoryCaptureSource.FRAMES_FILE)
    private var out: FileOutputStream

    var frameCount: Int = 0
        private set
    var bytesWritten: Long = 0
        private set

    init {
        File(dir, "depth").mkdirs(); File(dir, "color").mkdirs()
        val sessionFile = File(dir, DirectoryCaptureSource.SESSION_FILE)
        if (!sessionFile.exists()) writeAtomically(sessionFile, CaptureJson.encodeToString(CaptureSession.serializer(), session).toByteArray())
        val existing = DirectoryCaptureSource.readFrames(dir)
        frameCount = existing.size
        // Rewrite to drop any truncated tail before appending (resume after crash).
        writeAtomically(framesFile, existing.joinToString("") { CaptureJson.encodeToString(FrameRecord.serializer(), it) + "\n" }.toByteArray())
        out = FileOutputStream(framesFile, true)
    }

    fun nextIndex() = frameCount

    /** Persists one frame. [jpeg] is the platform-encoded color keyframe. */
    fun append(record: FrameRecord, depth: DepthFrame?, jpeg: ByteArray?): FrameRecord {
        require(record.index == frameCount) { "Frame index ${record.index} != expected $frameCount" }
        val name = "%06d".format(record.index)
        var r = record
        if (depth != null) {
            val f = File(dir, "depth/$name.sfd")
            val bytes = java.io.ByteArrayOutputStream().also { DepthFrameCodec.write(depth, it) }.toByteArray()
            writeAtomically(f, bytes); bytesWritten += bytes.size
            r = r.copy(depthFile = "depth/$name.sfd")
        }
        if (jpeg != null) {
            writeAtomically(File(dir, "color/$name.jpg"), jpeg); bytesWritten += jpeg.size
            r = r.copy(colorFile = "color/$name.jpg")
        }
        out.write((CaptureJson.encodeToString(FrameRecord.serializer(), r) + "\n").toByteArray())
        out.flush()
        out.fd.sync()
        frameCount++
        return r
    }

    /** Removes the most recent [count] frames ("undo recent capture"). */
    fun discardLast(count: Int) {
        val frames = DirectoryCaptureSource.readFrames(dir)
        val keep = frames.dropLast(count.coerceAtMost(frames.size))
        frames.drop(keep.size).forEach { f ->
            f.depthFile?.let { File(dir, it).delete() }; f.colorFile?.let { File(dir, it).delete() }
        }
        out.close()
        writeAtomically(framesFile, keep.joinToString("") { CaptureJson.encodeToString(FrameRecord.serializer(), it) + "\n" }.toByteArray())
        out = FileOutputStream(framesFile, true)
        frameCount = keep.size
    }

    override fun close() = out.close()

    companion object {
        fun writeAtomically(target: File, bytes: ByteArray) {
            val tmp = File(target.parentFile, target.name + ".tmp")
            FileOutputStream(tmp).use { it.write(bytes); it.fd.sync() }
            if (!tmp.renameTo(target)) {
                target.delete()
                check(tmp.renameTo(target)) { "Could not move ${tmp.path} to ${target.path}" }
            }
        }
    }
}

class InMemoryCaptureSource(
    override val session: CaptureSession,
    override val frames: List<FrameRecord>,
    private val depth: Map<Int, DepthFrame>,
    private val color: Map<Int, RgbImage>,
) : CaptureSource {
    override fun loadDepth(frame: FrameRecord) = depth[frame.index]
    override fun loadColor(frame: FrameRecord) = color[frame.index]
}
