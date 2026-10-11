"""ScanForge transfer protocol v1 client (pure Python, thread-safe per instance, no bpy).

The phone is the TLS server. Its self-signed certificate is authenticated by:
  * first pairing: the 16-character pairing code shown on the phone, which must equal the first 80 bits
    (base32) of SHA-256(certificate DER) presented in the TLS handshake;
  * afterwards: the full pinned SHA-256 fingerprint stored at pairing time.
The certificate is checked before any request (and therefore before any token) is sent.
Spec: docs/protocol/transfer-protocol-v1.md
"""

import base64
import hashlib
import http.client
import json
import os
import ssl
import time

PROTOCOL_VERSION = 1
DEFAULT_PORT = 47821
PAIRING_CODE_CHARS = 16
USER_AGENT = "ScanForge-Blender/0.1"


class TransferError(Exception):
    def __init__(self, message, code=None, status=None):
        super().__init__(message)
        self.code = code
        self.status = status


class CertificateMismatch(TransferError):
    pass


class Cancelled(TransferError):
    pass


def normalize_code(text):
    out = []
    for ch in text.upper():
        if ch.isalnum():
            out.append({"0": "O", "1": "I", "8": "B"}.get(ch, ch))
    return "".join(out)


def pairing_code_from_der(der):
    digest = hashlib.sha256(der).digest()
    return base64.b32encode(digest).decode("ascii").rstrip("=")[:PAIRING_CODE_CHARS]


def format_code(code):
    return "-".join(code[i:i + 4] for i in range(0, len(code), 4))


class PinnedConnection:
    """One TLS connection whose server certificate is verified against a code or a pinned fingerprint."""

    def __init__(self, host, port, fingerprint=None, pairing_code=None, timeout=15.0):
        if not fingerprint and not pairing_code:
            raise ValueError("Either a pinned fingerprint or a pairing code is required")
        self.host, self.port, self.timeout = host, int(port), timeout
        self.expected_fp = fingerprint.lower() if fingerprint else None
        self.expected_code = normalize_code(pairing_code) if pairing_code else None
        self.fingerprint = None
        ctx = ssl.SSLContext(ssl.PROTOCOL_TLS_CLIENT)
        ctx.minimum_version = ssl.TLSVersion.TLSv1_2
        # The certificate is self-signed; it is verified below by pinning instead of a CA chain.
        ctx.check_hostname = False
        ctx.verify_mode = ssl.CERT_NONE
        self._ctx = ctx
        self._conn = None

    def _connect(self):
        conn = http.client.HTTPSConnection(self.host, self.port, timeout=self.timeout, context=self._ctx)
        try:
            conn.connect()
        except (OSError, ssl.SSLError) as e:
            raise TransferError("Cannot reach %s:%s (%s). Check the address, that both devices are on the same "
                                "network, and the firewall." % (self.host, self.port, e), code="unreachable")
        der = conn.sock.getpeercert(binary_form=True)
        fp = hashlib.sha256(der).hexdigest()
        if self.expected_fp is not None:
            if fp != self.expected_fp:
                conn.close()
                raise CertificateMismatch("The phone presented a different certificate than when it was paired. "
                                          "If the app was reinstalled, pair again; otherwise something may be "
                                          "intercepting the connection.", code="certificate_mismatch")
        else:
            if len(self.expected_code) != PAIRING_CODE_CHARS or pairing_code_from_der(der) != self.expected_code:
                conn.close()
                raise CertificateMismatch("Pairing code does not match the device at this address. Check the "
                                          "code shown on the phone.", code="code_mismatch")
        self.fingerprint = fp
        self._conn = conn

    def request(self, method, path, body=None, token=None, raw=False, retry=True):
        if self._conn is None:
            self._connect()
        headers = {"User-Agent": USER_AGENT, "Accept": "application/json"}
        data = None
        if body is not None:
            data = json.dumps(body).encode("utf-8")
            headers["Content-Type"] = "application/json"
        if token:
            headers["Authorization"] = "Bearer " + token
        try:
            self._conn.request(method, path, body=data, headers=headers)
            resp = self._conn.getresponse()
            payload = resp.read()
        except (http.client.HTTPException, OSError, ssl.SSLError) as e:
            self.close()
            if retry:
                # Keep-alive connections may be closed by the server between requests: reconnect once.
                return self.request(method, path, body, token, raw, retry=False)
            raise TransferError("Connection lost: %s" % e, code="connection_lost")
        if resp.getheader("Connection", "").lower() == "close":
            self.close()
        if resp.status >= 400:
            try:
                err = json.loads(payload.decode("utf-8"))
                raise TransferError(err.get("message", "HTTP %d" % resp.status), code=err.get("error"), status=resp.status)
            except ValueError:
                raise TransferError("HTTP %d" % resp.status, status=resp.status)
        if raw:
            return payload, dict((k.lower(), v) for k, v in resp.getheaders())
        if resp.status == 204 or not payload:
            return None
        return json.loads(payload.decode("utf-8"))

    def close(self):
        if self._conn is not None:
            try:
                self._conn.close()
            finally:
                self._conn = None


def get_info(host, port, pairing_code=None, fingerprint=None, timeout=10.0):
    c = PinnedConnection(host, port, fingerprint=fingerprint, pairing_code=pairing_code, timeout=timeout)
    try:
        info = c.request("GET", "/v1/info")
        if info.get("service") != "scanforge":
            raise TransferError("The device at this address is not a ScanForge app")
        if int(info.get("protocol_version", 0)) != PROTOCOL_VERSION:
            raise TransferError("Protocol version %s is not supported by this add-on (expects %d). Update the app "
                                "or the add-on." % (info.get("protocol_version"), PROTOCOL_VERSION), code="version")
        return info, c.fingerprint
    finally:
        c.close()


