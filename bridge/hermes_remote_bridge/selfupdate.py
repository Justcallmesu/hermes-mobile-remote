"""Update a source-installed bridge (a git checkout set up by install.sh) to the newest release.

`check()` is read-only (it runs inside the sandboxed service, where the checkout is read-only):
the release tags on the remote against the tag this checkout is on. `launch()` starts the update
as its own transient systemd unit, outside the bridge's cgroup, because the update ends by
restarting the bridge and a restart kills everything in the service's cgroup. `apply()` is that
unit: fetch, fast-forward to the release, `uv sync`, re-render the unit, move any Hermes still
inside the bridge's cgroup into a scope of its own, restart the bridge. Pairings, TLS keys and
trusted networks live in the config directory, which nothing here touches, so phones reconnect
on their own with the same pairing.
"""
from __future__ import annotations

import json
import os
import re
import secrets
import shutil
import subprocess
import sys
import time
from pathlib import Path

REPO = Path(__file__).resolve().parents[2]
BRIDGE_DIR = REPO / "bridge"
UNIT = "hermes-remote-bridge.service"
_TAG = re.compile(r"^v(\d+)\.(\d+)\.(\d+)$")
CHECK_TTL = 600.0


def version_key(tag: str) -> tuple[int, int, int] | None:
    m = _TAG.match(tag.strip())
    return (int(m[1]), int(m[2]), int(m[3])) if m else None


def latest_tag(tags: list[str]) -> str | None:
    known = [t for t in tags if version_key(t)]
    return max(known, key=version_key) if known else None  # type: ignore[arg-type]


def _git(*args: str, timeout: float = 30) -> subprocess.CompletedProcess:
    return subprocess.run(["git", "--no-optional-locks", "-C", str(REPO), *args], capture_output=True, text=True,
                          timeout=timeout, stdin=subprocess.DEVNULL)


def _remote_tags(remote: str) -> list[str]:
    r = _git("ls-remote", "--tags", "--refs", remote, "v*.*.*", timeout=20)
    if r.returncode != 0:
        raise RuntimeError((r.stderr or "git ls-remote failed").strip().splitlines()[-1])
    return [line.split("refs/tags/", 1)[1] for line in r.stdout.splitlines() if "refs/tags/" in line]


def why_not(remote: str = "origin") -> str | None:
    """Why this install can't update itself, or None when it can."""
    if os.name == "nt":
        return "Windows: update with the Hermes Remote desktop app."
    if not (REPO / ".git").exists():
        return "This bridge was installed by the Hermes Remote desktop app; update the app instead."
    if not shutil.which("git"):
        return "git is not installed."
    if not shutil.which("systemd-run"):
        return "systemd is required to update in the background."
    if _git("remote", "get-url", remote).returncode != 0:
        return f"The checkout has no '{remote}' remote."
    dirty = _git("status", "--porcelain", "--untracked-files=no")
    if dirty.returncode == 0 and dirty.stdout.strip():
        return "The checkout has local changes; commit or stash them, then run install.sh."
    return None


def check(remote: str = "origin") -> dict:
    """Current and newest release, read-only. Network: one `git ls-remote`."""
    out: dict = {"current": None, "commit": None, "latest": None, "available": False, "can_update": False,
                 "reason": None, "status": read_status()}
    reason = why_not(remote)
    if (REPO / ".git").exists():
        cur = _git("describe", "--tags", "--abbrev=0", "--match", "v*.*.*")
        out["current"] = cur.stdout.strip() or None
        out["commit"] = _git("rev-parse", "--short", "HEAD").stdout.strip() or None
    if reason and not (REPO / ".git").exists():
        out["reason"] = reason
        return out
    try:
        out["latest"] = latest_tag(_remote_tags(remote))
    except (RuntimeError, OSError, subprocess.TimeoutExpired) as exc:
        out["reason"] = f"Can't reach the update server: {exc}"
        return out
    cur_key = version_key(out["current"] or "")
    new_key = version_key(out["latest"] or "")
    out["available"] = bool(new_key and (cur_key is None or new_key > cur_key))
    out["reason"] = reason
    out["can_update"] = reason is None and out["available"]
    return out


