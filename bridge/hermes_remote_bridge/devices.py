"""Paired-device registry. Tokens are shown once at pairing; only their SHA-256 is stored."""
from __future__ import annotations

import hashlib
import json
import os
import secrets
import threading
import time
import uuid
from dataclasses import asdict, dataclass
from pathlib import Path

TOKEN_PREFIX = "hrb_"


def hash_token(token: str) -> str:
    return hashlib.sha256(token.encode()).hexdigest()


@dataclass
class Device:
    id: str
    name: str
    token_sha256: str
    created_at: float
    revoked_at: float | None = None
    last_seen_at: float | None = None

    @property
    def active(self) -> bool:
        return self.revoked_at is None


class DeviceStore:
    """JSON-file store, re-read whenever the file changes so CLI pair/revoke apply live."""

    def __init__(self, path: Path):
        self.path = path
        self._lock = threading.Lock()
        self._mtime: float | None = None
        self._devices: dict[str, Device] = {}
        self._by_hash: dict[str, Device] = {}
        self._last_seen_dirty: dict[str, float] = {}

    def _load_if_changed(self) -> None:
        try:
            mtime = self.path.stat().st_mtime
        except FileNotFoundError:
            self._devices, self._by_hash, self._mtime = {}, {}, None
            return
        if mtime == self._mtime:
            return
        raw = json.loads(self.path.read_text() or "{}")
        self._devices = {d["id"]: Device(**d) for d in raw.get("devices", [])}
        self._by_hash = {d.token_sha256: d for d in self._devices.values()}
        self._mtime = mtime

    def _save(self) -> None:
        self.path.parent.mkdir(parents=True, exist_ok=True)
        os.chmod(self.path.parent, 0o700)
        tmp = self.path.with_suffix(".tmp")
        payload = {"version": 1, "devices": [asdict(d) for d in self._devices.values()]}
        fd = os.open(tmp, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
        with os.fdopen(fd, "w") as fh:
            json.dump(payload, fh, indent=2)
        os.replace(tmp, self.path)
        self._mtime = self.path.stat().st_mtime

    def authenticate(self, token: str) -> Device | None:
        if not token.startswith(TOKEN_PREFIX) or len(token) > 200:
            return None
        with self._lock:
            self._load_if_changed()
            device = self._by_hash.get(hash_token(token))
            if device is None or not device.active:
                return None
            self._last_seen_dirty[device.id] = time.time()
            return device

    def flush_last_seen(self) -> None:
        with self._lock:
            if not self._last_seen_dirty:
                return
            self._mtime = None
            self._load_if_changed()
            for device_id, ts in self._last_seen_dirty.items():
                if device_id in self._devices:
                    self._devices[device_id].last_seen_at = ts
            self._last_seen_dirty.clear()
            self._save()

    def pair(self, name: str, *, replace: bool = False) -> tuple[Device, str]:
        """A new device and its token. With [replace], an active device of the same name is
        revoked in the same step (re-pairing a phone whose pairing broke), so its old token
        stops working the moment the new one exists."""
        name = name.strip()
        if not name or len(name) > 64:
            raise ValueError("Device name must be 1-64 characters")
        with self._lock:
            self._mtime = None
            self._load_if_changed()
            same = [d for d in self._devices.values() if d.name == name and d.active]
            if same and not replace:
                raise ValueError(f"An active device named {name!r} already exists; "
                                 f"re-pair it with `pair {name} --replace`, or revoke it first")
            now = time.time()
            for old in same:
                old.revoked_at = now
            token = TOKEN_PREFIX + secrets.token_urlsafe(32)
            device = Device(id=uuid.uuid4().hex[:12], name=name, token_sha256=hash_token(token),
                            created_at=time.time())
            self._devices[device.id] = device
            self._by_hash[device.token_sha256] = device
            self._save()
            return device, token

    def revoke(self, name_or_id: str) -> Device:
        with self._lock:
            self._mtime = None
            self._load_if_changed()
            matches = [d for d in self._devices.values()
                       if d.active and (d.id == name_or_id or d.name == name_or_id)]
            if len(matches) != 1:
                raise ValueError(f"Expected exactly one active device matching {name_or_id!r}, found {len(matches)}")
            matches[0].revoked_at = time.time()
            self._save()
            return matches[0]

    def list(self) -> list[Device]:
        with self._lock:
            self._load_if_changed()
            return sorted(self._devices.values(), key=lambda d: d.created_at)
