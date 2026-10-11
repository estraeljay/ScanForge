package app.scanforge.project

import app.scanforge.export.MeshReaders
import app.scanforge.export.PlyWriter
import app.scanforge.geometry.Mesh
import app.scanforge.geometry.MeshAnalysis
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID

class ProjectNotFoundException(id: String) : Exception("Project $id not found")
class UnsupportedSchemaException(version: Int) :
    Exception("Project schema version $version is newer than this app supports ($PROJECT_SCHEMA_VERSION). Update ScanForge.")

/** Upgrades older project.json documents in place before decoding. */
object ProjectMigrations {
    fun migrate(json: kotlinx.serialization.json.JsonObject): kotlinx.serialization.json.JsonObject {
        val version = json["schema_version"]?.let { (it as kotlinx.serialization.json.JsonPrimitive).content.toInt() } ?: 1
        if (version > PROJECT_SCHEMA_VERSION) throw UnsupportedSchemaException(version)
        // No migrations yet: v1 is the first released schema.
        return json
    }
}

object Hashing {
    fun sha256(file: File): String = file.inputStream().use { input ->
        val md = MessageDigest.getInstance("SHA-256")
        val buf = ByteArray(1 shl 16)
        while (true) { val n = input.read(buf); if (n < 0) break; md.update(buf, 0, n) }
        md.digest().joinToString("") { "%02x".format(it) }
    }

    fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}

/**
 * File-based project storage:
 * ```
 * <root>/projects/<id>/
 *   project.json               ProjectDocument (atomic writes)
 *   capture/                   raw capture dataset (session.json, frames.jsonl, depth/, color/)
 *   model/v001.ply             untouched reconstruction (never modified)
 *   model/v002.ply …           edited versions (undo/redo = moving current_version)
 *   model/evidence.bin         per-vertex evidence classes of v001
 *   reconstruction/            checkpoints for interrupted reconstruction
 *   exports/ reports/ previews/
 * ```
 */
class ProjectStore(val root: File, private val clock: () -> Instant = Instant::now) {
    val projectsDir = File(root, "projects").apply { mkdirs() }

    fun now(): String = clock().toString()
    fun dir(id: String) = File(projectsDir, id)
    fun captureDir(id: String) = File(dir(id), "capture")
    fun checkpointDir(id: String) = File(dir(id), "reconstruction")
    private fun docFile(id: String) = File(dir(id), "project.json")

    fun create(name: String, category: ObjectCategory = ObjectCategory.OTHER, tags: List<String> = emptyList()): ProjectDocument {
        require(name.isNotBlank()) { "Project name must not be empty" }
        val id = UUID.randomUUID().toString()
        val t = now()
        val doc = ProjectDocument(id = id, name = name.trim(), category = category, tags = tags, createdAt = t, updatedAt = t)
        for (sub in listOf("capture", "model", "exports", "reports", "previews")) File(dir(id), sub).mkdirs()
        write(doc)
        return doc
    }

    fun exists(id: String) = docFile(id).isFile

    fun load(id: String): ProjectDocument {
        val f = docFile(id)
        if (!f.isFile) throw ProjectNotFoundException(id)
        val element = ProjectJson.parseToJsonElement(f.readText()) as kotlinx.serialization.json.JsonObject
        return ProjectJson.decodeFromJsonElement(ProjectDocument.serializer(), ProjectMigrations.migrate(element))
    }

    /** Lists projects, newest first. Unreadable projects are skipped rather than failing the whole list. */
    fun list(includeArchived: Boolean = false): List<ProjectDocument> =
        projectsDir.listFiles().orEmpty().filter { File(it, "project.json").isFile }
            .mapNotNull { runCatching { load(it.name) }.getOrNull() }
            .filter { includeArchived || !it.archived }
            .sortedByDescending { it.updatedAt }

    fun save(doc: ProjectDocument): ProjectDocument = doc.copy(updatedAt = now()).also { write(it) }

    fun update(id: String, change: (ProjectDocument) -> ProjectDocument): ProjectDocument = save(change(load(id)))

    private fun write(doc: ProjectDocument) {
        val f = docFile(doc.id)
        f.parentFile.mkdirs()
        val tmp = File(f.parentFile, "project.json.tmp")
        FileOutputStream(tmp).use { it.write(ProjectJson.encodeToString(ProjectDocument.serializer(), doc).toByteArray()); it.fd.sync() }
        if (!tmp.renameTo(f)) { f.delete(); check(tmp.renameTo(f)) { "Could not save project ${doc.id}" } }
    }

    fun rename(id: String, name: String) = update(id) { require(name.isNotBlank()); it.copy(name = name.trim()) }
    fun setArchived(id: String, archived: Boolean) = update(id) { it.copy(archived = archived) }

