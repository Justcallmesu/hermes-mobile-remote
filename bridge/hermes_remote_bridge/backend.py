"""Find the `hermes serve` process the Hermes desktop app talks to, or start one.

The phone is meant to be another window onto the same Hermes, not a second one. So the bridge
does not run agents itself: it attaches to the backend the desktop already uses (found through
Hermes' own spawn ledger, the same file the desktop's attach ladder reads), and when no desktop
is running it starts `hermes serve` the way the desktop would. A backend started here registers
itself in that ledger, so a desktop opened later attaches to it instead of starting its own.

The backend's session token never leaves this PC: the bridge holds it and the phone only ever
presents its own device token.
"""
from __future__ import annotations

import asyncio
import json
import logging
import os
import re
import secrets
import subprocess
import time
from dataclasses import dataclass
from pathlib import Path

import httpx

log = logging.getLogger(__name__)

_TOKEN_RE = re.compile(r'__HERMES_SESSION_TOKEN__\s*=\s*"([^"]+)"')
_READY_RE = re.compile(r"HERMES_(?:BACKEND|DASHBOARD)_READY port=(\d+)")
ATTACHABLE_PURPOSES = ("serve", "dashboard")
SESSION_HEADER = "X-Hermes-Session-Token"


class BackendUnavailable(Exception):
    pass


@dataclass(frozen=True)
class Backend:
    port: int
    token: str
    started_here: bool = False

    @property
    def http(self) -> str:
        return f"http://127.0.0.1:{self.port}"

    @property
    def ws(self) -> str:
        return f"ws://127.0.0.1:{self.port}/api/ws?token={self.token}"


def ledger_candidates(ledger: Path) -> list[int]:
    """Ports of live-looking serve/dashboard backends for the default profile, newest first."""
    try:
        entries = json.loads(ledger.read_text())
    except (OSError, ValueError):
        return []
    rows = [e for e in entries if isinstance(e, dict) and e.get("purpose") in ATTACHABLE_PURPOSES
            and isinstance(e.get("port"), int) and e.get("host", "127.0.0.1") in ("127.0.0.1", "localhost", "")
            and not e.get("profile")]
    rows.sort(key=lambda e: e.get("registered_at") or 0, reverse=True)
    return [e["port"] for e in rows if _pid_alive(e.get("pid"))]


def _pid_alive(pid) -> bool:
    if not isinstance(pid, int) or pid <= 0:
        return True  # nothing to check; the HTTP probe decides
    if os.name == "nt":
        return _pid_alive_windows(pid)
    try:
        os.kill(pid, 0)
    except ProcessLookupError:
        return False
    except (PermissionError, OSError):
        return True
    return True


def _pid_alive_windows(pid: int) -> bool:
    # os.kill(pid, 0) is no probe on Windows: signal 0 is CTRL_C_EVENT. Ask the kernel instead.
    import ctypes
    from ctypes import wintypes

    kernel32 = ctypes.WinDLL("kernel32", use_last_error=True)
    kernel32.OpenProcess.restype = wintypes.HANDLE
    kernel32.OpenProcess.argtypes = [wintypes.DWORD, wintypes.BOOL, wintypes.DWORD]
    kernel32.GetExitCodeProcess.argtypes = [wintypes.HANDLE, ctypes.POINTER(wintypes.DWORD)]
    kernel32.CloseHandle.argtypes = [wintypes.HANDLE]
    process_query_limited_information, still_active, error_access_denied = 0x1000, 259, 5
    handle = kernel32.OpenProcess(process_query_limited_information, False, pid)
    if not handle:
        return ctypes.get_last_error() == error_access_denied  # exists but not ours
    try:
        code = wintypes.DWORD()
        if not kernel32.GetExitCodeProcess(handle, ctypes.byref(code)):
            return True
        return code.value == still_active
    finally:
        kernel32.CloseHandle(handle)


