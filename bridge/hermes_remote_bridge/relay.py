"""The phone's live connection: a WebSocket relayed to Hermes' own JSON-RPC socket.

The desktop app renders `hermes serve`'s `/api/ws`; the phone gets the very same socket, one hop
further. Every agent event (streamed text, tools, titles, the sidebar's working state) reaches
both screens from the one process, so there is nothing to sync: a chat is live wherever it is
open. Approvals and clarify questions arrive as server→client requests on the same socket and
whichever screen answers first wins.

The bridge adds only what a socket leaving the PC needs: the device token, the network guard,
a method allowlist (the RPCs the app actually uses, not the whole desktop surface), a frame size
cap and a rate limit. Frames from Hermes are forwarded untouched.
"""
from __future__ import annotations

import asyncio
import contextlib
import json
import logging
import re
from collections.abc import Iterable
from typing import Any, Awaitable, Callable

import websockets
from starlette.websockets import WebSocket, WebSocketDisconnect, WebSocketState

from .backend import BackendLocator, BackendUnavailable
from .profiles import ProfileRefused, resolve_profile

log = logging.getLogger(__name__)

# What the phone may call. Everything here is something the desktop app does from its chat view.
ALLOWED_METHODS = frozenset({
    "ping", "client.capabilities",
    # sessions
    "session.list", "session.active_list", "session.most_recent", "session.resume", "session.activate",
    "session.create", "session.close", "session.history", "session.events.since", "session.title",
    "session.delete", "session.undo", "session.branch", "session.compress", "session.usage", "session.status",
    "session.save", "session.workspace.move", "session.context_breakdown",
    # projects: the drawer's group-by-project, plus "New project", rename, folder and delete from the
    # phone (delete drops Hermes' saved project only; files on the PC are never touched)
    "projects.tree", "projects.get", "projects.create", "projects.update", "projects.delete",
    "projects.add_folder", "projects.remove_folder", "projects.set_primary",
    # turns
    "prompt.submit", "prompt.btw", "prompt.background", "session.interrupt", "session.steer", "session.redirect",
    # subagents the open chat delegated to: the roster (to catch up mid-run) and stopping one. Hermes
    # only answers for sessions this socket is attached to.
    "subagent.list", "subagent.interrupt",
    # background processes the open chat started (terminal background=true): list with output tail,
    # and stop one. Scoped by Hermes to the caller's session; the registry-wide kill stays on the PC.
    "process.list", "process.kill",
    # slash commands, models, settings
    "slash.exec", "command.dispatch", "command.resolve", "commands.catalog", "complete.slash",
    "config.get", "config.set", "model.options",
    # questions from the agent
    "approval.respond", "approval.pending", "approval.received", "clarify.lock",
    # attachments
    "image.attach_bytes", "image.detach", "file.attach",
    # the vault page: list (metadata only, never a secret), add, remove. Unlocking a password manager
    # (its master password) stays on the PC.
    "vault.list", "vault.sources", "vault.add", "vault.remove",
    # read-only state of the open chat's /loop and /heartbeat (background work signals)
    "session.control.read",
})

# config.set keys the phone may change: the chat's model, reasoning and fast mode.
ALLOWED_CONFIG_KEYS = frozenset({"model", "reasoning", "fast", "yolo"})
_SERVER_REQUEST_ID = re.compile(r"^srq-[0-9a-f]{6,32}$")

CLOSE_UNAVAILABLE = 1013  # try again later: Hermes is starting or gone
CLOSE_POLICY = 1008
CLOSE_TOO_BIG = 1009


def _error(rid: Any, code: int, message: str) -> str:
    return json.dumps({"jsonrpc": "2.0", "id": rid, "error": {"code": code, "message": message}})


