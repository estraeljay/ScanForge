# ADR 0001 — Capture and reconstruction architecture

**Status:** accepted (2026-10). **Context:** see docs/feasibility-report.md.

## Decision
1. Capture with ARCore: camera pose (`Camera.getPose`, physical-camera convention), CPU image intrinsics,
   Raw Depth 16-bit mm + confidence, YUV CPU image → JPEG. Keyframes are selected by motion (≥ 3 cm or ≥ 5°,
   ≥ 150 ms apart, rejected above 0.5 m/s).
2. Persist every keyframe immediately (crash-safe append-only `frames.jsonl`, SFD1 depth files, JPEGs) —
   `docs/spec/capture-format.md`.
3. Reconstruct **after Finish** from saved data: sparse block TSDF (8³ blocks, hashed), confidence- and
   distance-weighted projective updates, behind-surface weight drop-off, Surface Nets extraction, support-plane
   cut, fragment removal, coverage/evidence analysis. Checkpoints every 20 frames allow resume.
4. Live preview: a separate low-resolution (8 mm) fusion of the same keyframes for points and measured coverage only.
5. Photo mode (no Depth API): store keyframes + poses; export a COLMAP known-pose model for optional desktop MVS.
6. All of the above lives in pure-Kotlin modules (`core/`) shared by Android and a desktop CLI, so it is unit-tested
   without a phone.

## Consequences
+ Real geometry from measured data only; no fabricated surfaces (cells with unobserved corners are never meshed).
+ Metric scale from ARCore; testable algorithms. − Detail limited by phone depth; textures are vertex colors (no UV
atlas yet); capture cannot continue across AR sessions (different world frames).

## Alternatives rejected
On-device SfM/MVS (cost, AGPL OpenMVS), ARCore point cloud only (too sparse), marching cubes (Surface Nets is simpler
and table-free with comparable quality for this voxel size).
