package app.scanforge.geometry

import kotlin.math.floor

/**
 * Non-destructive mesh editing operations. Each returns a new [Mesh]; inputs are never mutated.
 */
object MeshOps {

    /** Keeps triangles for which [keep] returns true and drops vertices no longer referenced. */
    fun filterTriangles(m: Mesh, keep: (Int) -> Boolean): Mesh = filterTrianglesWithMap(m, keep).first

    /**
     * Like [filterTriangles] but also returns, for each output vertex, its index in the input mesh,
     * so per-vertex attributes held outside the mesh can follow the edit.
     */
    fun filterTrianglesWithMap(m: Mesh, keep: (Int) -> Boolean): Pair<Mesh, IntArray> {
        val remap = IntArray(m.vertexCount) { -1 }
        val newIdx = ArrayList<Int>(m.indices.size)
        var next = 0
        for (t in 0 until m.triangleCount) {
            if (!keep(t)) continue
            for (k in 0 until 3) {
                val v = m.indices[t * 3 + k]
                if (remap[v] < 0) remap[v] = next++
                newIdx.add(remap[v])
            }
        }
        val pos = FloatArray(next * 3)
        val col = m.colors?.let { ByteArray(next * 3) }
        for (v in 0 until m.vertexCount) {
            val n = remap[v]
            if (n < 0) continue
            System.arraycopy(m.positions, v * 3, pos, n * 3, 3)
            if (col != null) System.arraycopy(m.colors!!, v * 3, col, n * 3, 3)
        }
        val source = IntArray(next)
        for (v in 0 until m.vertexCount) if (remap[v] >= 0) source[remap[v]] = v
        return Mesh(pos, newIdx.toIntArray(), col) to source
    }

    /** Removes connected components with fewer than [minTriangles] triangles ("isolated pieces"). */
    fun removeSmallComponents(m: Mesh, minTriangles: Int): Mesh {
        val comps = MeshAnalysis.connectedComponents(m)
        val counts = comps.triangleCounts()
        return filterTriangles(m) { counts[comps.triangleComponent[it]] >= minTriangles }
    }

    /** Keeps only the largest connected component. */
    fun keepLargestComponent(m: Mesh): Mesh {
        if (m.triangleCount == 0) return m
        val comps = MeshAnalysis.connectedComponents(m)
        val counts = comps.triangleCounts()
        val best = counts.indices.maxBy { counts[it] }
        return filterTriangles(m) { comps.triangleComponent[it] == best }
    }

    /** Keeps triangles whose centroid lies inside [box]. */
    fun cropToBox(m: Mesh, box: Aabb): Mesh = filterTriangles(m) { box.contains(centroid(m, it)) }

    /** Removes triangles whose centroid lies on the negative side of the plane (n·p < d). */
    fun cutBelowPlane(m: Mesh, normal: Vec3, offset: Double): Mesh {
        val n = normal.normalized()
        return filterTriangles(m) { (n dot centroid(m, it)) >= offset }
    }

    fun deleteTriangles(m: Mesh, triangles: Set<Int>): Mesh = filterTriangles(m) { it !in triangles }

    fun centroid(m: Mesh, t: Int): Vec3 {
        val (a, b, c) = m.triangle(t)
        return (m.vertex(a) + m.vertex(b) + m.vertex(c)) / 3.0
    }

    /** Applies a rigid transform followed by a uniform [scale] about the origin. */
    fun transform(m: Mesh, pose: Pose, scale: Double = 1.0): Mesh {
        val out = FloatArray(m.positions.size)
        for (i in 0 until m.vertexCount) {
            val p = pose.transformPoint(m.vertex(i)) * scale
            out[i * 3] = p.x.toFloat(); out[i * 3 + 1] = p.y.toFloat(); out[i * 3 + 2] = p.z.toFloat()
        }
        return Mesh(out, m.indices.copyOf(), m.colors?.copyOf())
    }

    fun translate(m: Mesh, offset: Vec3) = transform(m, Pose(offset, Quat.IDENTITY))

