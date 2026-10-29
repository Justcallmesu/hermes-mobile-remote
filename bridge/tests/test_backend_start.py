"""How the bridge runs `hermes serve`: the old venv interpreter, else the `hermes` launcher."""
from hermes_remote_bridge.backend import BackendLocator


def _locator(tmp_path, python=False, bin_=False):
    root = tmp_path / "hermes-agent"
    py = root / "venv" / "bin" / "python"
    hb = tmp_path / "bin" / "hermes"
    for path, make in ((py, python), (hb, bin_)):
        if make:
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text("")
    return BackendLocator(tmp_path, root, py, tmp_path / "serve.log", hermes_bin=hb), py, hb


def test_old_venv_interpreter_wins(tmp_path):
    loc, py, _ = _locator(tmp_path, python=True, bin_=True)
    assert loc.serve_argv() == [str(py), "-m", "hermes_cli.main", "serve", "--host", "127.0.0.1", "--port", "0"]


def test_launcher_used_when_there_is_no_old_venv(tmp_path):
    loc, _, hb = _locator(tmp_path, bin_=True)
    assert loc.serve_argv() == [str(hb), "serve", "--host", "127.0.0.1", "--port", "0"]


def test_nothing_installed(tmp_path):
    loc, _, _ = _locator(tmp_path)
    assert loc.serve_argv() is None


def test_serve_runs_in_its_own_systemd_scope_under_systemd(monkeypatch):
    """A bridge restart kills its whole cgroup: Hermes must live in a scope of its own."""
    from hermes_remote_bridge import backend
    monkeypatch.setattr(backend.os, "name", "posix")
    monkeypatch.setenv("INVOCATION_ID", "abc")
    monkeypatch.setattr(backend.shutil, "which", lambda name: "/usr/bin/" + name)
    argv = backend.own_scope_argv(["hermes", "serve"])
    assert argv[:4] == ["systemd-run", "--user", "--scope", "--quiet"]
    assert argv[-2:] == ["hermes", "serve"]


def test_no_scope_outside_systemd(monkeypatch):
    from hermes_remote_bridge import backend
    monkeypatch.delenv("INVOCATION_ID", raising=False)
    assert backend.own_scope_argv(["hermes", "serve"]) is None
