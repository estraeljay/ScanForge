package app.scanforge.reconstruction

import app.scanforge.geometry.Aabb
import app.scanforge.geometry.Mesh
import app.scanforge.geometry.MeshAnalysis
import app.scanforge.geometry.MeshOps
import app.scanforge.geometry.Vec3
import kotlinx.serialization.Serializable
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File

enum class Stage { VALIDATING, TRACKING, MATCHING, INTEGRATING, EXTRACTING, CLEANING, ANALYZING, DONE }

/** Progress is reported per real processing unit (frames, blocks), never by timer. */
data class Progress(val stage: Stage, val completed: Int, val total: Int, val message: String)

class CancellationToken {
    @Volatile var isCancelled = false
        private set
    fun cancel() { isCancelled = true }
    fun throwIfCancelled() { if (isCancelled) throw ReconstructionException(ReconstructionFailure.CANCELLED) }
}

/** Failure causes with user-facing explanations and recovery steps. */
enum class ReconstructionFailure(val userMessage: String, val recovery: List<String>) {
    PHOTO_MODE_NOT_SUPPORTED_ON_DEVICE(
        "This capture contains photos only. On-device reconstruction needs depth data.",
        listOf("Export the capture package and run the optional desktop photogrammetry workflow.", "Rescan in Depth mode on a device that supports the ARCore Depth API."),
    ),
    NOT_ENOUGH_FRAMES(
        "Not enough usable frames were captured.",
        listOf("Resume the scan and move slowly around the object.", "Make sure tracking stays green while capturing."),
    ),
    NO_DEPTH(
        "No valid depth was measured inside the scan region.",
        listOf("Keep the object between 0.3 m and 1.5 m from the camera.", "Improve lighting and avoid reflective, transparent or very dark surfaces."),
    ),
    INSUFFICIENT_COVERAGE(
        "The object was observed from too few directions.",
        listOf("Resume the scan and walk all the way around the object.", "Capture the top from a higher angle."),
    ),
    EMPTY_SURFACE(
        "No surface could be reconstructed from the captured data.",
        listOf("Check that the scan region contains the object.", "Rescan with more overlap and steadier motion."),
    ),
    BOARD_NOT_FOUND(
        "The marker board was not found in enough photos to compute camera positions.",
        listOf("Keep most of the board visible in every photo.", "Print the board at 100 % and keep it flat; avoid glare on glossy paper."),
    ),
    NO_STEREO_MATCHES(
        "No reliable 3D points could be matched between photos.",
        listOf("Use even, bright light without strong shadows.", "Objects need visible surface texture; plain, shiny or transparent surfaces cannot be matched."),
    ),
    OUT_OF_MEMORY(
        "The device ran out of memory during reconstruction.",
        listOf("Use the Draft quality preset.", "Reduce the scan region size."),
    ),
    CANCELLED("Reconstruction was cancelled.", listOf("Start reconstruction again; progress is resumed from the last checkpoint.")),
}

class ReconstructionException(val failure: ReconstructionFailure, detail: String? = null) :
    Exception(detail?.let { "${failure.userMessage} ($it)" } ?: failure.userMessage)

@Serializable
data class ReconstructionSettings(
    val voxelSize: Double,
    val truncationVoxels: Double = 4.0,
    val minFrames: Int,
    val minViewFraction: Double = 0.2,
    /** Views needed before a direction counts as observed (dense video: 2; sparse photo captures: 1). */
    val minViewsPerCoverageBin: Int = 2,
    val minConfidence: Double = 0.25,
    val maxDepth: Double = 2.5,
    /** Remove geometry this many voxels above the detected support plane (the table). */
    val groundCutVoxels: Double = 1.5,
    val cutGround: Boolean = true,
    /** Components smaller than this fraction of the largest are removed as isolated noise. */
    val minComponentFraction: Double = 0.05,
    val checkpointEveryFrames: Int = 20,
    /** Only extract surface where every cell corner was observed by at least this many frames (rejects one-off outliers). */
    val minObservationsForSurface: Int = 1,
    /** Skip depth pixels on occlusion boundaries (see IntegrationParams.rejectDepthEdges). */
    val rejectDepthEdges: Boolean = false,
    /** Default region size when the capture did not define one, meters. */
    val defaultRoiSize: Double = 1.0,
) {
    companion object {
        /**
         * Presets trade detail for time/memory. Smaller voxels do not improve dimensional accuracy once they are
         * below the depth sensor's noise (typically several mm at 0.5 m for depth-from-motion).
         */
        fun forPreset(p: QualityPreset) = when (p) {
            QualityPreset.DRAFT -> ReconstructionSettings(voxelSize = 0.006, minFrames = 15)
            QualityPreset.STANDARD -> ReconstructionSettings(voxelSize = 0.004, minFrames = 30)
            QualityPreset.HIGH -> ReconstructionSettings(voxelSize = 0.0025, minFrames = 60)
        }
    }
}

