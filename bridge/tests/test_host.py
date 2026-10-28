"""Per-OS behaviour. The Windows host is exercised with PowerShell's output replaced, so these run
(and gate CI) on every platform; they prove the parsing and command construction, not that
Windows itself behaves as documented."""
import json
import sys
from pathlib import Path

import pytest

from hermes_remote_bridge import firewall, network, tailnet
from hermes_remote_bridge.config import Config
from hermes_remote_bridge.host import base, windows
from hermes_remote_bridge.host.windows import WindowsHost, normalise_mac, parse_default_routes


# ------------------------------------------------------------------ selection and locations

def test_host_matches_the_running_platform():
    from hermes_remote_bridge.host import host
    expected = {"win32": "windows", "darwin": "macos"}.get(sys.platform, "linux")
    assert host().name == expected


def test_windows_locations_follow_localappdata(monkeypatch, tmp_path):
    monkeypatch.setenv("LOCALAPPDATA", str(tmp_path))
    monkeypatch.delenv("HERMES_HOME", raising=False)
    h = WindowsHost()
    assert h.config_dir() == tmp_path / "hermes-remote" / "config"
    assert h.default_hermes_home() == tmp_path / "hermes"
    assert h.hermes_python() == tmp_path / "hermes" / "hermes-agent" / "venv" / "Scripts" / "python.exe"


def test_hermes_home_override_is_honoured_everywhere(monkeypatch, tmp_path):
    monkeypatch.setenv("HERMES_HOME", str(tmp_path / "custom"))
    assert WindowsHost().hermes_root() == tmp_path / "custom" / "hermes-agent"
    assert base.Host().hermes_root() == tmp_path / "custom" / "hermes-agent"


def test_hermes_bin_falls_back_to_path(monkeypatch, tmp_path):
    monkeypatch.setenv("HERMES_HOME", str(tmp_path))
    monkeypatch.setattr(base.shutil, "which", lambda name: "/usr/local/bin/hermes")
    assert base.Host().hermes_bin() == Path("/usr/local/bin/hermes")


# ------------------------------------------------------------------ windows parsing

def test_default_route_prefers_lowest_metric_and_skips_onlink():
    rows = [{"Alias": "Ethernet", "Gateway": "192.168.1.1", "Metric": 35},
            {"Alias": "Wi-Fi", "Gateway": "192.168.1.1", "Metric": 55},
            {"Alias": "VPN", "Gateway": "0.0.0.0", "Metric": 1}]
    assert parse_default_routes(rows) == ("Ethernet", "192.168.1.1")
    assert parse_default_routes([]) is None
    assert parse_default_routes([{"Alias": "VPN", "Gateway": "0.0.0.0", "Metric": 1}]) is None


@pytest.mark.parametrize("raw,want", [
    ("AA-BB-CC-DD-EE-FF\r\n", "aa:bb:cc:dd:ee:ff"),
    ("00-00-00-00-00-00", None), ("", None), ("not a mac", None)])
def test_mac_normalisation(raw, want):
    assert normalise_mac(raw) == want


def test_ps_json_accepts_one_object_or_a_list(monkeypatch):
    monkeypatch.setattr(windows, "ps", lambda s, timeout=15.0: json.dumps({"a": 1}))
    assert windows.ps_json("x") == [{"a": 1}]
    monkeypatch.setattr(windows, "ps", lambda s, timeout=15.0: json.dumps([{"a": 1}, {"a": 2}]))
    assert len(windows.ps_json("x")) == 2
    monkeypatch.setattr(windows, "ps", lambda s, timeout=15.0: "garbage")
    assert windows.ps_json("x") == []


def test_encoded_command_is_utf16le_base64():
    import base64
    assert base64.b64decode(windows.encode_script("Get-Date")).decode("utf-16-le") == "Get-Date"


