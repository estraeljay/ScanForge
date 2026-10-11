package app.scanforge.reconstruction

import app.scanforge.geometry.Mat3
import app.scanforge.geometry.Quat
import app.scanforge.geometry.Vec3
import java.io.File
import java.util.Locale

/**
 * Writes a capture's color keyframes and ARCore poses as a COLMAP text model (cameras.txt, images.txt,
 * empty points3D.txt) for the optional desktop photogrammetry workflow (docs/workflows/desktop-photogrammetry.md).
 *
 * Conventions converted here:
 *  - Camera axes: ARCore/OpenGL camera (+X right, +Y up, looks −Z) → COLMAP/OpenCV camera (+X right, +Y down, looks +Z),
 *    i.e. a 180° rotation about the camera X axis.
 *  - COLMAP stores world→camera rotation (qw qx qy qz) and translation t = −R·C.
 *  - Pixel centers: ScanForge puts the first pixel center at (0,0); COLMAP at (0.5,0.5), so cx, cy shift by +0.5.
 * World coordinates stay metric ARCore world space, so a reconstruction from these known poses keeps real scale.
 */
object ColmapExport {
    private val FLIP = Mat3(doubleArrayOf(1.0, 0.0, 0.0, 0.0, -1.0, 0.0, 0.0, 0.0, -1.0))

    data class Result(val images: Int, val cameras: Int, val dir: File)

    /** World→camera rotation and translation in COLMAP's convention for an ARCore camera-to-world pose. */
    fun worldToColmap(frame: FrameRecord): Pair<Quat, Vec3> {
        val pose = frame.pose
        val rCw = pose.rotation.toMatrix()              // camera(ARCore) → world
        val r = FLIP * rCw.transpose()                   // world → camera(COLMAP)
        val t = -(r * pose.translation)
        return Quat.fromMatrix(r) to t
    }

    fun export(captureDir: File, outDir: File, copyImages: Boolean = true): Result {
        val frames = DirectoryCaptureSource.readFrames(captureDir).filter { it.colorFile != null && it.colorIntrinsics != null && it.tracking == TrackingQuality.TRACKING }
        require(frames.isNotEmpty()) { "No tracked color keyframes in ${captureDir.path}" }
        val sparse = File(outDir, "sparse/0").apply { mkdirs() }
        val images = File(outDir, "images").apply { mkdirs() }
        val cameraIds = LinkedHashMap<CameraIntrinsics, Int>()
        frames.forEach { f -> cameraIds.getOrPut(f.colorIntrinsics!!) { cameraIds.size + 1 } }
        fun n(x: Double) = java.math.BigDecimal(String.format(Locale.ROOT, "%.10g", x)).stripTrailingZeros().toPlainString()
        File(sparse, "cameras.txt").writeText(buildString {
            append("# Camera list with one line of data per camera:\n#   CAMERA_ID, MODEL, WIDTH, HEIGHT, PARAMS[]\n")
            cameraIds.forEach { (k, id) -> append("$id PINHOLE ${k.width} ${k.height} ${n(k.fx)} ${n(k.fy)} ${n(k.cx + 0.5)} ${n(k.cy + 0.5)}\n") }
        })
        File(sparse, "images.txt").writeText(buildString {
            append("# Image list with two lines of data per image:\n#   IMAGE_ID, QW, QX, QY, QZ, TX, TY, TZ, CAMERA_ID, NAME\n#   POINTS2D[] as (X, Y, POINT3D_ID)\n")
            frames.forEachIndexed { i, f ->
                val (q, t) = worldToColmap(f)
                val name = File(f.colorFile!!).name
                append("${i + 1} ${n(q.w)} ${n(q.x)} ${n(q.y)} ${n(q.z)} ${n(t.x)} ${n(t.y)} ${n(t.z)} ${cameraIds[f.colorIntrinsics]} $name\n\n")
                if (copyImages) File(captureDir, f.colorFile).copyTo(File(images, name), overwrite = true)
            }
        })
        File(sparse, "points3D.txt").writeText("# Empty: triangulate with known poses (colmap point_triangulator).\n")
        return Result(frames.size, cameraIds.size, outDir)
    }

    /** Projects a world point with COLMAP parameters (used to verify the conversion). Returns pixel (x, y). */
    fun projectColmap(q: Quat, t: Vec3, k: CameraIntrinsics, world: Vec3): Pair<Double, Double> {
        val p = q.toMatrix() * world + t
        return (k.fx * p.x / p.z + (k.cx + 0.5)) to (k.fy * p.y / p.z + (k.cy + 0.5))
    }
}
