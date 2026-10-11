package app.scanforge.project

import app.scanforge.geometry.LengthUnit
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import java.util.Locale

/** Measurement report export (CSV and JSON). Values are calibrated and converted to the chosen unit. */
object MeasurementReport {
    private fun unitLabel(dimension: Int, unit: LengthUnit) = when (dimension) { 1 -> unit.symbol; 2 -> "${unit.symbol}^2"; else -> "${unit.symbol}^3" }

    fun csv(doc: ProjectDocument, unit: LengthUnit): String = buildString {
        append("project,measurement,type,value_index,value,unit,confidence,derived,scale_source,scale_factor,resolution_limit,model_version,reasons\n")
        for (m in doc.measurements) {
            MeasurementRecords.calibrated(doc, m).forEachIndexed { i, v ->
                val row = listOf(
                    doc.name, m.label, m.type.name.lowercase(), i.toString(),
                    String.format(Locale.ROOT, "%.6f", MeasurementRecords.convert(v, m.dimension, unit)), unitLabel(m.dimension, unit),
                    m.confidence, m.derived.toString(), doc.scale.source, String.format(Locale.ROOT, "%.6f", doc.scale.factor),
                    m.resolutionLimitMeters?.let { String.format(Locale.ROOT, "%.6f", unit.fromMeters(it)) } ?: "",
                    m.modelVersion.toString(), m.reasons.joinToString("; "),
                )
                append(row.joinToString(",") { csvEscape(it) }).append('\n')
            }
        }
    }

    private fun csvEscape(s: String) = if (s.any { it == ',' || it == '"' || it == '\n' }) "\"" + s.replace("\"", "\"\"") + "\"" else s

    fun json(doc: ProjectDocument, unit: LengthUnit): JsonObject = buildJsonObject {
        put("report", "scanforge.measurements")
        put("report_version", 1)
        put("project_id", doc.id)
        put("project_name", doc.name)
        put("unit", unit.symbol)
        putJsonArray("scale") {
            add(buildJsonObject {
                put("source", doc.scale.source)
                put("factor", doc.scale.factor)
                doc.scale.relativeSpread?.let { put("relative_spread", it) }
                put("calibrations", doc.scale.calibrations.count { it.active })
            })
        }
        put("disclaimer", "Values come from a 3D reconstruction. Accuracy depends on device, scanning conditions and calibration; " +
            "confidence levels are qualitative. Verify critical dimensions with a physical measurement.")
        putJsonArray("measurements") {
            for (m in doc.measurements) add(buildJsonObject {
                put("id", m.id); put("label", m.label); put("type", m.type.name.lowercase())
                put("values", JsonArray(MeasurementRecords.calibrated(doc, m).map { JsonPrimitive(MeasurementRecords.convert(it, m.dimension, unit)) }))
                put("value_unit", unitLabel(m.dimension, unit))
                put("confidence", m.confidence); put("derived", m.derived)
                m.resolutionLimitMeters?.let { put("resolution_limit", unit.fromMeters(it)) }
                put("reasons", JsonArray(m.reasons.map(::JsonPrimitive)))
                put("model_version", m.modelVersion)
            })
        }
    }
}
