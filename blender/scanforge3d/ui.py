"""Sidebar panels: View3D > Sidebar > ScanForge."""

import time

import bpy

from . import importer
from .net.client import format_code
from .operators import current_pairing, state, worker


def _human_bytes(n):
    for unit in ("B", "KB", "MB", "GB"):
        if n < 1024 or unit == "GB":
            return "%.0f %s" % (n, unit) if unit == "B" else "%.1f %s" % (n, unit)
        n /= 1024.0


class _Panel:
    bl_space_type = "VIEW_3D"
    bl_region_type = "UI"
    bl_category = "ScanForge"


class SCANFORGE_PT_main(_Panel, bpy.types.Panel):
    bl_idname = "SCANFORGE_PT_main"
    bl_label = "ScanForge 3D"

    def draw(self, context):
        layout = self.layout
        if state.error:
            box = layout.box()
            box.alert = True
            for line in _wrap(state.error):
                box.label(text=line, icon="ERROR" if line is _wrap(state.error)[0] else "BLANK1")
        if state.message:
            layout.label(text=state.message, icon="INFO")
        job = worker.current
        if job is not None:
            row = layout.row(align=True)
            if job.progress:
                done, total = job.progress
                row.progress(factor=done / max(total, 1), text="%s / %s" % (_human_bytes(done), _human_bytes(total)))
            else:
                row.label(text="Working: %s…" % job.kind)
            row.operator("scanforge.cancel", text="", icon="CANCEL")
        layout.operator("scanforge.import_file", icon="IMPORT")


def _wrap(text, width=42):
    words, lines, cur = text.split(), [], ""
    for w in words:
        if len(cur) + len(w) + 1 > width:
            lines.append(cur)
            cur = w
        else:
            cur = (cur + " " + w).strip()
    if cur:
        lines.append(cur)
    return lines or [""]


class SCANFORGE_PT_connection(_Panel, bpy.types.Panel):
    bl_label = "Connection"
    bl_parent_id = "SCANFORGE_PT_main"

    def draw(self, context):
        s = context.scene.scanforge
        layout = self.layout
        p = current_pairing()
        col = layout.column(align=True)
        col.label(text=state.status, icon="LINKED" if state.connected_device else "UNLINKED")
        if p:
            col.label(text="Paired: %s" % p.get("device_name", "phone"))
            col.label(text="Last address: %s:%s" % (p["host"], p["port"]))
            if state.last_contact:
                col.label(text="Last contact: %s" % time.strftime("%H:%M:%S", time.localtime(state.last_contact)))
        layout.prop(s, "host")
        layout.prop(s, "port")
        if not p:
            layout.prop(s, "pairing_code")
            layout.operator("scanforge.pair", icon="KEY_HLT")
        else:
            row = layout.row(align=True)
            row.operator("scanforge.refresh", text="Reconnect / Check", icon="FILE_REFRESH")
            row.operator("scanforge.forget", text="", icon="X")
            with_code = layout.column()
            with_code.prop(s, "pairing_code", text="New Code")
            with_code.operator("scanforge.pair", text="Pair Another Phone", icon="KEY_HLT")
        row = layout.row()
        row.operator("scanforge.discover", icon="VIEWZOOM")
        for i, d in enumerate(state.discovered):
            r = layout.row()
            r.label(text="%s (%s)" % (d.get("device_name", "?"), d["host"]))
            r.operator("scanforge.use_discovered", text="Use").index = i
        box = layout.box()
        box.label(text="Firewall: Blender only makes outgoing", icon="QUESTION")
        box.label(text="connections; allow them if prompted.")


class SCANFORGE_PT_incoming(_Panel, bpy.types.Panel):
    bl_label = "Incoming Scans"
    bl_parent_id = "SCANFORGE_PT_main"

    def draw(self, context):
        layout = self.layout
        s = context.scene.scanforge
        layout.prop(s, "auto_import")
        if not state.offers:
            layout.label(text="No scans offered. Tap 'Send' on the phone,")
            layout.label(text="then 'Check for Scans'.")
        for o in state.offers:
            box = layout.box()
            box.label(text=o["project_name"], icon="MESH_DATA")
            box.label(text="%s · %s · %s" % (o["format"].upper(), _human_bytes(o["bytes"]), o["status"]))
            row = box.row(align=True)
            if o["id"] in state.received:
                row.label(text="Received ✓", icon="CHECKMARK")
                row.operator("scanforge.import_received", text="Import").offer_id = o["id"]
            else:
                op = row.operator("scanforge.receive", text="Receive & Import" if s.auto_import else "Receive", icon="IMPORT")
                op.offer_id = o["id"]
                op.import_after = s.auto_import


