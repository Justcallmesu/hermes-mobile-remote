"""FastAPI app: the only surface the Android client talks to.

Two kinds of endpoint:

* `/v1/ws`: the live connection. A relay to the JSON-RPC socket of the same `hermes serve`
  process the desktop app uses (relay.py), so the phone and the PC are two views of one Hermes.
* A few REST calls for what Hermes serves over HTTP rather than the socket: identity and
  addresses (`/v1/me`), health (`/v1/status`), the session sidebar list with its pinned flag,
  and the global approval mode.
"""
from __future__ import annotations

import asyncio
import contextlib
import json
import logging
import logging.handlers
import mimetypes
import os
import re
import socket
import time
from collections import defaultdict, deque
from typing import Any

import httpx
from fastapi import Depends, FastAPI, Request, WebSocket
from fastapi.exceptions import RequestValidationError
from fastapi.responses import FileResponse, JSONResponse
from pydantic import BaseModel, Field
from starlette.exceptions import HTTPException as StarletteHTTPException

from . import __version__
from .backend import SESSION_HEADER, Backend, BackendLocator, BackendUnavailable
from .config import Config
from .devices import Device, DeviceStore
from .network import TrustStore, network_info, serving_lan_ips
from .chat_files import referenced_file
from .project_notes import IDEA_MAX_BYTES, NoFolder, read_idea, write_idea
from .profiles import ProfileRefused, is_visible, media_roots, profile_home, resolve_profile
from .monitor import RANGES, ClaudeUsage, Monitor
from .relay import Relay
from .terminal import Shell
from .terminal import serve as serve_terminal
from .tailnet import TailnetClient, is_loopback, is_private_lan_ip, is_tailnet_ip

log = logging.getLogger("hermes_remote_bridge")

ID_RE = re.compile(r"^[A-Za-z0-9_.:-]{1,128}$")
APPROVAL_MODES = ("manual", "smart", "off")
# Images the phone may fetch for a chat (same set and cap as Hermes' own /api/media, minus SVG: script).
MEDIA_TYPES = {".png": "image/png", ".jpg": "image/jpeg", ".jpeg": "image/jpeg", ".gif": "image/gif",
               ".webp": "image/webp", ".bmp": "image/bmp"}
MEDIA_MAX_BYTES = 25 * 1024 * 1024
# Files a chat names (reports, spreadsheets, PDFs) the phone may download.
CHAT_FILE_MAX_BYTES = 100 * 1024 * 1024
# The wire contract between this bridge and the app. 2 = the WebSocket relay (v1.0.0).
PROTOCOL = 2


class ApiError(Exception):
    def __init__(self, status: int, code: str, message: str, **extra: Any):
        self.status, self.code, self.message, self.extra = status, code, message, extra


def _check_id(value: str, what: str) -> str:
    if not ID_RE.match(value):
        raise ApiError(400, "invalid_id", f"Invalid {what}")
    return value


# What the phone may do to a cron job. "trigger" is Hermes' word for "run now".
CRON_ACTIONS = ("pause", "resume", "trigger")
# The fields of a Hermes cron job the app shows. Prompts, scripts, delivery targets and paths stay
# on the PC: the phone needs to recognise a job and see how it is doing, not what it runs.
_CRON_JOB_FIELDS = ("id", "name", "schedule_display", "state", "enabled", "next_run_at", "last_run_at",
                    "last_status", "last_error", "failure_streak", "model")
_CRON_RUN_FIELDS = ("id", "title", "preview", "source", "started_at", "ended_at", "message_count", "model")


def _cron_job(job: dict) -> dict:
    return {k: job.get(k) for k in _CRON_JOB_FIELDS}


def _cron_run(run: dict) -> dict:
    return {k: run.get(k) for k in _CRON_RUN_FIELDS}


class PatchSession(BaseModel):
    title: str | None = Field(default=None, max_length=200)
    # Desktop-sidebar flags Hermes persists per session.
    pinned: bool | None = None
    archived: bool | None = None


class ProjectIdea(BaseModel):
    text: str = Field(default="", max_length=IDEA_MAX_BYTES)


