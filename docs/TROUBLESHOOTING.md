# Troubleshooting

Start with `doctor`. It checks the things that are actually worth checking and explains what is wrong.
Everything below is a symptom it may not name. In the desktop app, Settings runs it. In a terminal, run
`hermes-remote-bridge doctor` (source installs: `bridge/.venv/bin/hermes-remote-bridge doctor`).

## The phone cannot connect at all

On **Windows**, the same checks apply with different places to look: the app's Settings page runs
doctor and the firewall fix, and the log is `%LOCALAPPDATA%\hermes-remote\state\bridge.log` instead of
`journalctl`. The bridge only runs while the Hermes Remote app runs. If the app is closed or has
crashed, start it from the Start menu. WSL2 cannot work; see [PLATFORMS.md](PLATFORMS.md).

**Windows: `hermes-remote-bridge` with no command says to pair from the app.** That is the bridge
`.exe` run without arguments. Pair with **Pair a device** in Hermes Remote instead.

**`forbidden_network` / "The PC doesn't serve this network."**

The bridge only serves loopback, Tailscale, and a Wi-Fi network you marked trusted. On the PC:

```bash
bridge/.venv/bin/hermes-remote-bridge trust          # trust the network you are on now
bridge/.venv/bin/hermes-remote-bridge trust --list   # what is trusted
```

On Windows, the same question comes as a notification from the app, and the status line in the window
shows whether the current network is trusted. `trust` from a terminal does the same thing.

You are trusting the network you *control*. On someone else's Wi-Fi, use Tailscale instead — the
LAN listener is not supposed to exist there.

**It worked yesterday on the same Wi-Fi.**

The PC moved to a different network, or the router's MAC changed, or Tailscale went down. Check the
log: `journalctl --user -u hermes-remote-bridge -f` on Linux, or `bridge.log` on Windows. The bridge
logs every listen-set change. It rebinds within about 5 seconds of a change, so a phone that cannot
connect afterwards is usually an IP change the app has not found yet — leave the app open for a
moment, or check that Avahi is running if you rely on mDNS.

**Connection timeouts on the LAN.**

Check the firewall. On Linux:

```bash
bridge/.venv/bin/hermes-remote-bridge firewall
```

It prints the exact rules for ufw or firewalld and asks before applying them. On Windows, use the
firewall fix in the app's Settings (one UAC prompt). Only private ranges are opened, on the Private
profile only.

**`forbidden_peer` / "Bridge refused this Tailscale identity."**

The request came from a tailnet address that is not your login. Either someone else on your tailnet
is trying to connect, or `allowed_logins` in `config.toml` is too narrow. Empty means "the owner of
this node".

**The bridge stops and does not come back: port in use.**

Another copy of the bridge, or another program, holds the bridge port (8650 by default). The bridge
exits with status 76, and the supervisor stops instead of retrying, so it stays down. Find the holder
(Linux: `ss -ltnp | grep 8650`; Windows: `netstat -ano | findstr :8650`). If it is the desktop app's
own bridge, quit Hermes Remote first. Otherwise stop that program, or change `port` in `config.toml`.
Then start the bridge again.

## The app is paired but shows nothing

**"This device was revoked or the token is wrong. Pair again."**

The device was revoked, or `~/.config/hermes-remote/devices.json` was edited or replaced. Re-pair.

**The connection flips between LAN and Tailscale.**

The System tab shows the current path with manual switches. Automatic re-selection happens on a
network change, every 20 s while the app is open, and when you return to the app. If both paths are
reachable it prefers the LAN.

**"Hermes is not answering" / the app keeps reconnecting.**

The bridge could not reach a Hermes backend. `hermes-remote-bridge doctor` says which: no backend
in the spawn ledger and `start_hermes = false`, or `hermes serve` failed to start (its log is
`~/.local/state/hermes-remote/hermes-serve.log`). Opening the Hermes desktop app also starts one,
and the bridge attaches to it on the next connect.

**Nothing loads after updating only the PC or only the phone.**

1.0 changed the protocol. Update both: install the desktop app (or `./install.sh` for a Linux source
install) on the PC, and the phone app from its System tab.

## The desktop app

**"bridge not running, showing saved data".**

The app is open but the bridge is not running. Devices and doctor results come from saved data through
the command-line tool. Start the bridge with the switch in the window, or with **Start** in the tray
menu.

