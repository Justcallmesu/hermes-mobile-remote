# Hermes Mobile Remote

<img src="demo/screenshots/03-chat-answered.png" width="240" align="right" alt="A chat answered by the agent running on the PC">

<p align="right"><img src="demo/demo.gif" width="240" alt="A run streaming back to the phone: a tool call, then the reply"></p>

An Android app for [Hermes Agent](https://hermes-agent.nousresearch.com). The phone is another
window of the Hermes your desktop app is running: the same sessions, the same live turns, streamed
token by token on both screens. Send, steer and stop, answer approvals and questions, switch models
and reasoning, and use every slash command — while Hermes keeps running on your own PC, with its
full toolset.

The app never talks to Hermes directly on a bridge host. A small bridge runs next to Hermes, admits
only the phones you paired, and relays them to the same backend socket the desktop uses. A phone can
also sign in straight to a gated Hermes gateway with no bridge; see [gateway hosts](docs/FEATURES.md#gateway-hosts).

```
Android app ── trusted Wi-Fi: HTTPS, pinned certificate ──┐
           └── anywhere else: Tailscale (optional) ───────┴─► bridge :8650 ─ loopback ─► hermes serve
                                                                                 (shared with the desktop app)
```

**1.0 is a breaking change.** A 1.x bridge needs a 1.x app and vice versa. Update both: install the
desktop app on the PC (or re-run `./install.sh` for a Linux source install), then update the phone app
from its System tab.

MIT licensed. See [CONTRIBUTING.md](CONTRIBUTING.md) before opening a pull request.

## Install

On the PC, install **Hermes Remote**, the desktop app. It sets up the bridge, runs it, and shows its
status and the pairing QR code. Download it from the [Releases](https://github.com/nideta231/hermes-mobile-remote/releases)
page; desktop builds are tagged `desktop-v<version>`:

- **Windows 10/11** (experimental): the `.exe` installer. It installs per user, with no admin rights.
  It is unsigned, so SmartScreen warns: choose **More info**, then **Run anyway**.
- **Linux**: the `.AppImage` or the `.deb`.

Source install on Linux, without the desktop app:

```bash
git clone https://github.com/nideta231/hermes-mobile-remote.git
cd hermes-mobile-remote
./install.sh          # Linux (systemd)
```

macOS is not supported yet. See [docs/PLATFORMS.md](docs/PLATFORMS.md).

On the phone: install the APK from [Releases](https://github.com/nideta231/hermes-mobile-remote/releases/latest),
then pair it on the PC with **Pair a device** in the desktop app, and scan the QR code in the app. On a
Linux source install, `bridge/.venv/bin/hermes-remote-bridge pair phone` prints the QR code instead.

Full walkthrough, including what the installer changes and how to remove it:
**[docs/INSTALL.md](docs/INSTALL.md)**.

## Try it without installing Hermes

The repository ships a demo stack: a fake Hermes backend with scripted, synthetic data, plus a bridge
wired to it. No API keys, no model calls, and nothing private can leak into a screenshot.

```bash
(cd bridge && uv sync)
./demo/run-demo.sh --pair
```

See **[demo/README.md](demo/README.md)**.

## Documentation

Each document has one job.

| Document | What it covers |
|---|---|
| [docs/INSTALL.md](docs/INSTALL.md) | Installing the bridge, pairing a phone, removing it |
| [docs/PLATFORMS.md](docs/PLATFORMS.md) | Linux, Windows, macOS: what is supported, verified and missing |
| [docs/FEATURES.md](docs/FEATURES.md) | Every feature, how it behaves, and its limits |
| [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) | How the app, bridge and Hermes fit together |
| [docs/SECURITY.md](docs/SECURITY.md) | Threat model and the guarantees the bridge makes |
| [docs/ROADMAP.md](docs/ROADMAP.md) | What is planned, and what is deliberately not |
| [docs/TROUBLESHOOTING.md](docs/TROUBLESHOOTING.md) | Symptoms, causes, fixes |
| [docs/DEVELOPMENT.md](docs/DEVELOPMENT.md) | Building, testing and releasing |
| [docs/BRIDGE_API.md](docs/BRIDGE_API.md) | The REST + WebSocket contract the app codes against |
| [CONTRIBUTING.md](CONTRIBUTING.md) | The rules for contributions |
| [SECURITY.md](SECURITY.md) | Reporting a vulnerability |
| [demo/README.md](demo/README.md) | The demo stack and how to record screenshots |

## Requirements

| PC | Phone |
|---|---|
| Linux with systemd (Arch-based, KDE), or Windows 10/11 (experimental) | Android 8.0+ |
| [Hermes Agent](https://hermes-agent.nousresearch.com) installed | |
| [uv](https://docs.astral.sh/uv/) (source installs; the desktop app downloads it if missing) | |
| Optional: NetworkManager, Avahi, Tailscale | Optional: Tailscale |

## Status

Actively used on my own phone and tablet. Version numbers come from git tags; see
[docs/ROADMAP.md](docs/ROADMAP.md) for what is finished and what is still open.

## License

[MIT](LICENSE)