class CreateCronJob(BaseModel):
    """A job made from the phone: a prompt on a schedule, delivered locally. Scripts, working
    folders, toolsets and delivery targets stay a PC-side choice."""
    name: str = Field(default="", max_length=120)
    schedule: str = Field(min_length=1, max_length=120)
    prompt: str = Field(min_length=1, max_length=20_000)
    paused: bool = False


class SetApprovalMode(BaseModel):
    mode: str = Field(max_length=16)


class SetDefaultModel(BaseModel):
    model: str = Field(min_length=1, max_length=200)
    provider: str = Field(default="", max_length=100)
    confirm_expensive_model: bool = False


class RateLimiter:
    def __init__(self) -> None:
        self._hits: dict[str, deque] = defaultdict(deque)

    def allow(self, key: str, limit: int, window: float = 60.0) -> bool:
        now = time.monotonic()
        hits = self._hits[key]
        while hits and now - hits[0] > window:
            hits.popleft()
        if len(hits) >= limit:
            return False
        hits.append(now)
        return True


def _audit_logger(cfg: Config) -> logging.Logger:
    cfg.audit_log.parent.mkdir(parents=True, exist_ok=True)
    logger = logging.getLogger(f"hermes_remote_bridge.audit.{cfg.audit_log}")
    logger.propagate = False
    if not logger.handlers:
        handler = logging.handlers.RotatingFileHandler(cfg.audit_log, maxBytes=2_000_000, backupCount=5)
        handler.setFormatter(logging.Formatter("%(message)s"))
        logger.addHandler(handler)
        logger.setLevel(logging.INFO)
    os.chmod(cfg.audit_log, 0o600)
    return logger


async def _hermes_cli(cfg: Config, *args: str) -> str:
    """Run the Hermes CLI with fixed argv (no shell) and return stdout; raise on failure."""
    proc = await asyncio.create_subprocess_exec(
        str(cfg.hermes_bin), *args,
        stdout=asyncio.subprocess.PIPE, stderr=asyncio.subprocess.PIPE)
    try:
        out, err = await asyncio.wait_for(proc.communicate(), timeout=20)
    except asyncio.TimeoutError:
        proc.kill()
        raise ApiError(504, "hermes_cli_timeout", "Hermes CLI did not answer in time")
    if proc.returncode != 0:
        raise ApiError(502, "hermes_cli_failed", (err or out).decode(errors="replace").strip()[-300:])
    return out.decode(errors="replace")


