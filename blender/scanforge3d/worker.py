"""Background network worker.

Blender's Python API must only be used from the main thread. Network jobs therefore run on one daemon
thread and push plain-Python results onto a queue; drain() runs on the main thread (registered with
bpy.app.timers by the add-on, called directly by tests) and invokes the callbacks there.
"""

import queue
import threading
import time
import traceback


class Job:
    def __init__(self, kind, fn, on_done=None, on_error=None):
        self.kind = kind
        self.fn = fn
        self.on_done = on_done
        self.on_error = on_error
        self.cancel = threading.Event()
        self.progress = None  # (done, total) updated by the worker thread
        self.status = "queued"


class State:
    """Plain-Python UI state read by panels. Written only on the main thread (in drain callbacks)."""

    def __init__(self):
        self.status = "Not connected"
        self.connected_device = None  # pairing dict
        self.last_contact = None
        self.offers = []
        self.discovered = []
        self.message = ""
        self.error = ""
        self.active_job = None
        self.received = {}  # offer id -> local file path
        self.last_import = None


class Worker:
    def __init__(self):
        self._jobs = queue.Queue()
        self._results = queue.Queue()
        self._thread = None
        self._stop = threading.Event()
        self.current = None

    def start(self):
        if self._thread is not None and self._thread.is_alive():
            return
        self._stop.clear()
        self._thread = threading.Thread(target=self._run, name="scanforge-worker", daemon=True)
        self._thread.start()

    def stop(self):
        self._stop.set()
        if self.current is not None:
            self.current.cancel.set()
        self._jobs.put(None)

    def submit(self, job):
        self.start()
        self._jobs.put(job)
        return job

    def busy(self):
        return self.current is not None or not self._jobs.empty()

    def _run(self):
        while not self._stop.is_set():
            job = self._jobs.get()
            if job is None:
                break
            self.current = job
            job.status = "running"
            try:
                result = job.fn(job)
                self._results.put((job, True, result))
            except Exception as e:  # reported to the UI on the main thread
                self._results.put((job, False, (e, traceback.format_exc())))
            finally:
                self.current = None

    def drain(self):
        """Runs completed-job callbacks. Main thread only. Returns the number of results processed."""
        n = 0
        while True:
            try:
                job, ok, payload = self._results.get_nowait()
            except queue.Empty:
                return n
            n += 1
            job.status = "done" if ok else "failed"
            try:
                if ok and job.on_done:
                    job.on_done(payload)
                elif not ok and job.on_error:
                    job.on_error(payload[0])
            except Exception:
                traceback.print_exc()

    def wait_idle(self, timeout=60.0, drain=True):
        """Test helper: waits until no job is running, draining results on the calling (main) thread."""
        end = time.monotonic() + timeout
        while time.monotonic() < end:
            if drain:
                self.drain()
            if not self.busy() and self._results.empty():
                return True
            time.sleep(0.02)
        return False
