# Testing plan

## 1. Automated tests (run in CI or locally)
| Suite | Command | Count | Environment | Status (2026-10-11) |
|---|---|---|---|---|
| Core (geometry, reconstruction, export, project, transfer, workflow) | `cd core && gradle test` | 44 | JDK 17+ | **pass** |
| Blender add-on (package, import, mesh tools, transfer vs. real server) | `scripts/test_blender.sh <python-with-bpy>` | 26 | `pip install bpy==5.2.2` (Python 3.13) or Blender 5.2's Python | **pass** |
| Android framework subset type-check | `cd tools/android-framework-check && gradle compileKotlin` | 13 files | JDK 17+, Maven Central | **pass** |
| Android build | `gradle -p android :app:assembleDebug` | — | Android SDK 35, Google Maven (CI) | **pass in CI**; not run on a device |

What the automated suites cover: unit conversion; pose math; area/volume/topology; component cleanup;
simplification; ray picking; minimal boxes; circle fit; cross-sections; confidence rules; depth codec; crash-tolerant
capture writing and undo; TSDF reconstruction of box/cylinder with dimensional tolerances; coverage refusal; cancel and
checkpoint resume; keyframe selection; live preview; guidance priority; COLMAP reprojection equivalence; all export
formats incl. Z-up/mm; package tamper and zip-slip detection; schema version rejection; undo/redo versions;
calibration and reports; TLS pairing, wrong code, approval/denial, token once, auth lockout, malformed HTTP,
chunk/receipt verification, cancellation (410), discovery; Blender: every import format and axis/unit combination,
scene unit scale, calibration factor, origin modes, duplicates, save/reload, mesh tools, resume after interruption,
certificate change detection, forged token, and the full UI operator flow Pair → Check → Receive & Import.

## 2. Android test plan (requires devices)
### 2.1 Functional
Startup and permissions (deny/allow camera; Android 13+ notifications) · capability detection on: a Depth API device,
an ARCore device without Depth API, a device without ARCore, a ToF device · ARCore install/update flow · scan pause/
resume, undo 3 s, frame limit, low storage auto-pause (fill storage to < 200 MB) · tracking loss and recovery (cover
camera, fast motion, dark room) · incomplete scans (one side only → "insufficient coverage") · reconstruction success
and every failure reason; leave screen during reconstruction; kill process → resumes from checkpoint · measurement
tools and unit switching · calibration with a ruler-measured edge · export every format, share, re-import package ·
transfer: pairing approve/deny, wrong code, revoke, large package (> 200 MB with captures), Wi-Fi drop mid-transfer
(resume), cancel on each side · phone and tablet layouts, portrait/landscape, TalkBack labels, font scale 200 %.
### 2.2 Performance
Per device: fusion benchmark, keyframe write latency, reconstruction time per preset, peak memory, thermal throttling
over a 3-minute scan.
### 2.3 Synthetic regression (desktop)
```
CLI=core/cli/build/install/scanforge-cli/bin/scanforge-cli
for s in box cylinder sphere; do $CLI synth /tmp/c_$s --shape $s; for p in draft standard high; do
  id=$($CLI reconstruct /tmp/c_$s /tmp/store --name $s-$p --preset $p | tail -1); $CLI info /tmp/store $id; done; done
```
Compare with docs/validation/synthetic-accuracy.md.

## 3. Physical accuracy validation protocol
Reference objects (dimensions measured independently with a caliper ±0.02 mm or steel rule ±0.5 mm, recorded with
uncertainty): gauge block or machined aluminium block (~50 mm), cardboard box (~300 mm), mug (Ø ~80 mm),
textured ceramic figure, matte grey (textureless) box, glossy object, glass (expected failure), chair (~500 mm).
For each device × object × condition (bright diffuse / dim / mixed; distances 0.3 / 0.6 / 1.0 m; Draft/Standard/High):
5 repeated scans by 2 operators. Record per dimension: absolute error, relative error, mean, standard deviation
(repeatability), uncalibrated vs. calibrated (one reference edge, validated on other edges), failures and their
reasons. Report median and 95th percentile errors per size class. **No accuracy target may be advertised until this
protocol has been run and its results published.**

## 4. End-to-end acceptance test
| # | Step | How to verify | Status |
|---|---|---|---|
| 1 | Launch the Android app | Home shows capability report | **Not run** (no device / build) |
| 2 | Check device capabilities | Values match device spec sheet | Not run |
| 3 | Start a scan | Pre-scan checklist turns green; region placed | Not run |
| 4 | Capture a real object | Frames, depth frames, coverage increase | Not run |
| 5 | Reconstruct actual geometry | Stages progress; mesh appears | Not run on device; **verified on desktop** from synthetic captures via the same core code (`scanforge-cli reconstruct`) |
| 6 | Review mesh and measurements | Bounding box vs. caliper | Not run on device; synthetic: docs/validation |
| 7 | Export or transfer | Package verified; phone shows "Received & verified" | **Verified with desktop server** (`scanforge-cli serve`, same transfer code) ↔ Blender add-on |
| 8 | Receive in Blender | Add-on Pair → Check → Receive & Import | **Verified headless** (bpy 5.2.2) |
| 9 | Import into scene | Collection "ScanForge/<name>", vertex-color material | **Verified headless** |
| 10 | Verify dimensions, orientation, textures | `obj.dimensions` equals package bounds (x, z→y, y→z); base on Z=0; color attribute present | **Verified headless** (tolerance 1e-5 m) |
| 11 | Save the Blender project | Save, reload, metadata retained | **Verified headless** |
