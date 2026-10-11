import json
import os
import zipfile

import bpy

FIXTURES = os.environ.get("SF_FIXTURES", "")
CLI = os.environ.get("SF_CLI", "")


def fixture(*parts):
    p = os.path.join(FIXTURES, *parts)
    if not os.path.exists(p):
        raise unittest_skip("fixture missing: %s (run scripts/make_fixtures.sh)" % p)
    return p


def unittest_skip(msg):
    import unittest
    return unittest.SkipTest(msg)


def project_of(sf3d):
    with zipfile.ZipFile(sf3d) as z:
        return json.loads(z.read("project.json"))


def expected_scan_size(sf3d):
    """Size of the current model version in scan space (x, y-up, z), meters, before calibration."""
    p = project_of(sf3d)
    v = p["model"]["versions"][p["model"]["current_version"] - 1]
    return [v["bounds_max"][i] - v["bounds_min"][i] for i in range(3)]


def reset_scene():
    bpy.ops.wm.read_factory_settings(use_empty=True)
    bpy.context.scene.unit_settings.scale_length = 1.0


def world_size(obj):
    pts = [obj.matrix_world @ v.co for v in obj.data.vertices]
    return [max(p[i] for p in pts) - min(p[i] for p in pts) for i in range(3)]
