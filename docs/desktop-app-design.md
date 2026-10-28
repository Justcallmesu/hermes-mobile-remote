# Desktop app (Tauri) for Windows and Linux: design

Status: **designed, not built.** Branch `feature/desktop-app`, from
`origin/main` v1.23.1. Nothing committed or pushed. Replaces an earlier, never-committed draft
(pystray + pywebview, Windows only); its still-valid decisions are carried over below.

## Goal

One desktop app for **Windows and Linux** that installs, controls and shows the bridge, with a real
installer on each OS, and **no PowerShell anywhere in the plumbing** on Windows. The Python bridge,
its HTTP API and the phone contract stay; Linux keeps systemd.

## Why

- Windows today is PowerShell around a Python bridge: `install.ps1` (254 lines), `bridge/windows/tray.ps1`
  (683), `supervise.ps1` + `run-bridge.ps1`, and `.cmd` launchers that exist to bypass the execution policy.
  Most of `docs/windows-tray-design.md:312-340` is PowerShell/WinForms traps.
- `host/windows.py` spawns `powershell.exe` per OS question (`Get-NetRoute` ~820 ms, `Get-NetNeighbor`
  ~1070 ms, `Get-NetConnectionProfile` ~780 ms; `windows-tray-design.md:36-44`); a cache hides it until the
  network changes.
- Linux has a separate PyQt6 tray (`bridge/linux/tray.py`, 525 lines, system `python3`). Two GUIs, two
  toolkits, and stock GNOME shows no tray at all.
- Install is `git clone` + a script on both OSes; there is no installer, uninstall entry or app update.

## Shape

```
Windows
  HKCU Run (Tauri autostart plugin)
    -> Hermes Remote.exe  (Tauri: Rust + Vue; tray icon + flyout window)
         spawns, in a Job Object (kill on close):
           hermes-remote-bridge supervise     (Python policy: restart on 75, back off on crash)
             -> hermes-remote-bridge serve
  Headless / RDP: hermes-remote-bridge supervise  (same command, console)

Linux
  systemd --user hermes-remote-bridge.service   (Restart=always, sandboxed; unchanged)
  Hermes Remote (Tauri, AppImage or .deb)        app window from the launcher (.desktop);
                                                 tray icon only where the desktop has one;
                                                 controls the service with systemctl --user

Both: app <-> bridge
  http://127.0.0.1:<port>/v1/local/*   + per-install secret   (fast path, bridge running)
  hermes-remote-bridge <cmd> --json                             (fallback, bridge down)
```

Repo layout: top-level `desktop/` (`src-tauri/` Rust, Vue UI), next to `android/` and `bridge/`.

## Pieces

### 1. Bridge (Python): no PowerShell, a supervisor, a local control API

- **Native host calls** (`host/windows.py`): ctypes for Win32 (`GetIpForwardTable2`/`GetBestRoute2`,
  `GetIpInterfaceEntry`, `GetIpNetTable2`), **comtypes** for COM (`INetworkListManager`,
  `HNetCfg.FwPolicy2`). No subprocess. `parse_default_routes`/`normalise_mac` keep their contracts, and the
  identity key (connection profile, router MAC) must come out identical, so **existing trusted networks stay
  trusted**: CI runs old and new side by side on the runner before the PowerShell path is deleted. Firewall
  writes: `ShellExecuteW("runas", "hermes-remote-bridge.exe firewall add ...")`, one UAC prompt as today.
  Measure `current_network(fresh=True)` and `/v1/me` before/after.
- **`hermes-remote-bridge supervise`**: port of `supervise.ps1` policy (exit 75 -> restart after 2 s; crash ->
  back off `max(3, min(60, 5*quick_failures))` s, quick = under 20 s; logs UTF-8 to
  `%LOCALAPPDATA%\hermes-remote\state\bridge.log`, rotate at 2 MB). Port conflict is a distinct exit code from
  `serve`, not a log grep (D28). Used by the app (Windows) and headless; unit-tested with a fake child.