class ReconstructionResult(
    val mesh: Mesh,
    /** [EvidenceClass] per vertex of [mesh]. */
    val vertexEvidence: ByteArray,
    val coverage: CoverageReport,
    val warnings: List<String>,
    val framesUsed: Int,
    val voxelSize: Double,
    val roi: Aabb,
    val resumedFromFrame: Int,
)

/** Stores TSDF checkpoints so an interrupted reconstruction can resume. */
class CheckpointStore(private val dir: File) {
    private val file get() = File(dir, "tsdf.ckpt")

    fun save(volume: TsdfVolume, nextFrame: Int, fingerprint: String) {
        dir.mkdirs()
        val tmp = File(dir, "tsdf.ckpt.tmp")
        DataOutputStream(tmp.outputStream().buffered()).use { out ->
            out.writeUTF(fingerprint); out.writeInt(nextFrame); volume.save(out)
        }
        file.delete(); tmp.renameTo(file)
    }

    fun load(fingerprint: String, roi: Aabb): Pair<TsdfVolume, Int>? {
        if (!file.isFile) return null
        return runCatching {
            DataInputStream(file.inputStream().buffered()).use { input ->
                if (input.readUTF() != fingerprint) return null
                val next = input.readInt()
                TsdfVolume.load(input, roi) to next
            }
        }.getOrNull()
    }

    fun clear() { file.delete() }
}

class ReconstructionPipeline(private val settings: ReconstructionSettings) {

