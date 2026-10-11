package app.scanforge.reconstruction

import app.scanforge.geometry.Mesh

/** Per-vertex evidence extracted alongside the mesh, used for coverage and confidence reporting. */
class VertexEvidence(
    /** Mean fusion weight of the 8 cell corners. */
    val weight: FloatArray,
    /** Minimum observation count over the 8 cell corners. */
    val observations: IntArray,
)

class ExtractedSurface(val mesh: Mesh, val evidence: VertexEvidence)

/**
 * Naive Surface Nets iso-surface extraction (Gibson 1998) over the sparse TSDF. Each grid cell whose
 * corners straddle the zero level gets one vertex at the mean of its edge crossings; every sign-changing
 * grid edge emits a quad joining the four cells around it. Cells with any unobserved corner (weight below
 * [minWeight]) are skipped, so the mesh never invents surface where there is no evidence.
 */
object SurfaceNets {
    private val CORNERS = arrayOf(
        intArrayOf(0, 0, 0), intArrayOf(1, 0, 0), intArrayOf(0, 1, 0), intArrayOf(1, 1, 0),
        intArrayOf(0, 0, 1), intArrayOf(1, 0, 1), intArrayOf(0, 1, 1), intArrayOf(1, 1, 1),
    )
    private val EDGES = arrayOf(
        intArrayOf(0, 1), intArrayOf(2, 3), intArrayOf(4, 5), intArrayOf(6, 7),
        intArrayOf(0, 2), intArrayOf(1, 3), intArrayOf(4, 6), intArrayOf(5, 7),
        intArrayOf(0, 4), intArrayOf(1, 5), intArrayOf(2, 6), intArrayOf(3, 7),
    )

    private fun cellKey(i: Int, j: Int, k: Int) = TsdfVolume.key(i, j, k)

