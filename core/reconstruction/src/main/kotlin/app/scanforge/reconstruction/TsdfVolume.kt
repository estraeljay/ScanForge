package app.scanforge.reconstruction

import app.scanforge.geometry.Aabb
import app.scanforge.geometry.Pose
import app.scanforge.geometry.Vec3
import java.io.DataInputStream
import java.io.DataOutputStream
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.roundToInt

/** Tunables for depth integration. */
data class IntegrationParams(
    val minDepth: Double = 0.15,
    val maxDepth: Double = 3.0,
    /** Pixels with ARCore confidence below this (0..1) are ignored. */
    val minConfidence: Double = 0.25,
    /** Use every n-th depth pixel when allocating blocks. Integration itself visits every voxel. */
    val allocationStride: Int = 1,
)

data class IntegrationStats(val validPixels: Int, val allocatedBlocks: Int, val updatedVoxels: Int)

/**
 * Truncated signed distance volume stored as sparse 8³ voxel blocks (hashed by block coordinate),
 * so memory scales with observed surface area instead of the bounding volume.
 *
 * Sign convention: positive in observed free space in front of a surface, negative behind it.
 * Distances are normalized by [truncation] into [-1, 1]. Voxel (i, j, k) samples world point
 * (i, j, k) · [voxelSize].
 */
