package app.scanforge.android.capture

import android.os.StatFs
import android.os.SystemClock
import android.util.Log
import app.scanforge.android.ScanForgeApp
import app.scanforge.geometry.Aabb
import app.scanforge.geometry.Pose
import app.scanforge.geometry.Quat
import app.scanforge.geometry.Vec3
import app.scanforge.project.CaptureInfo
import app.scanforge.project.ObjectCategory
import app.scanforge.project.ProjectStatus
import app.scanforge.reconstruction.CameraIntrinsics
import app.scanforge.reconstruction.CaptureMode
import app.scanforge.reconstruction.CaptureSession
import app.scanforge.reconstruction.CaptureWriter
import app.scanforge.reconstruction.DepthFrame
import app.scanforge.reconstruction.DirectoryCaptureSource
import app.scanforge.reconstruction.FrameRecord
import app.scanforge.reconstruction.GroundPlane
import app.scanforge.reconstruction.KeyframeSelector
import app.scanforge.reconstruction.LiveGuidance
import app.scanforge.reconstruction.LiveScan
import app.scanforge.reconstruction.LiveSnapshot
import app.scanforge.reconstruction.QualityPreset
import app.scanforge.reconstruction.RegionOfInterest
import app.scanforge.reconstruction.TrackingQuality
import app.scanforge.reconstruction.toArray
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.time.Instant
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

enum class ScanPhase { PREPARING, PLACING, READY, SCANNING, PAUSED, FINISHED, ERROR }

data class ScanSetup(
    val projectId: String,
    val mode: CaptureMode,
    val preset: QualityPreset,
    val category: ObjectCategory,
)

/** Everything the HUD shows. Each field comes from measured state; nothing is simulated. */
data class ScanHudState(
    val phase: ScanPhase = ScanPhase.PREPARING,
    val tracking: TrackingQuality? = null,
    val trackingFailure: String? = null,
    val guidance: LiveGuidance = LiveGuidance.INITIALIZING,
    val keyframes: Int = 0,
    val depthFrames: Int = 0,
    val durationMs: Long = 0,
    val bytesWritten: Long = 0,
    val freeBytes: Long = 0,
    val pendingWrites: Int = 0,
    val viewFraction: Double = 0.0,
    val strongSurfaceFraction: Double = 0.0,
    val surfaceVoxels: Int = 0,
    val lightIntensity: Float? = null,
    val distanceMeters: Double? = null,
    val depthAvailable: Boolean = false,
    val planesDetected: Boolean = false,
    val roiHalfSize: Double = 0.15,
    val roiPlaced: Boolean = false,
    val nearestGapRadians: Double? = null,
    val message: String? = null,
    val error: String? = null,
)

/**
 * Owns one capture: keyframe selection, copying ARCore images off the GL thread, crash-safe writing
 * through [CaptureWriter], and the low-resolution live preview. Frame callbacks arrive on the GL thread;
 * disk and fusion work runs on a single background executor.
 */
class CaptureEngine(private val app: ScanForgeApp, val setup: ScanSetup) {
    private val tag = "ScanForge.Capture"
    private val _state = MutableStateFlow(ScanHudState(roiHalfSize = defaultHalfSize(setup.category)))
    val state: StateFlow<ScanHudState> = _state.asStateFlow()

    private val io = Executors.newSingleThreadExecutor { Thread(it, "sf-capture-io") }
    private val pending = AtomicInteger(0)
    private val selector = KeyframeSelector()
    private var writer: CaptureWriter? = null
    @Volatile private var live: LiveScan? = null
    @Volatile var snapshot: LiveSnapshot? = null
        private set
    private var lastDepthTimestamp = 0L
    private var scanStartedAt = 0L
    private var accumulatedMs = 0L
    @Volatile private var depthFrames = 0

    @Volatile var roi: Aabb? = null
        private set
    private var ground: GroundPlane? = null
    private val maxKeyframes = when (setup.preset) { QualityPreset.DRAFT -> 150; QualityPreset.STANDARD -> 300; QualityPreset.HIGH -> 600 }
    val wantsDepth get() = setup.mode == CaptureMode.DEPTH

    private fun update(change: (ScanHudState) -> ScanHudState) { _state.value = change(_state.value) }

    fun setPhase(p: ScanPhase, message: String? = null) = update { it.copy(phase = p, message = message) }
    fun fail(message: String) = update { it.copy(phase = ScanPhase.ERROR, error = message) }

    fun setRoiHalfSize(h: Double) {
        update { it.copy(roiHalfSize = h.coerceIn(0.05, 2.0)) }
        ground?.let { placeRoi(it.pointVec, it.normalVec) }
    }

