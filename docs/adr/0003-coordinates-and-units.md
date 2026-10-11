# ADR 0003 — Coordinate systems and units

**Status:** accepted. Normative: docs/spec/coordinate-systems.md.

* Internal unit: **meters**, double precision for measurements, float32 for mesh vertices.
* Scan space = ARCore world = glTF: right-handed, **+Y up**, −Z forward. All project files store this.
* Blender: +Z up. Conversion `(x, y, z) → (x, −z, y)` (proper rotation, winding preserved), applied explicitly by the
  add-on (PLY/OBJ/STL imported with identity axes, then transformed); glTF is converted by Blender's own importer.
* Exports may be written Z-up and/or in mm/cm/in/ft; the choice is recorded in the file (PLY/OBJ comments, STL header
  `ScanForge 3D <unit> <+Y|+Z>`) and honoured by the add-on. GLB is always meters, +Y up (glTF spec).
* Calibration never rewrites geometry: `project.json.scale.factor` is applied at export/import time.
