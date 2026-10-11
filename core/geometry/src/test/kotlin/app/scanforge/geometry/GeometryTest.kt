package app.scanforge.geometry

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GeometryTest {
    private fun close(expected: Double, actual: Double, eps: Double) =
        assertTrue(abs(expected - actual) <= eps, "expected $expected ± $eps but was $actual")

    @Test fun unitConversionRoundTrips() {
        for (u in LengthUnit.entries) close(1.2345, u.toMeters(u.fromMeters(1.2345)), 1e-12)
        close(25.4, LengthUnit.MILLIMETER.fromMeters(LengthUnit.INCH.toMeters(1.0)), 1e-9)
        close(12.0, LengthUnit.INCH.fromMeters(LengthUnit.FOOT.toMeters(1.0)), 1e-9)
        assertEquals("123.4 mm", LengthUnit.MILLIMETER.format(0.12341))
        close(1e4, LengthUnit.CENTIMETER.areaFromSquareMeters(1.0), 1e-9)
        close(1e9, LengthUnit.MILLIMETER.volumeFromCubicMeters(1.0), 1e-3)
        assertEquals(LengthUnit.FOOT, LengthUnit.fromSymbol("FT"))
    }

    @Test fun poseInverseAndLookAt() {
        val p = Pose(Vec3(1.0, 2.0, 3.0), Quat.fromAxisAngle(Vec3(0.3, 1.0, 0.2), 0.7))
        val x = Vec3(0.4, -0.5, 2.0)
        val back = p.inverse().transformPoint(p.transformPoint(x))
        close(0.0, back.distanceTo(x), 1e-12)
        val cam = Pose.lookAt(Vec3(0.0, 0.0, 2.0), Vec3.ZERO)
        // Camera looks down its -Z axis.
        val forward = cam.rotateVector(Vec3(0.0, 0.0, -1.0))
        close(0.0, forward.distanceTo(Vec3(0.0, 0.0, -1.0)), 1e-12)
        val q = Quat.fromMatrix(Quat.fromAxisAngle(Vec3(1.0, 1.0, 0.0), 2.5).toMatrix())
        close(0.0, q.rotate(x).distanceTo(Quat.fromAxisAngle(Vec3(1.0, 1.0, 0.0), 2.5).rotate(x)), 1e-12)
    }

    @Test fun boxAreaVolumeTopology() {
        val m = TestShapes.box(0.1, 0.06, 0.04)
        close(2 * (0.1 * 0.06 + 0.1 * 0.04 + 0.06 * 0.04), MeshAnalysis.surfaceArea(m), 1e-9)
        close(0.1 * 0.06 * 0.04, MeshAnalysis.signedVolume(m), 1e-9)
        val topo = MeshAnalysis.topology(m)
        assertTrue(topo.isWatertight, topo.toString())
        assertEquals(0, topo.holeCount)
        assertEquals(1, topo.componentCount)
        val b = m.bounds()!!
        close(0.1, b.size.x, 1e-7); close(0.06, b.size.y, 1e-7); close(0.04, b.size.z, 1e-7)
    }

    @Test fun openMeshIsNotWatertightAndHolesAreCounted() {
        val m = TestShapes.box(1.0, 1.0, 1.0)
        val open = MeshOps.deleteTriangles(m, setOf(0, 1))
        val topo = MeshAnalysis.topology(open)
        assertFalse(topo.isWatertight)
        assertEquals(1, topo.holeCount)
        assertEquals(4, topo.boundaryEdges)
        val flippedOne = Mesh(m.positions, m.indices.copyOf().also { val t = it[1]; it[1] = it[2]; it[2] = t })
        assertTrue(MeshAnalysis.topology(flippedOne).inconsistentlyOrientedEdges > 0)
    }

    @Test fun sphereVolumeApproachesAnalytic() {
        val m = TestShapes.sphere(0.05, 64, 128)
        close(4.0 / 3 * PI * 0.05 * 0.05 * 0.05, MeshAnalysis.signedVolume(m), 1e-6)
        close(4 * PI * 0.05 * 0.05, MeshAnalysis.surfaceArea(m), 2e-4)
        assertTrue(MeshAnalysis.topology(m).isWatertight)
    }

    @Test fun componentsAndCleanup() {
        val big = TestShapes.box(1.0, 1.0, 1.0)
        val small = TestShapes.box(0.1, 0.1, 0.1, Vec3(5.0, 0.0, 0.0))
        val merged = Mesh(
            big.positions + small.positions,
            big.indices + small.indices.map { it + big.vertexCount },
        )
        assertEquals(2, MeshAnalysis.topology(merged).componentCount)
        assertEquals(12, MeshOps.keepLargestComponent(merged).triangleCount)
        assertEquals(24, MeshOps.removeSmallComponents(merged, 12).triangleCount)
        assertEquals(12, MeshOps.cropToBox(merged, Aabb(Vec3(4.0, -1.0, -1.0), Vec3(6.0, 1.0, 1.0))).triangleCount)
    }

    @Test fun simplificationReducesTrianglesAndKeepsShape() {
        val m = TestShapes.sphere(0.5, 64, 128)
        val s = MeshOps.simplifyToTarget(m, 2000)
        assertTrue(s.triangleCount in 200..2000, "got ${s.triangleCount}")
        val b = s.bounds()!!
        close(1.0, b.size.x, 0.1)
        assertEquals(m.triangleCount, MeshOps.simplifyToTarget(m, m.triangleCount).triangleCount)
    }

    @Test fun raycastHitsBoxFace() {
        val m = TestShapes.box(1.0, 1.0, 1.0)
        val rc = MeshRaycaster(m)
        val hit = assertNotNull(rc.cast(Vec3(0.2, 0.1, 5.0), Vec3(0.0, 0.0, -1.0)))
        close(4.5, hit.distance, 1e-9)
        close(0.5, hit.point.z, 1e-9)
        assertNull(rc.cast(Vec3(3.0, 0.0, 5.0), Vec3(0.0, 0.0, -1.0)))
    }

    @Test fun obbRecoversRotatedBoxDimensions() {
        val rot = Pose(Vec3(0.3, 0.2, 0.1), Quat.fromAxisAngle(Vec3(1.0, 2.0, 0.5), 0.9))
        val m = MeshOps.transform(TestShapes.box(0.3, 0.2, 0.1), rot)
        val dims = Measurements.orientedBoundingBox(m)!!.sortedDimensions
        close(0.3, dims[0], 1e-6); close(0.2, dims[1], 1e-6); close(0.1, dims[2], 1e-6)
    }

    @Test fun circleFitOnTiltedRim() {
        val normal = Vec3(0.2, 1.0, 0.3).normalized()
        val u = (normal cross Vec3.X).normalized(); val w = normal cross u
        val c = Vec3(1.0, 2.0, 3.0)
        val pts = (0 until 12).map { i -> val a = i * 0.4; c + u * (0.04 * cos(a)) + w * (0.04 * sin(a)) }
        val fit = assertNotNull(Measurements.fitCircle(pts))
        close(0.08, fit.diameter, 1e-9)
        close(0.0, fit.center.distanceTo(c), 1e-9)
        assertTrue(fit.rmsResidual < 1e-9)
        assertNull(Measurements.fitCircle(listOf(Vec3.ZERO, Vec3.X, Vec3.X * 2.0)))
    }

    @Test fun crossSectionOfSphereGivesCircumference() {
        val m = TestShapes.sphere(0.1, 64, 128)
        val loops = Measurements.crossSection(m, Vec3.Y, 0.0)
        assertEquals(1, loops.size)
        assertTrue(loops[0].closed)
        close(2 * PI * 0.1, loops[0].length, 2e-3)
        // Plane between vertex rings (generic position).
        val off = Measurements.crossSection(m, Vec3.Y, 0.0123)
        assertEquals(1, off.size)
        assertTrue(off[0].closed)
    }

    @Test fun confidenceNeverHighWithoutCalibration() {
        val e = MeasurementEvidence(ScaleSource.DEVICE_TRACKING, 0.9, 10, false, 0.004, false)
        assertEquals(ConfidenceLevel.MEDIUM, MeasurementConfidence.assess(0.2, e).level)
        assertEquals(ConfidenceLevel.HIGH, MeasurementConfidence.assess(0.2, e.copy(scaleSource = ScaleSource.REFERENCE_LENGTH)).level)
        assertEquals(ConfidenceLevel.LOW, MeasurementConfidence.assess(0.02, e.copy(scaleSource = ScaleSource.REFERENCE_LENGTH)).level)
        assertEquals(ConfidenceLevel.UNKNOWN, MeasurementConfidence.assess(0.2, e.copy(scaleSource = ScaleSource.UNSCALED)).level)
    }
}
