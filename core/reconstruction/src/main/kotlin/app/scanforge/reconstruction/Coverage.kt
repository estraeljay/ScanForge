package app.scanforge.reconstruction

import app.scanforge.geometry.Pose
import app.scanforge.geometry.Vec3
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.math.PI
import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.atan2

/** Guidance derived from measured view coverage. */
enum class CoverageHint {
    /** The low ring (0–30° elevation) is incomplete: keep walking around the object. */
    CONTINUE_AROUND,
    /** The mid ring (30–60°) is incomplete: circle again from a higher angle. */
    RAISE_ANGLE,
    /** Few views from above (60–90°). */
    CAPTURE_TOP,
    /** All required bands are covered (the underside is optional for resting objects). */
    COMPLETE,
}

/**
 * Measures which directions around the object have actually been observed. The sphere around the
 * object center is split into azimuth × elevation bins; a frame counts for a bin only when the camera is
 * tracking and actually looking at the center (off-axis angle below [maxOffAxisDeg]).
 */
class ViewCoverage(
    val center: Vec3,
    val azimuthBins: Int = 12,
    /** Elevation bin edges in degrees, ascending. The first bin is "below the horizon". */
    val elevationEdgesDeg: DoubleArray = doubleArrayOf(-60.0, 0.0, 30.0, 60.0, 90.01),
    val maxOffAxisDeg: Double = 35.0,
    val minViewsPerBin: Int = 2,
) {
    val elevationBins = elevationEdgesDeg.size - 1
    private val counts = IntArray(azimuthBins * elevationBins)

    fun azimuthOf(p: Vec3): Double { val d = p - center; return (atan2(d.z, d.x) + 2 * PI) % (2 * PI) }
    fun elevationDegOf(p: Vec3): Double { val d = (p - center).normalized(); return Math.toDegrees(asin(d.y.coerceIn(-1.0, 1.0))) }

    /** Returns true when the view was counted. */
    fun addView(cameraPose: Pose): Boolean {
        val eye = cameraPose.translation
        val toCenter = center - eye
        if (toCenter.length() < 1e-6) return false
        val forward = cameraPose.rotateVector(Vec3(0.0, 0.0, -1.0))
        val off = Math.toDegrees(acos((forward dot toCenter.normalized()).coerceIn(-1.0, 1.0)))
        if (off > maxOffAxisDeg) return false
        val bin = binOf(eye) ?: return false
        counts[bin]++
        return true
    }

    private fun binOf(eye: Vec3): Int? {
        val el = elevationDegOf(eye)
        val e = (0 until elevationBins).firstOrNull { el >= elevationEdgesDeg[it] && el < elevationEdgesDeg[it + 1] } ?: return null
        val a = ((azimuthOf(eye) / (2 * PI)) * azimuthBins).toInt().coerceIn(0, azimuthBins - 1)
        return e * azimuthBins + a
    }

    fun isObserved(elevationBin: Int, azimuthBin: Int) = counts[elevationBin * azimuthBins + azimuthBin] >= minViewsPerBin

    /** Fraction of bins observed, by default excluding the below-horizon band (object resting on a surface). */
    fun observedFraction(includeUnderside: Boolean = false): Double {
        val from = if (includeUnderside) 0 else 1
        var obs = 0; var total = 0
        for (e in from until elevationBins) for (a in 0 until azimuthBins) { total++; if (isObserved(e, a)) obs++ }
        return obs.toDouble() / total
    }

    fun bandFraction(elevationBin: Int): Double = (0 until azimuthBins).count { isObserved(elevationBin, it) }.toDouble() / azimuthBins

    /** Most useful next action, based only on what has been measured. */
    fun hint(): CoverageHint = when {
        bandFraction(1) < 0.75 -> CoverageHint.CONTINUE_AROUND
        bandFraction(2) < 0.5 -> CoverageHint.RAISE_ANGLE
        bandFraction(elevationBins - 1) < 0.25 -> CoverageHint.CAPTURE_TOP
        else -> CoverageHint.COMPLETE
    }

    /**
     * Signed angle (radians, + = counter-clockwise seen from above) from the camera's current azimuth to the
     * nearest unobserved azimuth bin in the camera's elevation band, or null when that band is complete.
     */
    fun nearestGap(cameraPosition: Vec3): Double? {
        val el = elevationDegOf(cameraPosition)
        val e = (0 until elevationBins).firstOrNull { el >= elevationEdgesDeg[it] && el < elevationEdgesDeg[it + 1] } ?: return null
        val here = azimuthOf(cameraPosition)
        var best: Double? = null
        for (a in 0 until azimuthBins) {
            if (isObserved(e, a)) continue
            val mid = (a + 0.5) / azimuthBins * 2 * PI
            var d = mid - here
            while (d > PI) d -= 2 * PI
            while (d < -PI) d += 2 * PI
            if (best == null || kotlin.math.abs(d) < kotlin.math.abs(best)) best = d
        }
        return best
    }

    fun snapshot(): IntArray = counts.copyOf()
}

/** Per-vertex evidence class stored with reconstructions for coverage visualization. */
object EvidenceClass {
    const val WEAK: Byte = 1
    const val STRONG: Byte = 2
}

@Serializable
data class CoverageReport(
    @SerialName("view_fraction") val viewFraction: Double,
    @SerialName("top_view_fraction") val topViewFraction: Double,
    @SerialName("views_counted") val viewsCounted: Int,
    @SerialName("surface_vertices") val surfaceVertices: Int,
    @SerialName("strong_vertex_fraction") val strongVertexFraction: Double,
    @SerialName("weak_vertex_fraction") val weakVertexFraction: Double,
    @SerialName("hole_count") val holeCount: Int,
    @SerialName("boundary_edges") val boundaryEdges: Int,
    val watertight: Boolean,
)
