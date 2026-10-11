package app.scanforge.geometry

/**
 * Qualitative measurement confidence. ScanForge deliberately does not output a numeric accuracy
 * percentage: the factors below are reported instead, and a numeric uncertainty is only shown when it
 * can be justified (see [MeasurementQuality.resolutionLimitMeters]).
 */
enum class ConfidenceLevel { HIGH, MEDIUM, LOW, UNKNOWN }

/** Where the scale of a model comes from. */
enum class ScaleSource {
    /** Metric scale from ARCore visual-inertial tracking and depth (not independently verified). */
    DEVICE_TRACKING,
    /** User measured a known reference length on the model. */
    REFERENCE_LENGTH,
    /** Printed marker of documented size was detected / measured. */
    PRINTED_MARKER,
    /** Imported or synthetic data with an externally defined scale. */
    EXTERNAL,
    /** Photogrammetry without metric information: relative units only. */
    UNSCALED,
}

data class MeasurementEvidence(
    val scaleSource: ScaleSource,
    /** Mean depth confidence (0..1) of observations near the measured points, if known. */
    val localDepthConfidence: Double?,
    /** Number of independent views that observed the measured region, if known. */
    val observationCount: Int?,
    /** True if any endpoint lies on or next to a hole / unobserved region. */
    val nearMissingGeometry: Boolean,
    /** Reconstruction resolution (voxel size) in meters, if known. */
    val reconstructionResolution: Double?,
    /** Whether the value was measured directly between surface points or derived from a fitted model. */
    val derived: Boolean,
)

data class MeasurementQuality(
    val level: ConfidenceLevel,
    /** Human-readable reasons, most important first. */
    val reasons: List<String>,
    /**
     * Lower bound on uncertainty imposed by reconstruction resolution. This is a resolution limit,
     * not an accuracy claim; real error is typically larger.
     */
    val resolutionLimitMeters: Double?,
)

object MeasurementConfidence {
    fun assess(lengthMeters: Double, e: MeasurementEvidence): MeasurementQuality {
        val reasons = mutableListOf<String>()
        var score = 3 // 3=HIGH, 2=MEDIUM, 1=LOW
        when (e.scaleSource) {
            ScaleSource.UNSCALED -> return MeasurementQuality(
                ConfidenceLevel.UNKNOWN,
                listOf("Model has no metric scale; calibrate with a known reference length."),
                null,
            )
            ScaleSource.DEVICE_TRACKING -> { score = minOf(score, 2); reasons += "Scale from device tracking, not calibrated against a reference." }
            ScaleSource.REFERENCE_LENGTH, ScaleSource.PRINTED_MARKER -> reasons += "Scale calibrated against a reference; local reconstruction errors may remain."
            ScaleSource.EXTERNAL -> reasons += "Scale defined by imported data."
        }
        if (e.nearMissingGeometry) { score = 1; reasons += "Measurement touches missing or weak geometry." }
        e.localDepthConfidence?.let {
            if (it < 0.3) { score = 1; reasons += "Low depth confidence near the measured points." }
            else if (it < 0.6) { score = minOf(score, 2); reasons += "Moderate depth confidence near the measured points." }
        }
        e.observationCount?.let { if (it < 3) { score = minOf(score, 1); reasons += "Region observed from fewer than 3 views." } }
        e.reconstructionResolution?.let { res ->
            if (lengthMeters < res * 10) { score = minOf(score, 1); reasons += "Length is less than 10× the reconstruction resolution." }
        }
        if (e.derived) { score = minOf(score, 2); reasons += "Value derived from a fitted shape, not measured directly." }
        val level = when (score) { 3 -> ConfidenceLevel.HIGH; 2 -> ConfidenceLevel.MEDIUM; else -> ConfidenceLevel.LOW }
        return MeasurementQuality(level, reasons, e.reconstructionResolution)
    }
}
