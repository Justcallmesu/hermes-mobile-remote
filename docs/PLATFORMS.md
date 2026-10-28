# Platforms

Which operating systems the bridge runs on, how far each one is supported, and how a new one is
added. The phone side is Android everywhere; this page is about the PC.

| PC | Status | Service | Wi-Fi trust | Verified by |
|---|---|---|---|---|
| **Linux** (developed on Arch-based, KDE Plasma) | Supported | systemd user service, run by the Hermes Remote app or by `install.sh` | NetworkManager + router MAC | Daily use, CI on Ubuntu |
| **Windows 10 / 11** | Experimental | Hermes Remote desktop app owns the bridge; start at logon is a Run key | Connection profile + router MAC | CI on `windows-latest`; see below |
| **macOS** | Not yet | n/a | n/a | n/a |

"Experimental" has a specific meaning here: it is written and tested, but the author does not run
it daily. The Windows desktop app was written and unit-tested on Linux, so CI's Windows jobs are its
first real run. Read [What is verified on Windows](#what-is-verified-on-windows) before relying on it.

## What is the same everywhere

Everything that is not the operating system: the API, pairing, device tokens, the trust model
(a network is identified by its profile *and* the router's MAC), the pinned HTTPS on the LAN,
Tailscale identity checks, rate limits, the audit log, approvals, runs. One code path, one test
suite. See [ARCHITECTURE.md](ARCHITECTURE.md).

## What differs

All of it lives in `bridge/hermes_remote_bridge/host/`, one module per OS behind one interface
(`host/base.py`). The rest of the bridge never checks which OS it is on.

| Concern | Linux | Windows |
|---|---|---|
| Config and state | `~/.config/hermes-remote`, `~/.local/state/hermes-remote` (XDG) | `%LOCALAPPDATA%\hermes-remote\config`, `...\state` |
| Bridge source and venv (desktop app) | `$XDG_DATA_HOME/hermes-remote` (default `~/.local/share/hermes-remote`) | `%LOCALAPPDATA%\hermes-remote` |
| Hermes location | `~/.hermes` | `%LOCALAPPDATA%\hermes` (Hermes' own default); `HERMES_HOME` overrides both |
| Background service | systemd user unit `hermes-remote-bridge.service`, restarts on exit 75 | Run by the Hermes Remote app in a Job Object; quitting or killing the app stops the bridge. Start at logon is the HKCU Run key "Hermes Remote" |
| Headless (no desktop) | the same unit, or `hermes-remote-bridge supervise` in a terminal | `hermes-remote-bridge supervise` in a console or RDP session |
| Default route, router MAC | `/proc/net/route`, `/proc/net/arp` | Win32 `iphlpapi` through `ctypes`; no PowerShell |
| Network name | NetworkManager (`nmcli`) | Connection profile through COM (`INetworkListManager`); the SSID on Wi-Fi |
| Tailscale | LocalAPI unix socket | `tailscale status --json` / `whois --json` (no socket exists) |
| Firewall | `ufw` or `firewalld`, via `pkexec` | Windows Defender Firewall rule through COM (`HNetCfg.FwPolicy2`), Private profile only; added by an elevated run (one UAC prompt) |
| mDNS | Avahi | `zeroconf` (Python) |
| "Trust this network?" prompt | `notify-send`: the app's prompt, and the bridge's own while it still exists (a desktop can show two) | an OS notification from the app, asked once per new network |
| TLS certificate | `cryptography` | `cryptography` (no `openssl` needed) |

The supervisor policy (exit 75, backoff) lives once, in `bridge/hermes_remote_bridge/supervise.py`.
The desktop app and the `supervise` command both run it.

## Linux

Install: see [INSTALL.md](INSTALL.md). Either the Hermes Remote desktop app, or `./install.sh` for a
source install.

- **Run mode** (desktop app, Settings). "Always on" (default) runs the bridge as a systemd user
  service, which keeps running when the app is closed. "Only while the app is open" stops the
  service and runs the bridge as a child of the app, so it stops when the app closes. That mode has
  no systemd sandboxing, and the settings page says so.
- **Keep running after logout** is `loginctl enable-linger`. It is offered only when start at login
  is on.
- **Tray icon.** The app shows a tray icon only where the desktop has a StatusNotifier host. KDE
  does. GNOME needs the *AppIndicator and KStatusNotifierItem Support* extension. Without a tray, the
  window is the way in.

## Windows

Install: see [INSTALL.md](INSTALL.md#windows). Things that are specific to it:

- **No admin needed.** The app installs per user. The firewall rule is optional, asks first, and
  needs one UAC prompt.
- **WSL2 is not supported.** WSL2 sits behind a NAT with its own address, so the phone cannot reach
  a bridge running inside it, and it cannot see the Windows network profile. Install Hermes and the
  bridge natively; Hermes itself runs natively on Windows.
- **The desktop app (Hermes Remote)** runs the bridge and is the way to manage it. Its tray icon:
  left click opens a flyout at the taskbar corner, which hides when it loses focus. Right click: Open,
  Start/Stop bridge, Pair a device, Quit. The window has the on/off switch, a status line (network
  trusted, firewall, LAN, tailnet), devices, pairing with a QR code, and Settings.
- **The app owns the bridge.** Quitting the app stops it. If the app crashes, nothing restarts it:
  start Hermes Remote from the Start menu. There is no always-on mode on Windows, so the bridge is
  down whenever the app is not running.
- **Start at logon** is a Run key named "Hermes Remote" under the current user. It shows in Task
  Manager > Startup, where it can also be disabled.
- **Trusting a network** is asked once per new network, by an OS notification. The command is still
  there when you want it: `hermes-remote-bridge trust`.
- **Logs** are in `%LOCALAPPDATA%\hermes-remote\state\bridge.log` (and `audit.log` beside it).

### What is verified on Windows

CI runs on a real `windows-latest` machine on every change. That proves:

- the whole bridge test suite passes on Windows;
- the native host answers (default route, router MAC, connection profile) match what the Windows
  cmdlets report, so a network that was trusted stays trusted;
- `doctor`, `devices` and `trust --list` run without crashing, and answer `--json`;
- `supervise` brings the bridge up on the configured port, exits 76 on a port conflict instead of
  retrying, and stops when its stdin is closed;
- the desktop app's NSIS installer runs silently, the app brings the bridge up, a hard kill of the app
  takes the bridge with it, and the uninstaller removes autostart and an old Scheduled Task;
- the desktop app's Rust and Vue tests pass, and its bundles build, on both `windows-latest` and
  Ubuntu;
- neither the bridge nor the app starts PowerShell. The phone's Terminal is the one exception: it
  opens the user's own shell.

**Still not verified:** no person has used the app's flyout, checked its position on the bottom, top
or side taskbar, clicked through the UAC prompt, seen the SmartScreen warning on the unsigned
installer, paired a real phone, or upgraded from a script install. A GitHub runner has no
notification area, so CI cannot cover the flyout or the notifications. The manual checklist is
[desktop-app-manual-checks.md](desktop-app-manual-checks.md).

CI does **not** prove, because a GitHub runner has no Wi-Fi, no phone and no Tailscale:

- a phone can connect over Windows Wi-Fi or Tailscale;
- the firewall rule is accepted and then lets the phone through (the runner's router MAC is also
  synthetic, `12:34:56:78:9a:bc`);
- Tailscale's CLI output on Windows matches what the parser expects;
- start at logon survives a real logoff, sleep and wake;
- mDNS announcements reach the phone.

If you run it on Windows, **please report what worked and what did not**: open an issue with the
output of `doctor` (Settings in the app, or `hermes-remote-bridge doctor`). That is the fastest way to
move this from experimental to supported.

## macOS

Not implemented, and the seam is already there: `host/macos.py` exists and returns the right
config directory and finds the Tailscale CLI. What is missing is the local-network path, so on a
Mac today the bridge would serve loopback and Tailscale only:

| Needed | Likely approach |
|---|---|
| Default route and gateway | `route -n get default` |
| Router MAC | `arp -n <gateway>` |
| Network name | `networksetup -getairportnetwork <interface>` (changed in recent macOS versions) |
| Service | a launchd agent in `~/Library/LaunchAgents` |
| Firewall | the application firewall prompts per app; probably nothing to automate |
| mDNS | `zeroconf`, already shared |

It is listed in [ROADMAP.md](ROADMAP.md). If you want to take it, the work is one module and a
`install-macos.sh`; see [Adding a platform](#adding-a-platform).

## Adding a platform

1. Add `host/<os>.py` with a class extending `Host`. Override only what differs; the base class
   has safe defaults, and anything you leave out simply turns that feature off.
2. Add one branch to `_load()` in `host/__init__.py`.
3. Write an installer that registers the bridge with the OS's service manager and restarts it
   when it exits with status 75.
4. Add tests in `bridge/tests/test_host.py` with the OS commands' output replaced, so they run on
   every platform.
5. Add a CI job on that OS that runs the real commands, as `bridge-windows` does. Do not claim
   support until that job exists.
6. Update the table at the top of this page.
