"""Files a chat names: the ones Hermes handed over (`MEDIA:<path>` in a reply) and the ones the
user sent (`@file:<path>` in a message). The phone may download exactly these, nothing else: a
path is served only when the chat's own stored messages reference it.
"""
from __future__ import annotations

import os
import re
import sqlite3
from pathlib import Path

MEDIA_RE = re.compile(r"^\s*MEDIA:\s*(\S.*?)\s*$", re.MULTILINE)
FILE_RE = re.compile(r"""@file:(?:`([^`]+)`|"([^"]+)"|'([^']+)'|(\S+))""")
# A compressed chat continues under a new id; its earlier files live under the parents.
MAX_LINEAGE = 32


def refs_in(text: str) -> list[str]:
    """Every file path [text] names, as written (MEDIA lines and @file: references)."""
    out = [m.group(1).strip("`\"'") for m in MEDIA_RE.finditer(text)]
    out += [next(g for g in m.groups() if g) for m in FILE_RE.finditer(text)]
    return out


def _lineage(db: sqlite3.Connection, session_id: str) -> tuple[list[str], str | None]:
    """The session and its compression ancestors, and the chat's working folder."""
    ids, cwd, current = [], None, session_id
    while current and current not in ids and len(ids) < MAX_LINEAGE:
        row = db.execute("SELECT parent_session_id, cwd FROM sessions WHERE id = ?", (current,)).fetchone()
        if row is None:
            break
        ids.append(current)
        cwd = cwd or row[1]
        current = row[0]
    return ids, cwd


def referenced_file(home: Path, session_id: str, path: str) -> str | None:
    """Real path of [path] when the chat [session_id] names it, else None."""
    db_path = Path(home) / "state.db"
    if not db_path.is_file():
        return None
    db = sqlite3.connect(f"file:{db_path}?mode=ro", uri=True, timeout=5)
    try:
        ids, cwd = _lineage(db, session_id)
        if not ids:
            return None
        def resolve(ref: str) -> str | None:
            ref = os.path.expanduser(ref)
            if not os.path.isabs(ref):
                if not cwd:
                    return None
                ref = os.path.join(cwd, ref)
            return os.path.realpath(ref)

        wanted = resolve(path)
        if wanted is None:
            return None
        marks = ",".join("?" * len(ids))
        rows = db.execute(
            f"SELECT content FROM messages WHERE session_id IN ({marks}) AND content IS NOT NULL "
            "AND (content LIKE '%MEDIA:%' OR content LIKE '%@file:%')", ids)
        for (content,) in rows:
            if any(resolve(ref) == wanted for ref in refs_in(content)):
                return wanted
        return None
    finally:
        db.close()
