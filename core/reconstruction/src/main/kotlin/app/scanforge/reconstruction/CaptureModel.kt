package app.scanforge.reconstruction

import app.scanforge.geometry.Aabb
import app.scanforge.geometry.Pose
import app.scanforge.geometry.Quat
import app.scanforge.geometry.Vec3
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Capture dataset format version written to capture/session.json. */
const val CAPTURE_FORMAT_VERSION = 1

/**
 * Pinhole intrinsics in pixels for an image of [width]×[height].
 * Pixel (0,0) is the top-left pixel center; u grows right, v grows down (OpenCV convention).
 */
@Serializable
data class CameraIntrinsics(
    val fx: Double,
    val fy: Double,
    val cx: Double,
    val cy: Double,
    val width: Int,
    val height: Int,
) {
    /** Intrinsics for the same field of view sampled at a different resolution. */
    fun scaledTo(newWidth: Int, newHeight: Int): CameraIntrinsics {
        val sx = newWidth.toDouble() / width
        val sy = newHeight.toDouble() / height
        return CameraIntrinsics(fx * sx, fy * sy, (cx + 0.5) * sx - 0.5, (cy + 0.5) * sy - 0.5, newWidth, newHeight)
    }

    /**
     * Camera-space point for pixel (u, v) at z-depth [depth] meters. Camera space follows the ARCore/OpenGL
     * convention: +X right, +Y up, the camera looks down -Z.
     */
    fun unproject(u: Double, v: Double, depth: Double) = Vec3((u - cx) / fx * depth, -(v - cy) / fy * depth, -depth)

    /** Projects a camera-space point; returns null when it is behind the camera. Result is (u, v, depth). */
    fun project(p: Vec3): Vec3? {
        val d = -p.z
        if (d <= 1e-9) return null
        return Vec3(fx * p.x / d + cx, cy - fy * p.y / d, d)
    }
}

@Serializable
enum class TrackingQuality {
    @SerialName("tracking") TRACKING,
    @SerialName("limited") LIMITED,
    @SerialName("paused") PAUSED,
    @SerialName("stopped") STOPPED,
}

/** Serialized pose: [tx, ty, tz, qx, qy, qz, qw], camera-to-world. */
fun Pose.toArray() = listOf(translation.x, translation.y, translation.z, rotation.x, rotation.y, rotation.z, rotation.w)
fun poseFromArray(a: List<Double>): Pose {
    require(a.size == 7) { "pose needs 7 values" }
    return Pose(Vec3(a[0], a[1], a[2]), Quat(a[3], a[4], a[5], a[6]).normalized())
}

/** One captured keyframe, appended as a line of capture/frames.jsonl. */
@Serializable
data class FrameRecord(
    val index: Int,
    /** Sensor timestamp in nanoseconds (ARCore Frame.getTimestamp). */
    @SerialName("timestamp_ns") val timestampNs: Long,
    /** Camera-to-world pose of the physical (sensor-oriented) camera. */
    @SerialName("camera_pose") val cameraPose: List<Double>,
    val tracking: TrackingQuality,
    /** Relative path of the color image (JPEG), or null if not captured. */
    @SerialName("color_file") val colorFile: String? = null,
    @SerialName("color_intrinsics") val colorIntrinsics: CameraIntrinsics? = null,
    /** Relative path of the depth file (SFD1 format, see [DepthFrameCodec]), or null. */
    @SerialName("depth_file") val depthFile: String? = null,
    @SerialName("depth_intrinsics") val depthIntrinsics: CameraIntrinsics? = null,
    /** Mean depth confidence 0..1 over valid pixels, computed on device for quick filtering. */
    @SerialName("mean_depth_confidence") val meanDepthConfidence: Double? = null,
    /** Fraction of the color image with acceptable exposure (0..1), if measured. */
    @SerialName("exposure_ok_fraction") val exposureOkFraction: Double? = null,
) {
    val pose: Pose get() = poseFromArray(cameraPose)
}

/** Horizontal support plane (e.g. table top) detected during capture, world space. */
@Serializable
data class GroundPlane(
    /** Point on the plane. */
    val point: List<Double>,
    /** Unit normal pointing away from the supporting surface (normally +Y). */
    val normal: List<Double>,
) {
    val pointVec get() = Vec3(point[0], point[1], point[2])
    val normalVec get() = Vec3(normal[0], normal[1], normal[2]).normalized()
    val offset get() = normalVec dot pointVec
}

/** Region of interest that bounds reconstruction, world space. */
@Serializable
data class RegionOfInterest(val min: List<Double>, val max: List<Double>) {
    val aabb get() = Aabb(Vec3(min[0], min[1], min[2]), Vec3(max[0], max[1], max[2]))

    companion object {
        fun of(box: Aabb) = RegionOfInterest(listOf(box.min.x, box.min.y, box.min.z), listOf(box.max.x, box.max.y, box.max.z))
    }
}

/** Static information about a capture session, stored as capture/session.json. */
@Serializable
data class CaptureSession(
    @SerialName("format_version") val formatVersion: Int = CAPTURE_FORMAT_VERSION,
    @SerialName("session_id") val sessionId: String,
    @SerialName("started_at") val startedAt: String,
    @SerialName("capture_mode") val captureMode: CaptureMode,
    @SerialName("quality_preset") val qualityPreset: QualityPreset,
    val roi: RegionOfInterest? = null,
    @SerialName("ground_plane") val groundPlane: GroundPlane? = null,
    val device: Map<String, String> = emptyMap(),
    /** "synthetic" marks generated test data; never set for real captures. */
    val source: String = "device",
)

@Serializable
enum class CaptureMode {
    /** ARCore poses + depth frames + color keyframes; reconstructed on device by TSDF fusion. */
    @SerialName("depth") DEPTH,
    /** ARCore poses + color keyframes only; intended for optional desktop photogrammetry. */
    @SerialName("photo") PHOTO,
}

@Serializable
enum class QualityPreset {
    @SerialName("draft") DRAFT,
    @SerialName("standard") STANDARD,
    @SerialName("high") HIGH,
}
