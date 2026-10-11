# ScanForge transfer protocol v1

Implementations: phone/server `core/transfer` (Kotlin, used by Android and `scanforge-cli serve`);
client `blender/scanforge3d/net/client.py`. Tests: `core/transfer/src/test`, `blender/tests/test_transfer.py`.

## Transport
* HTTP/1.1 over **TLS 1.2/1.3**, server = phone, default TCP port **47821**. Keep-alive allowed.
* Server certificate: self-signed EC P-256. Clients MUST NOT use CA validation; they MUST pin as below.
* Request bodies: JSON only, ≤ 64 KiB, `Content-Length` required (no chunked requests).
* Errors: JSON `{"error": "<code>", "message": "<human text>"}` with HTTP status.

## Server authentication (pinning)
* **Pairing code** = `base32(SHA-256(cert_DER))[0:16]` (RFC 4648 alphabet, no padding), displayed as `XXXX-XXXX-XXXX-XXXX`.
  Input is normalized: uppercase, separators removed, `0→O`, `1→I`, `8→B`.
* First contact: after the handshake and **before sending any request**, the client computes the code from the
  presented certificate and aborts on mismatch.
* After pairing the client stores the full lowercase hex SHA-256 fingerprint and aborts on any mismatch.

## Endpoints
| Method | Path | Auth | Purpose |
|---|---|---|---|
| GET | `/v1/info` | none | `{service:"scanforge", protocol_version:1, app_name, app_version, device_name, device_id, pairing_open}` |
| POST | `/v1/pair` | none | `{client_id (8–128 chars), client_name, client_app}` → 202 `{request_id, status:"pending"}`; 403 `pairing_closed` unless pairing is open on the phone; 429 if 3 requests pending |
| GET | `/v1/pair/{request_id}` | none | `{request_id, status: pending|approved|denied|expired, token?}`; the token is returned **once** on approval; requests expire after 2 min |
| POST | `/v1/ping` | bearer | Heartbeat; returns info |
| GET | `/v1/offers` | bearer | `{offers:[{id, project_id, project_name, file_name, format:"sf3d", bytes, sha256, offered_at, status}]}` |
| GET | `/v1/offers/{id}` | bearer | Manifest `{offer, chunk_size, chunk_sha256:[...]}`; 410 if withdrawn |
| GET | `/v1/offers/{id}/chunks/{n}` | bearer | Raw bytes of chunk n (`application/octet-stream`), header `X-Chunk-SHA256`; 410 if withdrawn |
| POST | `/v1/offers/{id}/receipt` | bearer | `{status:"verified"|"failed", sha256, bytes, detail?}`; 200 when the server's own hash and size match, else 409 |
| DELETE | `/v1/pairing` | bearer | Client revokes its own pairing |

Bearer auth: `Authorization: Bearer <token>`. 401 `unauthorized` when unknown/revoked; after 10 failures from one
address, 429 `locked_out` for 1 minute. Server stores only SHA-256(token).

## Transfer semantics
* Offers exist only for packages the phone user chose to send. Re-offering identical content replaces the old offer.
* Chunk size 1 MiB by default (64 KiB–8 MiB allowed). Clients verify each chunk hash, append to `<dest>.part`, and
  record progress in `<dest>.part.json`, so interrupted transfers resume at the last verified chunk.
* After the last chunk the client verifies size and whole-file SHA-256, atomically renames the file, then posts a
  `verified` receipt. On mismatch it deletes partial data and posts `failed`.
* The phone reports a transfer successful **only** on a matching `verified` receipt.
* Cancellation: client cancel deletes partial files; phone cancel withdraws the offer (subsequent requests → 410).
* Timeouts: socket timeout 30 s on the server; client 10–30 s; chunk requests retried up to 3× with exponential
  backoff except on 401/403/404/410.

## Discovery (optional)
UDP broadcast of ASCII `SF3D_DISCOVER_V1` to port **47820**; phones reply unicast with JSON
`{service, protocol_version, device_name, device_id, port, pairing_open}`. Replies are unauthenticated hints.

## Versioning
`protocol_version` is an integer. Clients refuse other major versions with an "update app/add-on" message.
New optional fields may be added without a version bump; clients ignore unknown fields.
