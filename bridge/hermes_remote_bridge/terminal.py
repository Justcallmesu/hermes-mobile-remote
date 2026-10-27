"""A shell on the PC for the phone's Terminal page, over `/v1/terminal` (WebSocket).

Frames are JSON text both ways:
  phone -> bridge  {"type": "input", "data": "ls\\r"} | {"type": "resize", "cols": 80, "rows": 24}
                   | {"type": "interrupt"}
  bridge -> phone  {"type": "screen", ...} (see Screen.frame) | {"type": "exit", "code": 0}
                   | {"type": "error", "message": "..."}

On Linux/macOS the shell runs in a real PTY (job control, Ctrl-C, colours). Windows has no PTY in
the standard library, so there the shell runs on pipes: line-based commands work, full-screen
programs do not. One shell per connection; it is killed when the phone disconnects.
"""
from __future__ import annotations

import asyncio
import contextlib
import json
import os
import logging
import shlex
import shutil
import signal
import subprocess
import time

log = logging.getLogger(__name__)
READ_CHUNK = 65536
MAX_INPUT = 65536


def default_shell(cfg=None) -> list[str]:
    """The `terminal_shell` config line if it resolves on PATH, else the platform default."""
    override = getattr(cfg, "terminal_shell", "")
    if override:
        argv = shlex.split(override, posix=os.name != "nt")
        if os.name == "nt":  # non-POSIX split keeps the quotes around a token
            argv = [a[1:-1] if len(a) > 1 and a[0] == a[-1] and a[0] in "\"'" else a for a in argv]
        if argv and shutil.which(argv[0]):
            return argv
        log.warning("config.toml: terminal_shell %r was not found on PATH; using the default shell", override)
    if os.name == "nt":
        ps = shutil.which("pwsh") or shutil.which("powershell")
        return [ps, "-NoLogo"] if ps else [os.environ.get("COMSPEC", "cmd.exe")]
    shell = os.environ.get("SHELL") or shutil.which("bash") or "/bin/sh"
    return [shell, "-l"]


