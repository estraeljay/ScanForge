# Known limitations and troubleshooting

## Limitations (by design or not yet implemented)
* **Untested on hardware.** The Android app compiles (CI builds a debug APK) but has not been run on a device.
* **Accuracy is not established.** Only synthetic validation exists (docs/validation). Do not rely on scans for
  critical dimensions without calibration and physical verification.
* Depth-from-motion phones produce noisy, low-resolution depth; thin parts (< ~3 voxels), sharp edges and fine detail
  are lost or rounded. Surfaces that are transparent, mirror-like, very dark, textureless, moving or repetitive may fail.
* VIO drift on long scans can bend geometry; keep scans short (preset frame limits) and revisit the start area.
* Capture cannot be continued after the app restarts (new AR session = different world frame); saved frames can still
  be reconstructed.
* Textures are per-vertex colors, not UV-mapped texture images.
* Objects resting on a surface have an open bottom: volume is not reported for open meshes.
* Simplification is vertex clustering (fast, coarse); a QEM decimator is planned. Blender's Decimate is available
  in the add-on.
* Marker-board mode needs a printed board and textured objects. Plain, glossy, transparent or very dark objects give
  little or no stereo depth. The object's lowest ~1 voxel is removed with the board plane. MVS is slower than depth
  fusion; phone processing times have not been measured. See docs/marker-scanning.md.
* Photo mode has no on-device reconstruction; use the desktop workflow.
* The transfer protocol requires the phone app (or its foreground service) to be running.
* Blender: only 5.2.2 is tested.

## Troubleshooting
| Symptom | Likely cause | What to do |
|---|---|---|
| "ARCore depth" option disabled | Device not ARCore-certified or no Depth API | Use **Marker board** scanning (docs/marker-scanning.md) |
| Marker scan: board not detected | Glare, blur, too little of the board visible | See docs/marker-scanning.md § Troubleshooting |
| Tracking lost repeatedly | Dark, featureless or fast motion | More light, textured surroundings, move slower |
| "Insufficient coverage" | Not enough directions observed | Rescan walking all the way around and from above |
| Mesh has many holes | Weak depth (shiny/dark areas) or High preset with noisy depth | Use Standard, better light, closer distance |
| Blender: "Cannot reach …" | Different networks, guest Wi-Fi isolation, phone server off | Same Wi-Fi, open Transfer screen, check address |
| Blender: "Pairing code does not match" | Typo or wrong device | Re-enter code; check address |
| Blender: "different certificate than when it was paired" | App reinstalled / identity reset — or interception | Forget phone and pair again only if you reset the app |
| Blender: "already in the file" | Duplicate protection | Enable Allow Duplicate or delete the old object |
| Imported scan lies on its side | Exported with a non-ScanForge tool or wrong axis | Import Settings → File Up Axis |
| Wrong size in Blender | Scene Unit Scale or file unit mismatch | Keep "Respect Scene Unit Scale"; set File Unit for foreign files |
