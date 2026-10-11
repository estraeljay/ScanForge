# ScanForge 3D

Android 3D scanning app + Blender extension. The phone captures ARCore depth, camera poses and images, reconstructs a
real mesh on the device, measures it with stated confidence, and sends it over the local network (TLS, pairing,
SHA-256-verified) to Blender, which imports it with correct scale and orientation. The product name is configurable
(`android/gradle.properties` → `scanforge.appName`).

**Start here:** [Feasibility report](docs/feasibility-report.md) · [Roadmap & phase status](docs/roadmap.md) ·
[Known limitations](docs/known-limitations.md) · [Testing plan](docs/testing-plan.md)

## What is verified, and what is not

| Area | State | Evidence |
|---|---|---|
| Core: geometry, measurements, TSDF reconstruction, coverage, export (PLY/OBJ/STL/GLB), project store, packages, calibration, transfer protocol server | ✅ built and tested | `cd core && gradle test` — 44 tests |
| Reconstruction accuracy on **synthetic** depth (box, cylinder, sphere × 3 presets) | ✅ measured | [docs/validation/synthetic-accuracy.md](docs/validation/synthetic-accuracy.md) |
| Blender 5.2 extension: import of every format/axis/unit, metadata, mesh tools, transfer client against the real server, full operator flow, save/reload | ✅ tested headless with `bpy` 5.2.2 | `scripts/test_blender.sh` — 26 tests; zip installs via Blender's extension installer |
| Desktop end-to-end: synthetic capture → reconstruct → package → TLS transfer → Blender import → verified dimensions | ✅ | `blender/tests/test_transfer.py`, `test_import.py` |
| Android framework-only sources (GL, image conversion, AndroidKeyStore TLS, transfer manager/service, viewer) | ✅ type-checked against Android 15 framework classes | `tools/android-framework-check` |
| Android app (ARCore capture, Compose UI, WorkManager) | 🟡 **compiles** (debug APK built in CI); never run on a device | CI `android-build`; see [docs/android/BUILD.md](docs/android/BUILD.md) |
| Real-device scanning, real-world accuracy, AndroidKeyStore TLS on phones | ⬜ not tested | needs hardware: [testing plan §2–3](docs/testing-plan.md) |

No accuracy figure is claimed for real scans.

## Architecture

```
 Android app (Kotlin, Compose, ARCore)           Blender 5.2 extension (Python, stdlib only)
 ├─ capability assessment                         ├─ panels: Connection · Incoming · Import · Info · Mesh Tools
 ├─ ARCore capture → capture/ (crash-safe)        ├─ pinned-TLS client, resumable verified download
 ├─ HUD: measured tracking/coverage/guidance      ├─ .sf3d verification, explicit Y-up→Z-up, units, calibration
 ├─ WorkManager reconstruction (resumable)        └─ explicit undoable mesh tools, dimension report
 ├─ viewer, measure, calibrate, edit, export
 └─ TLS transfer server (AndroidKeyStore key)  ⇄  HTTPS (phone = server, Blender = client), pairing code = cert fingerprint
          │
          ▼  includeBuild
 core/ (pure Kotlin/JVM, tested on desktop): geometry · reconstruction · export · project · transfer · workflow · cli
```
Decisions: [ADR 0001 capture & reconstruction](docs/adr/0001-capture-and-reconstruction.md) ·
[ADR 0002 transfer & security](docs/adr/0002-transfer-direction-and-security.md) ·
[ADR 0003 coordinates & units](docs/adr/0003-coordinates-and-units.md).
Specs: [transfer protocol v1](docs/protocol/transfer-protocol-v1.md) · [capture format](docs/spec/capture-format.md) ·
[project schema v1](docs/spec/project-schema-v1.md) · [package `.sf3d`](docs/spec/package-format.md) ·
[coordinate systems](docs/spec/coordinate-systems.md) · [dependencies & licences](docs/dependencies-and-licenses.md) ·
[device compatibility](docs/device-compatibility.md).

## Repository layout
```
android/            Android Studio project (app module; consumes core via includeBuild)
core/               Kotlin/JVM modules + developer CLI (scanforge-cli)
blender/scanforge3d Blender extension source (blender_manifest.toml)
blender/tests/      headless Blender tests
tools/android-framework-check  partial Android compile check without the Android SDK
samples/            synthetic sample project (.sf3d, .glb)
scripts/            fixtures, Blender tests, extension build
docs/               feasibility, ADRs, specs, testing, validation, guides
```

## Quick start (reproducible commands)
```bash
# Core: build + 44 tests (JDK 17+, Gradle 8.14)
cd core && gradle test :cli:installDist && cd ..

# Synthetic scan → reconstruction → measurements (desktop, same code as the phone)
CLI=core/cli/build/install/scanforge-cli/bin/scanforge-cli
$CLI synth /tmp/sf/capture --shape box
ID=$($CLI reconstruct /tmp/sf/capture /tmp/sf/store --name "Box" | tail -1)
$CLI info /tmp/sf/store $ID --unit mm
$CLI package /tmp/sf/store $ID /tmp/sf/box.sf3d

# Blender extension tests (Python 3.13 + bpy 5.2.2) and installable zip
python3.13 -m venv .venv && .venv/bin/pip install bpy==5.2.2
scripts/test_blender.sh .venv/bin/python
scripts/build_blender_extension.sh            # → dist/scanforge3d-0.1.0.zip

# Try a transfer without a phone: the CLI runs the phone's server code
$CLI serve /tmp/sf/phone --offer /tmp/sf/box.sf3d --bind 0.0.0.0
#   → prints address and PAIRING_CODE; pair from Blender's ScanForge panel (approval: add --auto-approve for tests only)

# Android (needs Android Studio / SDK 35): see docs/android/BUILD.md
```

## Next milestone
Install the CI-built debug APK (artifact `app-debug-apk`) and run Phase 1 on one Depth API device (scan → reconstruct → send to Blender), then execute the physical accuracy protocol.