class Shell:
    """The process and its output stream, PTY or pipes."""

    def __init__(self, argv: list[str] | None = None, cwd: str | None = None, cols: int = 80, rows: int = 24,
                 cfg=None) -> None:
        self.argv = argv or default_shell(cfg)
        self.cwd = cwd or os.path.expanduser("~")
        self.cols, self.rows = cols, rows
        self.proc: subprocess.Popen | None = None
        self._fd: int | None = None  # PTY master

    def start(self) -> None:
        env = dict(os.environ, TERM="xterm-256color", COLUMNS=str(self.cols), LINES=str(self.rows))
        env.pop("HERMES_REMOTE_TOKEN", None)
        if os.name == "nt":
            self.proc = subprocess.Popen(self.argv, cwd=self.cwd, env=env, stdin=subprocess.PIPE,
                                         stdout=subprocess.PIPE, stderr=subprocess.STDOUT, bufsize=0,
                                         creationflags=getattr(subprocess, "CREATE_NO_WINDOW", 0))
            return
        import pty

        master, slave = pty.openpty()
        self._fd = master
        self._set_size()
        def controlling_tty() -> None:
            # After setsid (start_new_session), make the PTY this session's controlling terminal;
            # shells with job control (fish, zsh) exit at once without one.
            import fcntl
            import termios

            fcntl.ioctl(0, termios.TIOCSCTTY, 0)

        self.proc = subprocess.Popen(self.argv, cwd=self.cwd, env=env, stdin=slave, stdout=slave, stderr=slave,
                                     start_new_session=True, close_fds=True, preexec_fn=controlling_tty)
        os.close(slave)
        os.set_blocking(master, False)

    def _set_size(self) -> None:
        if self._fd is None:
            return
        import fcntl
        import struct
        import termios

        with contextlib.suppress(OSError):
            fcntl.ioctl(self._fd, termios.TIOCSWINSZ, struct.pack("HHHH", self.rows, self.cols, 0, 0))

    def resize(self, cols: int, rows: int) -> None:
        self.cols, self.rows = max(10, min(cols, 500)), max(4, min(rows, 300))
        self._set_size()

    def write(self, data: str) -> None:
        raw = data.encode("utf-8", "replace")
        if self._fd is not None:
            view = memoryview(raw)
            while view:
                try:
                    n = os.write(self._fd, view)
                    view = view[n:]
                except BlockingIOError:
                    time.sleep(0.01)
        elif self.proc and self.proc.stdin:
            # Pipes: no line discipline, so Enter arrives as \r; the shell wants \n.
            self.proc.stdin.write(raw.replace(b"\r\n", b"\n").replace(b"\r", b"\n"))
            self.proc.stdin.flush()

    async def read(self) -> bytes:
        """Next chunk of output; b"" once the shell is gone."""
        loop = asyncio.get_running_loop()
        if self._fd is not None:
            fd = self._fd
            fut: asyncio.Future = loop.create_future()

            def ready() -> None:
                if not fut.done():
                    fut.set_result(None)

            loop.add_reader(fd, ready)
            try:
                await fut
            finally:
                loop.remove_reader(fd)
            try:
                return os.read(fd, READ_CHUNK)
            except BlockingIOError:
                return b"\0"  # spurious wake-up; the caller skips it
            except OSError:
                return b""  # EIO: the slave side closed
        assert self.proc and self.proc.stdout
        return await loop.run_in_executor(None, self.proc.stdout.read1, READ_CHUNK)  # type: ignore[attr-defined]

    def interrupt(self) -> None:
        if self.proc and os.name != "nt":
            with contextlib.suppress(OSError):
                os.killpg(self.proc.pid, signal.SIGINT)

    def close(self) -> int | None:
        if self.proc is None:
            return None
        if self.proc.poll() is None:
            with contextlib.suppress(OSError):
                if os.name == "nt":
                    subprocess.run(["taskkill", "/T", "/F", "/PID", str(self.proc.pid)], capture_output=True,
                                   creationflags=getattr(subprocess, "CREATE_NO_WINDOW", 0))
                else:
                    os.killpg(self.proc.pid, signal.SIGHUP)
            with contextlib.suppress(subprocess.TimeoutExpired):
                self.proc.wait(timeout=2)
            if self.proc.poll() is None:
                with contextlib.suppress(OSError):
                    self.proc.kill()
        if self._fd is not None:
            with contextlib.suppress(OSError):
                os.close(self._fd)
            self._fd = None
        return self.proc.returncode


FRAME_SECONDS = 0.04
SCROLLBACK = 1000


class Screen:
    """The terminal emulator. It lives here, not on the phone: shells like fish wait for the
    terminal to answer queries (device attributes, cursor position) and pyte answers them, and
    the phone only has to draw finished rows of styled text."""

    def __init__(self, cols: int, rows: int, reply, pipes: bool = False) -> None:
        import pyte

        scrolled: list = []

        class Capturing(pyte.Screen):
            def index(self) -> None:
                # A line about to leave the top of the full-screen scroll region: keep a copy.
                top, bottom = self.margins or pyte.screens.Margins(0, self.lines - 1)
                if self.cursor.y == bottom and top == 0 and len(scrolled) < SCROLLBACK:
                    scrolled.append(dict(self.buffer[top]))
                super().index()

        self.scrolled = scrolled
        self.screen = Capturing(cols, rows)
        self.screen.write_process_input = reply
        if pipes:
            self.screen.set_mode(pyte.modes.LNM)  # bare \n from a pipe also returns the carriage
        self.stream = pyte.ByteStream(self.screen)
        self.default = self.screen.default_char
        self.full = True

    def feed(self, data: bytes) -> None:
        self.stream.feed(data)

    def resize(self, cols: int, rows: int) -> None:
        self.screen.resize(rows, cols)
        self.full = True

    def _runs(self, line, cols: int) -> list:
        runs: list = []
        for x in range(cols):
            ch = line.get(x, self.default)
            flags = (1 if ch.bold else 0) | (2 if ch.reverse else 0) | (4 if ch.underscore else 0) | (8 if ch.italics else 0)
            style = (ch.fg, ch.bg, flags)
            if runs and tuple(runs[-1][1:]) == style:
                runs[-1][0] += ch.data
            else:
                runs.append([ch.data, *style])
        # Trailing blanks in the default style carry nothing.
        if runs and runs[-1][1] == "default" and runs[-1][2] == "default" and not runs[-1][3] & 2:
            runs[-1][0] = runs[-1][0].rstrip()
            if not runs[-1][0]:
                runs.pop()
        return runs

    def frame(self) -> dict | None:
        """Changed rows since the last frame (all rows after a resize), plus rows that scrolled
        off the top since then, so the phone can keep its own scrollback."""
        s = self.screen
        dirty = set(range(s.lines)) if self.full else set(s.dirty)
        scrolled = self.scrolled[:]
        self.scrolled.clear()
        if not dirty and not scrolled and not self.full:
            return None
        out = {"type": "screen", "cols": s.columns, "rows": s.lines, "full": self.full,
               "cursor": [s.cursor.x, s.cursor.y, not s.cursor.hidden],
               "lines": {str(y): self._runs(s.buffer[y], s.columns) for y in sorted(dirty) if y < s.lines},
               "title": getattr(s, "title", "") or ""}
        if scrolled:
            out["scrolled"] = [self._runs(line, s.columns) for line in scrolled]
        s.dirty.clear()
        self.full = False
        return out


