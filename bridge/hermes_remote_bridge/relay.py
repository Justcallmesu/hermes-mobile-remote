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
from typing import Any, Awaitable, Callable, NamedTuple

import websockets
from starlette.websockets import WebSocket, WebSocketDisconnect, WebSocketState

from .backend import BackendLocator, BackendUnavailable
from .metrics import BridgeMetrics
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
    # subagents the open chat delegated to: the roster (to catch up mid-run), stopping one, steering
    # one and reading its live transcript tail. Hermes only answers for sessions this socket is
    # attached to.
    "subagent.list", "subagent.interrupt", "subagent.steer", "subagent.tail",
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
})

# config.set keys the phone may change: the chat's model, reasoning and fast mode.
ALLOWED_CONFIG_KEYS = frozenset({"model", "reasoning", "fast", "yolo"})
# Steering text is an instruction folded into a child's next tool round, not a document: a cap well
# under the frame limit keeps a pasted log from bloating the child's context.
MAX_STEER_CHARS = 4000
_SERVER_REQUEST_ID = re.compile(r"^srq-[0-9a-f]{6,32}$")

CLOSE_UNAVAILABLE = 1013  # try again later: Hermes is starting or gone
CLOSE_POLICY = 1008
CLOSE_TOO_BIG = 1009


def _error(rid: Any, code: int, message: str) -> str:
    return json.dumps({"jsonrpc": "2.0", "id": rid, "error": {"code": code, "message": message}})


class Verdict(NamedTuple):
    """What to do with one phone→Hermes frame: forward it, answer it, or drop it (and why)."""
    forward: str | None
    reply: str | None
    refused: str | None = None  # a fixed reason, for the metrics; never frame content
    frame: dict | None = None


def inspect_frame(raw: str, max_bytes: int, allowed_profiles: Iterable[str] = ()) -> Verdict:
    """Validate one phone→Hermes frame."""
    if len(raw.encode()) > max_bytes:
        return Verdict(None, _error(None, -32600, "Frame too large"), "too_large")
    try:
        frame = json.loads(raw)
    except ValueError:
        return Verdict(None, _error(None, -32700, "Not JSON"), "not_json")
    if not isinstance(frame, dict) or frame.get("jsonrpc") != "2.0":
        return Verdict(None, _error(None, -32600, "Not a JSON-RPC 2.0 frame"), "not_jsonrpc")
    rid = frame.get("id")
    method = frame.get("method")
    if method is None:
        # A response: only to a question Hermes asked (approval, clarify).
        if isinstance(rid, str) and _SERVER_REQUEST_ID.match(rid) and ("result" in frame or "error" in frame):
            return Verdict(raw, None, frame=frame)
        return Verdict(None, None, "stray_response")  # nothing to answer a stray response with
    if not isinstance(method, str) or method not in ALLOWED_METHODS:
        return Verdict(None, _error(rid, -32601, f"Method not available from the phone: {method}"), "method")
    params = frame.get("params")
    if isinstance(params, dict) and params.get("profile") is not None:
        try:
            resolve_profile(params["profile"] if isinstance(params["profile"], str) else "?", allowed_profiles)
        except ProfileRefused as exc:
            return Verdict(None, _error(rid, -32602, str(exc)), "profile")
    if method == "config.set":
        key = (params or {}).get("key") if isinstance(params, dict) else None
        if key not in ALLOWED_CONFIG_KEYS:
            return Verdict(None, _error(rid, -32602, f"config key not available from the phone: {key}"), "config_key")
    if method == "subagent.steer" and (problem := _steer_text_problem(params)):
        return Verdict(None, _error(rid, -32602, problem), "steer_text")
    return Verdict(raw, None, frame=frame)


def check_frame(raw: str, max_bytes: int, allowed_profiles: Iterable[str] = ()) -> tuple[str | None, str | None]:
    """(frame to forward, error reply to send back) for one phone→Hermes frame."""
    verdict = inspect_frame(raw, max_bytes, allowed_profiles)
    return verdict.forward, verdict.reply


