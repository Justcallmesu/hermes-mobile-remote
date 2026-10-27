import io
import os
import threading

import pytest

from hermes_remote_bridge import cli
from hermes_remote_bridge.supervise import Supervisor


class FakeChild:
    def __init__(self, world, code, ran_for, lines=()):
        self.world, self.code, self.ran_for = world, code, ran_for
        self.stdout = iter(lines)
        self.terminated = False

    def wait(self):
        self.world.now += self.ran_for
        return self.code

    def terminate(self):
        self.terminated = True


class World:
    """Fake clock + sleep + a queue of children to hand out."""

    def __init__(self, runs, tmp_path):
        self.now = 0.0
        self.sleeps = []
        self.children = []
        self.runs = list(runs)
        self.log = tmp_path / "bridge.log"
        self.sup = Supervisor(self.launch, self.log, clock=lambda: self.now, sleep=self.sleep)

    def launch(self):
        code, ran_for, *lines = self.runs.pop(0)
        child = FakeChild(self, code, ran_for, lines[0] if lines else ())
        self.children.append(child)
        return child

    def sleep(self, seconds):
        self.sleeps.append(seconds)
        if not self.runs:
            self.sup.stop()


def test_serve_exits_76_on_port_conflict(monkeypatch, capsys):
    monkeypatch.setattr(cli, "_port_in_use", lambda host, port: True)
    with pytest.raises(SystemExit) as exc:
        cli._bind("127.0.0.1", 8650)
    assert exc.value.code == 76
    assert "Port 8650 is already in use on 127.0.0.1; another bridge is probably running." in capsys.readouterr().err


def test_exit_75_restarts_after_2s_and_resets_count(tmp_path):
    w = World([(1, 1), (1, 1), (75, 1), (1, 1), (1, 1)], tmp_path)
    w.sup.run()
    assert w.sleeps == [5, 10, 2, 5, 10]


def test_quick_failures_back_off_capped_and_reset_by_long_run(tmp_path):
    runs = [(1, 1)] * 14 + [(1, 25), (1, 1)]
    w = World(runs, tmp_path)
    w.sup.run()
    assert w.sleeps[:3] == [5, 10, 15]
    assert w.sleeps[11:14] == [60, 60, 60]
    assert w.sleeps[14:] == [3, 5]  # a 20 s+ run: count 0 -> minimum 3 s, then 1 failure -> 5 s


def test_exit_76_does_not_restart_and_logs_conflict(tmp_path):
    w = World([(76, 1), (1, 1)], tmp_path)
    assert w.sup.run() != 0
    assert len(w.children) == 1 and w.sleeps == []
    assert "supervisor: port conflict" in w.log.read_text(encoding="utf-8")


def test_log_is_utf8_captures_child_output_and_rotates(tmp_path):
    w = World([(76, 1, ["héllo ✓\n", "second\n"])], tmp_path)
    w.log.write_bytes(b"x" * (2 * 1024 * 1024 + 1))
    w.sup.run()
    assert (tmp_path / "bridge.log.1").read_bytes() == b"x" * (2 * 1024 * 1024 + 1)
    text = w.log.read_text(encoding="utf-8")
    assert "héllo ✓\nsecond\n" in text
    assert " supervisor: " in text


def test_stop_terminates_child(tmp_path):
    w = World([(1, 1)], tmp_path)
    child_holder = {}
    orig = w.launch

    def launch():
        child_holder["c"] = orig()
        w.sup.stop()
        return child_holder["c"]

    w.sup._launcher = launch
    assert w.sup.run() == 0
    assert child_holder["c"].terminated


class BlockingChild:
    """Its output ends only when terminated, like a running bridge."""

    def __init__(self):
        self.done = threading.Event()
        self.terminated = False
        self.stdout = iter(())

    def wait(self):
        assert self.done.wait(5)
        return 0

    def terminate(self):
        self.terminated = True
        self.done.set()


def test_stdin_eof_stops_child_and_run_returns_0(tmp_path):
    child = BlockingChild()
    launched = threading.Event()

    class StdinClosedAfterLaunch(io.StringIO):
        def read(self, *_):
            assert launched.wait(5)
            return ""

    def launch():
        launched.set()
        return child

    sup = Supervisor(launch, tmp_path / "bridge.log")
    assert sup.watch_stdin(StdinClosedAfterLaunch()) is True
    assert sup.run() == 0
    assert child.terminated


class NoRead:
    def __init__(self, tty):
        self.tty = tty

    def isatty(self):
        return self.tty

    def read(self, *_):
        raise AssertionError("must not read")


def test_tty_or_missing_stdin_is_not_watched(tmp_path):
    sup = Supervisor(lambda: BlockingChild(), tmp_path / "bridge.log")
    assert sup.watch_stdin(NoRead(tty=True)) is False
    assert sup.watch_stdin(None) is False


@pytest.mark.skipif(not os.path.exists("/dev/null"), reason="no /dev/null")
def test_devnull_stdin_is_not_watched(tmp_path):
    # A service's stdin is /dev/null: already at EOF, but no parent app to follow.
    sup = Supervisor(lambda: BlockingChild(), tmp_path / "bridge.log")
    with open("/dev/null") as devnull:
        assert sup.watch_stdin(devnull) is False
    assert not sup._stop.is_set()


def test_supervise_subcommand_exists(capsys):
    with pytest.raises(SystemExit) as exc:
        cli.main(["supervise", "--help"])
    assert exc.value.code == 0
    assert "supervise" in capsys.readouterr().out
