# ScanForge 3D — Technical Feasibility Report (Phase 0)

Status: October 2026. Author: initial engineering pass. Scope: Android scanning app + Blender add-on.

## 1. How this was researched, and what could not be verified

Official sources were consulted through web search because this build environment cannot reach
`developers.google.com`, `docs.blender.org` or `dl.google.com` directly (network policy). Facts used for design:

| Fact | Source | Verified how |
|---|---|---|
| `Frame.acquireRawDepthImage16Bits()` returns 16-bit **millimeters**, all 16 bits used (≤ 65 535 mm); replaced the 13-bit API in ARCore 1.31 | ARCore *Raw Depth* developer guide and `Frame` reference | Search snippets of the official pages |
| `Frame.acquireRawDepthConfidenceImage()` returns 8-bit confidence, 0 = lowest, 255 = highest | ARCore Raw Depth guide | Search snippets |
| Optimal depth accuracy 0.5–5 m; error grows ~quadratically with distance | ARCore Raw Depth guide | Search snippets |
| Depth is new only when the depth image timestamp changes; otherwise it is a reprojection | ARCore Raw Depth guide | Search snippets |
| AR Required apps need minSdk ≥ 24; AR Optional ≥ 19; devices must have shipped with Google Play; Depth API support is per device | ARCore supported-devices page | Search snippets |
| Latest ARCore SDK line: 1.48 downloadable, 1.50/1.51 announced (targetSdk 36) | ARCore downloads / what's-new pages | Search snippets |
| Blender 5.1 moved to Python 3.13 (VFX Platform 2026) | Blender 5.1 Python API release notes | Search snippet **and** the `bpy` 5.2.2 wheel (cp313) used for tests |
| Extensions use `blender_manifest.toml` (schema 1.0.0), `blender_version_min` ≥ 4.2, `[permissions]` network/files, `[build] paths_exclude_pattern` | Blender manual "How to Create Extensions" | Search snippets **and** a successful install of the built zip with Blender 5.2.2's own extension installer |
| Importer operator signatures (`wm.ply_import`, `wm.obj_import`, `wm.stl_import`, `import_scene.gltf`) | Blender 5.2.2 | Introspected at runtime from `bpy` 5.2.2 |
| COLMAP is new-BSD licensed (dependencies separately licensed) | colmap.readthedocs.io | Search snippet |

Not verified (must be checked on first Android Studio sync): exact latest stable versions of AGP, Compose BOM,
ARCore Maven artifact; ARCore behaviour on specific devices; AndroidKeyStore-backed TLS **server** sockets on real
devices (expected to work via Conscrypt, untested).

## 2. Device reality

* ARCore runs on a large but not universal set of Play-certified devices. **Depth API is a per-device capability**;
  many supported devices lack it. Depth on most phones is *depth-from-motion* (no ToF sensor), at roughly 160×120,
  noisy, and needs camera motion to converge. A minority of devices expose a hardware depth sensor through
  `CameraConfig.DepthSensorUsage`.
* Consequently the app assesses capability at runtime (ARCore availability, Depth / Raw Depth support, hardware
  depth sensor use, Camera2 autofocus and output sizes, memory, storage, and a measured TSDF fusion micro-benchmark)
  and offers only what the device can do.

## 3. Scanning approaches evaluated

| Criterion | A. Depth-assisted (ARCore depth + poses, TSDF) | B. Photogrammetry (overlapping photos, SfM + MVS) | C. Hybrid (ARCore poses + depth on device; images + poses for desktop MVS) |
|---|---|---|---|
| Metric scale | Yes (VIO + depth are metric) | No, unless markers/known length | Yes |
| Geometric detail | Limited by depth resolution/noise (~mm–cm) | Highest (texture-dependent) | On device: as A. Desktop: as B |
| Textureless / dark surfaces | Better than B (depth-from-motion still needs some texture; ToF does not) | Poor | As A on device |
| Device coverage | Depth API devices only | Any camera phone | All ARCore devices (photo-only path without Depth API) |
| On-device cost | Moderate (sparse TSDF, seconds) | Very high (SfM + dense MVS: minutes–hours, GBs RAM) | Moderate |
| Licensing | Own implementation, no third-party code | COLMAP BSD ok; **OpenMVS is AGPL**, not acceptable in a closed app; AliceVision MPL-2 but GPU/CUDA-centric | Own code on device; COLMAP (BSD) optional on desktop |
| Complexity | Medium | Very high on Android | Medium + optional tooling |

**Recommendation: C (hybrid), shipping A on device first.**
On-device reconstruction uses ARCore Raw Depth + confidence fused with ARCore poses into a sparse TSDF and meshed
with Surface Nets; vertex colors come from the keyframes. The same captures (JPEG keyframes + metric poses +
intrinsics) are exported in a documented format and as a COLMAP model with **known poses**, enabling optional
high-detail desktop photogrammetry that keeps metric scale. Devices without Depth API capture photos + poses only.
Desktop processing is never required for the core workflow.

Why not on-device photogrammetry: Android ports of SfM/MVS exist but cost minutes to hours on a phone, need GBs of RAM,
and the strongest open MVS (OpenMVS) is AGPL. Reconstruction is therefore done after "Finish" (not in real time) from
saved data, with a low-resolution live fusion only for preview and coverage.

## 4. Hardest technical risks

1. **Depth quality on depth-from-motion phones** — noisy, low-resolution, edge artifacts ("flying pixels"). Mitigation:
   confidence weighting, behind-surface weight drop-off, small-component removal; honest confidence reporting.
2. **Pose drift / relocalization** — VIO drift over long scans bends geometry; ARCore may shift the world frame.
   Mitigation: short guided scans (frame limits per preset), keyframe gating by speed, warnings. Not eliminated.
3. **Dimensional accuracy claims** — cannot be promised. Mitigation: qualitative confidence, resolution limits stated
   as limits (not accuracy), calibration with references and spread reporting, a physical validation protocol.
4. **Uncompiled Android code in this environment** — mitigation: all algorithms live in tested pure-Kotlin core
   modules; framework-only Android files are type-checked against Android 15 framework classes; the rest needs an
   Android Studio build (see docs/android/BUILD.md).
5. **TLS server on Android with AndroidKeyStore keys** — expected to work (opaque keys are used for signing by
   Conscrypt) but untested on devices.
6. **Memory on low-end phones** — sparse blocks keep memory proportional to surface area; preset frame limits.

## 5. MVP

A device with ARCore Depth API can: create a project → guided depth capture with measured coverage → on-device
reconstruction → inspect mesh, measure (distance, bounding/upright box, height above support, diameter by circle fit,
section perimeter, area, volume only if closed) → calibrate with a known length → export PLY/OBJ/STL/GLB or a verified
`.sf3d` package → send over Wi-Fi with TLS + pairing → Blender add-on verifies, imports at correct scale/orientation.

## 6. Implementation status

See `docs/roadmap.md` (phase status) and `README.md` (verified vs. unverified matrix).
