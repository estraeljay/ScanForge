"""Import into Blender: scale, orientation, colors, collections, metadata, duplicates, every format."""

import os
import tempfile
import unittest

import bpy

from helpers import expected_scan_size, fixture, reset_scene, world_size
from scanforge3d import importer
from scanforge3d import operators as ops

TOL = 1e-5


class ImportTests(unittest.TestCase):
    def setUp(self):
        reset_scene()
        self.settings = bpy.context.scene.scanforge
        self.tmp = tempfile.mkdtemp()

    def assertSize(self, actual, expected, tol=TOL):
        for a, e in zip(actual, expected):
            self.assertAlmostEqual(a, e, delta=tol, msg="size %s != %s" % (actual, expected))

    def blender_expected(self, scan_size, factor=1.0):
        # scan (x, y-up, z) -> blender (x, -z, y): sizes map x->x, z->y, y->z
        return [scan_size[0] * factor, scan_size[2] * factor, scan_size[1] * factor]

    def import_pkg(self, name="box.sf3d", **kw):
        opts = importer.ImportOptions.from_settings(self.settings, kw.pop("allow_duplicate", False))
        for k, v in kw.items():
            setattr(opts, k, v)
        return importer.import_package(bpy.context, fixture(name), opts, self.tmp)

    def test_package_import_preserves_size_and_orientation(self):
        objs, pkg = self.import_pkg()
        obj = objs[0]
        self.assertEqual(obj.name, "Synthetic Box")
        self.assertSize(world_size(obj), self.blender_expected(expected_scan_size(fixture("box.sf3d"))))
        # Height (scan +Y) is Blender +Z, and the default origin is the bottom center.
        zs = [v.co.z for v in obj.data.vertices]
        self.assertAlmostEqual(min(zs), 0.0, delta=TOL)
        self.assertGreater(max(zs), 0.07)
        self.assertEqual(obj.users_collection[0].name, "Synthetic Box")
        self.assertEqual(obj.users_collection[0].name, bpy.data.collections["ScanForge"].children[0].name)
        self.assertEqual(obj["scanforge_scale_source"], "external")
        self.assertEqual(obj["scanforge_capture_source"], "synthetic")
        self.assertIn(obj["scanforge_metadata_text"], bpy.data.texts)
        self.assertIsNotNone(importer.metadata_for(obj))

    def test_vertex_colors_and_material(self):
        obj = self.import_pkg()[0][0]
        self.assertGreaterEqual(len(obj.data.color_attributes), 1)
        mat = obj.data.materials[0]
        self.assertTrue(any(n.type == "VERTEX_COLOR" for n in mat.node_tree.nodes))

    def test_scan_origin_is_preserved_on_request(self):
        obj = self.import_pkg(origin="SCAN")[0][0]
        p = importer.metadata_for(obj)
        v = p["model"]["versions"][0]
        zs = [v2.co.z for v2 in obj.data.vertices]
        self.assertAlmostEqual(min(zs), v["bounds_min"][1], delta=TOL)  # scan min Y -> Blender min Z

    def test_duplicates_are_blocked_unless_allowed(self):
        self.import_pkg()
        with self.assertRaises(importer.ImportBlocked):
            self.import_pkg()
        objs, _ = self.import_pkg(allow_duplicate=True)
        self.assertEqual(objs[0].name, "Synthetic Box.001")  # never overwrites
        self.assertEqual(len([o for o in bpy.data.objects if o.type == "MESH"]), 2)

    def test_scene_unit_scale_keeps_real_world_size(self):
        bpy.context.scene.unit_settings.scale_length = 0.001  # 1 BU = 1 mm
        obj = self.import_pkg()[0][0]
        exp = [s * 1000 for s in self.blender_expected(expected_scan_size(fixture("box.sf3d")))]
        self.assertSize(world_size(obj), exp, tol=1e-2)

    def test_calibration_factor_is_applied(self):
        obj = self.import_pkg("box_calibrated.sf3d")[0][0]
        self.assertAlmostEqual(obj["scanforge_scale_factor"], 1.1, places=9)
        self.assertSize(world_size(obj), self.blender_expected(expected_scan_size(fixture("box_calibrated.sf3d")), 1.1))
        reset_scene()
        self.settings = bpy.context.scene.scanforge
        raw = self.import_pkg("box_calibrated.sf3d", apply_calibration=False)[0][0]
        self.assertSize(world_size(raw), self.blender_expected(expected_scan_size(fixture("box_calibrated.sf3d"))))

    def test_every_export_format_imports_with_same_dimensions(self):
        expected = self.blender_expected(expected_scan_size(fixture("box.sf3d")))
        cases = [("export_yup_m", "Synthetic_Box.ply"), ("export_yup_m", "Synthetic_Box.obj"),
                 ("export_yup_m", "Synthetic_Box.stl"), ("export_yup_m", "Synthetic_Box.glb"),
                 ("export_zup_mm", "Synthetic_Box.ply"), ("export_zup_mm", "Synthetic_Box.stl"),
                 ("export_zup_cm", "Synthetic_Box.obj")]
        for d, f in cases:
            with self.subTest(file=d + "/" + f):
                reset_scene()
                opts = importer.ImportOptions.from_settings(bpy.context.scene.scanforge)
                objs = importer.import_mesh_file(bpy.context, fixture(d, f), opts, name=f)
                obj = [o for o in objs if o.type == "MESH"][0]
                # OBJ text output uses 7 significant digits.
                self.assertSize(world_size(obj), expected, tol=2e-5)
                zs = [v.co.z for v in obj.data.vertices]
                self.assertAlmostEqual(min(zs), 0.0, delta=2e-5, msg="upright, base on Z=0")

    def test_operator_import_file_and_save_reload(self):
        res = bpy.ops.scanforge.import_file(filepath=fixture("box.sf3d"))
        self.assertEqual(res, {"FINISHED"})
        path = os.path.join(self.tmp, "scene.blend")
        bpy.ops.wm.save_as_mainfile(filepath=path)
        bpy.ops.wm.open_mainfile(filepath=path)
        obj = bpy.data.objects["Synthetic Box"]
        self.assertEqual(obj["scanforge_project_name"], "Synthetic Box")
        self.assertIn(obj["scanforge_metadata_text"], bpy.data.texts)
        with self.assertRaisesRegex(RuntimeError, "already in the file"):  # duplicate: operator reports an error
            bpy.ops.scanforge.import_file(filepath=fixture("box.sf3d"))
