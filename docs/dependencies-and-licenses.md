# Dependencies and licenses

ScanForge's reconstruction, measurement, export, packaging and transfer code is original. No third-party
reconstruction library is bundled. The project license is not yet chosen; the Blender extension manifest declares
GPL-3.0-or-later because Blender add-ons that import `bpy` are generally treated as GPL-covered.
**Decide the project license before release** (see release checklist).

| Component | Used by | License | Notes |
|---|---|---|---|
| Kotlin stdlib 2.2.21 | core, Android | Apache-2.0 | |
| kotlinx.serialization-json 1.9.0 | core, Android | Apache-2.0 | |
| kotlinx.coroutines 1.9.0 | Android | Apache-2.0 | |
| JUnit 5.14 / kotlin-test | tests | EPL-2.0 / Apache-2.0 | test only |
| Google ARCore SDK (`com.google.ar:core`) 1.48.0 (pin unverified) | Android | Google APIs Terms of Service; SDK under Apache-2.0 per Google's distribution | Requires "Google Play Services for AR" on device; disclose ARCore usage in the privacy policy as Google requires |
| AndroidX (core, activity, navigation, lifecycle, work) and Jetpack Compose (BOM 2024.12.01) | Android | Apache-2.0 | versions to be refreshed in Android Studio |
| Android Gradle Plugin 8.7.3 | Android build | Apache-2.0 | |
| Blender `bpy` 5.2.2 / Blender 5.2 LTS | add-on runtime and tests | GPL-3.0-or-later (Blender), add-ons importing bpy are typically distributed under GPL | |
| Python standard library (`ssl`, `http.client`, `zipfile`, `hashlib`) | add-on | PSF | no third-party Python packages bundled |
| OpenCV Android SDK (`org.opencv:opencv`) 4.14.0 | Android: ArUco marker detection only | Apache-2.0 | adds `libopencv_java4.so` (arm64-v8a, armeabi-v7a kept) |
| CameraX (`androidx.camera:*`) 1.4.1 | Android: marker-mode camera | Apache-2.0 | |
| openpnp OpenCV 4.9.0-0 | core `markers` tests only (real ArUco detection on rendered boards) | Apache-2.0 (OpenCV) | test only, not shipped |
| ArUco DICT_4X4_250 bit patterns | `core/markers` resource | Apache-2.0 (OpenCV) | exported from OpenCV; must match the detector's dictionary |
| Robolectric `android-all` 15 | `tools/android-framework-check` only | Apache-2.0 (Robolectric) / AOSP | compile-time check, not shipped |
| COLMAP (optional, user-installed) | desktop photogrammetry workflow | BSD-3-Clause (deps separately licensed) | not bundled |

Rejected: OpenMVS (AGPL-3.0, incompatible with a closed-source app), proprietary SDKs requiring cloud upload.

Minimum versions: Android 8.0 (API 26) app minimum; ARCore itself needs Android 7.0+ and a supported device;
Depth API support is device-specific. Blender: 5.2.0 declared (only 5.2.2 tested).
