package app.scanforge.android.ui.projects

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import app.scanforge.android.app
import app.scanforge.android.reconstruction.ReconstructionWorker
import app.scanforge.export.ExportOptions
import app.scanforge.export.ExportResult
import app.scanforge.export.MeshExporter
import app.scanforge.geometry.Measurements
import app.scanforge.geometry.MeasurementConfidence
import app.scanforge.geometry.MeasurementEvidence
import app.scanforge.geometry.Mesh
import app.scanforge.geometry.MeshAnalysis
import app.scanforge.geometry.MeshOps
import app.scanforge.geometry.MeshRaycaster
import app.scanforge.geometry.Vec3
import app.scanforge.project.Calibration
import app.scanforge.project.CalibrationMethod
import app.scanforge.project.MeasurementRecords
import app.scanforge.project.MeasurementType
import app.scanforge.project.ProjectDocument
import app.scanforge.project.ProjectPackager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import java.io.File
import kotlin.math.PI
import app.scanforge.android.transfer.TransferService
import app.scanforge.geometry.Pose
import app.scanforge.geometry.ConfidenceLevel
import app.scanforge.geometry.Aabb

enum class MeasureMode { NONE, DISTANCE, CIRCLE, SECTION }

data class ProjectUi(
    val doc: ProjectDocument? = null,
    val mesh: Mesh? = null,
    /** Evidence per vertex; only aligned with model version 1. */
    val evidence: ByteArray? = null,
    val busy: String? = null,
    val message: String? = null,
    val measureMode: MeasureMode = MeasureMode.NONE,
    val picked: List<Vec3> = emptyList(),
    /** Whether each picked point lies on weak or boundary (hole-adjacent) geometry. */
    val pickedWeak: List<Boolean> = emptyList(),
    val lastExport: ExportResult? = null,
    val lastPackage: File? = null,
)

class ProjectViewModel(application: Application, handle: SavedStateHandle) : AndroidViewModel(application) {
    val projectId: String = handle.get<String>("projectId")!!
    private val app = application.app
    val ui = MutableStateFlow(ProjectUi())
    private var raycaster: MeshRaycaster? = null
    private var boundaryVertices: Set<Int> = emptySet()

    init { reload() }

    private fun set(change: (ProjectUi) -> ProjectUi) { ui.value = change(ui.value) }

    fun reload() = viewModelScope.launch(Dispatchers.IO) {
        val doc = runCatching { app.store.load(projectId) }.getOrElse { set { s -> s.copy(message = "Project not found") }; return@launch }
        val mesh = if (doc.model.current != null) runCatching { app.store.loadModel(projectId) }.getOrNull() else null
        val evidence = if (doc.model.currentVersion == 1) doc.model.evidenceFile?.let { File(app.store.dir(projectId), it) }?.takeIf { it.isFile }?.readBytes() else null
        raycaster = mesh?.let(::MeshRaycaster)
        boundaryVertices = mesh?.let(::boundaryVerticesOf).orEmpty()
        set { it.copy(doc = doc, mesh = mesh, evidence = evidence, busy = null) }
    }

    private fun boundaryVerticesOf(m: Mesh): Set<Int> {
        val count = HashMap<Long, Int>()
        for (t in 0 until m.triangleCount) for (k in 0 until 3) {
            val a = m.indices[t * 3 + k]; val b = m.indices[t * 3 + (k + 1) % 3]
            val key = minOf(a, b).toLong() shl 32 or maxOf(a, b).toLong()
            count[key] = (count[key] ?: 0) + 1
        }
        return count.filterValues { it == 1 }.keys.flatMap { listOf((it ushr 32).toInt(), (it and 0xffffffffL).toInt()) }.toSet()
    }

    fun setMeasureMode(m: MeasureMode) = set { it.copy(measureMode = m, picked = emptyList(), pickedWeak = emptyList(), message = null) }
    fun clearPicked() = set { it.copy(picked = emptyList(), pickedWeak = emptyList()) }

    /** Tap in the viewer: ray-casts the reconstructed surface (never free space). */
    fun onTapRay(origin: Vec3, dir: Vec3) {
        val s = ui.value
        if (s.measureMode == MeasureMode.NONE) return
        val hit = raycaster?.cast(origin, dir) ?: run { set { it.copy(message = "Tap on the model surface") }; return }
        val picked = s.picked + hit.point
        val weakFlags = s.pickedWeak + nearWeak(hit.triangle)
        set { it.copy(picked = picked, pickedWeak = weakFlags, message = null) }
        when (s.measureMode) {
            MeasureMode.DISTANCE -> if (picked.size == 2) {
                val weak = weakFlags.any { it }
                saveAsync { app.workflow.addDistance(projectId, picked[0], picked[1], "Distance ${(s.doc?.measurements?.size ?: 0) + 1}", weak) }
                clearPicked()
            }
            MeasureMode.SECTION -> { addSection(hit.point); clearPicked() }
            else -> {}
        }
    }