**Linux: two "Trust this network?" prompts.**

On Linux, the app and the bridge can each show a prompt for the same new network. This is a known
limit until the bridge's own prompt is removed.

**Linux: clicking the notification does nothing useful.**

Clicks on a notification cannot be detected on Linux. The window opens when the notification appears.

**Linux: "Keep running after logout" does not appear, or polkit refused.**

That option is shown only when start at login is on. If polkit refuses to keep the bridge running
after logout, run `loginctl enable-linger` in a terminal.

**Linux: no tray icon.**

Only desktops with a StatusNotifier host show one. GNOME needs the *AppIndicator and
KStatusNotifierItem Support* extension. The window still works without it.

**Windows: the bridge stopped when I closed the app.**

That is by design: the app owns the bridge on Windows, and there is no always-on mode there. Keep the
app running, or leave it open when you need the phone to connect.

**Windows: the app crashed and the bridge is down.**

Nothing restarts it. Start Hermes Remote from the Start menu.

**Windows: SmartScreen blocks the installer.**

The installer is unsigned. Choose **More info**, then **Run anyway**.

## Turns misbehave

**Typing while a turn runs steers it instead of sending a new message.**

By design, as on the desktop: one turn at a time per session. Stop the turn, or open a new chat.

**The phone lost the connection mid-turn.**

The turn keeps running on the PC. When the phone reconnects it re-opens the chat with
`session.resume`, which returns the transcript so far, the turn still in flight and any open
question, then continues streaming.

**"Too many requests".**

The per-device limits are 240 requests/min and, on a live connection, 600 frames/min. Usually a
reconnect loop on a network that drops every few seconds. Fix the network, not the limit.

## Slash commands

**A command is not in the list.**

The list comes from Hermes' own completer. Commands that only work in a terminal or open a desktop
window (`/voice`, `/skin`, `/pet`, `/config`, …) are hidden, the same set the desktop app hides.

**"Method not available from the phone".**

The bridge's relay refused a call that the desktop's chat view does not make. If the app made it,
that is a bug: report it with the bridge log line.

## Notifications

**No notification when a turn finishes.**

Notifications cover chats opened on the phone, while the app keeps its connection in the
background (a silent "watching" notification shows while it does). Check that the app is allowed
to run in the background: Xiaomi, Huawei and some other vendors kill background apps aggressively, and the
setting is per-vendor and easy to miss.

## Updates

**The phone app's update never appears.**

The phone app checks GitHub Releases at startup. Behind a captive network or with no internet, the
check fails silently by design — it is the only traffic outside the bridge and it carries no token.

**Android refuses to install the update.**

A self-built APK is signed with a different key than the releases, and Android only replaces an app
signed with the same key. Uninstall, then install the release.

**The desktop app says "New version X available" but does not install it.**

The `.deb` build cannot update itself. Open the link in the message and install the new package. The
AppImage and the Windows installer update themselves.

## The bridge itself

**The service will not start.**

```bash
systemctl --user status hermes-remote-bridge
journalctl --user -u hermes-remote-bridge -n 50
```

The most common cause: a saved network went away and the bridge is trying to rebind an address it
no longer has. It exits with status 75 when the listen set changed; the supervisor (or systemd)
restarts it. Status 76 means a port conflict, which is not retried; see above.

**Editing the unit file changed nothing.**

On a source install, the installed unit is a copy, not a symlink. Re-run `./install.sh`. The desktop
app writes its own unit; change the run mode in its Settings instead.

**I want to see everything the bridge is doing.**

`hermes-remote-bridge doctor`, then the audit log:

```bash
tail -f ~/.local/state/hermes-remote/audit.log
```

On Windows, the audit log is `%LOCALAPPDATA%\hermes-remote\state\audit.log`. It records device, path,
status and duration. It never contains tokens or prompts.

The bridge's own log is `bridge.log` in the state directory (`~/.local/state/hermes-remote` on Linux,
`%LOCALAPPDATA%\hermes-remote\state` on Windows). It is UTF-8 and rotates at 2 MB.

## Reporting a bug

Include `doctor` output, the relevant bridge log lines, the version of the app you are using (the
desktop app or the phone app), your operating system or Android version, and how to reproduce it.
Redact anything from a screenshot that you would not post publicly.