class FakeWinapi:
    """Recorded fixture data standing in for the Win32/COM calls."""
    def default_routes(self):
        return [{"Alias": "Ethernet", "Gateway": "192.168.1.1", "Metric": 35},
                {"Alias": "Wi-Fi", "Gateway": "192.168.1.1", "Metric": 55},
                {"Alias": "VPN", "Gateway": "0.0.0.0", "Metric": 1}]

    def neighbor_mac(self, ip):
        return "AA-BB-CC-DD-EE-FF" if ip == "192.168.1.1" else ""

    def network_profile(self, alias):
        return ("{3F2A9C1E-0000-4B1B-9C11-AABBCCDDEEFF}", "HomeWiFi") if alias == "Wi-Fi" else None

    def firewall_profiles_enabled(self):
        return True

    def firewall_rules(self):
        return [{"Name": "Hermes Mobile Remote", "Enabled": True, "Inbound": True, "LocalPorts": "8650"},
                {"Name": "Other", "Enabled": True, "Inbound": True, "LocalPorts": "9999"}]

    ports = []

    def bridge_reachable(self, port):
        self.ports.append(port)
        return True


@pytest.fixture
def fake_winapi(monkeypatch):
    """Swap in the fake winapi and fail loudly if anything spawns a process."""
    def spawned(*a, **k):
        raise AssertionError("no subprocess/powershell allowed")
    monkeypatch.setattr(windows.subprocess, "run", spawned)
    monkeypatch.setattr(windows.subprocess, "Popen", spawned)
    fake = FakeWinapi()
    monkeypatch.setattr(windows, "winapi", fake)
    return fake


def test_windows_methods_return_the_old_shapes_from_fixture_data(fake_winapi):
    h = WindowsHost()
    assert h.default_route() == ("Ethernet", "192.168.1.1")
    assert h.gateway_mac("192.168.1.1") == "aa:bb:cc:dd:ee:ff"
    assert h.gateway_mac("10.0.0.1") is None
    assert h.gateway_mac("not an ip") is None
    assert h.firewall_active() is True
    assert h.firewall_rule_present(8650) is True and h.firewall_rule_present(9999) is False
    assert h.service_state("hermes-remote-bridge") == "active"
    assert h.service_state("some.other.service") == "unknown"


def test_windows_service_state_inactive_when_bridge_unreachable(fake_winapi, monkeypatch):
    seen = []
    monkeypatch.setattr(fake_winapi, "bridge_reachable", lambda port: seen.append(port) or False)
    from hermes_remote_bridge import config
    monkeypatch.setattr(config.Config, "load", classmethod(lambda cls, path=None: cls(port=9123)))
    assert WindowsHost().service_state("hermes-remote-bridge") == "inactive"
    assert seen == [9123]


def test_connection_profile_id_keeps_the_instance_id_format(fake_winapi):
    h = WindowsHost()
    assert h.network_profile("Wi-Fi") == ("{3F2A9C1E-0000-4B1B-9C11-AABBCCDDEEFF}", "HomeWiFi")
    assert h.network_profile("Nowhere") is None
    from hermes_remote_bridge.host import winapi
    assert winapi.instance_id("3f2a9c1e-0000-4b1b-9c11-aabbccddeeff") == "{3F2A9C1E-0000-4B1B-9C11-AABBCCDDEEFF}"
    assert winapi.instance_id("{3f2a9c1e-0000-4b1b-9c11-aabbccddeeff}") == "{3F2A9C1E-0000-4B1B-9C11-AABBCCDDEEFF}"


def test_same_ssid_different_router_is_a_different_network_on_windows(monkeypatch):
    """The trust model is OS independent: identity = profile + router MAC.

    fresh=True throughout: current_network() caches for a moment, and this test is about the one
    case where a stale answer would be a security problem - a router swap behind the same SSID.
    """
    monkeypatch.setattr(network, "_default_route", lambda: ("Wi-Fi", "192.168.1.1"))
    monkeypatch.setattr(network, "_interface_ips", lambda: {"Wi-Fi": ["192.168.1.10"]})
    monkeypatch.setattr(network, "_network_profile", lambda i: ("{GUID}", "HomeWiFi"))
    monkeypatch.setattr(network, "_gateway_mac", lambda g: "aa:aa:aa:aa:aa:aa")
    home = network.current_network(fresh=True)
    monkeypatch.setattr(network, "_gateway_mac", lambda g: "bb:bb:bb:bb:bb:bb")
    assert network.current_network(fresh=True).id != home.id


# ------------------------------------------------------------------ firewall

