"""ScanForge 3D Blender extension: receive scans from the ScanForge 3D Android app and import them with
correct scale and orientation. See docs/blender/README.md."""

import bpy

from . import meshtools, operators, props, ui

_classes = props.classes + operators.classes + meshtools.classes + ui.classes
_POLL_INTERVAL = 0.2


def _poll_worker():
    """Runs on Blender's main thread: applies results produced by the network worker."""
    if operators.worker.drain():
        operators._redraw()
    elif operators.worker.current is not None and operators.worker.current.progress:
        operators._redraw()
    return _POLL_INTERVAL


def menu_import(self, context):
    self.layout.operator(operators.SCANFORGE_OT_import_file.bl_idname, text="ScanForge 3D (.sf3d/.ply/.obj/.stl/.glb)")


def register():
    for c in _classes:
        bpy.utils.register_class(c)
    bpy.types.Scene.scanforge = bpy.props.PointerProperty(type=props.ScanForgeSettings)
    bpy.types.TOPBAR_MT_file_import.append(menu_import)
    if not bpy.app.timers.is_registered(_poll_worker):
        bpy.app.timers.register(_poll_worker, first_interval=_POLL_INTERVAL, persistent=True)


def unregister():
    if bpy.app.timers.is_registered(_poll_worker):
        bpy.app.timers.unregister(_poll_worker)
    operators.worker.stop()
    bpy.types.TOPBAR_MT_file_import.remove(menu_import)
    del bpy.types.Scene.scanforge
    for c in reversed(_classes):
        bpy.utils.unregister_class(c)
