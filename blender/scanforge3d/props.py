"""Scene-level settings (saved with the .blend file)."""

import bpy

from .net.client import DEFAULT_PORT


class ScanForgeSettings(bpy.types.PropertyGroup):
    host: bpy.props.StringProperty(name="Address", description="IP address shown in the phone's Transfer screen")
    port: bpy.props.IntProperty(name="Port", default=DEFAULT_PORT, min=1, max=65535)
    pairing_code: bpy.props.StringProperty(name="Pairing Code", description="16-character code shown on the phone",
                                           options={"SKIP_SAVE"})
    download_dir: bpy.props.StringProperty(name="Download Folder", subtype="DIR_PATH",
                                           description="Where received packages are stored (default: add-on data folder)")
    auto_import: bpy.props.BoolProperty(name="Import After Receiving", default=True)
    # Import settings
    new_collection: bpy.props.BoolProperty(name="New Collection", default=True,
                                           description="Create a collection per scan inside 'ScanForge'")
    collection_name: bpy.props.StringProperty(name="Collection Name", description="Empty: use the project name")
    origin: bpy.props.EnumProperty(name="Origin", default="BOTTOM_CENTER", items=[
        ("BOTTOM_CENTER", "Bottom Center", "Place the object origin at the center of its base"),
        ("CENTER", "Center", "Place the object origin at its bounding-box center"),
        ("SCAN", "Scan Origin", "Keep the coordinates of the original scan"),
    ])
    import_colors: bpy.props.BoolProperty(name="Import Colors", default=True)
    create_material: bpy.props.BoolProperty(name="Create Material", default=True,
                                            description="Create a material that displays the scan's vertex colors")
    match_scene_units: bpy.props.BoolProperty(name="Respect Scene Unit Scale", default=True,
                                              description="Keep real-world size when the scene's Unit Scale is not 1")
    apply_calibration: bpy.props.BoolProperty(name="Apply Calibration", default=True,
                                              description="Apply the scale calibration recorded on the phone")
    source_up_axis: bpy.props.EnumProperty(name="File Up Axis", default="AUTO", items=[
        ("AUTO", "Auto", "Use the metadata in ScanForge files (default +Y)"),
        ("Y", "+Y", "File is Y-up (glTF / ScanForge default)"),
        ("Z", "+Z", "File is Z-up"),
    ])
    source_unit: bpy.props.EnumProperty(name="File Unit", default="AUTO", items=[
        ("AUTO", "Auto", "Use the metadata in ScanForge files (default meters)"),
        ("mm", "Millimeters", ""), ("cm", "Centimeters", ""), ("m", "Meters", ""), ("in", "Inches", ""), ("ft", "Feet", ""),
    ])


classes = (ScanForgeSettings,)