    /** Places the scan region on a detected horizontal surface at [base] (world coordinates). */
    fun placeRoi(base: Vec3, normal: Vec3) {
        val h = _state.value.roiHalfSize
        ground = GroundPlane(listOf(base.x, base.y, base.z), listOf(normal.x, normal.y, normal.z))
        // Region extends slightly below the surface so the base of the object is fully inside.
        roi = Aabb(base + Vec3(-h, -0.03, -h), base + Vec3(h, 2 * h, h))
        update { it.copy(roiPlaced = true, phase = if (it.phase == ScanPhase.PLACING) ScanPhase.READY else it.phase) }
    }

    fun start(deviceInfo: Map<String, String>) {
        val region = roi ?: return
        val dir = app.store.captureDir(setup.projectId)
        val session = CaptureSession(
            sessionId = UUID.randomUUID().toString(),
            startedAt = Instant.now().toString(),
            captureMode = setup.mode,
            qualityPreset = setup.preset,
            roi = RegionOfInterest.of(region),
            groundPlane = ground,
            device = deviceInfo,
        )
        // A capture continues only inside the same AR session (shared world frame). Leftovers from a killed
        // process belong to a different world frame and are discarded before starting.
        if (writer == null) {
            dir.deleteRecursively(); dir.mkdirs()
            writer = CaptureWriter(dir, session)
            live = LiveScan(region)
            app.store.update(setup.projectId) {
                it.copy(status = ProjectStatus.CAPTURING, capture = CaptureInfo(setup.mode.name.lowercase(), setup.preset.name.lowercase(), 0, session.sessionId, "device", deviceInfo))
            }
        }
        selector.reset()
        scanStartedAt = SystemClock.elapsedRealtime()
        setPhase(ScanPhase.SCANNING)
    }

    fun pause() {
        if (_state.value.phase != ScanPhase.SCANNING) return
        accumulatedMs += SystemClock.elapsedRealtime() - scanStartedAt
        setPhase(ScanPhase.PAUSED)
    }

    fun resume() { if (_state.value.phase == ScanPhase.PAUSED) start(emptyMap()) }

    /** Discards keyframes from the last [seconds] and rebuilds the live preview from what remains. */
    fun undoRecent(seconds: Int = 3) {
        val w = writer ?: return
        val region = roi ?: return
        io.execute {
            val frames = DirectoryCaptureSource.readFrames(w.dir)
            val cutoff = (frames.lastOrNull()?.timestampNs ?: 0) - seconds * 1_000_000_000L
            val drop = frames.count { it.timestampNs >= cutoff }
            w.discardLast(drop)
            val rebuilt = LiveScan(region)
            val source = DirectoryCaptureSource(w.dir, null)
            source.frames.forEach { f -> rebuilt.integrate(f, source.loadDepth(f)) }
            live = rebuilt
            snapshot = rebuilt.snapshot(null)
            depthFrames = source.frames.count { it.depthFile != null }
            update { it.copy(keyframes = w.frameCount, depthFrames = depthFrames, message = "Removed $drop recent frame(s)") }
        }
    }

    /** Stops capturing and marks the project CAPTURED. Returns false if nothing usable was captured. */
    fun finish(): Boolean {
        pause()
        io.submit {}.get() // wait for pending writes
        val w = writer ?: return false
        w.close()
        val count = w.frameCount
        val durationS = accumulatedMs / 1000.0
        app.store.update(setup.projectId) {
            it.copy(status = ProjectStatus.CAPTURED, capture = it.capture?.copy(frameCount = count, durationSeconds = durationS))
        }
        setPhase(ScanPhase.FINISHED)
        return count > 0
    }

    fun close() { io.shutdown(); writer?.close() }

