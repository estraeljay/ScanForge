package app.scanforge.workflow

import app.scanforge.geometry.Measurements
import app.scanforge.geometry.MeasurementConfidence
import app.scanforge.geometry.MeasurementEvidence
import app.scanforge.geometry.MeshAnalysis
import app.scanforge.geometry.ScaleSource
import app.scanforge.geometry.Vec3
import app.scanforge.project.CaptureInfo
import app.scanforge.project.MeasurementRecords
import app.scanforge.project.MeasurementType
import app.scanforge.project.ProjectDocument
import app.scanforge.project.ProjectJson
import app.scanforge.project.ProjectStatus
import app.scanforge.project.ProjectStore
import app.scanforge.project.ReconstructionInfo
import app.scanforge.reconstruction.CancellationToken
import app.scanforge.reconstruction.CaptureSource
import app.scanforge.reconstruction.CheckpointStore
import app.scanforge.reconstruction.CoverageReport
import app.scanforge.reconstruction.Progress
import app.scanforge.reconstruction.ReconstructionException
import app.scanforge.reconstruction.ReconstructionPipeline
import app.scanforge.reconstruction.ReconstructionSettings
import java.io.File

/** High-level operations shared by the Android app and the desktop CLI. */
class ScanWorkflow(private val store: ProjectStore) {
    companion object {
        const val SUPPORT_HEIGHT_LABEL = "Height above support surface"
    }

    fun scaleSource(doc: ProjectDocument): ScaleSource = when (doc.scale.source) {
        "reference_length" -> ScaleSource.REFERENCE_LENGTH
        "printed_marker" -> ScaleSource.PRINTED_MARKER
        "unscaled" -> ScaleSource.UNSCALED
        "external" -> ScaleSource.EXTERNAL
        else -> ScaleSource.DEVICE_TRACKING
    }

    /**
     * Runs reconstruction for [projectId] from [source] and stores version 1 of the model. Status and failure
     * reasons are persisted so the library always reflects real state, including after a crash.
     */
    fun reconstruct(
        projectId: String,
        source: CaptureSource,
        settings: ReconstructionSettings = ReconstructionSettings.forPreset(source.session.qualityPreset),
        cancel: CancellationToken = CancellationToken(),
        onProgress: (Progress) -> Unit = {},
    ): ProjectDocument {
        store.update(projectId) {
            it.copy(
                status = ProjectStatus.RECONSTRUCTING,
                failureReason = null,
                capture = CaptureInfo(
                    mode = source.session.captureMode.name.lowercase(),
                    qualityPreset = source.session.qualityPreset.name.lowercase(),
                    frameCount = source.frames.size,
                    sessionId = source.session.sessionId,
                    source = source.session.source,
                    device = source.session.device,
                    retained = it.capture?.retained ?: true,
                ),
            )
        }
        val result = try {
            ReconstructionPipeline(settings).run(source, CheckpointStore(store.checkpointDir(projectId)), cancel, onProgress)
        } catch (e: ReconstructionException) {
            store.update(projectId) { it.copy(status = ProjectStatus.FAILED, failureReason = e.failure.name.lowercase()) }
            throw e
        }
        // A re-run replaces the model history: version 1 must always be the raw reconstruction.
        store.update(projectId) { doc ->
            doc.model.versions.forEach { File(store.dir(projectId), it.file.path).delete() }
            doc.copy(model = doc.model.copy(versions = emptyList(), currentVersion = 0), measurements = emptyList())
        }
        store.addModelVersion(projectId, result.mesh, "reconstruction", mapOf("method" to "tsdf_surface_nets", "voxel_size_m" to settings.voxelSize.toString()))
        File(store.dir(projectId), "model/evidence.bin").writeBytes(result.vertexEvidence)
        val coverageJson = ProjectJson.encodeToJsonElement(CoverageReport.serializer(), result.coverage)
        val doc = store.update(projectId) {
            it.copy(
                status = ProjectStatus.RECONSTRUCTED,
                reconstruction = ReconstructionInfo(
                    "tsdf_surface_nets", settings.voxelSize, result.framesUsed, result.warnings, coverageJson, store.now(),
                    supportPlane = source.session.groundPlane?.let { g -> g.point + g.normal },
                ),
                model = it.model.copy(evidenceFile = "model/evidence.bin"),
                scale = it.scale.copy(source = if (source.session.source == "synthetic") "external" else it.scale.source),
            )
        }
        return addStandardMeasurements(doc)
    }

