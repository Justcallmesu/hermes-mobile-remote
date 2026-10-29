"""PC bridge admin from a paired phone."""
import httpx
from fastapi.testclient import TestClient

from hermes_remote_bridge import doctor, selfupdate
from hermes_remote_bridge.app import create_app
from hermes_remote_bridge.config import Config
from hermes_remote_bridge.devices import DeviceStore

from test_cron import Locator, Tailnet


def _client(tmp_path, **kw):
    cfg = Config(devices_file=tmp_path / "d.json", audit_log=tmp_path / "a.log", trust_file=tmp_path / "n.json", **kw)
    store = DeviceStore(cfg.devices_file)
    me, token = store.pair("phone")
    store.pair("old-laptop")
    app = create_app(cfg, locator=Locator(), http=httpx.AsyncClient(transport=httpx.MockTransport(lambda r: httpx.Response(404))),
                     tailnet=Tailnet(), devices=store, owner_login="me@x")
    return TestClient(app, client=("100.64.0.10", 50000)), {"Authorization": f"Bearer {token}"}


def test_doctor_offers_fixes(tmp_path, monkeypatch):
    monkeypatch.setattr(doctor, "collect", lambda cfg: [{"id": "service", "level": "fail", "message": "down"},
                                                         {"id": "devices", "level": "ok", "message": "1"}])
    c, h = _client(tmp_path)
    checks = c.get("/v1/admin/doctor", headers=h).json()["checks"]
    assert checks[0]["fix"] == "restart" and checks[1]["fix"] is None
    assert c.get("/v1/admin/doctor").status_code == 401


def test_restart_runs_outside_the_bridge(tmp_path, monkeypatch):
    called = []
    monkeypatch.setattr(selfupdate, "launch_restart", lambda: called.append(1))
    c, h = _client(tmp_path)
    assert c.post("/v1/admin/restart", headers=h).json() == {"restarting": True}
    assert called


def test_devices_and_no_self_revoke(tmp_path):
    c, h = _client(tmp_path)
    devs = c.get("/v1/admin/devices", headers=h).json()["devices"]
    assert [d["this"] for d in devs] == [True, False]
    assert c.post("/v1/admin/revoke", json={"name": "phone"}, headers=h).status_code == 409
    assert c.post("/v1/admin/revoke", json={"name": "old-laptop"}, headers=h).json()["revoked"]["name"] == "old-laptop"


def test_admin_can_be_turned_off(tmp_path):
    c, h = _client(tmp_path, remote_admin=False)
    assert c.post("/v1/admin/restart", headers=h).status_code == 404


def test_restart_argv_is_its_own_unit():
    assert selfupdate.launch_argv("/py", action="--restart")[-2:] == ["update", "--restart"]
