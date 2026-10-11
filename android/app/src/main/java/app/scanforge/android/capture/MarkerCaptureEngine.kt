package app.scanforge.android.capture

import android.os.SystemClock
import android.util.Log
import app.scanforge.android.ScanForgeApp
import app.scanforge.geometry.Vec3
import app.scanforge.markers.BoardLayout
import app.scanforge.markers.MarkerObservation
import app.scanforge.markers.Pinhole
import app.scanforge.markers.PlanarPose
import app.scanforge.project.CaptureInfo
import app.scanforge.project.ProjectStatus
import app.scanforge.reconstruction.CameraIntrinsics
import app.scanforge.reconstruction.CaptureMode
import app.scanforge.reconstruction.CaptureSession
import app.scanforge.reconstruction.CaptureWriter
import app.scanforge.reconstruction.CoverageHint
import app.scanforge.reconstruction.FrameRecord
import app.scanforge.reconstruction.KeyframeSelector
import app.scanforge.reconstruction.QualityPreset
import app.scanforge.reconstruction.TrackingQuality
import app.scanforge.reconstruction.ViewCoverage
import app.scanforge.reconstruction.toArray
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.time.Instant
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

enum class MarkerGuidance { BOARD_NOT_VISIBLE, MORE_OF_BOARD, HOLD_STILL, CONTINUE_AROUND, RAISE_ANGLE, CAPTURE_TOP, COMPLETE, READY }

data class MarkerHudState(
    val scanning: Boolean = false,
    val finished: Boolean = false,
    val markersVisible: Int = 0,
    val boardMarkers: Int = 0,
    val poseOk: Boolean = false,
    val reprojectionPx: Double? = null,
    val sharpness: Double = 0.0,
    val photos: Int = 0,
    val bytesWritten: Long = 0,
    val pendingWrites: Int = 0,
    val durationMs: Long = 0,
    val viewFraction: Double = 0.0,
    val topFraction: Double = 0.0,
    val guidance: MarkerGuidance = MarkerGuidance.BOARD_NOT_VISIBLE,
    val nearestGapRadians: Double? = null,
    val message: String? = null,
)

/**
 * Marker-board capture: each camera frame is searched for board markers; when tracking is good and the camera has moved
 * to a new viewpoint, the frame is kept as a sharp photo together with its marker detections. Camera poses and the focal
 * length are recomputed from the stored detections during reconstruction, so live estimates only drive guidance.
 */