def test_windows_elevation_argv_is_exact_and_uses_no_powershell(monkeypatch, tmp_path):
    (tmp_path / "hermes-remote-bridge.exe").write_text("")
    calls = []

    class Fw:
        def run_elevated(self, exe, params):
            calls.append((exe, params))
            return 0
    monkeypatch.setattr(windows, "winapi", Fw())
    monkeypatch.setattr(windows.sys, "executable", str(tmp_path / "python.exe"))
    monkeypatch.setattr(windows.sys, "argv", ["fallback.exe"])
    assert WindowsHost().elevate_cli(["firewall", "add", "--port", "8650", "--elevated"]) is True
    assert calls == [(str(tmp_path / "hermes-remote-bridge.exe"), "firewall add --port 8650 --elevated")]
    assert "powershell" not in " ".join(calls[0]).lower()
    Fw.run_elevated = lambda self, exe, params: None  # UAC cancelled
    assert WindowsHost().elevate_cli(["firewall", "add"]) is False


def test_windows_firewall_open_port_elevates_the_cli_add(monkeypatch):
    seen = []
    monkeypatch.setattr(firewall, "host", lambda: type("H", (), {
        "name": "windows", "elevate_cli": lambda s, a: seen.append(a) or True})())
    assert firewall.open_commands("windows", 8650) == [
        ["hermes-remote-bridge", "firewall", "add", "--port", "8650", "--elevated"]]
    assert firewall.open_port("windows", 8650) is True
    assert seen == [["firewall", "add", "--port", "8650", "--elevated"]]


def test_elevated_firewall_cli_writes_the_rule_through_the_host(monkeypatch):
    from hermes_remote_bridge import cli
    seen = []
    monkeypatch.setattr(cli, "host", lambda: type("H", (), {
        "name": "windows", "firewall_add": lambda s, p: seen.append(("add", p)) or True,
        "firewall_remove": lambda s: seen.append(("remove",)) or True})())
    for action in ("add", "remove"):
        with pytest.raises(SystemExit) as e:
            cli.main(["firewall", action, "--port", "8650", "--elevated"])
        assert e.value.code == 0
    assert seen == [("add", 8650), ("remove",)]


def test_firewall_add_port_flag_is_used_for_state_and_elevation(monkeypatch, tmp_path):
    from hermes_remote_bridge import cli
    seen, checked = [], []
    fake = type("H", (), {
        "name": "windows", "firewall_active": lambda s: True,
        "firewall_rule_present": lambda s, p: checked.append(p) or False,
        "elevate_cli": lambda s, a: seen.append(a) or True})()
    monkeypatch.setattr(firewall, "host", lambda: fake)
    monkeypatch.setattr(cli, "host", lambda: fake)
    cli.main(["--config", str(tmp_path / "config.toml"), "firewall", "add", "--port", "9999", "--yes"])
    assert checked == [9999]
    assert seen == [["firewall", "add", "--port", "9999", "--elevated"]]


def test_windows_host_firewall_add_and_remove_use_rule_name_and_private_ranges(fake_winapi):
    rec = []
    fake_winapi.firewall_add_rule = lambda *a: rec.append(("add",) + a) or True
    fake_winapi.firewall_remove_rules = lambda n: rec.append(("remove", n)) or True
    h = WindowsHost()
    assert h.firewall_add(8650) and h.firewall_remove()
    assert rec == [("add", "Hermes Mobile Remote", "Hermes Mobile Remote", 8650, ",".join(firewall.PRIVATE_RANGES)),
                   ("remove", "Hermes Mobile Remote")]


def test_windows_firewall_state(monkeypatch):
    monkeypatch.setattr(firewall, "host", lambda: type("H", (), {
        "name": "windows", "firewall_active": lambda s: True, "firewall_rule_present": lambda s, p: p == 8650})())
    assert firewall.state(8650).port_open is True
    assert firewall.state(9999).needs_opening


# ------------------------------------------------------------------ tailscale through the CLI

STATUS = {"BackendState": "Running", "Self": {"TailscaleIPs": ["100.64.0.10", "fd7a:115c:a1e0::1"], "UserID": 7},
          "User": {"7": {"LoginName": "me@example.com"}}}


def test_tailscale_cli_path_returns_the_same_answers(monkeypatch):
    monkeypatch.setattr(tailnet, "_cli_json", lambda *a: STATUS)
    assert tailnet.sync_tailscale_ips(None) == (["100.64.0.10", "fd7a:115c:a1e0::1"], "me@example.com")


