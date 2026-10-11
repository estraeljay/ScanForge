"""Wireless transfer against the real protocol implementation (core CLI `serve`, same code as the phone)."""

import os
import shutil
import subprocess
import tempfile
import threading
import time
import unittest

import bpy

from helpers import CLI, fixture, reset_scene
from scanforge3d import operators as ops
from scanforge3d.net import client as sfclient


class Server:
    def __init__(self, offer, state_dir, port=0):
        self.state_dir = state_dir
        args = [CLI, "serve", state_dir, "--offer", offer, "--port", str(port), "--bind", "127.0.0.1", "--auto-approve"]
        self.proc = subprocess.Popen(args, stdout=subprocess.PIPE, stderr=subprocess.DEVNULL, text=True)
        self.lines = []
        self.info = {}
        self._reader = threading.Thread(target=self._read, daemon=True)
        self._reader.start()
        end = time.time() + 60
        while time.time() < end and not ("PAIRING_CODE" in self.info and "LISTENING" in self.info):
            time.sleep(0.05)
        if "PAIRING_CODE" not in self.info:
            self.stop()
            raise RuntimeError("server did not start: %s" % self.lines)
        self.host, self.port = self.info["LISTENING"].rsplit(":", 1)
        self.port = int(self.port)
        self.code = self.info["PAIRING_CODE"]

    def _read(self):
        for line in self.proc.stdout:
            line = line.strip()
            self.lines.append(line)
            key, _, rest = line.partition(" ")
            self.info[key] = rest

    def wait_for(self, prefix, timeout=15):
        end = time.time() + timeout
        while time.time() < end:
            for l in self.lines:
                if l.startswith(prefix):
                    return l
            time.sleep(0.05)
        return None

    def stop(self):
        self.proc.terminate()
        try:
            self.proc.wait(10)
        except subprocess.TimeoutExpired:
            self.proc.kill()


@unittest.skipUnless(CLI and os.path.exists(CLI), "SF_CLI not set")
class TransferTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.mkdtemp()
        self.server = Server(fixture("box_full.sf3d"), os.path.join(self.tmp, "phone"))

    def tearDown(self):
        self.server.stop()
        shutil.rmtree(self.tmp, ignore_errors=True)

    def pair(self):
        return sfclient.pair(self.server.host, self.server.port, self.server.code, "blender-test-client", "Test Blender",
                             poll=0.1)

    def test_wrong_pairing_code_is_rejected_before_any_request(self):
        with self.assertRaises(sfclient.CertificateMismatch):
            sfclient.pair(self.server.host, self.server.port, "AAAA-BBBB-CCCC-DDDD", "blender-test-client", "x")
        self.assertIsNone(self.server.wait_for("PAIRING_REQUEST", timeout=0.5))

    def test_pair_download_verify_and_receipt(self):
        pairing = self.pair()
        self.assertEqual(len(pairing["fingerprint"]), 64)
        sess = sfclient.Session(pairing)
        offers = sess.offers()
        self.assertEqual(len(offers), 1)
        dest = os.path.join(self.tmp, "recv.sf3d")
        progress = []
        sess.download(offers[0]["id"], dest, on_progress=lambda d, t: progress.append((d, t)))
        sess.close()
        self.assertEqual(len(progress), 3)  # 2.2 MB in 1 MiB chunks
        with open(dest, "rb") as a, open(fixture("box_full.sf3d"), "rb") as b:
            self.assertEqual(a.read(), b.read())
        self.assertFalse(os.path.exists(dest + ".part"))
        self.assertIsNotNone(self.server.wait_for("RECEIPT"))
        self.assertIn("verified=true", self.server.wait_for("RECEIPT"))

    def test_interrupted_download_resumes(self):
        pairing = self.pair()
        sess = sfclient.Session(pairing)
        offer = sess.offers()[0]
        dest = os.path.join(self.tmp, "resume.sf3d")
        cancel = threading.Event()
        with self.assertRaises(sfclient.Cancelled):
            sess.download(offer["id"], dest, on_progress=lambda d, t: cancel.set(), cancel_event=cancel)
        self.assertTrue(os.path.exists(dest + ".part"))
        self.assertIsNone(self.server.wait_for("RECEIPT", timeout=0.3), "no receipt for partial data")
        progress = []
        sess.download(offer["id"], dest, on_progress=lambda d, t: progress.append(d))
        self.assertEqual(len(progress), 2, "resumed after the first verified chunk")
        self.assertIn("verified=true", self.server.wait_for("RECEIPT"))

    def test_certificate_change_is_detected_after_pairing(self):
        pairing = self.pair()
        self.server.stop()
        os.remove(os.path.join(self.server.state_dir, "server.p12"))
        self.server = Server(fixture("box.sf3d"), self.server.state_dir, port=self.server.port)
        with self.assertRaises(sfclient.CertificateMismatch):
            sfclient.Session(pairing).offers()

    def test_bad_token_is_rejected(self):
        pairing = dict(self.pair(), token="forged")
        with self.assertRaises(sfclient.TransferError) as cm:
            sfclient.Session(pairing).offers()
        self.assertEqual(cm.exception.status, 401)

    def test_operator_flow_receive_and_import(self):
        """The UI path: Pair -> Check for Scans -> Receive & Import, with worker results drained on the main thread."""
        reset_scene()
        ops._store = None
        os.environ["HOME"] = self.tmp  # isolate pairing storage
        s = bpy.context.scene.scanforge
        s.host, s.port, s.pairing_code = self.server.host, self.server.port, self.server.code
        s.download_dir = os.path.join(self.tmp, "downloads")
        ops.state.__init__()
        self.assertEqual(bpy.ops.scanforge.pair(), {"FINISHED"})
        self.assertTrue(ops.worker.wait_idle(60))
        self.assertEqual(ops.state.error, "")
        self.assertIsNotNone(ops.state.connected_device)
        bpy.ops.scanforge.refresh()
        self.assertTrue(ops.worker.wait_idle(60))
        self.assertEqual(len(ops.state.offers), 1)
        bpy.ops.scanforge.receive(offer_id=ops.state.offers[0]["id"], import_after=True)
        self.assertTrue(ops.worker.wait_idle(120))
        self.assertEqual(ops.state.error, "")
        self.assertIn("Synthetic Box", bpy.data.objects)
        self.assertIn("verified=true", self.server.wait_for("RECEIPT"))
        ops.store().remove(ops.state.connected_device["device_id"])
