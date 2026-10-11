package app.scanforge.android.capability

import app.scanforge.android.systemService
import android.app.ActivityManager
import android.content.Context
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.Build
import android.os.StatFs
import android.util.Log
import app.scanforge.geometry.Pose
import app.scanforge.reconstruction.CameraIntrinsics
import app.scanforge.reconstruction.DepthFrame
import app.scanforge.reconstruction.TsdfVolume
import com.google.ar.core.ArCoreApk
import com.google.ar.core.CameraConfig
import com.google.ar.core.CameraConfigFilter
import com.google.ar.core.Config
import com.google.ar.core.Session
import java.util.EnumSet

enum class ArCoreState { SUPPORTED_INSTALLED, NEEDS_INSTALL_OR_UPDATE, UNSUPPORTED, CHECKING, UNKNOWN_ERROR }

/** Recommended capture mode given what the device can actually do. */
enum class ScanSupport { DEPTH_SCANNING, PHOTO_CAPTURE_ONLY, NOT_SUPPORTED }

data class CameraInfo(
    val hasBackCamera: Boolean,
    val autofocus: Boolean,
    val maxJpegPixels: Long,
    /** Camera2 reports a DEPTH_OUTPUT capability (e.g. a ToF sensor); not necessarily usable through ARCore. */
    val depthOutputCapability: Boolean,
)

data class DeviceReport(
    val model: String,
    val androidVersion: String,
    val sdkInt: Int,
    val arCore: ArCoreState,
    /** ARCore Depth API support (null when ARCore could not be queried). */
    val depthApi: Boolean?,
    val rawDepthApi: Boolean?,
    /** ARCore exposes a camera config that uses a hardware depth sensor. */
    val hardwareDepthSensor: Boolean?,
    val camera: CameraInfo,
    val totalRamBytes: Long,
    val availableRamBytes: Long,
    val lowRamDevice: Boolean,
    val freeStorageBytes: Long,
    val cpuCores: Int,
    /** Measured: milliseconds to fuse one 160×120 depth frame on this device (real micro-benchmark). */
    val fusionBenchmarkMs: Double?,
) {
    val support: ScanSupport get() = when {
        arCore == ArCoreState.SUPPORTED_INSTALLED && depthApi == true -> ScanSupport.DEPTH_SCANNING
        arCore == ArCoreState.SUPPORTED_INSTALLED || arCore == ArCoreState.NEEDS_INSTALL_OR_UPDATE -> ScanSupport.PHOTO_CAPTURE_ONLY
        else -> ScanSupport.NOT_SUPPORTED
    }

    /** Human-readable limitations derived only from measured facts. */
    fun warnings(): List<String> = buildList {
        if (arCore == ArCoreState.UNSUPPORTED) add("This device is not supported by Google Play Services for AR; scanning is unavailable. Projects, measurements and transfer still work.")
        if (arCore == ArCoreState.NEEDS_INSTALL_OR_UPDATE) add("Google Play Services for AR must be installed or updated before scanning.")
        if (depthApi == false) add("The ARCore Depth API is not supported: on-device 3D reconstruction is unavailable. Photo capture for optional desktop processing is still possible.")
        if (!camera.autofocus) add("The rear camera has a fixed focus; keep objects at least 30 cm away.")
        if (lowRamDevice || totalRamBytes < 3L shl 30) add("Limited memory: use the Draft quality preset.")
        if (freeStorageBytes < 500L shl 20) add("Less than 500 MB of free storage; a scan may not fit.")
        fusionBenchmarkMs?.let { if (it > 60) add("Processing is slow on this device (%.0f ms per depth frame); reconstruction will take longer.".format(it)) }
    }
}

/**
 * Assesses the device. ARCore queries need an Activity context for Session creation; the session is
 * closed immediately after querying.
 */
object DeviceCapabilities {
    private const val TAG = "ScanForge.Capability"