    fun run(
        source: CaptureSource,
        checkpoints: CheckpointStore? = null,
        cancel: CancellationToken = CancellationToken(),
        onProgress: (Progress) -> Unit = {},
    ): ReconstructionResult {
        val warnings = mutableListOf<String>()
        // --- Validate -------------------------------------------------------------------------------
        onProgress(Progress(Stage.VALIDATING, 0, 1, "Validating capture"))
        if (source.session.captureMode == CaptureMode.PHOTO) throw ReconstructionException(ReconstructionFailure.PHOTO_MODE_NOT_SUPPORTED_ON_DEVICE)
        val usable = source.frames.filter { it.tracking == TrackingQuality.TRACKING && it.depthFile != null && it.depthIntrinsics != null }
        if (usable.size < settings.minFrames) {
            throw ReconstructionException(ReconstructionFailure.NOT_ENOUGH_FRAMES, "${usable.size} usable of ${settings.minFrames} required")
        }
        val dropped = source.frames.size - usable.size
        if (dropped > 0) warnings += "$dropped frame(s) skipped because tracking was not stable or depth was missing."
        val roi = source.session.roi?.aabb ?: estimateRoi(source, usable).also {
            warnings += "No scan region was set; it was estimated from the depth data."
        }
        val coverage = ViewCoverage(roi.center, minViewsPerBin = settings.minViewsPerCoverageBin)
        usable.forEach { coverage.addView(it.pose) }
        val viewFraction = coverage.observedFraction()
        if (viewFraction < settings.minViewFraction) {
            throw ReconstructionException(ReconstructionFailure.INSUFFICIENT_COVERAGE, "%.0f%% of directions observed".format(viewFraction * 100))
        }
        if (viewFraction < 0.6) warnings += "Only %.0f%% of viewing directions were observed; expect missing surfaces.".format(viewFraction * 100)
        if (coverage.bandFraction(coverage.elevationBins - 1) < 0.25) warnings += "The top of the object was rarely observed."
        onProgress(Progress(Stage.VALIDATING, 1, 1, "${usable.size} usable frames"))

        // --- Integrate ------------------------------------------------------------------------------
        val params = IntegrationParams(minConfidence = settings.minConfidence, maxDepth = settings.maxDepth, rejectDepthEdges = settings.rejectDepthEdges)
        val fingerprint = "${settings.hashCode()}|${usable.size}|${usable.last().timestampNs}|$roi"
        val restored = checkpoints?.load(fingerprint, roi)
        val volume = restored?.first ?: TsdfVolume(settings.voxelSize, settings.voxelSize * settings.truncationVoxels, roi)
        val start = restored?.second ?: 0
        var validPixels = 0L
        try {
            for (i in start until usable.size) {
                cancel.throwIfCancelled()
                val f = usable[i]
                val depth = source.loadDepth(f)
                if (depth == null) { warnings += "Depth file missing for frame ${f.index}."; continue }
                val stats = volume.integrate(depth, f.depthIntrinsics!!, f.pose, source.loadColor(f), f.colorIntrinsics, params)
                validPixels += stats.validPixels
                onProgress(Progress(Stage.INTEGRATING, i + 1, usable.size, "Fusing frame ${i + 1} of ${usable.size}"))
                if (checkpoints != null && (i + 1) % settings.checkpointEveryFrames == 0 && i + 1 < usable.size) {
                    checkpoints.save(volume, i + 1, fingerprint)
                }
            }
        } catch (e: OutOfMemoryError) {
            throw ReconstructionException(ReconstructionFailure.OUT_OF_MEMORY)
        }
        if (volume.blockCount == 0 || (start == 0 && validPixels == 0L)) throw ReconstructionException(ReconstructionFailure.NO_DEPTH)

        // --- Extract ------------------------------------------------------------------------------
        cancel.throwIfCancelled()
        onProgress(Progress(Stage.EXTRACTING, 0, 1, "Extracting surface"))
        val surface = SurfaceNets.extract(volume, minObservations = settings.minObservationsForSurface)
        onProgress(Progress(Stage.EXTRACTING, 1, 1, "${surface.mesh.triangleCount} triangles"))

        // --- Clean --------------------------------------------------------------------------------
        cancel.throwIfCancelled()
        onProgress(Progress(Stage.CLEANING, 0, 2, "Removing support plane"))
        var mesh = surface.mesh
        var sourceIndex = IntArray(mesh.vertexCount) { it }
        fun apply(edit: Pair<Mesh, IntArray>) { mesh = edit.first; sourceIndex = IntArray(edit.second.size) { sourceIndex[edit.second[it]] } }
        val ground = source.session.groundPlane
        if (settings.cutGround && ground != null) {
            val n = ground.normalVec
            val cut = ground.offset + settings.groundCutVoxels * settings.voxelSize
            val m0 = mesh
            apply(MeshOps.filterTrianglesWithMap(m0) { (n dot MeshOps.centroid(m0, it)) >= cut })
        }
        onProgress(Progress(Stage.CLEANING, 1, 2, "Removing isolated fragments"))
        if (mesh.triangleCount > 0) {
            val comps = MeshAnalysis.connectedComponents(mesh)
            val counts = comps.triangleCounts()
            val threshold = maxOf(20, (counts.max() * settings.minComponentFraction).toInt())
            val removed = counts.count { it < threshold }
            val m0 = mesh
            apply(MeshOps.filterTrianglesWithMap(m0) { counts[comps.triangleComponent[it]] >= threshold })
            if (removed > 0) warnings += "$removed small disconnected fragment(s) removed as noise."
        }
        if (mesh.triangleCount == 0) throw ReconstructionException(ReconstructionFailure.EMPTY_SURFACE)
        onProgress(Progress(Stage.CLEANING, 2, 2, "Cleaned"))

        // --- Analyze ------------------------------------------------------------------------------
        onProgress(Progress(Stage.ANALYZING, 0, 1, "Analyzing coverage"))
        val evidence = ByteArray(mesh.vertexCount) { v ->
            if (surface.evidence.observations[sourceIndex[v]] >= 3) EvidenceClass.STRONG else EvidenceClass.WEAK
        }
        val strong = evidence.count { it == EvidenceClass.STRONG }
        val topo = MeshAnalysis.topology(mesh)
        if (topo.holeCount > 0) warnings += "${topo.holeCount} hole(s) in the surface (unobserved or failed regions)."
        val report = CoverageReport(
            viewFraction = viewFraction,
            topViewFraction = coverage.bandFraction(coverage.elevationBins - 1),
            viewsCounted = usable.size,
            surfaceVertices = mesh.vertexCount,
            strongVertexFraction = strong.toDouble() / mesh.vertexCount,
            weakVertexFraction = 1.0 - strong.toDouble() / mesh.vertexCount,
            holeCount = topo.holeCount,
            boundaryEdges = topo.boundaryEdges,
            watertight = topo.isWatertight,
        )
        checkpoints?.clear()
        onProgress(Progress(Stage.DONE, 1, 1, "Reconstruction complete"))
        return ReconstructionResult(mesh, evidence, report, warnings, usable.size, settings.voxelSize, roi, start)
    }

    /** Robust ROI estimate: median of center-pixel depth points, expanded to [ReconstructionSettings.defaultRoiSize]. */
    private fun estimateRoi(source: CaptureSource, frames: List<FrameRecord>): Aabb {
        val pts = ArrayList<Vec3>()
        for (f in frames.filterIndexed { i, _ -> i % maxOf(1, frames.size / 20) == 0 }) {
            val d = source.loadDepth(f) ?: continue
            val k = f.depthIntrinsics ?: continue
            val z = d.depthMetersAt(d.width / 2, d.height / 2)
            if (z > 0.1) pts += f.pose.transformPoint(k.unproject((d.width / 2).toDouble(), (d.height / 2).toDouble(), z))
        }
        if (pts.isEmpty()) throw ReconstructionException(ReconstructionFailure.NO_DEPTH)
        fun median(sel: (Vec3) -> Double) = pts.map(sel).sorted()[pts.size / 2]
        val c = Vec3(median { it.x }, median { it.y }, median { it.z })
        val h = settings.defaultRoiSize / 2
        return Aabb(c - Vec3(h, h, h), c + Vec3(h, h, h))
    }
}