class MarkerCaptureEngine(
    private val app: ScanForgeApp,
    val projectId: String,
    val preset: QualityPreset,
    val board: BoardLayout,
) {
    private val tag = "ScanForge.MarkerCapture"
    private val _state = MutableStateFlow(MarkerHudState(boardMarkers = board.markers.size))
    val state: StateFlow<MarkerHudState> = _state.asStateFlow()
    private val io = Executors.newSingleThreadExecutor { Thread(it, "sf-marker-io") }
    private val pending = AtomicInteger()
    private val selector = KeyframeSelector(minTranslation = 0.04, minRotationDeg = 8.0, minIntervalNs = 400_000_000L, maxSpeed = 0.35)
    // Coverage around the middle of the board, a little above it (where objects are).
    private val coverage = ViewCoverage(Vec3(0.0, 0.05, 0.0), minViewsPerBin = 1)
    private var writer: CaptureWriter? = null
    private var startedAt = 0L
    private var sharpnessBaseline = 0.0
    val maxPhotos = when (preset) { QualityPreset.DRAFT -> 40; QualityPreset.STANDARD -> 60; QualityPreset.HIGH -> 90 }

    private fun update(f: (MarkerHudState) -> MarkerHudState) { _state.value = f(_state.value) }

    fun start(device: Map<String, String>) {
        if (writer == null) {
            val dir = app.store.captureDir(projectId)
            dir.deleteRecursively(); dir.mkdirs()
            val session = CaptureSession(
                sessionId = UUID.randomUUID().toString(), startedAt = Instant.now().toString(),
                captureMode = CaptureMode.MARKER, qualityPreset = preset, device = device,
                board = board.spec.code, boardScale = board.scale,
            )
            writer = CaptureWriter(dir, session)
            app.store.update(projectId) {
                it.copy(status = ProjectStatus.CAPTURING, capture = CaptureInfo("marker", preset.name.lowercase(), 0, session.sessionId, "device", device))
            }
        }
        startedAt = SystemClock.elapsedRealtime() - _state.value.durationMs
        selector.reset()
        update { it.copy(scanning = true, message = null) }
    }

    fun pause() = update { it.copy(scanning = false) }

    fun finish(): Boolean {
        pause()
        io.submit {}.get()
        val w = writer ?: return false
        w.close()
        app.store.update(projectId) { it.copy(status = ProjectStatus.CAPTURED, capture = it.capture?.copy(frameCount = w.frameCount, durationSeconds = _state.value.durationMs / 1000.0)) }
        update { it.copy(finished = true) }
        return w.frameCount >= 8
    }

    fun close() { io.shutdown(); writer?.close() }

    /**
     * Called on the analysis thread for each frame. [encode] produces the JPEG only when the frame is kept.
     */
    fun onFrame(gray: ByteArray, width: Int, height: Int, timestampNs: Long, k: CameraIntrinsics, markers: List<MarkerObservation>, encode: () -> ByteArray) {
        val s = _state.value
        val pin = Pinhole(k.fx, k.fy, k.cx, k.cy)
        val est = if (markers.size >= 3) PlanarPose.estimate(markers, board, pin, 10) else null
        val poseOk = est != null && est.rmsErrorPx < 3.0
        val sharp = CameraFrames.sharpness(gray, width, height)
        sharpnessBaseline = if (sharpnessBaseline == 0.0) sharp else 0.95 * sharpnessBaseline + 0.05 * sharp
        var message = s.message
        var photos = s.photos
        if (s.scanning && poseOk && markers.size >= 6) {
            val pose = est!!.pose
            val moved = selector.observe(pose, timestampNs)
            val sharpEnough = sharp >= 0.6 * sharpnessBaseline
            if (moved && sharpEnough) {
                val w = writer!!
                when {
                    w.frameCount >= maxPhotos -> { pause(); message = "Photo limit for this quality reached. Finish the scan." }
                    pending.get() > 2 -> message = "Saving photos… move more slowly"
                    else -> {
                        coverage.addView(pose)
                        val jpeg = encode()
                        pending.incrementAndGet()
                        photos++
                        io.execute {
                            try {
                                w.append(FrameRecord(w.nextIndex(), timestampNs, pose.toArray(), TrackingQuality.TRACKING, colorIntrinsics = k, markers = markers), null, jpeg)
                            } catch (e: Exception) {
                                Log.e(tag, "Could not save photo", e)
                                update { it.copy(message = "Could not save a photo: ${e.message}") }
                            } finally { pending.decrementAndGet() }
                        }
                    }
                }
            }
        }
        val guidance = when {
            markers.isEmpty() -> MarkerGuidance.BOARD_NOT_VISIBLE
            markers.size < 6 || !poseOk -> MarkerGuidance.MORE_OF_BOARD
            s.scanning && selector.speed > selector.maxSpeed -> MarkerGuidance.HOLD_STILL
            !s.scanning && s.photos == 0 -> MarkerGuidance.READY
            else -> when (coverage.hint()) {
                CoverageHint.CONTINUE_AROUND -> MarkerGuidance.CONTINUE_AROUND
                CoverageHint.RAISE_ANGLE -> MarkerGuidance.RAISE_ANGLE
                CoverageHint.CAPTURE_TOP -> MarkerGuidance.CAPTURE_TOP
                CoverageHint.COMPLETE -> MarkerGuidance.COMPLETE
            }
        }
        _state.value = _state.value.copy(
            markersVisible = markers.size, poseOk = poseOk, reprojectionPx = est?.rmsErrorPx, sharpness = sharp,
            photos = photos, bytesWritten = writer?.bytesWritten ?: 0, pendingWrites = pending.get(),
            durationMs = if (s.scanning) SystemClock.elapsedRealtime() - startedAt else s.durationMs,
            viewFraction = coverage.observedFraction(), topFraction = coverage.bandFraction(coverage.elevationBins - 1),
            guidance = guidance, nearestGapRadians = est?.let { coverage.nearestGap(it.pose.translation) }, message = message,
        )
    }
}
