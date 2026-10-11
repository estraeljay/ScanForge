package app.scanforge.geometry

import kotlin.math.abs

/** Topological summary of a mesh used for warnings and to decide which measurements are meaningful. */
data class MeshTopology(
    val vertexCount: Int,
    val triangleCount: Int,
    val degenerateTriangles: Int,
    /** Edges used by exactly one triangle (holes / open borders). */
    val boundaryEdges: Int,
    /** Edges used by more than two triangles. */
    val nonManifoldEdges: Int,
    /** Interior edges whose two triangles traverse it in the same direction (inconsistent winding). */
    val inconsistentlyOrientedEdges: Int,
    /** Number of closed boundary loops, i.e. holes. */
    val holeCount: Int,
    val componentCount: Int,
) {
    /** A closed, consistently oriented 2-manifold encloses a well-defined volume. */
    val isWatertight get() =
        triangleCount > 0 && boundaryEdges == 0 && nonManifoldEdges == 0 && inconsistentlyOrientedEdges == 0
}

object MeshAnalysis {

    fun triangleArea(m: Mesh, t: Int): Double {
        val (a, b, c) = m.triangle(t)
        return ((m.vertex(b) - m.vertex(a)) cross (m.vertex(c) - m.vertex(a))).length() * 0.5
    }

    /** Total surface area in square meters. */
    fun surfaceArea(m: Mesh): Double {
        var s = 0.0
        for (t in 0 until m.triangleCount) s += triangleArea(m, t)
        return s
    }

    /**
     * Signed volume by the divergence theorem (sum of signed tetrahedra against the origin).
     * Only meaningful for watertight meshes; callers must check [MeshTopology.isWatertight].
     */
    fun signedVolume(m: Mesh): Double {
        var v = 0.0
        for (t in 0 until m.triangleCount) {
            val (a, b, c) = m.triangle(t)
            v += m.vertex(a) dot (m.vertex(b) cross m.vertex(c))
        }
        return v / 6.0
    }

    private fun edgeKey(a: Int, b: Int): Long {
        val lo = minOf(a, b).toLong(); val hi = maxOf(a, b).toLong()
        return (lo shl 32) or hi
    }

    fun topology(m: Mesh): MeshTopology {
        // For each undirected edge: count uses and the net direction (+1 for lo->hi, -1 for hi->lo).
        val uses = HashMap<Long, IntArray>(m.triangleCount * 2)
        var degenerate = 0
        for (t in 0 until m.triangleCount) {
            val (a, b, c) = m.triangle(t)
            if (a == b || b == c || a == c || triangleArea(m, t) < 1e-18) degenerate++
            for ((u, v) in arrayOf(a to b, b to c, c to a)) {
                if (u == v) continue
                val e = uses.getOrPut(edgeKey(u, v)) { IntArray(2) }
                e[0]++
                e[1] += if (u < v) 1 else -1
            }
        }
        var boundary = 0; var nonManifold = 0; var inconsistent = 0
        val boundaryAdj = HashMap<Int, MutableList<Int>>()
        for ((key, e) in uses) {
            when {
                e[0] == 1 -> {
                    boundary++
                    val a = (key ushr 32).toInt(); val b = (key and 0xffffffffL).toInt()
                    boundaryAdj.getOrPut(a) { mutableListOf() }.add(b)
                    boundaryAdj.getOrPut(b) { mutableListOf() }.add(a)
                }
                e[0] == 2 && e[1] != 0 -> inconsistent++
                e[0] > 2 -> nonManifold++
            }
        }
        return MeshTopology(
            vertexCount = m.vertexCount,
            triangleCount = m.triangleCount,
            degenerateTriangles = degenerate,
            boundaryEdges = boundary,
            nonManifoldEdges = nonManifold,
            inconsistentlyOrientedEdges = inconsistent,
            holeCount = countLoops(boundaryAdj),
            componentCount = connectedComponents(m).count,
        )
    }

    /** Counts connected components of the boundary-edge graph (each is a hole outline). */
    private fun countLoops(adj: Map<Int, List<Int>>): Int {
        val seen = HashSet<Int>()
        var loops = 0
        for (start in adj.keys) {
            if (!seen.add(start)) continue
            loops++
            val stack = ArrayDeque<Int>().apply { add(start) }
            while (stack.isNotEmpty()) {
                for (n in adj[stack.removeLast()].orEmpty()) if (seen.add(n)) stack.add(n)
            }
        }
        return loops
    }

    class Components(val count: Int, /** component id per triangle */ val triangleComponent: IntArray) {
        fun triangleCounts(): IntArray = IntArray(count).also { c -> triangleComponent.forEach { c[it]++ } }
    }

    /** Connected components over shared vertices, computed with union-find. */
    fun connectedComponents(m: Mesh): Components {
        val parent = IntArray(m.vertexCount) { it }
        fun find(x: Int): Int {
            var r = x
            while (parent[r] != r) r = parent[r]
            var c = x
            while (parent[c] != r) { val n = parent[c]; parent[c] = r; c = n }
            return r
        }
        fun union(a: Int, b: Int) { val ra = find(a); val rb = find(b); if (ra != rb) parent[ra] = rb }
        for (t in 0 until m.triangleCount) {
            val (a, b, c) = m.triangle(t); union(a, b); union(b, c)
        }
        val ids = HashMap<Int, Int>()
        val tri = IntArray(m.triangleCount) { t -> ids.getOrPut(find(m.indices[t * 3])) { ids.size } }
        return Components(ids.size, tri)
    }

    /** Area-weighted centroid of the surface. */
    fun surfaceCentroid(m: Mesh): Vec3 {
        var acc = Vec3.ZERO; var total = 0.0
        for (t in 0 until m.triangleCount) {
            val (a, b, c) = m.triangle(t)
            val area = triangleArea(m, t)
            acc += (m.vertex(a) + m.vertex(b) + m.vertex(c)) * (area / 3.0)
            total += area
        }
        return if (total > 0) acc / total else Vec3.ZERO
    }

    fun nearlyEqual(a: Double, b: Double, eps: Double) = abs(a - b) <= eps
}
