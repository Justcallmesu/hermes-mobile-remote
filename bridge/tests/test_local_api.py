"""The loopback control API the desktop app uses: /v1/local/* behind a secret file."""
import base64
import json
import os
import stat

import pytest
from fastapi.testclient import TestClient

from hermes_remote_bridge import app as app_mod
from hermes_remote_bridge import cli, firewall, relay
from hermes_remote_bridge.app import create_app
from hermes_remote_bridge.config import Config
from hermes_remote_bridge.devices import DeviceStore
from hermes_remote_bridge.network import Network
from hermes_remote_bridge.tailnet import TailnetClient

from test_app import PHONE, FakeLocator, FakeTailnet, fake_http

NET = Network("abc123", "HomeWiFi", "Wi-Fi", ("192.168.1.5",))
H = "X-Hermes-Local"


def _cfg_path(tmp_path, lan=False):
    path = tmp_path / "config.toml"
    path.write_text("\n".join([
        f"devices_file = '{(tmp_path / 'devices.json').as_posix()}'",
        f"trust_file = '{(tmp_path / 'networks.json').as_posix()}'",
        f"audit_log = '{(tmp_path / 'audit.log').as_posix()}'",
        f"tls_dir = '{(tmp_path / 'tls').as_posix()}'",
        f"local_secret_file = '{(tmp_path / 'local-secret').as_posix()}'",
        f"hermes_home = '{(tmp_path / 'no-hermes').as_posix()}'",
        f"hermes_python = '{(tmp_path / 'no-hermes' / 'python').as_posix()}'",
        f"hermes_bin = '{(tmp_path / 'no-hermes' / 'hermes').as_posix()}'",  # never the real launcher
        f"lan = {'true' if lan else 'false'}",
    ]) + "\n")
    return path


@pytest.fixture
def env(tmp_path, monkeypatch):
    monkeypatch.setattr(app_mod, "current_network", lambda: NET)
    monkeypatch.setattr(cli, "current_network", lambda: NET)
    monkeypatch.setattr(firewall, "state", lambda port: firewall.FirewallState("none", False, None))
    path = _cfg_path(tmp_path)
    cfg = Config.load(path)
    app = create_app(cfg, locator=FakeLocator(), http=fake_http(), owner_login="me@example.com",
                     tailnet=FakeTailnet({PHONE: "me@example.com"}))
    secret = (tmp_path / "local-secret").read_text().strip()
    return path, cfg, app, secret, TestClient(app, client=("127.0.0.1", 50000))


def cli_json(capsys, path, *argv):
    cli.main(["--config", str(path), *argv])
    return json.loads(capsys.readouterr().out)


def test_secret_is_created_0600_and_recreated_with_a_new_value_when_deleted(env, tmp_path):
    path, cfg, app, secret, c = env
    file = tmp_path / "local-secret"
    assert len(secret) == 64 and bytes.fromhex(secret)  # 32 random bytes, hex
    if os.name == "posix":
        assert stat.S_IMODE(file.stat().st_mode) == 0o600
    file.unlink()
    assert c.get("/v1/local/status", headers={H: secret}).status_code == 403  # revoked
    new = file.read_text().strip()  # re-created by that very request
    assert new != secret
    assert c.get("/v1/local/status", headers={H: new}).status_code == 200


ROUTES = [("GET", "status", None), ("GET", "devices", None), ("GET", "trust", None), ("GET", "doctor", None),
          ("POST", "pair", {"name": "tablet"}), ("POST", "revoke", {"name": "phone"}),
          ("POST", "trust", {}), ("POST", "untrust", {"id": "abc123"})]


def _call(c, method, route, body, headers):
    return c.request(method, f"/v1/local/{route}", json=body, headers=headers)


@pytest.mark.parametrize("method,route,body", ROUTES)
def test_loopback_with_the_secret_gets_200_and_without_it_403(env, monkeypatch, method, route, body):
    path, cfg, app, secret, c = env
    DeviceStore(cfg.devices_file).pair("phone")
    monkeypatch.setattr(cli, "_bridge_urls", lambda cfg: ["http://100.64.0.20:8650"])
    assert _call(c, method, route, body, {H: secret}).status_code == 200
    for headers in ({}, {H: "wrong"}, {H: secret[:-1]}):
        r = _call(c, method, route, body, headers)
        assert r.status_code == 403 and r.json()["error"]["code"] == "forbidden_local"


