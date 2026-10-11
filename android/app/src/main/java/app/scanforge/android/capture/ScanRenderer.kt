package app.scanforge.android.capture

import android.app.Activity
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import android.os.Build
import android.util.Log
import app.scanforge.android.ar.BackgroundRenderer
import app.scanforge.android.ar.DisplayRotationHelper
import app.scanforge.android.ar.GlUtil
import app.scanforge.android.ar.OverlayRenderer
import app.scanforge.geometry.Vec3
import app.scanforge.reconstruction.CameraIntrinsics
import app.scanforge.reconstruction.EvidenceClass
import app.scanforge.reconstruction.QualityPreset
import app.scanforge.reconstruction.TrackingQuality
import com.google.ar.core.CameraConfig
import com.google.ar.core.CameraConfigFilter
import com.google.ar.core.Config
import com.google.ar.core.Frame
import com.google.ar.core.LightEstimate
import com.google.ar.core.Plane
import com.google.ar.core.Session
import com.google.ar.core.TrackingFailureReason
import com.google.ar.core.TrackingState
import com.google.ar.core.exceptions.CameraNotAvailableException
import com.google.ar.core.exceptions.NotYetAvailableException
import java.util.concurrent.ConcurrentLinkedQueue
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/**
 * GL renderer that drives the ARCore session: one session.update() per drawn frame, camera background,
 * scan-region box and live preview points, and keyframe extraction for the [CaptureEngine].
 */
class ScanRenderer(private val activity: Activity, private val engine: CaptureEngine) : GLSurfaceView.Renderer {
    private val tag = "ScanForge.Renderer"
    private var session: Session? = null
    private val background = BackgroundRenderer()
    private val overlay = OverlayRenderer()
    private val rotation = DisplayRotationHelper(activity)
    private val taps = ConcurrentLinkedQueue<Pair<Float, Float>>()
    @Volatile var showPoints = true
    @Volatile var showCoverage = true
    @Volatile var depthSupported = false
        private set
    private val proj = FloatArray(16)
    private val view = FloatArray(16)
    private val vp = FloatArray(16)
    private var pointsVersion: Any? = null
    private var pointXyz = GlUtil.floatBuffer(FloatArray(3))
    private var pointRgba = GlUtil.floatBuffer(FloatArray(4))
    private var pointCount = 0

    /** Creates and configures the session. Returns an error message or null. Call from the UI thread. */
    fun resume(): String? {
        try {
            if (session == null) {
                val s = Session(activity)
                depthSupported = s.isDepthModeSupported(Config.DepthMode.RAW_DEPTH_ONLY)
                chooseCameraConfig(s)
                val cfg = Config(s).apply {
                    depthMode = if (engine.wantsDepth && depthSupported) Config.DepthMode.RAW_DEPTH_ONLY else Config.DepthMode.DISABLED
                    focusMode = Config.FocusMode.AUTO
                    planeFindingMode = Config.PlaneFindingMode.HORIZONTAL
                    lightEstimationMode = Config.LightEstimationMode.AMBIENT_INTENSITY
                    updateMode = Config.UpdateMode.LATEST_CAMERA_IMAGE
                }
                s.configure(cfg)
                session = s
            }
            session!!.resume()
            rotation.onResume()
            return null
        } catch (e: CameraNotAvailableException) {
            session = null
            return "The camera is in use by another app. Close it and try again."
        } catch (e: Exception) {
            Log.e(tag, "Could not start AR session", e)
            session = null
            return "Could not start ARCore: ${e.message}"
        }
    }