- **`/v1/local/*`** (status, devices, pair, revoke, trust, doctor, firewall state): only from a loopback peer
  **and** with the per-install secret (a file in the bridge config dir readable only by the user, sent as a
  header; created by the bridge, D29). Loopback alone is not trusted: `tailscale serve`, other Windows accounts and any local program reach
  127.0.0.1. Never in the relay's `ALLOWED_METHODS`, never reachable over LAN/tailnet listeners. Implemented
  on the same functions the CLI's `--json` output uses, so the two paths cannot disagree.
- **Terminal shell** (`terminal.py:default_shell`): `pwsh` > `powershell` > `cmd`, overridable by a
  `terminal_shell` config key. The phone's Terminal is the user's shell, not plumbing.

### 2. Desktop app (Tauri, `desktop/`)

- **Windows**: tray icon; left click opens a WARP-style flyout (borderless, at the taskbar corner, closes on
  focus loss); right click a short menu (open, start/stop, pair, quit).
- **Linux**: an app window from the launcher. A tray icon is added only where the desktop has one, and opens
  the window from a menu item (Tauri's tray click events do not fire on Linux; GNOME needs the AppIndicator
  extension for any tray).
- **Same Vue UI** in both: big on/off switch; status line (network trusted?, phone reachable via firewall /
  LAN / tailnet?); paired devices with re-pair / revoke (revoke confirms); "Pair a device" with the QR inline;
  settings page: doctor, open logs, firewall check/fix, start at logon, terminal shell.
- **Data**: `/v1/local/*` when the bridge answers; otherwise the CLI with `--json` (doctor, pair, revoke,
  trust all still work when the bridge is down or will not start).
- **Start/stop**: Windows, the app owns `supervise` in a Job Object (quit = bridge stops; a hard kill of the app
  also stops it). Linux, by run mode (D31):
  - **Always on** (default): systemd user service; the app runs `systemctl --user start/stop`; closing or
    crashing the app leaves the bridge running.
  - **Only while the app is open**: the app stops and disables the unit, then runs `supervise` as its child
    (`PR_SET_PDEATHSIG` so a crashed app takes the bridge with it, the Linux stand-in for a Job Object). Loses
    systemd's sandboxing (`ProtectSystem=strict` etc.); the settings page says so.
  - Switching modes always stops the old owner before starting the new one; the port-conflict exit code (D28)
    catches a mistake instead of two bridges fighting.
  - **Start at login** (D32): `systemctl --user enable/disable` for the bridge, separate from the app's own
    autostart. Only in always-on mode.
  - **Keep running after logout** (D33): `loginctl enable-linger $USER`, off by default, for a headless or server
    box. Only in always-on mode with start at login on. Polkit allows it for your own user on most distros; where
    it asks for a password the app says so. Turning it off runs `disable-linger`.
- **First untrusted network**: an OS notification "Trust this network?" opening the UI; a decline is recorded
  and not re-asked (as Linux does today).
- **Crash of the app (Windows, and Linux in app-only mode)**: nothing restarts it; the bridge dies with it, so nothing runs unseen; the
  Start-menu entry relaunches it. Stated in the docs and the manual checks.

### 3. Installers

- Built by Tauri's bundler in CI: **Windows NSIS** (per-user, no admin; bundles the WebView2 bootstrapper),
  **Linux AppImage + .deb**. Unsigned for now: Windows shows SmartScreen "More info > Run anyway".