def test_a_non_loopback_peer_gets_403_even_with_the_right_secret(tmp_path, monkeypatch):
    monkeypatch.setattr(app_mod, "current_network", lambda: NET)
    cfg = Config.load(_cfg_path(tmp_path, lan=True))
    app = create_app(cfg, locator=FakeLocator(), http=fake_http(), owner_login="me@example.com",
                     tailnet=FakeTailnet({PHONE: "me@example.com"}))
    secret = (tmp_path / "local-secret").read_text().strip()
    tailnet = TestClient(app, client=(PHONE, 50000))
    lan = TestClient(app, client=("192.168.1.50", 50000), base_url="https://192.168.1.10:8650")
    for c in (tailnet, lan):
        r = c.get("/v1/local/status", headers={H: secret})
        assert r.status_code == 403
    assert tailnet.get("/v1/local/status", headers={H: secret}).json()["error"]["code"] == "forbidden_local"
    assert lan.get("/v1/local/status", headers={H: secret}).json()["error"]["code"] == "forbidden_local"


def test_status_reports_running_version_addresses_network_and_firewall(env):
    path, cfg, app, secret, c = env
    body = c.get("/v1/local/status", headers={H: secret}).json()
    assert body["running"] is True and body["version"]
    assert body["port"] == cfg.port and "127.0.0.1" in body["listen"]
    assert body["network"] == {"id": "abc123", "name": "HomeWiFi", "trusted": False}
    assert body["firewall_ok"] is True


def test_devices_route_equals_cli_json(env, capsys):
    path, cfg, app, secret, c = env
    DeviceStore(cfg.devices_file).pair("phone")
    assert c.get("/v1/local/devices", headers={H: secret}).json() == cli_json(capsys, path, "devices", "--json")


def test_trust_route_equals_cli_json(env, capsys):
    path, cfg, app, secret, c = env
    assert c.get("/v1/local/trust", headers={H: secret}).json() == cli_json(capsys, path, "trust", "--list", "--json")


def test_doctor_route_equals_cli_json(env, capsys):
    path, cfg, app, secret, c = env
    assert c.get("/v1/local/doctor", headers={H: secret}).json() == cli_json(capsys, path, "doctor", "--json")


def test_post_trust_and_untrust_change_the_trust_list(env, capsys):
    path, cfg, app, secret, c = env
    after = c.post("/v1/local/trust", json={}, headers={H: secret}).json()
    assert after == cli_json(capsys, path, "trust", "--list", "--json")
    assert after["current"]["trusted"] is True
    c.post("/v1/local/untrust", json={"id": "abc123"}, headers={H: secret})
    assert cli_json(capsys, path, "trust", "--list", "--json")["trusted"] == []


def test_pair_returns_a_code_and_a_qr_png_and_registers_the_device(env, monkeypatch):
    path, cfg, app, secret, c = env
    monkeypatch.setattr(cli, "_bridge_urls", lambda cfg: ["http://100.64.0.20:8650"])
    body = c.post("/v1/local/pair", json={"name": "tablet"}, headers={H: secret}).json()
    assert body["code"].startswith("hermesremote://pair?")
    assert base64.b64decode(body["qr_png_base64"]).startswith(b"\x89PNG")
    assert [d.name for d in DeviceStore(cfg.devices_file).list()] == ["tablet"]
    # a second pair under the same name is refused unless replace is set
    assert c.post("/v1/local/pair", json={"name": "tablet"}, headers={H: secret}).status_code == 409
    assert c.post("/v1/local/pair", json={"name": "tablet", "replace": True},
                  headers={H: secret}).status_code == 200


def test_revoke_route_revokes_the_device(env):
    path, cfg, app, secret, c = env
    store = DeviceStore(cfg.devices_file)
    store.pair("phone")
    assert c.post("/v1/local/revoke", json={"name": "phone"}, headers={H: secret}).status_code == 200
    assert [d.active for d in store.list()] == [False]


def test_local_api_is_in_no_relay_allowlist_and_needs_no_device_token(env):
    path, cfg, app, secret, c = env
    assert not any("local" in m for m in relay.ALLOWED_METHODS)
    # a device token without the secret is not enough
    token = DeviceStore(cfg.devices_file).pair("phone")[1]
    assert c.get("/v1/local/status", headers={"Authorization": f"Bearer {token}"}).status_code == 403
    assert c.get("/v1/local/status", headers={H: secret}).status_code == 200  # no Authorization header
