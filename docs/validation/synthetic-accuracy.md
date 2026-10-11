# Synthetic reconstruction accuracy (measured)

**What this is:** reconstruction of analytic scenes rendered as noisy depth (σ = 4 mm·d², 3 % dropouts, 160×120
depth, 72 views on three rings at 0.45 m). It validates the algorithms (fusion, extraction, cleanup, measurement)
against exact ground truth. **It is not a real-world accuracy claim**: real ARCore depth is noisier, has edge
artifacts and pose drift. Real-world accuracy must be measured with the physical protocol in testing-plan.md §3.

Reproduce: `scripts/make_fixtures.sh` (box) or the loop in docs/testing-plan.md §2.3. Measured on 2026-10-11 with
the code in this repository (desktop JVM, 4 cores; times are not phone times).

| Object (truth) | Preset (voxel) | Bounding box W × H × D (mm) | Height above support (mm) | Triangles | Holes | Time |
|---|---|---|---|---|---|---|
| Box 120 × 80 × 60 | Draft (6 mm) | 122.4 × 73.1 × 62.2 | 80.1 | 2 768 | 1 | 2.2 s |
| Box 120 × 80 × 60 | Standard (4 mm) | 121.5 × 75.5 × 60.6 | 80.4 | 5 235 | 1 | 2.4 s |
| Box 120 × 80 × 60 | High (2.5 mm) | 121.0 × 77.4 × 61.0 | 80.3 | 16 455 | 1 | 4.5 s |
| Cylinder Ø80, h 100 | Draft | 78.8 × 90.6 × 78.9 | 99.6 | 1 902 | 1 | 2.1 s |
| Cylinder Ø80, h 100 | Standard | 80.8 × 94.4 × 80.8 | 100.4 | 4 953 | 1 | 2.5 s |
| Cylinder Ø80, h 100 | High | 80.3 × 97.3 × 80.2 | 100.2 | 12 519 | 1 | 4.2 s |
| Sphere Ø100 | Draft | 98.5 × 92.2 × 98.5 | 99.3 | 2 384 | 1 | 1.9 s |
| Sphere Ø100 | Standard | 98.4 × 93.7 × 98.5 | 99.2 | 5 160 | 1 | 2.4 s |
| Sphere Ø100 | High | 98.4 × 90.4 × 98.4 | 99.7 | 13 036 | **9** | 4.2 s |

Observations
* Horizontal dimensions are within ≈ 2.5 mm at Draft and ≈ 1.6 mm at Standard/High on these scenes.
* The mesh's own height (H) is lower than truth because the support-plane cut removes the bottom 1.5 voxels; the
  **height above support surface** measurement uses the recorded plane and is within 1 mm.
* The sphere's under-side near the table is never observed, so its bounding box is slightly small.
* **High is not uniformly better**: with the same depth noise, finer voxels collect less evidence per voxel and the
  sphere surface developed 9 holes. This is why the app states that High quality adds detail but does not guarantee
  accuracy.
* Every mesh is open at the bottom (resting objects) and is correctly reported as not watertight; volume is
  therefore not reported.

Algorithm changes driven by these measurements: behind-surface weight drop-off reduced convex-edge bulging
(box width error +4.5 → +1.5 mm); depth-edge rejection made results worse on synthetic data and is off by default.
Face positions of the box were within 0.3 mm of truth; the remaining bounding-box excess comes from edge vertices.

## Marker-board mode

**What this is:** synthetic marker-board scans (`scanforge-cli synth-marker`). An A3 board and a textured 100 × 70 × 50 mm
box are rendered into 640 × 480 photos (f = 520 px). Photos are taken on three rings at 35°, 55° and 75° elevation,
0.5 m from the target. Marker corners are projected exactly, then each coordinate gets uniform ±0.3 px noise (no image-based detection in this test; detection is tested separately with OpenCV). Reconstruction runs the full phone pipeline:
* focal estimation from the markers (not given the true value);
* per-photo pose;
* plane-sweep MVS at 320 px;
* TSDF;
* cleanup.

**This is not a real-world accuracy claim.** Real photos add lens distortion, blur, lighting changes, paper warp and
detection errors. Real accuracy requires the physical protocol (testing-plan.md §3).

Reproduce:
```
$CLI synth-marker /tmp/m36 --size a3                # 36 photos
$CLI synth-marker /tmp/m90 --size a3 --frames 90    # 90 photos
$CLI reconstruct /tmp/m36 /tmp/store --name m --preset draft|standard|high
```

Measured 2026-10-11 on a desktop JVM with 4 cores. Phone times are not measured.

| Photos | Preset (voxel) | Bounding box W × H × D (mm) | Height above board (mm) | Triangles | Holes | Time |
|---|---|---|---|---|---|---|
| 36 | Draft (6 mm) | 99.0 × 67.5 × 51.5 | 70.3 | 1 421 | 3 | 31 s |
| 36 | Standard (4 mm) | 99.0 × 68.5 × 53.2 | 70.3 | 3 126 | 8 | 32 s |
| 36 | High (4 mm, same as Standard) | 99.0 × 68.5 × 53.2 | 70.3 | 3 126 | 8 | 29 s |
| 90 | Draft (6 mm) | 101.0 × 66.3 × 50.8 | 70.0 | 1 578 | 1 | 69 s |
| 90 | Standard (4 mm) | 99.5 × 67.5 × 50.9 | 70.2 | 3 595 | 1 | 70 s |
| 90 | High (4 mm, same as Standard) | 99.5 × 67.5 × 50.9 | 70.2 | 3 595 | 1 | 72 s |

The automated test `MarkerReconstructionTest` generates its own 36-photo capture in memory and reconstructs it with
Draft settings. It asserts:
* focal length within 1 %;
* mean pose reprojection error < 0.6 px;
* width, depth and height above the board within 3 mm of truth;
* no vertex more than 2 mm outside the true footprint.
Measured: 99.8 × 70.2 × 51.5 mm.

Observations
* Footprint (W, D) is within 1.5 mm of truth in 5 of 6 runs; the worst run is +3.2 mm. Height above the board is within
  0.3 mm in every run. Scale comes from the board and the estimated focal length. Neither is given the true value.
* The mesh's own height (H) is 1.5–3.7 mm short: the plane cut removes about one voxel above the board. Use **height
  above support** for the height.
* 90 photos close most holes (1 instead of 3–8).
* Settings chosen from these measurements:
  * The plane cut is 1 voxel, not 1.5. With 1.5 the mesh height was 61.6 mm; with 0.5, board fragments widened the
    footprint to 102 × 55 mm.
  * Stereo matching runs at 320 px for every preset. Matching at 480–640 px gave footprints of +4–7 mm and took
    about 4× longer.
  * High keeps 4 mm voxels. At 2.5–3 mm there were more holes, and footprint errors were +2–10 mm with 36 photos and
    +1.7/+3.0 mm with 90 photos.
* The bounding box can be labelled "high" confidence and still be 3 mm off (36 photos, Standard). Confidence levels
  reflect observation evidence and coverage. They are **not** error bounds.
