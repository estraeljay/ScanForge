# ADR 0004 — ARCore-free scanning with a printed marker board

Status: accepted (2026-10-11). Complements ADR 0001; does not replace the ARCore depth path.

## Context
ARCore depth scanning only works on certified phones with the Depth API. The main use case is objects up to an A3
footprint (≈ 30 × 42 cm), which is small enough to place on a printed sheet. We want scanning on any Android phone with a
rear camera, with real metric scale and no cloud processing.

## Decision
A **printed ArUco board** (DICT_4X4_250; A4, A3 or A2) is placed under the object. A board is identified by a code such as
`SFB1-A2-40-10`: version, paper size, marker size in mm, gap in mm. The app generates it as a true-size PDF with a 100 mm
check bar.

1. **Capture (phone):** CameraX delivers YUV frames. OpenCV's ArUco detector finds marker corners with sub-pixel
   refinement; this is the only OpenCV use. A live planar pose drives guidance and keyframe selection. Kept photos are
   sharp, taken from a new viewpoint, and see at least 6 markers. Each is stored with its raw marker detections.
2. **Calibration and poses (core):** the shared focal length is estimated from all photos (Zhang constraints from the
   board homographies, then a 1-D search on reprojection error). Each photo's pose then comes from planar PnP:
   homography DLT followed by Gauss–Newton, with per-marker outlier rejection. Poses are recomputed from the stored
   detections, not taken from the live estimate.
3. **Depth (core):** plane-sweep multi-view stereo against 4 neighbouring photos per reference photo:
   * inverse-depth planes over the object's height range, homography warps, and 7×7 NCC via summed-area tables;
   * multi-view consistency check;
   * parallel over photos.
   The resulting depth maps go into the existing TSDF / Surface Nets / cleanup / measurement pipeline.
4. **Scale:** comes from the printed board. The user can measure the 100 mm bar, and the board-scale factor is stored per
   board and per session (`board_scale`). The project records `scale.source = printed_marker`.
5. **Support plane:** the board plane (y = 0) is exact, so the plane cut sits 1 voxel above it, not 1.5.

## Why this design
* **Works without ARCore.** Only a camera is needed; OpenCV's Android AAR is Apache-2.0.
* **Poses are metric and drift-free.** Every photo is registered to the same physical board, so there is no SfM scale
  ambiguity and no VIO drift.
* **The heavy maths is tested on the desktop.** Pose, focal length, stereo and fusion are pure Kotlin in `core/` and are
  tested with synthetic photos rendered from the real board layout. OpenCV detection is tested on rendered board images
  with the real OpenCV, via openpnp on desktop.

## Rejected alternatives
* **Full SfM (COLMAP-style) on the phone:** heavy, slow, and has no metric scale without a reference.
* **OpenMVS:** AGPL licence.
* **ChArUco or AprilTag:** ChArUco needs OpenCV's calibration module and adds little for this use. AprilTag would need
  another native library. ArUco is in OpenCV's main objdetect module.
* **Depth from phone dual cameras:** not exposed consistently across devices.

Variants measured and rejected in the MVS stage:
* shiftable windows;
* 3-view consistency;
* depth-edge rejection;
* requiring 2–3 TSDF observations per surface voxel.

Each one lowered coverage or accuracy on the synthetic scenes (`MvsDiagnostics`, run with `-Dmvs.diag=true`).

## Consequences and limits
* Objects need surface **texture**. Plain white, glossy, transparent or very dark objects give sparse or no depth.
* The **board must stay visible** in each photo: at least 6 markers, and in practice much of the board. The object must
  not cover most of it, which is why an A2 board is recommended for A3-footprint objects.
* The object's **underside is never seen**. Its lowest ~1 voxel is removed with the board plane.
* Processing is **slower than ARCore-depth fusion**. On desktop (4 cores, matching at 320 px) it takes ≈ 30 s for 36
  photos and longer for 90. Phone times are **not measured**.
* **Presets:** every preset matches at 320 px, and High keeps Standard's 4 mm voxels. Matching at 480–640 px and voxels
  finer than 4 mm both increased errors on synthetic captures (docs/validation/synthetic-accuracy.md).
* Accuracy is measured only on **synthetic** data (docs/validation/synthetic-accuracy.md). Real-world accuracy still
  needs the physical protocol (testing-plan §3).
