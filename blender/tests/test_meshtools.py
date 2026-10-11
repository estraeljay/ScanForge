"""Mesh tools are explicit and behave as documented."""

import tempfile
import unittest

import bpy

from helpers import fixture, reset_scene, world_size
from scanforge3d import importer


class MeshToolTests(unittest.TestCase):
    def setUp(self):
        reset_scene()
        self.tmp = tempfile.mkdtemp()
        opts = importer.ImportOptions.from_settings(bpy.context.scene.scanforge)
        self.obj = importer.import_package(bpy.context, fixture("box.sf3d"), opts, self.tmp)[0][0]
        bpy.context.view_layer.objects.active = self.obj
        self.obj.select_set(True)

    def test_import_does_not_modify_mesh(self):
        p = importer.metadata_for(self.obj)
        v = p["model"]["versions"][p["model"]["current_version"] - 1]
        self.assertEqual(len(self.obj.data.vertices), v["vertex_count"])
        self.assertEqual(len(self.obj.data.polygons), v["triangle_count"])
        self.assertEqual(len(self.obj.modifiers), 0)

    def test_merge_by_distance_welds_split_vertices(self):
        import bmesh
        me = self.obj.data
        n0 = len(me.vertices)
        bm = bmesh.new()
        bm.from_mesh(me)
        bmesh.ops.split_edges(bm, edges=bm.edges)  # every face gets its own vertices
        bm.to_mesh(me)
        bm.free()
        self.assertGreater(len(me.vertices), 2 * n0)
        bpy.ops.scanforge.merge_by_distance(tolerance=1e-6)
        self.assertEqual(len(me.vertices), n0)

    def test_decimate_adds_modifier_and_apply(self):
        bpy.ops.scanforge.decimate(ratio=0.5)
        self.assertEqual(self.obj.modifiers[0].type, "DECIMATE")
        n0 = len(self.obj.data.polygons)
        self.obj.modifiers.clear()
        bpy.ops.scanforge.decimate(ratio=0.5, apply=True)
        self.assertLess(len(self.obj.data.polygons), n0 * 0.6)

    def test_remove_loose_and_normals(self):
        self.assertEqual(bpy.ops.scanforge.remove_loose(min_faces=10), {"FINISHED"})
        self.assertEqual(bpy.ops.scanforge.recalc_normals(), {"FINISHED"})

    def test_set_origin_round_trip_keeps_world_geometry(self):
        before = [tuple(self.obj.matrix_world @ v.co) for v in self.obj.data.vertices[:20]]
        bpy.ops.scanforge.set_origin(mode="CENTER")
        bpy.ops.scanforge.set_origin(mode="SCAN")
        after = [tuple(self.obj.matrix_world @ v.co) for v in self.obj.data.vertices[:20]]
        for a, b in zip(before, after):
            for i in range(3):
                self.assertAlmostEqual(a[i], b[i], delta=1e-6)

    def test_orient_ground_uses_recorded_support_plane(self):
        self.assertIn("scanforge_support_plane", self.obj)
        bpy.ops.scanforge.orient_ground()
        # The synthetic support plane is y=0 in scan space; the box base sits a voxel above the cut.
        self.assertGreaterEqual(min(v.co.z for v in self.obj.data.vertices), 0.0)
        self.assertLess(min(v.co.z for v in self.obj.data.vertices), 0.01)

    def test_bounding_box_and_report(self):
        bpy.ops.scanforge.bounding_box()
        box = bpy.data.objects[self.obj.name + "_BBox"]
        for a, b in zip(world_size(box), world_size(self.obj)):
            self.assertAlmostEqual(a, b, delta=1e-6)
        csv_path = self.tmp + "/report.csv"
        bpy.ops.scanforge.dimension_report(csv_path=csv_path)
        text = bpy.data.texts["ScanForge_Report_%s" % self.obj.name].as_string()
        self.assertIn("not available (mesh is not closed)", text)
        self.assertIn("Phone measurement", text)
        with open(csv_path) as f:
            self.assertIn("height_z", f.read())