    /** Adds bounding-box dimensions and, when meaningful, area/volume for the current model version. */
    fun addStandardMeasurements(doc0: ProjectDocument): ProjectDocument {
        val mesh = store.loadModel(doc0.id)
        val b = mesh.bounds() ?: return doc0
        val resolution = doc0.reconstruction?.voxelSizeMeters
        val evidence = MeasurementEvidence(scaleSource(doc0), null, null, nearMissingGeometry = false, reconstructionResolution = resolution, derived = false)
        val records = ArrayList(doc0.measurements.filter { it.type != MeasurementType.BOUNDING_BOX && it.type != MeasurementType.SURFACE_AREA && it.type != MeasurementType.VOLUME && it.type != MeasurementType.ORIENTED_BOUNDING_BOX })
        val size = b.size
        records += MeasurementRecords.create(doc0, MeasurementType.BOUNDING_BOX, "Bounding box (W × H × D)",
            listOf(listOf(b.min.x, b.min.y, b.min.z), listOf(b.max.x, b.max.y, b.max.z)), listOf(size.x, size.y, size.z), 1, false,
            MeasurementConfidence.assess(minOf(size.x, size.y, size.z), evidence), store.now())
        val plane = doc0.reconstruction?.supportPlane
        if (plane != null) {
            val up = Vec3(plane[3], plane[4], plane[5])
            Measurements.uprightBoundingBox((0 until mesh.vertexCount).map { mesh.vertex(it) }, up)?.let { obb ->
                val l = maxOf(obb.halfExtents.x, obb.halfExtents.y) * 2; val w = minOf(obb.halfExtents.x, obb.halfExtents.y) * 2
                records += MeasurementRecords.create(doc0, MeasurementType.ORIENTED_BOUNDING_BOX, "Upright footprint (L × W)", emptyList(), listOf(l, w), 1, true,
                    MeasurementConfidence.assess(w, evidence.copy(derived = true)), store.now())
            }
        } else {
            Measurements.orientedBoundingBox(mesh)?.let { obb ->
                val d = obb.sortedDimensions
                records += MeasurementRecords.create(doc0, MeasurementType.ORIENTED_BOUNDING_BOX, "Oriented box (L × W × H)", emptyList(), d, 1, true,
                    MeasurementConfidence.assess(d.min(), evidence.copy(derived = true)), store.now())
            }
        }
        // Objects resting on a surface lose their bottom slice to the support-plane cut, so the mesh's own
        // bounding box under-reports height. When the plane is known, height is measured from the plane.
        doc0.reconstruction?.supportPlane?.let { sp ->
            val point = Vec3(sp[0], sp[1], sp[2]); val n = Vec3(sp[3], sp[4], sp[5]).normalized()
            val top = (0 until mesh.vertexCount).maxOf { n dot (mesh.vertex(it) - point) }
            records.removeAll { it.label == SUPPORT_HEIGHT_LABEL }
            records += MeasurementRecords.create(doc0, MeasurementType.DISTANCE, SUPPORT_HEIGHT_LABEL, emptyList(), listOf(top), 1, true,
                MeasurementConfidence.assess(top, evidence.copy(derived = true)), store.now())
        }
        val topo = MeshAnalysis.topology(mesh)
        records += MeasurementRecords.create(doc0, MeasurementType.SURFACE_AREA, "Surface area (reconstructed)", emptyList(),
            listOf(MeshAnalysis.surfaceArea(mesh)), 2, false,
            MeasurementConfidence.assess(size.length(), evidence.copy(nearMissingGeometry = topo.holeCount > 0)), store.now())
        if (topo.isWatertight) {
            records += MeasurementRecords.create(doc0, MeasurementType.VOLUME, "Enclosed volume", emptyList(),
                listOf(kotlin.math.abs(MeshAnalysis.signedVolume(mesh))), 3, false, MeasurementConfidence.assess(size.length(), evidence), store.now())
        }
        return store.save(doc0.copy(measurements = records))
    }

    /** Point-to-point distance between two surface points picked on the current model version. */
    fun addDistance(projectId: String, a: Vec3, b: Vec3, label: String, nearMissingGeometry: Boolean = false): ProjectDocument {
        val doc = store.load(projectId)
        val q = MeasurementConfidence.assess(a.distanceTo(b), MeasurementEvidence(scaleSource(doc), null, null, nearMissingGeometry, doc.reconstruction?.voxelSizeMeters, false))
        val rec = MeasurementRecords.create(doc, MeasurementType.DISTANCE, label, listOf(listOf(a.x, a.y, a.z), listOf(b.x, b.y, b.z)), listOf(a.distanceTo(b)), 1, false, q, store.now())
        return store.save(doc.copy(measurements = doc.measurements + rec))
    }
}