def create_app(cfg: Config, *, locator: BackendLocator | None = None, tailnet: TailnetClient | None = None,
               devices: DeviceStore | None = None, owner_login: str | None = None,
               hermes_cli=None, trust: TrustStore | None = None,
               http: httpx.AsyncClient | None = None, monitor: Monitor | None = None,
               shell_factory=None) -> FastAPI:
    if monitor is None and cfg.monitor:
        monitor = Monitor(cfg.monitor_db, ClaudeUsage(cfg.claude_credentials) if cfg.claude_usage else None)
    shell_factory = shell_factory or Shell
    locator = locator or BackendLocator(cfg.hermes_home, cfg.hermes_root, cfg.hermes_python, cfg.backend_log,
                                        spawn=cfg.start_hermes)
    cli = hermes_cli or (lambda *a: _hermes_cli(cfg, *a))
    tailnet = tailnet or TailnetClient()
    devices = devices or DeviceStore(cfg.devices_file)
    trust = trust or TrustStore(cfg.trust_file)
    http = http or httpx.AsyncClient(timeout=httpx.Timeout(15.0, connect=2.0))
    allowed_logins = set(cfg.allowed_logins) or ({owner_login} if owner_login else set())
    audit = _audit_logger(cfg)
    limiter = RateLimiter()

    def audit_event(entry: dict) -> None:
        audit.info(json.dumps({"ts": round(time.time(), 3), **entry}))

    @contextlib.asynccontextmanager
    async def lifespan(_: FastAPI):
        async def housekeeping() -> None:
            while True:
                await asyncio.sleep(60)
                with contextlib.suppress(Exception):
                    devices.flush_last_seen()

        task = asyncio.create_task(housekeeping())
        if monitor is not None:
            monitor.start()
        try:
            yield
        finally:
            task.cancel()
            if monitor is not None:
                monitor.stop()
            with contextlib.suppress(Exception):
                devices.flush_last_seen()
            await locator.aclose()
            await http.aclose()
            await tailnet.aclose()

    app = FastAPI(title="Hermes Remote Bridge", version=__version__, lifespan=lifespan,
                  docs_url=None, redoc_url=None, openapi_url=None)

    # ------------------------------------------------------------ error mapping

    def _err(status: int, code: str, message: str, **extra: Any) -> JSONResponse:
        return JSONResponse({"error": {"code": code, "message": message, **extra}}, status_code=status)

    @app.exception_handler(ApiError)
    async def _api_error(_: Request, exc: ApiError):
        return _err(exc.status, exc.code, exc.message, **exc.extra)

    @app.exception_handler(BackendUnavailable)
    async def _backend_down(_: Request, exc: BackendUnavailable):
        return _err(503, "hermes_unavailable", str(exc))

    @app.exception_handler(ProfileRefused)
    async def _profile_refused(_: Request, exc: ProfileRefused):
        return _err(exc.status, "invalid_profile" if exc.status == 400 else "profile_refused", str(exc))

    @app.exception_handler(RequestValidationError)
    async def _validation_error(_: Request, exc: RequestValidationError):
        fields = [".".join(str(p) for p in e.get("loc", [])[1:]) for e in exc.errors()]
        return _err(422, "invalid_request", "Request body failed validation", fields=fields)

    @app.exception_handler(StarletteHTTPException)
    async def _http_error(_: Request, exc: StarletteHTTPException):
        return _err(exc.status_code, "not_found" if exc.status_code == 404 else "http_error", str(exc.detail))

    @app.exception_handler(Exception)
    async def _unhandled(_: Request, exc: Exception):
        log.exception("unhandled error")
        return _err(500, "internal_error", "Internal bridge error")

    # ------------------------------------------------------------ network guard (HTTP and WS)

    async def peer_verdict(peer: str, scheme_secure: bool) -> tuple[str | None, tuple[int, str, str] | None]:
        """(peer node label, rejection) for a connecting address."""
        if is_loopback(peer):
            return "local", None
        if is_tailnet_ip(peer):
            who = await tailnet.whois(peer)
            if who is None or who["login"] not in allowed_logins:
                audit_event({"event": "peer_rejected", "peer": peer, "login": who and who["login"]})
                return None, (403, "forbidden_peer", "This tailnet identity is not allowed")
            return who["node"], None
        if cfg.lan and is_private_lan_ip(peer) and scheme_secure:
            # No tailnet identity exists on the LAN, so the device token is the only
            # authenticator. Logged distinctly so LAN use is always attributable.
            return f"lan:{peer}", None
        return None, (403, "forbidden_network", "Only tailnet peers may connect" if not cfg.lan
                      else "Only tailnet or local-network peers may connect")

    @app.middleware("http")
    async def guard(request: Request, call_next):
        start = time.monotonic()
        peer = request.client.host if request.client else ""
        request.state.device = None
        node, rejected = await peer_verdict(peer, request.url.scheme == "https")
        if rejected:
            return _err(*rejected)
        request.state.peer_node = node
        if request.method in ("POST", "PATCH", "PUT"):
            length = request.headers.get("content-length")
            if length is None and request.headers.get("transfer-encoding"):
                return _err(411, "length_required", "Chunked request bodies are not accepted")
            if length is not None and (not length.isdigit() or int(length) > cfg.max_body_bytes):
                return _err(413, "body_too_large", "Request body too large")
        response = await call_next(request)
        if request.url.path != "/healthz":
            device: Device | None = request.state.device
            audit_event({"peer": node, "device": device.name if device else None, "method": request.method,
                         "path": request.url.path, "status": response.status_code,
                         "ms": round((time.monotonic() - start) * 1000)})
        return response

    # ------------------------------------------------------------ device auth

    def _bearer(header: str) -> str:
        return header[7:].strip() if header.lower().startswith("bearer ") else ""

    async def device_auth(request: Request) -> Device:
        token = _bearer(request.headers.get("authorization", ""))
        device = devices.authenticate(token) if token else None
        if device is None:
            raise ApiError(401, "unauthorized", "Missing, invalid or revoked device token")
        request.state.device = device
        if not limiter.allow(f"req:{device.id}", cfg.requests_per_minute):
            raise ApiError(429, "rate_limited", "Too many requests")
        return device

    # ------------------------------------------------------------ Hermes' HTTP API

    async def hermes_http(method: str, path: str, **kw) -> Any:
        backend: Backend = await locator.get()
        try:
            r = await http.request(method, f"{backend.http}{path}", headers={SESSION_HEADER: backend.token}, **kw)
        except httpx.HTTPError as exc:
            locator.forget(backend)
            raise ApiError(503, "hermes_unavailable", f"Hermes did not answer: {type(exc).__name__}") from exc
        if r.status_code == 401:
            locator.forget(backend)  # a new backend took the port; resolve again next time
            raise ApiError(503, "hermes_unavailable", "Hermes restarted; try again")
        if r.status_code >= 400:
            detail = ""
            with contextlib.suppress(Exception):
                detail = str(r.json().get("detail") or "")
            raise ApiError(r.status_code if r.status_code < 500 else 502, "hermes_error",
                           detail or f"Hermes answered HTTP {r.status_code}")
        return r.json() if r.content else {}

    # ------------------------------------------------------------ meta

    @app.get("/healthz")
    async def healthz():
        return {"ok": True}

    async def _tailnet_ips() -> list[str]:
        try:
            return await tailnet.status_self_ips()
        except Exception:
            return []

    @app.get("/v1/me")
    async def me(request: Request, device: Device = Depends(device_auth)):
        """Identity, plus every address this bridge answers on so the client can offer a switch."""
        return {"device": {"id": device.id, "name": device.name, "created_at": device.created_at},
                "peer_node": request.state.peer_node,
                "via": "lan" if str(request.state.peer_node).startswith("lan:") else "tailnet",
                "port": cfg.port,
                "addresses": {"lan": await asyncio.to_thread(serving_lan_ips, cfg.lan, trust),
                              "tailnet": await _tailnet_ips()},
                "lan_scheme": "https",
                "network": await asyncio.to_thread(network_info, trust) if cfg.lan else None,
                "pc_name": socket.gethostname(),
                # What this bridge serves beyond chat, so the app hides pages an older or
                # restricted bridge would refuse.
                "features": [f for f, on in (("monitor", monitor is not None),
                                              ("claude_usage", monitor is not None and cfg.claude_usage),
                                              ("terminal", cfg.terminal)) if on],
                "bridge_version": __version__, "protocol": PROTOCOL}

    @app.get("/v1/status")
    async def status(_: Device = Depends(device_auth)):
        components: dict[str, Any] = {"bridge": {"status": "ok", "version": __version__, "protocol": PROTOCOL}}
        try:
            backend = await locator.get(start=False)
            info = await hermes_http("GET", "/api/status")
            components["hermes"] = {"status": "ok", "version": info.get("version"), "port": backend.port,
                                    "shared_with_desktop": not backend.started_here}
        except (BackendUnavailable, ApiError) as exc:
            components["hermes"] = {"status": "down", "error": getattr(exc, "message", str(exc)),
                                    "starts_on_connect": cfg.start_hermes}
        try:
            ts = await tailnet.status()
            components["tailscale"] = {
                "status": "ok" if ts.get("BackendState") == "Running" else ts.get("BackendState"),
                "host": ts["Self"].get("HostName"), "dns_name": ts["Self"].get("DNSName", "").rstrip("."),
            }
        except Exception:
            components["tailscale"] = {"status": "unreachable"}
        return {"components": components, "checked_at": time.time()}

    # ------------------------------------------------------------ Hermes profiles
    # One `hermes serve` serves every profile. A REST call names the one it means with
    # `?profile=`; leaving it out (or `default`) is the default profile, as before.

    async def profile_param(profile: str | None = None, _: Device = Depends(device_auth)) -> str | None:
        # Authenticate first, so an unpaired caller gets 401 and learns nothing about profile names.
        return resolve_profile(profile, cfg.allowed_profiles)

    def scoped(profile: str | None) -> dict[str, str]:
        """Query params that point a Hermes REST call at `profile`."""
        return {"profile": profile} if profile else {}

    def cli_scoped(profile: str | None, *args: str) -> tuple[str, ...]:
        """Hermes CLI argv for `profile` (its global `-p` flag)."""
        return ("-p", profile, *args) if profile else args

    @app.get("/v1/profiles")
    async def list_profiles(_: Device = Depends(device_auth)):
        """The profiles the phone may switch to, with what the picker shows for each."""
        data = await hermes_http("GET", "/api/profiles")
        rows = [p for p in data.get("profiles", []) if isinstance(p, dict) and isinstance(p.get("name"), str)]
        return {"profiles": [
            {"name": p["name"], "display_name": p.get("display_name") or "", "description": p.get("description") or "",
             "model": p.get("model") or "", "provider": p.get("provider") or "", "is_default": bool(p.get("is_default"))}
            for p in rows if is_visible(p["name"], cfg.allowed_profiles)]}

    # ------------------------------------------------------------ sessions (the sidebar list)

    @app.get("/v1/sessions")
    async def list_sessions(limit: int = 40, offset: int = 0, profile: str | None = Depends(profile_param),
                            _: Device = Depends(device_auth)):
        """The desktop sidebar's own query: newest activity first, pinned rows included."""
        params = {"limit": max(1, min(limit, 100)), "offset": max(0, offset), "min_messages": 0,
                  "archived": "exclude", "order": "recent", "exclude_sources": "cron", **scoped(profile)}
        data = await hermes_http("GET", "/api/sessions", params=params)
        return {"sessions": data.get("sessions", []), "total": data.get("total"),
                "limit": data.get("limit"), "offset": data.get("offset")}

    @app.patch("/v1/sessions/{session_id}")
    async def patch_session(session_id: str, body: PatchSession, profile: str | None = Depends(profile_param),
                            _: Device = Depends(device_auth)):
        payload = {k: v for k, v in body.model_dump().items() if v is not None}
        if not payload:
            raise ApiError(400, "empty_patch", "Provide at least one of: title, pinned, archived")
        # Hermes takes the owning profile in the body here, not the query.
        return await hermes_http("PATCH", f"/api/sessions/{_check_id(session_id, 'session id')}",
                                 json={**payload, **scoped(profile)})

    # ------------------------------------------------------------ cron jobs
    # Read, create (a prompt on a schedule), pause, resume and run now. Editing and deleting a job,
    # and jobs with scripts or other delivery targets, stay on the PC.

    @app.get("/v1/cron/jobs")
    async def list_cron_jobs(profile: str | None = Depends(profile_param), _: Device = Depends(device_auth)):
        return {"jobs": [_cron_job(j) for j in await cron_jobs_of(profile)]}

    async def cron_jobs_of(profile: str | None) -> list[dict]:
        # Always name the profile: without one Hermes lists every profile's jobs.
        data = await hermes_http("GET", "/api/cron/jobs", params={"profile": profile or "default"})
        rows = data if isinstance(data, list) else data.get("jobs", [])
        return [j for j in rows if isinstance(j, dict)]

    async def owned_cron_job(job_id: str, profile: str | None) -> str:
        """The job's id when it belongs to `profile`, else 404. Hermes treats the profile of a per-job
        call as a hint and falls back to searching every profile, which would let a phone reach a job
        in a profile `allowed_profiles` keeps it out of."""
        _check_id(job_id, "job id")
        if not any(j.get("id") == job_id for j in await cron_jobs_of(profile)):
            raise ApiError(404, "job_not_found", "No such cron job in this profile")
        return job_id

    @app.post("/v1/cron/jobs")
    async def create_cron_job(body: CreateCronJob, profile: str | None = Depends(profile_param),
                              device: Device = Depends(device_auth)):
        payload = {"name": body.name.strip(), "schedule": body.schedule.strip(), "prompt": body.prompt.strip(),
                   "deliver": "local", "paused": body.paused}
        job = await hermes_http("POST", "/api/cron/jobs", json=payload, params={"profile": profile or "default"})
        job = job.get("job", job) if isinstance(job, dict) else None
        audit_event({"event": "cron_create", "device": device.name, "profile": profile or "default",
                     "job": job.get("id") if isinstance(job, dict) else None})
        return {"job": _cron_job(job) if isinstance(job, dict) else None}

    @app.get("/v1/cron/jobs/{job_id}/runs")
    async def list_cron_runs(job_id: str, limit: int = 10, profile: str | None = Depends(profile_param),
                             _: Device = Depends(device_auth)):
        job_id = await owned_cron_job(job_id, profile)
        data = await hermes_http("GET", f"/api/cron/jobs/{job_id}/runs",
                                 params={"limit": max(1, min(limit, 50)), **scoped(profile)})
        rows = data.get("runs", []) if isinstance(data, dict) else data
        return {"runs": [_cron_run(r) for r in rows if isinstance(r, dict)]}

    @app.post("/v1/cron/jobs/{job_id}/{action}")
    async def cron_action(job_id: str, action: str, profile: str | None = Depends(profile_param),
                          device: Device = Depends(device_auth)):
        if action not in CRON_ACTIONS:
            raise ApiError(404, "not_found", f"Unknown cron action: {action}")
        job_id = await owned_cron_job(job_id, profile)
        job = await hermes_http("POST", f"/api/cron/jobs/{job_id}/{action}", params=scoped(profile))
        audit_event({"event": f"cron_{action}", "device": device.name, "profile": profile or "default",
                     "job": job_id})
        return {"job": _cron_job(job) if isinstance(job, dict) else None}

    # ------------------------------------------------------------ settings

    async def _approval_mode(profile: str | None) -> str:
        mode = (await cli(*cli_scoped(profile, "config", "get", "approvals.mode"))).strip().strip('"').lower()
        return mode if mode in APPROVAL_MODES else "manual"

    @app.get("/v1/settings/approvals")
    async def get_approvals(profile: str | None = Depends(profile_param), _: Device = Depends(device_auth)):
        return {"mode": await _approval_mode(profile), "modes": list(APPROVAL_MODES)}

    @app.put("/v1/settings/approvals")
    async def set_approvals(body: SetApprovalMode, profile: str | None = Depends(profile_param),
                            device: Device = Depends(device_auth)):
        if body.mode not in APPROVAL_MODES:
            raise ApiError(400, "invalid_mode", f"mode must be one of {list(APPROVAL_MODES)}")
        before = await _approval_mode(profile)
        if before != body.mode:
            await cli(*cli_scoped(profile, "config", "set", "approvals.mode", body.mode))
        after = await _approval_mode(profile)
        if after != body.mode:
            raise ApiError(502, "not_applied", f"Hermes still reports approvals.mode={after}")
        audit_event({"event": "approval_mode_changed", "device": device.name, "profile": profile or "default",
                     "from": before, "to": after})
        return {"mode": after, "modes": list(APPROVAL_MODES)}

    @app.get("/v1/settings/model")
    async def get_default_model(profile: str | None = Depends(profile_param), _: Device = Depends(device_auth)):
        info = await hermes_http("GET", "/api/model/info", params=scoped(profile))
        return {"model": info.get("model") or "", "provider": info.get("provider") or ""}

    @app.put("/v1/settings/model")
    async def set_default_model(body: SetDefaultModel, profile: str | None = Depends(profile_param),
                                device: Device = Depends(device_auth)):
        """The profile default for NEW chats (Hermes' own /api/model/set, scope=main).
        Open chats keep their model; they switch per session over the live connection."""
        res = await hermes_http("POST", "/api/model/set", params=scoped(profile), json={
            "scope": "main", "model": body.model, "provider": body.provider,
            "confirm_expensive_model": body.confirm_expensive_model})
        if res.get("confirm_required"):
            return {"confirm_required": True, "confirm_message": res.get("confirm_message") or "",
                    "model": body.model, "provider": body.provider}
        if res.get("ok") is False:
            raise ApiError(502, "not_applied", str(res.get("error") or res.get("detail") or "Hermes refused the model"))
        info = await hermes_http("GET", "/api/model/info", params=scoped(profile))
        audit_event({"event": "default_model_changed", "device": device.name, "profile": profile or "default",
                     "to": info.get("model")})
        return {"model": info.get("model") or body.model, "provider": info.get("provider") or body.provider}

    # ------------------------------------------------------------ media (images in a chat)

    @app.get("/v1/media")
    async def get_media(path: str, _: Device = Depends(device_auth)):
        """An image a chat shows (one the phone or the desktop attached, a screenshot, a generated
        image), by the path Hermes stored in the message. Only image files inside a served profile's
        images/screenshots/cache folders: never an arbitrary file from the PC."""
        try:
            target = await asyncio.to_thread(lambda: os.path.realpath(os.path.expanduser(path)))
        except (OSError, ValueError):
            raise ApiError(400, "invalid_path", "Invalid path")
        mime = MEDIA_TYPES.get(os.path.splitext(target)[1].lower())
        if mime is None:
            raise ApiError(415, "unsupported_media", "Not an image")
        roots = media_roots(cfg.hermes_home, cfg.allowed_profiles)
        if not any(target.startswith(root + os.sep) for root in roots):
            raise ApiError(403, "outside_media_roots", "Not one of Hermes' image folders")
        if not os.path.isfile(target):
            raise ApiError(404, "not_found", "Image not found")
        if os.path.getsize(target) > MEDIA_MAX_BYTES:
            raise ApiError(413, "too_large", "Image too large")
        return FileResponse(target, media_type=mime, headers={"Cache-Control": "private, max-age=86400"})

    @app.get("/v1/sessions/{session_id}/file")
    async def get_chat_file(session_id: str, path: str, profile: str | None = Depends(profile_param),
                            _: Device = Depends(device_auth)):
        """A file a chat names (a reply's `MEDIA:` line, or an `@file:` the user sent), for the
        phone to preview or save. Only paths that chat's own stored messages reference are served."""
        _check_id(session_id, "session id")
        home = profile_home(cfg.hermes_home, profile)
        target = await asyncio.to_thread(referenced_file, home, session_id, path)
        if target is None:
            raise ApiError(403, "not_in_chat", "This chat doesn't name that file")
        if not os.path.isfile(target):
            raise ApiError(404, "not_found", "File not found on the PC")
        if os.path.getsize(target) > CHAT_FILE_MAX_BYTES:
            raise ApiError(413, "too_large", "File too large to send to the phone")
        mime = mimetypes.guess_type(target)[0] or "application/octet-stream"
        return FileResponse(target, media_type=mime, filename=os.path.basename(target),
                            headers={"Cache-Control": "private, no-cache"})

    @app.get("/v1/projects/{project_id}/idea")
    async def get_project_idea(project_id: str, profile: str | None = Depends(profile_param),
                               _: Device = Depends(device_auth)):
        """The project's IDEA.md (its description, as the desktop's "New project" writes it)."""
        _check_id(project_id, "project id")
        try:
            return await asyncio.to_thread(read_idea, profile_home(cfg.hermes_home, profile), project_id)
        except NoFolder as exc:
            raise ApiError(404, "no_folder", str(exc))

    @app.put("/v1/projects/{project_id}/idea")
    async def put_project_idea(project_id: str, body: ProjectIdea, profile: str | None = Depends(profile_param),
                               _: Device = Depends(device_auth)):
        """Write (or, when empty, remove) IDEA.md in the project's own folder; no other path is writable."""
        _check_id(project_id, "project id")
        try:
            return await asyncio.to_thread(write_idea, profile_home(cfg.hermes_home, profile), project_id, body.text)
        except NoFolder as exc:
            raise ApiError(404, "no_folder", str(exc))
        except OSError as exc:
            raise ApiError(500, "write_failed", f"Could not write IDEA.md: {exc.strerror or exc}")

    # ------------------------------------------------------------ the live connection

    # ------------------------------------------------------------ PC monitor + Claude usage

    def need_monitor() -> Monitor:
        if monitor is None:
            raise ApiError(404, "monitor_off", "The PC monitor is turned off in the bridge's config.toml")
        return monitor

    async def off_loop(fn, *args):
        return await asyncio.get_running_loop().run_in_executor(None, fn, *args)

    @app.get("/v1/monitor/now")
    async def monitor_now(live: bool = False, _: Device = Depends(device_auth)):
        return await off_loop(need_monitor().now, live)

    @app.get("/v1/monitor/history")
    async def monitor_history(range: str = "1h", _: Device = Depends(device_auth)):  # noqa: A002 - query name
        if range not in RANGES:
            raise ApiError(400, "invalid_range", f"range must be one of {', '.join(RANGES)}")
        return await off_loop(need_monitor().history, range)

    @app.get("/v1/monitor/processes")
    async def monitor_processes(sort: str = "cpu", limit: int = 80, q: str = "", _: Device = Depends(device_auth)):
        return await off_loop(need_monitor().processes, sort, limit, q[:100])

    @app.post("/v1/monitor/processes/{pid}/stop")
    async def stop_process(pid: int, force: bool = False, device: Device = Depends(device_auth)):
        m = need_monitor()
        try:
            result = await off_loop(m.signal, pid, force)
        except PermissionError as exc:
            raise ApiError(403, "forbidden_process", str(exc) or "Not allowed to stop that process") from exc
        except Exception as exc:  # psutil.NoSuchProcess / AccessDenied
            name = type(exc).__name__
            if name == "NoSuchProcess":
                raise ApiError(404, "no_such_process", f"No process {pid}") from exc
            if name == "AccessDenied":
                raise ApiError(403, "forbidden_process", "That process belongs to another user") from exc
            raise
        audit_event({"event": "process_stop", "device": device.name, "pid": pid, "name": result["name"], "force": force})
        return result

    @app.get("/v1/claude/usage")
    async def claude_usage(refresh: bool = False, _: Device = Depends(device_auth)):
        m = need_monitor()
        return await off_loop(m.claude_poll) if refresh else m.claude_latest()

    @app.get("/v1/claude/history")
    async def claude_history(range: str = "24h", _: Device = Depends(device_auth)):  # noqa: A002
        if range not in RANGES:
            raise ApiError(400, "invalid_range", f"range must be one of {', '.join(RANGES)}")
        return await off_loop(need_monitor().claude_history, range)

    # ------------------------------------------------------------ live sockets

    async def ws_device(ws: WebSocket) -> tuple[str | None, Device | None]:
        """Same gate as HTTP: network, then the device token from the upgrade request's header."""
        peer = ws.client.host if ws.client else ""
        node, rejected = await peer_verdict(peer, ws.url.scheme == "wss")
        if rejected:
            await ws.close(code=4403, reason=rejected[2])
            return None, None
        # The token rides in the Authorization header (OkHttp sets it on the upgrade request),
        # never in the URL, so it stays out of every log line.
        token = _bearer(ws.headers.get("authorization", ""))
        device = devices.authenticate(token) if token else None
        if device is None:
            audit_event({"event": "ws_unauthorized", "peer": node, "path": ws.url.path})
            await ws.close(code=4401, reason="Missing, invalid or revoked device token")
            return node, None
        return node, device

    @app.websocket("/v1/terminal")
    async def terminal(ws: WebSocket, cols: int = 80, rows: int = 24):
        node, device = await ws_device(ws)
        if device is None:
            return
        if not cfg.terminal:
            await ws.close(code=4404, reason="The terminal is turned off in the bridge's config.toml")
            return
        await ws.accept()
        shell = shell_factory(cols=max(10, min(cols, 500)), rows=max(4, min(rows, 300)))
        await serve_terminal(ws, shell, audit=lambda e: audit_event({**e, "peer": node, "device": device.name}))

    @app.websocket("/v1/ws")
    async def live(ws: WebSocket):
        node, device = await ws_device(ws)
        if device is None:
            return
        frames_key = f"frames:{device.id}:{id(ws)}"
        relay = Relay(locator, max_frame_bytes=cfg.max_frame_bytes,
                      allow_frame=lambda: limiter.allow(frames_key, cfg.frames_per_minute),
                      audit=lambda e: audit_event({**e, "peer": node}),
                      allowed_profiles=cfg.allowed_profiles)
        await relay.run(ws, device.name)

    app.state.locator = locator
    return app
