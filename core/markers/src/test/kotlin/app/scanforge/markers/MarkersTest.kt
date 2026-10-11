package app.scanforge.markers

import app.scanforge.geometry.Pose
import app.scanforge.geometry.Quat
import app.scanforge.geometry.Vec3
import nu.pattern.OpenCV
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.objdetect.ArucoDetector
import org.opencv.objdetect.DetectorParameters
import org.opencv.objdetect.Objdetect
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class MarkersTest {
    private fun close(e: Double, a: Double, eps: Double, what: String) = assertTrue(abs(e - a) <= eps, "$what: expected $e ± $eps, got $a")

    private fun orbitPose(target: Vec3, r: Double, elevDeg: Double, azDeg: Double): Pose {
        val e = Math.toRadians(elevDeg); val a = Math.toRadians(azDeg)
        return Pose.lookAt(target + Vec3(r * cos(e) * cos(a), r * sin(e), r * cos(e) * sin(a)), target)
    }

    private fun synthObs(board: BoardLayout, pose: Pose, k: Pinhole, noise: Double, rnd: Random, w: Int, h: Int): List<MarkerObservation> =
        board.markers.mapNotNull { m ->
            val px = board.cornersWorld(m.id).map { PlanarPose.reproject(pose, k, it) ?: return@mapNotNull null }
            if (px.any { it.first < 0 || it.second < 0 || it.first > w - 1 || it.second > h - 1 }) return@mapNotNull null
            MarkerObservation(m.id, px.flatMap { listOf(it.first + rnd.nextDouble(-noise, noise), it.second + rnd.nextDouble(-noise, noise)) })
        }

    @Test fun dictionaryMatchesOpenCvExport() {
        // Marker 0 of DICT_4X4_250 (checked against cv2.aruco.generateImageMarker): first row 1011.
        assertEquals(listOf(true, false, true, true), (0 until 4).map { ArucoDictionary.bit(0, 0, it) })
        val codes = (0 until ArucoDictionary.SIZE).map { id -> (0 until 16).joinToString("") { if (ArucoDictionary.bit(id, it / 4, it % 4)) "1" else "0" } }
        assertEquals(250, codes.toSet().size)
    }

    @Test fun boardLayoutsAndCodes() {
        val a2 = BoardLayout(BoardSpec.A2)
        assertEquals(8 to 11, a2.cols to a2.rows)
        assertEquals(6 to 8, BoardLayout(BoardSpec.A3).let { it.cols to it.rows })
        assertEquals(5 to 6, BoardLayout(BoardSpec.A4).let { it.cols to it.rows })
        assertEquals(BoardSpec.A2, BoardSpec.fromCode(BoardSpec.A2.code))
        // Free area for an A3 footprint (297 × 420 mm) inside the A2 grid (390 × 540 mm).
        assertTrue(a2.gridWidthMm >= 297 && a2.gridHeightMm >= 420)
        val c = a2.cornersWorld(0)
        close(0.040, c[0].distanceTo(c[1]), 1e-12, "marker side"); close(0.0, c[0].y, 0.0, "on plane")
        assertTrue(c[0].x < c[1].x && c[1].z < c[2].z, "TL,TR,BR,BL with +X right, +Z down the page")
        // Printer scale correction scales world coordinates.
        close(0.040 * 0.98, BoardLayout(BoardSpec.A2, 0.98).cornersWorld(0).let { it[0].distanceTo(it[1]) }, 1e-12, "scaled side")
    }

    @Test fun pdfIsWellFormedAtTrueSize() {
        val pdf = String(BoardPdf.render(BoardLayout(BoardSpec.A3)), Charsets.US_ASCII)
        assertTrue(pdf.startsWith("%PDF-1.4") && pdf.trimEnd().endsWith("%%EOF"))
        assertTrue(pdf.contains("/MediaBox [0 0 841.890 1190.551]"), "A3 = 297 × 420 mm")
        val xref = pdf.substringAfterLast("startxref\n").trim().lines()[0].toInt()
        assertTrue(pdf.substring(xref).startsWith("xref"))
        // Every xref offset must point at its object header.
        val offsets = pdf.substring(xref).lines().drop(3).take(5).map { it.take(10).toInt() }
        offsets.forEachIndexed { i, off -> assertTrue(pdf.substring(off).startsWith("${i + 1} 0 obj"), "object ${i + 1}") }
    }

    @Test fun poseFromNoisyCornersAndOutlierRejection() {
        val board = BoardLayout(BoardSpec.A2)
        val k = Pinhole(1500.0, 1500.0, 959.5, 719.5)
        val rnd = Random(3)
        for ((elev, az) in listOf(30.0 to 0.0, 45.0 to 70.0, 60.0 to 200.0, 75.0 to 300.0)) {
            val truth = orbitPose(Vec3(0.0, 0.05, 0.0), 0.7, elev, az)
            val obs = synthObs(board, truth, k, 0.4, rnd, 1920, 1440).toMutableList()
            // One misdetected marker with corners 25 px off.
            obs[0] = obs[0].copy(corners = obs[0].corners.map { it + 25 })
            val est = assertNotNull(PlanarPose.estimate(obs, board, k))
            assertTrue(est.markersRejected >= 1, "outlier rejected")
            close(0.0, est.pose.translation.distanceTo(truth.translation), 0.002, "position @ $elev/$az")
            val dq = abs(est.pose.rotation.x * truth.rotation.x + est.pose.rotation.y * truth.rotation.y + est.pose.rotation.z * truth.rotation.z + est.pose.rotation.w * truth.rotation.w)
            close(0.0, Math.toDegrees(2 * kotlin.math.acos(dq.coerceAtMost(1.0))), 0.2, "rotation deg")
            assertTrue(est.rmsErrorPx < 0.6, "rms ${est.rmsErrorPx}")
        }
    }

    @Test fun focalLengthFromViews() {
        val board = BoardLayout(BoardSpec.A3)
        val k = Pinhole(1450.0, 1450.0, 959.5, 719.5)
        val rnd = Random(5)
        val views = listOf(35.0 to 10.0, 50.0 to 100.0, 65.0 to 190.0, 40.0 to 280.0, 55.0 to 330.0).map { (e, a) ->
            synthObs(board, orbitPose(Vec3.ZERO, 0.55, e, a), k, 0.3, rnd, 1920, 1440)
        }
        val r = assertNotNull(FocalEstimation.estimate(views, board, 1920, 1440))
        close(1450.0, r.focalPx, 1450 * 0.01, "focal")
    }

    @Test fun realOpenCvDetectionOnRenderedBoard() {
        OpenCV.loadLocally()
        val board = BoardLayout(BoardSpec.A3)
        val w = 1280; val h = 960
        val k = Pinhole(1000.0, 1000.0, (w - 1) / 2.0, (h - 1) / 2.0)
        val truth = orbitPose(Vec3(0.0, 0.0, 0.0), 0.55, 55.0, 35.0)
        // A box standing in the middle hides some markers, as a real object does.
        val gray = BoardRender.render(board, truth, k, w, h) { p -> abs(p.x) < 0.06 && abs(p.z) < 0.08 }
        val mat = Mat(h, w, CvType.CV_8UC1).apply { put(0, 0, gray) }
        val det = ArucoDetector(Objdetect.getPredefinedDictionary(Objdetect.DICT_4X4_250), DetectorParameters().apply { set_cornerRefinementMethod(Objdetect.CORNER_REFINE_SUBPIX) })
        val corners = ArrayList<Mat>(); val ids = Mat()
        det.detectMarkers(mat, corners, ids)
        val obs = corners.indices.map { i ->
            val c = FloatArray(8).also { corners[i].get(0, 0, it) }
            MarkerObservation(ids.get(i, 0)[0].toInt(), c.map { it.toDouble() })
        }
        assertTrue(obs.size >= 20, "detected ${obs.size} markers")
        assertTrue(obs.all { board.contains(it.id) }, "no foreign ids")
        val est = assertNotNull(PlanarPose.estimate(obs, board, k))
        close(0.0, est.pose.translation.distanceTo(truth.translation), 0.003, "position from real detections")
        assertTrue(est.rmsErrorPx < 1.0, "rms ${est.rmsErrorPx}")
        println("OpenCV ${org.opencv.core.Core.VERSION}: ${obs.size} markers, pos err ${"%.2f".format(est.pose.translation.distanceTo(truth.translation) * 1000)} mm, rms ${"%.2f".format(est.rmsErrorPx)} px, rejected ${est.markersRejected}")
    }
}