def pair(host, port, pairing_code, client_id, client_name, wait_timeout=120.0, poll=1.0, cancel_event=None, on_status=None):
    """Runs the pairing flow. Returns a dict to persist: host, port, fingerprint, token, device info."""
    info, fp = get_info(host, port, pairing_code=pairing_code)
    c = PinnedConnection(host, port, fingerprint=fp)
    try:
        start = c.request("POST", "/v1/pair", {"client_id": client_id, "client_name": client_name, "client_app": USER_AGENT})
        rid = start["request_id"]
        if on_status:
            on_status("Waiting for approval on the phone…")
        deadline = time.monotonic() + wait_timeout
        while time.monotonic() < deadline:
            if cancel_event is not None and cancel_event.is_set():
                raise Cancelled("Pairing cancelled", code="cancelled")
            st = c.request("GET", "/v1/pair/" + rid)
            if st["status"] == "approved":
                return {
                    "host": host, "port": int(port), "fingerprint": fp, "token": st["token"],
                    "device_name": info.get("device_name", ""), "device_id": info.get("device_id", ""),
                    "paired_at": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()),
                }
            if st["status"] in ("denied", "expired"):
                raise TransferError("Pairing was %s on the phone" % st["status"], code=st["status"])
            time.sleep(poll)
        raise TransferError("Timed out waiting for approval on the phone", code="timeout")
    finally:
        c.close()


class Session:
    """Authenticated session with a paired phone."""

    def __init__(self, pairing, timeout=30.0):
        self.pairing = pairing
        self.conn = PinnedConnection(pairing["host"], pairing["port"], fingerprint=pairing["fingerprint"], timeout=timeout)

    def ping(self):
        return self.conn.request("POST", "/v1/ping", {}, token=self.pairing["token"])

    def offers(self):
        return self.conn.request("GET", "/v1/offers", token=self.pairing["token"])["offers"]

    def manifest(self, offer_id):
        return self.conn.request("GET", "/v1/offers/" + offer_id, token=self.pairing["token"])

    def unpair(self):
        self.conn.request("DELETE", "/v1/pairing", token=self.pairing["token"])

    def receipt(self, offer_id, ok, sha256, size, detail=None):
        body = {"status": "verified" if ok else "failed", "sha256": sha256, "bytes": size}
        if detail:
            body["detail"] = detail
        return self.conn.request("POST", "/v1/offers/%s/receipt" % offer_id, body, token=self.pairing["token"])

    def download(self, offer_id, dest_path, on_progress=None, cancel_event=None, max_retries=3):
        """Downloads an offer to dest_path with resume, per-chunk and whole-file SHA-256 verification.

        Partial data lives in dest_path + '.part' with a JSON progress file so an interrupted transfer
        resumes from the last verified chunk. The final file only appears after full verification, and the
        phone is then sent a receipt so it can report success. Returns the manifest's offer summary.
        """
        man = self.manifest(offer_id)
        offer, chunk_size, hashes = man["offer"], int(man["chunk_size"]), man["chunk_sha256"]
        part, state_path = dest_path + ".part", dest_path + ".part.json"
        done = 0
        if os.path.exists(part) and os.path.exists(state_path):
            try:
                with open(state_path) as f:
                    st = json.load(f)
                if st.get("sha256") == offer["sha256"] and st.get("chunk_size") == chunk_size:
                    done = int(st.get("chunks_done", 0))
            except (OSError, ValueError):
                done = 0
        if done == 0:
            open(part, "wb").close()
        with open(part, "r+b") as out:
            out.truncate(done * chunk_size)
            out.seek(done * chunk_size)
            for i in range(done, len(hashes)):
                if cancel_event is not None and cancel_event.is_set():
                    raise Cancelled("Transfer cancelled", code="cancelled")
                attempt = 0
                while True:
                    try:
                        data, headers = self.conn.request("GET", "/v1/offers/%s/chunks/%d" % (offer_id, i),
                                                          token=self.pairing["token"], raw=True)
                        if hashlib.sha256(data).hexdigest() != hashes[i]:
                            raise TransferError("Chunk %d failed its checksum" % i, code="chunk_checksum")
                        break
                    except TransferError as e:
                        attempt += 1
                        if e.status in (401, 403, 404, 410) or attempt > max_retries:
                            raise
                        time.sleep(min(8.0, 0.5 * 2 ** attempt))
                out.write(data)
                out.flush()
                with open(state_path, "w") as f:
                    json.dump({"sha256": offer["sha256"], "chunk_size": chunk_size, "chunks_done": i + 1}, f)
                if on_progress:
                    on_progress(min(offer["bytes"], (i + 1) * chunk_size), offer["bytes"])
        size = os.path.getsize(part)
        h = hashlib.sha256()
        with open(part, "rb") as f:
            for b in iter(lambda: f.read(1 << 20), b""):
                h.update(b)
        digest = h.hexdigest()
        ok = digest == offer["sha256"] and size == offer["bytes"]
        if not ok:
            cleanup_partial(dest_path)
            try:
                self.receipt(offer_id, False, digest, size, "Whole-file checksum mismatch")
            except TransferError:
                pass  # The local failure is what matters; the phone will show the transfer as unconfirmed.
            raise TransferError("Received file failed verification and was discarded", code="file_checksum")
        if os.path.exists(dest_path):
            os.remove(dest_path)
        os.replace(part, dest_path)
        os.remove(state_path)
        self.receipt(offer_id, True, digest, size)
        return offer

    def close(self):
        self.conn.close()


def cleanup_partial(dest_path):
    for p in (dest_path + ".part", dest_path + ".part.json"):
        try:
            os.remove(p)
        except OSError:
            pass
