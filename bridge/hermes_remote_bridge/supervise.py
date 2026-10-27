"""The supervisor: run ``serve`` as a child and keep it running, the same on every OS.

Policy (formerly bridge/windows/supervise.ps1):
  exit 75  the addresses to serve changed: restart after 2 s, forget earlier quick failures
  exit 76  port conflict: another bridge owns the port, stop (retrying would only fill the log)
  other    a run shorter than 20 s is a quick failure; wait max(3, min(60, 5 * failures)) s
"""
from __future__ import annotations

import os
import subprocess
import sys
import threading
import time
from datetime import datetime
from pathlib import Path
from typing import Callable

EXIT_NETWORK_CHANGED = 75
EXIT_PORT_CONFLICT = 76
QUICK_RUN_SECONDS = 20
MAX_QUICK_FAILURES = 12
LOG_MAX_BYTES = 2 * 1024 * 1024


class Supervisor:
    """``launcher()`` returns a child with ``stdout`` (iterable of text lines), ``wait() -> int``
    and ``terminate()``. Clock and sleep are injected so tests need no real time."""

    def __init__(self, launcher: Callable[[], object], log_path: Path,
                 clock: Callable[[], float] = time.monotonic,
                 sleep: Callable[[float], None] | None = None):
        self._launcher = launcher
        self._log = Path(log_path)
        self._clock = clock
        self._stop = threading.Event()
        self._sleep = sleep or self._stop.wait
        self._child = None

    def stop(self) -> None:
        """Stop the child and make ``run`` return 0. Safe from a signal handler or another thread."""
        self._stop.set()
        child = self._child
        if child is not None:
            child.terminate()

    def _write(self, text: str) -> None:
        self._log.parent.mkdir(parents=True, exist_ok=True)
        if self._log.exists() and self._log.stat().st_size > LOG_MAX_BYTES:
            os.replace(self._log, self._log.with_name(self._log.name + ".1"))
        with open(self._log, "a", encoding="utf-8") as fh:
            fh.write(text + "\n")

    def _say(self, text: str) -> None:
        self._write(f"{datetime.now().isoformat(timespec='seconds')} supervisor: {text}")

    def run(self) -> int:
        quick_failures = 0
        while not self._stop.is_set():
            started = self._clock()
            self._child = child = self._launcher()
            if self._stop.is_set():  # stop() raced the launch
                child.terminate()
            for line in child.stdout:
                self._write(line.rstrip("\r\n"))
            code = child.wait()
            self._child = None
            ran_for = self._clock() - started
            if self._stop.is_set():
                self._say("stopped")
                return 0
            if code == EXIT_NETWORK_CHANGED:
                quick_failures = 0
                self._say("network changed; restarting in 2s")
                self._sleep(2)
                continue
            if code == EXIT_PORT_CONFLICT:
                self._say("port conflict: another bridge is using the port; not restarting")
                return EXIT_PORT_CONFLICT
            quick_failures = min(quick_failures + 1, MAX_QUICK_FAILURES) if ran_for < QUICK_RUN_SECONDS else 0
            wait = max(3, min(60, 5 * quick_failures))
            self._say(f"bridge exited with code {code} after {int(ran_for)}s; restarting in {wait}s")
            self._sleep(wait)
        return 0


def popen_launcher(config: Path | None) -> Callable[[], subprocess.Popen]:
    """Launch ``serve`` in a child process whose stdout and stderr arrive as UTF-8 lines."""
    cmd = [sys.executable, "-m", "hermes_remote_bridge.cli"]
    if config:
        cmd += ["--config", str(config)]
    cmd.append("serve")
    env = {**os.environ, "PYTHONIOENCODING": "utf-8", "PYTHONUNBUFFERED": "1"}

    def launch() -> subprocess.Popen:
        return subprocess.Popen(cmd, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True,
                                encoding="utf-8", errors="replace", env=env)
    return launch
