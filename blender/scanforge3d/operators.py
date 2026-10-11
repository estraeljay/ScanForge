"""Connection, transfer and import operators."""

import os
import socket
import time

import bpy

from . import importer
from . import package as sfpkg
from .net import client as sfclient
from .net import discovery as sfdiscovery
from .net.store import PairingStore
from .worker import Job, State, Worker

worker = Worker()
state = State()
_store = None


def data_dir():
    """Per-user writable directory for pairings and received files."""
    try:
        return bpy.utils.extension_path_user(__package__, path="", create=True)
    except (ValueError, AttributeError, TypeError):
        path = bpy.utils.user_resource("CONFIG", path="scanforge3d", create=True)
        return path


def store():
    global _store
    if _store is None:
        _store = PairingStore(data_dir())
    return _store


def downloads_dir(context):
    s = context.scene.scanforge
    d = bpy.path.abspath(s.download_dir) if s.download_dir else os.path.join(data_dir(), "received")
    os.makedirs(d, exist_ok=True)
    return d


def _redraw():
    wm = getattr(bpy.context, "window_manager", None)
    if wm is None:
        return
    for window in wm.windows:
        for area in window.screen.areas:
            if area.type == "VIEW_3D":
                area.tag_redraw()


def _fail(prefix):
    def handler(err):
        state.error = "%s: %s" % (prefix, err)
        state.message = ""
        state.active_job = None
        if isinstance(err, sfclient.TransferError) and err.code in ("unreachable", "connection_lost"):
            state.status = "Disconnected"
        _redraw()
    return handler


def current_pairing():
    if state.connected_device:
        return state.connected_device
    pairs = store().pairings()
    return pairs[-1] if pairs else None


# ---------------------------------------------------------------------------------------------------------------

class SCANFORGE_OT_discover(bpy.types.Operator):
    bl_idname = "scanforge.discover"
    bl_label = "Find Phones"
    bl_description = "Look for ScanForge phones on the local network (UDP broadcast)"

    def execute(self, context):
        def run(job):
            return sfdiscovery.discover(timeout=1.5)

        def done(found):
            state.discovered = found
            state.message = "Found %d device(s)" % len(found) if found else "No devices answered. Enter the address shown on the phone."
            _redraw()

        worker.submit(Job("discover", run, done, _fail("Discovery failed")))
        return {"FINISHED"}


class SCANFORGE_OT_use_discovered(bpy.types.Operator):
    bl_idname = "scanforge.use_discovered"
    bl_label = "Use"
    index: bpy.props.IntProperty()

    def execute(self, context):
        d = state.discovered[self.index]
        s = context.scene.scanforge
        s.host, s.port = d["host"], int(d["port"])
        return {"FINISHED"}


class SCANFORGE_OT_pair(bpy.types.Operator):
    bl_idname = "scanforge.pair"
    bl_label = "Pair with Phone"
    bl_description = "Connect using the address and pairing code shown on the phone, then approve on the phone"

    def execute(self, context):
        s = context.scene.scanforge
        host, port, code = s.host.strip(), s.port, s.pairing_code
        if not host or len(sfclient.normalize_code(code)) != sfclient.PAIRING_CODE_CHARS:
            self.report({"ERROR"}, "Enter the phone's address and the 16-character pairing code")
            return {"CANCELLED"}
        client_id = store().client_id
        name = "Blender on %s" % socket.gethostname()

        def run(job):
            return sfclient.pair(host, port, code, client_id, name, cancel_event=job.cancel)

        def done(pairing):
            store().upsert(pairing)
            state.connected_device = pairing
            state.status = "Paired with %s" % pairing["device_name"]
            state.last_contact = time.time()
            state.message, state.error, state.active_job = "Pairing approved", "", None
            s.pairing_code = ""
            _redraw()

        state.error, state.message = "", "Waiting for approval on the phone…"
        state.active_job = worker.submit(Job("pair", run, done, _fail("Pairing failed")))
        return {"FINISHED"}