    /** Prefers a hardware depth sensor when ARCore offers one; High quality also picks the largest CPU image. */
    private fun chooseCameraConfig(s: Session) {
        val filter = CameraConfigFilter(s).setFacingDirection(CameraConfig.FacingDirection.BACK)
        val configs = s.getSupportedCameraConfigs(filter)
        if (configs.isEmpty()) return
        val withDepth = configs.filter { it.depthSensorUsage == CameraConfig.DepthSensorUsage.REQUIRE_AND_USE }
        val pool = if (engine.wantsDepth && withDepth.isNotEmpty()) withDepth else configs
        val chosen = if (engine.setup.preset == QualityPreset.HIGH) pool.maxBy { it.imageSize.width * it.imageSize.height } else pool.first()
        s.cameraConfig = chosen
    }

    fun pause() {
        rotation.onPause()
        session?.pause()
    }

    fun destroy() { session?.close(); session = null }

    fun onTap(x: Float, y: Float) { taps.add(x to y) }

    fun deviceInfo(): Map<String, String> = buildMap {
        put("model", "${Build.MANUFACTURER} ${Build.MODEL}")
        put("android", Build.VERSION.RELEASE)
        put("depth_api", depthSupported.toString())
        session?.cameraConfig?.let {
            put("cpu_image", "${it.imageSize.width}x${it.imageSize.height}")
            put("depth_sensor", it.depthSensorUsage.name.lowercase())
        }
    }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES20.glClearColor(0.07f, 0.08f, 0.09f, 1f)
        background.createOnGlThread()
        overlay.createOnGlThread()
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        GLES20.glViewport(0, 0, width, height)
        rotation.onSurfaceChanged(width, height)
    }

    override fun onDrawFrame(gl: GL10?) {
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)
        val s = session ?: return
        rotation.updateSessionIfNeeded(s)
        s.setCameraTextureName(background.textureId)
        val frame = try { s.update() } catch (e: Exception) { Log.w(tag, "session.update failed", e); return }
        background.draw(frame)
        val camera = frame.camera
        val tracking = when (camera.trackingState) {
            TrackingState.TRACKING -> TrackingQuality.TRACKING
            TrackingState.PAUSED -> TrackingQuality.PAUSED
            else -> TrackingQuality.STOPPED
        }
        val failure = camera.trackingFailureReason.takeIf { it != TrackingFailureReason.NONE }?.let(::describe)
        val planes = s.getAllTrackables(Plane::class.java).any { it.trackingState == TrackingState.TRACKING && it.subsumedBy == null && it.type == Plane.Type.HORIZONTAL_UPWARD_FACING }
        handleTaps(frame)
        val light = frame.lightEstimate.takeIf { it.state == LightEstimate.State.VALID }?.pixelIntensity
        val pose = CaptureEngine.poseOf(camera.pose)
        engine.onFrame(tracking, failure, pose, frame.timestamp, light, planes) { extractKeyframe(frame) }
        if (tracking == TrackingQuality.TRACKING) drawOverlay(frame)
    }

    private fun handleTaps(frame: Frame) {
        while (true) {
            val (x, y) = taps.poll() ?: return
            if (frame.camera.trackingState != TrackingState.TRACKING) continue
            for (hit in frame.hitTest(x, y)) {
                val t = hit.trackable
                if (t is Plane && t.type == Plane.Type.HORIZONTAL_UPWARD_FACING && t.isPoseInPolygon(hit.hitPose)) {
                    val p = hit.hitPose
                    val n = t.centerPose.yAxis
                    engine.placeRoi(Vec3(p.tx().toDouble(), p.ty().toDouble(), p.tz().toDouble()), Vec3(n[0].toDouble(), n[1].toDouble(), n[2].toDouble()))
                    break
                }
            }
        }
    }

    private fun extractKeyframe(frame: Frame): KeyframeData? {
        val camera = frame.camera
        val k = camera.imageIntrinsics
        val f = k.focalLength; val c = k.principalPoint; val dims = k.imageDimensions
        val colorK = CameraIntrinsics(f[0].toDouble(), f[1].toDouble(), c[0].toDouble(), c[1].toDouble(), dims[0], dims[1])
        var nv21: ByteArray? = null
        try {
            frame.acquireCameraImage().use { img -> if (img.width == dims[0] && img.height == dims[1]) nv21 = ArImages.toNv21(img) }
        } catch (_: NotYetAvailableException) {
        }
        var depth: app.scanforge.reconstruction.DepthFrame? = null
        var depthK: CameraIntrinsics? = null
        if (engine.wantsDepth && depthSupported) {
            try {
                frame.acquireRawDepthImage16Bits().use { d ->
                    if (engine.isNewDepth(d.timestamp)) {
                        frame.acquireRawDepthConfidenceImage().use { conf -> depth = ArImages.copyDepth(d, conf) }
                        // The raw depth image covers the CPU image's field of view; intrinsics scale with resolution.
                        val aspectCpu = dims[0].toDouble() / dims[1]
                        val aspectDepth = d.width.toDouble() / d.height
                        depthK = if (kotlin.math.abs(aspectCpu - aspectDepth) < 0.01) colorK.scaledTo(d.width, d.height) else null
                        if (depthK == null) { Log.w(tag, "Depth/CPU aspect mismatch; depth skipped"); depth = null }
                    }
                }
            } catch (_: NotYetAvailableException) {
            }
            // A keyframe without new depth is still useful for color and coverage in depth mode only if depth arrives
            // soon; skip it so every stored depth keyframe has fresh depth.
            if (depth == null) return null
        }
        if (nv21 == null && depth == null) return null
        return KeyframeData(nv21, if (nv21 != null) colorK else null, depth, depthK)
    }

    private fun drawOverlay(frame: Frame) {
        frame.camera.getProjectionMatrix(proj, 0, 0.05f, 30f)
        frame.camera.getViewMatrix(view, 0)
        Matrix.multiplyMM(vp, 0, proj, 0, view, 0)
        engine.roi?.let { r ->
            val lines = OverlayRenderer.boxLines(r.min.toFloatArray(), r.max.toFloatArray())
            overlay.draw(vp, GlUtil.floatBuffer(lines), GlUtil.floatBuffer(OverlayRenderer.solidColors(lines.size / 3, 0.18f, 0.77f, 0.95f, 0.9f)), lines.size / 3, GLES20.GL_LINES)
        }
        val snap = engine.snapshot
        if (showPoints && snap != null) {
            if (snap !== pointsVersion) {
                pointsVersion = snap
                pointCount = snap.points.size / 3
                pointXyz = GlUtil.floatBuffer(snap.points)
                // Strong evidence = cyan, weak evidence = amber. With coverage off, a single neutral color.
                val rgba = FloatArray(pointCount * 4)
                for (i in 0 until pointCount) {
                    val strong = snap.pointClasses[i] == EvidenceClass.STRONG
                    val col = if (!showCoverage) floatArrayOf(0.85f, 0.88f, 0.92f) else if (strong) floatArrayOf(0.18f, 0.77f, 0.95f) else floatArrayOf(0.98f, 0.75f, 0.18f)
                    rgba[i * 4] = col[0]; rgba[i * 4 + 1] = col[1]; rgba[i * 4 + 2] = col[2]; rgba[i * 4 + 3] = 0.9f
                }
                pointRgba = GlUtil.floatBuffer(rgba)
            }
            overlay.draw(vp, pointXyz, pointRgba, pointCount, GLES20.GL_POINTS, 5f)
        }
    }

    private fun describe(r: TrackingFailureReason) = when (r) {
        TrackingFailureReason.INSUFFICIENT_LIGHT -> "Too dark: improve the lighting"
        TrackingFailureReason.EXCESSIVE_MOTION -> "Moving too fast: hold the device steady"
        TrackingFailureReason.INSUFFICIENT_FEATURES -> "Not enough detail: point at a textured area"
        TrackingFailureReason.CAMERA_UNAVAILABLE -> "Camera unavailable"
        TrackingFailureReason.BAD_STATE -> "Tracking error: return to the previously scanned area"
        else -> "Tracking lost: return to the previously scanned area"
    }
}
