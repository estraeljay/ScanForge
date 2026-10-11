package app.scanforge.markers

import app.scanforge.geometry.Pose
import app.scanforge.geometry.Vec3

/** Renders a grayscale photo of a board lying on a mid-gray table, with 3×3 supersampling. */
object BoardRender {
    fun render(board: BoardLayout, pose: Pose, k: Pinhole, w: Int, h: Int, occluder: ((Vec3) -> Boolean)? = null): ByteArray {
        val img = ByteArray(w * h)
        val inv = pose.rotation.toMatrix()
        val o = pose.translation
        val halfW = board.spec.paper.widthMm / 2000; val halfH = board.spec.paper.heightMm / 2000
        val (cxMm, cyMm) = board.centerMm
        val paperCx = (board.spec.paper.widthMm / 2 - cxMm) / 1000; val paperCz = (board.spec.paper.heightMm / 2 - cyMm) / 1000
        for (v in 0 until h) for (u in 0 until w) {
            var acc = 0
            for (sy in 0 until 3) for (sx in 0 until 3) {
                val pu = u - 1.0 / 3 + sx / 3.0; val pv = v - 1.0 / 3 + sy / 3.0
                val dirCam = Vec3((pu - k.cx) / k.fx, -(pv - k.cy) / k.fy, -1.0)
                val d = inv * dirCam
                if (d.y >= -1e-9) { acc += 90; continue }
                val tHit = -o.y / d.y
                val p = o + d * tHit
                acc += when {
                    occluder != null && occluder(p) -> 140
                    kotlin.math.abs(p.x - paperCx) > halfW || kotlin.math.abs(p.z - paperCz) > halfH -> 90
                    board.isBlackAtWorld(p.x, p.z) -> 20
                    else -> 235
                }
            }
            img[v * w + u] = (acc / 9).toByte()
        }
        return img
    }
}