    /**
     * Called on the GL thread for every ARCore frame with values already extracted from ARCore objects.
     * [acquire] lazily copies the images only when the frame becomes a keyframe.
     */
    fun onFrame(
        tracking: TrackingQuality,
        failure: String?,
        pose: Pose,
        timestampNs: Long,
        lightIntensity: Float?,
        planesDetected: Boolean,
        acquire: () -> KeyframeData?,
    ) {
        val s = _state.value
        var message = s.message
        if (s.phase == ScanPhase.SCANNING && tracking == TrackingQuality.TRACKING && selector.observe(pose, timestampNs)) {
            when {
                writer!!.frameCount >= maxKeyframes -> { pause(); message = "Frame limit for this quality preset reached. Finish the scan." }
                pending.get() > 4 -> message = "Storage is busy; slow down"
                freeBytes() < 200L shl 20 -> { pause(); message = "Storage almost full: scan paused" }
                else -> acquire()?.let { enqueue(it, pose, timestampNs) }
            }
        } else if (s.phase == ScanPhase.SCANNING) {
            selector.observe(pose, timestampNs) // keep the speed estimate current
        }
        val snap = snapshot
        val lowLight = lightIntensity != null && lightIntensity < 0.12f
        val guidance = if (s.phase == ScanPhase.SCANNING || s.phase == ScanPhase.READY)
            LiveScan.guidance(tracking, selector.speed, selector.maxSpeed, snap?.lastDepthMeters, 0.2, 1.8, lowLight, snap?.hint)
        else LiveGuidance.INITIALIZING
        val duration = accumulatedMs + if (s.phase == ScanPhase.SCANNING) SystemClock.elapsedRealtime() - scanStartedAt else 0
        _state.value = s.copy(
            tracking = tracking, trackingFailure = failure, guidance = guidance,
            keyframes = writer?.frameCount ?: 0, depthFrames = depthFrames, durationMs = duration,
            bytesWritten = writer?.bytesWritten ?: 0, pendingWrites = pending.get(),
            viewFraction = snap?.viewFraction ?: 0.0, strongSurfaceFraction = snap?.strongSurfaceFraction ?: 0.0,
            surfaceVoxels = snap?.surfaceVoxels ?: 0, lightIntensity = lightIntensity,
            distanceMeters = snap?.lastDepthMeters, planesDetected = planesDetected,
            nearestGapRadians = snap?.nearestGapRadians, message = message,
            phase = if (s.phase == ScanPhase.PREPARING && tracking == TrackingQuality.TRACKING) ScanPhase.PLACING else s.phase,
        )
    }

    fun isNewDepth(timestampNs: Long): Boolean {
        if (timestampNs == lastDepthTimestamp) return false
        lastDepthTimestamp = timestampNs
        return true
    }

    private fun enqueue(k: KeyframeData, pose: Pose, timestampNs: Long) {
        val w = writer ?: return
        pending.incrementAndGet()
        io.execute {
            try {
                val jpeg = k.nv21?.let { ArImages.nv21ToJpeg(it, k.colorIntrinsics!!.width, k.colorIntrinsics.height, 90) }
                val exposure = k.nv21?.let { ArImages.meanLuma(it, k.colorIntrinsics!!.width, k.colorIntrinsics.height) }
                val record = FrameRecord(
                    index = w.nextIndex(), timestampNs = timestampNs, cameraPose = pose.toArray(), tracking = TrackingQuality.TRACKING,
                    colorIntrinsics = k.colorIntrinsics, depthIntrinsics = k.depthIntrinsics,
                    meanDepthConfidence = k.depth?.let(::meanConfidence),
                    exposureOkFraction = exposure?.let { if (it in 0.1..0.9) 1.0 else 0.0 },
                )
                val saved = w.append(record, k.depth, jpeg)
                if (k.depth != null) depthFrames++
                live?.let { l ->
                    l.integrate(saved, k.depth)
                    if (w.frameCount % 3 == 0) snapshot = l.snapshot(pose.translation)
                }
            } catch (e: Exception) {
                Log.e(tag, "Failed to store keyframe", e)
                update { it.copy(message = "Could not save a frame: ${e.message}") }
            } finally {
                pending.decrementAndGet()
            }
        }
    }

    private fun meanConfidence(d: DepthFrame): Double? {
        val c = d.confidence ?: return null
        var s = 0L; var n = 0
        for (i in c.indices) if (d.depthMm[i].toInt() != 0) { s += c[i].toInt() and 0xff; n++ }
        return if (n == 0) 0.0 else s / (n * 255.0)
    }

    private fun freeBytes() = runCatching { StatFs(app.filesDir.path).availableBytes }.getOrDefault(Long.MAX_VALUE)

    companion object {
        fun defaultHalfSize(c: ObjectCategory) = when (c) {
            ObjectCategory.SMALL_PRODUCT -> 0.12
            ObjectCategory.MECHANICAL_COMPONENT -> 0.15
            ObjectCategory.HOUSEHOLD_OBJECT -> 0.25
            ObjectCategory.SCULPTURAL_OBJECT -> 0.35
            ObjectCategory.FURNITURE -> 1.0
            ObjectCategory.ARCHITECTURAL_FEATURE -> 1.5
            ObjectCategory.OTHER -> 0.25
        }

        fun poseOf(p: com.google.ar.core.Pose) =
            Pose(Vec3(p.tx().toDouble(), p.ty().toDouble(), p.tz().toDouble()), Quat(p.qx().toDouble(), p.qy().toDouble(), p.qz().toDouble(), p.qw().toDouble()).normalized())
    }
}

/** Data copied from ARCore for one keyframe. */
class KeyframeData(
    val nv21: ByteArray?,
    val colorIntrinsics: CameraIntrinsics?,
    val depth: DepthFrame?,
    val depthIntrinsics: CameraIntrinsics?,
)
