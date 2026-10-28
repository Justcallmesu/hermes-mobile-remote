# Installing

Two parts: the bridge on your PC, the app on your phone. On Windows and Linux the bridge comes with
**Hermes Remote**, a desktop app that installs it, runs it and shows its status. On Linux you can
still install the bridge from source with `install.sh`.

## Requirements

| PC | Phone |
|---|---|
| Linux with systemd (developed on KDE Plasma, Arch-based), **or** Windows 10/11 (experimental) | Android 8.0+ |
| [Hermes Agent](https://hermes-agent.nousresearch.com) installed and running | |
| Source install only: `git` and [uv](https://docs.astral.sh/uv/). The desktop app finds `uv` or downloads it. | |
| Optional: Tailscale; on Linux also NetworkManager and Avahi | Optional: Tailscale |

macOS is not supported yet. See [PLATFORMS.md](PLATFORMS.md) for what each OS does and does not
have.

Nothing in Hermes needs enabling. The phone shares the backend the Hermes desktop app runs; when
the desktop app is closed, the bridge starts `hermes serve` itself on loopback.

**Upgrading from 0.x:** install the desktop app (or re-run `./install.sh` for a Linux source install),
then update the app from its System tab. A 1.x bridge and a 0.x app cannot talk to each other. The
installer no longer touches Hermes' API server; if 0.x turned it on for the bridge, you can turn it
off again (`hermes config set api_server.enabled false`) unless something else uses it.

## Install the desktop app

Desktop builds are on the [Releases](https://github.com/nideta231/hermes-mobile-remote/releases)
page, tagged `desktop-v<version>`. The phone's APK keeps the plain `v<version>` tags.

The first time the app starts, it runs first-run setup. It shows the list of changes before it makes
any, and saves that list to `INSTALLED.txt` in the config directory. Then it:

1. Finds `uv` on your PATH, or downloads uv 0.13.6 from GitHub Releases and checks its SHA-256.
2. Copies the bridge source into the data directory.
3. Creates the bridge's Python environment in the data directory (`uv sync --frozen --no-dev`).
4. Sets up autostart and starts the bridge. On Linux it writes and enables a systemd user unit. On
   Windows it turns on start at logon and starts the bridge.
5. Waits up to 20 seconds for the bridge to answer.

Hermes' own config and `.env` are never touched.

| | Linux | Windows |
|---|---|---|
| Config (`config.toml`, devices) | `~/.config/hermes-remote` | `%LOCALAPPDATA%\hermes-remote\config` |
| Data (bridge source, venv) | `$XDG_DATA_HOME/hermes-remote` (default `~/.local/share/hermes-remote`) | `%LOCALAPPDATA%\hermes-remote` |
| State (logs, audit log) | `~/.local/state/hermes-remote` | `%LOCALAPPDATA%\hermes-remote\state` |

After an update, the app re-syncs the bridge's environment the next time it starts.

### Windows

Experimental: see [PLATFORMS.md](PLATFORMS.md#what-is-verified-on-windows) for exactly what is and
is not verified. Use native Windows, not WSL2.

1. Run the Hermes Remote `.exe` installer from the desktop release. It installs for your user only;
   no admin rights are needed. It bundles the WebView2 bootstrapper, so the WebView2 runtime is
   installed if it is missing.
2. The installer is unsigned, so SmartScreen shows a warning. Choose **More info**, then **Run
   anyway**.
3. Start **Hermes Remote** from the Start menu and let first-run setup finish.
4. Pair a phone: right-click the tray icon and choose **Pair a device**, or use the window. Scan the
   QR code in the app.

How it runs:

- The app owns the bridge. It starts the bridge in a Job Object, so quitting the app, or killing it,
  stops the bridge.
- If the app crashes, nothing restarts it. Start Hermes Remote from the Start menu.
- There is no "always on" mode on Windows.
- Start at logon is a Run key named "Hermes Remote" for your user. It shows in Task Manager >
  Startup, where you can also disable it.
- Left click on the tray icon opens a flyout at the taskbar corner; it hides when it loses focus.

**Upgrading from a script install.** On first run, setup stops the old PowerShell tray and bridge
(it finds them by their command line), deletes the "Hermes Mobile Remote" Scheduled Task, and turns
on start at logon. Paired devices and trusted networks stay. The old `install.cmd`, `install.ps1`,
`pair.cmd` and `tray.cmd` are gone.

**Upgrading from `install.sh` on Linux.** Setup takes over the existing `hermes-remote-bridge.service`:
it rewrites the unit to point at the app's venv (`~/.local/share/hermes-remote/venv`) and restarts the
service, so the bridge from the repo's `.venv` stops. Paired devices and trusted networks stay (same
config folder). The old Python tray keeps its autostart entry; if you now see two tray icons, remove it:
`rm ~/.config/autostart/hermes-remote-tray.desktop ~/.local/share/applications/hermes-remote-tray.desktop`.

### Linux

1. Download the `.AppImage` or the `.deb` from the desktop release. For the AppImage, make it
   executable (`chmod +x`) and run it. For the `.deb`, install it with your package manager, for
   example `sudo apt install ./<file>.deb`.
2. Open **Hermes Remote** from the application launcher. It opens a normal window.
3. A tray icon appears only if your desktop has a StatusNotifier host. KDE does. GNOME needs the
   *AppIndicator and KStatusNotifierItem Support* extension. Without a tray icon, the window is the
   way in. The tray menu holds Open, Start, Stop and Quit; there is no left-click action on Linux.

Run mode (Settings):

- **Always on** (default): the bridge runs as the systemd user service `hermes-remote-bridge.service`.
  Closing the app leaves it running. **Start at login** enables or disables the service. **Keep
  running after logout** runs `loginctl enable-linger`; it appears only when start at login is on.
  If polkit refuses, the app tells you to run `loginctl enable-linger` in a terminal.
- **Only while the app is open**: the app stops and disables the service and runs the bridge as its
  own child, so the bridge stops when the app closes. This mode has no systemd sandboxing, and the
  settings page says so. If the switch fails, the service is put back and the mode stays Always on.

### Both systems

- **Updates.** The AppImage and the Windows installer update themselves: a "Restart to update" banner
  appears when one is ready. The `.deb` cannot update itself; the app shows "New version X available"
  with a link to the release page.
- **The window** has an on/off switch, a status line (is this network trusted, firewall, LAN,
  tailnet), devices with re-pair and revoke (revoke asks first), **Pair a device** with the QR code
  inline and a Copy button, and Settings (doctor, firewall fix, start at logon, terminal shell, run
  mode on Linux, and a list of what this install changed).
- **If the bridge is not running**, the app still shows devices and doctor results from saved data,
  and says "bridge not running, showing saved data".
- **Trust this network?** The first time the PC joins a network it does not trust, an OS notification
  asks. Clicking it opens the window. It is asked once per network. On Linux the window opens when the
  notification appears, because clicks cannot be detected there.

## Install the bridge from source (Linux)

This is the alternative to the desktop app on Linux. It installs the bridge as a systemd user service.

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
2. On the PC, pair the phone. In the desktop app, choose **Pair a device**; the QR code is shown in
   the window with a Copy button. On a Linux source install, this command prints a QR code that is
   shown **once**:
   ```bash
   bridge/.venv/bin/hermes-remote-bridge pair phone
   ```
3. In the app, tap **Scan pairing QR code**.

Each QR code pairs exactly one device. On a source install, the same command prints the URL and token
for manual entry.

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

## Tray app (Linux, source installs)

If you use the desktop app, you do not need this section: the app has its own tray icon.

`install.sh` also installs a tray icon: an app-menu entry with its own icon, a `hermes-remote-tray`
command in `~/.local/bin`, and (unless you say no) an autostart entry so the tray icon appears every
time you log in. It shows a coloured dot for bridge health (green ok, amber warning, red failure,
grey stopped), the doctor checks under **Details**, start/stop/restart, **Network** (trust or forget
networks), **Devices** (pair with a QR, revoke), copying the log, the firewall fix, and **Start tray
at login**.

The bridge itself stays a systemd user service, so quitting the tray does not stop it. Reopen the
tray from the app menu or with `hermes-remote-tray`. It needs PyQt6 for the system Python
(`sudo pacman -S python-pyqt6`, `sudo apt install python3-pyqt6` or `sudo dnf install
python3-pyqt6`); the installer looks for a Python that has it and writes that interpreter's path
into the menu and autostart entries, so a different `python3` earlier on your PATH does not matter.
GNOME also needs the *AppIndicator and KStatusNotifierItem Support* extension to show tray icons.

`install.sh` also links `hermes-remote-bridge` into `~/.local/bin`, so `hermes-remote-bridge pair
phone` works from any directory (make sure `~/.local/bin` is on your PATH).

## Everyday commands

Source install (Linux):

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

With the desktop app, the same commands live in the app's venv: `hermes-remote-bridge` under
`$XDG_DATA_HOME/hermes-remote/venv/bin` on Linux, and `hermes-remote-bridge.exe` under
`%LOCALAPPDATA%\hermes-remote\venv\Scripts` on Windows. On Windows, the bridge's log is
`%LOCALAPPDATA%\hermes-remote\state\bridge.log`.

Revoking a device is immediate: the next request from it is `401`. Re-pairing under a name that is
already active fails, so revoke first.

## Uninstall

### Desktop app

- **Windows:** Settings > Apps > Hermes Remote > Uninstall. This stops the bridge, removes start at
  logon, removes the old Scheduled Task if one is left, and removes the firewall rule (one UAC prompt).
  Your config and paired devices stay in `%LOCALAPPDATA%\hermes-remote` unless you tick **Delete the
  application data**.
- **Linux:** stop the service (`systemctl --user disable --now hermes-remote-bridge`), then delete the
  AppImage or remove the `.deb` with your package manager. Your config and paired devices in
  `~/.config/hermes-remote` are not removed. To remove everything, also delete
  `~/.config/hermes-remote`, `~/.local/share/hermes-remote` and `~/.local/state/hermes-remote`.

### Source install (Linux)

```bash
./install.sh --uninstall
```

This stops and removes the systemd user service. It does **not** delete your data: paired devices and
settings stay in `~/.config/hermes-remote`, and the audit log in `~/.local/state/hermes-remote`.
Remove those yourself if you want a clean slate:

```bash
rm -rf ~/.config/hermes-remote ~/.local/state/hermes-remote
```

### The phone app

The app is removed by uninstalling it from Android. Because the release key is not published, a
release-signed app cannot be updated by a locally built one: uninstall before switching.

## Configuring

Everything is optional; the defaults are sensible. Config lives in
`~/.config/hermes-remote/config.toml` on Linux and `%LOCALAPPDATA%\hermes-remote\config\config.toml`
on Windows. Every key with its default is documented in
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
terminal_shell = ""             # the shell the phone's Terminal opens; empty means the platform
                                #   default (Windows: pwsh, then powershell, then cmd)
```

`terminal_shell` can be any command on your PATH, with arguments, for example
`terminal_shell = "\"C:\\Program Files\\Git\\bin\\bash.exe\" -l"` on Windows. If the command is not
found, the default is used and a warning is logged.

An unknown key is an error, not a silently ignored typo. Keys from 0.x (`hermes_url`,
`hermes_env`, `krdp_unit`, `krdp_port`, `runs_per_minute`, `run_buffer_events`,
`run_retention_seconds`) are accepted and ignored, so an old file still loads.

## Trying it without a Hermes install

The repository ships a demo stack with a synthetic backend — no keys, no model calls:
[`demo/README.md`](../demo/README.md).

## If something is wrong

[docs/TROUBLESHOOTING.md](TROUBLESHOOTING.md).
