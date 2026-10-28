"""In-memory counters for `GET /metrics`: what the bridge does, never what it carries.

A small registry rather than prometheus_client: the bridge needs three metric types, one text
format and a JSON view for the CLI, and every extra dependency ships to the user's PC.

Labels are always from a closed set (route templates, allowlisted RPC methods, fixed reasons and
outcomes), so a client cannot grow the registry and nothing personal can end up in a label: no
paths, ids, titles, prompts, device names or tokens. Values reset when the bridge restarts.
"""
from __future__ import annotations

import json
import math
import os
import re
import secrets
import threading
import time
from collections import OrderedDict
from collections.abc import Iterable
from pathlib import Path
from typing import Any

CONTENT_TYPE = "text/plain; version=0.0.4; charset=utf-8"

LATENCY_BUCKETS = (0.005, 0.01, 0.025, 0.05, 0.1, 0.25, 0.5, 1.0, 2.5, 5.0, 10.0, 30.0)
# A human reading a question on a phone: seconds to an hour.
WAIT_BUCKETS = (1.0, 5.0, 10.0, 30.0, 60.0, 120.0, 300.0, 600.0, 1800.0, 3600.0)

# Server→client requests a person answers (tui_gateway/contracts/server_requests.py). Window-owned
# reads (preview.read, terminal.read, ...) are answered by software and are not counted.
QUESTION_METHODS = frozenset({"approval", "clarify", "sudo", "secret",
                              "vault.unlock_prompt", "vault.save_login", "vault.code"})