class BackendLocator:
    """Resolves a usable backend, caching it until it stops answering."""

    def __init__(self, hermes_home: Path, hermes_root: Path, hermes_python: Path, log_file: Path,
                 *, hermes_bin: Path | None = None, spawn: bool = True, http: httpx.AsyncClient | None = None):
        self.ledger = hermes_home / "spawn-ledger.json"
        self.hermes_home = hermes_home
        self.hermes_root = hermes_root
        self.hermes_python = hermes_python
        self.hermes_bin = hermes_bin
        self.log_file = log_file
        self.spawn_allowed = spawn
        self._http = http or httpx.AsyncClient(timeout=httpx.Timeout(4.0, connect=1.5))
        self._current: Backend | None = None
        self._lock = asyncio.Lock()

    async def aclose(self) -> None:
        await self._http.aclose()

    @property
    def current(self) -> Backend | None:
        return self._current

    def forget(self, backend: Backend | None = None) -> None:
        if backend is None or backend == self._current:
            self._current = None

    async def get(self, *, start: bool = True) -> Backend:
        async with self._lock:
            if self._current and await self._alive(self._current):
                return self._current
            self._current = None
            for port in ledger_candidates(self.ledger):
                token = await self._scrape_token(port)
                if token:
                    self._current = Backend(port, token)
                    log.info("attached to Hermes backend on port %d", port)
                    return self._current
            if not (start and self.spawn_allowed):
                raise BackendUnavailable("Hermes is not running on this PC")
            self._current = await self._start()
            return self._current

    async def _alive(self, b: Backend) -> bool:
        try:
            r = await self._http.get(f"{b.http}/api/sessions", params={"limit": 1},
                                     headers={SESSION_HEADER: b.token})
            return r.status_code == 200
        except httpx.HTTPError:
            return False

    async def _scrape_token(self, port: int) -> str | None:
        """The token the backend injects into its own page for the desktop renderer."""
        try:
            r = await self._http.get(f"http://127.0.0.1:{port}/")
        except httpx.HTTPError:
            return None
        m = _TOKEN_RE.search(r.text) if r.status_code == 200 else None
        if not m:
            return None
        token = m.group(1)
        return token if await self._alive(Backend(port, token)) else None

    def serve_argv(self) -> list[str] | None:
        """How to run ``hermes serve``: the old ``hermes-agent/venv`` interpreter, else the
        ``hermes`` launcher (current installers); None when neither exists."""
        tail = ["serve", "--host", "127.0.0.1", "--port", "0"]
        if self.hermes_python.exists():
            return [str(self.hermes_python), "-m", "hermes_cli.main", *tail]
        if self.hermes_bin is not None and self.hermes_bin.exists():
            return [str(self.hermes_bin), *tail]
        return None

    async def _start(self) -> Backend:
        argv = self.serve_argv()
        if argv is None:
            raise BackendUnavailable(f"Hermes is not installed at {self.hermes_root}")
        token = secrets.token_urlsafe(32)
        env = dict(os.environ, HERMES_DASHBOARD_SESSION_TOKEN=token, HERMES_HOME=str(self.hermes_home),
                   PYTHONUTF8="1")
        self.log_file.parent.mkdir(parents=True, exist_ok=True)
        start_offset = self.log_file.stat().st_size if self.log_file.exists() else 0
        out = open(self.log_file, "ab")
        kwargs: dict = {}
        if os.name == "nt":
            kwargs["creationflags"] = subprocess.CREATE_NEW_PROCESS_GROUP | subprocess.CREATE_NO_WINDOW  # type: ignore[attr-defined]
        else:
            kwargs["start_new_session"] = True  # outlives a bridge restart; Hermes' idle exit reaps it
        try:
            proc = subprocess.Popen(
                argv, cwd=str(self.hermes_root if self.hermes_root.is_dir() else self.hermes_home), env=env, stdin=subprocess.DEVNULL, stdout=out, stderr=out, **kwargs)
        finally:
            out.close()
        log.info("started hermes serve (pid %d)", proc.pid)
        deadline = time.monotonic() + 90
        while time.monotonic() < deadline:
            if proc.poll() is not None:
                raise BackendUnavailable(f"hermes serve exited with code {proc.returncode} (see {self.log_file})")
            port = self._ready_port(start_offset)
            if port:
                backend = Backend(port, token, started_here=True)
                if await self._alive(backend):
                    return backend
            await asyncio.sleep(0.4)
        proc.terminate()
        raise BackendUnavailable(f"hermes serve did not start within 90 s (see {self.log_file})")

    def _ready_port(self, offset: int) -> int | None:
        try:
            with open(self.log_file, "rb") as f:
                f.seek(offset)
                m = _READY_RE.search(f.read().decode(errors="replace"))
        except OSError:
            return None
        return int(m.group(1)) if m else None
