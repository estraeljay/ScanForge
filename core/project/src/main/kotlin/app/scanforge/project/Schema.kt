package app.scanforge.project

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

/** Current project.json schema version. Bump on breaking changes and add a migration in [ProjectMigrations]. */
const val PROJECT_SCHEMA_VERSION = 1
const val PROJECT_SCHEMA_ID = "scanforge.project"

val ProjectJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    explicitNulls = false
    prettyPrint = true
}

@Serializable
enum class ProjectStatus {
    @SerialName("capturing") CAPTURING,
    @SerialName("captured") CAPTURED,
    @SerialName("reconstructing") RECONSTRUCTING,
    @SerialName("reconstructed") RECONSTRUCTED,
    @SerialName("failed") FAILED,
}

@Serializable
enum class ObjectCategory {
    @SerialName("small_product") SMALL_PRODUCT,
    @SerialName("household_object") HOUSEHOLD_OBJECT,
    @SerialName("furniture") FURNITURE,
    @SerialName("mechanical_component") MECHANICAL_COMPONENT,
    @SerialName("sculptural_object") SCULPTURAL_OBJECT,
    @SerialName("architectural_feature") ARCHITECTURAL_FEATURE,
    @SerialName("other") OTHER,
}

/**
 * Coordinate system of every geometry file and point stored in the project.
 * Right-handed, +Y up, −Z forward, meters (identical to ARCore world space and glTF).
 */
@Serializable
data class CoordinateSystem(
    val handedness: String = "right",
    val up: String = "+Y",
    val forward: String = "-Z",
    val unit: String = "m",
)

@Serializable
data class FileEntry(
    val role: String,
    val path: String,
    val format: String,
    val bytes: Long,
    val sha256: String,
)

@Serializable
data class CaptureInfo(
    val mode: String,
    @SerialName("quality_preset") val qualityPreset: String,
    @SerialName("frame_count") val frameCount: Int = 0,
    @SerialName("session_id") val sessionId: String? = null,
    /** "device" for real captures, "synthetic" for generated test data. */
    val source: String = "device",
    val device: Map<String, String> = emptyMap(),
    /** False after the user deleted source captures to save space (re-reconstruction impossible). */
    val retained: Boolean = true,
    @SerialName("duration_s") val durationSeconds: Double? = null,
)

@Serializable
data class ReconstructionInfo(
    val method: String,
    @SerialName("voxel_size_m") val voxelSizeMeters: Double? = null,
    @SerialName("frames_used") val framesUsed: Int = 0,
    val warnings: List<String> = emptyList(),
    /** Coverage report as produced by the reconstruction module. */
    val coverage: JsonElement? = null,
    @SerialName("completed_at") val completedAt: String? = null,
)

/** One version of the model geometry. Version 1 is the untouched reconstruction output. */
@Serializable
data class ModelVersion(
    val version: Int,
    val file: FileEntry,
    /** Operation that produced this version from the previous one, e.g. "remove_small_components". */
    val operation: String,
    val parameters: Map<String, String> = emptyMap(),
    @SerialName("vertex_count") val vertexCount: Int,
    @SerialName("triangle_count") val triangleCount: Int,
    @SerialName("bounds_min") val boundsMin: List<Double>,
    @SerialName("bounds_max") val boundsMax: List<Double>,
    @SerialName("has_vertex_colors") val hasVertexColors: Boolean,
    val watertight: Boolean,
    @SerialName("hole_count") val holeCount: Int,
    @SerialName("created_at") val createdAt: String,
)

@Serializable
data class ModelInfo(
    val versions: List<ModelVersion> = emptyList(),
    /** Index into [versions] of the version currently shown/exported (undo/redo moves this pointer). */
    @SerialName("current_version") val currentVersion: Int = 0,
    @SerialName("has_texture") val hasTexture: Boolean = false,
    /** Per-vertex evidence classes (1 = weak, 2 = strong) for version 1, raw bytes. */
    @SerialName("evidence_file") val evidenceFile: String? = null,
) {
    val current: ModelVersion? get() = versions.getOrNull(currentVersion - 1)
    val source: ModelVersion? get() = versions.firstOrNull()
}