def check_frame(raw: str, max_bytes: int, allowed_profiles: Iterable[str] = ()) -> tuple[str | None, str | None]:
    """Validate one phone→Hermes frame. Returns (frame to forward, error reply to send back)."""
    if len(raw.encode()) > max_bytes:
        return None, _error(None, -32600, "Frame too large")
    try:
        frame = json.loads(raw)
    except ValueError:
        return None, _error(None, -32700, "Not JSON")
    if not isinstance(frame, dict) or frame.get("jsonrpc") != "2.0":
        return None, _error(None, -32600, "Not a JSON-RPC 2.0 frame")
    rid = frame.get("id")
    method = frame.get("method")
    if method is None:
        # A response: only to a question Hermes asked (approval, clarify).
        if isinstance(rid, str) and _SERVER_REQUEST_ID.match(rid) and ("result" in frame or "error" in frame):
            return raw, None
        return None, None  # nothing to answer a stray response with
    if not isinstance(method, str) or method not in ALLOWED_METHODS:
        return None, _error(rid, -32601, f"Method not available from the phone: {method}")
    params = frame.get("params")
    if isinstance(params, dict) and params.get("profile") is not None:
        try:
            resolve_profile(params["profile"] if isinstance(params["profile"], str) else "?", allowed_profiles)
        except ProfileRefused as exc:
            return None, _error(rid, -32602, str(exc))
    if method == "config.set":
        key = (params or {}).get("key") if isinstance(params, dict) else None
        if key not in ALLOWED_CONFIG_KEYS:
            return None, _error(rid, -32602, f"config key not available from the phone: {key}")
    return raw, None


class _Live:
    """A phone connection that is running now; `replaced` is set when the same device connects again."""

    def __init__(self) -> None:
        self.replaced = asyncio.Event()
        self.done = asyncio.Event()


class ParkedUpstreams:
    """Hermes sockets kept open, per device, for a while after the phone's socket dropped.

    Hermes reaps a session 20 s after its socket detaches. Holding the upstream socket for
    `park_seconds` lets a phone that comes back (app reopened, network change) re-attach to the
    same one. Frames Hermes sends meanwhile are dropped: the phone's `session.resume` rebuilds state.
    """

    def __init__(self, park_seconds: float = 0, *, sleep: Callable[[float], Awaitable[Any]] = asyncio.sleep,
                 audit: Callable[[dict], None] = lambda e: None):
        self.park_seconds = park_seconds
        self._sleep = sleep
        self._audit = audit
        self._parked: dict[str, tuple[Any, str, list[asyncio.Task]]] = {}
        self.live: dict[str, _Live] = {}

    @property
    def enabled(self) -> bool:
        return self.park_seconds > 0

    def park(self, device: str, upstream: Any, ready: str) -> None:
        self._discard(device)

        async def expire() -> None:
            await self._sleep(self.park_seconds)
            self._audit({"event": "ws_park_expired", "device": device})
            self._discard(device)

        async def drain() -> None:
            with contextlib.suppress(Exception):
                async for _ in upstream:
                    pass
            self._discard(device)  # Hermes closed (or errored) while parked

        self._parked[device] = (upstream, ready, [asyncio.create_task(expire()), asyncio.create_task(drain())])

    def claim(self, device: str) -> tuple[Any, str] | None:
        entry = self._parked.pop(device, None)
        if entry is None:
            return None
        for t in entry[2]:
            t.cancel()
        return entry[0], entry[1]

    def _discard(self, device: str) -> None:
        entry = self._parked.pop(device, None)
        if entry is not None:
            for t in entry[2]:
                t.cancel()
            asyncio.get_running_loop().create_task(_close_quietly(entry[0]))

    async def close_all(self) -> None:
        entries, self._parked = list(self._parked.values()), {}
        for upstream, _, tasks in entries:
            for t in tasks:
                t.cancel()
            await _close_quietly(upstream)


async def _close_quietly(upstream: Any) -> None:
    with contextlib.suppress(BaseException):
        await upstream.close()