- The installer carries the **bridge source + `uv.lock`** as resources. First run (and after every update):
  1. show **what it will change** (uv, Python, the venv, autostart, Hermes API-server setting, the optional
     firewall rule + UAC) before acting; keep that list in the app's settings page;
  2. find `uv`, else download it (Rust, HTTPS, SHA-256 checked against the release's published hash), no
     PowerShell;
  3. `uv sync --frozen` into a **per-user** venv (Windows `%LOCALAPPDATA%\hermes-remote\venv`, Linux
     `~/.local/share/hermes-remote/venv`, also for the system-wide `.deb`, because the service is a user
     service);
  4. Windows: Run key via the autostart plugin. Linux: write and enable the systemd user unit;
  5. verify the bridge answers before reporting success.
- **Migration from a script install** (Windows): kill the PowerShell tray / `run-bridge.ps1` (found by command
  line), delete the "Hermes Mobile Remote" Scheduled Task (`schtasks /Delete /F`), then autostart. Linux:
  take over the existing unit; keep `tray.py` working until it is removed (D23). Paired devices, trusted
  networks and config live in the same per-user dirs and are kept.
- **Uninstall** (Add/Remove Programs, or the package manager): stop the bridge, remove autostart (and any old
  task), remove the firewall rule (one UAC), keep config/devices unless the user ticks "remove my data".
  Hermes' `.env` is never touched.
- **Updates**: Tauri updater plugin from GitHub Releases (Windows NSIS and AppImage; on `.deb` the app announces a new
  release with a download link, D30). Needs an updater signing key pair (free); the private key is a repo secret that nideta holds.
  After an update: stop the bridge (Windows locks files in a venv in use), `uv sync --frozen`, start it.
- `install.cmd`, `install.ps1`, `tray.cmd`, `pair.cmd` and `bridge/windows/*.ps1` are deleted in the same PR.
  `install.sh` stays for source installs on Linux (decide its fate with D23's follow-up).

## Decisions (with the maintainer, 2026-10-27)

| # | Decision |
|---|---|
| D1 | No PowerShell in the plumbing: no `.ps1`, no `powershell.exe` spawned by the bridge or installer. The phone Terminal may still open PowerShell (D13). |
| D2 | One long branch, one PR; `origin/main` merged in weekly. Reaffirmed twice, including after the Tauri switch doubled the scope. |
| D3 | GUI is a **Tauri app (Rust + Vue)** for Windows and Linux, in top-level `desktop/`. |
| D4 | UI content: on/off switch, status line, devices with re-pair/revoke, inline QR pairing; settings page with doctor/logs/firewall/startup/shell. |
| D5 | Windows autostart: HKCU Run key (Tauri autostart plugin); visible and disableable in Task Manager > Startup. Replaces the Scheduled Task. |
| D6 | Nothing restarts a crashed app; the bridge dies with it (Job Object); the Start-menu entry relaunches it. |
| D7 | Windows: the app spawns `hermes-remote-bridge supervise` (Python policy, also the headless path) in a Job Object; quitting the app stops the bridge. |
| D8 | App <-> bridge: loopback-only `/v1/local/*` with a per-install secret; never in the phone allowlist. |
| D9 | Bridge OS calls: ctypes for Win32, comtypes for the two COM objects. |
| D10 | Real installers from Tauri's bundler: Windows NSIS, Linux AppImage + .deb (AUR later). |
| D11 | The installer ships the bridge source + `uv.lock`; first run downloads uv (hash-checked, in Rust) and runs `uv sync --frozen` into a per-user venv. |
| D12 | Upgrade from a script install: kill the PowerShell tray, delete the old task, set autostart; the `.ps1`/`.cmd` files are deleted in the same PR. |
| D13 | Terminal shell: `pwsh` > `powershell` > `cmd`, overridable by config. |
| D14 | WebView2: bundled bootstrapper in the NSIS installer. |
| D15 | OS scope: Windows and Linux. macOS stays a stub. |
| D16 | The installer shows "what this changes" before acting; the list stays visible in settings. |
| D17 | CI: Python unit tests (supervisor, host, `/v1/local` auth) with fakes; `windows-latest` job with real Win32/COM probes, install from the built NSIS, bridge dies with the app, uninstall removes autostart and the old task; Linux job builds AppImage/.deb and runs the service path; Tauri builds on both. |
| D18 | Human checks in `docs/desktop-app-manual-checks.md`, filled in on the maintainer's machines (Windows PC, Arch PC) before the PR. |
| D19 | Linux keeps the systemd user service (Restart=always + sandboxing); the app controls it; closing the app leaves the bridge running. |
| D20 | Linux GUI: app window from the launcher; tray icon optional, only where the desktop has one, opening the window from a menu item. |
| D21 | Bridge down: the app falls back to the CLI with `--json` for doctor/pair/revoke/trust. |
| D22 | Updates: Tauri updater from GitHub Releases, updater key held by nideta; re-sync the venv after an update. |
| D23 | `bridge/linux/tray.py` stays until the Tauri app has proven itself on Linux; removed in a follow-up PR. |
| D24 | Unsigned builds for now; code signing deferred. |
| D25 | nideta agreed (2026-10-27) to Rust + Node in the repo, per-OS release builds and holding the updater key as a repo secret. |
| D26 | Rust toolchain (rustup + MSVC Build Tools, ~6 GB) is installed on this PC when the build starts, on the maintainer's yes at that time. |
| D27 | Branch renamed `feature/desktop-app`. |
| D28 | `serve` exits with a distinct code on a port conflict, so the supervisor stops retrying on a fact, not a log grep. (Proposed, not objected to.) |
| D29 | The `/v1/local` secret is a random 32-byte file the bridge creates on first start (user-only readable), re-created if missing; deleting it revokes every local client. |
| D30 | `.deb` installs: the app checks GitHub Releases and shows "new version available" with a download link. |
| D31 | Linux run mode setting: "always on" (systemd, default) or "only while the app is open" (app runs `supervise`, dies with the app via `PR_SET_PDEATHSIG`, no sandboxing). Switching stops the old owner first. |
| D32 | Linux "start at login" toggle for the bridge (`systemctl --user enable/disable`), separate from the app's autostart; always-on mode only. |
| D33 | Linux "keep running after logout" (`loginctl enable-linger`), off by default; always-on mode with start at login only. |
| D34 | Windows keeps "the app owns the bridge" (D6/D7): no always-on mode, since it would need a Scheduled Task again. |

### Decisions made during the build (planner, 2026-10-28; the maintainer was away and delegated them)

| # | Decision |
|---|---|
| D35 | Desktop releases use tags `desktop-v<semver>`; the APK keeps `v<semver>`. The repo's "latest release" is usually an APK, so neither the updater nor the `.deb` release check may read `releases/latest`. |
| D36 | The updater reads `latest.json` from a rolling pre-release `desktop-latest`; `desktop-updater.yml` copies it there when a `desktop-v*` release is published. `release.yml` makes desktop releases as drafts; publishing one is the maintainer's step. |
| D37 | The `.deb` (and a dev build) checks the GitHub releases list for the newest `desktop-v*` tag and links its page (D30). |
| D38 | The UI is plain JavaScript (no TypeScript), Vue 3 + Vite + vitest. |
| D39 | uv is pinned in the app (`UV_VERSION`, 0.13.6 at first) and checked against the release's `.sha256` file. `uv sync --frozen --no-dev`. |
| D40 | The data dir is `$XDG_DATA_HOME/hermes-remote` on Linux (default `~/.local/share/hermes-remote`), `%LOCALAPPDATA%\hermes-remote` on Windows; the venv, uv and the bridge source live under it. |
| D41 | Linux run mode is stored as `run_mode` in `<config_dir>/desktop.toml`, a file the bridge never reads. A failed switch to app-only puts the systemd service back and keeps always-on. |
| D42 | The app-only child is `supervise` with a stdin pipe: closing it (app exit) stops the bridge, `PR_SET_PDEATHSIG` covers a crash. The supervisor ignores a stdin that is a TTY or `/dev/null`. |
| D43 | Linux tray: created only when `org.kde.StatusNotifierWatcher` has an owner on the session bus. The trust notification is sent by the app once per untrusted network (`<config_dir>/desktop-asked.json`); the notification plugin cannot report a click on Linux, so the window is shown when it fires. |
| D44 | Open: the bridge's own `notify-send` trust prompt (`cli._ask_to_trust`) still runs under `serve`, so a Linux desktop may see two prompts. Remove it once the app has proven itself (with D23's follow-up). |
| D45 | Controls whose OS backend is per platform (start/stop, start at logon, firewall fix, open logs) are Tauri commands behind a `Control` trait; a platform without a backend answers "not available yet on this platform". |

## Rejected

| Option | Why not |
|---|---|
| pystray + pywebview (the first draft) | The maintainer wants Rust + Vue with real installers on both OSes; pywebview also dragged in pythonnet/.NET on Windows. |
| Qt for both (grow `tray.py`) | 60-100 MB on Windows, widget look instead of the flyout. |
| tkinter | Cannot look like the flyout. |
| Batch-file installer (`install.cmd` -> uv -> window) | Superseded by real installers (D10). |
| Frozen bridge sidecar (PyInstaller/Nuitka) | Larger, AV false positives on unsigned builds; uv + `uv sync` keeps today's model. |
| Rust supervisor | Policy would live in two languages; the Python `supervise` is also the headless path. |
| Launcher that restarts a crashed app; Scheduled Task | Maintainer accepts "app crash = bridge down, relaunch from Start"; the task is invisible in Task Manager. |
| Spawning the CLI for every status poll | 0.3-0.5 s per call; kept only as the bridge-down fallback (D21). |
| Tray supervising the bridge on Linux as the only mode | Loses systemd's restart and sandboxing; offered instead as an opt-in run mode (D31). |
| Always-on mode on Windows | Needs a Scheduled Task (invisible in Task Manager); the maintainer kept D6/D7 (D34). |
| Parsing `route print` / `arp -a` / `netsh` | Translated output on non-English Windows. |
| pywin32, pythonnet for COM | Native DLLs and per-Python wheels / ties network code to a GUI dependency. |
| Staged PRs | Maintainer chose one PR (D2). |
| Signing now | Deferred (D24). |

## Order of work on the branch

0. ~~Ask nideta~~ (D25): agreed.
1. **Bridge, Python only** (no Rust needed, can start on this PC today): `supervise` + D28; native host calls with
   the side-by-side CI check; `/v1/local/*` + secret; terminal shell.
2. **Toolchain** on the maintainer's yes (D26), then a **Tauri spike**: tray + flyout positioned at the taskbar
   on Windows, app window on Linux (Arch PC), spawning `supervise` in a Job Object, calling `/v1/local/status`.
   If the spike fails, D3 reopens before more is built.
3. **App UI** (Vue): status, devices, pairing, settings; CLI fallback.
4. **Installers**: NSIS + AppImage + .deb, first-run setup (uv download, `uv sync`, autostart / systemd unit),
   migration, uninstall, updater.
5. **CI + release**: Tauri builds on both OSes, the Windows and Linux jobs (D17), release assets, updater manifest.
6. **Cleanup + docs**: delete `.ps1`/`.cmd`; `PLATFORMS.md`, `INSTALL.md`, `TROUBLESHOOTING.md`, `SECURITY.md`
   (the `/v1/local` surface), `ARCHITECTURE.md`, `BRIDGE_API.md`; "replaced by" header on
   `windows-tray-design.md`; `CHANGELOG.md` under `## Unreleased`; `docs/desktop-app-manual-checks.md`.

## Deferred

| Deferred | Revisit when |
|---|---|
| Code signing (Windows; Linux packages) | SmartScreen complaints, or before announcing the installer widely |
| AUR package | An Arch user asks, or the AppImage proves awkward on Arch |
| Removing `bridge/linux/tray.py` and deciding `install.sh` | The Tauri app has run on Linux for a release (D23) |
| macOS app | `host/macos.py` gets a real implementation |

## Open questions

1. **Tauri spike results** (step 2): flyout positioning at the Windows taskbar, Job Object from Rust, Linux
   window on KDE and GNOME.