    fun extract(volume: TsdfVolume, minWeight: Float = 0.05f): ExtractedSurface {
        val vs = volume.voxelSize
        val cellVertex = HashMap<Long, Int>()
        val positions = ArrayList<Float>()
        val colors = ArrayList<Byte>()
        val weights = ArrayList<Float>()
        val observations = ArrayList<Int>()
        val values = FloatArray(8)
        val cw = FloatArray(8)
        val cr = FloatArray(24)
        val obs = IntArray(8)
        val wts = FloatArray(8)

        fun loadCell(i: Int, j: Int, k: Int): Boolean {
            for (c in 0 until 8) {
                val ok = volume.withVoxel(i + CORNERS[c][0], j + CORNERS[c][1], k + CORNERS[c][2]) { b, idx ->
                    if (b.weight[idx] < minWeight) false else {
                        values[c] = b.tsdf[idx]; wts[c] = b.weight[idx]; cw[c] = b.colorWeight[idx]
                        cr[c * 3] = b.color[idx * 3]; cr[c * 3 + 1] = b.color[idx * 3 + 1]; cr[c * 3 + 2] = b.color[idx * 3 + 2]
                        obs[c] = b.observations[idx].toInt() and 0xff
                        true
                    }
                } ?: false
                if (!ok) return false
            }
            return true
        }

        // Pass 1: one vertex per surface-crossing cell.
        volume.forEachBlock { bx, by, bz, _ ->
            for (lz in 0 until TsdfVolume.B) for (ly in 0 until TsdfVolume.B) for (lx in 0 until TsdfVolume.B) {
                val i = bx * TsdfVolume.B + lx; val j = by * TsdfVolume.B + ly; val k = bz * TsdfVolume.B + lz
                if (!loadCell(i, j, k)) continue
                var neg = 0
                for (c in 0 until 8) if (values[c] < 0) neg++
                if (neg == 0 || neg == 8) continue
                var sx = 0.0; var sy = 0.0; var sz = 0.0; var n = 0
                for (e in EDGES) {
                    val a = values[e[0]]; val b = values[e[1]]
                    if ((a < 0) == (b < 0)) continue
                    val t = a / (a - b)
                    val ca = CORNERS[e[0]]; val cb = CORNERS[e[1]]
                    sx += ca[0] + (cb[0] - ca[0]) * t; sy += ca[1] + (cb[1] - ca[1]) * t; sz += ca[2] + (cb[2] - ca[2]) * t
                    n++
                }
                cellVertex[cellKey(i, j, k)] = positions.size / 3
                positions += ((i + sx / n) * vs).toFloat(); positions += ((j + sy / n) * vs).toFloat(); positions += ((k + sz / n) * vs).toFloat()
                var tw = 0f; var r = 0f; var g = 0f; var bl = 0f; var wsum = 0f; var omin = 255
                for (c in 0 until 8) {
                    tw += cw[c]; r += cr[c * 3] * cw[c]; g += cr[c * 3 + 1] * cw[c]; bl += cr[c * 3 + 2] * cw[c]
                    wsum += wts[c]; omin = minOf(omin, obs[c])
                }
                if (tw > 0) {
                    colors += (r / tw).toInt().coerceIn(0, 255).toByte(); colors += (g / tw).toInt().coerceIn(0, 255).toByte(); colors += (bl / tw).toInt().coerceIn(0, 255).toByte()
                } else {
                    colors += 160.toByte(); colors += 160.toByte(); colors += 160.toByte()
                }
                weights += wsum / 8f
                observations += omin
            }
        }

        // Pass 2: a quad for each sign-changing edge leaving a cell's minimum corner.
        val indices = ArrayList<Int>()
        val p = positions
        fun pos(v: Int, a: Int) = p[v * 3 + a].toDouble()
        for ((key, _) in cellVertex) {
            val i = TsdfVolume.keyX(key); val j = TsdfVolume.keyY(key); val k = TsdfVolume.keyZ(key)
            val v0 = volume.withVoxel(i, j, k) { b, idx -> b.tsdf[idx] } ?: continue
            for (axis in 0 until 3) {
                val v1 = when (axis) {
                    0 -> volume.withVoxel(i + 1, j, k) { b, idx -> b.tsdf[idx] }
                    1 -> volume.withVoxel(i, j + 1, k) { b, idx -> b.tsdf[idx] }
                    else -> volume.withVoxel(i, j, k + 1) { b, idx -> b.tsdf[idx] }
                } ?: continue
                if ((v0 < 0) == (v1 < 0)) continue
                // The four cells sharing this edge, in cyclic order around the axis.
                val quad = when (axis) {
                    0 -> arrayOf(intArrayOf(i, j - 1, k - 1), intArrayOf(i, j, k - 1), intArrayOf(i, j, k), intArrayOf(i, j - 1, k))
                    1 -> arrayOf(intArrayOf(i - 1, j, k - 1), intArrayOf(i, j, k - 1), intArrayOf(i, j, k), intArrayOf(i - 1, j, k))
                    else -> arrayOf(intArrayOf(i - 1, j - 1, k), intArrayOf(i, j - 1, k), intArrayOf(i, j, k), intArrayOf(i - 1, j, k))
                }
                val q = IntArray(4)
                var complete = true
                for (c in 0 until 4) {
                    val idx = cellVertex[cellKey(quad[c][0], quad[c][1], quad[c][2])]
                    if (idx == null) { complete = false; break }
                    q[c] = idx
                }
                if (!complete) continue
                // Orient so the face normal points toward positive (free-space) TSDF values.
                val outward = if (v0 < 0) 1.0 else -1.0
                val d1 = DoubleArray(3) { pos(q[2], it) - pos(q[0], it) }
                val d2 = DoubleArray(3) { pos(q[3], it) - pos(q[1], it) }
                val nAxis = when (axis) {
                    0 -> d1[1] * d2[2] - d1[2] * d2[1]
                    1 -> d1[2] * d2[0] - d1[0] * d2[2]
                    else -> d1[0] * d2[1] - d1[1] * d2[0]
                }
                if (nAxis * outward < 0) { val tmp = q[1]; q[1] = q[3]; q[3] = tmp }
                indices += q[0]; indices += q[1]; indices += q[2]
                indices += q[0]; indices += q[2]; indices += q[3]
            }
        }
        val mesh = Mesh(positions.toFloatArray(), indices.toIntArray(), colors.toByteArray())
        return ExtractedSurface(mesh, VertexEvidence(weights.toFloatArray(), observations.toIntArray()))
    }
}
