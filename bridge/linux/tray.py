#!/usr/bin/env python3
"""Hermes Mobile Remote - Linux tray app.

The Linux tray from before the desktop app (kept until D23's follow-up). Its menu: status, the doctor
checks, bridge stats, start/stop/restart, network trust, devices (pair / revoke), logs, firewall, start at
login, quit.

One difference is deliberate. On Linux the bridge is a systemd *user service* (install.sh), so
this tray controls that service with `systemctl --user` and does NOT supervise the bridge
itself. Quitting the tray leaves the bridge running; "Stop bridge" stops it.

As on Windows, every structured answer comes from the bridge CLI's --json output (doctor,
devices, trust). This file never parses English and never reads the bridge's state files.

Runs with the system python3 and PyQt6 (QSystemTrayIcon works on KDE, GNOME with the
AppIndicator extension, XFCE, and others), not with the bridge's virtualenv:
    python3 bridge/linux/tray.py
"""
from __future__ import annotations

import json
import os
import shutil
import subprocess
import sys
import tempfile
from pathlib import Path

try:
    from PyQt6.QtCore import QObject, QPointF, QRectF, Qt, QThread, QTimer, pyqtSignal
    from PyQt6.QtGui import QAction, QBrush, QColor, QFont, QGuiApplication, QIcon, QPainter, QPixmap
    from PyQt6.QtWidgets import (QApplication, QDialog, QInputDialog, QLabel, QMenu, QMessageBox,
                                 QPushButton, QSystemTrayIcon, QVBoxLayout)
except ImportError:  # pragma: no cover - only reached on a machine without PyQt6
    sys.stderr.write("The tray needs PyQt6. Install it with your package manager, e.g.\n"
                     "  Arch/CachyOS: sudo pacman -S python-pyqt6\n"
                     "  Debian/Ubuntu: sudo apt install python3-pyqt6\n"
                     "  Fedora: sudo dnf install python3-pyqt6\n")
    sys.exit(1)

REPO = Path(__file__).resolve().parents[2]
BIN = REPO / "bridge" / ".venv" / "bin" / "hermes-remote-bridge"
UNIT = "hermes-remote-bridge.service"
CONFIG_HOME = Path(os.environ.get("XDG_CONFIG_HOME", Path.home() / ".config"))
STATE_DIR = Path(os.environ.get("XDG_STATE_HOME", Path.home() / ".local" / "state")) / "hermes-remote"
AUTOSTART = CONFIG_HOME / "autostart" / "hermes-remote-tray.desktop"
POLL_MS = 15_000
# Runs in the bridge's virtualenv (segno lives there): pairing.json -> the app's QR (same URI as `pair`).
QR_SCRIPT = (
    "import json, os, sys, urllib.parse, segno\n"
    "p = json.load(open(sys.argv[1]))\n"
    "fd = os.open(sys.argv[2], os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)\n"
    "with os.fdopen(fd, 'wb') as fh:\n"
    "    segno.make('hermesremote://pair?' + urllib.parse.urlencode(p), error='m')"
    ".save(fh, kind='png', scale=8, border=3, dark='#000', light='#fff')\n"
)

ACCENT = QColor("#E8B54A")
STATE_COLOURS = {"ok": QColor("#5BC27A"), "warn": QColor("#E0A53A"), "fail": QColor("#E5675C"),
                 "stopped": QColor("#8A8A8A"), "unknown": QColor("#8A8A8A")}


# ---------------------------------------------------------------- bridge and systemd helpers
def run(args: list[str], timeout: int = 45) -> str:
    """Run a command and return its stdout, or '' when it could not be run."""
    try:
        return subprocess.run(args, capture_output=True, text=True, timeout=timeout).stdout
    except (OSError, subprocess.TimeoutExpired):
        return ""


def bridge(*args: str, timeout: int = 45) -> str:
    return run([str(BIN), *args], timeout)


def bridge_json(*args: str) -> dict | None:
    out = bridge(*args)
    try:
        return json.loads(out) if out else None
    except json.JSONDecodeError:
        return None


def service_state() -> str:
    """'running', 'stopped' or 'missing' (the unit was never installed)."""
    out = run(["systemctl", "--user", "is-active", UNIT], 10).strip()
    if out == "active":
        return "running"
    if run(["systemctl", "--user", "cat", UNIT], 10).strip():
        return "stopped"
    return "missing"