async def serve(ws, shell: Shell, audit=None) -> None:
    """Pump one WebSocket <-> one shell until either side ends."""
    try:
        shell.start()
    except OSError as exc:
        await ws.send_text(json.dumps({"type": "error", "message": f"Could not start {shell.argv[0]}: {exc}"}))
        await ws.close(code=1011)
        return
    if audit:
        audit({"event": "terminal_open", "shell": shell.argv[0]})
    screen = Screen(shell.cols, shell.rows, reply=lambda d: shell.write(d), pipes=shell._fd is None)
    changed = asyncio.Event()

    async def pump_out() -> None:
        while True:
            chunk = await shell.read()
            if chunk == b"\0":
                continue
            if not chunk:
                break
            screen.feed(chunk)
            changed.set()

    async def pump_frames() -> None:
        # At most one frame per FRAME_SECONDS: a `cat` of a big file is many reads but one redraw.
        while True:
            await changed.wait()
            changed.clear()
            frame = screen.frame()
            if frame:
                await ws.send_text(json.dumps(frame, separators=(",", ":")))
            await asyncio.sleep(FRAME_SECONDS)

    async def pump_in() -> None:
        while True:
            raw = await ws.receive_text()
            if len(raw) > MAX_INPUT:
                continue
            try:
                msg = json.loads(raw)
            except ValueError:
                continue
            kind = msg.get("type")
            if kind == "input" and isinstance(msg.get("data"), str):
                if os.name == "nt" and msg["data"] == "\x03":
                    continue  # no PTY to deliver Ctrl-C to on Windows
                shell.write(msg["data"])
            elif kind == "resize":
                with contextlib.suppress(TypeError, ValueError):
                    shell.resize(int(msg.get("cols", 80)), int(msg.get("rows", 24)))
                    screen.resize(shell.cols, shell.rows)
                    changed.set()
            elif kind == "interrupt":
                shell.interrupt()

    tasks = [asyncio.create_task(pump_out()), asyncio.create_task(pump_in()), asyncio.create_task(pump_frames())]
    try:
        done, _ = await asyncio.wait(tasks[:2], return_when=asyncio.FIRST_COMPLETED)
        for t in done:
            with contextlib.suppress(Exception):
                t.result()
        # The shell ended: let the last frame (its final output) reach the phone.
        frame = screen.frame()
        if frame:
            with contextlib.suppress(Exception):
                await ws.send_text(json.dumps(frame, separators=(",", ":")))
    finally:
        for t in tasks:
            t.cancel()
        code = await asyncio.get_running_loop().run_in_executor(None, shell.close)
        with contextlib.suppress(Exception):
            await ws.send_text(json.dumps({"type": "exit", "code": code}))
            await ws.close()
        if audit:
            audit({"event": "terminal_close", "code": code})
