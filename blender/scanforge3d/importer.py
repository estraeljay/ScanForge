"""Import of ScanForge packages and plain mesh files into Blender (main thread only)."""

import json
import math
import os

import bpy
from mathutils import Matrix, Vector

from . import coords
from . import package as sfpkg

ROOT_COLLECTION = "ScanForge"
FORMATS = {".ply": "ply", ".obj": "obj", ".stl": "stl", ".glb": "glb", ".gltf": "glb"}


class ImportBlocked(Exception):
    """Raised instead of silently creating a duplicate or overwriting existing data."""


class ImportOptions:
    def __init__(self, new_collection=True, collection_name="", origin="BOTTOM_CENTER", import_colors=True,
                 create_material=True, match_scene_units=True, apply_calibration=True, allow_duplicate=False,
                 source_up_axis="AUTO", source_unit="AUTO"):
        self.new_collection = new_collection
        self.collection_name = collection_name
        self.origin = origin  # SCAN | BOTTOM_CENTER | CENTER
        self.import_colors = import_colors
        self.create_material = create_material
        self.match_scene_units = match_scene_units
        self.apply_calibration = apply_calibration
        self.allow_duplicate = allow_duplicate
        self.source_up_axis = source_up_axis  # AUTO | Y | Z
        self.source_unit = source_unit  # AUTO | mm | cm | m | in | ft

    @classmethod
    def from_settings(cls, s, allow_duplicate=False):
        return cls(s.new_collection, s.collection_name, s.origin, s.import_colors, s.create_material,
                   s.match_scene_units, s.apply_calibration, allow_duplicate, s.source_up_axis, s.source_unit)


def _target_collection(context, name, new):
    scene_root = context.scene.collection
    root = bpy.data.collections.get(ROOT_COLLECTION)
    if root is None:
        root = bpy.data.collections.new(ROOT_COLLECTION)
    if root.name not in scene_root.children:
        scene_root.children.link(root)
    if not new:
        return root
    # bpy.data.collections.new picks a unique name (".001") instead of overwriting.
    col = bpy.data.collections.new(name or "Scan")
    root.children.link(col)
    return col


def _move_to_collection(obj, col):
    for c in list(obj.users_collection):
        c.objects.unlink(obj)
    col.objects.link(obj)


def _vertex_color_material(mesh, name):
    attr = mesh.color_attributes.active_color or (mesh.color_attributes[0] if len(mesh.color_attributes) else None)
    if attr is None:
        return None
    mat = bpy.data.materials.new(name)
    mat.use_nodes = True
    nt = mat.node_tree
    bsdf = next((n for n in nt.nodes if n.type == "BSDF_PRINCIPLED"), None)
    if bsdf is None:
        bsdf = nt.nodes.new("ShaderNodeBsdfPrincipled")
    col = nt.nodes.new("ShaderNodeVertexColor")
    col.layer_name = attr.name
    col.location = (bsdf.location.x - 300, bsdf.location.y)
    nt.links.new(col.outputs["Color"], bsdf.inputs["Base Color"])
    if "Roughness" in bsdf.inputs:
        bsdf.inputs["Roughness"].default_value = 0.9
    mesh.materials.clear()
    mesh.materials.append(mat)
    return mat


def _detect_metadata(filepath, fmt):
    if fmt == "ply":
        return coords.parse_ply_header_metadata(filepath)
    if fmt == "obj":
        return coords.parse_obj_header_metadata(filepath)
    if fmt == "stl":
        return coords.parse_stl_header_metadata(filepath)
    return {"units": "m", "up_axis": "+Y"}