def _steer_text_problem(params: Any) -> str | None:
    text = params.get("text") if isinstance(params, dict) else None
    if not isinstance(text, str) or not text.strip():
        return "subagent.steer needs text"
    if len(text) > MAX_STEER_CHARS:
        return f"steer text longer than {MAX_STEER_CHARS} characters"
    return None


class Relay:
    """One phone connection ↔ one Hermes connection."""

    def __init__(self, locator: BackendLocator, *, max_frame_bytes: int,
                 allow_frame: Callable[[], bool], audit: Callable[[dict], None],
                 allowed_profiles: Iterable[str] = (), metrics: BridgeMetrics | None = None):
        self.locator = locator
        self.metrics = metrics or BridgeMetrics("", 0)
        self.max_frame_bytes = max_frame_bytes
        self.allow_frame = allow_frame
        self.audit = audit
        self.allowed_profiles = frozenset(allowed_profiles)

    async def run(self, phone: WebSocket, device_name: str, device_id: str | None = None) -> None:
        try:
            backend = await self.locator.get()
        except BackendUnavailable as exc:
            self.metrics.ws_rejected.inc(endpoint="live", reason="hermes_unavailable")
            await phone.close(code=CLOSE_UNAVAILABLE, reason=str(exc)[:120])
            return
        try:
            upstream = await websockets.connect(backend.ws, max_size=None, ping_interval=20, ping_timeout=20,
                                                open_timeout=10, close_timeout=2, compression=None)
        except (OSError, websockets.WebSocketException, asyncio.TimeoutError) as exc:
            log.warning("could not reach Hermes on port %d: %s", backend.port, exc)
            self.locator.forget(backend)
            self.metrics.ws_rejected.inc(endpoint="live", reason="hermes_unavailable")
            await phone.close(code=CLOSE_UNAVAILABLE, reason="Hermes is not answering")
            return
        await phone.accept()
        self.audit({"event": "ws_open", "device": device_name, "backend_port": backend.port})
        self.metrics.socket_opened("live", device_id)
        stats = self.metrics.connection()
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
                    stats.refused("rate_limited")
                    await phone.send_text(_error(None, -32000, "Too many requests"))
                    continue
                verdict = inspect_frame(raw, self.max_frame_bytes, self.allowed_profiles)
                if verdict.refused:
                    stats.refused(verdict.refused)
                if verdict.reply:
                    await phone.send_text(verdict.reply)
                if verdict.forward:
                    method = verdict.frame.get("method")
                    if isinstance(method, str):
                        methods[method] = methods.get(method, 0) + 1
                    stats.from_phone(verdict.frame)
                    await upstream.send(verdict.forward)

        async def hermes_to_phone() -> None:
            async for raw in upstream:
                text = raw if isinstance(raw, str) else raw.decode(errors="replace")
                stats.from_hermes(text)
                await phone.send_text(text)

        tasks = [asyncio.create_task(phone_to_hermes()), asyncio.create_task(hermes_to_phone())]
        reason = "phone left"
        try:
            done, _ = await asyncio.wait(tasks, return_when=asyncio.FIRST_COMPLETED)
            for t in done:
                exc = t.exception()
                if t is tasks[1]:
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
            stats.close()
            self.metrics.socket_closed("live", device_id)
            with contextlib.suppress(BaseException):
                await asyncio.gather(*tasks, return_exceptions=True)
                await upstream.close()
                if (phone.application_state != WebSocketState.DISCONNECTED
                        and phone.client_state != WebSocketState.DISCONNECTED):
                    await phone.close(code=1012 if reason.startswith("hermes") else 1000)


AuditFn = Callable[[dict], None]
GuardFn = Callable[[WebSocket], Awaitable[str | None]]
