"""Persistent pairing storage (pure Python). Tokens are secrets: the file is created with 0600 permissions."""

import json
import os
import uuid


class PairingStore:
    def __init__(self, directory):
        self.directory = directory
        self.path = os.path.join(directory, "pairings.json")
        self._data = {"client_id": None, "pairings": []}
        self._load()

    def _load(self):
        try:
            with open(self.path, "r", encoding="utf-8") as f:
                self._data = json.load(f)
        except (OSError, ValueError):
            pass
        if not self._data.get("client_id"):
            self._data["client_id"] = "blender-" + uuid.uuid4().hex
            self._save()

    def _save(self):
        os.makedirs(self.directory, exist_ok=True)
        tmp = self.path + ".tmp"
        fd = os.open(tmp, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
        with os.fdopen(fd, "w", encoding="utf-8") as f:
            json.dump(self._data, f, indent=2)
        os.replace(tmp, self.path)

    @property
    def client_id(self):
        return self._data["client_id"]

    def pairings(self):
        return list(self._data.get("pairings", []))

    def get(self, device_id):
        return next((p for p in self.pairings() if p.get("device_id") == device_id), None)

    def upsert(self, pairing):
        rest = [p for p in self.pairings() if p.get("device_id") != pairing.get("device_id")]
        self._data["pairings"] = rest + [pairing]
        self._save()

    def update_address(self, device_id, host, port):
        for p in self._data.get("pairings", []):
            if p.get("device_id") == device_id:
                p["host"], p["port"] = host, int(port)
        self._save()

    def remove(self, device_id):
        self._data["pairings"] = [p for p in self.pairings() if p.get("device_id") != device_id]
        self._save()
