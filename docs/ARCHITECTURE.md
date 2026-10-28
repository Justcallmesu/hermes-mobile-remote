# Architecture

Four pieces, with one rule: on a bridge host the phone never holds a credential that can reach Hermes
directly. Gateway hosts are the exception; see [Direct gateway hosts](#direct-gateway-hosts).

```
┌──────────────┐  WebSocket + REST, HTTPS  ┌──────────────┐   WebSocket, loopback   ┌──────────────┐
│ Android app  │ ────────────────────────► │    bridge    │ ──────────────────────► │ hermes serve │
│              │  (pinned cert) or inside  │  (your PC)   │   the desktop's own     │  127.0.0.1   │
│ 1 token      │ ◄──────────────────────── │ checks token │ ◄────────────────────── │  (shared)    │
└──────────────┘        WireGuard          └──────────────┘                         └──────────────┘
                                                                                        ▲
                                                                     Hermes desktop app ┘
```

| Piece | Lives in | Responsibility |
|---|---|---|
| App | `android/` | UI, one device token, TLS pinning, reconnect, rendering Hermes' events |
| Bridge | `bridge/` | Device auth, network policy, finding the backend, relaying the socket |
| Hermes | your install | The agent, the tools, the model calls, the session store, the live turns |
| Desktop app | `desktop/` | Tray or window on Windows and Linux. Starts and stops the bridge, runs first-run setup, handles pairing, devices, trusted networks and doctor. Reaches the bridge only through `/v1/local/*` |

## Why a bridge at all

Hermes' backend trusts whoever holds its session token and listens on loopback only: anything
that can reach it can drive an agent with your tools. A phone on cafe Wi-Fi cannot be given that.

So the token stays on the PC, behind a process that can say no. The bridge exposes a narrower
surface on the network and authenticates each device separately. Compromise of the phone means a
revoked device token, not access to Hermes' own credentials.

## Why one shared backend

Before 1.0 the bridge drove Hermes' HTTP runs API and kept its own run bookkeeping, so the phone
and the desktop were two different clients of two different APIs: phone runs reached the desktop
late, desktop runs reached the phone by polling, and every send paid a setup cost.

Since 1.0 the phone uses the same backend and the same socket protocol as the desktop app. Hermes
broadcasts every session's events to every attached client, routes approval and clarify questions
to any client that announced it can answer them, and withdraws a question answered elsewhere. Live
sync between the two screens is therefore Hermes' own behaviour, not something the bridge
reimplements.

## The bridge

A single FastAPI application (`bridge/hermes_remote_bridge/app.py`). One codebase, two listeners:

- **Plain HTTP** on loopback and the node's Tailscale addresses. Inside WireGuard the transport is
  already encrypted and authenticated, so there is nothing to add.
- **HTTPS** on the Wi-Fi address, but only on a network you marked trusted, with a self-signed
  certificate the app pins. Plain HTTP from the LAN is refused.

It refuses to bind `0.0.0.0` or `::`, and watches the network: when the set of addresses it
should serve changes (new Wi-Fi, new DHCP lease, Tailscale up or down, trust granted or revoked) it
restarts its listeners. Otherwise a network change would silently leave the phone unable to reach
it.

### Request pipeline

Every request passes three gates, in this order:

1. **Where from.** The peer address must be loopback, a tailnet address whose `whois` login is
   allowed, or — with LAN enabled — a private address on the HTTPS listener. Anything else is
   `403 forbidden_network`.
2. **How big.** Chunked bodies are refused; bodies over 1 MB are `413`. This runs before
   authentication so an oversized upload is rejected without doing work for the caller.
3. **Who.** `Authorization: Bearer <device token>`, matched against the SHA-256 of the token.
   Unknown or revoked is `401` (on the socket: close `4401`). Per-device rate limits apply after
   this (240 requests/min; 600 frames/min on a live connection).

Successful requests are appended to an audit log with device, path, status and duration — never
tokens or prompts.

### Running it: `supervise`

`hermes-remote-bridge supervise` runs `serve` and keeps it up. A network change restarts it after 2 s. A crash restarts it with a back-off that grows when failures repeat, up to 60 s. A port conflict stops the supervisor instead of retrying. It stops, together with its child, on SIGTERM or Ctrl+C, or when the desktop app closes its stdin. Its log is `bridge.log` in the state directory, rotated at 2 MB.

The desktop app decides how the bridge runs. On Linux, *Always on* runs it as the systemd user service `hermes-remote-bridge.service`. *Only while the app is open* stops that service and runs `supervise` as a child of the app. On Windows the app runs `supervise` inside a Job Object, so the bridge ends with the app.

### Finding Hermes (`backend.py`)

The bridge reads Hermes' spawn ledger (`~/.hermes/spawn-ledger.json`), the same file the desktop
app reads to attach to a running backend, and takes the newest live `serve` entry of the default
profile. The backend's session token is read from the page it serves its own renderer. If nothing
is running and `start_hermes` is on, the bridge starts `hermes serve` on loopback; it registers in
the same ledger, so a desktop opened later joins it instead of starting a second one.

### The relay (`relay.py`)

`/v1/ws` is relayed frame for frame to the backend's `/api/ws`. The bridge parses each frame from
the phone just enough to enforce policy: a method allowlist (what the desktop's chat view calls),
`config.set` limited to the chat's model, reasoning and fast mode, replies to the agent's questions
only by their `srq-` id, a frame size cap and a per-connection frame rate. Frames from Hermes pass
through untouched. Each connection is audited on open and close with per-method counts.

### The local control API

`/v1/local/*` exists for the desktop app. It answers only a loopback peer that sends the local secret in `X-Hermes-Local`, in place of the device token. It is never in the relay's allowlist and has no device rate limit. Its routes cover status, devices, pairing, revoking, trusted networks and doctor. The routes are listed in [BRIDGE_API.md](BRIDGE_API.md#local-control-api-desktop-app), and the secret is covered in [SECURITY.md](SECURITY.md#guarantees).

## Direct gateway hosts

A gateway host skips the bridge. The app signs in to a gated `hermes serve` itself, using the native
PKCE flow (RFC 8252), and then talks to the gateway with its own bearer token:

- **Sign-in:** the gateway's login page opens in a Chrome Custom Tab. The gateway redirects to a
  one-shot listener on `127.0.0.1` (random port), which receives a single-use code and checks the
  `state`. The app exchanges the code for access and refresh tokens. The password never reaches the app.
- **Socket:** before each connection the app asks the gateway for a single-use ticket and connects to
  `ws(s)://<gateway>/api/ws?ticket=…`. A `401 session_expired` triggers one refresh and a retry.
- **REST:** sessions, pin and rename, profiles, status and chat pictures (`/api/media`) over `/api/*`,
  with the bearer token.

Here the phone does hold a credential for the gateway, so the rule above does not apply. The
gateway's login and the network are the boundary; no bridge sits in front of it. The Kotlin pieces are
`data/GatewaySignIn.kt`, `data/GatewayAuth.kt`, `data/GatewayRest.kt` and `data/GatewayCredentialStore.kt`.
Security trade-offs are in [SECURITY.md](SECURITY.md#gateway-hosts).

## The desktop app

Hermes Remote is a Tauri v2 app (Rust, with Vue 3 and Vite) in `desktop/`, for Windows and Linux. On Windows it lives in the tray and opens a flyout. On Linux it is a normal window with an optional tray icon. It starts and stops the bridge, runs first-run setup, and manages pairing, devices, trusted networks and doctor. It reaches the running bridge through the [local control API](BRIDGE_API.md#local-control-api-desktop-app). When the bridge is not running, it shows saved devices and doctor output from the CLI instead.

The Rust side is in `desktop/src-tauri/src/`, and the Vue screens are in `desktop/src/`. Which systems it runs on and how far each is verified is in [PLATFORMS.md](PLATFORMS.md).

## The app

Kotlin and Jetpack Compose, one activity, two pages (`CHAT`, `SETTINGS`) swapped with
`AnimatedContent`, plus the sessions pane. On a phone the session list is a modal drawer; at
720 dp and up it is a fixed 320 dp pane beside the chat.

| Concern | Where |
|---|---|
| REST transport, pinning, error mapping | `data/BridgeClient.kt`, `data/CertPin.kt` |
| The live socket: JSON-RPC calls, events, questions, reconnect (URL from the bridge or a gateway ticket) | `data/Gateway.kt` |
| One socket for the whole process, background notifications | `data/LiveLink.kt`, `data/WatchService.kt`, `data/Notifier.kt` |
| Which path to use, LAN vs Tailscale | `data/EndpointResolver.kt` |
| Folding Hermes' events into chat items | `data/Chat.kt` (`LiveReducer`) |
| Hermes' stored transcript into chat items | `data/Chat.kt` (`HistoryMapper`) |
| Token storage (Android Keystore): bridge pairing | `data/CredentialStore.kt` |
| Token storage (Android Keystore): gateway tokens | `data/GatewayCredentialStore.kt` |
| State, slash commands, models | `AppViewModel.kt` |
| UI | `ui/ChatScreen.kt`, `ui/Screens.kt`, `ui/Components.kt` |

Two rules shape this code. The device token is only ever sent to a tailnet host, or to a LAN host
over a pinned TLS handshake: an interceptor re-checks that on every request and on the socket
upgrade, so a misconfigured endpoint cannot quietly downgrade to cleartext. This is the bridge rule.
A gateway host accepts `https` anywhere and plain `http` only for tailnet or LAN addresses
(`GatewayUrl.validate`). And the app never
invents state Hermes already has: a chat is shown by `session.resume` (transcript, turn in flight,
open questions) and then kept current by Hermes' own events, the way the desktop does it.

Streamed text is applied to the list at most once per frame and revealed at a steady pace, so a
burst of tokens does not make the list re-layout per token.

## Data on the phone

The bridge token is encrypted with an Android Keystore key and excluded from backups. Gateway tokens
(access and refresh) are stored the same way, in a separate preferences file per gateway host, and are
also excluded from backups. Drafts are stored
per session. Session history is not cached in full: the app asks for the tail of a session and
keeps what it has rendered.

## One bridge, one OS layer

The bridge started on Linux and every OS-specific thing it needed (service manager, network
identity, Tailscale socket, firewall, mDNS, the trust prompt) was written against Linux. Those now
sit behind one interface in `bridge/hermes_remote_bridge/host/`, with a module per operating
system. The rest of the code asks the host a question ("which interface has the default route?")
and never checks the platform, so the security model above is the same code on every OS.

The Windows host reads routes, the router, the connection profile and the firewall state through Windows APIs (ctypes and comtypes). It starts no PowerShell.

Which operating systems exist, how far each is verified, and how to add one:
[PLATFORMS.md](PLATFORMS.md).
