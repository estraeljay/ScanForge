# ScanForge 3D — Blender extension

Location: `blender/scanforge3d/` (extension source), built zip: `dist/scanforge3d-<version>.zip`.

## Install
1. Build: `scripts/build_blender_extension.sh` (uses `blender --command extension build` when Blender is on PATH).
2. Blender 5.2+: *Edit › Preferences › Get Extensions › ⌄ › Install from Disk…* and pick the zip.
   (Verified: the zip installs and enables via Blender 5.2.2's extension installer.)
3. Open *View3D › Sidebar (N) › ScanForge*.

## Use
* **Connection**: on the phone open *Transfer › Connect to Blender*. Enter the phone's address and the 16-character
  pairing code in Blender, press *Pair with Phone*, approve on the phone. Afterwards *Reconnect / Check for Scans*.
  *Find Phones* broadcasts on the LAN to fill in the address.
* **Incoming Scans**: send from the phone; *Receive & Import* downloads with resume, verifies SHA-256 per chunk and
  per file, confirms receipt to the phone and imports. *Cancel* deletes partial data.
* **Manual import** (no network): *File › Import › ScanForge 3D* accepts `.sf3d`, `.ply`, `.obj`, `.stl`, `.glb`.
* **Import Settings**: new collection per scan, origin (bottom center / center / scan origin), colors + material,
  respect scene unit scale, apply calibration, file up axis/unit for foreign files.
* **Scan Information**: size, scale source and factor, device, mesh status, reconstruction warnings, phone measurements.
* **Mesh Tools** (explicit, undoable): recalculate normals, remove loose pieces, merge by distance, decimate modifier,
  orient to recorded ground plane, set origin, bounding box object, dimension report (text block + CSV).

Nothing modifies the mesh on import. Network work runs on a background thread; results are applied on Blender's main
thread via `bpy.app.timers`.

## Security notes
TLS with certificate pinning (pairing code = fingerprint prefix), explicit approval on the phone, bearer token stored
in `pairings.json` (mode 0600) in the extension's user directory. Blender only makes outgoing connections.

## Tests
`pip install bpy==5.2.2` (Python 3.13) then `scripts/test_blender.sh $(which python)`; generates SYNTHETIC fixtures with
the core CLI and runs 26 tests including transfers against the real protocol server.
