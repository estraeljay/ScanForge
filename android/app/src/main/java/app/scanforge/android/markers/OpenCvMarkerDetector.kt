package app.scanforge.android.markers

import app.scanforge.markers.BoardLayout
import app.scanforge.markers.MarkerObservation
import org.opencv.android.OpenCVLoader
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.objdetect.ArucoDetector
import org.opencv.objdetect.DetectorParameters
import org.opencv.objdetect.Objdetect

/**
 * ArUco detection with OpenCV (the only OpenCV use in the app). Everything after detection — pose, focal length,
 * stereo, fusion — runs in the tested ScanForge core. Not thread-safe: use from a single analysis thread.
 */
class OpenCvMarkerDetector {
    private val detector: ArucoDetector
    private var mat: Mat? = null

    init {
        check(ensureLoaded()) { "OpenCV native library could not be loaded on this device" }
        val params = DetectorParameters()
        params.set_cornerRefinementMethod(Objdetect.CORNER_REFINE_SUBPIX)
        detector = ArucoDetector(Objdetect.getPredefinedDictionary(Objdetect.DICT_4X4_250), params)
    }

    /** Detects markers in an 8-bit grayscale image (row-major, no padding). Only ids on [board] are returned. */
    fun detect(gray: ByteArray, width: Int, height: Int, board: BoardLayout): List<MarkerObservation> {
        val m = mat?.takeIf { it.cols() == width && it.rows() == height } ?: Mat(height, width, CvType.CV_8UC1).also { mat?.release(); mat = it }
        m.put(0, 0, gray)
        val corners = ArrayList<Mat>(); val ids = Mat()
        try {
            detector.detectMarkers(m, corners, ids)
            val out = ArrayList<MarkerObservation>(corners.size)
            val c = FloatArray(8)
            for (i in corners.indices) {
                val id = ids.get(i, 0)[0].toInt()
                if (!board.contains(id)) continue
                corners[i].get(0, 0, c)
                out += MarkerObservation(id, c.map { it.toDouble() })
            }
            return out
        } finally {
            corners.forEach { it.release() }; ids.release()
        }
    }

    fun close() { mat?.release(); mat = null }

    companion object {
        @Volatile private var loaded: Boolean? = null
        fun ensureLoaded(): Boolean = loaded ?: synchronized(this) { loaded ?: OpenCVLoader.initLocal().also { loaded = it } }
    }
}