    fun arCoreState(context: Context): ArCoreState {
        val a = ArCoreApk.getInstance().checkAvailability(context)
        return when {
            a.isTransient -> ArCoreState.CHECKING
            a == ArCoreApk.Availability.SUPPORTED_INSTALLED -> ArCoreState.SUPPORTED_INSTALLED
            a == ArCoreApk.Availability.SUPPORTED_APK_TOO_OLD || a == ArCoreApk.Availability.SUPPORTED_NOT_INSTALLED -> ArCoreState.NEEDS_INSTALL_OR_UPDATE
            a == ArCoreApk.Availability.UNSUPPORTED_DEVICE_NOT_CAPABLE -> ArCoreState.UNSUPPORTED
            else -> ArCoreState.UNKNOWN_ERROR
        }
    }

    fun assess(context: Context, storageDir: java.io.File, runBenchmark: Boolean = true): DeviceReport {
        var ar = arCoreState(context)
        // checkAvailability may be transient right after launch; retry briefly.
        var tries = 0
        while (ar == ArCoreState.CHECKING && tries++ < 10) { Thread.sleep(200); ar = arCoreState(context) }
        var depth: Boolean? = null; var raw: Boolean? = null; var hwDepth: Boolean? = null
        if (ar == ArCoreState.SUPPORTED_INSTALLED) {
            runCatching {
                val session = Session(context)
                try {
                    depth = session.isDepthModeSupported(Config.DepthMode.AUTOMATIC)
                    raw = session.isDepthModeSupported(Config.DepthMode.RAW_DEPTH_ONLY)
                    val filter = CameraConfigFilter(session).setDepthSensorUsage(EnumSet.of(CameraConfig.DepthSensorUsage.REQUIRE_AND_USE))
                    hwDepth = session.getSupportedCameraConfigs(filter).isNotEmpty()
                } finally {
                    session.close()
                }
            }.onFailure { Log.w(TAG, "ARCore session query failed", it) }
        }
        val am = context.systemService<ActivityManager>()
        val mem = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
        return DeviceReport(
            model = "${Build.MANUFACTURER} ${Build.MODEL}",
            androidVersion = Build.VERSION.RELEASE,
            sdkInt = Build.VERSION.SDK_INT,
            arCore = ar,
            depthApi = depth,
            rawDepthApi = raw,
            hardwareDepthSensor = hwDepth,
            camera = cameraInfo(context),
            totalRamBytes = mem.totalMem,
            availableRamBytes = mem.availMem,
            lowRamDevice = am.isLowRamDevice,
            freeStorageBytes = StatFs(storageDir.path).availableBytes,
            cpuCores = Runtime.getRuntime().availableProcessors(),
            fusionBenchmarkMs = if (runBenchmark) benchmarkFusion() else null,
        )
    }

    private fun cameraInfo(context: Context): CameraInfo {
        val cm = context.systemService<CameraManager>()
        return runCatching {
            val id = cm.cameraIdList.firstOrNull { cm.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK }
                ?: return CameraInfo(false, false, 0, false)
            val c = cm.getCameraCharacteristics(id)
            val minFocus = c.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE) ?: 0f
            val sizes = c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)?.getOutputSizes(ImageFormat.JPEG).orEmpty()
            val caps = c.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES) ?: IntArray(0)
            CameraInfo(
                hasBackCamera = true,
                autofocus = minFocus > 0f,
                maxJpegPixels = sizes.maxOfOrNull { it.width.toLong() * it.height } ?: 0,
                depthOutputCapability = CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_DEPTH_OUTPUT in caps,
            )
        }.getOrElse { CameraInfo(false, false, 0, false) }
    }

    /** Times real TSDF fusion of a synthetic plane at the Standard voxel size. */
    fun benchmarkFusion(): Double {
        val k = CameraIntrinsics(130.0, 130.0, 79.5, 59.5, 160, 120)
        val depth = DepthFrame(160, 120, ShortArray(160 * 120) { 500 }, null)
        val vol = TsdfVolume(0.004)
        vol.integrate(depth, k, Pose.IDENTITY) // warm-up and block allocation
        val t0 = System.nanoTime()
        repeat(3) { vol.integrate(depth, k, Pose.IDENTITY) }
        return (System.nanoTime() - t0) / 3e6
    }
}
