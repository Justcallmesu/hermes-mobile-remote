"""Windows 10/11: routes, neighbours, network profile and firewall through ctypes/COM (winapi.py);
no PowerShell anywhere (elevation re-runs the CLI via ShellExecuteExW runas). The old Scheduled
Task of a script install is removed by the desktop app (migration).

Written against Microsoft's documented APIs and unit-tested with their output replaced; CI's Windows job
runs them for real and compares them with the cmdlets' answers (see docs/PLATFORMS.md).
"""
from __future__ import annotations

import os
import shutil
import subprocess
import sys
from pathlib import Path

from . import winapi
from .base import Host

TASK_NAME = "Hermes Mobile Remote"
FIREWALL_RULE = "Hermes Mobile Remote"


def _local_appdata() -> Path:
    return Path(os.environ.get("LOCALAPPDATA") or Path.home() / "AppData" / "Local")


def parse_default_routes(rows: list[dict]) -> tuple[str, str] | None:
    """Pick (interface, gateway) from Get-NetRoute rows: lowest combined metric, on-link routes
    (NextHop 0.0.0.0) excluded because they have no router to identify the network by."""
    best: tuple[int, str, str] | None = None
    for row in rows:
        gw, alias = str(row.get("Gateway") or ""), str(row.get("Alias") or "")
        if not alias or gw in ("", "0.0.0.0"):
            continue
        metric = int(row.get("Metric") or 0)
        if best is None or metric < best[0]:
            best = (metric, alias, gw)
    return (best[1], best[2]) if best else None


def normalise_mac(raw: str) -> str | None:
    """"AA-BB-CC-DD-EE-FF" (Windows) to "aa:bb:cc:dd:ee:ff"; None for blanks and all-zero."""
    mac = raw.strip().lower().replace("-", ":")
    if len(mac.split(":")) != 6 or set(mac) <= {"0", ":"}:
        return None
    return mac


class WindowsHost(Host):
    name = "windows"
    venv_bin = "Scripts"
    exe_suffix = ".exe"

    def config_dir(self) -> Path:
        return _local_appdata() / "hermes-remote" / "config"

    def state_dir(self) -> Path:
        return _local_appdata() / "hermes-remote" / "state"

    def default_hermes_home(self) -> Path:
        return _local_appdata() / "hermes"

    def default_route(self) -> tuple[str, str] | None:
        return parse_default_routes(winapi.default_routes())

    def gateway_mac(self, gateway: str) -> str | None:
        if not all(c.isdigit() or c == "." for c in gateway):
            return None  # only ever an IPv4 literal
        return normalise_mac(winapi.neighbor_mac(gateway))

    def network_profile(self, interface: str) -> tuple[str, str] | None:
        """(connection profile GUID, name) from the Network List Manager; the name is the SSID on Wi-Fi."""
        return winapi.network_profile(interface)

    def tailscale_cli(self) -> str | None:
        found = shutil.which("tailscale")
        if found:
            return found
        default = Path(os.environ.get("ProgramFiles", r"C:\Program Files")) / "Tailscale" / "tailscale.exe"
        return str(default) if default.exists() else None

    def service_state(self, name: str) -> str:
        """"active" or "inactive" by whether the bridge answers on its configured port; other
        service names are not tracked on Windows."""
        if name != "hermes-remote-bridge":
            return "unknown"
        from ..config import Config  # config imports this package
        return "active" if winapi.bridge_reachable(Config.load().port) else "inactive"

    def process_snapshot(self) -> list[dict] | None:
        return winapi.process_snapshot()

    # ------------------------------------------------------------------ interactive use

    def launched_by_double_click(self) -> bool:
        """A console shared with its launcher (cmd, PowerShell) has 2+ processes attached; a window
        Explorer opened just for us has exactly one."""
        try:
            import ctypes
            buf = (ctypes.c_uint * 4)()
            return ctypes.windll.kernel32.GetConsoleProcessList(buf, 4) <= 1
        except (AttributeError, OSError):
            return False

    def qr_as_image(self) -> bool:
        return True

    def open_file(self, path: Path) -> bool:
        try:
            os.startfile(str(path))  # noqa: S606 - the user's own file, default viewer
            return True
        except (AttributeError, OSError):
            return False

    # ------------------------------------------------------------------ firewall

    def firewall_active(self) -> bool:
        return winapi.firewall_profiles_enabled()

    def firewall_rule_present(self, port: int) -> bool:
        return any(r["Name"] == FIREWALL_RULE and r["Enabled"] and r["Inbound"]
                   and str(port) in str(r["LocalPorts"]).replace(" ", "").split(",")
                   for r in winapi.firewall_rules())

    def firewall_add(self, port: int) -> bool:
        """Create our rule (Private profile, private ranges). Only works in an elevated process."""
        from ..firewall import COMMENT, PRIVATE_RANGES  # firewall imports this package
        return winapi.firewall_add_rule(FIREWALL_RULE, COMMENT, port, ",".join(PRIVATE_RANGES))

    def firewall_remove(self) -> bool:
        return winapi.firewall_remove_rules(FIREWALL_RULE)

    def elevate_cli(self, args: list[str]) -> bool:
        """Relaunch this venv's bridge exe with ``args`` through the UAC prompt (one prompt, no
        PowerShell); True when it exits 0, False when cancelled or failed."""
        own = Path(sys.executable).with_name("hermes-remote-bridge.exe")
        exe = str(own) if own.exists() else sys.argv[0]
        return winapi.run_elevated(exe, subprocess.list2cmdline(args)) == 0
