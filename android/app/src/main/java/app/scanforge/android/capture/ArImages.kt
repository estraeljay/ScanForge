package app.scanforge.android.capture

import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import android.media.Image
import app.scanforge.reconstruction.DepthFrame
import java.io.ByteArrayOutputStream
import java.nio.ByteOrder

/** Copies of ARCore images, made on the GL thread so the ARCore images can be closed immediately. */
object ArImages {

    /**
     * Copies Frame.acquireRawDepthImage16Bits() (16-bit unsigned millimeters, all 16 bits used) and
     * Frame.acquireRawDepthConfidenceImage() (8-bit, 0 = lowest confidence) into a [DepthFrame].
     */
    fun copyDepth(depth: Image, confidence: Image?): DepthFrame {
        val w = depth.width; val h = depth.height
        val plane = depth.planes[0]
        val buf = plane.buffer.order(ByteOrder.LITTLE_ENDIAN)
        val rowStride = plane.rowStride; val pixelStride = plane.pixelStride
        val out = ShortArray(w * h)
        for (y in 0 until h) for (x in 0 until w) out[y * w + x] = buf.getShort(y * rowStride + x * pixelStride)
        val conf = confidence?.takeIf { it.width == w && it.height == h }?.let { c ->
            val p = c.planes[0]; val b = p.buffer
            ByteArray(w * h).also { arr -> for (y in 0 until h) for (x in 0 until w) arr[y * w + x] = b.get(y * p.rowStride + x * p.pixelStride) }
        }
        return DepthFrame(w, h, out, conf)
    }

    /** Copies a YUV_420_888 image to NV21, handling arbitrary row and pixel strides. */
    fun toNv21(image: Image): ByteArray {
        require(image.format == ImageFormat.YUV_420_888) { "Unexpected camera image format ${image.format}" }
        val w = image.width; val h = image.height
        val out = ByteArray(w * h * 3 / 2)
        val y = image.planes[0]; val u = image.planes[1]; val v = image.planes[2]
        val yBuf = y.buffer
        var o = 0
        for (row in 0 until h) {
            yBuf.position(row * y.rowStride)
            if (y.pixelStride == 1) { yBuf.get(out, o, w); o += w }
            else for (col in 0 until w) out[o++] = yBuf.get(row * y.rowStride + col * y.pixelStride)
        }
        val uBuf = u.buffer; val vBuf = v.buffer
        for (row in 0 until h / 2) for (col in 0 until w / 2) {
            out[o++] = vBuf.get(row * v.rowStride + col * v.pixelStride)
            out[o++] = uBuf.get(row * u.rowStride + col * u.pixelStride)
        }
        return out
    }

    fun nv21ToJpeg(nv21: ByteArray, width: Int, height: Int, quality: Int): ByteArray {
        val out = ByteArrayOutputStream(width * height / 4)
        YuvImage(nv21, ImageFormat.NV21, width, height, null).compressToJpeg(Rect(0, 0, width, height), quality, out)
        return out.toByteArray()
    }

    /** Mean luma (0..1) on a sparse grid, used as an exposure check. */
    fun meanLuma(nv21: ByteArray, width: Int, height: Int): Double {
        var sum = 0L; var n = 0
        for (yy in 0 until height step 8) for (xx in 0 until width step 8) { sum += nv21[yy * width + xx].toInt() and 0xff; n++ }
        return sum / (n * 255.0)
    }
}