# `request.cancel` reasons Hermes sends; anything newer is reported as "other".
CANCEL_REASONS = frozenset({"resolved", "timeout", "interrupted", "shutdown"})
TURN_STATUSES = frozenset({"complete", "interrupted", "error"})
HTTP_METHODS = frozenset({"GET", "HEAD", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"})
_SERVER_REQUEST_ID = re.compile(r"^srq-[0-9a-f]{6,32}$")
# Substrings that make a Hermes→phone frame worth decoding when no phone RPC is in flight.
_MARKERS = ('"srq-', "message.start", "message.complete", "request.cancel")
# Bounds on per-process bookkeeping, so a misbehaving peer cannot grow memory.
MAX_PENDING_RPCS = 512
MAX_OPEN_QUESTIONS = 256
_SEEN_EVENTS = 2048


METRICS_TOKEN_PREFIX = "hrm_"


def read_metrics_token(path: Path) -> str | None:
    """The local metrics token, or None when `serve` has not made one (metrics then need a device token)."""
    try:
        token = path.read_text().strip()
    except OSError:
        return None
    return token if token.startswith(METRICS_TOKEN_PREFIX) and len(token) >= 32 else None


def ensure_metrics_token(path: Path) -> str:
    """Make the metrics token once (mode 600) and keep it across restarts, so a scraper's
    `bearer_token_file` and the CLI keep working. It opens `/metrics` and nothing else."""
    token = read_metrics_token(path)
    if token:
        return token
    token = METRICS_TOKEN_PREFIX + secrets.token_urlsafe(32)
    path.parent.mkdir(parents=True, exist_ok=True)
    fd = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
    with os.fdopen(fd, "w") as fh:
        fh.write(token + "\n")
    return token


def _escape(value: str) -> str:
    return value.replace("\\", "\\\\").replace("\n", "\\n").replace('"', '\\"')


def _num(value: float) -> str:
    if math.isinf(value):
        return "+Inf" if value > 0 else "-Inf"
    return repr(float(value)) if not float(value).is_integer() else str(int(value))


def _labels(names: tuple[str, ...], values: tuple[str, ...], extra: str = "") -> str:
    parts = [f'{n}="{_escape(v)}"' for n, v in zip(names, values)]
    if extra:
        parts.append(extra)
    return "{" + ",".join(parts) + "}" if parts else ""


class _Metric:
    kind = ""

    def __init__(self, name: str, help_: str, labels: Iterable[str], lock: threading.Lock):
        self.name, self.help, self.labelnames = name, help_, tuple(labels)
        self._lock = lock
        self._values: dict[tuple[str, ...], Any] = {}

    def _key(self, labels: dict[str, str]) -> tuple[str, ...]:
        if set(labels) != set(self.labelnames):
            raise ValueError(f"{self.name} takes labels {self.labelnames}, got {sorted(labels)}")
        return tuple(str(labels[n]) for n in self.labelnames)

    def header(self) -> list[str]:
        return [f"# HELP {self.name} {self.help}", f"# TYPE {self.name} {self.kind}"]


class Counter(_Metric):
    kind = "counter"

    def inc(self, amount: float = 1.0, **labels: str) -> None:
        key = self._key(labels)
        with self._lock:
            self._values[key] = self._values.get(key, 0.0) + amount

    def get(self, **labels: str) -> float:
        with self._lock:
            return self._values.get(self._key(labels), 0.0)

    def lines(self) -> list[str]:
        # An unlabelled series reads 0 before its first event, so rate() and alerts see it at once.
        values = self._values or ({(): 0.0} if not self.labelnames else {})
        return [f"{self.name}{_labels(self.labelnames, k)} {_num(v)}" for k, v in sorted(values.items())]

    def json(self) -> list[dict]:
        return [{"labels": dict(zip(self.labelnames, k)), "value": v} for k, v in sorted(self._values.items())]


class Gauge(Counter):
    kind = "gauge"

    def set(self, value: float, **labels: str) -> None:
        with self._lock:
            self._values[self._key(labels)] = float(value)


class Histogram(_Metric):
    kind = "histogram"

    def __init__(self, name: str, help_: str, labels: Iterable[str], lock: threading.Lock,
                 buckets: tuple[float, ...]):
        super().__init__(name, help_, labels, lock)
        self.buckets = tuple(sorted(buckets))

    def observe(self, value: float, **labels: str) -> None:
        key = self._key(labels)
        with self._lock:
            counts, total = self._values.get(key) or ([0] * len(self.buckets), [0.0, 0])
            for i, bound in enumerate(self.buckets):
                if value <= bound:
                    counts[i] += 1
            total[0] += value
            total[1] += 1
            self._values[key] = (counts, total)

    def count(self, **labels: str) -> int:
        with self._lock:
            entry = self._values.get(self._key(labels))
        return entry[1][1] if entry else 0

    def lines(self) -> list[str]:
        out = []
        for key, (counts, (total, n)) in sorted(self._values.items()):
            for bound, c in (*zip(self.buckets, counts), (math.inf, n)):
                le = 'le="' + _num(bound) + '"'
                out.append(f"{self.name}_bucket{_labels(self.labelnames, key, le)} {c}")
            out.append(f"{self.name}_sum{_labels(self.labelnames, key)} {_num(total)}")
            out.append(f"{self.name}_count{_labels(self.labelnames, key)} {n}")
        return out

    def json(self) -> list[dict]:
        return [{"labels": dict(zip(self.labelnames, key)), "count": n, "sum": total,
                 "buckets": {_num(b): c for b, c in zip(self.buckets, counts)}}
                for key, (counts, (total, n)) in sorted(self._values.items())]


class Registry:
    """Named metrics in registration order; one lock, because every update is a few dict ops."""

    def __init__(self) -> None:
        self._lock = threading.Lock()
        self._metrics: list[_Metric] = []

    def _add(self, metric: _Metric) -> Any:
        self._metrics.append(metric)
        return metric

    def counter(self, name: str, help_: str, labels: Iterable[str] = ()) -> Counter:
        return self._add(Counter(name, help_, labels, self._lock))

    def gauge(self, name: str, help_: str, labels: Iterable[str] = ()) -> Gauge:
        return self._add(Gauge(name, help_, labels, self._lock))

    def histogram(self, name: str, help_: str, labels: Iterable[str] = (),
                  buckets: tuple[float, ...] = LATENCY_BUCKETS) -> Histogram:
        return self._add(Histogram(name, help_, labels, self._lock, buckets))

    def render(self) -> str:
        """Prometheus text exposition format 0.0.4."""
        with self._lock:
            lines = [line for m in self._metrics for line in (*m.header(), *m.lines())]
        return "\n".join(lines) + "\n"

    def snapshot(self) -> dict[str, dict]:
        with self._lock:
            return {m.name: {"type": m.kind, "help": m.help, "samples": m.json()} for m in self._metrics}


class _Recent:
    """A bounded set: remembers the last `size` keys, so events seen on two sockets count once."""

    def __init__(self, size: int):
        self.size = size
        self._keys: OrderedDict[Any, None] = OrderedDict()

    def first_time(self, key: Any) -> bool:
        if key in self._keys:
            return False
        self._keys[key] = None
        if len(self._keys) > self.size:
            self._keys.popitem(last=False)
        return True


class BridgeMetrics:
    """Every metric the bridge exposes, plus the bookkeeping that turns frames into them."""

    def __init__(self, version: str, protocol: int, *, clock=time.monotonic):
        self.clock = clock
        r = self.registry = Registry()
        info = r.gauge("bridge_info", "Bridge version and wire protocol; always 1.", ("version", "protocol"))
        info.set(1, version=version, protocol=str(protocol))
        r.gauge("process_start_time_seconds", "Bridge start, Unix time.").set(time.time())
        self.paired_devices = r.gauge("bridge_paired_devices", "Paired devices that are not revoked.")
        self.http_requests = r.counter("bridge_http_requests_total", "REST requests answered.",
                                       ("route", "method", "status"))
        self.http_seconds = r.histogram("bridge_http_request_seconds", "REST request duration.", ("route",))
        self.ws_open = r.gauge("bridge_ws_connections", "Open WebSocket connections.", ("endpoint",))
        self.ws_total = r.counter("bridge_ws_connections_total", "WebSocket connections accepted.", ("endpoint",))
        self.ws_rejected = r.counter("bridge_ws_rejected_total", "WebSocket upgrades refused before accept.",
                                     ("endpoint", "reason"))
        self.live_devices = r.gauge("bridge_live_devices", "Distinct devices with an open live connection.")
        self.frames_forwarded = r.counter("bridge_relay_frames_forwarded_total",
                                          "Phone→Hermes frames forwarded, by RPC method (response = an answer "
                                          "to a Hermes question).", ("method",))
        self.frames_refused = r.counter("bridge_relay_frames_refused_total",
                                        "Phone→Hermes frames the bridge refused.", ("reason",))
        self.frames_from_hermes = r.counter("bridge_relay_frames_from_hermes_total", "Hermes→phone frames relayed.")
        self.rpc_seconds = r.histogram("bridge_hermes_rpc_seconds", "Phone RPC round trip through Hermes, "
                                       "request forwarded to response relayed.", ("method",))
        self.rpc_errors = r.counter("bridge_hermes_rpc_errors_total", "Phone RPCs Hermes answered with an error.",
                                    ("method",))
        self.questions = r.counter("bridge_questions_total", "Questions Hermes asked (approval, clarify, ...).",
                                   ("method",))
        self.questions_open = r.gauge("bridge_questions_open", "Questions seen and not yet settled.")
        self.questions_ended = r.counter("bridge_questions_ended_total", "Questions settled, by outcome.",
                                         ("method", "outcome"))
        self.question_wait = r.histogram("bridge_question_wait_seconds", "Question seen to answered or withdrawn.",
                                         ("method",), buckets=WAIT_BUCKETS)
        self.turns_started = r.counter("bridge_turns_started_total", "message.start events seen on live sockets.")
        self.turns_completed = r.counter("bridge_turns_completed_total", "message.complete events, by status.",
                                         ("status",))
        self._lock = threading.Lock()
        self._live: dict[str, int] = {}  # device id -> open live sockets; never exported
        self._questions: dict[str, tuple[str, float, set[int]]] = {}  # srq id -> method, start, watchers
        self._seen_events = _Recent(_SEEN_EVENTS)

    # ---------------------------------------------------------------- REST and sockets

    def http_request(self, route: str, method: str, status: int, seconds: float) -> None:
        method = method if method in HTTP_METHODS else "other"  # the client picks it; keep the set closed
        self.http_requests.inc(route=route, method=method, status=str(status))
        self.http_seconds.observe(seconds, route=route)

    def socket_opened(self, endpoint: str, device_id: str | None = None) -> None:
        self.ws_open.inc(endpoint=endpoint)
        self.ws_total.inc(endpoint=endpoint)
        if device_id is not None:
            with self._lock:
                self._live[device_id] = self._live.get(device_id, 0) + 1
                self.live_devices.set(len(self._live))

    def socket_closed(self, endpoint: str, device_id: str | None = None) -> None:
        self.ws_open.inc(-1, endpoint=endpoint)
        if device_id is not None:
            with self._lock:
                left = self._live.get(device_id, 1) - 1
                if left > 0:
                    self._live[device_id] = left
                else:
                    self._live.pop(device_id, None)
                self.live_devices.set(len(self._live))

    def connection(self) -> LiveStats:
        return LiveStats(self)

    # ---------------------------------------------------------------- questions (process-wide)
    # Two phones see the same question; it is counted and timed once, from its first sighting
    # to the first settlement any socket observes.

    def _question_seen(self, rid: str, method: str, conn: int) -> None:
        with self._lock:
            entry = self._questions.get(rid)
            if entry is not None:
                entry[2].add(conn)
                return
            if len(self._questions) >= MAX_OPEN_QUESTIONS:
                return
            self._questions[rid] = (method, self.clock(), {conn})
            self.questions_open.set(len(self._questions))
        self.questions.inc(method=method)

    def _question_ended(self, rid: str, outcome: str, *, timed: bool = True) -> None:
        with self._lock:
            entry = self._questions.pop(rid, None)
            self.questions_open.set(len(self._questions))
        if entry is None:
            return
        method, start, _ = entry
        self.questions_ended.inc(method=method, outcome=outcome)
        if timed:
            self.question_wait.observe(max(0.0, self.clock() - start), method=method)

    def _questions_unwatched(self, conn: int) -> None:
        """A socket closed: questions no other socket saw can no longer be followed to their end."""
        with self._lock:
            orphans = [rid for rid, (_, _, watchers) in self._questions.items() if watchers <= {conn}]
            for _, _, watchers in self._questions.values():
                watchers.discard(conn)
        for rid in orphans:
            self._question_ended(rid, "unobserved", timed=False)

    def _turn_event(self, params: dict) -> None:
        kind = params.get("type")
        if kind not in ("message.start", "message.complete"):
            return
        seq = params.get("seq")
        if seq is not None and not self._seen_events.first_time((params.get("session_id"), kind, seq)):
            return
        if kind == "message.start":
            self.turns_started.inc()
            return
        payload = params.get("payload") if isinstance(params.get("payload"), dict) else {}
        status = payload.get("status") or "complete"
        self.turns_completed.inc(status=status if status in TURN_STATUSES else "other")


class LiveStats:
    """One live connection's view: matches the phone's RPC ids to Hermes' responses."""

    def __init__(self, metrics: BridgeMetrics):
        self.m = metrics
        self._pending: dict[Any, tuple[str, float]] = {}

    def refused(self, reason: str) -> None:
        self.m.frames_refused.inc(reason=reason)

    def from_phone(self, frame: dict) -> None:
        """A frame the relay forwards (already validated: an allowlisted call or a srq-… answer)."""
        method = frame.get("method")
        rid = frame.get("id")
        if method is None:
            self.m.frames_forwarded.inc(method="response")
            self.m._question_ended(rid, "answered" if "result" in frame else "declined")
            return
        self.m.frames_forwarded.inc(method=method)
        if rid is not None and isinstance(rid, (int, str)) and len(self._pending) < MAX_PENDING_RPCS:
            self._pending[rid] = (method, self.m.clock())

    def from_hermes(self, raw: str) -> None:
        self.m.frames_from_hermes.inc()
        if not self._pending and not any(marker in raw for marker in _MARKERS):
            return  # the common case while a reply streams: no decode
        try:
            frame = json.loads(raw)
        except ValueError:
            return
        if not isinstance(frame, dict):
            return
        method, rid = frame.get("method"), frame.get("id")
        if method is None:
            self._response(rid, "error" in frame)
        elif method == "event" and isinstance(frame.get("params"), dict):
            self._event(frame["params"])
        elif method in QUESTION_METHODS and isinstance(rid, str) and _SERVER_REQUEST_ID.match(rid):
            self.m._question_seen(rid, method, id(self))

    def _response(self, rid: Any, failed: bool) -> None:
        if not isinstance(rid, (int, str)):
            return
        sent = self._pending.pop(rid, None)
        if sent is None:
            return
        method, start = sent
        self.m.rpc_seconds.observe(max(0.0, self.m.clock() - start), method=method)
        if failed:
            self.m.rpc_errors.inc(method=method)

    def _event(self, params: dict) -> None:
        if params.get("type") == "request.cancel":
            payload = params.get("payload") if isinstance(params.get("payload"), dict) else {}
            rid, reason = payload.get("id"), payload.get("reason")
            if isinstance(rid, str):
                self.m._question_ended(rid, reason if reason in CANCEL_REASONS else "other")
            return
        self.m._turn_event(params)

    def close(self) -> None:
        self._pending.clear()
        self.m._questions_unwatched(id(self))


# ---------------------------------------------------------------- the CLI's summary


def _samples(data: dict, name: str) -> list[dict]:
    return data.get(name, {}).get("samples", [])


def _total(data: dict, name: str, **match: str) -> float:
    return sum(s["value"] for s in _samples(data, name) if all(s["labels"].get(k) == v for k, v in match.items()))


def _by(data: dict, name: str, label: str) -> dict[str, float]:
    out: dict[str, float] = {}
    for s in _samples(data, name):
        out[s["labels"][label]] = out.get(s["labels"][label], 0) + s["value"]
    return out


def _timing(samples: list[dict]) -> tuple[int, float, str]:
    """(count, mean seconds, p95 as "<= bucket") over histogram samples merged across labels."""
    count = sum(s["count"] for s in samples)
    if not count:
        return 0, 0.0, "-"
    buckets: dict[float, int] = {}
    for s in samples:
        for bound, c in s["buckets"].items():
            buckets[float(bound)] = buckets.get(float(bound), 0) + c
    p95 = next((b for b in sorted(buckets) if buckets[b] >= 0.95 * count), math.inf)
    return count, sum(s["sum"] for s in samples) / count, "> last bucket" if math.isinf(p95) else f"<= {_secs(p95)}"


def _secs(value: float) -> str:
    return f"{value * 1000:.0f} ms" if value < 1 else f"{value:.1f} s" if value < 120 else f"{value / 60:.0f} min"


def _uptime(start: float) -> str:
    left = max(0, int(time.time() - start))
    days, left = divmod(left, 86400)
    hours, left = divmod(left, 3600)
    return (f"{days}d " if days else "") + f"{hours}h {left // 60}m"


def _top(counts: dict[str, float], n: int = 5) -> str:
    rows = sorted(counts.items(), key=lambda kv: -kv[1])[:n]
    return ", ".join(f"{k} {int(v)}" for k, v in rows) or "-"


def summarize(data: dict) -> str:
    """A few readable lines from `GET /metrics?format=json`."""
    info = (_samples(data, "bridge_info") or [{"labels": {}}])[0]["labels"]
    start = _total(data, "process_start_time_seconds")
    lines = [f"Bridge v{info.get('version', '?')} (protocol {info.get('protocol', '?')}), up {_uptime(start)}"]
    sockets = _by(data, "bridge_ws_connections", "endpoint")
    lines.append(f"Devices: {int(_total(data, 'bridge_paired_devices'))} paired, "
                 f"{int(_total(data, 'bridge_live_devices'))} connected now "
                 f"({int(sockets.get('live', 0))} live socket(s), {int(sockets.get('terminal', 0))} terminal(s))")
    http = _samples(data, "bridge_http_requests_total")
    errors = sum(s["value"] for s in http if int(s["labels"]["status"]) >= 400)
    n, mean, p95 = _timing(_samples(data, "bridge_http_request_seconds"))
    lines.append(f"REST: {n} request(s), {int(errors)} answered 4xx/5xx; mean {_secs(mean)}, p95 {p95}")
    lines.append(f"  busiest: {_top(_by(data, 'bridge_http_requests_total', 'route'))}")
    refused = _by(data, "bridge_relay_frames_refused_total", "reason")
    lines.append(f"Live: {int(_total(data, 'bridge_ws_connections_total', endpoint='live'))} connection(s) since start; "
                 f"{int(_total(data, 'bridge_relay_frames_forwarded_total'))} frame(s) to Hermes, "
                 f"{int(_total(data, 'bridge_relay_frames_from_hermes_total'))} back; "
                 f"{int(sum(refused.values()))} refused" + (f" ({_top(refused)})" if refused else ""))
    n, mean, p95 = _timing(_samples(data, "bridge_hermes_rpc_seconds"))
    lines.append(f"Hermes RPC: {n} call(s), mean {_secs(mean)}, p95 {p95}, "
                 f"{int(_total(data, 'bridge_hermes_rpc_errors_total'))} error(s)")
    lines.append(f"  most called: {_top(_by(data, 'bridge_relay_frames_forwarded_total', 'method'))}")
    n, mean, p95 = _timing(_samples(data, "bridge_question_wait_seconds"))
    outcomes = _by(data, "bridge_questions_ended_total", "outcome")
    lines.append(f"Questions: {int(_total(data, 'bridge_questions_total'))} asked, "
                 f"{int(_total(data, 'bridge_questions_open'))} open; wait mean {_secs(mean) if n else '-'}, "
                 f"p95 {p95}" + (f"; {_top(outcomes)}" if outcomes else ""))
    done = _by(data, "bridge_turns_completed_total", "status")
    lines.append(f"Turns: {int(_total(data, 'bridge_turns_started_total'))} started, "
                 f"{int(sum(done.values()))} finished" + (f" ({_top(done)})" if done else ""))
    return "\n".join(lines)
