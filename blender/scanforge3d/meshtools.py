"""Explicit, undoable mesh tools. None of these run automatically on import."""

import csv
import io
import math

import bmesh
import bpy
from mathutils import Matrix, Vector

from . import importer


def _active_mesh(context):
    obj = context.active_object
    return obj if obj is not None and obj.type == "MESH" else None


class _MeshOp:
    bl_options = {"REGISTER", "UNDO"}

    @classmethod
    def poll(cls, context):
        return _active_mesh(context) is not None and context.mode == "OBJECT"


class SCANFORGE_OT_recalc_normals(_MeshOp, bpy.types.Operator):
    bl_idname = "scanforge.recalc_normals"
    bl_label = "Recalculate Normals"
    bl_description = "Make face normals consistent and point outward"

    def execute(self, context):
        obj = _active_mesh(context)
        bm = bmesh.new()
        bm.from_mesh(obj.data)
        bmesh.ops.recalc_face_normals(bm, faces=bm.faces)
        bm.to_mesh(obj.data)
        bm.free()
        obj.data.update()
        return {"FINISHED"}


class SCANFORGE_OT_remove_loose(_MeshOp, bpy.types.Operator):
    bl_idname = "scanforge.remove_loose"
    bl_label = "Remove Loose Geometry"
    bl_description = "Delete loose vertices/edges and disconnected pieces smaller than the threshold"

    min_faces: bpy.props.IntProperty(name="Minimum Faces per Piece", default=50, min=0,
                                     description="Disconnected pieces with fewer faces are deleted (0 keeps all pieces)")

    def execute(self, context):
        obj = _active_mesh(context)
        bm = bmesh.new()
        bm.from_mesh(obj.data)
        removed_pieces = 0
        bm.faces.index_update()
        if self.min_faces > 0:
            seen = set()
            doomed = []
            for f in bm.faces:
                if f.index in seen:
                    continue
                island, stack = [], [f]
                seen.add(f.index)
                while stack:
                    cur = stack.pop()
                    island.append(cur)
                    for e in cur.edges:
                        for nf in e.link_faces:
                            if nf.index not in seen:
                                seen.add(nf.index)
                                stack.append(nf)
                if len(island) < self.min_faces:
                    doomed.extend(island)
                    removed_pieces += 1
            bm.faces.ensure_lookup_table()
            if doomed:
                bmesh.ops.delete(bm, geom=doomed, context="FACES")
        loose_v = [v for v in bm.verts if not v.link_faces]
        loose_e = [e for e in bm.edges if not e.link_faces]
        bmesh.ops.delete(bm, geom=loose_e, context="EDGES")
        bmesh.ops.delete(bm, geom=[v for v in loose_v if v.is_valid], context="VERTS")
        bm.to_mesh(obj.data)
        bm.free()
        obj.data.update()
        self.report({"INFO"}, "Removed %d small piece(s) and loose geometry" % removed_pieces)
        return {"FINISHED"}


class SCANFORGE_OT_merge_by_distance(_MeshOp, bpy.types.Operator):
    bl_idname = "scanforge.merge_by_distance"
    bl_label = "Merge Vertices by Distance"
    bl_description = "Merge vertices closer than the tolerance"

    tolerance: bpy.props.FloatProperty(name="Tolerance", default=0.0001, min=0.0, soft_max=0.01,
                                       subtype="DISTANCE", unit="LENGTH", precision=5)

    def execute(self, context):
        obj = _active_mesh(context)
        bm = bmesh.new()
        bm.from_mesh(obj.data)
        n0 = len(bm.verts)
        bmesh.ops.remove_doubles(bm, verts=bm.verts, dist=self.tolerance)
        n1 = len(bm.verts)
        bm.to_mesh(obj.data)
        bm.free()
        obj.data.update()
        self.report({"INFO"}, "Merged %d vertices" % (n0 - n1))
        return {"FINISHED"}


