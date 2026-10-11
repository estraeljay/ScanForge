"""Coordinate-system conversion (pure Python, no bpy).

ScanForge scan space (ARCore world, glTF): right-handed, +Y up, -Z forward, meters.
Blender: right-handed, +Z up, -Y forward, 1 BU = scene.unit_settings.scale_length meters.

The conversion is a +90 degree rotation about X:  (x, y, z)_scan -> (x, -z, y)_blender.
It is a proper rotation (determinant +1), so triangle winding and normals are preserved.
This is the same convention Blender's glTF importer uses.
"""

SCAN_TO_BLENDER = (
    (1.0, 0.0, 0.0, 0.0),
    (0.0, 0.0, -1.0, 0.0),
    (0.0, 1.0, 0.0, 0.0),
    (0.0, 0.0, 0.0, 1.0),
)

UNIT_TO_METERS = {"mm": 0.001, "cm": 0.01, "m": 1.0, "in": 0.0254, "ft": 0.3048}


def scan_to_blender_point(p):
    x, y, z = p
    return (x, -z, y)


def import_scale(file_unit_m, scene_scale_length):
    """Factor applied to file coordinates so that one Blender unit equals scene_scale_length meters."""
    if scene_scale_length <= 0:
        raise ValueError("scene unit scale must be positive")
    return file_unit_m / scene_scale_length


def parse_ply_header_metadata(path):
    """Reads ScanForge metadata comments ('units mm', 'up_axis +Z') from an ASCII PLY header."""
    meta = {}
    with open(path, "rb") as f:
        head = f.read(4096)
    end = head.find(b"end_header")
    if end < 0:
        return meta
    for line in head[:end].decode("ascii", "replace").splitlines():
        if line.startswith("comment "):
            parts = line[8:].split()
            if len(parts) == 2 and parts[0] in ("units", "up_axis"):
                meta[parts[0]] = parts[1]
    return meta


def parse_obj_header_metadata(path):
    meta = {}
    with open(path, "r", encoding="utf-8", errors="replace") as f:
        for _ in range(50):
            line = f.readline()
            if not line:
                break
            if line.startswith("# "):
                parts = line[2:].split()
                if len(parts) == 2 and parts[0] in ("units", "up_axis"):
                    meta[parts[0]] = parts[1]
    return meta


def parse_stl_header_metadata(path):
    """ScanForge binary STL headers read 'ScanForge 3D <unit> <up_axis>', e.g. 'ScanForge 3D mm +Z'."""
    with open(path, "rb") as f:
        header = f.read(80).rstrip(b"\0").decode("ascii", "replace").split()
    meta = {}
    if len(header) >= 3 and header[0] == "ScanForge" and header[1] == "3D":
        if header[2] in UNIT_TO_METERS:
            meta["units"] = header[2]
        if len(header) >= 4 and header[3] in ("+Y", "+Z"):
            meta["up_axis"] = header[3]
    return meta
