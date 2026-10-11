"""Headless test runner for the ScanForge Blender extension.

Runs with any Python that can `import bpy` (the `bpy` wheel or Blender's bundled Python):
    SF_FIXTURES=build/fixtures SF_CLI=core/cli/build/install/scanforge-cli/bin/scanforge-cli python blender/tests/run_tests.py
"""

import os
import sys
import unittest

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.dirname(HERE))  # blender/ (contains the scanforge3d package)
sys.path.insert(0, HERE)

import bpy  # noqa: E402
import scanforge3d  # noqa: E402

if __name__ == "__main__":
    print("Blender", bpy.app.version_string, "| Python", sys.version.split()[0])
    scanforge3d.register()
    try:
        suite = unittest.defaultTestLoader.discover(HERE, pattern="test_*.py")
        result = unittest.TextTestRunner(verbosity=2).run(suite)
    finally:
        scanforge3d.unregister()
    sys.exit(0 if result.wasSuccessful() else 1)
