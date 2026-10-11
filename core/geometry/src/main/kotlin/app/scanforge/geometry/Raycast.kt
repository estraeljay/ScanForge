package app.scanforge.geometry

/** Result of a ray/mesh intersection. */
data class RayHit(val distance: Double, val point: Vec3, val triangle: Int, val barycentricU: Double, val barycentricV: Double)

/**
 * Bounding-volume hierarchy over a mesh's triangles for fast ray picking, used to place
 * measurement points on the reconstructed surface.
 */
class MeshRaycaster(private val mesh: Mesh) {
    private class Node(val box: Aabb, val left: Node?, val right: Node?, val tris: IntArray?)

    private val root: Node? = if (mesh.triangleCount == 0) null else build((0 until mesh.triangleCount).toList().toIntArray())

    private fun triBox(t: Int): Aabb {
        val (a, b, c) = mesh.triangle(t)
        val va = mesh.vertex(a); val vb = mesh.vertex(b); val vc = mesh.vertex(c)
        return Aabb(Vec3.min(va, Vec3.min(vb, vc)), Vec3.max(va, Vec3.max(vb, vc)))
    }

    private fun build(tris: IntArray): Node {
        var box = triBox(tris[0])
        for (t in tris) { val b = triBox(t); box = Aabb(Vec3.min(box.min, b.min), Vec3.max(box.max, b.max)) }
        if (tris.size <= 8) return Node(box, null, null, tris)
        val size = box.size
        val axis = if (size.x >= size.y && size.x >= size.z) 0 else if (size.y >= size.z) 1 else 2
        val sorted = tris.sortedBy { MeshOps.centroid(mesh, it)[axis] }.toIntArray()
        val mid = sorted.size / 2
        return Node(box, build(sorted.copyOfRange(0, mid)), build(sorted.copyOfRange(mid, sorted.size)), null)
    }

    private fun hitsBox(box: Aabb, o: Vec3, invD: Vec3, maxT: Double): Boolean {
        var tmin = 0.0; var tmax = maxT
        for (axis in 0 until 3) {
            val t1 = (box.min[axis] - o[axis]) * invD[axis]
            val t2 = (box.max[axis] - o[axis]) * invD[axis]
            tmin = maxOf(tmin, minOf(t1, t2)); tmax = minOf(tmax, maxOf(t1, t2))
        }
        return tmin <= tmax
    }

    /** Möller–Trumbore ray/triangle intersection. Two-sided. */
    private fun intersect(t: Int, o: Vec3, d: Vec3): RayHit? {
        val (ia, ib, ic) = mesh.triangle(t)
        val a = mesh.vertex(ia); val e1 = mesh.vertex(ib) - a; val e2 = mesh.vertex(ic) - a
        val p = d cross e2
        val det = e1 dot p
        if (kotlin.math.abs(det) < 1e-15) return null
        val inv = 1.0 / det
        val s = o - a
        val u = (s dot p) * inv
        if (u < 0 || u > 1) return null
        val q = s cross e1
        val v = (d dot q) * inv
        if (v < 0 || u + v > 1) return null
        val dist = (e2 dot q) * inv
        if (dist <= 1e-12) return null
        return RayHit(dist, o + d * dist, t, u, v)
    }

    /** Returns the closest hit along the ray, or null. [direction] need not be normalized. */
    fun cast(origin: Vec3, direction: Vec3, maxDistance: Double = Double.MAX_VALUE): RayHit? {
        val d = direction.normalized()
        val inv = Vec3(1.0 / d.x, 1.0 / d.y, 1.0 / d.z)
        var best: RayHit? = null
        val stack = ArrayDeque<Node>()
        root?.let { stack.add(it) }
        while (stack.isNotEmpty()) {
            val n = stack.removeLast()
            if (!hitsBox(n.box, origin, inv, best?.distance ?: maxDistance)) continue
            if (n.tris != null) {
                for (t in n.tris) {
                    val h = intersect(t, origin, d) ?: continue
                    if (h.distance <= (best?.distance ?: maxDistance)) best = h
                }
            } else {
                n.left?.let { stack.add(it) }; n.right?.let { stack.add(it) }
            }
        }
        return best
    }
}
