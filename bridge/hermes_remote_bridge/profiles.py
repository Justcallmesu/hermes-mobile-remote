"""Which Hermes profile a phone request is for.

One `hermes serve` serves every profile on the PC; a request names the one it means with a
`profile` parameter (REST query, or the JSON-RPC params), and no name means the default
profile. This module is the single place that decides whether a name is acceptable, so the
REST endpoints and the live relay can never disagree.
"""
from __future__ import annotations

import os
import re
from collections.abc import Iterable
from pathlib import Path

# Hermes' own rule for a profile id (hermes_constants.PROFILE_ID_RE), plus "default".
PROFILE_ID_RE = re.compile(r"^[a-z0-9][a-z0-9_-]{0,63}$")
DEFAULT = "default"


class ProfileRefused(ValueError):
    """The name is malformed (400), or this bridge does not let the phone use that profile (403)."""

    def __init__(self, message: str, status: int = 403):
        super().__init__(message)
        self.status = status


def resolve_profile(value: str | None, allowed: Iterable[str] = ()) -> str | None:
    """The named profile a request targets, or None for the default profile.

    `allowed` empty means every profile; otherwise only those names (the default profile is
    always reachable, because every request without a name already uses it).
    """
    name = (value or "").strip().lower()
    if name in ("", DEFAULT):
        return None
    if not PROFILE_ID_RE.match(name):
        raise ProfileRefused(f"Invalid profile name: {value!r}", status=400)
    allowed = set(allowed)
    if allowed and name not in allowed:
        raise ProfileRefused(f"Profile '{name}' is not available from the phone")
    return name


def is_visible(name: str, allowed: Iterable[str] = ()) -> bool:
    """Whether a profile Hermes lists should be offered to the phone."""
    allowed = set(allowed)
    return name == DEFAULT or not allowed or name in allowed


# Where Hermes writes chat images: attachments (images/), browser screenshots, generated media (cache/).
MEDIA_DIRS = ("images", "screenshots", "cache")


def media_roots(hermes_home: Path, allowed: Iterable[str] = ()) -> list[str]:
    """Resolved image folders of every profile the phone may use: the default home, plus
    `profiles/<name>` for each named profile that is visible."""
    homes = [Path(hermes_home)]
    profiles_dir = Path(hermes_home) / "profiles"
    if profiles_dir.is_dir():
        homes += [d for d in profiles_dir.iterdir()
                  if d.is_dir() and PROFILE_ID_RE.match(d.name) and is_visible(d.name, allowed)]
    return [os.path.realpath(home / sub) for home in homes for sub in MEDIA_DIRS]
