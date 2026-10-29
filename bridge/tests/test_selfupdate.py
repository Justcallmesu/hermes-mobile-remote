"""Self-update: version picking, the check, and the launch (all without network or systemd)."""
import subprocess

from hermes_remote_bridge import selfupdate


def test_latest_tag_is_by_version_not_text():
    assert selfupdate.latest_tag(["v1.9.0", "v1.10.0", "v1.28.1", "desktop-v0.3.0", "v1.28.0"]) == "v1.28.1"
    assert selfupdate.latest_tag(["junk"]) is None


def test_check_reports_an_available_release(monkeypatch):
    monkeypatch.setattr(selfupdate, "RUNNING_COMMIT", None)
    monkeypatch.setattr(selfupdate, "RUNNING_TAG", None)
    monkeypatch.setattr(selfupdate, "why_not", lambda remote="origin": None)
    monkeypatch.setattr(selfupdate, "_remote_tags", lambda remote: ["v1.27.0", "v1.29.0"])
    monkeypatch.setattr(selfupdate, "_head", lambda: ("cccc333", "v1.28.1"))
    info = selfupdate.check()
    assert info["current"] == "v1.28.1" and info["latest"] == "v1.29.0"
    assert info["available"] and info["can_update"]


def test_check_never_offers_an_older_release(monkeypatch):
    monkeypatch.setattr(selfupdate, "RUNNING_COMMIT", None)
    monkeypatch.setattr(selfupdate, "RUNNING_TAG", None)
    monkeypatch.setattr(selfupdate, "why_not", lambda remote="origin": None)
    monkeypatch.setattr(selfupdate, "_remote_tags", lambda remote: ["v1.20.0"])
    monkeypatch.setattr(selfupdate, "_head", lambda: ("cccc333", "v1.28.1"))
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




def test_bridge_running_old_code_reports_a_restart(monkeypatch):
    """git pull without a restart: files are new, the process is not. Report what runs."""
    monkeypatch.setattr(selfupdate, "RUNNING_COMMIT", "aaaa111")
    monkeypatch.setattr(selfupdate, "RUNNING_TAG", "v1.29.0")
    monkeypatch.setattr(selfupdate, "_head", lambda: ("bbbb222", "v1.31.0"))
    monkeypatch.setattr(selfupdate, "why_not", lambda remote="origin": None)
    monkeypatch.setattr(selfupdate, "_remote_tags", lambda remote: ["v1.31.0"])
    info = selfupdate.check()
    assert info["current"] == "v1.29.0" and info["restart_needed"]
    assert info["available"] and info["can_update"] and info["latest"] == "v1.31.0"


def test_local_changes_still_allow_a_plain_restart(monkeypatch):
    monkeypatch.setattr(selfupdate, "RUNNING_COMMIT", "aaaa111")
    monkeypatch.setattr(selfupdate, "RUNNING_TAG", "v1.29.0")
    monkeypatch.setattr(selfupdate, "_head", lambda: ("bbbb222", "v1.31.0"))
    monkeypatch.setattr(selfupdate, "why_not", lambda remote="origin": "local changes")
    monkeypatch.setattr(selfupdate, "_remote_tags", lambda remote: ["v1.31.0"])
    assert selfupdate.check()["can_update"]


def test_install_skills_copies_missing_only(tmp_path, monkeypatch):
    monkeypatch.setenv("HERMES_HOME", str(tmp_path))
    monkeypatch.setattr("hermes_remote_bridge.config.Config.load", classmethod(lambda cls, p=None: (_ for _ in ()).throw(OSError())))
    mine = tmp_path / "skills" / "productivity" / "personal-notes" / "SKILL.md"
    mine.parent.mkdir(parents=True)
    mine.write_text("my edits")
    assert selfupdate.install_skills(log=lambda m: None) == ["project-blueprint"]
    assert mine.read_text() == "my edits"
    assert (tmp_path / "skills" / "software-development" / "project-blueprint" / "SKILL.md").exists()
    assert selfupdate.install_skills(log=lambda m: None) == []