def systemctl(verb: str) -> None:
    run(["systemctl", "--user", verb, UNIT], 30)


def poll() -> dict:
    """Everything the menu shows, gathered off the UI thread."""
    state = service_state()
    doctor = bridge_json("doctor", "--json")
    checks = (doctor or {}).get("checks", [])
    levels = {c.get("level") for c in checks}
    if state != "running":
        summary = "stopped" if state == "stopped" else "unknown"
    elif "fail" in levels or "error" in levels:
        summary = "fail"
    elif "warn" in levels:
        summary = "warn"
    elif checks:
        summary = "ok"
    else:
        summary = "unknown"
    problem = next((c["message"] for c in checks if c.get("level") not in ("ok", None)), "all fine" if checks else "checking...")
    return {"state": state, "summary": summary, "problem": problem, "checks": checks,
            "trust": bridge_json("trust", "--list", "--json"), "devices": bridge_json("devices", "--json"),
            "activity": activity_line(bridge_json("metrics", "--json")) if state == "running" else None}


def _total(metrics: dict, name: str) -> float:
    return sum(s.get("value", 0) for s in metrics.get(name, {}).get("samples", []))


def activity_line(metrics: dict | None) -> str | None:
    """One menu line from `metrics --json`: who is on, and anything waiting. None without metrics."""
    if not metrics:
        return None
    parts = [f"{int(_total(metrics, 'bridge_live_devices'))} connected"]
    turns = int(_total(metrics, "bridge_turns_started_total"))
    if turns:
        parts.append(f"{turns} turn(s)")
    waiting = int(_total(metrics, "bridge_questions_open"))
    if waiting:
        parts.append(f"{waiting} approval(s) waiting")
    return ", ".join(parts)


# ---------------------------------------------------------------- icon
def make_icon(summary: str) -> QIcon:
    """The app's gold mark with a status dot, drawn in code so it is sharp at every size."""
    icon = QIcon()
    for size in (16, 22, 24, 32, 48, 64):
        pix = QPixmap(size, size)
        pix.fill(Qt.GlobalColor.transparent)
        p = QPainter(pix)
        p.setRenderHint(QPainter.RenderHint.Antialiasing)
        p.setPen(Qt.PenStyle.NoPen)
        p.setBrush(QBrush(ACCENT))
        p.drawRoundedRect(QRectF(0, 0, size, size), size * 0.24, size * 0.24)
        p.setPen(QColor("#1A1A1A"))
        font = QFont()
        font.setBold(True)
        font.setPixelSize(int(size * 0.66))
        p.setFont(font)
        p.drawText(QRectF(0, 0, size, size), Qt.AlignmentFlag.AlignCenter, "H")
        r = size * 0.22
        p.setPen(QColor("#1A1A1A"))
        p.setBrush(QBrush(STATE_COLOURS.get(summary, STATE_COLOURS["unknown"])))
        p.drawEllipse(QPointF(size - r - 0.5, size - r - 0.5), r, r)
        p.end()
        icon.addPixmap(pix)
    return icon


# ---------------------------------------------------------------- background polling
class Poller(QObject):
    done = pyqtSignal(int, dict)

    def work(self, generation: int) -> None:
        self.done.emit(generation, poll())


