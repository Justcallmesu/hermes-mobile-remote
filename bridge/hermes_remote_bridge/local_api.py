"""The documents the CLI's `--json` output and the `/v1/local/*` routes both return.

One builder per document, so the two cannot drift.
"""
from __future__ import annotations

import io

from .devices import DeviceStore
from .network import TrustStore
from .tls import cert_pin, ensure_identity


def devices_doc(store: DeviceStore) -> dict:
    return {"devices": [
        {"id": d.id, "name": d.name, "active": d.active, "created_at": d.created_at,
         "last_seen_at": d.last_seen_at, "revoked_at": d.revoked_at} for d in store.list()]}


def trust_doc(trust: TrustStore, current) -> dict:
    rows = trust.list()
    return {
        "current": ({"id": current.id, "name": current.name, "trusted": current.id in rows,
                     "interface": current.interface} if current else None),
        "trusted": [{"id": net_id, "name": info.get("name", "?"), "since": info.get("since")}
                    for net_id, info in rows.items()],
    }


def pairing_payload(device, token: str, urls: list[str], tls_dir) -> dict:
    """What the pairing code carries. v2: LAN entries are HTTPS and `pin` is the certificate the app
    must see before it sends the token there; Tailscale entries stay HTTP inside WireGuard."""
    payload = {"v": 2, "url": urls[0], "device": device.name, "token": token,
               "pin": cert_pin(ensure_identity(tls_dir)[0])}
    if len(urls) > 1:
        payload["alt"] = ",".join(urls[1:])
    return payload


def qr_png(uri: str) -> bytes:
    import segno

    buf = io.BytesIO()
    segno.make(uri, error="m").save(buf, kind="png", scale=8, border=3, dark="#000", light="#fff")
    return buf.getvalue()
