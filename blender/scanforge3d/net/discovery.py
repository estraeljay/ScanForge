"""LAN discovery: broadcasts a probe and collects replies from ScanForge phones (pure Python).

Replies are unauthenticated hints for convenience only; the TLS certificate is always verified
against the pairing code or the pinned fingerprint.
"""

import json
import socket
import time

DISCOVERY_PORT = 47820
PROBE = b"SF3D_DISCOVER_V1"


def discover(timeout=1.5, port=DISCOVERY_PORT, targets=("255.255.255.255",)):
    found = {}
    s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    try:
        s.setsockopt(socket.SOL_SOCKET, socket.SO_BROADCAST, 1)
        s.settimeout(0.2)
        for t in targets:
            try:
                s.sendto(PROBE, (t, port))
            except OSError:
                pass
        end = time.monotonic() + timeout
        while time.monotonic() < end:
            try:
                data, addr = s.recvfrom(2048)
            except socket.timeout:
                continue
            except OSError:
                break
            try:
                info = json.loads(data.decode("utf-8"))
            except ValueError:
                continue
            if info.get("service") != "scanforge":
                continue
            info["host"] = addr[0]
            found[(addr[0], info.get("port"))] = info
    finally:
        s.close()
    return list(found.values())
