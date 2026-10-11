package app.scanforge.reconstruction

import app.scanforge.geometry.Vec3
import app.scanforge.markers.BoardLayout
import app.scanforge.markers.BoardSpec
import kotlin.test.Test

/** Not an assertion test: prints MVS depth accuracy against ground truth (run with -Dmvs.diag=true). */
class MvsDiagnostics {
    @Test fun depthAccuracy() {
        if (System.getProperty("mvs.diag") != "true") return
        val k = CameraIntrinsics(520.0, 520.0, 319.5, 239.5, 640, 480)
        val board = BoardLayout(BoardSpec.A3)
        val box = SdfShape.Box(Vec3(0.0, 0.035, 0.0), Vec3(0.05, 0.035, 0.025))
        val preset = QualityPreset.valueOf(System.getProperty("mvs.preset") ?: "DRAFT")
        val cap = SyntheticMarkerCapture.generate(listOf(box), board, k, Vec3(0.0, 0.03, 0.0), 0.5, listOf(35.0, 55.0, 75.0), 12, preset = preset)
        val samples = (System.getProperty("mvs.samples") ?: "96").toInt()
        val t0 = System.nanoTime()
        val params = MvsParams(depthSamples = samples,
            minNcc = (System.getProperty("mvs.ncc") ?: "0.55").toDouble(),
            neighbors = (System.getProperty("mvs.nb") ?: "4").toInt(),
            windowRadius = (System.getProperty("mvs.win") ?: "2").toInt())
        println("params $params")
        val prep = MarkerReconstruction.prepare(cap, params = params)
        println("preset $preset samples $samples time ${(System.nanoTime() - t0) / 1e9}")
        val scene = SyntheticScene(listOf(box), SdfShape.Ground(0.0))
        var n = 0; var bad = 0; val errs = ArrayList<Double>(); var objPts = 0; var objBad = 0; var total = 0; var objVisible = 0
        for (f in prep.source.frames) {
            val d = prep.source.loadDepth(f)!!; val kd = f.depthIntrinsics!!; val pose = f.pose
            total += d.width * d.height
            for (v in 0 until d.height) for (u in 0 until d.width) {
                run {
                    val dir0 = pose.rotateVector(kd.unproject(u.toDouble(), v.toDouble(), 1.0).normalized())
                    val t0 = scene.trace(pose.translation, dir0)
                    if (t0 != null && box.distance(pose.translation + dir0 * t0) < 1e-3) objVisible++
                }
                val z = d.depthMetersAt(u, v); if (z <= 0) continue
                val dir = pose.rotateVector(kd.unproject(u.toDouble(), v.toDouble(), 1.0).normalized())
                val t = scene.trace(pose.translation, dir) ?: continue
                val zTrue = t * -kd.unproject(u.toDouble(), v.toDouble(), 1.0).normalized().z
                val e = z - zTrue
                n++; errs += kotlin.math.abs(e); if (kotlin.math.abs(e) > 0.01) bad++
                val p = pose.translation + dir * t
                if (box.distance(p) < 1e-3) { objPts++; if (kotlin.math.abs(e) > 0.005) objBad++ }
            }
        }
        errs.sort()
        println("MVS valid ${"%.1f".format(100.0 * n / total)}% | median |err| ${"%.2f".format(errs[errs.size / 2] * 1000)} mm | p90 ${"%.2f".format(errs[errs.size * 9 / 10] * 1000)} mm | >1cm ${"%.1f".format(100.0 * bad / n)}% | object coverage ${"%.1f".format(100.0 * objPts / maxOf(1, objVisible))}% ($objPts px), >5mm ${"%.1f".format(100.0 * objBad / maxOf(1, objPts))}%")
    }
}