    /** Reverses triangle winding (flips normals). */
    fun flipWinding(m: Mesh): Mesh {
        val idx = m.indices.copyOf()
        for (t in 0 until m.triangleCount) { val tmp = idx[t * 3 + 1]; idx[t * 3 + 1] = idx[t * 3 + 2]; idx[t * 3 + 2] = tmp }
        return Mesh(m.positions.copyOf(), idx, m.colors?.copyOf())
    }

    /** Merges vertices closer than [tolerance] meters (grid based) and drops collapsed triangles. */
    fun weldVertices(m: Mesh, tolerance: Double): Mesh = clusterVertices(m, tolerance)

    /**
     * Vertex-clustering simplification (Rossignac & Borrel): vertices are snapped to a uniform grid of
     * [cellSize] meters, each occupied cell becomes one vertex at the mean position, and triangles that
     * collapse are removed. Fast and robust; detail below the cell size is lost by design.
     */
    fun clusterVertices(m: Mesh, cellSize: Double): Mesh {
        require(cellSize > 0) { "cellSize must be positive" }
        val cellOf = HashMap<Triple<Long, Long, Long>, Int>()
        val remap = IntArray(m.vertexCount)
        val sum = ArrayList<DoubleArray>()
        for (v in 0 until m.vertexCount) {
            val p = m.vertex(v)
            val key = Triple(floor(p.x / cellSize).toLong(), floor(p.y / cellSize).toLong(), floor(p.z / cellSize).toLong())
            val id = cellOf.getOrPut(key) { sum.add(DoubleArray(7)); sum.size - 1 }
            remap[v] = id
            val s = sum[id]
            s[0] += p.x; s[1] += p.y; s[2] += p.z; s[6] += 1.0
            m.colors?.let { c ->
                s[3] += (c[v * 3].toInt() and 0xff); s[4] += (c[v * 3 + 1].toInt() and 0xff); s[5] += (c[v * 3 + 2].toInt() and 0xff)
            }
        }
        val pos = FloatArray(sum.size * 3)
        val col = m.colors?.let { ByteArray(sum.size * 3) }
        sum.forEachIndexed { i, s ->
            val n = s[6]
            pos[i * 3] = (s[0] / n).toFloat(); pos[i * 3 + 1] = (s[1] / n).toFloat(); pos[i * 3 + 2] = (s[2] / n).toFloat()
            col?.let { c ->
                c[i * 3] = (s[3] / n).toInt().toByte(); c[i * 3 + 1] = (s[4] / n).toInt().toByte(); c[i * 3 + 2] = (s[5] / n).toInt().toByte()
            }
        }
        val idx = ArrayList<Int>(m.indices.size)
        val seen = HashSet<Triple<Int, Int, Int>>()
        for (t in 0 until m.triangleCount) {
            val a = remap[m.indices[t * 3]]; val b = remap[m.indices[t * 3 + 1]]; val c = remap[m.indices[t * 3 + 2]]
            if (a == b || b == c || a == c) continue
            // Canonical rotation so duplicate faces (same winding) are only emitted once.
            val key = when (minOf(a, b, c)) { a -> Triple(a, b, c); b -> Triple(b, c, a); else -> Triple(c, a, b) }
            if (!seen.add(key)) continue
            idx.add(a); idx.add(b); idx.add(c)
        }
        return filterTriangles(Mesh(pos, idx.toIntArray(), col)) { true }
    }

    /**
     * Simplifies to approximately [targetTriangles] by searching for a clustering cell size
     * (bisection on the cell size). Returns the input when it is already small enough.
     */
    fun simplifyToTarget(m: Mesh, targetTriangles: Int): Mesh {
        if (m.triangleCount <= targetTriangles) return m
        val diag = m.bounds()!!.size.length()
        var lo = 0.0; var hi = diag / 2
        var best = clusterVertices(m, hi)
        repeat(24) {
            val mid = (lo + hi) / 2
            val candidate = clusterVertices(m, mid)
            if (candidate.triangleCount > targetTriangles) lo = mid else { hi = mid; best = candidate }
        }
        return best
    }
}