class SCANFORGE_OT_forget(bpy.types.Operator):
    bl_idname = "scanforge.forget"
    bl_label = "Forget Phone"
    bl_description = "Remove the pairing on this computer (and on the phone if reachable)"

    def invoke(self, context, event):
        return context.window_manager.invoke_confirm(self, event)

    def execute(self, context):
        p = current_pairing()
        if not p:
            return {"CANCELLED"}

        def run(job):
            try:
                sess = sfclient.Session(p, timeout=5)
                sess.unpair()
                sess.close()
            except sfclient.TransferError:
                pass  # Offline: the local pairing is still removed.
            return True

        store().remove(p["device_id"])
        state.connected_device, state.offers, state.status = None, [], "Not connected"
        worker.submit(Job("unpair", run))
        return {"FINISHED"}


class SCANFORGE_OT_refresh(bpy.types.Operator):
    bl_idname = "scanforge.refresh"
    bl_label = "Check for Scans"
    bl_description = "Ask the paired phone for scans it is offering (also reconnects)"

    @classmethod
    def poll(cls, context):
        return current_pairing() is not None

    def execute(self, context):
        p = current_pairing()
        s = context.scene.scanforge
        host = s.host.strip() or p["host"]
        port = s.port or p["port"]
        p = dict(p, host=host, port=port)

        def run(job):
            sess = sfclient.Session(p, timeout=10)
            try:
                sess.ping()
                return sess.offers()
            finally:
                sess.close()

        def done(offers):
            store().update_address(p["device_id"], host, port)
            state.connected_device = p
            state.offers = offers
            state.status = "Connected to %s" % p["device_name"]
            state.last_contact = time.time()
            state.error = ""
            state.message = "%d scan(s) offered" % len(offers)
            _redraw()

        worker.submit(Job("refresh", run, done, _fail("Connection failed")))
        return {"FINISHED"}


def receive(context, offer_id, then_import):
    p = current_pairing()
    dest_dir = downloads_dir(context)
    offer = next((o for o in state.offers if o["id"] == offer_id), None)
    if p is None or offer is None:
        raise RuntimeError("Offer not available; check for scans again")
    safe = "".join(c if c.isalnum() or c in "-_." else "_" for c in offer["file_name"]) or "scan.sf3d"
    dest = os.path.join(dest_dir, "%s_%s" % (offer["sha256"][:8], safe))
    settings = context.scene.scanforge

    def run(job):
        if os.path.exists(dest) and sfpkg.sha256_file(dest) == offer["sha256"]:
            # Already received (duplicate detection): confirm receipt again so the phone shows success.
            sess = sfclient.Session(p)
            try:
                sess.receipt(offer_id, True, offer["sha256"], offer["bytes"])
            finally:
                sess.close()
            return dest
        sess = sfclient.Session(p)
        try:
            def progress(done, total):
                job.progress = (done, total)
            sess.download(offer_id, dest, on_progress=progress, cancel_event=job.cancel)
        except sfclient.Cancelled:
            sfclient.cleanup_partial(dest)
            raise
        except sfclient.TransferError as e:
            if e.status == 410:
                sfclient.cleanup_partial(dest)
            raise
        finally:
            sess.close()
        return dest

    def done(path):
        state.received[offer_id] = path
        state.active_job = None
        state.message = "Received and verified %s" % os.path.basename(path)
        state.error = ""
        if then_import:
            do_import(bpy.context, path, settings)
        _redraw()

    state.error, state.message = "", "Receiving %s…" % offer["project_name"]
    state.active_job = worker.submit(Job("receive", run, done, _fail("Transfer failed")))
    return state.active_job