# ------------------------------------------------------------------ status file

def _state_dir() -> Path:
    from .config import STATE_DIR
    return STATE_DIR


def status_file() -> Path:
    return _state_dir() / "update.json"


def log_file() -> Path:
    return _state_dir() / "update.log"


def read_status() -> dict | None:
    try:
        st = json.loads(status_file().read_text())
    except (OSError, ValueError):
        return None
    # The update unit died without writing an outcome (killed, PC rebooted): don't spin forever.
    if st.get("state") == "running" and time.time() - st.get("at", 0) > 20:
        unit = st.get("unit")
        gone = unit and subprocess.run(["systemctl", "--user", "is-active", "--quiet", unit], capture_output=True,
                                       timeout=10).returncode != 0
        if gone or time.time() - st.get("at", 0) > 900:
            st = {**st, "state": "failed", "error": "The update stopped before finishing; the bridge kept running."}
    return st


def _write_status(**fields) -> None:
    status_file().parent.mkdir(parents=True, exist_ok=True)
    unit = os.environ.get("HERMES_REMOTE_UPDATE_UNIT") or fields.pop("unit", None)
    status_file().write_text(json.dumps({**fields, **({"unit": unit} if unit else {}), "at": time.time()}))


# ------------------------------------------------------------------ launch

def _uv() -> str | None:
    for c in (shutil.which("uv"), str(Path.home() / ".local/bin/uv"), str(Path.home() / ".cargo/bin/uv")):
        if c and Path(c).exists():
            return c
    return None


def launch_argv(python: str = sys.executable) -> list[str]:
    """The transient unit that runs [apply]: its own cgroup, so the bridge restart can't end it."""
    path = os.pathsep.join(dict.fromkeys([str(Path.home() / ".local/bin"), str(Path.home() / ".cargo/bin"),
                                          *os.environ.get("PATH", "/usr/bin:/bin").split(os.pathsep)]))
    unit = f"hermes-remote-update-{secrets.token_hex(3)}"
    return ["systemd-run", "--user", "--quiet", "--collect", f"--unit={unit}",
            "--description=Hermes Remote bridge update", f"--working-directory={REPO}", "-E", f"PATH={path}",
            "-E", f"HERMES_REMOTE_UPDATE_UNIT={unit}.service",
            python, "-m", "hermes_remote_bridge.cli", "update", "--apply"]


def launch() -> None:
    reason = why_not()
    if reason:
        raise RuntimeError(reason)
    st = read_status()
    if st and st.get("state") == "running" and time.time() - st.get("at", 0) < 600:
        raise RuntimeError("An update is already running.")
    argv = launch_argv()
    unit = next(a.split("=", 1)[1] for a in argv if a.startswith("--unit=")) + ".service"
    _write_status(state="running", step="starting", unit=unit)
    r = subprocess.run(argv, capture_output=True, text=True, timeout=20, stdin=subprocess.DEVNULL)
    if r.returncode != 0:
        _write_status(state="failed", error=(r.stderr or "systemd-run failed").strip()[-300:])
        raise RuntimeError((r.stderr or "systemd-run failed").strip())


# ------------------------------------------------------------------ apply (runs outside the bridge)

def _systemctl(*args: str) -> subprocess.CompletedProcess:
    return subprocess.run(["systemctl", "--user", *args], capture_output=True, text=True, timeout=60, stdin=subprocess.DEVNULL)


def bridge_cgroup_pids() -> tuple[list[int], int]:
    """Processes in the bridge service's cgroup other than the bridge itself, and its main pid."""
    cg = _systemctl("show", "-p", "ControlGroup", "--value", UNIT).stdout.strip()
    main = int(_systemctl("show", "-p", "MainPID", "--value", UNIT).stdout.strip() or 0)
    if not cg:
        return [], main
    try:
        pids = [int(p) for p in Path("/sys/fs/cgroup" + cg, "cgroup.procs").read_text().split()]
    except (OSError, ValueError):
        return [], main
    return [p for p in pids if p != main and p != os.getpid()], main