class SCANFORGE_OT_decimate(_MeshOp, bpy.types.Operator):
    bl_idname = "scanforge.decimate"
    bl_label = "Decimate"
    bl_description = "Reduce triangle count with a Decimate modifier (non-destructive until applied)"

    ratio: bpy.props.FloatProperty(name="Ratio", default=0.5, min=0.01, max=1.0)
    apply: bpy.props.BoolProperty(name="Apply Immediately", default=False)

    def execute(self, context):
        obj = _active_mesh(context)
        mod = obj.modifiers.new("ScanForge Decimate", "DECIMATE")
        mod.ratio = self.ratio
        if self.apply:
            with context.temp_override(object=obj, active_object=obj):
                bpy.ops.object.modifier_apply(modifier=mod.name)
        return {"FINISHED"}


class SCANFORGE_OT_orient_ground(_MeshOp, bpy.types.Operator):
    bl_idname = "scanforge.orient_ground"
    bl_label = "Orient to Ground Plane"
    bl_description = ("Rotate and move the mesh so the support surface recorded during scanning lies on Z=0. "
                      "Without a recorded plane, the lowest point is moved to Z=0")

    def execute(self, context):
        obj = _active_mesh(context)
        sp = obj.get("scanforge_support_plane")
        me = obj.data
        if sp is not None and len(sp) == 6:
            p = Vector(sp[:3])
            n = Vector(sp[3:]).normalized()
            rot = n.rotation_difference(Vector((0, 0, 1))).to_matrix().to_4x4()
            me.transform(rot @ Matrix.Translation(-p))
            obj["scanforge_support_plane"] = [0.0, 0.0, 0.0, 0.0, 0.0, 1.0]
            msg = "Aligned to recorded support plane"
        else:
            if not me.vertices:
                return {"CANCELLED"}
            zmin = min(v.co.z for v in me.vertices)
            me.transform(Matrix.Translation((0, 0, -zmin)))
            msg = "No recorded plane: moved lowest point to Z=0"
        me.update()
        self.report({"INFO"}, msg)
        return {"FINISHED"}


class SCANFORGE_OT_set_origin(_MeshOp, bpy.types.Operator):
    bl_idname = "scanforge.set_origin"
    bl_label = "Set Origin"
    bl_description = "Move the object origin without moving the mesh in the world"

    mode: bpy.props.EnumProperty(name="Origin", items=[
        ("BOTTOM_CENTER", "Bottom Center", "Center of the bounding box bottom"),
        ("CENTER", "Bounds Center", "Center of the bounding box"),
        ("SCAN", "Scan Origin", "Original scan origin recorded at import"),
    ])

    def execute(self, context):
        obj = _active_mesh(context)
        me = obj.data
        if not me.vertices:
            return {"CANCELLED"}
        if self.mode == "SCAN":
            off = Vector(obj.get("scanforge_origin_offset", (0, 0, 0)))
            target = -off
            obj["scanforge_origin_offset"] = (0.0, 0.0, 0.0)
        else:
            cs = [v.co for v in me.vertices]
            lo = Vector((min(c.x for c in cs), min(c.y for c in cs), min(c.z for c in cs)))
            hi = Vector((max(c.x for c in cs), max(c.y for c in cs), max(c.z for c in cs)))
            target = (lo + hi) / 2
            if self.mode == "BOTTOM_CENTER":
                target.z = lo.z
            obj["scanforge_origin_offset"] = tuple(Vector(obj.get("scanforge_origin_offset", (0, 0, 0))) + target)
        me.transform(Matrix.Translation(-target))
        me.update()
        obj.matrix_world = obj.matrix_world @ Matrix.Translation(target)
        return {"FINISHED"}


def world_bounds(obj):
    pts = [obj.matrix_world @ v.co for v in obj.data.vertices]
    lo = Vector((min(p.x for p in pts), min(p.y for p in pts), min(p.z for p in pts)))
    hi = Vector((max(p.x for p in pts), max(p.y for p in pts), max(p.z for p in pts)))
    return lo, hi