def do_import(context, path, settings, allow_duplicate=False):
    opts = importer.ImportOptions.from_settings(settings, allow_duplicate=allow_duplicate)
    try:
        objs, pkg = importer.import_package(context, path, opts, os.path.join(data_dir(), "extracted"))
    except importer.ImportBlocked as e:
        state.error = str(e)
        return None
    except sfpkg.PackageError as e:
        state.error = "Package rejected: %s" % e
        return None
    state.last_import = objs[0].name
    state.message = "Imported '%s'" % objs[0].name
    for o in context.view_layer.objects:
        o.select_set(False)
    if objs[0].name in context.view_layer.objects:
        objs[0].select_set(True)
        context.view_layer.objects.active = objs[0]
    return objs


class SCANFORGE_OT_receive(bpy.types.Operator):
    bl_idname = "scanforge.receive"
    bl_label = "Receive"
    bl_description = "Download and verify the scan; optionally import it right away"
    offer_id: bpy.props.StringProperty()
    import_after: bpy.props.BoolProperty(default=True)

    @classmethod
    def poll(cls, context):
        return not worker.busy()

    def execute(self, context):
        try:
            receive(context, self.offer_id, self.import_after)
        except RuntimeError as e:
            self.report({"ERROR"}, str(e))
            return {"CANCELLED"}
        return {"FINISHED"}


class SCANFORGE_OT_import_received(bpy.types.Operator):
    bl_idname = "scanforge.import_received"
    bl_label = "Import"
    offer_id: bpy.props.StringProperty()
    allow_duplicate: bpy.props.BoolProperty(name="Allow Duplicate", default=False)

    def execute(self, context):
        path = state.received.get(self.offer_id)
        if not path or not os.path.exists(path):
            self.report({"ERROR"}, "File not received yet")
            return {"CANCELLED"}
        return {"FINISHED"} if do_import(context, path, context.scene.scanforge, self.allow_duplicate) else {"CANCELLED"}


class SCANFORGE_OT_cancel(bpy.types.Operator):
    bl_idname = "scanforge.cancel"
    bl_label = "Cancel"
    bl_description = "Cancel the running pairing or transfer; partial files are deleted"

    def execute(self, context):
        if worker.current is not None:
            worker.current.cancel.set()
        state.message = "Cancelling…"
        return {"FINISHED"}


class SCANFORGE_OT_import_file(bpy.types.Operator):
    bl_idname = "scanforge.import_file"
    bl_label = "Import ScanForge File"
    bl_description = "Import a .sf3d package or an exported PLY/OBJ/STL/GLB file without the phone"
    bl_options = {"REGISTER", "UNDO"}

    filepath: bpy.props.StringProperty(subtype="FILE_PATH")
    filter_glob: bpy.props.StringProperty(default="*.sf3d;*.ply;*.obj;*.stl;*.glb;*.gltf", options={"HIDDEN"})
    allow_duplicate: bpy.props.BoolProperty(name="Allow Duplicate", default=False)

    def invoke(self, context, event):
        context.window_manager.fileselect_add(self)
        return {"RUNNING_MODAL"}

    def execute(self, context):
        settings = context.scene.scanforge
        if self.filepath.lower().endswith(".sf3d"):
            ok = do_import(context, self.filepath, settings, self.allow_duplicate)
            if not ok:
                self.report({"ERROR"}, state.error)
                return {"CANCELLED"}
            return {"FINISHED"}
        opts = importer.ImportOptions.from_settings(settings, self.allow_duplicate)
        name = os.path.splitext(os.path.basename(self.filepath))[0]
        col = importer._target_collection(context, settings.collection_name or name, settings.new_collection)
        try:
            importer.import_mesh_file(context, self.filepath, opts, name=name, collection=col)
        except (ValueError, RuntimeError) as e:
            self.report({"ERROR"}, str(e))
            return {"CANCELLED"}
        return {"FINISHED"}


classes = (
    SCANFORGE_OT_discover, SCANFORGE_OT_use_discovered, SCANFORGE_OT_pair, SCANFORGE_OT_forget, SCANFORGE_OT_refresh,
    SCANFORGE_OT_receive, SCANFORGE_OT_import_received, SCANFORGE_OT_cancel, SCANFORGE_OT_import_file,
)
