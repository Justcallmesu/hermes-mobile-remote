"""The Linux tray's one-line activity summary, read from `hermes-remote-bridge metrics --json`."""
import importlib.util
import sys
import types
from pathlib import Path

import pytest

TRAY = Path(__file__).resolve().parents[1] / "linux" / "tray.py"


@pytest.fixture(scope="module")
def tray():
    """tray.py imports PyQt6 at the top; the summary helpers don't touch Qt, so stub it."""
    class Dummy:
        """Stands in for any Qt class: takes any arguments, has any attribute."""
        def __init__(self, *args, **kwargs):
            pass

        def __getattr__(self, name):
            return Dummy()

    stubs = {}
    for name in ("PyQt6", "PyQt6.QtCore", "PyQt6.QtGui", "PyQt6.QtWidgets"):
        mod = types.ModuleType(name)
        setattr(mod, "__getattr__", lambda attr: Dummy)
        stubs[name] = mod
    saved = {k: sys.modules.get(k) for k in stubs}
    sys.modules.update(stubs)
    try:
        spec = importlib.util.spec_from_file_location("hermes_tray_under_test", TRAY)
        assert spec is not None and spec.loader is not None
        module = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(module)
        yield module
    finally:
        for k, v in saved.items():
            if v is None:
                sys.modules.pop(k, None)
            else:
                sys.modules[k] = v


def gauge(value):
    return {"type": "gauge", "samples": [{"labels": {}, "value": value}]}


def test_quiet_bridge(tray):
    assert tray.activity_line({"bridge_live_devices": gauge(0)}) == "0 connected"


def test_busy_bridge_names_what_waits(tray):
    m = {"bridge_live_devices": gauge(1), "bridge_turns_started_total": gauge(4), "bridge_questions_open": gauge(2)}
    assert tray.activity_line(m) == "1 connected, 4 turn(s), 2 approval(s) waiting"


def test_no_metrics_means_no_line(tray):
    assert tray.activity_line(None) is None
    assert tray.activity_line({}) is None
