"""Personal notes, like Google Keep, shared by the phone and Hermes.

One Markdown file per note in the notes directory, so Hermes (and you) can read, search and edit
them with plain file tools. A small header of `key: <json value>` lines between `---` fences holds
what the app shows (title, colour, pinned, archived, labels, times); the body is Markdown, and
`- [ ]` / `- [x]` lines are checklist items the app ticks off.

    ---
    title: "Weekend plan"
    color: "yellow"
    pinned: true
    archived: false
    labels: ["plans"]
    created: 1793200000.0
    updated: 1793200500.0
    ---
    - [ ] buy paint
    - [x] call Kevin
"""
from __future__ import annotations

import json
import os
import re
import secrets
import tempfile
import time
from dataclasses import asdict, dataclass, field
from pathlib import Path

COLORS = ("default", "red", "orange", "yellow", "green", "teal", "blue", "purple", "pink", "brown", "gray")
MAX_BODY = 200_000
MAX_TITLE = 300
_ID = re.compile(r"^[a-z0-9-]{4,64}$")
_HEADER_KEYS = ("title", "color", "pinned", "archived", "labels", "created", "updated")


@dataclass
class Note:
    id: str
    title: str = ""
    body: str = ""
    color: str = "default"
    pinned: bool = False
    archived: bool = False
    labels: list[str] = field(default_factory=list)
    created: float = 0.0
    updated: float = 0.0

    def to_dict(self) -> dict:
        return asdict(self)


def valid_id(note_id: str) -> bool:
    return bool(_ID.match(note_id or ""))


def parse(note_id: str, text: str) -> Note:
    n = Note(note_id)
    body = text
    if text.startswith("---\n"):
        end = text.find("\n---", 4)
        if end != -1:
            header, body = text[4:end], text[end + 4:].lstrip("\n")
            for line in header.splitlines():
                key, sep, raw = line.partition(":")
                key = key.strip()
                if not sep or key not in _HEADER_KEYS:
                    continue
                try:
                    val = json.loads(raw.strip())
                except ValueError:
                    val = raw.strip()  # hand-edited plain text
                setattr(n, key, val)
    n.body = body
    return _clean(n)


def render(n: Note) -> str:
    header = "\n".join(f"{k}: {json.dumps(getattr(n, k), ensure_ascii=False)}" for k in _HEADER_KEYS)
    return f"---\n{header}\n---\n{n.body}"


def _clean(n: Note) -> Note:
    n.title = str(n.title or "")[:MAX_TITLE]
    n.body = str(n.body or "")[:MAX_BODY]
    n.color = n.color if n.color in COLORS else "default"
    n.pinned, n.archived = bool(n.pinned), bool(n.archived)
    labels = n.labels if isinstance(n.labels, list) else [n.labels] if n.labels else []
    n.labels = list(dict.fromkeys(str(x).strip()[:40] for x in labels if str(x).strip()))[:20]
    n.created = float(n.created or 0) if isinstance(n.created, (int, float, str)) and str(n.created).replace(".", "", 1).isdigit() else 0.0
    n.updated = float(n.updated or 0) if isinstance(n.updated, (int, float, str)) and str(n.updated).replace(".", "", 1).isdigit() else 0.0
    return n


class NoteStore:
    def __init__(self, root: Path):
        self.root = Path(root).expanduser()

    def _path(self, note_id: str) -> Path:
        if not valid_id(note_id):
            raise KeyError(note_id)
        return self.root / f"{note_id}.md"

    def _write(self, n: Note) -> None:
        self.root.mkdir(parents=True, exist_ok=True)
        os.chmod(self.root, 0o700)
        fd, tmp = tempfile.mkstemp(dir=self.root, prefix=".tmp-", suffix=".md")
        with os.fdopen(fd, "w", encoding="utf-8") as f:
            f.write(render(n))
        os.replace(tmp, self._path(n.id))  # atomic: a reader never sees half a note

    def get(self, note_id: str) -> Note:
        p = self._path(note_id)
        if not p.exists():
            raise KeyError(note_id)
        n = parse(note_id, p.read_text(encoding="utf-8", errors="replace"))
        if not n.updated:  # a file Hermes or you wrote by hand
            n.updated = p.stat().st_mtime
        n.created = n.created or n.updated
        return n

    def all(self) -> list[Note]:
        if not self.root.is_dir():
            return []
        out = []
        for p in self.root.glob("*.md"):
            if valid_id(p.stem):
                try:
                    out.append(self.get(p.stem))
                except (KeyError, OSError):
                    continue
        return out

    def list(self, query: str = "", archived: bool | None = False, label: str | None = None) -> list[Note]:
        q = query.strip().lower()
        notes = [n for n in self.all()
                 if (archived is None or n.archived == archived)
                 and (not label or label in n.labels)
                 and (not q or q in n.title.lower() or q in n.body.lower() or any(q in x.lower() for x in n.labels))]
        notes.sort(key=lambda n: (not n.pinned, -n.updated))
        return notes

    def create(self, **fields) -> Note:
        now = time.time()
        note_id = time.strftime("%Y%m%d-%H%M%S", time.localtime(now)) + "-" + secrets.token_hex(2)
        n = _clean(Note(note_id, **{k: v for k, v in fields.items() if k in _HEADER_KEYS or k == "body"}))
        n.created = n.updated = now
        self._write(n)
        return n

    def update(self, note_id: str, **fields) -> Note:
        n = self.get(note_id)
        for k, v in fields.items():
            if (k in _HEADER_KEYS or k == "body") and k not in ("created", "updated") and v is not None:
                setattr(n, k, v)
        n = _clean(n)
        n.updated = time.time()
        self._write(n)
        return n

    def append(self, note_id: str, text: str) -> Note:
        n = self.get(note_id)
        sep = "" if not n.body or n.body.endswith("\n") else "\n"
        return self.update(note_id, body=n.body + sep + text.rstrip("\n") + "\n")

    def delete(self, note_id: str) -> None:
        p = self._path(note_id)
        if not p.exists():
            raise KeyError(note_id)
        p.unlink()

    def labels(self) -> list[str]:
        return sorted({x for n in self.all() for x in n.labels}, key=str.lower)
