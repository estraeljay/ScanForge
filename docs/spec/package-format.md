# ScanForge package `.sf3d` (format 1)

A ZIP archive, self-contained so Blender can import it without the Android app.

```
package.json               PackageManifest
project.json               ProjectDocument (schema v1)
model/vNNN.ply             current model version (binary PLY, +Y up, meters, vertex colors)
model/v001.ply             original reconstruction (if different from current)
model/evidence.bin         optional per-vertex evidence
reports/measurements.json  calibrated measurement report (meters)
reports/measurements.csv
capture/…                  only when "include captures" was chosen
```

**package.json**: `{format:"scanforge.package", format_version:1, project_id, project_name, created_at,
primary_model, includes_capture, files:[{role, path, format, bytes, sha256}]}`.

Readers MUST verify every listed file's size and SHA-256, reject absolute paths, `..`, backslashes, drive letters
and non-normalized paths, and refuse newer `format_version`s. Both readers (Kotlin `ProjectPackager.verify`, Python
`scanforge3d.package.verify`) implement and test this, including tamper detection.
