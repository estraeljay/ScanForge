# Device compatibility

## Android
| Capability | Requirement | Behaviour when missing |
|---|---|---|
| Rear camera (CameraX) | Any Android ≥ 8.0 phone; arm64-v8a or armeabi-v7a (OpenCV native library) | Scanning disabled |
| Printed marker board | A4/A3/A2 print (PDF from the app) | Marker scanning not possible; ARCore depth if available |
| ARCore (Google Play Services for AR) | Supported device that shipped with Google Play; Android ≥ 7.0 (app needs ≥ 8.0) | Only **marker-board** scanning (docs/marker-scanning.md); library, measurement, export, import and transfer still work |
| ARCore Depth API (`RAW_DEPTH_ONLY`) | Device-specific | ARCore depth mode disabled; marker-board scanning still available |
| Hardware depth sensor (ToF) | Optional; used when ARCore offers a `REQUIRE_AND_USE` camera config | Depth-from-motion used instead |
| Rear camera autofocus | Recommended | Warning to keep ≥ 30 cm distance |
| OpenGL ES 3.0 | Required (manifest) | App not installable |
| Memory | ≥ 3 GB recommended | Draft preset recommended; frame limit per preset (150/300/600 keyframes) |
| Storage | ≥ 500 MB free recommended | Warning; capture auto-pauses below 200 MB free |
| Wi-Fi / Ethernet | For wireless transfer | Use package export + USB/file sharing |

Capability detection (`DeviceCapabilities.assess`) reports all of the above plus a measured TSDF fusion benchmark,
and the Home screen only enables what the device supports.

**Tested devices: none.** The Android app has not been run on hardware in this environment. A device test matrix
is part of Phase 6 (see docs/testing-plan.md §4).

## Blender
| Version | Status |
|---|---|
| 5.2.2 LTS (Python 3.13) | 26 automated headless tests pass (`bpy` 5.2.2 module); zip installed via Blender's extension installer |
| 5.0 / 5.1 | Not tested. Code is pure Python using stable APIs; lower `blender_version_min` only after testing |
| ≤ 4.x | Not tested; Python 3.11 compatible syntax, but importer options differ in older releases |
