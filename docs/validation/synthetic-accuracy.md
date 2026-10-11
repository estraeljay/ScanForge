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