class Relay:
    """One phone connection ↔ one Hermes connection."""

    def __init__(self, locator: BackendLocator, *, max_frame_bytes: int,
                 allow_frame: Callable[[], bool], audit: Callable[[dict], None],
                 allowed_profiles: Iterable[str] = (), parked: ParkedUpstreams | None = None):
        self.locator = locator
        self.max_frame_bytes = max_frame_bytes
        self.allow_frame = allow_frame
        self.audit = audit
        self.allowed_profiles = frozenset(allowed_profiles)
        self.parked = parked or ParkedUpstreams(0)

    async def run(self, phone: WebSocket, device_name: str) -> None:
        try:
            backend = await self.locator.get()
        except BackendUnavailable as exc:
            await phone.close(code=CLOSE_UNAVAILABLE, reason=str(exc)[:120])
            return
        parked = self.parked
        old = parked.live.get(device_name)
        if old is not None:
            # Same device again: the old phone side ends, and its upstream is parked for us below.
            old.replaced.set()
            with contextlib.suppress(asyncio.TimeoutError):
                await asyncio.wait_for(old.done.wait(), 5)
        claimed = parked.claim(device_name)
        ready: str | None = None
        if claimed is not None:
            upstream, ready = claimed
        else:
            try:
                upstream = await websockets.connect(backend.ws, max_size=None, ping_interval=20, ping_timeout=20,
                                                    open_timeout=10, close_timeout=2, compression=None)
            except (OSError, websockets.WebSocketException, asyncio.TimeoutError) as exc:
                log.warning("could not reach Hermes on port %d: %s", backend.port, exc)
                self.locator.forget(backend)
                await phone.close(code=CLOSE_UNAVAILABLE, reason="Hermes is not answering")
                return
        await phone.accept()
        live = parked.live[device_name] = _Live()
        if claimed is not None:
            self.audit({"event": "ws_reattach", "device": device_name})
        self.audit({"event": "ws_open", "device": device_name, "backend_port": backend.port})
        methods: dict[str, int] = {}

        async def phone_to_hermes() -> None:
            while True:
                msg = await phone.receive()
                if msg["type"] == "websocket.disconnect":
                    return
                raw = msg.get("text")
                if raw is None and msg.get("bytes") is not None:
                    raw = msg["bytes"].decode(errors="replace")
                if raw is None:
                    continue
                if not self.allow_frame():
                    await phone.send_text(_error(None, -32000, "Too many requests"))
                    continue
                forward, reply = check_frame(raw, self.max_frame_bytes, self.allowed_profiles)
                if reply:
                    await phone.send_text(reply)
                if forward:
                    method = _method_of(forward)
                    if method:
                        methods[method] = methods.get(method, 0) + 1
                    await upstream.send(forward)

        async def hermes_to_phone() -> None:
            nonlocal ready
            if ready is not None:
                await phone.send_text(ready)  # Hermes sent its gateway.ready to the first phone only
            async for raw in upstream:
                text = raw if isinstance(raw, str) else raw.decode(errors="replace")
                if ready is None and "gateway.ready" in text and _is_ready(text):
                    ready = text
                await phone.send_text(text)

        tasks = [asyncio.create_task(phone_to_hermes()), asyncio.create_task(hermes_to_phone()),
                 asyncio.create_task(live.replaced.wait())]
        reason = "phone left"
        try:
            done, _ = await asyncio.wait(tasks, return_when=asyncio.FIRST_COMPLETED)
            for t in done:
                exc = t.exception()
                if t is tasks[2]:
                    reason = "replaced by a new connection"
                elif t is tasks[1]:
                    reason = "hermes closed" if exc is None else f"hermes error: {type(exc).__name__}"
                elif exc is not None and not isinstance(exc, WebSocketDisconnect):
                    reason = f"phone error: {type(exc).__name__}"
        except asyncio.CancelledError:
            reason = "bridge shutting down"
            raise
        finally:
            # Synchronous bookkeeping first: the awaits below may be cancelled (shutdown, the
            # server dropping the task), and the audit line must be written regardless.
            for t in tasks:
                t.cancel()
            if reason.startswith("hermes"):
                # The backend went away (desktop closed it, update, crash): drop the cached one so
                # the phone's reconnect attaches to, or starts, whatever runs next.
                self.locator.forget(backend)
            self.audit({"event": "ws_close", "device": device_name, "reason": reason, "methods": methods})
            keep = (parked.enabled and ready is not None
                    and not reason.startswith("hermes") and reason != "bridge shutting down")
            if keep:
                parked.park(device_name, upstream, ready)
                self.audit({"event": "ws_park", "device": device_name})
            if parked.live.get(device_name) is live:
                del parked.live[device_name]
            live.done.set()
            with contextlib.suppress(BaseException):
                await asyncio.gather(*tasks, return_exceptions=True)
                if not keep:
                    await upstream.close()
                if (phone.application_state != WebSocketState.DISCONNECTED
                        and phone.client_state != WebSocketState.DISCONNECTED):
                    await phone.close(code=1012 if reason.startswith("hermes") else 1000)


def _is_ready(raw: str) -> bool:
    try:
        return json.loads(raw).get("params", {}).get("type") == "gateway.ready"
    except (ValueError, AttributeError):
        return False


def _method_of(raw: str) -> str | None:
    try:
        m = json.loads(raw).get("method")
    except ValueError:
        return None
    return m if isinstance(m, str) else None


AuditFn = Callable[[dict], None]
GuardFn = Callable[[WebSocket], Awaitable[str | None]]