class TsdfVolume(
    val voxelSize: Double,
    val truncation: Double = voxelSize * 4,
    val roi: Aabb? = null,
    val maxWeight: Float = 100f,
) {
    class Block {
        val tsdf = FloatArray(N) { 1f }
        val weight = FloatArray(N)
        val color = FloatArray(N * 3)
        val colorWeight = FloatArray(N)
        /** Number of frames that updated each voxel, saturating at 255. */
        val observations = ByteArray(N)
    }

    @PublishedApi internal val blocks = HashMap<Long, Block>()
    val blockCount get() = blocks.size
    private val blockMeters = voxelSize * B

    fun approximateBytes(): Long = blocks.size.toLong() * N * (4 + 4 + 12 + 4 + 1)

    private fun allocate(bx: Int, by: Int, bz: Int): Boolean {
        val key = key(bx, by, bz)
        if (blocks.containsKey(key)) return false
        if (roi != null) {
            val lo = Vec3(bx * blockMeters, by * blockMeters, bz * blockMeters)
            val hi = lo + Vec3(blockMeters, blockMeters, blockMeters)
            if (hi.x < roi.min.x || hi.y < roi.min.y || hi.z < roi.min.z || lo.x > roi.max.x || lo.y > roi.max.y || lo.z > roi.max.z) return false
        }
        blocks[key] = Block()
        return true
    }

    /**
     * Fuses one depth frame. [pose] maps camera to world (ARCore convention). The optional [color]
     * image is sampled with [colorIntrinsics] (same camera pose) to accumulate per-voxel color.
     */
    fun integrate(
        depth: DepthFrame,
        depthIntrinsics: CameraIntrinsics,
        pose: Pose,
        color: RgbImage? = null,
        colorIntrinsics: CameraIntrinsics? = null,
        params: IntegrationParams = IntegrationParams(),
    ): IntegrationStats {
        require(depth.width == depthIntrinsics.width && depth.height == depthIntrinsics.height) {
            "Depth intrinsics ${depthIntrinsics.width}x${depthIntrinsics.height} do not match image ${depth.width}x${depth.height}"
        }
        val r = pose.rotation.toMatrix()
        val t = pose.translation
        // 1) Allocate blocks around valid depth samples.
        var valid = 0; var allocated = 0
        val step = blockMeters * 0.5
        for (v in 0 until depth.height step params.allocationStride) for (u in 0 until depth.width step params.allocationStride) {
            val d = depth.depthMetersAt(u, v)
            if (d < params.minDepth || d > params.maxDepth || depth.confidenceAt(u, v) < params.minConfidence) continue
            valid++
            val pc = depthIntrinsics.unproject(u.toDouble(), v.toDouble(), d)
            val pw = r * pc + t
            val dir = (pw - t).normalized()
            var s = -truncation
            while (s <= truncation + 1e-12) {
                val q = pw + dir * s
                if (allocate(floor(q.x / blockMeters).toInt(), floor(q.y / blockMeters).toInt(), floor(q.z / blockMeters).toInt())) allocated++
                s += step
            }
        }
        // 2) Projective update of every voxel in every block that may be visible.
        // Camera from world: p_c = Rᵀ (p_w − t), written out with primitives for speed.
        val r00 = r[0, 0]; val r01 = r[0, 1]; val r02 = r[0, 2]
        val r10 = r[1, 0]; val r11 = r[1, 1]; val r12 = r[1, 2]
        val r20 = r[2, 0]; val r21 = r[2, 1]; val r22 = r[2, 2]
        val k = depthIntrinsics
        val kc = colorIntrinsics
        val rgb = IntArray(3)
        var updated = 0
        val halfDiag = blockMeters * 0.87
        for ((key, block) in blocks) {
            val bx = keyX(key); val by = keyY(key); val bz = keyZ(key)
            // Frustum pre-check on the block center.
            val cxw = (bx + 0.5) * blockMeters - t.x; val cyw = (by + 0.5) * blockMeters - t.y; val czw = (bz + 0.5) * blockMeters - t.z
            val bzc = r02 * cxw + r12 * cyw + r22 * czw
            if (-bzc + halfDiag < params.minDepth || -bzc - halfDiag > params.maxDepth + truncation) continue
            for (lz in 0 until B) for (ly in 0 until B) for (lx in 0 until B) {
                val wx = (bx * B + lx) * voxelSize - t.x
                val wy = (by * B + ly) * voxelSize - t.y
                val wz = (bz * B + lz) * voxelSize - t.z
                val xc = r00 * wx + r10 * wy + r20 * wz
                val yc = r01 * wx + r11 * wy + r21 * wz
                val zc = r02 * wx + r12 * wy + r22 * wz
                val dist = -zc
                if (dist < params.minDepth) continue
                val ui = (k.fx * xc / dist + k.cx).roundToInt()
                val vi = (k.cy - k.fy * yc / dist).roundToInt()
                if (ui < 0 || vi < 0 || ui >= depth.width || vi >= depth.height) continue
                val measured = depth.depthMetersAt(ui, vi)
                if (measured < params.minDepth || measured > params.maxDepth) continue
                val conf = depth.confidenceAt(ui, vi)
                if (conf < params.minConfidence) continue
                val sdf = measured - dist
                if (sdf < -truncation) continue // occluded: behind the observed surface
                val value = minOf(1.0, sdf / truncation).toFloat()
                // Depth noise grows roughly quadratically with range, so down-weight far samples.
                val w = (conf / (1.0 + measured * measured)).toFloat()
                val idx = (lz * B + ly) * B + lx
                val w0 = block.weight[idx]
                block.tsdf[idx] = (block.tsdf[idx] * w0 + value * w) / (w0 + w)
                block.weight[idx] = minOf(maxWeight, w0 + w)
                val obs = block.observations[idx].toInt() and 0xff
                if (obs < 255) block.observations[idx] = (obs + 1).toByte()
                if (color != null && kc != null && abs(sdf) < truncation * 0.5) {
                    val uc = kc.fx * xc / dist + kc.cx
                    val vc = kc.cy - kc.fy * yc / dist
                    if (color.sample(uc, vc, rgb)) {
                        val cw0 = block.colorWeight[idx]
                        val cw = cw0 + w
                        for (c in 0 until 3) block.color[idx * 3 + c] = (block.color[idx * 3 + c] * cw0 + rgb[c] * w) / cw
                        block.colorWeight[idx] = minOf(maxWeight, cw)
                    }
                }
                updated++
            }
        }
        return IntegrationStats(valid, allocated, updated)
    }

    /** Voxel accessor used by extraction; returns false when the voxel has never been observed. */
    internal inline fun <R> withVoxel(i: Int, j: Int, k: Int, fn: (Block, Int) -> R): R? {
        val bx = Math.floorDiv(i, B); val by = Math.floorDiv(j, B); val bz = Math.floorDiv(k, B)
        val block = blocks[key(bx, by, bz)] ?: return null
        val idx = ((k - bz * B) * B + (j - by * B)) * B + (i - bx * B)
        return fn(block, idx)
    }

    internal fun forEachBlock(fn: (bx: Int, by: Int, bz: Int, Block) -> Unit) {
        for ((key, b) in blocks) fn(keyX(key), keyY(key), keyZ(key), b)
    }

    /** Fraction of near-surface voxels (|tsdf| < 0.5) whose observation count is at least [minObservations]. */
    fun surfaceObservationStats(minObservations: Int): Pair<Int, Int> {
        var surface = 0; var strong = 0
        for (b in blocks.values) for (i in 0 until N) {
            if (b.weight[i] <= 0f || abs(b.tsdf[i]) >= 0.5f) continue
            surface++
            if ((b.observations[i].toInt() and 0xff) >= minObservations) strong++
        }
        return surface to strong
    }

    /** Writes the full volume state (for interrupted-reconstruction recovery). */
    fun save(out: DataOutputStream) {
        out.writeInt(CHECKPOINT_MAGIC)
        out.writeDouble(voxelSize); out.writeDouble(truncation)
        out.writeInt(blocks.size)
        for ((key, b) in blocks) {
            out.writeLong(key)
            for (i in 0 until N) {
                out.writeFloat(b.tsdf[i]); out.writeFloat(b.weight[i]); out.writeFloat(b.colorWeight[i])
                out.writeFloat(b.color[i * 3]); out.writeFloat(b.color[i * 3 + 1]); out.writeFloat(b.color[i * 3 + 2])
            }
            out.write(b.observations)
        }
    }

    companion object {
        const val B = 8
        const val N = B * B * B
        private const val CHECKPOINT_MAGIC = 0x53465456 // "SFTV"
        private const val OFF = 1 shl 20
        private const val MASK = (1L shl 21) - 1

        fun key(bx: Int, by: Int, bz: Int): Long =
            ((bx + OFF).toLong() and MASK) or (((by + OFF).toLong() and MASK) shl 21) or (((bz + OFF).toLong() and MASK) shl 42)

        fun keyX(k: Long) = (k and MASK).toInt() - OFF
        fun keyY(k: Long) = ((k shr 21) and MASK).toInt() - OFF
        fun keyZ(k: Long) = ((k shr 42) and MASK).toInt() - OFF

        fun load(input: DataInputStream, roi: Aabb?): TsdfVolume {
            require(input.readInt() == CHECKPOINT_MAGIC) { "Not a TSDF checkpoint" }
            val vol = TsdfVolume(input.readDouble(), input.readDouble(), roi)
            repeat(input.readInt()) {
                val key = input.readLong()
                val b = Block()
                for (i in 0 until N) {
                    b.tsdf[i] = input.readFloat(); b.weight[i] = input.readFloat(); b.colorWeight[i] = input.readFloat()
                    b.color[i * 3] = input.readFloat(); b.color[i * 3 + 1] = input.readFloat(); b.color[i * 3 + 2] = input.readFloat()
                }
                input.readFully(b.observations)
                vol.blocks[key] = b
            }
            return vol
        }
    }
}
