import logging
import types

from hermes_remote_bridge import terminal
from hermes_remote_bridge.config import Config


def _fake_which(found):
    return lambda name: f"/x/{name}" if name in found else None


def _os(name, **env):
    return types.SimpleNamespace(name=name, environ=env)


def test_override_used_verbatim_when_it_resolves(monkeypatch):
    monkeypatch.setattr(terminal.shutil, "which", _fake_which({"fish"}))
    cfg = Config(terminal_shell="fish -l")
    assert terminal.default_shell(cfg) == ["fish", "-l"]


def test_windows_order_pwsh_powershell_cmd(monkeypatch):
    monkeypatch.setattr(terminal, "os", _os("nt", COMSPEC="C:\\cmd.exe"))
    cfg = Config()
    monkeypatch.setattr(terminal.shutil, "which", _fake_which({"pwsh", "powershell"}))
    assert terminal.default_shell(cfg)[0] == "/x/pwsh"
    monkeypatch.setattr(terminal.shutil, "which", _fake_which({"powershell"}))
    assert terminal.default_shell(cfg)[0] == "/x/powershell"
    monkeypatch.setattr(terminal.shutil, "which", _fake_which(set()))
    assert terminal.default_shell(cfg) == ["C:\\cmd.exe"]


def test_windows_quoted_override_path_loses_quotes_keeps_backslashes(monkeypatch):
    path = "C:\\Program Files\\Git\\bin\\bash.exe"
    monkeypatch.setattr(terminal, "os", _os("nt"))
    monkeypatch.setattr(terminal.shutil, "which", _fake_which({path}))
    cfg = Config(terminal_shell=f'"{path}" -l')
    assert terminal.default_shell(cfg) == [path, "-l"]


def test_windows_unquoted_override_unchanged(monkeypatch):
    monkeypatch.setattr(terminal, "os", _os("nt"))
    monkeypatch.setattr(terminal.shutil, "which", _fake_which({"bash"}))
    assert terminal.default_shell(Config(terminal_shell="bash -l")) == ["bash", "-l"]


def test_missing_override_falls_back_with_one_warning(monkeypatch, caplog):
    monkeypatch.setattr(terminal, "os", _os("posix", SHELL="/bin/zsh"))
    monkeypatch.setattr(terminal.shutil, "which", _fake_which(set()))
    with caplog.at_level(logging.WARNING):
        argv = terminal.default_shell(Config(terminal_shell="nope -x"))
    assert argv == ["/bin/zsh", "-l"]
    assert len([r for r in caplog.records if r.levelno == logging.WARNING]) == 1


def test_non_windows_default_unchanged(monkeypatch):
    monkeypatch.setattr(terminal, "os", _os("posix", SHELL="/usr/bin/fish"))
    monkeypatch.setattr(terminal.shutil, "which", _fake_which({"bash"}))
    assert terminal.default_shell(Config()) == ["/usr/bin/fish", "-l"]
    monkeypatch.setattr(terminal, "os", _os("posix"))
    assert terminal.default_shell(Config()) == ["/x/bash", "-l"]
    monkeypatch.setattr(terminal.shutil, "which", _fake_which(set()))
    assert terminal.default_shell(Config()) == ["/bin/sh", "-l"]
