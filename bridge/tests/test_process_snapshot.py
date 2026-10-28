"""The Windows process list: one SystemProcessInformation snapshot instead of psutil per process."""
import struct

import pytest

from hermes_remote_bridge import monitor as monitor_mod
from hermes_remote_bridge.host import winapi
from hermes_remote_bridge.monitor import Monitor

ENTRY = 256  # bigger than the last offset we read (WorkingSetSize @ 144)


def _entry(pid, name_ptr, name_len, create, user, kernel, rss, last=False):
    b = bytearray(ENTRY)
    struct.pack_into("<I", b, 0, 0 if last else ENTRY)
    struct.pack_into("<qqq", b, 32, create, user, kernel)
    struct.pack_into("<H", b, 56, name_len)
    struct.pack_into("<Q", b, 64, name_ptr)
    struct.pack_into("<Q", b, 80, pid)
    struct.pack_into("<Q", b, 144, rss)
    return bytes(b)


def test_parse_walks_every_entry_and_reads_the_documented_offsets():
    names = {1000: "System", 2000: "python.exe"}
    buf = (_entry(0, 0, 0, 0, 0, 5_000_000_0, 0)
           + _entry(4, 1000, 12, 11, 0, 20_000_000, 1 << 20)
           + _entry(1234, 2000, 20, 99, 30_000_000, 10_000_000, 50 << 20, last=True))
    rows = winapi.parse_process_snapshot(buf, lambda ptr, n: names[ptr][: n // 2])
    assert [r["pid"] for r in rows] == [0, 4, 1234]
    assert rows[0]["name"] == ""  # the idle process has no ImageName
    assert rows[2] == {"pid": 1234, "name": "python.exe", "cpu_s": 4.0, "rss": 50 << 20, "created": 99}


class _Host:
    def __init__(self):
        self.snap = []

    def process_snapshot(self):
        return self.snap


@pytest.fixture
def mon(monkeypatch, tmp_path):
    h = _Host()
    monkeypatch.setattr(monitor_mod, "host", lambda: h)
    monkeypatch.setattr(monitor_mod.psutil, "cpu_count", lambda: 2)
    monkeypatch.setattr(monitor_mod, "_details", lambda p, cmd: {"user": "", "status": "", "cmd": cmd or ""})
    clock = [100.0]
    monkeypatch.setattr(monitor_mod.time, "monotonic", lambda: clock[0])
    return Monitor(tmp_path / "m.db"), h, clock


def _p(pid, cpu_s, created=1, name="app.exe", rss=0):
    return {"pid": pid, "name": name, "cpu_s": cpu_s, "rss": rss, "created": created}


def test_cpu_share_comes_from_the_change_between_snapshots_and_idle_is_dropped(mon):
    m, h, clock = mon
    h.snap = [_p(0, 1000.0, name=""), _p(10, 5.0), _p(20, 1.0, rss=300 << 20)]
    first = m.processes("cpu", 10)["processes"]
    assert [p["pid"] for p in first] == [20, 10] or [p["pid"] for p in first] == [10, 20]
    assert all(p["cpu"] == 0.0 for p in first)  # nothing to compare with yet
    clock[0] += 2.0
    h.snap = [_p(0, 1003.0, name=""), _p(10, 6.0), _p(20, 1.5, rss=300 << 20)]
    rows = m.processes("cpu", 10)["processes"]
    # pid 10: 1 s of CPU in 2 s on 2 cores = 25% of the machine; pid 20: 0.5 s -> 12.5%
    assert [(p["pid"], p["cpu"]) for p in rows] == [(10, 25.0), (20, 12.5)]
    assert rows[1]["mem_mb"] == 300
    assert all(p["pid"] != 0 for p in rows)


def test_a_reused_pid_is_a_new_process(mon):
    m, h, clock = mon
    h.snap = [_p(10, 50.0, created=1)]
    m.processes()
    clock[0] += 1.0
    h.snap = [_p(10, 0.2, created=2)]  # different start time: not the same process
    assert m.processes()["processes"][0]["cpu"] == 0.0


def test_search_matches_name_without_reading_the_command_line(mon, monkeypatch):
    m, h, _ = mon
    asked = []
    monkeypatch.setattr(monitor_mod, "_cmdline", lambda p: asked.append(p.pid) or "C:\\x\\tool.exe --serve")
    h.snap = [_p(10, 1.0, name="python.exe"), _p(20, 1.0, name="other.exe")]
    r = m.processes("cpu", 10, "python")
    assert [p["pid"] for p in r["processes"]] == [10] and asked == [20]
    r = m.processes("cpu", 10, "serve")  # found through the command line
    assert {p["pid"] for p in r["processes"]} == {10, 20}
