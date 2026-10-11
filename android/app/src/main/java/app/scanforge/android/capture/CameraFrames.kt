package app.scanforge.android.capture

import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.Build
import app.scanforge.reconstruction.CameraIntrinsics
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer

/** One YUV_420_888 plane as delivered by CameraX/Camera2 (buffer with arbitrary strides). */
class Plane(val buffer: ByteBuffer, val rowStride: Int, val pixelStride: Int)

/** Conversions for plain camera frames (marker mode); independent of ARCore. */
object CameraFrames {
    /** Copies the luma plane into a tightly packed grayscale array. */
    fun luma(y: Plane, width: Int, height: Int, out: ByteArray = ByteArray(width * height)): ByteArray {
        val b = y.buffer
        for (row in 0 until height) {
            if (y.pixelStride == 1) { b.position(row * y.rowStride); b.get(out, row * width, width) }
            else for (col in 0 until width) out[row * width + col] = b.get(row * y.rowStride + col * y.pixelStride)
        }
        return out
    }

    fun toNv21(y: Plane, u: Plane, v: Plane, width: Int, height: Int): ByteArray {
        val out = ByteArray(width * height * 3 / 2)
        luma(y, width, height, out)
        var o = width * height
        for (row in 0 until height / 2) for (col in 0 until width / 2) {
            out[o++] = v.buffer.get(row * v.rowStride + col * v.pixelStride)
            out[o++] = u.buffer.get(row * u.rowStride + col * u.pixelStride)
        }
        return out
    }

    fun jpeg(nv21: ByteArray, width: Int, height: Int, quality: Int = 92): ByteArray =
        ByteArrayOutputStream(width * height / 3).also { YuvImage(nv21, ImageFormat.NV21, width, height, null).compressToJpeg(Rect(0, 0, width, height), quality, it) }.toByteArray()

    /**
     * Sharpness score: variance of a 4-neighbour Laplacian on every 2nd pixel. Motion-blurred frames score low and are
     * not kept as photos.
     */
    fun sharpness(gray: ByteArray, width: Int, height: Int): Double {
        var s = 0.0; var s2 = 0.0; var n = 0
        for (yy in 2 until height - 2 step 2) for (xx in 2 until width - 2 step 2) {
            val i = yy * width + xx
            val l = 4 * (gray[i].toInt() and 0xff) - (gray[i - 1].toInt() and 0xff) - (gray[i + 1].toInt() and 0xff) -
                (gray[i - width].toInt() and 0xff) - (gray[i + width].toInt() and 0xff)
            s += l; s2 += l.toDouble() * l; n++
        }
        val m = s / n
        return s2 / n - m * m
    }

    /**
     * Initial intrinsics for the rear camera at [width]×[height] (sensor orientation). Prefers the factory calibration
     * (LENS_INTRINSIC_CALIBRATION), else focal length / sensor size. Reconstruction refines the focal length from the
     * board, so this only needs to be approximately right.
     */
    fun intrinsicsGuess(cm: CameraManager, width: Int, height: Int): CameraIntrinsics {
        val id = cm.cameraIdList.firstOrNull { cm.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK }
        val c = id?.let { cm.getCameraCharacteristics(it) }
        val cx = (width - 1) / 2.0; val cy = (height - 1) / 2.0
        if (c != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val cal = c.get(CameraCharacteristics.LENS_INTRINSIC_CALIBRATION)
            val active = c.get(CameraCharacteristics.SENSOR_INFO_PRE_CORRECTION_ACTIVE_ARRAY_SIZE)
            if (cal != null && active != null && cal[0] > 0f && active.width() > 0) {
                val f = cal[0].toDouble() * width / active.width()
                return CameraIntrinsics(f, f, cx, cy, width, height)
            }
        }
        val focal = c?.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.firstOrNull()
        val sensor = c?.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)
        val f = if (focal != null && sensor != null && sensor.width > 0f) focal.toDouble() / sensor.width * width else width * 0.8
        return CameraIntrinsics(f, f, cx, cy, width, height)
    }
}