@Serializable
enum class CalibrationMethod {
    @SerialName("reference_length") REFERENCE_LENGTH,
    @SerialName("printed_marker") PRINTED_MARKER,
    @SerialName("user_entered") USER_ENTERED,
    @SerialName("device_depth") DEVICE_DEPTH,
}

@Serializable
data class CalibrationRecord(
    val id: String,
    val method: CalibrationMethod,
    /** Length measured on the uncalibrated model, meters. */
    @SerialName("measured_m") val measuredMeters: Double,
    /** True physical length entered by the user / defined by the marker, meters. */
    @SerialName("reference_m") val referenceMeters: Double,
    val points: List<List<Double>> = emptyList(),
    val note: String? = null,
    val active: Boolean = true,
    @SerialName("created_at") val createdAt: String,
) {
    val factor get() = referenceMeters / measuredMeters
}

@Serializable
data class ScaleInfo(
    /** One of geometry.ScaleSource names in snake case, e.g. "device_tracking". */
    val source: String = "device_tracking",
    val factor: Double = 1.0,
    /** Relative spread (std/mean) between active calibrations; null with fewer than two. */
    @SerialName("relative_spread") val relativeSpread: Double? = null,
    val calibrations: List<CalibrationRecord> = emptyList(),
)

@Serializable
enum class MeasurementType {
    @SerialName("distance") DISTANCE,
    @SerialName("bounding_box") BOUNDING_BOX,
    @SerialName("oriented_bounding_box") ORIENTED_BOUNDING_BOX,
    @SerialName("diameter") DIAMETER,
    @SerialName("circumference") CIRCUMFERENCE,
    @SerialName("surface_area") SURFACE_AREA,
    @SerialName("volume") VOLUME,
}

@Serializable
data class MeasurementRecord(
    val id: String,
    val type: MeasurementType,
    val label: String,
    /** Points in model space (uncalibrated meters), on the model version recorded in [modelVersion]. */
    val points: List<List<Double>> = emptyList(),
    /** Values in uncalibrated SI units (m, m², m³). Calibrated value = raw × factor^dimension. */
    @SerialName("raw_values") val rawValues: List<Double>,
    val dimension: Int = 1,
    val derived: Boolean = false,
    @SerialName("model_version") val modelVersion: Int,
    val confidence: String,
    val reasons: List<String> = emptyList(),
    @SerialName("resolution_limit_m") val resolutionLimitMeters: Double? = null,
    @SerialName("created_at") val createdAt: String,
)

@Serializable
data class TransferRecord(
    val id: String,
    @SerialName("package_sha256") val packageSha256: String,
    val peer: String,
    val status: String,
    val at: String,
    val detail: String? = null,
)

@Serializable
data class ProjectDocument(
    val schema: String = PROJECT_SCHEMA_ID,
    @SerialName("schema_version") val schemaVersion: Int = PROJECT_SCHEMA_VERSION,
    val app: Map<String, String> = mapOf("name" to "ScanForge 3D"),
    val id: String,
    val name: String,
    val category: ObjectCategory = ObjectCategory.OTHER,
    val tags: List<String> = emptyList(),
    val notes: String? = null,
    @SerialName("created_at") val createdAt: String,
    @SerialName("updated_at") val updatedAt: String,
    val status: ProjectStatus = ProjectStatus.CAPTURING,
    val archived: Boolean = false,
    @SerialName("coordinate_system") val coordinateSystem: CoordinateSystem = CoordinateSystem(),
    val capture: CaptureInfo? = null,
    val reconstruction: ReconstructionInfo? = null,
    val model: ModelInfo = ModelInfo(),
    val scale: ScaleInfo = ScaleInfo(),
    val measurements: List<MeasurementRecord> = emptyList(),
    val transfers: List<TransferRecord> = emptyList(),
    @SerialName("failure_reason") val failureReason: String? = null,
)
