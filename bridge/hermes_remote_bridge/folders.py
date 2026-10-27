"""Folder browsing for the phone's project folder picker.

Lists sub-folders (never files) of a PC path so the user can pick a project folder instead of
typing a path from memory, and makes a new folder there. The paired owner already has a full
shell (terminal.py), so this adds no new reach; it only lists names.
"""
from __future__ import annotations

import os

MAX_DIRS = 1000


class FolderError(Exception):
    def __init__(self, status: int, code: str, message: str) -> None:
        super().__init__(message)
        self.status, self.code, self.message = status, code, message


def _resolve(path: str | None) -> str:
    raw = (path or "").strip()
    if "\x00" in raw:
        raise FolderError(400, "invalid_path", "Invalid path")
    return os.path.abspath(os.path.expanduser(raw or "~"))


def _parent(path: str) -> str | None:
    parent = os.path.dirname(path)
    return None if parent == path else parent


def list_dirs(path: str | None, hidden: bool = False) -> dict:
    """Sub-folders of [path] (home when empty), sorted by name; `git` marks repositories."""
    target = _resolve(path)
    if not os.path.isdir(target):
        raise FolderError(404, "no_such_folder", f"No folder {target}")
    dirs = []
    try:
        with os.scandir(target) as it:
            for entry in it:
                if not hidden and entry.name.startswith("."):
                    continue
                try:
                    if not entry.is_dir(follow_symlinks=True):
                        continue
                except OSError:
                    continue
                dirs.append(entry)
    except PermissionError:
        raise FolderError(403, "folder_forbidden", f"Not allowed to read {target}") from None
    dirs.sort(key=lambda e: e.name.lower())
    out = [{"name": e.name, "path": e.path, "git": os.path.exists(os.path.join(e.path, ".git"))}
           for e in dirs[:MAX_DIRS]]
    return {"path": target, "parent": _parent(target), "home": os.path.expanduser("~"),
            "git": os.path.exists(os.path.join(target, ".git")), "dirs": out, "truncated": len(dirs) > MAX_DIRS}


def make_dir(path: str) -> dict:
    """Create one folder (its parent must exist) and return its listing."""
    target = _resolve(path)
    if not (path or "").strip():
        raise FolderError(400, "invalid_path", "Give the new folder a path")
    parent = _parent(target)
    if parent is None or not os.path.isdir(parent):
        raise FolderError(404, "no_such_folder", f"No folder {parent}")
    try:
        os.mkdir(target)
    except FileExistsError:
        raise FolderError(409, "exists", f"{target} already exists") from None
    except PermissionError:
        raise FolderError(403, "folder_forbidden", f"Not allowed to create {target}") from None
    return list_dirs(target)
