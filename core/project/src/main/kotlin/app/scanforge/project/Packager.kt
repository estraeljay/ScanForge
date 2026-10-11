package app.scanforge.project

import app.scanforge.geometry.LengthUnit
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

const val PACKAGE_FORMAT_ID = "scanforge.package"
const val PACKAGE_FORMAT_VERSION = 1
const val PACKAGE_EXTENSION = "sf3d"

@Serializable
data class PackageManifest(
    val format: String = PACKAGE_FORMAT_ID,
    @SerialName("format_version") val formatVersion: Int = PACKAGE_FORMAT_VERSION,
    @SerialName("project_id") val projectId: String,
    @SerialName("project_name") val projectName: String,
    @SerialName("created_at") val createdAt: String,
    /** Path of the mesh to import by default (the current model version). */
    @SerialName("primary_model") val primaryModel: String,
    @SerialName("includes_capture") val includesCapture: Boolean,
    val files: List<FileEntry>,
)

class PackageValidationException(message: String) : Exception(message)

/**
 * Native project package (`.sf3d`): a ZIP archive containing
 * ```
 * package.json                 PackageManifest with SHA-256 of every file
 * project.json                 ProjectDocument
 * model/vNNN.ply               current model version (+ source v001 when different)
 * model/evidence.bin           optional
 * reports/measurements.json    calibrated measurement report (meters)
 * reports/measurements.csv
 * capture/…                    only when requested (large)
 * ```
 * It is self-contained: Blender can import it without the Android app.
 */
object ProjectPackager {

    fun pack(store: ProjectStore, id: String, out: File, includeCapture: Boolean = false): PackageManifest {
        val doc = store.load(id)
        val current = doc.model.current ?: throw IllegalStateException("Project has no reconstructed model to package")
        val dir = store.dir(id)
        val paths = linkedSetOf(current.file.path)
        doc.model.source?.let { paths += it.file.path }
        doc.model.evidenceFile?.let { if (File(dir, it).isFile) paths += it }
        if (includeCapture && doc.capture?.retained != false) {
            store.captureDir(id).walkTopDown().filter { it.isFile }.forEach { paths += it.relativeTo(dir).invariantSeparatorsPath }
        }
        // Generated content written fresh into the archive.
        val generated = linkedMapOf(
            "project.json" to ProjectJson.encodeToString(ProjectDocument.serializer(), doc).toByteArray(),
            "reports/measurements.json" to ProjectJson.encodeToString(kotlinx.serialization.json.JsonObject.serializer(), MeasurementReport.json(doc, LengthUnit.METER)).toByteArray(),
            "reports/measurements.csv" to MeasurementReport.csv(doc, LengthUnit.METER).toByteArray(),
        )
        val entries = ArrayList<FileEntry>()
        generated.forEach { (p, bytes) -> entries += FileEntry(roleOf(p), p, p.substringAfterLast('.'), bytes.size.toLong(), Hashing.sha256(bytes)) }
        paths.forEach { p -> val f = File(dir, p); entries += FileEntry(roleOf(p), p, p.substringAfterLast('.'), f.length(), Hashing.sha256(f)) }
        val manifest = PackageManifest(
            projectId = doc.id, projectName = doc.name, createdAt = store.now(),
            primaryModel = current.file.path, includesCapture = includeCapture, files = entries,
        )
        out.parentFile?.mkdirs()
        val tmp = File(out.parentFile, out.name + ".tmp")
        ZipOutputStream(FileOutputStream(tmp).buffered()).use { zip ->
            fun put(name: String, bytes: ByteArray) { zip.putNextEntry(ZipEntry(name)); zip.write(bytes); zip.closeEntry() }
            put("package.json", ProjectJson.encodeToString(PackageManifest.serializer(), manifest).toByteArray())
            generated.forEach { (p, bytes) -> put(p, bytes) }
            paths.forEach { p ->
                zip.putNextEntry(ZipEntry(p))
                File(dir, p).inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
            }
        }
        if (!tmp.renameTo(out)) { out.delete(); check(tmp.renameTo(out)) }
        return manifest
    }

    private fun roleOf(path: String) = when {
        path == "project.json" -> "project"
        path.startsWith("model/") && path.endsWith(".ply") -> "mesh"
        path.startsWith("model/") -> "model_data"
        path.startsWith("reports/") -> "report"
        path.startsWith("capture/") -> "capture"
        else -> "other"
    }

    /** Reads and fully verifies a package (manifest, paths, sizes, hashes) without extracting it. */
    fun verify(file: File): PackageManifest = ZipFile(file).use { zip ->
        val manifestEntry = zip.getEntry("package.json") ?: throw PackageValidationException("package.json missing")
        val manifest = runCatching {
            ProjectJson.decodeFromString(PackageManifest.serializer(), zip.getInputStream(manifestEntry).readBytes().decodeToString())
        }.getOrElse { throw PackageValidationException("package.json unreadable: ${it.message}") }
        if (manifest.format != PACKAGE_FORMAT_ID) throw PackageValidationException("Not a ScanForge package")
        if (manifest.formatVersion > PACKAGE_FORMAT_VERSION) throw PackageValidationException("Package format ${manifest.formatVersion} is newer than supported")
        for (e in manifest.files) {
            checkSafePath(e.path)
            val ze = zip.getEntry(e.path) ?: throw PackageValidationException("Missing file ${e.path}")
            val bytes = zip.getInputStream(ze).readBytes()
            if (bytes.size.toLong() != e.bytes) throw PackageValidationException("Size mismatch for ${e.path}")
            if (Hashing.sha256(bytes) != e.sha256) throw PackageValidationException("Checksum mismatch for ${e.path}")
        }
        if (manifest.files.none { it.path == manifest.primaryModel }) throw PackageValidationException("Primary model not listed")
        manifest
    }

    /** Imports a verified package as a new project (new id, so existing projects are never overwritten). */
    fun unpack(file: File, store: ProjectStore): ProjectDocument {
        val manifest = verify(file)
        val newId = java.util.UUID.randomUUID().toString()
        val dir = store.dir(newId)
        ZipFile(file).use { zip ->
            for (e in manifest.files) {
                val target = File(dir, e.path)
                target.parentFile.mkdirs()
                zip.getInputStream(zip.getEntry(e.path)).use { input -> target.outputStream().use { input.copyTo(it) } }
            }
        }
        val doc = store.load(newId).copy(id = newId)
        return store.save(doc.copy(name = doc.name, transfers = emptyList()))
    }

    /** Rejects absolute paths and parent traversal ("zip slip"). */
    fun checkSafePath(path: String) {
        if (path.isEmpty() || path.startsWith("/") || path.startsWith("\\") || path.contains("..") || path.contains(':') || path.contains('\\')) {
            throw PackageValidationException("Unsafe path in package: $path")
        }
    }
}