class SCANFORGE_PT_import(_Panel, bpy.types.Panel):
    bl_label = "Import Settings"
    bl_parent_id = "SCANFORGE_PT_main"
    bl_options = {"DEFAULT_CLOSED"}

    def draw(self, context):
        s = context.scene.scanforge
        layout = self.layout
        for prop in ("new_collection", "collection_name", "origin", "import_colors", "create_material",
                     "match_scene_units", "apply_calibration", "source_up_axis", "source_unit", "download_dir"):
            layout.prop(s, prop)


class SCANFORGE_PT_info(_Panel, bpy.types.Panel):
    bl_label = "Scan Information"
    bl_parent_id = "SCANFORGE_PT_main"

    def draw(self, context):
        layout = self.layout
        obj = context.active_object
        if obj is None or "scanforge_project_id" not in obj and "scanforge_source_file" not in obj:
            layout.label(text="Select an imported scan")
            return
        meta = importer.metadata_for(obj) or {}
        col = layout.column(align=True)
        col.label(text=obj.get("scanforge_project_name", obj.name), icon="OBJECT_DATA")
        if meta.get("created_at"):
            col.label(text="Scanned: %s" % meta["created_at"][:19].replace("T", " "))
        dims = obj.dimensions * context.scene.unit_settings.scale_length
        col.label(text="Size: %.1f × %.1f × %.1f mm" % (dims.x * 1000, dims.y * 1000, dims.z * 1000))
        src = obj.get("scanforge_scale_source", "")
        col.label(text="Scale: %s × %.4f" % (src or "unknown", obj.get("scanforge_scale_factor", 1.0)))
        if src == "device_tracking":
            col.label(text="Not calibrated against a reference", icon="INFO")
        if obj.get("scanforge_capture_source") == "synthetic":
            col.label(text="Synthetic test data", icon="ERROR")
        cap = meta.get("capture") or {}
        if cap.get("device"):
            col.label(text="Device: %s" % cap["device"].get("model", "?"))
        model = meta.get("model") or {}
        versions = model.get("versions", [])
        cur = model.get("current_version", 0)
        if 0 < cur <= len(versions):
            v = versions[cur - 1]
            col.label(text="Mesh: %d tris, %s, %d hole(s)" % (v["triangle_count"], "closed" if v["watertight"] else "open", v["hole_count"]))
        for w in (meta.get("reconstruction") or {}).get("warnings", [])[:4]:
            for i, line in enumerate(_wrap(w)):
                layout.label(text=line, icon="ERROR" if i == 0 else "BLANK1")
        ms = meta.get("measurements", [])
        if ms:
            layout.label(text="Phone measurements:")
            factor = obj.get("scanforge_scale_factor", 1.0)
            for m in ms[:8]:
                vals = [v * factor ** m.get("dimension", 1) for v in m.get("raw_values", [])]
                unit = {1: "mm", 2: "cm²", 3: "cm³"}[m.get("dimension", 1)]
                mult = {1: 1000, 2: 1e4, 3: 1e6}[m.get("dimension", 1)]
                layout.label(text="%s: %s %s (%s)" % (m["label"][:24], " × ".join("%.1f" % (v * mult) for v in vals), unit, m["confidence"]))


class SCANFORGE_PT_tools(_Panel, bpy.types.Panel):
    bl_label = "Mesh Tools"
    bl_parent_id = "SCANFORGE_PT_main"
    bl_options = {"DEFAULT_CLOSED"}

    def draw(self, context):
        col = self.layout.column(align=True)
        col.label(text="Explicit, undoable operations:")
        col.operator("scanforge.recalc_normals", icon="NORMALS_FACE")
        col.operator("scanforge.remove_loose", icon="TRASH")
        col.operator("scanforge.merge_by_distance", icon="AUTOMERGE_ON")
        col.operator("scanforge.decimate", icon="MOD_DECIM")
        col.operator("scanforge.orient_ground", icon="ORIENTATION_GLOBAL")
        col.operator_menu_enum("scanforge.set_origin", "mode", icon="OBJECT_ORIGIN")
        col.operator("scanforge.bounding_box", icon="CUBE")
        col.operator("scanforge.dimension_report", icon="TEXT")


classes = (SCANFORGE_PT_main, SCANFORGE_PT_connection, SCANFORGE_PT_incoming, SCANFORGE_PT_import, SCANFORGE_PT_info, SCANFORGE_PT_tools)
