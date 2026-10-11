# Scanning with the printed marker board (no ARCore needed)

Marker-board scanning works on any Android phone with a rear camera. It is the default method in the app. It is meant
for objects that fit on a sheet, up to an A3 footprint (≈ 30 × 42 cm). Design: [ADR 0004](adr/0004-marker-board-scanning.md).

> **Status:** the reconstruction is tested on synthetic photos only. The app compiles in CI but has **not yet been run on
> a phone**, so treat the first real scans as a test.

## 1. Pick and print the board
| Board | Paper | Objects up to (footprint) | Markers |
|---|---|---|---|
| A4 | 210 × 297 mm | ≈ 15 × 20 cm | 30 |
| A3 | 297 × 420 mm | ≈ 20 × 28 cm | 48 |
| **A2 (recommended for A3-size objects)** | 420 × 594 mm | ≈ 30 × 42 cm | 88 |

* In the app: **New scan → Marker board → choose size → Print board (PDF)**. Or on a computer:
  `scanforge-cli board board-a2.pdf --size a2`.
* Print at **100 % / "actual size"**. Do not use "fit to page". A2 can be printed at a copy shop.
* Matte paper is best. Glossy paper reflects lamps and hides markers.
* Glue or tape the sheet onto something flat, such as cardboard or a table. A wavy sheet makes poses and scale worse.

## 2. Check the print scale (once per printed board)
Measure the bar labelled **100 mm** with a ruler and enter the length under **Check print scale**. The correction is saved
for that board and applied to every scan made with it. Without this check, the app assumes the print is exactly 100 %
and says so.

## 3. Scan
1. Put the board on a table in **even, bright light**, then put the object **in the middle**. Leave the markers around
   it visible.
2. Press **Start**. The HUD shows how many markers are visible and the pose error in pixels.
3. Walk slowly around the object about **35–60 cm** away, keeping much of the board in view. Photos are taken
   **automatically** when the view has changed enough (about 4 cm or 8°) and the frame is sharp.
4. Go around again **from higher up**, then take a few photos **from above**. Follow the on-screen guidance.
5. Press **Finish**. You need at least 8 photos; the Standard preset allows up to 60. Reconstruction runs on the phone
   and keeps running if you leave the screen.

## 4. What works and what doesn't
* **Works best:** matte objects with visible texture, print or wood grain, such as boxes, toys, shoes, tools and mugs
  with a pattern.
* **Weak or fails:** plain white or single-colour surfaces, glossy, transparent or very dark objects. Fine features
  thinner than about 2 voxels (Draft about 12 mm; Standard and High about 8 mm) are lost.
* **Quality presets in marker mode:** Draft uses 6 mm voxels and up to 40 photos. Standard uses 4 mm voxels and up to
  60 photos. High also uses 4 mm voxels but allows up to 90 photos. Finer voxels made results worse on synthetic tests;
  see the validation doc.
* The underside is never photographed, so meshes are open at the bottom and volume is not reported.
* Dimensions are reported with confidence levels. The "height above support" uses the exact board plane.

## Troubleshooting
| Symptom | Likely cause | What to do |
|---|---|---|
| "Point the camera at the marker board" | Board not detected | More light, less glare, hold the phone steady, show more of the board |
| "Show more of the board" | Fewer than 6 markers, or a poor pose | Step back or tilt the phone; the object should not cover most of the board |
| Few photos are taken | Motion blur or no new viewpoint | Move more slowly; walk further around the object |
| "The marker board was not found in enough photos…" | Too few usable photos | Rescan with more of the board in view |
| "No reliable 3D points could be matched between photos." | Textureless object or poor light | Add texture, such as removable stickers or masking tape with marks, or use better light |
| Sizes about 1–3 % off | Print scaled | Run **Check print scale** |
