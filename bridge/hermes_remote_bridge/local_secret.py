"""The local secret: what proves a request to `/v1/local/*` comes from a local client.

A 32-byte hex value in `<config_dir>/local-secret`, readable only by the user (0600 on POSIX; on
Windows the per-user %LOCALAPPDATA% directory already is). Deleting the file revokes every local
client: the next request creates a new value.
"""
from __future__ import annotations

import hmac
import os
import secrets
from pathlib import Path


def ensure_secret(path: Path) -> str:
    """The secret in `path`, creating the file (and its folder) when it is missing or empty."""
    try:
        value = path.read_text().strip()
        if value:
            return value
    except FileNotFoundError:
        pass
    path.parent.mkdir(parents=True, exist_ok=True)
    value = secrets.token_hex(32)
    fd = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
    with os.fdopen(fd, "w") as fh:
        fh.write(value)
    return value


def matches(path: Path, presented: str) -> bool:
    """True when `presented` is the current secret (constant-time compare)."""
    return hmac.compare_digest(ensure_secret(path).encode(), presented.encode())
