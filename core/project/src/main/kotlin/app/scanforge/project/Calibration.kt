package app.scanforge.project

import app.scanforge.geometry.ConfidenceLevel
import app.scanforge.geometry.LengthUnit
import app.scanforge.geometry.MeasurementQuality
import java.util.UUID
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Scale calibration. One reference corrects the global scale only; local reconstruction errors remain.
 * With several references the factor is their mean and the relative spread is reported as an
 * empirical consistency indicator.
 */
object Calibration {
    fun addReference(
        doc: ProjectDocument,
        method: CalibrationMethod,
        measuredMeters: Double,
        referenceMeters: Double,
        points: List<List<Double>>,
        now: String,
        note: String? = null,
    ): ProjectDocument {
        require(measuredMeters > 0 && referenceMeters > 0) { "Lengths must be positive" }
        val factor = referenceMeters / measuredMeters
        require(factor in 0.2..5.0) { "Reference implies a scale change of %.2f×; check the selected points and units".format(factor) }
        val record = CalibrationRecord(UUID.randomUUID().toString(), method, measuredMeters, referenceMeters, points, note, true, now)
        return withCalibrations(doc, doc.scale.calibrations + record)
    }

    fun setActive(doc: ProjectDocument, id: String, active: Boolean) =
        withCalibrations(doc, doc.scale.calibrations.map { if (it.id == id) it.copy(active = active) else it })

    private fun withCalibrations(doc: ProjectDocument, list: List<CalibrationRecord>): ProjectDocument {
        val active = list.filter { it.active }
        if (active.isEmpty()) {
            val base = if (doc.capture?.mode == "photo") "unscaled" else "device_tracking"
            return doc.copy(scale = ScaleInfo(source = base, factor = 1.0, calibrations = list))
        }
        val factors = active.map { it.factor }
        val mean = factors.average()
        val spread = if (factors.size >= 2) sqrt(factors.sumOf { (it - mean).pow(2) } / (factors.size - 1)) / mean else null
        val source = if (active.any { it.method == CalibrationMethod.PRINTED_MARKER }) "printed_marker" else "reference_length"
        return doc.copy(scale = ScaleInfo(source, mean, spread, list))
    }
}

object MeasurementRecords {
    fun create(
        doc: ProjectDocument,
        type: app.scanforge.project.MeasurementType,
        label: String,
        points: List<List<Double>>,
        rawValues: List<Double>,
        dimension: Int,
        derived: Boolean,
        quality: MeasurementQuality,
        now: String,
    ) = MeasurementRecord(
        id = UUID.randomUUID().toString(),
        type = type,
        label = label,
        points = points,
        rawValues = rawValues,
        dimension = dimension,
        derived = derived,
        modelVersion = doc.model.currentVersion,
        confidence = quality.level.name.lowercase(),
        reasons = quality.reasons,
        resolutionLimitMeters = quality.resolutionLimitMeters,
        createdAt = now,
    )

    /** Applies the current scale factor: lengths ×s, areas ×s², volumes ×s³. */
    fun calibrated(doc: ProjectDocument, m: MeasurementRecord): List<Double> = m.rawValues.map { it * doc.scale.factor.pow(m.dimension) }

    fun format(value: Double, dimension: Int, unit: LengthUnit): String = when (dimension) {
        1 -> unit.format(value)
        2 -> "%.${unit.defaultDecimals}f %s²".format(java.util.Locale.ROOT, unit.areaFromSquareMeters(value), unit.symbol)
        else -> "%.${unit.defaultDecimals}f %s³".format(java.util.Locale.ROOT, unit.volumeFromCubicMeters(value), unit.symbol)
    }

    fun convert(value: Double, dimension: Int, unit: LengthUnit) = when (dimension) {
        1 -> unit.fromMeters(value); 2 -> unit.areaFromSquareMeters(value); else -> unit.volumeFromCubicMeters(value)
    }

    fun confidenceLevel(m: MeasurementRecord) = ConfidenceLevel.valueOf(m.confidence.uppercase())
}