class Tray(QObject):
    request = pyqtSignal(int)

    def __init__(self, app: QApplication) -> None:
        super().__init__()
        self.app = app
        self.health: dict = {"state": "unknown", "summary": "unknown", "problem": "checking...",
                             "checks": [], "trust": None, "devices": None}
        self.asked_networks: set[str] = set()
        # Every action that changes the bridge (revoke, trust, stop, ...) starts a new generation.
        # A poll reports the generation it started in, and an older answer is dropped: it was
        # read before the change and would put a revoked device back in the menu.
        self.generation = 0
        self.polling: int | None = None  # the generation of the poll in flight, if any

        self.icon = QSystemTrayIcon(make_icon("unknown"))
        self.icon.setToolTip("Hermes Remote: checking...")
        self.menu = QMenu()
        self.menu.aboutToShow.connect(self.build_menu)
        self.icon.setContextMenu(self.menu)
        self.icon.activated.connect(self.on_activated)
        self.icon.show()

        self.thread = QThread()
        self.poller = Poller()
        self.poller.moveToThread(self.thread)
        self.poller.done.connect(self.apply)
        self.request.connect(self.poller.work)  # queued: the poll runs on the worker thread
        self.thread.start()

        self.timer = QTimer()
        self.timer.timeout.connect(self.refresh)
        self.timer.start(POLL_MS)
        self.refresh()

    # ------------------------------------------------------------ health
    def refresh(self) -> None:
        if self.polling == self.generation:
            return  # an up-to-date poll is already running
        self.polling = self.generation
        self.request.emit(self.generation)

    def changed(self) -> None:
        """The bridge's state just changed: polls already running describe the old one."""
        self.generation += 1
        self.refresh()

    def apply(self, generation: int, health: dict) -> None:
        if generation != self.generation:
            return  # read before the last action; the poll queued after it is the one to show
        self.polling = None
        self.health = health
        self.icon.setIcon(make_icon(health["summary"]))
        label = {"running": "running", "stopped": "stopped", "missing": "not installed"}.get(health["state"], health["state"])
        self.icon.setToolTip(f"Hermes Remote: {label}\n{health['problem']}")
        self.show_health()
        self.offer_trust()

    def show_health(self) -> None:
        """Rebuild the menu as soon as fresh data lands (and again as it opens), so whatever
        opens next is never a snapshot from before the last change."""
        if not self.menu.isVisible():
            self.build_menu()

    def offer_trust(self) -> None:
        """Once per new network, offer to trust it - the same nudge the Windows balloon gives."""
        current = (self.health.get("trust") or {}).get("current")
        if not current or current.get("trusted") or current["id"] in self.asked_networks:
            return
        self.asked_networks.add(current["id"])
        self.icon.showMessage("Hermes Remote", f"New network '{current['name']}'. Open the tray menu -> Network to "
                              "let the phone connect over it.", QSystemTrayIcon.MessageIcon.Information, 8000)

    def on_activated(self, reason: QSystemTrayIcon.ActivationReason) -> None:
        if reason in (QSystemTrayIcon.ActivationReason.Trigger, QSystemTrayIcon.ActivationReason.DoubleClick):
            self.refresh()
            self.menu.popup(self.icon.geometry().center() if not self.icon.geometry().isNull()
                            else QGuiApplication.primaryScreen().availableGeometry().center())

    def after(self, fn) -> None:
        """Run a blocking action, then poll so the menu reflects it."""
        QApplication.setOverrideCursor(Qt.CursorShape.WaitCursor)
        try:
            fn()
        finally:
            QApplication.restoreOverrideCursor()
        self.changed()

    # ------------------------------------------------------------ menu
    def add(self, menu: QMenu, text: str, fn=None, enabled: bool = True) -> QAction:
        action = menu.addAction(text)
        action.setEnabled(enabled and fn is not None)
        if fn is not None:
            action.triggered.connect(lambda _=False, f=fn: f())
        return action

    def build_menu(self) -> None:
        """Rebuilt right before it opens, so what you read is at most one poll old."""
        m = self.menu
        m.clear()
        h = self.health
        state = h["state"]
        label = {"running": "running", "stopped": "stopped", "missing": "not installed - run ./install.sh"}.get(state, state)
        self.add(m, f"Bridge: {label}")
        self.add(m, f"Checks: {h['problem']}")
        if h.get("activity"):
            self.add(m, f"Activity: {h['activity']}")
        m.addSeparator()

        if h["checks"]:
            details = m.addMenu("Details")
            for check in h["checks"]:
                mark = {"ok": "\u2713", "warn": "!"}.get(check.get("level"), "\u2717")
                self.add(details, f"{mark}  {check.get('message', '')}")
        self.add(m, "Run doctor...", self.run_doctor)
        self.add(m, "Bridge stats...", self.show_stats, state == "running")
        self.add(m, "Refresh now", self.changed)
        m.addSeparator()

        installed = state != "missing"
        self.add(m, "Start bridge", lambda: self.after(lambda: systemctl("start")), installed and state != "running")
        self.add(m, "Stop bridge", lambda: self.after(lambda: systemctl("stop")), state == "running")
        self.add(m, "Restart bridge", lambda: self.after(lambda: systemctl("restart")), installed)
        m.addSeparator()

        net = m.addMenu("Network")
        trust = h["trust"]
        current = (trust or {}).get("current")
        if current:
            if current.get("trusted"):
                self.add(net, f"Stop trusting {current['name']}",
                         lambda i=current["id"]: self.after(lambda: bridge("trust", "--remove", i)))
            else:
                self.add(net, f"Trust {current['name']}", lambda: self.after(lambda: bridge("trust")))
        elif trust is not None:
            self.add(net, "No network (offline)")
        else:
            self.add(net, "Checking network...")
        trusted = (trust or {}).get("trusted") or []
        if trusted:
            listing = net.addMenu("Trusted networks")
            for entry in trusted:
                self.add(listing, f"{entry['name']} - remove",
                         lambda i=entry["id"]: self.after(lambda: bridge("trust", "--remove", i)))

        dev = m.addMenu("Devices")
        self.add(dev, "Pair a new device...", lambda: self.pair())
        active = [d for d in ((h["devices"] or {}).get("devices") or []) if d.get("active")]
        if active:
            dev.addSeparator()
            for device in active:
                one = dev.addMenu(device["name"])
                self.add(one, "Re-pair (new code, old one stops working)...", lambda d=device: self.pair(d["name"]))
                self.add(one, "Revoke", lambda d=device: self.revoke(d))
        m.addSeparator()

        self.add(m, "Copy last 50 log lines", self.copy_log)
        self.add(m, "Open logs in a terminal", self.open_log_terminal)
        fw = next((c for c in h["checks"] if c.get("id") == "firewall"), None)
        if fw and fw.get("level") != "ok":
            self.add(m, "Firewall is blocking the phone (fix...)", self.fix_firewall)

        login = m.addAction("Start tray at login")
        login.setCheckable(True)
        login.setChecked(AUTOSTART.exists())
        login.toggled.connect(self.set_autostart)
        m.addSeparator()
        self.add(m, "Quit tray (bridge keeps running)", self.quit)

    # ------------------------------------------------------------ actions
    def revoke(self, device: dict) -> None:
        name = device["name"]
        if QMessageBox.question(None, "Revoke device", f"Revoke '{name}'? It will have to pair again.") \
                != QMessageBox.StandardButton.Yes:
            return
        out = ""

        def do_revoke() -> None:
            nonlocal out
            out = bridge("revoke", device["id"])  # the id: a name can come back after a re-pair

        self.after(do_revoke)
        if not out.startswith("Revoked"):
            QMessageBox.warning(None, "Revoke failed", f"Could not revoke '{name}'. Run in a terminal:\n\n"
                                f"hermes-remote-bridge revoke {device['id']}")
            return
        self.forget_device(device["id"])

    def forget_device(self, device_id: str) -> None:
        """Take a revoked device off the menu now; the poll already running confirms it."""
        rows = (self.health.get("devices") or {}).get("devices") or []
        self.health = {**self.health, "devices": {"devices": [d for d in rows if d.get("id") != device_id]}}
        self.show_health()

    def run_doctor(self) -> None:
        """Every check with a tick or cross, in a dialog: the same list `doctor` prints."""
        QApplication.setOverrideCursor(Qt.CursorShape.WaitCursor)
        try:
            checks = (bridge_json("doctor", "--json") or {}).get("checks") or []
        finally:
            QApplication.restoreOverrideCursor()
        if not checks:
            QMessageBox.warning(None, "Doctor", f"The doctor did not answer. Run in a terminal:\n\n'{BIN}' doctor")
            return
        marks = {"ok": "\u2713", "warn": "!"}
        lines = [f"{marks.get(c.get('level'), chr(0x2717))}  {c.get('message', '')}" for c in checks]
        failed = sum(c.get("level") not in ("ok", "warn") for c in checks)
        lines.append("")
        lines.append("All good." if not failed else f"{failed} problem(s) found.")
        box = QMessageBox(QMessageBox.Icon.Information if not failed else QMessageBox.Icon.Warning,
                          "Hermes Remote doctor", "\n".join(lines))
        box.exec()
        self.changed()

    def show_stats(self) -> None:
        """The bridge's counters since it started: the same summary `hermes-remote-bridge metrics` prints."""
        QApplication.setOverrideCursor(Qt.CursorShape.WaitCursor)
        try:
            text = bridge("metrics").strip()
        finally:
            QApplication.restoreOverrideCursor()
        if not text:
            QMessageBox.warning(None, "Bridge stats", "The bridge did not answer. Run in a terminal:\n\n"
                                f"'{BIN}' metrics\n\n(Bridges before 1.26 have no metrics; update with ./install.sh.)")
            return
        box = QMessageBox(QMessageBox.Icon.Information, "Hermes Remote bridge stats", text)
        box.setInformativeText("Counted since the bridge started. Prometheus: GET /metrics (see docs/BRIDGE_API.md).")
        box.setTextInteractionFlags(Qt.TextInteractionFlag.TextSelectableByMouse)
        font = QFont("monospace")
        font.setStyleHint(QFont.StyleHint.Monospace)
        box.setFont(font)
        box.exec()

    def pair(self, replace: str | None = None) -> None:
        """Pair a new phone, or with [replace] re-pair that one: its old code stops working and
        the app, scanning the new code, replaces the broken pairing in place."""
        if replace:
            if QMessageBox.question(None, "Re-pair device", f"Make a new pairing code for '{replace}'?\n\n"
                                    "Its current pairing stops working now. Scan the new code in the app "
                                    "(Settings -> Re-pair this PC) and it picks up where it left off.") \
                    != QMessageBox.StandardButton.Yes:
                return
            name = replace
        else:
            name, ok = QInputDialog.getText(None, "Pair a device", "Name for this phone:", text="phone")
            name = name.strip()
            if not ok or not name:
                return
        # Same as the Windows tray: the CLI writes the pairing payload to a mode-600 file (never
        # stdout), the QR is drawn from it, and both files are deleted when the dialog closes.
        STATE_DIR.mkdir(parents=True, exist_ok=True)
        work = Path(tempfile.mkdtemp(prefix="pair-", dir=STATE_DIR))
        cred, qr = work / "pairing.json", work / "pairing-qr.png"
        QApplication.setOverrideCursor(Qt.CursorShape.WaitCursor)
        try:
            out = bridge("pair", name, "--token-file", str(cred), *(["--replace"] if replace else []), timeout=60)
            payload = json.loads(cred.read_text()) if cred.exists() else None
            if payload:
                run([str(BIN.parent / "python"), "-c", QR_SCRIPT, str(cred), str(qr)], 30)
        except (OSError, json.JSONDecodeError):
            payload = None
        finally:
            QApplication.restoreOverrideCursor()
        try:
            if not payload or not qr.exists():
                QMessageBox.warning(None, "Pairing failed", f"Could not pair '{name}'.\n\n{out}")
                return
            PairDialog(name, str(qr), f"URL: {payload['url']}\nToken: {payload['token']}").exec()
        finally:
            shutil.rmtree(work, ignore_errors=True)
            self.changed()

    def copy_log(self) -> None:
        out = run(["journalctl", "--user", "-u", UNIT, "-n", "50", "--no-pager", "-o", "short-iso"], 15)
        QGuiApplication.clipboard().setText(out or "(no log lines)")
        self.icon.showMessage("Hermes Remote", "Last 50 log lines copied.", QSystemTrayIcon.MessageIcon.Information, 3000)

    def terminal(self, command: str) -> bool:
        for term, flag in (("konsole", "-e"), ("gnome-terminal", "--"), ("kgx", "--"), ("xfce4-terminal", "-x"),
                           ("alacritty", "-e"), ("kitty", ""), ("foot", ""), ("wezterm", "start --"), ("xterm", "-e")):
            if shutil.which(term):
                subprocess.Popen([term, *flag.split(), "bash", "-c", command], start_new_session=True)
                return True
        QMessageBox.information(None, "No terminal found", f"Run this in a terminal:\n\n{command}")
        return False

    def open_log_terminal(self) -> None:
        self.terminal(f"journalctl --user -u {UNIT} -f")

    def fix_firewall(self) -> None:
        # The CLI explains the change and asks before using sudo, so it needs a real terminal.
        self.terminal(f"'{BIN}' firewall; echo; read -rp 'Press Enter to close.'")

    def set_autostart(self, enabled: bool) -> None:
        if enabled:
            AUTOSTART.parent.mkdir(parents=True, exist_ok=True)
            AUTOSTART.write_text(desktop_entry())
        else:
            AUTOSTART.unlink(missing_ok=True)

    def quit(self) -> None:
        self.timer.stop()
        self.thread.quit()
        self.thread.wait(2000)
        self.icon.hide()
        self.app.quit()


