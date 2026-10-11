"""Package verification (pure Python parts of the add-on)."""

import os
import shutil
import tempfile
import unittest
import zipfile

from helpers import fixture
from scanforge3d import package as sfpkg
from scanforge3d import coords


class PackageTests(unittest.TestCase):
    def test_valid_package_verifies(self):
        pkg = sfpkg.verify(fixture("box.sf3d"))
        self.assertEqual(pkg.name, "Synthetic Box")
        s = pkg.summary()
        self.assertGreater(s["triangles"], 1000)
        self.assertEqual(s["capture_source"], "synthetic")
        self.assertFalse(s["watertight"])

    def _rewrite(self, src, dst, mutate):
        with zipfile.ZipFile(src) as zin, zipfile.ZipFile(dst, "w") as zout:
            for info in zin.infolist():
                data = zin.read(info.filename)
                name, data = mutate(info.filename, data)
                if name is not None:
                    zout.writestr(name, data)

    def test_tampered_file_is_rejected(self):
        d = tempfile.mkdtemp()
        bad = os.path.join(d, "bad.sf3d")
        self._rewrite(fixture("box.sf3d"), bad,
                      lambda n, b: (n, b[:-1] + bytes([(b[-1] + 1) % 256]) if n.endswith(".ply") else b))
        with self.assertRaisesRegex(sfpkg.PackageError, "Checksum mismatch"):
            sfpkg.verify(bad)
        missing = os.path.join(d, "missing.sf3d")
        self._rewrite(fixture("box.sf3d"), missing, lambda n, b: (None, b) if n == "project.json" else (n, b))
        with self.assertRaises(sfpkg.PackageError):
            sfpkg.verify(missing)
        with open(os.path.join(d, "x.sf3d"), "wb") as f:
            f.write(b"not a zip")
        with self.assertRaises(sfpkg.PackageError):
            sfpkg.verify(os.path.join(d, "x.sf3d"))
        shutil.rmtree(d)

    def test_unsafe_paths_are_rejected(self):
        for p in ("../evil", "/abs", "a/../../b", "C:/x", "a\\b", "a//b"):
            with self.assertRaises(sfpkg.PackageError, msg=p):
                sfpkg.check_safe_path(p)
        sfpkg.check_safe_path("model/v001.ply")

    def test_extract_writes_primary_model(self):
        d = tempfile.mkdtemp()
        pkg = sfpkg.verify(fixture("box.sf3d"))
        primary = sfpkg.extract(pkg, d)
        self.assertTrue(os.path.isfile(primary))
        self.assertEqual(sfpkg.sha256_file(primary),
                         next(f["sha256"] for f in pkg.manifest["files"] if f["path"] == pkg.primary_model))
        shutil.rmtree(d)

    def test_coordinate_conversion_is_a_proper_rotation(self):
        self.assertEqual(coords.scan_to_blender_point((1.0, 2.0, 3.0)), (1.0, -3.0, 2.0))
        m = coords.SCAN_TO_BLENDER
        det = (m[0][0] * (m[1][1] * m[2][2] - m[1][2] * m[2][1]) - m[0][1] * (m[1][0] * m[2][2] - m[1][2] * m[2][0])
               + m[0][2] * (m[1][0] * m[2][1] - m[1][1] * m[2][0]))
        self.assertEqual(det, 1.0)
        self.assertAlmostEqual(coords.import_scale(0.001, 0.01), 0.1)
