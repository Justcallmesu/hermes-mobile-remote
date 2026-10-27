"""The doctor's Windows service check asks the bridge on the port the running config names.

The Scheduled Task only starts the tray and a task in Ready says nothing about the bridge, so on
Windows the check probes the port itself. A bridge started with a non-default port (or a run
driven by a non-default config file) must not be reported as down: the probe has to follow cfg,
not a compiled-in 8650.
"""

from hermes_remote_bridge import doctor
from hermes_remote_bridge.config import Config


def test_the_windows_service_check_probes_the_port_from_the_config(monkeypatch):
    class ReadyTaskHost:
        name = "windows"

        def service_state(self, unit):
            assert unit == "hermes-remote-bridge"
            return "ready"

    monkeypatch.setattr(doctor, "host", lambda: ReadyTaskHost())
    probed = []
    monkeypatch.setattr(doctor, "_bridge_answers", lambda port: probed.append(port) or False)

    cfg = Config()
    cfg.port = 9123
    level, message = doctor._check_service(cfg)

    assert probed == [9123]
    assert "9123" in message


def test_firewall_check_warns_inside_a_container(monkeypatch):
    from hermes_remote_bridge import doctor, firewall
    from hermes_remote_bridge.config import Config

    monkeypatch.setattr(firewall, "state", lambda port: firewall.FirewallState("none", False, True))
    monkeypatch.setattr(doctor, "_container", lambda: "systemd-nspawn")
    level, msg = doctor._check_firewall(Config())
    assert level == doctor.WARN and "systemd-nspawn" in msg and "host" in msg
    monkeypatch.setattr(doctor, "_container", lambda: None)
    assert doctor._check_firewall(Config())[0] == doctor.OK