def test_stopped_tailscale_offers_no_addresses(monkeypatch):
    # `tailscale down` keeps the IPs in status, but they are unbindable (EADDRNOTAVAIL crash loop).
    monkeypatch.setattr(tailnet, "_cli_json", lambda *a: {**STATUS, "BackendState": "Stopped"})
    assert tailnet.sync_tailscale_ips(None) == ([], "me@example.com")


async def test_cli_backed_client_status_and_whois(monkeypatch):
    whois = {"UserProfile": {"LoginName": "me@example.com"}, "Node": {"ComputedName": "phone", "Hostinfo": {"OS": "android"}}}
    monkeypatch.setattr(tailnet, "_cli_json", lambda *a: whois if a[0] == "whois" else STATUS)
    client = tailnet.TailnetClient(None)
    assert (await client.status_self_ips())[0] == "100.64.0.10"
    assert await client.whois("100.64.0.20") == {"login": "me@example.com", "node": "phone", "os": "android"}
    await client.aclose()


async def test_cli_backed_whois_fails_closed(monkeypatch):
    def boom(*a):
        raise RuntimeError("tailscale not running")
    monkeypatch.setattr(tailnet, "_cli_json", boom)
    assert await tailnet.TailnetClient(None).whois("100.64.0.20") is None


def test_missing_tailscale_cli_raises(monkeypatch):
    monkeypatch.setattr(tailnet, "host", lambda: type("H", (), {"tailscale_cli": lambda s: None, "name": "windows"})())
    with pytest.raises(RuntimeError):
        tailnet._cli_json("status", "--json")


# ------------------------------------------------------------------ portable pieces

def test_generic_interface_listing_uses_psutil(monkeypatch):
    psutil = pytest.importorskip("psutil")
    import socket
    from types import SimpleNamespace as NS
    monkeypatch.setattr(psutil, "net_if_addrs", lambda: {
        "Wi-Fi": [NS(family=socket.AF_INET, address="192.168.1.10"), NS(family=socket.AF_INET6, address="fe80::1")],
        "Loopback": [NS(family=socket.AF_INET, address="127.0.0.1")]})
    assert base.Host().interface_ips() == {"Wi-Fi": ["192.168.1.10"], "Loopback": ["127.0.0.1"]}


def test_tls_certificate_works_for_an_https_server(tmp_path):
    import ssl
    from hermes_remote_bridge.tls import ensure_identity
    cert, key = ensure_identity(tmp_path)
    ctx = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
    ctx.load_cert_chain(cert, key)  # raises if the pair is unusable


def test_doctor_prints_on_a_legacy_windows_console(monkeypatch, capsys):
    """cp1252 cannot encode the tick/cross; doctor must fall back to ASCII, not crash."""
    import io
    from hermes_remote_bridge import doctor
    legacy = io.TextIOWrapper(io.BytesIO(), encoding="cp1252")
    monkeypatch.setattr(doctor.sys, "stdout", legacy)
    marks = doctor._marks()
    assert all(m.isascii() for m in marks.values())
    for m in marks.values():
        print(m)  # would raise UnicodeEncodeError with the fancy marks


def test_a_closed_listening_socket_is_detected():
    """asyncio on Windows closes the listener after one failed accept; the bridge must notice."""
    import socket
    from hermes_remote_bridge import cli
    a, b = socket.socket(), socket.socket()
    assert not cli._listener_closed([[a], [b]])
    b.close()
    assert cli._listener_closed([[a], [b]])
    a.close()


def test_no_command_prints_help_instead_of_an_argparse_error(capsys):
    from hermes_remote_bridge import cli
    cli.main([])
    out = capsys.readouterr().out
    assert "pair phone" in out and "doctor" in out


def test_windows_shows_the_pairing_qr_as_a_picture(monkeypatch, tmp_path):
    """Block characters are unreliable in the Windows console, so the QR must open as an image."""
    from hermes_remote_bridge import cli
    from hermes_remote_bridge.config import Config
    opened = []
    fake = type("H", (), {"name": "windows", "qr_as_image": lambda s: True,
                          "open_file": lambda s, p: opened.append(p) or True})()
    monkeypatch.setattr(cli, "host", lambda: fake)
    cfg = Config(audit_log=tmp_path / "state" / "audit.log")
    assert cli._show_qr("hermesremote://pair?v=2", cfg) is True
    assert opened and opened[0].read_bytes()[:4] == b"\x89PNG"


