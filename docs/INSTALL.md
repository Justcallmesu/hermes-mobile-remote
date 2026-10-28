# Installing

Two parts: the bridge on your PC, the app on your phone. The bridge is the part with options.

## Requirements

The steps below are for Linux. **Windows** has its own section at the end.

| PC | Phone |
|---|---|
| Linux with systemd (developed on KDE Plasma, Arch-based), **or** Windows 10/11 (experimental) | Android 8.0+ |
| [Hermes Agent](https://hermes-agent.nousresearch.com) installed and running | |
| `git` | |
| [uv](https://docs.astral.sh/uv/) | |
| Optional: Tailscale; on Linux also NetworkManager and Avahi | Optional: Tailscale |

macOS is not supported yet. See [PLATFORMS.md](PLATFORMS.md) for what each OS does and does not
have.

Nothing in Hermes needs enabling. The phone shares the backend the Hermes desktop app runs; when
the desktop app is closed, the bridge starts `hermes serve` itself on loopback.

**Upgrading from 0.x:** re-run the installer, then update the app from its System tab. A 1.x bridge
and a 0.x app cannot talk to each other. The installer no longer touches Hermes' API server; if 0.x
turned it on for the bridge, you can turn it off again (`hermes config set api_server.enabled
false`) unless something else uses it.

## Install the bridge

```bash
git clone https://github.com/nideta231/hermes-mobile-remote.git
cd hermes-mobile-remote
./install.sh
```

The installer is safe to re-run. It:

1. Checks for `uv` and a Hermes install.
2. Installs the bridge into `bridge/.venv` and starts it as a systemd **user** service.
3. Asks whether the current network is yours (home or office) and should be trusted.
4. If a firewall (ufw or firewalld) blocks the bridge port, shows the exact rules and asks before
   adding them. This is the only step that needs your password.
5. Runs `doctor` to check everything over.

The installed unit file is a **copy**, not a symlink. Editing the unit in the repository does not
touch the running service until you run `./install.sh` again.

## Install the app

1. Download the APK from
   [Releases](https://github.com/nideta231/hermes-mobile-remote/releases/latest), or
   [build it yourself](DEVELOPMENT.md#build-the-app).
2. On the PC, pair the phone. This prints a QR code that is shown **once**:
   ```bash
   bridge/.venv/bin/hermes-remote-bridge pair phone
   ```
3. In the app, tap **Scan pairing QR code**.

Each QR code pairs exactly one device. To pair without a camera, the same command prints the URL
and token for manual entry.

## Connecting

The app picks the best path on its own. You only need to care when it cannot.

| Where you are | Connection | Tailscale needed? |
|---|---|---|
| A Wi-Fi you trusted | Direct, HTTPS with the PC's pinned certificate | No |
| Any other Wi-Fi | Tailscale only | Yes |
| Mobile data | Tailscale | Yes |

- **Trusting a network:** when the PC joins a network it has not seen, a desktop notification asks
  whether to trust it. You can also run `hermes-remote-bridge trust` while on it. The System tab in
  the app shows the current path and has manual switch buttons.
- **If the PC's IP changes,** the app finds it again over mDNS without re-pairing. On the PC side,
  network changes are picked up within about 5 seconds.

## Tray app (Linux)

`install.sh` installs **Hermes Remote** as a desktop app: an app-menu entry with its own icon, a
`hermes-remote-tray` command in `~/.local/bin`, and (unless you say no) an autostart entry so the
tray icon appears every time you log in. It is the Linux version of the Windows tray: a coloured dot for bridge health
(green ok, amber warning, red failure, grey stopped), the doctor checks under **Details**,
start/stop/restart, **Network** (trust or forget networks), **Devices** (pair with a QR, revoke),
copying the log, the firewall fix, and **Start tray at login**.

The bridge itself stays a systemd user service, so quitting the tray does not stop it. Reopen the
tray from the app menu or with `hermes-remote-tray`. It needs PyQt6 for the system Python
(`sudo pacman -S python-pyqt6`, `sudo apt install python3-pyqt6` or `sudo dnf install
python3-pyqt6`); the installer looks for a Python that has it and writes that interpreter's path
into the menu and autostart entries, so a different `python3` earlier on your PATH does not matter.
GNOME also needs the *AppIndicator and KStatusNotifierItem Support* extension to show tray icons.

`install.sh` also links `hermes-remote-bridge` into `~/.local/bin`, so `hermes-remote-bridge pair
phone` works from any directory (make sure `~/.local/bin` is on your PATH).

## Everyday commands

```bash
B=bridge/.venv/bin/hermes-remote-bridge

$B doctor                  # explains what is wrong if the phone cannot connect
$B pair phone              # pair a device (QR in the terminal; --qr-png file.png to save it)
$B devices                 # list paired devices
$B revoke phone            # takes effect immediately
$B trust                   # trust the current network
$B trust --list            # ...which networks are trusted
$B trust --remove ID       # ...stop trusting one
$B firewall                # allow the port from private networks (asks first)

journalctl --user -u hermes-remote-bridge -f    # live log
```

Revoking a device is immediate: the next request from it is `401`. Re-pairing under a name that is
already active fails, so revoke first.

## Uninstall

```bash
./install.sh --uninstall
```

This stops and removes the systemd user service. It does **not** delete your data: paired devices
and settings stay in `~/.config/hermes-remote`, and the audit log in
`~/.local/state/hermes-remote`. Remove those yourself if you want a clean slate:

```bash
rm -rf ~/.config/hermes-remote ~/.local/state/hermes-remote
```

The app is removed by uninstalling it from Android. Because the release key is not published, a
release-signed app cannot be updated by a locally built one: uninstall before switching.

## Configuring

Everything is optional; the defaults are sensible. Config lives in
`~/.config/hermes-remote/config.toml`, and every key with its default is documented in
[`bridge/hermes_remote_bridge/config.py`](../bridge/hermes_remote_bridge/config.py).

```toml
port = 8650
lan = true                      # serve the local network on trusted networks
mdns = true                     # announce over mDNS so the app finds a changed IP
start_hermes = true             # start `hermes serve` when the desktop app isn't running
allowed_logins = []             # Tailscale logins allowed; empty means this node's owner
allowed_profiles = []           # Hermes profiles the phone may switch to; empty means all
                                #   (the default profile is always available)
max_body_bytes = 1_000_000
requests_per_minute = 240
frames_per_minute = 600         # per live connection
park_seconds = 300              # keep Hermes' socket open this long after the phone drops; 0 = off
```

An unknown key is an error, not a silently ignored typo. Keys from 0.x (`hermes_url`,
`hermes_env`, `krdp_unit`, `krdp_port`, `runs_per_minute`, `run_buffer_events`,
`run_retention_seconds`) are accepted and ignored, so an old file still loads.

## Trying it without a Hermes install

The repository ships a demo stack with a synthetic backend — no keys, no model calls:
[`demo/README.md`](../demo/README.md).

## If something is wrong

[docs/TROUBLESHOOTING.md](TROUBLESHOOTING.md).

## Windows

Experimental: see [PLATFORMS.md](PLATFORMS.md#what-is-verified-on-windows) for exactly what is and
is not verified. Use native Windows, not WSL2.

From a normal (non-admin) Command Prompt or PowerShell window, or by double-clicking `install.cmd`
in the folder:

```powershell
git clone https://github.com/nideta231/hermes-mobile-remote.git
cd hermes-mobile-remote
install.cmd
```

Use `install.cmd`, not `install.ps1` directly. A stock Windows refuses unsigned scripts ("running
scripts is disabled on this system"), and `install.cmd` relaxes that for this one run only, so you
never touch the account-wide policy. If you downloaded a ZIP instead of cloning, the installer also
clears the "downloaded from the internet" mark on its scripts. Do not extract the ZIP and then edit
the execution policy by hand.

If `uv` (the Python package manager the bridge is installed with) is missing, the installer asks
and installs it for you, with `winget` or the official installer, no admin needed. Pass `-InstallUv`
(`install.cmd -InstallUv`) to skip the question. It then finds `uv` in the same window, so you do not have to reopen
PowerShell.

The installer does the same things as the Linux one:

1. Checks `uv` and Hermes (`%LOCALAPPDATA%\hermes`, or `$env:HERMES_HOME`).
2. Installs the bridge into `bridge\.venv`.
3. Registers a Scheduled Task called **Hermes Mobile Remote** that starts at logon. It starts the
   tray app, which runs the bridge, restarts it when the network changes, and puts an icon in the
   notification area.
4. Asks whether this is a network you trust, then offers the firewall rule (one UAC prompt, private
   ranges and the Private profile only).

Then pair the phone: the tray is in the notification area, so use **Devices → Pair a new device**
and scan the code in the app. `pair.cmd` in the folder still works from a console, but the
installer deliberately does not offer it: `pair` from the console writes the QR to a file and
opens it with your default image viewer, which leaves a live token sitting on disk. The tray shows
the QR in a window of its own and deletes the picture when you close it. The `.exe` in
`bridge\.venv\Scripts` needs a command after its name, so double-clicking *that* only shows a help
screen.

Check on it any time from the tray menu, or with
`.\bridge\.venv\Scripts\hermes-remote-bridge.exe doctor`. The tray also offers the firewall rule,
which is the usual reason a phone cannot connect on a fresh Windows install. To remove it:
`install.cmd -Uninstall` stops the tray, the task and the firewall rule, and keeps your paired
devices and settings in `%LOCALAPPDATA%\hermes-remote`; add `-Purge` to delete those too.
