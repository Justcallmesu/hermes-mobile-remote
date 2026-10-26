# Direct gateway login: design

Status: **draft, not built.** Branch `feature/gateway-login` (from `origin/main`, v1.17.0). Not pushed.

## Goal

Let the phone sign in to a *gated* remote Hermes gateway (`hermes serve` behind its own auth, the
same thing Hermes Desktop calls a "Remote gateway") and chat with it directly, with no bridge and
no PC-side install. The bridge path stays as it is.

## Decisions (made with the maintainer)

| # | Decision |
|---|---|
| D1 | A host is **either** a bridge (pair by QR) **or** a gateway (sign in). Both live in the existing multi-PC list; the chat UI is shared. |
| D2 | First cut = sign-in (RFC 8252 native flow, Custom Tab, token storage + refresh) + chat over the gateway's WebSocket. Features that exist only in the bridge are hidden for gateway hosts. |
| D3 | Test target: WorkLab `http://100.65.118.116:9119` over Tailscale (basic-auth provider). |
| D4 | Docs first, wait for a build signal. Nothing pushed. |

## Why this is cheap

The bridge's `/v1/ws` is a filtered relay of Hermes' own `/api/ws` (`relay.py`: frames from Hermes
are forwarded untouched). `data/Gateway.kt` already speaks that JSON-RPC (calls, events,
server-to-client requests, `client.capabilities`, reconnect). So direct mode mostly changes
**how the socket URL and credential are obtained**, not the chat code.

## Wire protocol (from the Hermes source notes in the `hermes-agent` skill; verify before building)

1. `GET /api/status` is public. `GET /api/auth/providers` lists providers (`basic` = user/password).
2. Browser step: open `GET /auth/native/authorize?code_challenge=<S256>&code_challenge_method=S256&redirect_uri=http://127.0.0.1:<port>/cb&state=<s>[&provider=<p>]` in a Custom Tab. `redirect_uri` must be a loopback IP literal. The password is typed into the gateway's `/login` page, never into the app.
3. A one-shot listener on `127.0.0.1:<random port>` receives `?code=&state=` (code TTL 120 s). Check `state`.
4. `POST /auth/native/token {code, code_verifier}` -> access + refresh tokens (JSON). Any failure is a generic 400; the code is spent either way.
5. `POST /auth/native/refresh {refresh_token, provider}` rotates. 401 `session_expired` -> sign in again. 503 -> retry later, do **not** sign out.
6. API calls carry `Authorization: Bearer <access>`.
7. Socket: `POST /api/auth/ws-ticket` -> `{ticket, ttl_seconds: 30}`, single use. Connect `ws(s)://<base>/api/ws?ticket=<ticket>` (the upgrade ignores the `Authorization` header). **A fresh ticket for every (re)connect.**
8. After `gateway.ready`, send `client.capabilities {"server_requests": true}` (Gateway.kt already does).
9. Build every URL from the configured base so a reverse-proxy path prefix works.
10. `POST /auth/logout` on sign-out.

## Android changes

| Piece | Change |
|---|---|
| `data/Profile` / `ProfileStore` | `Profile` gains `kind` (`bridge` \| `gateway`), default `bridge` so existing installs are untouched. |
| `data/GatewayAuth.kt` (new) | Pure Kotlin: PKCE (verifier, S256 challenge), authorize-URL builder, loopback listener, token/refresh calls over an injected HTTP interface. JVM-tested. |
| `data/GatewayCredentialStore.kt` (new) | Same Keystore AES-GCM scheme as `CredentialStore`; stores base URL, access, refresh, expiry. Excluded from backup like the bridge pairing. |
| `data/Gateway.kt` | Take a `urlProvider: suspend () -> String` (and an auth-failure hook) instead of a fixed `url`. Bridge mode returns the fixed URL; gateway mode mints a ticket each attempt. Close code / HTTP 401 -> refresh once, then `UNAUTHORIZED`. |
| Refresh | One in-flight refresh at a time (coalesced): rotating refresh tokens detect reuse. |
| REST client | A `GatewayRestClient` for the few `/api/*` calls the shared UI needs (sessions list, session PATCH, profiles, status). The bridge's `/v1/*` handlers are thin wrappers over these (`hermes_http` in `app.py`), so the paths and params are the starting point. |
| UI | "Add host" offers *Pair a bridge* / *Sign in to a gateway* (URL field -> Custom Tab). Gateway hosts hide: Vault, cron, project notes (IDEA.md), chat file download, bridge status card, desktop handoff. |
| `Tailnet` host check | Applies to bridge pairing. For a gateway, allow `http` only for tailnet/LAN hosts (same rule), require `https` for anything else, never accept bare public `http`. |
| Background | Foreground service holds the socket only while a phone-started turn runs or waits on a question, then stops. Reuse `WatchService` logic if it fits; otherwise a follow-up. |

No bridge changes. `CHANGELOG.md`, `docs/FEATURES.md`, `docs/ARCHITECTURE.md` get a short section when it ships.

## Features by host kind (first cut)

| Feature | Bridge | Gateway |
|---|---|---|
| Chat, streaming, tools, approvals, clarify | yes | yes (same socket RPC) |
| Slash commands, model/reasoning, sessions, pin/rename | yes | yes |
| Session list, profiles | `/v1/*` | `/api/*` equivalents |
| Cron, Vault, project IDEA.md, chat files, `/v1/media` images, status card | yes | **hidden** (bridge-only) |
| Notifications while app closed | yes | follow-up |

Open question: images inside chats come through `/v1/media`. The gateway may serve media itself;
if not, pictures from a gateway host are a known gap.

## Unknowns to resolve first (spike, then tickets)

1. Is WorkLab reachable and what does `/api/auth/providers` return? (From this PC, `curl` to `100.65.118.116:9119` returned nothing, so Tailscale was off or the host was down: check before the spike.) The `basic` provider's native flow has not been exercised from a phone.
2. Exact `/api/*` routes and response shapes for sessions/profiles on a gated gateway (read `hermes_cli` routes; do not assume the bridge's `/v1` shapes).
3. Does `ALLOWED_METHODS` (bridge allowlist) hide RPCs the app relies on that a direct socket also exposes? Direct mode has **no method allowlist**, so the app is the only guard: the gateway's own auth is the boundary. Say so in SECURITY.md.
4. Custom Tab redirect on a phone whose browser blocks loopback redirects: need a fallback (manual code paste?).

## Security notes

- The password never enters the app; tokens live in Keystore-backed storage.
- Direct mode drops the bridge's device pinning, rate limits and method allowlist. The trust boundary is the gateway's login plus the network (Tailscale/LAN). Plain `http` is acceptable only on tailnet/LAN, as today.
- Logout calls `/auth/logout` and wipes local tokens.

## Proposed ticket order (for `to-tickets`, after grilling)

1. Spike: probe WorkLab, document the real `/api/*` and auth responses in this file (planner/researcher, no app code).
2. `GatewayAuth` pure Kotlin + JVM tests (PKCE vectors computed with a real tool, not by hand).
3. `GatewayCredentialStore` + `Profile.kind` migration.
4. `Gateway.kt` URL provider + ticket per connect + refresh coalescing, JVM tests with a fake socket.
5. Android glue: Custom Tab, loopback listener activity/service, manifest entries, Add-host screen.
6. REST client + feature gating in the UI.
7. Docs, changelog, `docs/gateway-manual-checks.md` (real-phone checklist: sign in, chat, approval, airplane-mode reconnect, token expiry, logout).