def _scope(pids: list[int], name: str) -> bool:
    args = ["busctl", "--user", "call", "org.freedesktop.systemd1", "/org/freedesktop/systemd1",
            "org.freedesktop.systemd1.Manager", "StartTransientUnit", "ssa(sv)a(sa(sv))", name, "fail", "2",
            "PIDs", "au", str(len(pids)), *map(str, pids), "Description", "s", "Hermes (kept running across a bridge restart)", "0"]
    return subprocess.run(args, capture_output=True, timeout=20, stdin=subprocess.DEVNULL).returncode == 0


def keep_hermes_alive() -> int:
    """Move what runs inside the bridge's cgroup (a Hermes an older bridge started, and its tools) into
    scopes of their own, so restarting the bridge leaves them running. Returns how many moved."""
    pids, _ = bridge_cgroup_pids()
    if not pids:
        return 0
    if _scope(pids, f"hermes-remote-serve-{secrets.token_hex(4)}.scope"):
        return len(pids)
    moved = 0  # one pid gone meanwhile fails the whole call: move them one by one
    for p in pids:
        if Path(f"/proc/{p}").exists() and _scope([p], f"hermes-remote-serve-{secrets.token_hex(4)}.scope"):
            moved += 1
    return moved


def _render_unit() -> None:
    """Refresh the installed unit from the checkout's template (as install.sh does), when it changed."""
    unit = Path(os.environ.get("XDG_CONFIG_HOME", Path.home() / ".config")) / "systemd/user" / UNIT
    template = BRIDGE_DIR / "systemd" / UNIT
    if not unit.exists() or unit.is_symlink() or not template.exists():
        return
    text = template.read_text().replace("@BIN@", str(BRIDGE_DIR / ".venv/bin/hermes-remote-bridge")).replace("@REPO@", str(REPO))
    if unit.read_text() != text:
        unit.write_text(text)
        _systemctl("daemon-reload")


def _restarts() -> int:
    try:
        return int(_systemctl("show", "-p", "NRestarts", "--value", UNIT).stdout.strip() or 0)
    except ValueError:
        return 0


def _health_url() -> str | None:
    """The bridge's loopback /healthz, when it listens on loopback (the default)."""
    try:
        from .config import Config
        cfg = Config.load()
    except Exception:  # noqa: BLE001 - a broken config is exactly what the health check must catch
        return None
    host = next((h for h in cfg.listen if h in ("127.0.0.1", "::1", "localhost")), None)
    if not host:
        return None
    return f"http://{'[::1]' if host == '::1' else host}:{cfg.port}/healthz"


def bridge_healthy(timeout: float = 45.0, settle: float = 6.0) -> bool:
    """True once the restarted bridge answers /healthz (or, without loopback, stays up without
    restarting for [settle] s). A bridge that crash-loops on the new code is unhealthy."""
    import urllib.request

    url = _health_url()
    start_restarts = _restarts()
    deadline = time.monotonic() + timeout
    up_since = None
    while time.monotonic() < deadline:
        active = _systemctl("is-active", "--quiet", UNIT).returncode == 0
        if active and url:
            try:
                with urllib.request.urlopen(url, timeout=3) as r:
                    if r.status == 200 and _restarts() == start_restarts:
                        return True
            except OSError:
                pass
        elif active:
            up_since = up_since or time.monotonic()
            if time.monotonic() - up_since >= settle and _restarts() == start_restarts:
                return True
        else:
            up_since = None
        time.sleep(1.5)
    return False


def _ensure_running(log) -> None:
    """Whatever happened, leave the bridge running: phones can only reconnect to a running bridge."""
    if _systemctl("is-active", "--quiet", UNIT).returncode != 0:
        log("The bridge was not running; starting it.")
        _systemctl("reset-failed", UNIT)
        _systemctl("start", UNIT)


def _sync(uv: str) -> subprocess.CompletedProcess:
    return subprocess.run([uv, "sync", "--quiet", "--no-dev", "--inexact"], cwd=BRIDGE_DIR, capture_output=True, text=True,
                          timeout=600, stdin=subprocess.DEVNULL)