    private fun nearWeak(triangle: Int): Boolean {
        val m = ui.value.mesh ?: return false
        val ev = ui.value.evidence
        return (0 until 3).any { k ->
            val v = m.indices[triangle * 3 + k]
            v in boundaryVertices || (ev != null && ev.getOrNull(v)?.toInt() == 1)
        }
    }

    private fun evidence(doc: ProjectDocument, near: Boolean, derived: Boolean) =
        MeasurementEvidence(app.workflow.scaleSource(doc), null, null, near, doc.reconstruction?.voxelSizeMeters, derived)

    /** Fits a circle to ≥3 picked rim points: diameter and circumference (derived values). */
    fun finishCircle() {
        val pts = ui.value.picked
        val doc = ui.value.doc ?: return
        val fit = Measurements.fitCircle(pts) ?: run { set { it.copy(message = "Pick at least 3 points that are not in a line") }; return }
        val q0 = MeasurementConfidence.assess(fit.diameter, evidence(doc, ui.value.pickedWeak.any { it }, true))
        val extra = buildList {
            if (fit.largestAngularGap > Math.toRadians(120.0)) add("Points cover only part of the circle; the estimate may be unreliable.")
            add("Fit residual: %.2f mm RMS over %d points.".format(fit.rmsResidual * 1000, fit.pointCount))
        }
        val q = q0.copy(reasons = q0.reasons + extra, level = if (fit.largestAngularGap > Math.toRadians(120.0)) ConfidenceLevel.LOW else q0.level)
        val pointList = pts.map { listOf(it.x, it.y, it.z) }
        val n = doc.measurements.size + 1
        val recs = listOf(
            MeasurementRecords.create(doc, MeasurementType.DIAMETER, "Diameter $n", pointList, listOf(fit.diameter), 1, true, q, app.store.now()),
            MeasurementRecords.create(doc, MeasurementType.CIRCUMFERENCE, "Circumference $n (fitted)", pointList, listOf(2 * PI * fit.radius), 1, true, q, app.store.now()),
        )
        saveAsync { app.store.save(app.store.load(projectId).let { it.copy(measurements = it.measurements + recs) }) }
        clearPicked()
    }

    /** Perimeter of the closed cross-section through [p], cut parallel to the support surface. */
    private fun addSection(p: Vec3) {
        val doc = ui.value.doc ?: return
        val mesh = ui.value.mesh ?: return
        val sp = doc.reconstruction?.supportPlane
        val n = if (sp != null) Vec3(sp[3], sp[4], sp[5]).normalized() else Vec3.Y
        val loops = Measurements.crossSection(mesh, n, n dot p)
        val loop = loops.filter { it.closed }.minByOrNull { l -> l.points.minOf { it.distanceTo(p) } }
        if (loop == null) {
            set { it.copy(message = "No closed outline at this height (the surface has gaps there), so no perimeter can be measured.") }
            return
        }
        val q = MeasurementConfidence.assess(loop.length / PI, evidence(doc, false, false))
        val rec = MeasurementRecords.create(doc, MeasurementType.CIRCUMFERENCE, "Section perimeter ${doc.measurements.size + 1}",
            listOf(listOf(p.x, p.y, p.z)), listOf(loop.length), 1, false, q, app.store.now())
        saveAsync { app.store.save(app.store.load(projectId).let { it.copy(measurements = it.measurements + rec) }) }
    }

    fun deleteMeasurement(id: String) = saveAsync { app.store.update(projectId) { d -> d.copy(measurements = d.measurements.filter { it.id != id }) } }

    /** Calibrates scale from an existing distance measurement and a known true length (meters). */
    fun calibrate(measurementId: String, trueLengthMeters: Double) {
        val doc = ui.value.doc ?: return
        val m = doc.measurements.firstOrNull { it.id == measurementId } ?: return
        saveAsync {
            val updated = Calibration.addReference(app.store.load(projectId), CalibrationMethod.REFERENCE_LENGTH, m.rawValues[0], trueLengthMeters, m.points, app.store.now(),
                note = "From measurement '${m.label}'")
            app.workflow.addStandardMeasurements(app.store.save(updated))
        }
    }

    fun setCalibrationActive(id: String, active: Boolean) = saveAsync { app.store.save(Calibration.setActive(app.store.load(projectId), id, active)) }

    // ---- Editing: every edit creates a new model version; version 1 is never modified -----------------------

    private fun edit(label: String, op: String, params: Map<String, String> = emptyMap(), f: (Mesh) -> Mesh) {
        val mesh = ui.value.mesh ?: return
        set { it.copy(busy = label) }
        viewModelScope.launch(Dispatchers.Default) {
            val result = f(mesh)
            if (result.triangleCount == 0) { set { it.copy(busy = null, message = "That edit would remove the whole model; nothing was changed.") }; return@launch }
            app.store.addModelVersion(projectId, result, op, params)
            app.workflow.addStandardMeasurements(app.store.load(projectId))
            runCatching { ReconstructionWorker.writeThumbnail(getApplication(), projectId) }
            reload()
        }
    }

