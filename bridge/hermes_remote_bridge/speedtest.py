"""Internet speed test of the PC, run on demand from the phone.

Uses Cloudflare's public speed test endpoints (the ones speed.cloudflare.com itself uses): latency
is the median of a few tiny requests, download and upload stream a fixed payload and are cut off
after [PHASE_SECONDS] so a slow line never hangs the request.
"""
from __future__ import annotations

import http.client
import os
import statistics
import time
import urllib.request

BASE = "https://speed.cloudflare.com"
UA = {"User-Agent": "hermes-remote-bridge"}
PING_COUNT = 8
DOWN_BYTES = 50_000_000
UP_CHUNK = 2_000_000
PHASE_SECONDS = 8.0


def _get(url: str, timeout: float):
    return urllib.request.urlopen(urllib.request.Request(url, headers=UA), timeout=timeout)


def _keepalive_get():
    """One persistent HTTPS connection, so each probe is one round trip (no new TCP/TLS handshake)."""
    conn = http.client.HTTPSConnection(BASE.split("//", 1)[1], timeout=10)

    def get(url: str, timeout: float):
        conn.request("GET", url.split(BASE, 1)[1], headers=UA)
        return conn.getresponse()
    return get, conn


def latency_ms(opener=None) -> tuple[float, float]:
    """(median, jitter) in ms of tiny round trips; the first one (DNS + TLS) is not counted."""
    conn = None
    if opener is None:
        opener, conn = _keepalive_get()
    times = []
    try:
        for i in range(PING_COUNT + 1):
            t = time.perf_counter()
            r = opener(f"{BASE}/__down?bytes=0", 10)
            r.read()
            if i:
                times.append((time.perf_counter() - t) * 1000)
    finally:
        if conn is not None:
            conn.close()
    jitter = statistics.mean(abs(a - b) for a, b in zip(times, times[1:])) if len(times) > 1 else 0.0
    return round(statistics.median(times), 1), round(jitter, 1)


def download_mbps(opener=_get) -> tuple[float, int]:
    """(Mbit/s, bytes read) streaming one download until done or [PHASE_SECONDS]."""
    n = 0
    t = time.perf_counter()
    with opener(f"{BASE}/__down?bytes={DOWN_BYTES}", 20) as r:
        while time.perf_counter() - t < PHASE_SECONDS:
            chunk = r.read(256 * 1024)
            if not chunk:
                break
            n += len(chunk)
    dt = max(time.perf_counter() - t, 1e-3)
    return round(n * 8 / dt / 1e6, 1), n


def upload_mbps(poster=None) -> tuple[float, int]:
    """(Mbit/s, bytes sent): repeated uploads of [UP_CHUNK] until [PHASE_SECONDS] is used up."""
    def post(data: bytes) -> None:
        req = urllib.request.Request(f"{BASE}/__up", data=data, method="POST",
                                     headers={**UA, "Content-Type": "application/octet-stream"})
        with urllib.request.urlopen(req, timeout=20) as r:
            r.read()

    send = poster or post
    payload = os.urandom(UP_CHUNK)
    n = 0
    t = time.perf_counter()
    while time.perf_counter() - t < PHASE_SECONDS:
        send(payload)
        n += len(payload)
    dt = max(time.perf_counter() - t, 1e-3)
    return round(n * 8 / dt / 1e6, 1), n


def run(opener=None, poster=None) -> dict:
    """One full test; raises on network failure (the caller reports it)."""
    started = time.time()
    ping, jitter = latency_ms(opener)
    opener = opener or _get
    down, down_bytes = download_mbps(opener)
    up, up_bytes = upload_mbps(poster)
    return {"ts": int(started), "ping_ms": ping, "jitter_ms": jitter, "down_mbps": down, "up_mbps": up,
            "down_bytes": down_bytes, "up_bytes": up_bytes, "seconds": round(time.time() - started, 1),
            "server": "Cloudflare"}
