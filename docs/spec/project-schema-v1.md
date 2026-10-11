# Project schema v1 (`project.json`)

Source of truth: `core/project/src/main/kotlin/app/scanforge/project/Schema.kt`. Unknown fields are ignored when
reading; documents with a newer `schema_version` are rejected with an "update the app" error. Migrations live in
`ProjectMigrations`.

| Field | Meaning |
|---|---|
| `schema` = `scanforge.project`, `schema_version` = 1 | Identification and version |
| `id`, `name`, `category`, `tags`, `notes`, `created_at`, `updated_at`, `archived` | Library metadata |
| `status` | `capturing`, `captured`, `reconstructing`, `reconstructed`, `failed` (real pipeline state) |
| `failure_reason` | e.g. `not_enough_frames`, `insufficient_coverage`, `cancelled` |
| `coordinate_system` | `{handedness:"right", up:"+Y", forward:"-Z", unit:"m"}` |
| `capture` | `{mode, quality_preset, frame_count, session_id, source, device, retained, duration_s}` |
| `reconstruction` | `{method:"tsdf_surface_nets", voxel_size_m, frames_used, warnings[], coverage{…}, completed_at, support_plane[px,py,pz,nx,ny,nz]}` |
| `model.versions[]` | `{version, file{role,path,format,bytes,sha256}, operation, parameters, vertex_count, triangle_count, bounds_min, bounds_max, has_vertex_colors, watertight, hole_count, created_at}` |
| `model.current_version` | Version shown/exported; undo/redo move this pointer. Version 1 is the untouched reconstruction. |
| `model.evidence_file` | Per-vertex evidence bytes (1 weak, 2 strong) for version 1 |
| `scale` | `{source: device_tracking|reference_length|printed_marker|external|unscaled, factor, relative_spread, calibrations[]}` |
| `scale.calibrations[]` | `{id, method, measured_m, reference_m, points, note, active, created_at}`; factor = reference/measured; mean of active ones |
| `measurements[]` | `{id, type, label, points (uncalibrated model space), raw_values (SI, uncalibrated), dimension (1/2/3), derived, model_version, confidence (high/medium/low/unknown), reasons[], resolution_limit_m, created_at}` |
| `transfers[]` | `{id, package_sha256, peer, status, at, detail}` |

Calibrated value = raw × factor^dimension. Geometry files are never rescaled by calibration.
