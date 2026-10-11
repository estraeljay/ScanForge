# Coordinate systems and units

| Space | Handedness | Up | Forward | Units |
|---|---|---|---|---|
| ARCore world / scan space / project files / glTF | right | +Y | −Z | m |
| ARCore camera (physical) | right | +Y (image up) | −Z (looks) | m |
| Image pixels | — | v down | — | px, (0,0) = first pixel center |
| COLMAP camera | right | +Y down | +Z (looks) | px centers at +0.5 |
| Blender | right | +Z | −Y | BU = `scale_length` m |

* Scan → Blender: `(x, y, z) → (x, −z, y)`; rotation matrix rows `(1,0,0) (0,0,−1) (0,1,0)`, det +1.
* File units → Blender units: `file_unit_m / scene.unit_settings.scale_length` (option "Respect Scene Unit Scale").
* Calibration factor multiplies lengths on export and import (areas by factor², volumes by factor³).
* Verified by tests: `core/export` (Z-up/mm conversion), `blender/tests/test_import.py` (every format, Y-up/Z-up,
  m/cm/mm, scene unit scale 0.001, calibration factor) and `ColmapExportTest` (reprojection equivalence).
