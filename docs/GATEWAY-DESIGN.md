# Direct gateway login: design

Status: **built (app code, JVM tests, APK builds); not verified on a phone or against a signed-in gateway.** Branch `feature/gateway-login` (from `origin/main`, v1.17.0). Not pushed.

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
| UI | "Add host" offers *Pair a bridge* / *Sign in to a gateway* (URL field -> Custom Tab). Gateway hosts hide: cron, project notes (IDEA.md), chat file download, bridge status card, desktop handoff. |
| `Tailnet` host check | Applies to bridge pairing. For a gateway, allow `http` only for tailnet/LAN hosts (same rule), require `https` for anything else, never accept bare public `http`. |
| Background | Foreground service holds the socket only while a phone-started turn runs or waits on a question, then stops. Reuse `WatchService` logic if it fits; otherwise a follow-up. |

No bridge changes. `CHANGELOG.md`, `docs/FEATURES.md`, `docs/ARCHITECTURE.md` get a short section when it ships.

## Features by host kind (first cut)

| Feature | Bridge | Gateway |
|---|---|---|
| Chat, streaming, tools, approvals, clarify | yes | yes (same socket RPC) |
| Slash commands, model/reasoning, sessions, pin/rename | yes | yes |
| Session list, profiles | `/v1/*` | `/api/*` equivalents |
| Project IDEA.md, chat files, status card | yes | **hidden** (bridge-only; chat files have a gateway `/api/files/download`, shape not read) |
| Cron (Scheduled) | `/v1/cron/*` | yes, via the gateway's own `/api/cron/jobs` |
| Vault (`vault.*` RPC over the socket) | yes | yes (Hermes' own RPC, not a bridge feature) |
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

## Verified wire facts

Sources: **live** = `curl` against WorkLab (`http://100.65.118.116:9119`, Hermes 0.22.2, 2026-10-26, no
credentials used); **src** = local Hermes checkout v0.22.5 (WorkLab is 0.22.2, so a `src` claim is
one minor version ahead of the live host). Paths below are under `hermes_cli/`. **unknown** = not
confirmed; the app must not rely on it.

| # | Claim | Status | Source |
|---|---|---|---|
| W1 | `GET /api/status` is public (no login). Reports `auth_required: true`, `auth_providers: ["basic"]`, `auth_flows: ["cookie", "native_pkce"]`, plus `version`, `gateway_running`, `profiles: ["default"]`. The app can use `auth_flows` to tell a gated gateway from an open one. | verified | live; `dashboard_auth/public_paths.py` |
| W2 | `GET /api/auth/providers` -> `{"providers":[{"name":"basic","display_name":"Username & Password","supports_password":true}]}`. | verified | live; `dashboard_auth/routes.py:203` |
| W3 | `GET /auth/native/authorize` needs `code_challenge`, `code_challenge_method=S256`, `redirect_uri`, `state`; `provider` optional (auto-picked when only one). Missing params -> 400. `redirect_uri` must be `http://127.0.0.1:<port>[/path]` or `::1`, canonical; `localhost` -> 400. A password provider answers `302 -> <base>/login` and rides a PKCE cookie that holds the broker state (so the Custom Tab must keep its cookies through the login). | verified | live (400 without params, 400 for `localhost`, 302 to `/login` for a valid request); `routes.py:242-336` |
| W4 | After login the gateway redirects the browser to `<redirect_uri>?code=<gw_code>&state=<client state>`. Code TTL 120 s, single use; the whole pending login lasts 600 s. Tokens never go through the browser. | verified (src); the `/login` form itself was not submitted | `dashboard_auth/native_flow.py:25-30`, `routes.py:119-133` |
| W5 | `POST /auth/native/token` JSON `{code, code_verifier}` -> `{access_token, refresh_token, token_type:"Bearer", expires_at (unix s), provider, user_id}`. Any failure is `400 {"detail":"Invalid or expired authorization code."}`; the code is spent even on a wrong verifier. | verified (failure path live, success shape src) | live; `routes.py:111-117,574-591` |
| W6 | `POST /auth/native/refresh` JSON `{refresh_token, provider?}` -> same shape as W5 (rotated). Rejected token -> `401 {"error":"session_expired","detail":...}`. Provider unreachable -> `503`. Empty token -> 400. Concurrent calls are coalesced server-side too. | verified (401 live, 200 and 503 src) | live; `routes.py:594-617` |
| W7 | REST calls carry `Authorization: Bearer <access_token>`. A missing credential gives `401 {"error":"unauthenticated","reason":"no_cookie","login_url":"/login"}`; a presented but bad/expired bearer gives `401 {"error":"session_expired","reason":"invalid_or_expired_session"}`. Provider down -> 503. Note that two different 401 bodies exist and only `session_expired` means "refresh now". | verified (no-credential live, rest src) | live; `dashboard_auth/middleware.py:72-86,175-184` |
| W8 | `POST /api/auth/ws-ticket` (Bearer) -> `{"ticket": "...", "ttl_seconds": 30}`; single use; unauthenticated -> 401. | verified (401 live, shape src) | live; `routes.py:556-571`, `ws_tickets.py` |
| W9 | `/api/ws` upgrade in gated mode accepts, in order: `?internal=` (server only), a ticket in the WS subprotocol, `?ticket=`, or `?token=<access token>` (a provider-verified session access token, used by Hermes' own token-mode remote desktop). `?ticket=` is what the design uses. Nothing else is required. | verified (src), not exercised live | `web_server_chat.py:200-300` |
| W10 | `GET /api/sessions` (Bearer) -> `{sessions:[...], total, limit, offset, storage}`; query `limit<=100, offset, min_messages, archived=exclude|only|include, order=created|recent, exclude_sources, source, sources, cwd_prefix, full, profile`. Rows carry `id, title, source, model, started_at, last_active, message_count`, plus `pinned`, `archived` (JSON booleans), `is_active`, `profile`, `is_default_profile`. Same shape the bridge's `/v1/sessions` passes through. | verified (src) | `web_routers/sessions.py:182-245` |
| W11 | `PATCH /api/sessions/{id}` body takes any of `title, archived, hidden, pinned, unread`, plus `profile`, `request_id`, `expected_revision`, `expected_generation`; goes through a revisioned mutation. The bridge sends the owning profile in the body. | verified (src) | `web_routers/sessions.py:926-935` |
| W12 | `GET /api/profiles` -> `{"profiles":[{name, display_name, description, model, provider, is_default, ...}]}` (same fields the bridge reads). | verified (src) | `web_routers/profiles.py:807,801`; bridge `app.py:373` |
| W13 | The gateway serves chat images itself: `GET /api/media?path=<abs path>` (Bearer) -> `{"data_url":"data:image/...;base64,..."}`; image extensions only, size cap, restricted to the agent's media roots (403/404/413/415 otherwise). Remote image URLs on an allowlist of hosts go via `/api/media/proxy`. | verified (src) | `web_routers/files.py:336-380` |
| W14 | Files: `GET /api/files`, `/api/files/read`, `/api/files/download`, `/api/files/stream` exist and are Bearer-gated. Their parameters and root restrictions were not read. | unknown (exist, shape not read) | `web_routers/files.py:515-626` |
| W15 | `POST /auth/logout` redirects to `/login` and is cookie oriented; whether it accepts a Bearer or a refresh token to revoke a native session is not established. | unknown | `routes.py:496-510` |
| W16 | The `/login` form's password POST (`/auth/password-login`) and its rate limit were not exercised; no credentials were used. | unknown | `routes.py:393-488` |

### Unknowns from the design, resolved

1. **Reachable?** Yes, over Tailscale (W1, W2). The `basic` provider is gated and native PKCE is advertised. The sign-in step itself has not been run from a phone: ticket 05/07 does that.
2. **Routes and shapes:** W10-W12, taken from the dashboard routes, not the bridge's `/v1`.
3. **Method allowlist:** unchanged; a direct socket has no allowlist (the gateway's login is the only boundary). SECURITY.md must say so (ticket 07).
4. **Loopback redirect blocked by the browser:** still open. Only a phone test can show it (ticket 07 checklist). Fallback if it fails: paste the `code` by hand, which the server already supports (the code is just a query value).

### Design corrections

- **Images:** the design says gateway hosts hide `/v1/media` pictures. The gateway has `/api/media` returning a data URL (W13), so pictures can work. Ticket 06 says "placeholder instead"; change that to "load via `/api/media`" unless the chat image path proves too costly (decide with the maintainer before ticket 06).
- **Chat files:** `/api/files/download` exists (W14). It stays hidden in the first cut because its shape is unread.
- **401 handling (ticket 04/06):** only `error: "session_expired"` should trigger a refresh. `error: "unauthenticated"` with no bearer sent is a bug, not a refresh case. A refresh that returns 401 `session_expired` is the sign-out case.
- **Socket credential:** `?token=<access token>` also works on `/api/ws` (W9), which would skip the ticket call. Keep the ticket (shorter-lived secret in a URL), but it is a fallback if the ticket call misbehaves.
- **Vault is not bridge-only:** it is Hermes' own `vault.list/sources/add/remove` RPC (`tui_gateway/methods_vault.py`); the bridge only allowlists it. A direct socket has it, so a gateway host shows Vault. **Cron** has gateway routes too (`/api/cron/jobs`, which the bridge wraps); ticket 08 serves the Scheduled page from them, so gateway hosts have it.
- **Version skew:** source claims are from v0.22.5, WorkLab runs 0.22.2. Re-check W10-W13 against WorkLab once signed in.


## Background-work signals

What the phone can learn about work a session still owns, so the foreground service knows when to stay up
(`docs/fix-background-work-dies-on-app-close.md`). Source: Hermes `tui_gateway/` at the version on the
planner's PC (0.22.x), read with `rg`; no live gateway call was made, so every entry is **source-verified,
not run**. "Bridge" says whether `bridge/hermes_remote_bridge/relay.py` `ALLOWED_METHODS` lets the phone call it.

### What Hermes itself keeps alive (so the phone need not)

`session_lifecycle.py:937-940`: when the 20 s orphan-reap timer fires, a detached session is kept (re-checked
every grace) if it has live async delegations (`_session_has_active_delegations`, `:772`) or an active
`/loop` or `/heartbeat` that only this process fires (`session_notifications.py:273`
`_session_owns_live_wakeup_schedule`). A running turn is deferred while its activity clock is fresh
(`_ws_orphan_turn_activity_is_fresh`), else interrupted once. **Plain background processes
(`terminal background=true`) are in neither list**: an idle detached session with only those is reaped, which
is what the 2026-10-28 test showed. So the phone must keep its socket for: a turn that looks quiet, and
background processes. Delegations and loops survive a detach on the server, but the phone still wants to know
for the notification and to resume.

### Signals per kind

| Kind | Method / event | Fields | Bridge | Status |
|---|---|---|---|---|
| Running turn | `session.active_list` (`methods_session.py:1143`) | per live session `status` = idle / starting / waiting / working / streaming / resuming (`contracts/sessions.py:64`, computed by `server.py:3041` `_session_live_status`: waiting = a question is open, working = `running`), `session_key`, `last_active` | yes (`relay.py:35`) | source-verified |
| Running turn (push) | events `message.start`, `message.complete` (already used by `LiveLink`) | runtime `session_id` | n/a (event) | in use today |
| Live children of `delegate_task` | `subagent.list` (`methods_subagents.py`, `contracts/profiles_vault_complete_foreign_subagents.py:666`) | `subagents[]` live children owned by the session | yes (`relay.py:47`) | source-verified |
| Async delegations | **no live signal found.** `subagent.list` `delegations` holds only recently *failed* tasks; async completion units "are not agents" (`tui_gateway/AGENTS.md`). `has_live_for_session` (`tools/async_delegation.py:694`) is server-internal | none | none | **cannot detect** |
| `/loop`, `/heartbeat` | `session.control.read` (`contracts/config_free_tier_control.py:700`): `control.loop` / `control.heartbeat` with `status`, `interval_seconds`, `ticks_fired`; push event `session.control.update` (`contracts/events.py:450`); `status.update` with `kind` loop/heartbeat | `status` (active vs paused/stopped) | **no**: `session.control.read` is not in `ALLOWED_METHODS`; the push event reaches the phone only while attached | source-verified; needs a bridge allowlist entry |
| Background processes | `process.list` (`methods_tools.py:281`), rows with `status`, `uptime_seconds`, `output_tail`, `exit_code` (`contracts/tools_commands.py:91`); `status.update` `kind=process` | per session, scoped by Hermes | yes (`relay.py:50`) | source-verified |

### Recommendation for tickets 03 and 04

- **Busy = any of:** `session.active_list` status other than `idle` for a watched session; a non-empty
  `subagent.list.subagents`; a `process.list` row with `status == "running"`; a loop or heartbeat whose
  `status` is active when `session.control.update` has been seen.
- **Poll** `session.active_list` plus `process.list` for watched sessions every 30 s while backgrounded and
  attached. The 10 s `ping` of ticket 04 already proves the socket is alive; these two calls are in-memory reads
  on the Hermes side (not measured). Events cover the gaps between polls.
- **Loop/heartbeat over a bridge:** the phone cannot call `session.control.read` today. Either ticket 03 relies
  on the pushed `session.control.update` only, or the bridge allowlist gains `session.control.read` (one line in
  `relay.py`, read-only). Prefer the allowlist line; it is a separate small change in ticket 05's file.
- **Resume** replays running state: `session.resume` returns `running` and `status` (`contracts/sessions.py:86-89`),
  so a reattach recovers "busy" without a separate call.

### Cannot detect

- Live async delegations (no RPC). Mitigation: Hermes keeps such a session alive on its own; the phone treats
  "a watched session was working before the drop" as busy for the linger time, then resumes it.
- A turn that is `working` but silent for a long time (the Claude subscription provider waits 30 s+): the phone
  sees `working`, but Hermes' own stale-activity check may still interrupt it once the socket is gone, which is
  why the socket must stay up (ticket 03) rather than rely on the server's keep-alive.