    fun removeSmallPieces() = edit("Removing isolated pieces", "remove_small_components") { m ->
        val counts = MeshAnalysis.connectedComponents(m).triangleCounts()
        MeshOps.removeSmallComponents(m, maxOf(20, (counts.maxOrNull() ?: 0) / 50))
    }

    fun keepLargest() = edit("Keeping largest piece", "keep_largest_component") { MeshOps.keepLargestComponent(it) }

    fun cutBelowSupport() {
        val sp = ui.value.doc?.reconstruction?.supportPlane ?: run { set { it.copy(message = "No support surface was recorded for this scan") }; return }
        val n = Vec3(sp[3], sp[4], sp[5]).normalized()
        val offset = (n dot Vec3(sp[0], sp[1], sp[2])) + 0.002
        edit("Cutting below support surface", "cut_below_plane", mapOf("offset" to offset.toString())) { MeshOps.cutBelowPlane(it, n, offset) }
    }

    fun simplify(fraction: Double) {
        val target = ((ui.value.mesh?.triangleCount ?: 0) * fraction).toInt()
        edit("Simplifying", "simplify_vertex_clustering", mapOf("target_triangles" to target.toString())) { MeshOps.simplifyToTarget(it, target) }
    }

    /** Moves the origin to the bottom center of the model (translation only). */
    fun originToBottomCenter() = edit("Setting origin", "set_origin_bottom_center") { m ->
        val b = m.bounds()!!
        MeshOps.translate(m, -Vec3(b.center.x, b.min.y, b.center.z))
    }

    fun deleteCrop(box: Aabb) = edit("Cropping", "crop_to_box") { MeshOps.cropToBox(it, box) }

    fun undo() = saveAsync { app.store.undo(projectId); runCatching { ReconstructionWorker.writeThumbnail(getApplication(), projectId) } }
    fun redo() = saveAsync { app.store.redo(projectId); runCatching { ReconstructionWorker.writeThumbnail(getApplication(), projectId) } }

    fun reconstruct() { ReconstructionWorker.enqueue(getApplication(), projectId) }

    // ---- Export / transfer ------------------------------------------------------------------------------------

    fun export(options: ExportOptions) {
        val mesh = ui.value.mesh ?: return
        val doc = ui.value.doc ?: return
        set { it.copy(busy = "Exporting") }
        viewModelScope.launch(Dispatchers.IO) {
            val dir = File(app.filesDir, "scanforge/exports/${doc.id}").apply { deleteRecursively(); mkdirs() }
            val factor = doc.scale.factor
            val scaled = if (factor != 1.0) MeshOps.transform(mesh, Pose.IDENTITY, factor) else mesh
            val r = runCatching {
                MeshExporter.export(scaled, dir, safeName(doc.name), options, comments = listOf("ScanForge 3D project ${doc.id}", "scale_source ${doc.scale.source}")) { step -> set { it.copy(busy = step) } }
            }
            set { it.copy(busy = null, lastExport = r.getOrNull(), message = r.exceptionOrNull()?.let { e -> "Export failed: ${e.message}" }
                ?: r.getOrNull()?.validation?.let { v -> if (v.ok) "Exported and verified" else "Export problems: ${v.problems.joinToString()}" }) }
        }
    }

    fun exportPackage(includeCapture: Boolean) {
        val doc = ui.value.doc ?: return
        set { it.copy(busy = "Packaging") }
        viewModelScope.launch(Dispatchers.IO) {
            val f = File(app.filesDir, "scanforge/exports/${doc.id}/${safeName(doc.name)}.sf3d")
            val r = runCatching { ProjectPackager.pack(app.store, projectId, f, includeCapture); ProjectPackager.verify(f) }
            set { it.copy(busy = null, lastPackage = if (r.isSuccess) f else null, message = r.exceptionOrNull()?.let { e -> "Packaging failed: ${e.message}" } ?: "Package created and verified") }
        }
    }

    fun sendToBlender(includeCapture: Boolean = false) {
        viewModelScope.launch(Dispatchers.IO) {
            TransferService.start(getApplication())
            var err = app.transfer.start()
            if (err == null) err = app.transfer.send(projectId, includeCapture)
            set { it.copy(message = err ?: "Offered to paired computers. In Blender: ScanForge > Check for Scans.") }
        }
    }

    private fun saveAsync(block: () -> Any?) = viewModelScope.launch(Dispatchers.IO) {
        runCatching(block).onFailure { e -> set { it.copy(message = e.message) } }
        reload()
    }

    companion object {
        fun safeName(s: String) = s.replace(Regex("[^A-Za-z0-9_-]+"), "_").trim('_').ifEmpty { "scan" }
    }
}
