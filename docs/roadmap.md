# Roadmap and phase status

Legend: ✅ implemented and verified by automated tests here · 🟡 implemented, not compiled/run in this environment ·
⬜ not started.

## Phase 0 — Research and architecture ✅
Feasibility report, ADRs 0001–0003, protocol and data specs, dependency/licence review, compatibility, test plan.

## Phase 1 — Android scanning proof of concept 🟡
Capability detection, ARCore session (raw depth, plane finding, light estimation), keyframe capture to the crash-safe
capture format, HUD with measured tracking/coverage/storage, pause/resume/undo/finish. All algorithms it relies on
(capture format, keyframe selection, live fusion, coverage, guidance) are ✅ in core. The app compiles in CI.
**Next milestone: install the CI debug APK on one Depth API phone and run scan → reconstruct → send to Blender.**

## Phase 2 — Reconstruction and measurements ✅ core / 🟡 app
Sparse TSDF + Surface Nets, cleanup, coverage, checkpoints; measurements (distance, AABB, upright and minimal oriented
box, height above support, circle fit, section perimeter, area, volume when closed); confidence; calibration;
exports with validation; thumbnails. App screens 🟡.

## Phase 3 — Blender import add-on ✅
Extension for Blender 5.2, verified imports with scale/orientation, metadata, mesh tools, 26 headless tests.

## Phase 4 — Direct wireless transfer ✅ protocol / 🟡 Android integration
TLS server + pairing + chunked verified transfer + discovery (Kotlin, tested); Blender client (tested against it);
Android foreground service + AndroidKeyStore identity 🟡.

## Phase 5 — Complete user experience 🟡
Project library, viewer, editing with undo/redo, tablet layouts, accessibility labels are written; need device QA.
⬜ Texture atlas (UV + baked texture) instead of vertex colors. ⬜ Lasso crop in the viewer (box crop exists in the
ViewModel). ⬜ Printed calibration marker detection (manual reference length works).

## Phase 6 — Validation and release ⬜
Device matrix, performance benchmarks, physical accuracy protocol (testing-plan §3), release build signing, Play
listing, privacy policy, licence decision.

## Later
QEM simplification; hole filling for volume estimates (clearly labelled); multi-session capture via anchors;
desktop helper to run COLMAP/OpenMVS-free MVS automatically; USD export if a tested benefit appears.
