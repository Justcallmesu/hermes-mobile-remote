"""A project's IDEA.md: the description file the desktop's "New project" writes into the project's folder.

The phone names a project by id only; the folder comes from the profile's own `projects.db`, so the
only file this module ever reads or writes is `<that project's primary folder>/IDEA.md`.
"""
from __future__ import annotations

import os
import sqlite3
from pathlib import Path

IDEA_FILE = "IDEA.md"
IDEA_MAX_BYTES = 256 * 1024


class NoFolder(LookupError):
    """The project is unknown, or has no folder (yet) to keep IDEA.md in."""


def project_folder(home: Path, project_id: str) -> str:
    """The primary folder of [project_id] in `home/projects.db` (raises NoFolder)."""
    db_path = Path(home) / "projects.db"
    if not db_path.is_file():
        raise NoFolder("No projects on this PC")
    conn = sqlite3.connect(f"file:{db_path}?mode=ro", uri=True, timeout=5)
    try:
        row = conn.execute("SELECT primary_path FROM projects WHERE id = ?", (project_id,)).fetchone()
    finally:
        conn.close()
    if row is None:
        raise NoFolder("Project not found")
    folder = (row[0] or "").strip()
    if not folder:
        raise NoFolder("This project has no folder yet: set one first")
    if not os.path.isdir(folder):
        raise NoFolder("The project's folder doesn't exist on the PC")
    return folder


def read_idea(home: Path, project_id: str) -> dict:
    path = os.path.join(project_folder(home, project_id), IDEA_FILE)
    text = ""
    if os.path.isfile(path):
        with open(path, "rb") as f:
            text = f.read(IDEA_MAX_BYTES).decode("utf-8", "replace")
    return {"path": path, "exists": os.path.isfile(path), "text": text}


def write_idea(home: Path, project_id: str, text: str) -> dict:
    path = os.path.join(project_folder(home, project_id), IDEA_FILE)
    body = text.strip()
    if body:
        tmp = path + ".tmp"
        # newline="": write the phone's text as-is; Windows would otherwise turn every \n into \r\n.
        with open(tmp, "w", encoding="utf-8", newline="") as f:
            f.write(body + "\n")
        os.replace(tmp, path)
    elif os.path.isfile(path):
        os.remove(path)  # emptied on the phone: same as no description
    return {"path": path, "exists": bool(body), "text": body + "\n" if body else ""}
