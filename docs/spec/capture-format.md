# Capture dataset format v1 (`capture/`)

Written on the phone during scanning by `CaptureWriter` (core/reconstruction). Read by reconstruction, the CLI and
the COLMAP exporter.

```
capture/
  session.json        CaptureSession
  frames.jsonl        one FrameRecord per line, appended after the frame's files are fully written
  depth/000123.sfd    SFD1 depth + confidence
  color/000123.jpg    JPEG keyframe (CPU image, sensor orientation)
```

* **session.json**: `format_version` (1), `session_id`, `started_at`, `capture_mode` (`depth`|`photo`|`marker`),
  `quality_preset`, `roi` `{min,max}` (world, m), `ground_plane` `{point, normal}` (world), `device` (map),
  `source` (`device` for real scans, `synthetic` for generated test data). Marker sessions add `board` (board code,
  e.g. `SFB1-A2-40-10`) and `board_scale` (measured print scale, 1.0 = exactly 100 %; board coordinates are multiplied
  by it).
* **FrameRecord**: `index`, `timestamp_ns`, `camera_pose` `[tx,ty,tz,qx,qy,qz,qw]` (camera→world, ARCore physical
  camera: +X right, +Y up, looks −Z, relative to the image readout), `tracking`, `color_file`, `color_intrinsics`,
  `depth_file`, `depth_intrinsics`, `mean_depth_confidence`, `exposure_ok_fraction`, `markers` (marker mode only).
* **Marker mode** (`capture_mode: marker`): frames have no depth file. `markers` is a list of
  `{id, corners:[x0,y0,…,x3,y3]}` (ArUco DICT_4X4_250 ids; corners in color-image pixels, order TL, TR, BR, BL of the
  printed marker). `camera_pose` holds the live estimate used for guidance; reconstruction recomputes the focal length
  and every pose from `markers` and ignores the stored pose. `color_intrinsics` is the initial guess from Camera2.
* **Board coordinates** (marker mode world frame): origin at the board grid center, +X along the page width, +Z toward
  the page bottom edge, +Y up out of the paper, meters. The scan's support plane is y = 0.
* **Intrinsics**: `fx, fy, cx, cy, width, height` in pixels; pixel (0,0) is the center of the top-left pixel; v down.
  Depth intrinsics are the CPU-image intrinsics scaled to the depth resolution (same field of view; aspect checked).
* **SFD1**: little-endian `"SFD1" | u16 w | u16 h | u8 flags(bit0=confidence) | 3×u8 0 | u16 depth_mm[w·h] | u8 conf[w·h]?`.
  Depth 0 = invalid. Chosen because Android cannot encode 16-bit PNG.
* Crash safety: files are written via temp+rename and fsync; the reader ignores a truncated last line.
