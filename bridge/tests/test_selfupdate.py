"""Self-update: version picking, the check, and the launch (all without network or systemd)."""
import subprocess

from hermes_remote_bridge import selfupdate


def test_latest_tag_is_by_version_not_text():
    assert selfupdate.latest_tag(["v1.9.0", "v1.10.0", "v1.28.1", "desktop-v0.3.0", "v1.28.0"]) == "v1.28.1"
    assert selfupdate.latest_tag(["junk"]) is None


def test_check_reports_an_available_release(monkeypatch):
    monkeypatch.setattr(selfupdate, "why_not", lambda remote="origin": None)
    monkeypatch.setattr(selfupdate, "_remote_tags", lambda remote: ["v1.27.0", "v1.29.0"])
    real = selfupdate._git

    def git(*args, **kw):
        if args[0] == "describe":
            return subprocess.CompletedProcess(args, 0, "v1.28.1\n", "")
        return real(*args, **kw)
    monkeypatch.setattr(selfupdate, "_git", git)
    info = selfupdate.check()
    assert info["current"] == "v1.28.1" and info["latest"] == "v1.29.0"
    assert info["available"] and info["can_update"]


def test_check_never_offers_an_older_release(monkeypatch):
    monkeypatch.setattr(selfupdate, "why_not", lambda remote="origin": None)
    monkeypatch.setattr(selfupdate, "_remote_tags", lambda remote: ["v1.20.0"])
    monkeypatch.setattr(selfupdate, "_git", lambda *a, **k: subprocess.CompletedProcess(a, 0, "v1.28.1\n", ""))
    assert selfupdate.check()["available"] is False


def test_update_runs_in_its_own_unit_outside_the_bridge():
    argv = selfupdate.launch_argv("/py")
    assert argv[:3] == ["systemd-run", "--user", "--quiet"]
    assert any(a.startswith("--unit=hermes-remote-update-") for a in argv)
    assert argv[-2:] == ["update", "--apply"]


def test_cached_check_reuses_one_network_call():
    calls = []
    cc = selfupdate.CachedCheck(ttl=999, fn=lambda: calls.append(1) or {"available": False})
    cc.get(); cc.get()
    assert len(calls) == 1
    cc.get(force=True)
    assert len(calls) == 2


