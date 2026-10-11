# ADR 0002 — Phone-to-Blender transfer: direction and security

**Status:** accepted (2026-10).

## Context
The brief asks for an authenticated, preferably encrypted LAN transfer with explicit approval, no cloud, and a Blender
panel showing connection state. Blender's bundled Python has `ssl` but **no way to create a certificate** without
third-party packages (e.g. `cryptography`, which would need per-platform wheels in the extension).

## Decision
* **The phone is the TLS server; Blender is the client.** The phone generates an EC P-256 key in AndroidKeyStore
  (non-exportable) with a self-signed certificate. Python's standard `ssl` can then do TLS 1.2/1.3 as a client.
* **Server authentication by pairing code:** the phone shows `XXXX-XXXX-XXXX-XXXX` = first 80 bits (base32) of
  SHA-256(certificate DER). Blender checks the handshake certificate against it **before sending anything**, then
  pins the full fingerprint. Finding a colliding certificate for 80 bits is infeasible.
* **Client authorization by explicit approval:** pairing requests are only accepted while "Allow new computers to
  pair" is on; the phone user approves each request; a 256-bit bearer token is returned once; the phone stores only
  its SHA-256. Tokens can be revoked on either side. 10 failed authentications lock an address out for 1 minute.
* **Integrity end-to-end:** per-chunk SHA-256 (1 MiB chunks), whole-file SHA-256, and a receipt from Blender; the phone
  reports success only after a matching receipt.
* **Network exposure:** the server binds only to the phone's Wi-Fi/Ethernet IPv4 address, never on mobile data, and
  runs only while the user has transfers enabled (foreground service with notification).
* **Discovery:** Blender broadcasts a UDP probe; phones reply with name/port. Replies are untrusted hints only.

## Deviations from the brief and why
The brief sketches Blender listening ("Local IP and port", "Start or stop receiving"). Reversing the direction gives
real TLS with stdlib only and avoids opening an inbound port on the desktop firewall. The Blender panel therefore
shows the *phone's* address and "Reconnect / Check for Scans" instead of start/stop listening; auto-receive is done
by checking for offers. USB/manual transfer: export `.sf3d` and import from disk.

## Consequences
+ Encrypted, mutually authorized, integrity-checked, no desktop inbound port. − The phone app must be open (or its
foreground service running) during transfers. − Untested on physical phones: AndroidKeyStore keys in a server
`SSLContext` (custom `X509ExtendedKeyManager`).