class SCANFORGE_OT_bounding_box(_MeshOp, bpy.types.Operator):
    bl_idname = "scanforge.bounding_box"
    bl_label = "Create Bounding Box"
    bl_description = "Add a wireframe box matching the object's world-space bounds"

    def execute(self, context):
        obj = _active_mesh(context)
        if not obj.data.vertices:
            return {"CANCELLED"}
        lo, hi = world_bounds(obj)
        me = bpy.data.meshes.new(obj.name + "_BBox")
        corners = [(x, y, z) for z in (lo.z, hi.z) for y in (lo.y, hi.y) for x in (lo.x, hi.x)]
        edges = [(0, 1), (2, 3), (4, 5), (6, 7), (0, 2), (1, 3), (4, 6), (5, 7), (0, 4), (1, 5), (2, 6), (3, 7)]
        me.from_pydata(corners, edges, [])
        box = bpy.data.objects.new(obj.name + "_BBox", me)
        for c in obj.users_collection:
            c.objects.link(box)
        box.display_type = "WIRE"
        box["scanforge_dimensions_m"] = tuple((hi - lo) * context.scene.unit_settings.scale_length)
        return {"FINISHED"}


def dimension_report(context, obj):
    """Returns (rows, text). Lengths in meters, using the scene unit scale."""
    s = context.scene.unit_settings.scale_length
    lo, hi = world_bounds(obj)
    size = (hi - lo) * s
    bm = bmesh.new()
    bm.from_mesh(obj.data)
    bm.transform(obj.matrix_world)
    area = sum(f.calc_area() for f in bm.faces) * s * s
    manifold = all(e.is_manifold for e in bm.edges) and len(bm.edges) > 0
    volume = abs(bm.calc_volume(signed=True)) * s ** 3 if manifold else None
    bm.free()
    rows = [
        ("width_x", size.x, "m"), ("depth_y", size.y, "m"), ("height_z", size.z, "m"),
        ("surface_area", area, "m^2"),
        ("volume", volume if volume is not None else float("nan"), "m^3"),
    ]
    meta = importer.metadata_for(obj) or {}
    lines = ["ScanForge dimension report: %s" % obj.name,
             "Scale source: %s (factor %.6f)" % (obj.get("scanforge_scale_source", "unknown"), obj.get("scanforge_scale_factor", 1.0)),
             "Bounding box (world, axis-aligned): %.1f x %.1f x %.1f mm" % (size.x * 1000, size.y * 1000, size.z * 1000),
             "Surface area: %.1f cm^2" % (area * 1e4),
             "Volume: %s" % ("%.2f cm^3" % (volume * 1e6) if volume is not None else "not available (mesh is not closed)")]
    for m in meta.get("measurements", []):
        lines.append("Phone measurement: %s = %s (%s confidence)" % (m.get("label"), ", ".join("%.4f" % v for v in m.get("raw_values", [])), m.get("confidence")))
    lines.append("Note: values come from a 3D scan; verify critical dimensions physically.")
    return rows, "\n".join(lines)


class SCANFORGE_OT_dimension_report(_MeshOp, bpy.types.Operator):
    bl_idname = "scanforge.dimension_report"
    bl_label = "Dimension Report"
    bl_description = "Write a dimension report to a text block (and optionally a CSV file)"
    bl_options = {"REGISTER"}

    csv_path: bpy.props.StringProperty(name="CSV File", subtype="FILE_PATH", default="")

    def execute(self, context):
        obj = _active_mesh(context)
        rows, text = dimension_report(context, obj)
        t = bpy.data.texts.get("ScanForge_Report_%s" % obj.name) or bpy.data.texts.new("ScanForge_Report_%s" % obj.name)
        t.clear()
        t.write(text)
        if self.csv_path:
            buf = io.StringIO()
            w = csv.writer(buf)
            w.writerow(["object", "quantity", "value", "unit"])
            for q, v, u in rows:
                w.writerow([obj.name, q, "" if math.isnan(v) else "%.6f" % v, u])
            with open(bpy.path.abspath(self.csv_path), "w", newline="", encoding="utf-8") as f:
                f.write(buf.getvalue())
        self.report({"INFO"}, "Report written to text block '%s'" % t.name)
        return {"FINISHED"}


classes = (
    SCANFORGE_OT_recalc_normals, SCANFORGE_OT_remove_loose, SCANFORGE_OT_merge_by_distance, SCANFORGE_OT_decimate,
    SCANFORGE_OT_orient_ground, SCANFORGE_OT_set_origin, SCANFORGE_OT_bounding_box, SCANFORGE_OT_dimension_report,
)