    fun duplicate(id: String, newName: String? = null): ProjectDocument {
        val src = load(id)
        val newId = UUID.randomUUID().toString()
        dir(id).copyRecursively(dir(newId))
        File(dir(newId), "reconstruction").deleteRecursively()
        val t = now()
        val doc = src.copy(id = newId, name = newName ?: "${src.name} (copy)", createdAt = t, updatedAt = t, transfers = emptyList())
        write(doc)
        return doc
    }

    /** Permanently deletes a project. Callers must obtain explicit user confirmation first. */
    fun delete(id: String) {
        val d = dir(id)
        if (!d.exists()) throw ProjectNotFoundException(id)
        val trash = File(root, ".trash/$id-${System.nanoTime()}")
        trash.parentFile.mkdirs()
        // Move first so a crash never leaves a half-deleted project visible in the library.
        if (!d.renameTo(trash)) d.deleteRecursively() else trash.deleteRecursively()
    }

    /** Deletes raw captures to free space. Re-reconstruction is impossible afterwards. */
    fun deleteCapture(id: String): Long {
        val c = captureDir(id)
        val freed = sizeOf(c)
        c.deleteRecursively(); c.mkdirs()
        File(dir(id), "reconstruction").deleteRecursively()
        update(id) { it.copy(capture = it.capture?.copy(retained = false)) }
        return freed
    }

    fun sizeOnDisk(id: String) = sizeOf(dir(id))
    fun sizeOf(f: File): Long = if (!f.exists()) 0 else f.walkBottomUp().filter { it.isFile }.sumOf { it.length() }

    /** Cleans temp files and stale trash left behind by an interrupted process. */
    fun recoverAfterCrash() {
        File(root, ".trash").deleteRecursively()
        projectsDir.walkTopDown().filter { it.isFile && it.name.endsWith(".tmp") }.forEach { it.delete() }
    }

    // ---- Model versions (non-destructive editing) -------------------------------------------------

    fun loadModel(id: String, version: Int? = null): Mesh {
        val doc = load(id)
        val v = version?.let { n -> doc.model.versions.firstOrNull { it.version == n } } ?: doc.model.current
            ?: throw IllegalStateException("Project has no model")
        return MeshReaders.readPly(File(dir(id), v.file.path).readBytes())
    }

    /**
     * Stores [mesh] as a new version produced by [operation]. Any redo history above the current version is
     * discarded; version 1 (the source reconstruction) can never be overwritten.
     */
    fun addModelVersion(id: String, mesh: Mesh, operation: String, parameters: Map<String, String> = emptyMap()): ProjectDocument {
        val doc = load(id)
        val kept = doc.model.versions.take(doc.model.currentVersion)
        // Drop redo files that are being discarded.
        doc.model.versions.drop(doc.model.currentVersion).forEach { File(dir(id), it.file.path).delete() }
        val number = kept.size + 1
        val path = "model/v%03d.ply".format(number)
        val f = File(dir(id), path)
        f.parentFile.mkdirs()
        val tmp = File(f.parentFile, f.name + ".tmp")
        FileOutputStream(tmp).buffered().use { PlyWriter.write(mesh, it, includeColors = true, comments = listOf("ScanForge 3D model version $number", "units m", "up_axis +Y")) }
        tmp.renameTo(f)
        val topo = MeshAnalysis.topology(mesh)
        val b = mesh.bounds()
        val version = ModelVersion(
            version = number,
            file = FileEntry("mesh", path, "ply", f.length(), Hashing.sha256(f)),
            operation = operation,
            parameters = parameters,
            vertexCount = mesh.vertexCount,
            triangleCount = mesh.triangleCount,
            boundsMin = b?.let { listOf(it.min.x, it.min.y, it.min.z) } ?: listOf(0.0, 0.0, 0.0),
            boundsMax = b?.let { listOf(it.max.x, it.max.y, it.max.z) } ?: listOf(0.0, 0.0, 0.0),
            hasVertexColors = mesh.hasColors,
            watertight = topo.isWatertight,
            holeCount = topo.holeCount,
            createdAt = now(),
        )
        return save(doc.copy(model = doc.model.copy(versions = kept + version, currentVersion = number)))
    }

    fun canUndo(doc: ProjectDocument) = doc.model.currentVersion > 1
    fun canRedo(doc: ProjectDocument) = doc.model.currentVersion < doc.model.versions.size

    fun undo(id: String) = update(id) { require(canUndo(it)) { "Nothing to undo" }; it.copy(model = it.model.copy(currentVersion = it.model.currentVersion - 1)) }
    fun redo(id: String) = update(id) { require(canRedo(it)) { "Nothing to redo" }; it.copy(model = it.model.copy(currentVersion = it.model.currentVersion + 1)) }
}
