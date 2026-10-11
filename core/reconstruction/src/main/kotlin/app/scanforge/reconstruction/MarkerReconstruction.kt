package app.scanforge.reconstruction

import app.scanforge.geometry.Aabb
import app.scanforge.geometry.Vec3
import app.scanforge.markers.BoardLayout
import app.scanforge.markers.BoardSpec
import app.scanforge.markers.FocalEstimation
import app.scanforge.markers.Pinhole
import app.scanforge.markers.PlanarPose
import kotlin.math.max

data class MarkerSolveReport(
    val focalPx: Double,
    val framesWithPose: Int,
    val framesRejected: Int,
    val meanRmsPx: Double,
    val depthFramesWithData: Int,
)

/**
 * Marker-board capture → posed depth frames:
 * 1. focal length from all board detections, 2. per-frame camera pose (PnP), 3. plane-sweep MVS depth maps.
 * The result is a depth-mode capture fused by [ReconstructionPipeline], so cleanup, coverage and measurement are
 * identical to the ARCore path. World space: board plane Y = 0, origin at the board center, meters
 * (times the user-verified print scale).
 */
object MarkerReconstruction {
    data class Prepared(val source: InMemoryCaptureSource, val report: MarkerSolveReport)

    /**
     * Width of the images used for stereo matching. Measured on synthetic A3-board captures (100 × 70 × 50 mm box):
     * matching at 480–640 px with the 7×7 window gave larger errors (footprint +4–7 mm) than 320 px (≤ 1.5 mm) and took
     * 4× longer, so every preset matches at 320 px.
     */
    const val MVS_WIDTH = 320

    fun prepare(
        source: CaptureSource,
        objectHeightMax: Double = 0.45,
        params: MvsParams = MvsParams(),
        cancel: CancellationToken = CancellationToken(),
        onProgress: (Progress) -> Unit = {},
    ): Prepared {
        val session = source.session
        val board = BoardLayout(BoardSpec.fromCode(session.board ?: throw ReconstructionException(ReconstructionFailure.BOARD_NOT_FOUND, "no board recorded")), session.boardScale)
        val frames = source.frames.filter { (it.markers?.size ?: 0) >= 4 && it.colorIntrinsics != null && it.colorFile != null }
        onProgress(Progress(Stage.TRACKING, 0, frames.size, "Estimating camera focal length"))
        if (frames.size < 8) throw ReconstructionException(ReconstructionFailure.BOARD_NOT_FOUND, "${frames.size} usable photos")
        val k0 = frames.first().colorIntrinsics!!
        val focal = FocalEstimation.estimate(frames.map { it.markers!! }, board, k0.width, k0.height, initialGuess = k0.fx)
        val f = focal?.focalPx ?: k0.fx
        val pin = Pinhole(f, f, (k0.width - 1) / 2.0, (k0.height - 1) / 2.0)
        val kFull = CameraIntrinsics(f, f, pin.cx, pin.cy, k0.width, k0.height)

        val posed = ArrayList<Pair<FrameRecord, app.scanforge.geometry.Pose>>()
        var rmsSum = 0.0
        frames.forEachIndexed { i, fr ->
            cancel.throwIfCancelled()
            val est = PlanarPose.estimate(fr.markers!!, board, pin)
            if (est != null && est.rmsErrorPx <= 2.0 && est.markersUsed >= 4) { posed += fr to est.pose; rmsSum += est.rmsErrorPx }
            onProgress(Progress(Stage.TRACKING, i + 1, frames.size, "Solving camera positions"))
        }
        if (posed.size < 8) throw ReconstructionException(ReconstructionFailure.BOARD_NOT_FOUND, "${posed.size} photos with a reliable pose")

        // Region of interest: the marker grid area, up to objectHeightMax above it.
        val hw = board.gridWidthMm / 2000 * board.scale; val hd = board.gridHeightMm / 2000 * board.scale
        val roi = Aabb(Vec3(-hw, -0.01, -hd), Vec3(hw, objectHeightMax, hd))

        val targetW = MVS_WIDTH
        val views = posed.map { (fr, pose) ->
            val rgb = source.loadColor(fr) ?: throw ReconstructionException(ReconstructionFailure.NO_STEREO_MATCHES, "missing image ${fr.colorFile}")
            val gray = GrayImage.fromRgb(rgb)
            // Largest integer downsampling that keeps at least the matching width.
            val factor = max(1, gray.width / targetW)
            val small = gray.downsample(factor)
            PosedView(small, kFull.scaledTo(small.width, small.height), pose)
        }
        val depths = PlaneSweepStereo(params).run(views, roi, { done, total ->
            onProgress(Progress(Stage.MATCHING, done, total, "Matching photo $done of $total"))
        }, { cancel.isCancelled })
        val withData = depths.count { d -> d != null && d.depthMm.any { it.toInt() != 0 } }
        if (withData == 0) throw ReconstructionException(ReconstructionFailure.NO_STEREO_MATCHES)

        val outFrames = ArrayList<FrameRecord>(); val depthMap = HashMap<Int, DepthFrame>(); val colorMap = HashMap<Int, RgbImage>()
        posed.forEachIndexed { i, (fr, pose) ->
            val d = depths[i] ?: return@forEachIndexed
            depthMap[i] = d
            source.loadColor(fr)?.let { colorMap[i] = it }
            outFrames += fr.copy(
                index = i, cameraPose = pose.toArray(), tracking = TrackingQuality.TRACKING,
                colorIntrinsics = kFull, depthIntrinsics = views[i].k, depthFile = "mvs/%06d".format(i), markers = null,
            )
        }
        val outSession = session.copy(captureMode = CaptureMode.DEPTH, roi = RegionOfInterest.of(roi),
            groundPlane = GroundPlane(listOf(0.0, 0.0, 0.0), listOf(0.0, 1.0, 0.0)))
        val report = MarkerSolveReport(f, posed.size, frames.size - posed.size, rmsSum / posed.size, withData)
        return Prepared(InMemoryCaptureSource(outSession, outFrames, depthMap, colorMap), report)
    }

    /**
     * Reconstruction settings for MVS depth: denser, less noisy depth than phone depth-from-motion. High uses Standard's
     * 4 mm voxels: with 320 px stereo depth, 2.5–3 mm voxels gave more holes and larger errors on synthetic captures (36
     * and 90 photos). High differs by allowing more photos during capture.
     */
    fun settingsFor(preset: QualityPreset) = ReconstructionSettings.forPreset(preset).copy(
        voxelSize = if (preset == QualityPreset.HIGH) 0.004 else ReconstructionSettings.forPreset(preset).voxelSize,
        minFrames = 8, minViewFraction = 0.15, minViewsPerCoverageBin = 1, minObservationsForSurface = 1, maxDepth = 2.0, minConfidence = 0.4,
        // The board defines the support plane exactly (no fitted-plane noise), so the cut can sit closer to it.
        groundCutVoxels = 1.0,
    )
}
