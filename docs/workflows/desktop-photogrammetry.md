# Optional desktop photogrammetry (hybrid workflow)

For higher geometric detail than phone depth allows, or for devices without the Depth API (Photo mode).
This is optional; the app never requires it.

1. On the phone: project › *Export package with captures* (or Export › Package › include source captures).
2. On the computer, unzip the `.sf3d`; the `capture/` folder holds JPEG keyframes, ARCore poses and intrinsics.
3. Convert to a COLMAP model with **known metric poses**:
   `core/cli/build/install/scanforge-cli/bin/scanforge-cli colmap capture/ colmap_ws/`
   (writes `colmap_ws/images/` and `colmap_ws/sparse/0/{cameras,images,points3D}.txt`; conventions in
   `core/reconstruction/.../ColmapExport.kt`, reprojection-tested).
4. With COLMAP (BSD; install separately):
   ```
   colmap feature_extractor --database_path db.db --image_path colmap_ws/images
   colmap exhaustive_matcher --database_path db.db
   colmap point_triangulator --database_path db.db --image_path colmap_ws/images --input_path colmap_ws/sparse/0 --output_path colmap_ws/tri
   colmap image_undistorter --image_path colmap_ws/images --input_path colmap_ws/tri --output_path colmap_ws/dense
   colmap patch_match_stereo --workspace_path colmap_ws/dense
   colmap stereo_fusion --workspace_path colmap_ws/dense --output_path fused.ply
   colmap poisson_mesher --input_path fused.ply --output_path mesh.ply
   ```
   `point_triangulator` keeps the supplied poses, so the result stays in ARCore's metric world space (+Y up).
   Note: `image_ids` in `images.txt` must match the database; if COLMAP assigns different IDs, use COLMAP's
   documented procedure for reconstruction from known poses.
5. Import `mesh.ply` in Blender with *File › Import › ScanForge 3D* and File Up Axis = +Y, File Unit = m.

Status: the COLMAP export is implemented and unit-tested; the COLMAP command sequence above is documented from COLMAP's
public CLI and has **not** been run in this environment.