def desktop_entry() -> str:
    # The interpreter running this file is one that has PyQt6. `python3` on the login PATH may
    # be a different one (a tool-bundled Python, a virtualenv), so name this one explicitly.
    # Not resolved: /usr/bin/python3 survives a Python upgrade, /usr/bin/python3.14 does not.
    return ("[Desktop Entry]\nType=Application\nName=Hermes Remote\n"
            "Comment=Tray for the Hermes Mobile Remote bridge\n"
            f'Exec="{sys.executable}" "{Path(__file__).resolve()}"\n'
            "Icon=hermes-remote\nTerminal=false\nCategories=Network;Utility;\n"
            "StartupNotify=false\nX-GNOME-Autostart-enabled=true\nX-GNOME-Autostart-Delay=5\n")


def wait_for_tray(seconds: int = 60) -> bool:
    """At login the tray can start before the panel does; wait for it instead of giving up."""
    for _ in range(seconds):
        if QSystemTrayIcon.isSystemTrayAvailable():
            return True
        QThread.sleep(1)
    return QSystemTrayIcon.isSystemTrayAvailable()


class PairDialog(QDialog):
    """Draws the QR the bridge wrote, plus a Copy button for manual entry."""

    def __init__(self, name: str, qr: str, details: str) -> None:
        super().__init__()
        self.setWindowTitle(f"Pair {name} - scan this in the Hermes Remote app")
        self.setWindowFlag(Qt.WindowType.WindowStaysOnTopHint)
        layout = QVBoxLayout(self)
        pic = QLabel()
        pic.setPixmap(QPixmap(qr).scaled(380, 380, Qt.AspectRatioMode.KeepAspectRatio,
                                         Qt.TransformationMode.FastTransformation))
        pic.setAlignment(Qt.AlignmentFlag.AlignCenter)
        layout.addWidget(pic)
        hint = QLabel("Open Hermes Remote on the phone and scan the code.\nNo camera? Copy the URL and token below.")
        hint.setAlignment(Qt.AlignmentFlag.AlignCenter)
        layout.addWidget(hint)
        entry = details
        copy = QPushButton("Copy URL and token")
        copy.setEnabled(bool(entry))

        def do_copy() -> None:
            QGuiApplication.clipboard().setText(entry)
            copy.setText("Copied")
        copy.clicked.connect(do_copy)
        layout.addWidget(copy)
        done = QPushButton("Done")
        done.clicked.connect(self.accept)
        layout.addWidget(done)


