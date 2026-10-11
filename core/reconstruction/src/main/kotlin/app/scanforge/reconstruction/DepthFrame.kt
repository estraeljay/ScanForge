package app.scanforge.reconstruction

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * A depth image in millimeters (0 = invalid) with an optional per-pixel confidence (0..255),
 * matching ARCore's Raw Depth API outputs (DEPTH16 millimeters + 8-bit confidence).
 */
class DepthFrame(val width: Int, val height: Int, val depthMm: ShortArray, val confidence: ByteArray?) {
    init {
        require(depthMm.size == width * height)
        require(confidence == null || confidence.size == width * height)
    }

    fun depthMetersAt(u: Int, v: Int): Double = (depthMm[v * width + u].toInt() and 0xffff) / 1000.0
    fun confidenceAt(u: Int, v: Int): Double = confidence?.let { (it[v * width + u].toInt() and 0xff) / 255.0 } ?: 1.0
}

/**
 * SFD1 ("ScanForge depth") file codec: little-endian
 * `magic "SFD1" | u16 width | u16 height | u8 flags (bit0 = has confidence) | u8 reserved[3] |
 *  u16 depth_mm[w*h] | u8 confidence[w*h]?`
 * Chosen over 16-bit PNG because Android's Bitmap API cannot encode 16-bit grayscale.
 */
object DepthFrameCodec {
    private val MAGIC = byteArrayOf('S'.code.toByte(), 'F'.code.toByte(), 'D'.code.toByte(), '1'.code.toByte())

    fun write(frame: DepthFrame, out: OutputStream) {
        val n = frame.width * frame.height
        val buf = ByteBuffer.allocate(12 + n * 2 + (if (frame.confidence != null) n else 0)).order(ByteOrder.LITTLE_ENDIAN)
        buf.put(MAGIC)
        buf.putShort(frame.width.toShort()); buf.putShort(frame.height.toShort())
        buf.put((if (frame.confidence != null) 1 else 0).toByte()); buf.put(ByteArray(3))
        for (d in frame.depthMm) buf.putShort(d)
        frame.confidence?.let { buf.put(it) }
        DataOutputStream(out).write(buf.array())
    }

    fun read(input: InputStream): DepthFrame {
        val din = DataInputStream(input)
        val header = ByteArray(12).also { din.readFully(it) }
        require(header.copyOfRange(0, 4).contentEquals(MAGIC)) { "Not an SFD1 depth file" }
        val hb = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
        val w = hb.getShort(4).toInt() and 0xffff
        val h = hb.getShort(6).toInt() and 0xffff
        val hasConf = (header[8].toInt() and 1) != 0
        require(w in 1..8192 && h in 1..8192) { "Invalid depth dimensions ${w}x$h" }
        val n = w * h
        val raw = ByteArray(n * 2).also { din.readFully(it) }
        val depth = ShortArray(n)
        ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(depth)
        val conf = if (hasConf) ByteArray(n).also { din.readFully(it) } else null
        return DepthFrame(w, h, depth, conf)
    }
}

/** 8-bit sRGB image, row-major, 3 bytes per pixel. */
class RgbImage(val width: Int, val height: Int, val rgb: ByteArray) {
    init { require(rgb.size == width * height * 3) }

    fun sample(u: Double, v: Double, out: IntArray): Boolean {
        val x = Math.round(u).toInt(); val y = Math.round(v).toInt()
        if (x < 0 || y < 0 || x >= width || y >= height) return false
        val o = (y * width + x) * 3
        out[0] = rgb[o].toInt() and 0xff; out[1] = rgb[o + 1].toInt() and 0xff; out[2] = rgb[o + 2].toInt() and 0xff
        return true
    }
}

/** Platform image decoding (BitmapFactory on Android, ImageIO on desktop). */
fun interface ImageDecoder {
    fun decode(bytes: ByteArray): RgbImage
}