def _rollback(old: str, uv: str | None, log) -> None:
    log("Rolling back to the previous version…")
    _git("reset", "--keep", old)
    if uv:
        _sync(uv)
    _render_unit()


def apply(remote: str = "origin", log=print) -> str:
    """Fetch, move to the newest release, sync the venv, restart the bridge and check it answers.

    Every path ends with a running bridge: a failure before the restart changes nothing (or is
    rolled back), and a new version that doesn't come up healthy is rolled back and restarted.
    """
    reason = why_not(remote)
    if reason:
        raise RuntimeError(reason)
    old = _git("rev-parse", "HEAD").stdout.strip()
    old_tag = _git("describe", "--tags", "--abbrev=0", "--match", "v*.*.*").stdout.strip() or old[:7]
    uv = _uv()
    try:
        log(f"Fetching {remote}…")
        _write_status(state="running", step="fetching")
        f = _git("fetch", "--tags", "--force", remote, timeout=180)
        if f.returncode != 0:
            raise RuntimeError(f"git fetch failed: {f.stderr.strip()}")
        target = latest_tag(_git("tag", "--list", "v*.*.*").stdout.split())
        if not target:
            raise RuntimeError("No release tags found.")
        if not uv:
            raise RuntimeError("uv not found; nothing changed.")
        branch = _git("rev-parse", "--abbrev-ref", "HEAD").stdout.strip()
        log(f"Moving to {target}…")
        _write_status(state="running", step="checkout", to=target)
        step = _git("checkout", "--quiet", target) if branch == "HEAD" else _git("merge", "--ff-only", "--quiet", target)
        if step.returncode != 0:
            raise RuntimeError(f"Could not move to {target}: {step.stderr.strip() or step.stdout.strip()}")
        log("Installing dependencies (uv sync)…")
        _write_status(state="running", step="sync", to=target)
        s = _sync(uv)
        if s.returncode != 0:
            _rollback(old, uv, log)
            raise RuntimeError(f"uv sync failed, rolled back: {s.stderr.strip()[-300:]}")
        _render_unit()
        moved = keep_hermes_alive()
        if moved:
            log(f"Kept {moved} Hermes process(es) running outside the bridge.")
        log("Restarting the bridge…")
        _write_status(state="running", step="restart", to=target)
        _systemctl("reset-failed", UNIT)
        _systemctl("restart", UNIT)
        log("Checking the new bridge answers…")
        _write_status(state="running", step="verify", to=target)
        if not bridge_healthy():
            _rollback(old, uv, log)
            keep_hermes_alive()
            _systemctl("reset-failed", UNIT)
            _systemctl("restart", UNIT)
            ok = bridge_healthy()
            raise RuntimeError(f"{target} did not start; rolled back to {old_tag}" + ("" if ok else " (check `journalctl --user -u hermes-remote-bridge`)"))
        _write_status(state="done", to=target, moved=moved)
        log(f"Updated to {target}. Paired devices are unchanged; phones reconnect on their own.")
        return target
    finally:
        _ensure_running(log)


def run_apply() -> int:
    """`hermes-remote-bridge update --apply`: the transient unit's entry point; logs to update.log."""
    log_file().parent.mkdir(parents=True, exist_ok=True)
    with open(log_file(), "a") as fh:
        def log(msg: str) -> None:
            fh.write(time.strftime("%Y-%m-%d %H:%M:%S ") + msg + "\n"); fh.flush()
        try:
            apply(log=log)
            return 0
        except Exception as exc:  # noqa: BLE001 - every failure must land in the status file
            log(f"Update failed: {exc}")
            _write_status(state="failed", error=str(exc)[:500])
            return 1


class CachedCheck:
    """`check()` is a network call: the phone's Settings page reuses one for [CHECK_TTL] seconds."""

    def __init__(self, ttl: float = CHECK_TTL, fn=check):
        self.ttl, self.fn, self._at, self._value = ttl, fn, 0.0, None

    def get(self, force: bool = False) -> dict:
        if force or self._value is None or time.monotonic() - self._at > self.ttl:
            self._value, self._at = self.fn(), time.monotonic()
        return {**self._value, "status": read_status()}