def import_mesh_file(context, filepath, options, name=None, collection=None, extra_scale=1.0):
    """Imports one mesh file and returns the created objects. Coordinates are converted explicitly:
    scan space (+Y up) -> Blender (+Z up) and file units -> scene units (docs/spec/coordinate-systems.md)."""
    ext = os.path.splitext(filepath)[1].lower()
    fmt = FORMATS.get(ext)
    if fmt is None:
        raise ValueError("Unsupported file type %s" % ext)
    meta = _detect_metadata(filepath, fmt)
    unit = meta.get("units", "m") if options.source_unit == "AUTO" else options.source_unit
    up = meta.get("up_axis", "+Y") if options.source_up_axis == "AUTO" else "+" + options.source_up_axis
    if unit not in coords.UNIT_TO_METERS:
        raise ValueError("Unknown unit %r in %s" % (unit, os.path.basename(filepath)))
    scene_scale = context.scene.unit_settings.scale_length if options.match_scene_units else 1.0
    scale = coords.import_scale(coords.UNIT_TO_METERS[unit], scene_scale) * extra_scale

    before = set(bpy.data.objects)
    if fmt == "ply":
        bpy.ops.wm.ply_import(filepath=filepath, forward_axis="Y", up_axis="Z", global_scale=1.0,
                              import_colors="SRGB" if options.import_colors else "NONE", merge_verts=False)
    elif fmt == "stl":
        bpy.ops.wm.stl_import(filepath=filepath, forward_axis="Y", up_axis="Z", global_scale=1.0)
    elif fmt == "obj":
        bpy.ops.wm.obj_import(filepath=filepath, forward_axis="Y", up_axis="Z", global_scale=1.0)
    else:
        bpy.ops.import_scene.gltf(filepath=filepath)
    created = [o for o in bpy.data.objects if o not in before]
    if not created:
        raise RuntimeError("Blender's importer created no objects for %s" % os.path.basename(filepath))

    # glTF is converted to Z-up by Blender's importer itself; the others are imported without axis changes.
    convert = fmt != "glb" and up == "+Y"
    m = Matrix.Scale(scale, 4)
    if convert:
        m = m @ Matrix(coords.SCAN_TO_BLENDER)
    for obj in created:
        if obj.type != "MESH":
            continue
        if fmt == "glb":
            obj.data.transform(obj.matrix_basis)
            obj.matrix_basis = Matrix.Identity(4)
        obj.data.transform(m)
        obj.data.update()
        for v in obj.data.vertices[:1000]:
            if not all(math.isfinite(c) for c in v.co):
                raise RuntimeError("Imported mesh contains invalid coordinates")
        if name:
            obj.name = name
            obj.data.name = name
        if collection is not None:
            _move_to_collection(obj, collection)
        if options.create_material and fmt != "glb" and options.import_colors:
            _vertex_color_material(obj.data, "SF_%s_VertexColor" % (name or obj.name))
        obj["scanforge_source_file"] = os.path.basename(filepath)
        obj["scanforge_file_unit"] = unit
        obj["scanforge_file_up_axis"] = up
        obj["scanforge_import_scale"] = scale
    _apply_origin([o for o in created if o.type == "MESH"], options.origin)
    return created


def _apply_origin(objects, mode):
    for obj in objects:
        if mode == "SCAN" or not obj.data.vertices:
            obj["scanforge_origin_offset"] = (0.0, 0.0, 0.0)
            continue
        xs = [v.co for v in obj.data.vertices]
        lo = Vector((min(c.x for c in xs), min(c.y for c in xs), min(c.z for c in xs)))
        hi = Vector((max(c.x for c in xs), max(c.y for c in xs), max(c.z for c in xs)))
        c = (lo + hi) / 2
        if mode == "BOTTOM_CENTER":
            c.z = lo.z
        obj.data.transform(Matrix.Translation(-c))
        obj.data.update()
        # Recorded so the original scan placement can be restored.
        obj["scanforge_origin_offset"] = tuple(c)


def find_existing(sha256):
    return [o for o in bpy.data.objects if o.get("scanforge_package_sha256") == sha256]


def _support_plane_blender(project, scale):
    sp = (project.get("reconstruction") or {}).get("support_plane")
    if not sp or len(sp) != 6:
        return None
    p = coords.scan_to_blender_point(sp[:3])
    n = coords.scan_to_blender_point(sp[3:])
    return [c * scale for c in p], list(n)


def import_package(context, path, options, extract_dir):
    """Verifies and imports a .sf3d package. Returns (objects, package)."""
    pkg = sfpkg.verify(path)
    sha = sfpkg.sha256_file(path)
    existing = find_existing(sha)
    if existing and not options.allow_duplicate:
        raise ImportBlocked("This scan is already in the file as '%s'. Enable 'Allow duplicate' to import it again."
                            % existing[0].name)
    primary = sfpkg.extract(pkg, os.path.join(extract_dir, "%s_%s" % (pkg.manifest.get("project_id", "scan")[:8], sha[:8])))
    project = pkg.project
    factor = float((project.get("scale") or {}).get("factor", 1.0)) if options.apply_calibration else 1.0
    collection = _target_collection(context, options.collection_name or pkg.name, options.new_collection)
    objects = import_mesh_file(context, primary, options, name=pkg.name, collection=collection, extra_scale=factor)
    summary = pkg.summary()
    text = bpy.data.texts.new("ScanForge_%s.json" % pkg.name)
    text.write(json.dumps(project, indent=2))
    for obj in objects:
        obj["scanforge_project_id"] = pkg.manifest.get("project_id", "")
        obj["scanforge_project_name"] = pkg.name
        obj["scanforge_package_sha256"] = sha
        obj["scanforge_metadata_text"] = text.name
        obj["scanforge_scale_source"] = summary.get("scale_source") or ""
        obj["scanforge_scale_factor"] = factor
        obj["scanforge_capture_source"] = summary.get("capture_source") or ""
        obj["scanforge_created_at"] = project.get("created_at", "")
        sp = _support_plane_blender(project, obj["scanforge_import_scale"])
        if sp:
            off = obj.get("scanforge_origin_offset", (0, 0, 0))
            obj["scanforge_support_plane"] = [sp[0][i] - off[i] for i in range(3)] + sp[1]
    return objects, pkg


def metadata_for(obj):
    """Project document for an imported object, if available."""
    name = obj.get("scanforge_metadata_text") if obj else None
    if not name or name not in bpy.data.texts:
        return None
    try:
        return json.loads(bpy.data.texts[name].as_string())
    except ValueError:
        return None