def main() -> int:
    if "--desktop-entry" in sys.argv:
        print(desktop_entry(), end="")
        return 0
    if not BIN.exists():
        sys.stderr.write(f"The bridge is not installed ({BIN} is missing). Run ./install.sh first.\n")
        return 1
    app = QApplication(sys.argv)
    app.setApplicationName("Hermes Remote")
    app.setDesktopFileName("hermes-remote-tray")
    app.setQuitOnLastWindowClosed(False)
    if not wait_for_tray():
        sys.stderr.write("No system tray found. On GNOME, install the 'AppIndicator and KStatusNotifierItem "
                         "Support' extension.\n")
        return 1
    # Single instance: a second tray would just duplicate the icon.
    lock = STATE_DIR / "tray.pid"
    STATE_DIR.mkdir(parents=True, exist_ok=True)
    try:
        pid = int(lock.read_text())
        os.kill(pid, 0)
        if Path(f"/proc/{pid}/cmdline").read_bytes().find(b"tray.py") >= 0:
            sys.stderr.write("The Hermes Remote tray is already running.\n")
            return 0
    except (OSError, ValueError):
        pass
    lock.write_text(str(os.getpid()))
    tray = Tray(app)  # noqa: F841 - kept alive for the event loop
    try:
        return app.exec()
    finally:
        lock.unlink(missing_ok=True)


if __name__ == "__main__":
    sys.exit(main())