def test_terminal_qr_survives_a_console_that_cannot_encode_blocks(monkeypatch, tmp_path):
    import io
    from hermes_remote_bridge import cli
    from hermes_remote_bridge.config import Config
    monkeypatch.setattr(cli.sys, "stdout", io.TextIOWrapper(io.BytesIO(), encoding="cp1252"))
    # A real Windows host would open the PNG in Photos on every test run; fake it.
    opened = []
    fake = type("H", (), {"name": "linux", "qr_as_image": lambda s: False,
                          "open_file": lambda s, p: opened.append(p) or True})()
    monkeypatch.setattr(cli, "host", lambda: fake)
    cfg = Config(audit_log=tmp_path / "audit.log")
    cli._show_qr("hermesremote://pair?v=2", cfg)  # must not raise UnicodeEncodeError
    assert (tmp_path / "pairing-qr.png").exists()
    assert opened == [tmp_path / "pairing-qr.png"]


def test_pairing_warns_when_the_firewall_blocks_the_port(monkeypatch):
    from hermes_remote_bridge import cli, firewall
    monkeypatch.setattr(cli, "serving_lan_ips", lambda enabled, trust: ["192.168.1.10"])
    monkeypatch.setattr(cli, "sync_tailscale_ips", lambda: ([], None))
    monkeypatch.setattr(firewall, "state", lambda port: firewall.FirewallState("windows", True, False))
    warnings = cli._pairing_warnings(Config(), ["https://192.168.1.10:8650"])
    assert any("blocking" in w and "hermes-remote-bridge firewall" in w for w in warnings)
    assert any("SAME Wi-Fi" in w for w in warnings)


def test_pairing_warns_when_only_tailscale_is_available(monkeypatch):
    from hermes_remote_bridge import cli, firewall
    monkeypatch.setattr(cli, "serving_lan_ips", lambda enabled, trust: [])
    monkeypatch.setattr(cli, "sync_tailscale_ips", lambda: (["100.64.0.10"], None))
    monkeypatch.setattr(firewall, "state", lambda port: firewall.FirewallState("windows", True, True))
    warnings = cli._pairing_warnings(Config(lan=True), ["http://100.64.0.10:8650"])
    assert any("Tailscale" in w for w in warnings)


def test_pairing_only_nags_about_the_wifi_when_nothing_is_wrong(monkeypatch):
    """With the port open and the network trusted, the only advice left is "be on the same Wi-Fi"."""
    from hermes_remote_bridge import cli, firewall
    monkeypatch.setattr(cli, "serving_lan_ips", lambda enabled, trust: ["192.168.1.10"])
    monkeypatch.setattr(cli, "sync_tailscale_ips", lambda: ([], None))
    monkeypatch.setattr(firewall, "state", lambda port: firewall.FirewallState("windows", True, True))
    warnings = cli._pairing_warnings(Config(), ["https://192.168.1.10:8650"])
    assert not any("blocking" in w or "Tailscale" in w for w in warnings)
    assert len(warnings) == 1 and "SAME Wi-Fi" in warnings[0]


def test_reachability_check_accepts_the_bridges_own_certificate(tmp_path, capsys):
    """The LAN listener uses a self-signed cert; treating that as 'unreachable' would be wrong."""
    import ssl
    import threading
    from http.server import BaseHTTPRequestHandler, HTTPServer

    from hermes_remote_bridge import cli
    from hermes_remote_bridge.tls import ensure_identity

    class Handler(BaseHTTPRequestHandler):
        def do_GET(self):
            self.send_error(401)  # what an unauthenticated request really gets
        def log_message(self, *a):
            pass

    cert, key = ensure_identity(tmp_path / "tls")
    server = HTTPServer(("127.0.0.1", 0), Handler)
    ctx = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
    ctx.load_cert_chain(cert, key)
    server.socket = ctx.wrap_socket(server.socket, server_side=True)
    threading.Thread(target=server.serve_forever, daemon=True).start()
    try:
        cfg = Config(tls_dir=tmp_path / "tls", audit_log=tmp_path / "audit.log")
        cli._confirm_reachable(f"https://127.0.0.1:{server.server_port}", cfg)  # cert names no IP
    finally:
        server.shutdown()
    assert "Reachable" in capsys.readouterr().out
