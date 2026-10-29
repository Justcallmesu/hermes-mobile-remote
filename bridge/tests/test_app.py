import asyncio
import json
import os
import threading
import time

import httpx
import pytest
from fastapi.testclient import TestClient
from starlette.websockets import WebSocketDisconnect
from websockets.sync.server import serve

from hermes_remote_bridge.app import create_app
from hermes_remote_bridge.backend import Backend, BackendUnavailable, ledger_candidates
from hermes_remote_bridge.config import Config
from hermes_remote_bridge.devices import DeviceStore
from hermes_remote_bridge.relay import MAX_STEER_CHARS, check_frame
from hermes_remote_bridge.tailnet import is_loopback, is_tailnet_ip

PHONE = "100.64.0.10"


class FakeTailnet:
    def __init__(self, logins):
        self.logins = logins

    async def whois(self, addr):
        login = self.logins.get(addr)
        return {"login": login, "node": f"node-{addr}", "os": "android"} if login else None

    async def status_self_ips(self):
        return ["100.1.2.3"]

    async def status(self):
        return {"BackendState": "Running", "Self": {"TailscaleIPs": ["100.1.2.3"], "HostName": "pc", "DNSName": "pc.ts.net."}}

    async def aclose(self):
        pass


class FakeLocator:
    """Stands in for BackendLocator: a fixed backend, or none at all."""

    def __init__(self, backend: Backend | None = Backend(1234, "tok")):
        self.backend = backend
        self.forgotten = 0

    async def get(self, *, start: bool = True):
        if self.backend is None:
            raise BackendUnavailable("Hermes is not running on this PC")
        return self.backend

    def forget(self, backend=None):
        self.forgotten += 1

    async def aclose(self):
        pass


def fake_http(responses=None, calls=None):
    """httpx client whose transport answers like `hermes serve`'s REST API."""
    responses = responses or {}

    def handler(request: httpx.Request):
        if calls is not None:
            calls.append(request)
        if request.headers.get("X-Hermes-Session-Token") != "tok":
            return httpx.Response(401, json={"detail": "Unauthorized"})
        body = responses.get((request.method, request.url.path))
        return httpx.Response(200, json=body if body is not None else {})

    return httpx.AsyncClient(transport=httpx.MockTransport(handler))


@pytest.fixture
def env(tmp_path):
    cfg = Config(devices_file=tmp_path / "devices.json", audit_log=tmp_path / "audit.log")
    store = DeviceStore(cfg.devices_file)
    _, token = store.pair("phone")
    calls = []
    http = fake_http({("GET", "/api/sessions"): {"sessions": [{"id": "s1", "pinned": True}], "total": 1,
                                                 "limit": 40, "offset": 0}}, calls)
    tailnet = FakeTailnet({PHONE: "me@example.com", "100.99.0.1": "stranger@example.com"})
    app = create_app(cfg, locator=FakeLocator(), http=http, tailnet=tailnet, devices=store, owner_login="me@example.com")
    return cfg, store, token, calls, app


def client(app, ip):
    return TestClient(app, client=(ip, 50000))


def auth(token):
    return {"Authorization": f"Bearer {token}"}


def test_ip_classification():
    assert is_tailnet_ip("100.64.0.10") and is_tailnet_ip("fd7a:115c:a1e0::1")
    assert not is_tailnet_ip("192.168.1.5") and not is_tailnet_ip("100.128.0.1")
    assert is_loopback("127.0.0.1") and is_loopback("::1") and not is_loopback("100.64.0.10")


def test_lan_peer_rejected_even_with_valid_token(env):
    _, _, token, _, app = env
    r = client(app, "192.168.1.50").get("/v1/me", headers=auth(token))
    assert r.status_code == 403 and r.json()["error"]["code"] == "forbidden_network"


def test_foreign_tailnet_identity_rejected(env):
    _, _, token, _, app = env
    r = client(app, "100.99.0.1").get("/v1/me", headers=auth(token))
    assert r.status_code == 403 and r.json()["error"]["code"] == "forbidden_peer"


def test_token_required_and_revocation_is_live(env):
    _, store, token, _, app = env
    c = client(app, PHONE)
    assert c.get("/v1/me").status_code == 401
    assert c.get("/v1/me", headers=auth("hrb_wrong")).status_code == 401
    ok = c.get("/v1/me", headers=auth(token))
    assert ok.status_code == 200 and ok.json()["device"]["name"] == "phone" and ok.json()["protocol"] == 2
    DeviceStore(store.path).revoke("phone")  # a separate store instance, as the CLI does
    assert c.get("/v1/me", headers=auth(token)).status_code == 401


def test_device_file_stores_only_hash(env):
    cfg, _, token, _, _ = env
    assert token not in cfg.devices_file.read_text()
    if os.name == "posix":  # Windows has no mode bits; the user-profile ACL applies
        assert oct(os.stat(cfg.devices_file).st_mode & 0o777) == "0o600"


def test_body_limit_and_validation(env):
    _, _, token, _, app = env
    c = client(app, PHONE)
    big = c.patch("/v1/sessions/s1", headers={**auth(token), "content-type": "application/json"},
                  content=b'{"title":"' + b"x" * 1_100_000 + b'"}')
    assert big.status_code == 413
    assert c.patch("/v1/sessions/s1", headers=auth(token), json={}).status_code == 400
    assert c.patch("/v1/sessions/..%2Fetc", headers=auth(token), json={"pinned": True}).status_code in (400, 404)


def test_session_list_is_the_desktop_sidebar_query_with_the_backend_token(env):
    _, _, token, calls, app = env
    body = client(app, PHONE).get("/v1/sessions", headers=auth(token)).json()
    assert body["sessions"] == [{"id": "s1", "pinned": True}]
    req = calls[-1]
    assert req.url.path == "/api/sessions" and req.url.port == 1234
    assert req.headers["X-Hermes-Session-Token"] == "tok"
    q = dict(req.url.params)
    assert q["order"] == "recent" and q["archived"] == "exclude" and q["exclude_sources"] == "cron"


def test_pin_passes_through_to_hermes(env):
    _, _, token, calls, app = env
    r = client(app, PHONE).patch("/v1/sessions/s1", headers=auth(token), json={"pinned": False})
    assert r.status_code == 200
    assert calls[-1].method == "PATCH" and json.loads(calls[-1].content) == {"pinned": False}


def test_hermes_down_is_a_503_not_a_500(tmp_path):
    cfg = Config(devices_file=tmp_path / "d.json", audit_log=tmp_path / "a.log")
    store = DeviceStore(cfg.devices_file)
    _, token = store.pair("phone")
    app = create_app(cfg, locator=FakeLocator(None), http=fake_http(), tailnet=FakeTailnet({PHONE: "me@x"}),
                     devices=store, owner_login="me@x")
    c = client(app, PHONE)
    r = c.get("/v1/sessions", headers=auth(token))
    assert r.status_code == 503 and r.json()["error"]["code"] == "hermes_unavailable"
    assert c.get("/v1/status", headers=auth(token)).json()["components"]["hermes"]["status"] == "down"


def test_audit_log_has_no_tokens(env):
    cfg, _, token, _, app = env
    client(app, PHONE).get("/v1/sessions", headers=auth(token))
    log = cfg.audit_log.read_text()
    assert token not in log and "tok" not in log.replace("token", "") and '"device": "phone"' in log


def test_approval_mode_get_set_and_validation(tmp_path):
    cfg = Config(devices_file=tmp_path / "d.json", audit_log=tmp_path / "a.log")
    store = DeviceStore(cfg.devices_file)
    _, token = store.pair("phone")
    state = {"mode": "smart"}
    argv_seen = []

    async def fake_cli(*argv):
        argv_seen.append(argv)
        if argv[:2] == ("config", "get"):
            return state["mode"] + "\n"
        if argv[:2] == ("config", "set"):
            state["mode"] = argv[3]
            return "ok\n"
        raise AssertionError(argv)

    app = create_app(cfg, locator=FakeLocator(), http=fake_http(), tailnet=FakeTailnet({PHONE: "me@example.com"}),
                     devices=store, owner_login="me@example.com", hermes_cli=fake_cli)
    c = client(app, PHONE)
    assert c.get("/v1/settings/approvals", headers=auth(token)).json() == {"mode": "smart", "modes": ["manual", "smart", "off"]}
    r = c.put("/v1/settings/approvals", headers=auth(token), json={"mode": "off"})
    assert r.status_code == 200 and r.json()["mode"] == "off" and state["mode"] == "off"
    assert ("config", "set", "approvals.mode", "off") in argv_seen
    n = len(argv_seen)
    assert c.put("/v1/settings/approvals", headers=auth(token), json={"mode": "yolo; rm -rf"}).status_code == 400
    assert len(argv_seen) == n  # arbitrary values never reach the CLI
    assert "approval_mode_changed" in (tmp_path / "a.log").read_text()
    assert c.put("/v1/settings/approvals", json={"mode": "manual"}).status_code == 401


def test_default_model_setting(env):
    cfg, store, token, calls, _ = env
    state = {"model": "claude-opus", "provider": "anthropic"}

    def handler(request: httpx.Request):
        calls.append(request)
        if request.url.path == "/api/model/info":
            return httpx.Response(200, json=dict(state))
        if request.url.path == "/api/model/set":
            body = json.loads(request.content)
            if body["model"] == "pricey" and not body["confirm_expensive_model"]:
                return httpx.Response(200, json={"confirm_required": True, "confirm_message": "costly"})
            state.update(model=body["model"], provider=body["provider"])
            return httpx.Response(200, json={"ok": True})
        return httpx.Response(404)

    http = httpx.AsyncClient(transport=httpx.MockTransport(handler))
    app = create_app(cfg, locator=FakeLocator(), http=http, tailnet=FakeTailnet({PHONE: "me@example.com"}),
                     devices=store, owner_login="me@example.com")
    c = client(app, PHONE)
    assert c.get("/v1/settings/model", headers=auth(token)).json() == {"model": "claude-opus", "provider": "anthropic"}
    r = c.put("/v1/settings/model", headers=auth(token), json={"model": "gpt-5", "provider": "openai"})
    assert r.json() == {"model": "gpt-5", "provider": "openai"}
    sent = json.loads(next(q for q in calls if q.url.path == "/api/model/set").content)
    assert sent["scope"] == "main"
    r = c.put("/v1/settings/model", headers=auth(token), json={"model": "pricey"})
    assert r.json()["confirm_required"] is True and state["model"] == "gpt-5"
    r = c.put("/v1/settings/model", headers=auth(token), json={"model": "pricey", "confirm_expensive_model": True})
    assert r.json()["model"] == "pricey"
    assert c.put("/v1/settings/model", json={"model": "x"}).status_code == 401


# ---------------------------------------------------------------- the live relay

def test_frame_filter():
    ok, err = check_frame(json.dumps({"jsonrpc": "2.0", "id": 1, "method": "prompt.submit",
                                      "params": {"session_id": "a", "text": "hi"}}), 1000)
    assert ok and err is None
    # Desktop-only or dangerous RPCs are refused with a JSON-RPC error, not forwarded.
    for method in ("cli.exec", "process.stop", "mcp.servers.add", "profiles.configure", "display.start"):
        ok, err = check_frame(json.dumps({"jsonrpc": "2.0", "id": 2, "method": method}), 1000)
        assert ok is None and json.loads(err)["error"]["code"] == -32601
    # The phone may list, create, rename, re-folder and delete projects; nothing else of projects.*.
    for method in ("projects.tree", "projects.create", "projects.update", "projects.delete",
                   "projects.add_folder", "projects.remove_folder", "projects.set_primary"):
        ok, err = check_frame(json.dumps({"jsonrpc": "2.0", "id": 5, "method": method,
                                          "params": {"name": "x", "primary_path": "~/x"}}), 1000)
        assert ok and err is None
    ok, err = check_frame(json.dumps({"jsonrpc": "2.0", "id": 6, "method": "projects.archive"}), 1000)
    assert ok is None and json.loads(err)["error"]["code"] == -32601
    # The vault page: list/add/remove; unlocking a password manager or turning sources on/off stays on the PC.
    for method in ("vault.list", "vault.sources", "vault.add", "vault.remove"):
        assert check_frame(json.dumps({"jsonrpc": "2.0", "id": 8, "method": method, "params": {}}), 1000)[0]
    for method in ("vault.unlock", "vault.lock", "vault.source.set"):
        ok, err = check_frame(json.dumps({"jsonrpc": "2.0", "id": 9, "method": method}), 1000)
        assert ok is None and json.loads(err)["error"]["code"] == -32601
    # Subagents: see them, stop one, steer one and read its output. Spawning them stays with the agent.
    for method in ("subagent.list", "subagent.interrupt", "subagent.steer", "subagent.tail"):
        ok, err = check_frame(json.dumps({"jsonrpc": "2.0", "id": 11, "method": method,
                                          "params": {"session_id": "a", "subagent_id": "s", "text": "hi"}}), 1000)
        assert ok and err is None
    # Steering text must be a non-blank string of sane length.
    for text in (None, "  ", 7, "x" * (MAX_STEER_CHARS + 1)):
        ok, err = check_frame(json.dumps({"jsonrpc": "2.0", "id": 10, "method": "subagent.steer",
                                          "params": {"session_id": "a", "subagent_id": "s", "text": text}}),
                              MAX_STEER_CHARS * 2)
        assert ok is None and json.loads(err)["error"]["code"] == -32602
    # Background processes of the open chat: see them and stop one. Killing every process stays on the PC.
    for method in ("process.list", "process.kill"):
        ok, err = check_frame(json.dumps({"jsonrpc": "2.0", "id": 12, "method": method,
                                          "params": {"session_id": "a", "process_id": "p"}}), 1000)
        assert ok and err is None
    for method in ("process.stop", "agents.list"):
        ok, err = check_frame(json.dumps({"jsonrpc": "2.0", "id": 13, "method": method}), 1000)
        assert ok is None and json.loads(err)["error"]["code"] == -32601
    # config.set only for the chat's own settings.
    ok, err = check_frame(json.dumps({"jsonrpc": "2.0", "id": 3, "method": "config.set",
                                      "params": {"key": "model", "value": "x"}}), 1000)
    assert ok
    ok, err = check_frame(json.dumps({"jsonrpc": "2.0", "id": 4, "method": "config.set",
                                      "params": {"key": "terminal.backend", "value": "x"}}), 1000)
    assert ok is None and err
    # Answers only to Hermes' own questions (srq- ids).
    assert check_frame(json.dumps({"jsonrpc": "2.0", "id": "srq-0123456789ab", "result": {"choice": "once"}}), 1000)[0]
    assert check_frame(json.dumps({"jsonrpc": "2.0", "id": 7, "result": {}}), 1000) == (None, None)
    assert json.loads(check_frame("x" * 2000, 1000)[1])["error"]["message"] == "Frame too large"
    assert json.loads(check_frame("not json", 1000)[1])["error"]["code"] == -32700


def test_frame_filter_checks_the_profile_a_call_names():
    def frame(profile):
        return json.dumps({"jsonrpc": "2.0", "id": 9, "method": "session.create", "params": {"profile": profile}})

    # Every profile when the bridge does not restrict them.
    assert check_frame(frame("developer"), 1000)[0]
    # Only the listed ones (and the default) when it does.
    assert check_frame(frame("developer"), 1000, ["developer"])[0]
    assert check_frame(frame("default"), 1000, ["developer"])[0]
    assert check_frame(frame(None), 1000, ["developer"])[0]  # null is Hermes' "default" too
    ok, err = check_frame(frame("bkm-reports-bot"), 1000, ["developer"])
    assert ok is None and json.loads(err)["error"]["code"] == -32602
    # A malformed name never reaches Hermes, restricted or not.
    for bad in ("../x", 7, ["pm"]):
        ok, err = check_frame(frame(bad), 1000)
        assert ok is None and json.loads(err)["error"]["code"] == -32602


# ---------------------------------------------------------------- Hermes profiles

PROFILES = {"profiles": [
    {"name": "default", "is_default": True, "model": "opus", "provider": "anthropic", "display_name": "",
     "description": "", "path": "/home/u/.hermes", "has_env": True},
    {"name": "developer", "is_default": False, "model": "sonnet", "provider": "anthropic",
     "display_name": "Dev", "description": "Engineer", "path": "/home/u/.hermes/profiles/developer"},
    {"name": "bkm-reports-bot", "is_default": False, "model": "qwen", "provider": "local", "display_name": "",
     "description": "", "path": "/home/u/.hermes/profiles/bkm-reports-bot"},
]}


def _profile_app(tmp_path, calls, cli=None, **cfg_kw):
    cfg = Config(devices_file=tmp_path / "d.json", audit_log=tmp_path / "a.log", **cfg_kw)
    store = DeviceStore(cfg.devices_file)
    _, token = store.pair("phone")
    http = fake_http({("GET", "/api/profiles"): PROFILES,
                      ("GET", "/api/sessions"): {"sessions": [], "total": 0, "limit": 40, "offset": 0},
                      ("GET", "/api/model/info"): {"model": "sonnet", "provider": "anthropic"}}, calls)
    app = create_app(cfg, locator=FakeLocator(), http=http, tailnet=FakeTailnet({PHONE: "me@x"}),
                     devices=store, owner_login="me@x", hermes_cli=cli)
    return client(app, PHONE), token


def test_profiles_lists_only_what_the_picker_needs(tmp_path):
    c, token = _profile_app(tmp_path, [])
    body = c.get("/v1/profiles", headers=auth(token)).json()
    assert [p["name"] for p in body["profiles"]] == ["default", "developer", "bkm-reports-bot"]
    assert body["profiles"][1] == {"name": "developer", "display_name": "Dev", "description": "Engineer",
                                   "model": "sonnet", "provider": "anthropic", "is_default": False}
    assert "path" not in json.dumps(body)  # no filesystem layout leaves the PC
    assert c.get("/v1/profiles").status_code == 401


def test_profiles_hides_what_the_allowlist_excludes(tmp_path):
    c, token = _profile_app(tmp_path, [], allowed_profiles=["developer"])
    names = [p["name"] for p in c.get("/v1/profiles", headers=auth(token)).json()["profiles"]]
    assert names == ["default", "developer"]


def test_profile_names_are_not_probed_without_auth(tmp_path):
    c, _ = _profile_app(tmp_path, [], allowed_profiles=["developer"])
    for name in ("secret", "BAD!!", "developer"):
        assert c.get(f"/v1/sessions?profile={name}").status_code == 401


def test_rest_calls_carry_the_profile_to_hermes(tmp_path):
    calls = []
    c, token = _profile_app(tmp_path, calls)
    c.get("/v1/sessions?profile=developer", headers=auth(token))
    assert dict(calls[-1].url.params)["profile"] == "developer"
    c.get("/v1/settings/model?profile=developer", headers=auth(token))
    assert calls[-1].url.path == "/api/model/info" and dict(calls[-1].url.params)["profile"] == "developer"
    c.patch("/v1/sessions/s1?profile=developer", headers=auth(token), json={"pinned": True})
    assert json.loads(calls[-1].content) == {"pinned": True, "profile": "developer"}
    # No profile (or "default") is exactly the old request: nothing extra reaches Hermes.
    for query in ("", "?profile=default"):
        c.get(f"/v1/sessions{query}", headers=auth(token))
        assert "profile" not in dict(calls[-1].url.params)
    c.patch("/v1/sessions/s1", headers=auth(token), json={"pinned": True})
    assert json.loads(calls[-1].content) == {"pinned": True}


def test_rest_refuses_bad_or_disallowed_profiles_before_hermes(tmp_path):
    calls = []
    c, token = _profile_app(tmp_path, calls, allowed_profiles=["developer"])
    r = c.get("/v1/sessions?profile=../../etc", headers=auth(token))
    assert r.status_code == 400 and r.json()["error"]["code"] == "invalid_profile"
    r = c.get("/v1/sessions?profile=bkm-reports-bot", headers=auth(token))
    assert r.status_code == 403 and r.json()["error"]["code"] == "profile_refused"
    assert calls == []


def test_approval_mode_is_per_profile(tmp_path):
    modes = {None: "smart", "developer": "manual"}
    seen = []

    async def fake_cli(*argv):
        seen.append(argv)
        profile, rest = (argv[1], argv[2:]) if argv[:1] == ("-p",) else (None, argv)
        if rest[:2] == ("config", "get"):
            return modes[profile] + "\n"
        modes[profile] = rest[3]
        return "ok\n"

    c, token = _profile_app(tmp_path, [], cli=fake_cli)
    assert c.get("/v1/settings/approvals?profile=developer", headers=auth(token)).json()["mode"] == "manual"
    assert c.put("/v1/settings/approvals?profile=developer", headers=auth(token), json={"mode": "off"}).json()["mode"] == "off"
    assert ("-p", "developer", "config", "set", "approvals.mode", "off") in seen
    assert modes == {None: "smart", "developer": "off"}  # the default profile was not touched
    assert '"profile": "developer"' in (tmp_path / "a.log").read_text()


@pytest.fixture
def fake_hermes():
    """A real WebSocket server playing `hermes serve`'s /api/ws: checks the token, echoes calls
    as results and pushes one event, so the test sees both directions through the bridge."""
    seen = []

    def handler(conn):
        if "token=tok" not in conn.request.path:
            conn.close(4401)
            return
        conn.send(json.dumps({"jsonrpc": "2.0", "method": "event", "params": {"type": "gateway.ready", "payload": {}}}))
        for raw in conn:
            frame = json.loads(raw)
            seen.append(frame)
            if "method" in frame:
                conn.send(json.dumps({"jsonrpc": "2.0", "id": frame["id"], "result": {"echo": frame["method"]}}))
                if frame["method"] == "prompt.submit":
                    conn.send(json.dumps({"jsonrpc": "2.0", "method": "event", "params": {
                        "type": "message.delta", "session_id": "a", "payload": {"text": "hel"}, "seq": 1}}))
                    conn.send(json.dumps({"jsonrpc": "2.0", "id": "srq-0123456789ab", "method": "approval",
                                          "params": {"session_id": "a", "request_id": "r1", "command": "rm x"}}))

    server = serve(handler, "127.0.0.1", 0)
    threading.Thread(target=server.serve_forever, daemon=True).start()
    yield server.socket.getsockname()[1], seen
    server.shutdown()


def _relay_app(tmp_path, port):
    cfg = Config(devices_file=tmp_path / "d.json", audit_log=tmp_path / "a.log")
    store = DeviceStore(cfg.devices_file)
    _, token = store.pair("phone")
    app = create_app(cfg, locator=FakeLocator(Backend(port, "tok")), http=fake_http(),
                     tailnet=FakeTailnet({PHONE: "me@example.com"}), devices=store, owner_login="me@example.com")
    return app, token, store, cfg


def test_relay_carries_both_directions(tmp_path, fake_hermes):
    port, seen = fake_hermes
    app, token, _, cfg = _relay_app(tmp_path, port)
    with client(app, PHONE).websocket_connect("/v1/ws", headers=auth(token)) as ws:
        assert ws.receive_json()["params"]["type"] == "gateway.ready"
        ws.send_text(json.dumps({"jsonrpc": "2.0", "id": 1, "method": "prompt.submit",
                                 "params": {"session_id": "a", "text": "hi"}}))
        assert ws.receive_json() == {"jsonrpc": "2.0", "id": 1, "result": {"echo": "prompt.submit"}}
        assert ws.receive_json()["params"]["payload"]["text"] == "hel"
        question = ws.receive_json()
        assert question["method"] == "approval"
        # The phone answers Hermes' question on the same socket.
        ws.send_text(json.dumps({"jsonrpc": "2.0", "id": question["id"], "result": {"choice": "once"}}))
        # A refused method is answered by the bridge and never reaches Hermes.
        ws.send_text(json.dumps({"jsonrpc": "2.0", "id": 2, "method": "cli.exec", "params": {"argv": ["x"]}}))
        assert ws.receive_json()["error"]["code"] == -32601
        ws.send_text(json.dumps({"jsonrpc": "2.0", "id": 3, "method": "ping"}))
        assert ws.receive_json()["id"] == 3
    assert [f.get("method") or f["id"] for f in seen] == ["prompt.submit", "srq-0123456789ab", "ping"]
    # The close entry lands just after the phone's socket goes away.
    for _ in range(50):
        log = cfg.audit_log.read_text()
        if '"ws_close"' in log:
            break
        time.sleep(0.05)
    assert '"event": "ws_open"' in log and '"prompt.submit": 1' in log and token not in log


@pytest.fixture
def parkable_hermes():
    """Like fake_hermes, but exposes each upstream connection and whether it has closed."""
    conns, closed = [], []

    def handler(conn):
        conns.append(conn)
        conn.send(json.dumps({"jsonrpc": "2.0", "method": "event", "params": {"type": "gateway.ready", "payload": {"n": len(conns)}}}))
        for raw in conn:
            frame = json.loads(raw)
            if "method" in frame:
                conn.send(json.dumps({"jsonrpc": "2.0", "id": frame["id"], "result": {"conn": conns.index(conn)}}))
                if frame["method"] == "prompt.submit":  # keep streaming until the connection ends
                    threading.Thread(target=stream, args=(conn,), daemon=True).start()
        closed.append(conn)

    def stream(conn):
        try:
            while True:
                conn.send(json.dumps({"jsonrpc": "2.0", "method": "event", "params": {"type": "message.delta"}}))
                time.sleep(0.002)
        except Exception:
            pass

    server = serve(handler, "127.0.0.1", 0)
    threading.Thread(target=server.serve_forever, daemon=True).start()
    yield server.socket.getsockname()[1], conns, closed
    server.shutdown()


def _park_app(tmp_path, port, park_seconds=300):
    expire = threading.Event()

    async def fake_sleep(_):  # the grace passes when the test says so
        while not expire.is_set():
            await asyncio.sleep(0.01)

    cfg = Config(devices_file=tmp_path / "d.json", audit_log=tmp_path / "a.log", park_seconds=park_seconds)
    store = DeviceStore(cfg.devices_file)
    tokens = {name: store.pair(name)[1] for name in ("phone", "tablet")}
    app = create_app(cfg, locator=FakeLocator(Backend(port, "tok")), http=fake_http(),
                     tailnet=FakeTailnet({PHONE: "me@example.com"}), devices=store,
                     owner_login="me@example.com", park_sleep=fake_sleep)
    return app, tokens, expire, cfg


def _wait(cond, what):
    for _ in range(100):
        if cond():
            return
        time.sleep(0.05)
    raise AssertionError(f"timed out waiting for {what}")


def _connect_and_drop(c, token):
    with c.websocket_connect("/v1/ws", headers=auth(token)) as ws:
        ready = ws.receive_json()
        ws.send_text(json.dumps({"jsonrpc": "2.0", "id": 1, "method": "ping"}))
        reply = ws.receive_json()
    return ready, reply


def _log(cfg):
    return cfg.audit_log.read_text() if cfg.audit_log.exists() else ""


def test_returning_device_gets_its_parked_upstream_and_the_stored_ready(tmp_path, parkable_hermes):
    port, conns, closed = parkable_hermes
    app, tokens, _, cfg = _park_app(tmp_path, port)
    with client(app, PHONE) as c:
        ready, reply = _connect_and_drop(c, tokens["phone"])
        assert ready["params"]["payload"] == {"n": 1} and reply["result"] == {"conn": 0}
        _wait(lambda: '"ws_park"' in _log(cfg), "ws_park")
        with c.websocket_connect("/v1/ws", headers=auth(tokens["phone"])) as ws:
            assert ws.receive_json() == ready  # the stored gateway.ready comes first
            ws.send_text(json.dumps({"jsonrpc": "2.0", "id": 2, "method": "ping"}))
            assert ws.receive_json()["result"] == {"conn": 0}  # forwarded both ways, same upstream
        assert len(conns) == 1 and closed == []
        assert '"ws_reattach"' in _log(cfg)


def test_grace_expiry_closes_the_parked_upstream(tmp_path, parkable_hermes):
    port, conns, closed = parkable_hermes
    app, tokens, expire, cfg = _park_app(tmp_path, port)
    with client(app, PHONE) as c:
        _connect_and_drop(c, tokens["phone"])
        _wait(lambda: '"ws_park"' in _log(cfg), "ws_park")
        expire.set()
        _wait(lambda: '"ws_park_expired"' in _log(cfg) and len(closed) == 1, "expiry and close")
        ready, _ = _connect_and_drop(c, tokens["phone"])
        assert len(conns) == 2 and ready["params"]["payload"] == {"n": 2}


def test_parked_upstream_closed_by_hermes_is_discarded(tmp_path, parkable_hermes):
    port, conns, closed = parkable_hermes
    app, tokens, _, cfg = _park_app(tmp_path, port)
    with client(app, PHONE) as c:
        _connect_and_drop(c, tokens["phone"])
        _wait(lambda: '"ws_park"' in _log(cfg), "ws_park")
        conns[0].close()
        _wait(lambda: len(closed) == 1, "Hermes close")
        time.sleep(0.5)  # let the bridge notice
        ready, _ = _connect_and_drop(c, tokens["phone"])
        assert len(conns) == 2 and ready["params"]["payload"] == {"n": 2}
        assert '"ws_reattach"' not in _log(cfg)


def test_park_seconds_zero_closes_upstream_with_the_phone(tmp_path, parkable_hermes):
    port, conns, closed = parkable_hermes
    app, tokens, _, cfg = _park_app(tmp_path, port, park_seconds=0)
    with client(app, PHONE) as c:
        _connect_and_drop(c, tokens["phone"])
        _wait(lambda: len(closed) == 1, "upstream close")
        _connect_and_drop(c, tokens["phone"])
        assert len(conns) == 2 and '"ws_park"' not in _log(cfg)


def test_different_devices_never_share_a_parked_socket(tmp_path, parkable_hermes):
    port, conns, closed = parkable_hermes
    app, tokens, _, cfg = _park_app(tmp_path, port)
    with client(app, PHONE) as c:
        _connect_and_drop(c, tokens["phone"])
        _wait(lambda: '"ws_park"' in _log(cfg), "ws_park")
        ready, reply = _connect_and_drop(c, tokens["tablet"])
        assert len(conns) == 2 and reply["result"] == {"conn": 1}
        _wait(lambda: _log(cfg).count('"ws_park"') == 2, "second park")
        ready, reply = _connect_and_drop(c, tokens["phone"])
        assert len(conns) == 2 and reply["result"] == {"conn": 0}


def test_same_device_connecting_again_replaces_its_live_connection(tmp_path, parkable_hermes):
    port, conns, closed = parkable_hermes
    app, tokens, _, cfg = _park_app(tmp_path, port)
    with client(app, PHONE) as c:
        with c.websocket_connect("/v1/ws", headers=auth(tokens["phone"])) as first:
            ready = first.receive_json()
            with c.websocket_connect("/v1/ws", headers=auth(tokens["phone"])) as second:
                assert second.receive_json() == ready  # the stored gateway.ready comes first
                with pytest.raises(WebSocketDisconnect):
                    first.receive_json()  # the old phone side was closed
                second.send_text(json.dumps({"jsonrpc": "2.0", "id": 2, "method": "ping"}))
                assert second.receive_json()["result"] == {"conn": 0}
        assert len(conns) == 1 and closed == []


def test_a_phone_that_drops_mid_stream_returns_to_a_working_upstream(tmp_path, parkable_hermes):
    port, conns, closed = parkable_hermes
    app, tokens, _, cfg = _park_app(tmp_path, port)
    with client(app, PHONE) as c:
        with c.websocket_connect("/v1/ws", headers=auth(tokens["phone"])) as ws:
            ws.receive_json()
            ws.send_text(json.dumps({"jsonrpc": "2.0", "id": 1, "method": "prompt.submit",
                                     "params": {"session_id": "a", "text": "hi"}}))
            ws.receive_json()
        _wait(lambda: '"ws_park"' in _log(cfg), "ws_park")
        time.sleep(0.3)  # Hermes keeps streaming into the parked socket
        with c.websocket_connect("/v1/ws", headers=auth(tokens["phone"])) as ws:
            ws.receive_json()
            ws.send_text(json.dumps({"jsonrpc": "2.0", "id": 2, "method": "ping"}))
            while (frame := ws.receive_json()).get("id") != 2:
                pass
            assert frame["result"] == {"conn": 0}
        assert len(conns) == 1 and closed == []


def test_a_discarded_parked_socket_is_closed_to_the_end():
    async def scenario():
        finished = []

        class Upstream:
            def __aiter__(self):
                return self

            async def __anext__(self):
                await asyncio.sleep(3600)

            async def close(self):
                await asyncio.sleep(0.05)
                finished.append(True)

        async def instant(_):
            pass

        from hermes_remote_bridge.relay import ParkedUpstreams
        parked = ParkedUpstreams(300, sleep=instant)
        parked.park("phone", Upstream(), "ready")
        import gc
        for _ in range(20):  # nothing else holds the close task: it must survive collection
            gc.collect()
            await asyncio.sleep(0.01)
        return finished

    assert asyncio.run(scenario()) == [True]


def test_a_different_device_connecting_does_not_close_a_live_connection(tmp_path, parkable_hermes):
    port, conns, closed = parkable_hermes
    app, tokens, _, cfg = _park_app(tmp_path, port)
    with client(app, PHONE) as c:
        with c.websocket_connect("/v1/ws", headers=auth(tokens["phone"])) as phone:
            phone.receive_json()
            with c.websocket_connect("/v1/ws", headers=auth(tokens["tablet"])) as tablet:
                assert tablet.receive_json()["params"]["payload"] == {"n": 2}
                phone.send_text(json.dumps({"jsonrpc": "2.0", "id": 3, "method": "ping"}))
                assert phone.receive_json()["result"] == {"conn": 0}  # still live, still its own upstream
                tablet.send_text(json.dumps({"jsonrpc": "2.0", "id": 4, "method": "ping"}))
                assert tablet.receive_json()["result"] == {"conn": 1}
        assert len(conns) == 2


def test_bridge_shutdown_closes_every_parked_socket(tmp_path, parkable_hermes):
    port, conns, closed = parkable_hermes
    app, tokens, _, cfg = _park_app(tmp_path, port)
    with client(app, PHONE) as c:
        _connect_and_drop(c, tokens["phone"])
        _connect_and_drop(c, tokens["tablet"])
        _wait(lambda: _log(cfg).count('"ws_park"') == 2, "both parked")
        assert closed == []
    _wait(lambda: len(closed) == 2, "shutdown closing both")


@pytest.fixture
def workable_hermes():
    """A Hermes whose attached session `s1` reports work as the test sets it, and that records the
    bridge's own probe requests (ids `brg-...`). `state["mode"]`: ok | error | silent."""
    state = {"turn": False, "process": False, "subagent": False, "loop": False, "heartbeat": False,
             "mode": "ok", "hold": None}
    conns, closed, probes = [], [], []

    def answer(frame):
        m = frame["method"]
        if m in ("session.create", "session.resume"):
            return {"session_id": "s1"}
        if m == "session.active_list":
            return {"sessions": [{"id": "s1", "status": "working" if state["turn"] else "idle"}]}
        if m == "process.list":
            return {"processes": [{"status": "running" if state["process"] else "exited"}]}
        if m == "subagent.list":
            return {"subagents": [{"id": "x"}] if state["subagent"] else []}
        if m == "session.control.read":
            return {"control": {k: ({"status": "active"} if state[k] else {"status": "done"})
                                for k in ("loop", "heartbeat")}}
        return {"ok": True}

    def handler(conn):
        conns.append(conn)
        conn.send(json.dumps({"jsonrpc": "2.0", "method": "event", "params": {"type": "gateway.ready", "payload": {}}}))
        for raw in conn:
            frame = json.loads(raw)
            if "method" not in frame:
                continue
            if str(frame["id"]).startswith("brg-"):
                probes.append(frame)
                if state["hold"] is not None:
                    state["hold"].wait()
                if state["mode"] == "silent":
                    continue
                if state["mode"] == "error":
                    conn.send(json.dumps({"jsonrpc": "2.0", "id": frame["id"], "error": {"code": -1, "message": "no"}}))
                    continue
            conn.send(json.dumps({"jsonrpc": "2.0", "id": frame["id"], "result": answer(frame)}))
        closed.append(conn)

    server = serve(handler, "127.0.0.1", 0)
    threading.Thread(target=server.serve_forever, daemon=True).start()
    yield server.socket.getsockname()[1], state, closed, probes
    server.shutdown()


class _Clock:
    """Virtual time: a sleep adds its length at once (plus a tiny real yield), so minutes pass in a blink."""

    def __init__(self):
        self.t = 0.0

    async def sleep(self, d):
        await asyncio.sleep(0.01)
        self.t += d


def _work_app(tmp_path, port, park_seconds=60, park_max_seconds=86400):
    clock = _Clock()
    cfg = Config(devices_file=tmp_path / "d.json", audit_log=tmp_path / "a.log", park_seconds=park_seconds,
                 park_max_seconds=park_max_seconds)
    store = DeviceStore(cfg.devices_file)
    token = store.pair("phone")[1]
    app = create_app(cfg, locator=FakeLocator(Backend(port, "tok")), http=fake_http(),
                     tailnet=FakeTailnet({PHONE: "me@example.com"}), devices=store,
                     owner_login="me@example.com", park_sleep=clock.sleep)
    return app, token, clock, cfg


def _attach_and_drop(c, token, attach=True):
    with c.websocket_connect("/v1/ws", headers=auth(token)) as ws:
        ws.receive_json()
        if attach:
            ws.send_text(json.dumps({"jsonrpc": "2.0", "id": 5, "method": "session.create", "params": {}}))
            assert ws.receive_json()["result"] == {"session_id": "s1"}


def _stays_open_for(clock, seconds, closed):
    start = clock.t
    _wait(lambda: clock.t - start >= seconds, "virtual time")
    assert closed == []


def test_parked_socket_with_a_running_process_stays_open_until_it_stops(tmp_path, workable_hermes):
    port, state, closed, _ = workable_hermes
    app, token, clock, cfg = _work_app(tmp_path, port)
    state["process"] = True
    with client(app, PHONE) as c:
        _attach_and_drop(c, token)
        _wait(lambda: '"ws_park"' in _log(cfg), "ws_park")
        _stays_open_for(clock, 3 * 60, closed)
        state["process"] = False
        stopped = clock.t
        _wait(lambda: '"ws_park_expired"' in _log(cfg) and len(closed) == 1, "idle release")
        assert clock.t - stopped >= 60 - 30  # park_seconds of idle, to the 30 s check


@pytest.mark.parametrize("signal", ["turn", "subagent", "loop", "heartbeat", "process"])
def test_each_work_signal_alone_keeps_the_parked_socket_open(tmp_path, workable_hermes, signal):
    port, state, closed, _ = workable_hermes
    app, token, clock, cfg = _work_app(tmp_path, port)
    state[signal] = True
    with client(app, PHONE) as c:
        _attach_and_drop(c, token)
        _wait(lambda: '"ws_park"' in _log(cfg), "ws_park")
        _stays_open_for(clock, 3 * 60, closed)
        assert '"ws_park_expired"' not in _log(cfg)


def test_no_attached_session_is_a_plain_idle_park(tmp_path, workable_hermes):
    port, state, closed, probes = workable_hermes
    app, token, clock, cfg = _work_app(tmp_path, port)
    with client(app, PHONE) as c:
        _attach_and_drop(c, token, attach=False)
        _wait(lambda: '"ws_park_expired"' in _log(cfg) and len(closed) == 1, "idle release")
        assert clock.t >= 60 - 30 and probes == []


def test_park_max_seconds_releases_a_socket_that_always_has_work(tmp_path, workable_hermes):
    port, state, closed, _ = workable_hermes
    app, token, clock, cfg = _work_app(tmp_path, port, park_max_seconds=200)
    state["process"] = True
    with client(app, PHONE) as c:
        _attach_and_drop(c, token)
        _wait(lambda: '"ws_park_cap"' in _log(cfg) and len(closed) == 1, "cap release")
        assert '"ws_park_expired"' not in _log(cfg)


def test_probe_replies_never_reach_a_phone_that_reattaches_mid_probe(tmp_path, workable_hermes):
    port, state, closed, probes = workable_hermes
    app, token, clock, cfg = _work_app(tmp_path, port)
    hold = state["hold"] = threading.Event()
    with client(app, PHONE) as c:
        _attach_and_drop(c, token)
        _wait(lambda: probes, "a probe in flight")
        with c.websocket_connect("/v1/ws", headers=auth(token)) as ws:
            ws.receive_json()
            hold.set()
            time.sleep(0.3)  # Hermes answers the probe now
            ws.send_text(json.dumps({"jsonrpc": "2.0", "id": 7, "method": "ping"}))
            seen = []
            while (frame := ws.receive_json()).get("id") != 7:
                seen.append(frame)
            assert seen == [] and frame["result"] == {"ok": True}
        assert closed == []


@pytest.mark.parametrize("mode", ["error", "silent"])
def test_a_failed_probe_counts_as_work(tmp_path, workable_hermes, monkeypatch, mode):
    from hermes_remote_bridge import relay
    monkeypatch.setattr(relay, "PROBE_TIMEOUT", 0.2)
    port, state, closed, _ = workable_hermes
    app, token, clock, cfg = _work_app(tmp_path, port)
    state["mode"] = mode
    with client(app, PHONE) as c:
        _attach_and_drop(c, token)
        _wait(lambda: '"ws_park"' in _log(cfg), "ws_park")
        _stays_open_for(clock, 3 * 60, closed)
        assert '"ws_park_expired"' not in _log(cfg)


def test_phone_can_read_loop_state_but_not_call_outside_the_list():
    ok = json.dumps({"jsonrpc": "2.0", "id": 1, "method": "session.control.read", "params": {"session_id": "a"}})
    assert check_frame(ok, 1000) == (ok, None)
    bad = json.dumps({"jsonrpc": "2.0", "id": 2, "method": "session.control.write", "params": {}})
    forward, reply = check_frame(bad, 1000)
    assert forward is None and json.loads(reply)["error"]["code"] == -32601


def test_relay_requires_a_live_device_token(tmp_path, fake_hermes):
    port, seen = fake_hermes
    app, token, store, _ = _relay_app(tmp_path, port)
    c = client(app, PHONE)
    for headers in ({}, auth("hrb_wrong")):
        with pytest.raises(WebSocketDisconnect) as e:
            with c.websocket_connect("/v1/ws", headers=headers) as ws:
                ws.receive_json()
        assert e.value.code == 4401
    store.revoke("phone")
    with pytest.raises(WebSocketDisconnect):
        with c.websocket_connect("/v1/ws", headers=auth(token)) as ws:
            ws.receive_json()
    assert seen == []


def test_relay_refuses_untrusted_networks(tmp_path, fake_hermes):
    port, _ = fake_hermes
    app, token, _, _ = _relay_app(tmp_path, port)
    for ip in ("192.168.1.50", "203.0.113.9", "100.99.0.1"):
        with pytest.raises(WebSocketDisconnect) as e:
            with client(app, ip).websocket_connect("/v1/ws", headers=auth(token)) as ws:
                ws.receive_json()
        assert e.value.code == 4403


def test_relay_says_when_hermes_is_not_running(tmp_path):
    cfg = Config(devices_file=tmp_path / "d.json", audit_log=tmp_path / "a.log")
    store = DeviceStore(cfg.devices_file)
    _, token = store.pair("phone")
    app = create_app(cfg, locator=FakeLocator(None), http=fake_http(), tailnet=FakeTailnet({PHONE: "me@x"}),
                     devices=store, owner_login="me@x")
    with pytest.raises(WebSocketDisconnect) as e:
        with client(app, PHONE).websocket_connect("/v1/ws", headers=auth(token)) as ws:
            ws.receive_json()
    assert e.value.code == 1013


def test_ledger_picks_live_serve_backends_newest_first(tmp_path):
    ledger = tmp_path / "spawn-ledger.json"
    me = os.getpid()
    ledger.write_text(json.dumps([
        {"pid": me, "purpose": "mcp-helper", "port": None},
        {"pid": me, "purpose": "serve", "host": "127.0.0.1", "port": 40001, "registered_at": 1, "profile": ""},
        {"pid": me, "purpose": "serve", "host": "127.0.0.1", "port": 40002, "registered_at": 5, "profile": ""},
        {"pid": me, "purpose": "serve", "host": "127.0.0.1", "port": 40003, "registered_at": 9, "profile": "work"},
        {"pid": 2_000_000_000, "purpose": "serve", "host": "127.0.0.1", "port": 40004, "registered_at": 9},
        {"pid": me, "purpose": "serve", "host": "0.0.0.0", "port": 40005, "registered_at": 9},
    ]))
    assert ledger_candidates(ledger) == [40002, 40001]
    assert ledger_candidates(tmp_path / "missing.json") == []


def _lan_app(tmp_path, https=True, **cfg_kw):
    cfg = Config(devices_file=tmp_path / "d.json", audit_log=tmp_path / "a.log",
                 trust_file=tmp_path / "n.json", **cfg_kw)
    store = DeviceStore(cfg.devices_file)
    _, token = store.pair("phone")
    app = create_app(cfg, locator=FakeLocator(), http=fake_http(), tailnet=FakeTailnet({}), devices=store, owner_login=None)
    base = "https://192.168.1.10:8650" if https else "http://192.168.1.10:8650"
    return TestClient(app, client=("192.168.1.50", 50000), base_url=base), token


def test_lan_is_refused_unless_enabled(tmp_path):
    c, token = _lan_app(tmp_path)
    r = c.get("/v1/me", headers={"Authorization": f"Bearer {token}"})
    assert r.status_code == 403 and r.json()["error"]["code"] == "forbidden_network"


def test_lan_peer_is_accepted_on_device_token_over_tls(tmp_path):
    c, token = _lan_app(tmp_path, lan=True)
    r = c.get("/v1/me", headers={"Authorization": f"Bearer {token}"})
    assert r.status_code == 200
    body = r.json()
    assert body["via"] == "lan" and body["peer_node"] == "lan:192.168.1.50"
    assert c.get("/v1/me").status_code == 401
    assert c.get("/v1/me", headers={"Authorization": "Bearer hrb_wrong"}).status_code == 401


def test_lan_peer_over_plain_http_is_refused(tmp_path):
    # The token would cross the Wi-Fi in clear text; LAN is HTTPS-only.
    c, token = _lan_app(tmp_path, https=False, lan=True)
    r = c.get("/v1/me", headers={"Authorization": f"Bearer {token}"})
    assert r.status_code == 403 and r.json()["error"]["code"] == "forbidden_network"


def test_public_address_is_refused_even_with_lan_on(tmp_path):
    cfg = Config(devices_file=tmp_path / "d.json", audit_log=tmp_path / "a.log", lan=True)
    store = DeviceStore(cfg.devices_file)
    _, token = store.pair("phone")
    app = create_app(cfg, locator=FakeLocator(), http=fake_http(), tailnet=FakeTailnet({}), devices=store, owner_login=None)
    c = client(app, "203.0.113.9")  # TEST-NET-3, globally routable
    r = c.get("/v1/me", headers={"Authorization": f"Bearer {token}"})
    assert r.status_code == 403 and r.json()["error"]["code"] == "forbidden_network"


def test_me_reports_both_networks(tmp_path, monkeypatch):
    from hermes_remote_bridge import app as app_mod
    monkeypatch.setattr(app_mod, "serving_lan_ips", lambda enabled, trust: ["192.168.1.10"])
    monkeypatch.setattr(app_mod, "network_info", lambda trust: {"id": "abc", "name": "Home", "trusted": True})
    c, token = _lan_app(tmp_path, lan=True)
    body = c.get("/v1/me", headers={"Authorization": f"Bearer {token}"}).json()
    assert body["addresses"] == {"lan": ["192.168.1.10"], "tailnet": ["100.1.2.3"]}
    assert body["lan_scheme"] == "https" and body["network"]["trusted"] is True


def test_private_and_tailnet_ranges_are_disjoint():
    from hermes_remote_bridge.tailnet import is_private_lan_ip, is_tailnet_ip
    assert is_private_lan_ip("192.168.1.10") and is_private_lan_ip("10.1.2.3")
    assert not is_private_lan_ip("100.64.0.20")  # tailnet CGNAT is not "private LAN"
    assert not is_private_lan_ip("127.0.0.1") and not is_private_lan_ip("203.0.113.9")
    assert not is_private_lan_ip("garbage")
    assert is_tailnet_ip("100.64.0.20") and not is_tailnet_ip("192.168.1.10")


def test_pairing_code_puts_trusted_lan_first_and_tailscale_as_fallback(monkeypatch):
    from hermes_remote_bridge import cli
    monkeypatch.setattr(cli, "serving_lan_ips", lambda enabled, trust: ["192.168.1.10"])
    monkeypatch.setattr(cli, "sync_tailscale_ips", lambda: (["100.64.0.20", "fd7a::1"], "me@x"))
    assert cli._bridge_urls(Config(lan=True, port=8650)) == ["https://192.168.1.10:8650", "http://100.64.0.20:8650"]


def test_pairing_code_works_without_tailscale(monkeypatch):
    from hermes_remote_bridge import cli
    def down():
        raise OSError("tailscaled not running")
    monkeypatch.setattr(cli, "serving_lan_ips", lambda enabled, trust: ["192.168.1.10"])
    monkeypatch.setattr(cli, "sync_tailscale_ips", down)
    assert cli._bridge_urls(Config(lan=True, port=8650)) == ["https://192.168.1.10:8650"]


def test_untrusted_network_serves_no_lan(tmp_path, monkeypatch):
    from hermes_remote_bridge import network
    from hermes_remote_bridge.network import Network, TrustStore
    cafe = Network("cafe0001", "Cafe WiFi", "wlan0", ("10.0.0.7",))
    monkeypatch.setattr(network, "current_network", lambda: cafe)
    monkeypatch.setattr(network, "_default_route_ips", lambda: {"10.0.0.7"})
    trust = TrustStore(tmp_path / "n.json")
    assert network.serving_lan_ips(True, trust) == []
    trust.trust(cafe)
    assert network.serving_lan_ips(True, trust) == ["10.0.0.7"]
    assert network.serving_lan_ips(False, trust) == []  # lan off overrides trust
    if os.name == "posix":
        assert oct((tmp_path / "n.json").stat().st_mode & 0o777) == "0o600"
    trust.untrust("cafe0001")
    assert network.serving_lan_ips(True, trust) == []


def test_same_ssid_behind_a_different_router_is_a_different_network(monkeypatch):
    from hermes_remote_bridge import network
    monkeypatch.setattr(network, "_default_route", lambda: ("wlan0", "192.168.1.1"))
    monkeypatch.setattr(network, "_interface_ips", lambda: {"wlan0": ["192.168.1.10"]})
    monkeypatch.setattr(network, "_network_profile", lambda iface: ("uuid-home", "HomeWiFi"))
    monkeypatch.setattr(network, "_gateway_mac", lambda gw: "aa:aa:aa:aa:aa:aa")
    # fresh=True: this is the router-swap case, where a cached identity would be a security bug.
    home = network.current_network(fresh=True)
    monkeypatch.setattr(network, "_gateway_mac", lambda gw: "bb:bb:bb:bb:bb:bb")
    impostor = network.current_network(fresh=True)
    assert home.name == impostor.name == "HomeWiFi" and home.id != impostor.id
    monkeypatch.setattr(network, "_gateway_mac", lambda gw: None)
    assert network.current_network(fresh=True) is None  # unknown router -> never trusted


def test_tls_identity_is_created_once_with_private_key(tmp_path):
    from hermes_remote_bridge.tls import cert_pin, ensure_identity
    cert, key = ensure_identity(tmp_path / "tls")
    pin = cert_pin(cert)
    assert len(pin) == 43
    if os.name == "posix":  # Windows has no mode bits; the profile ACL protects the key
        assert oct(key.stat().st_mode & 0o777) == "0o600"
    assert cert_pin(ensure_identity(tmp_path / "tls")[0]) == pin  # stable across restarts



def test_retired_config_keys_still_load(tmp_path):
    p = tmp_path / "config.toml"
    p.write_text("lan = true\nhermes_url = 'http://127.0.0.1:8642'\nkrdp_port = 3389\n")
    assert Config.load(p).lan is True
    p.write_text("bogus = 1\n")
    with pytest.raises(ValueError):
        Config.load(p)


def test_media_serves_only_images_in_hermes_image_folders(tmp_path):
    home = tmp_path / "hermes"
    (home / "images").mkdir(parents=True)
    (home / "images" / "upload_1.png").write_bytes(b"\x89PNG\r\n\x1a\nimg")
    (home / "images" / "notes.txt").write_text("secret")
    (home / "profiles" / "developer" / "images").mkdir(parents=True)
    (home / "profiles" / "developer" / "images" / "d.jpg").write_bytes(b"\xff\xd8\xffjpg")
    (home / "profiles" / "bkm" / "images").mkdir(parents=True)
    (home / "profiles" / "bkm" / "images" / "b.png").write_bytes(b"png")
    (home / ".env.png").write_text("not in an image folder")
    (home / "images" / "link.png").symlink_to(home / ".env.png")
    c, token = _profile_app(tmp_path, [], hermes_home=home, allowed_profiles=["developer"])

    def get(path, **kw):
        return c.get("/v1/media", params={"path": str(path)}, **kw)

    r = get(home / "images" / "upload_1.png", headers=auth(token))
    assert r.status_code == 200 and r.headers["content-type"] == "image/png" and r.content.endswith(b"img")
    assert get(home / "profiles" / "developer" / "images" / "d.jpg", headers=auth(token)).status_code == 200
    assert get(home / "images" / "upload_1.png").status_code == 401
    assert get(home / "images" / "notes.txt", headers=auth(token)).status_code == 415
    assert get(home / ".env.png", headers=auth(token)).status_code == 403
    assert get(home / "images" / "link.png", headers=auth(token)).status_code == 403  # symlink out of the folder
    assert get(home / "images" / ".." / ".env.png", headers=auth(token)).status_code == 403
    assert get(home / "profiles" / "bkm" / "images" / "b.png", headers=auth(token)).status_code == 403  # not allowed
    assert get(home / "images" / "missing.png", headers=auth(token)).status_code == 404


def test_chat_file_serves_only_files_the_chat_names(tmp_path):
    import sqlite3
    home = tmp_path / "hermes"
    work = tmp_path / "work"
    (home / "attachments").mkdir(parents=True)
    work.mkdir()
    (home / "attachments" / "sheet one.xlsx").write_bytes(b"PK-sheet")
    (work / "report.pdf").write_bytes(b"%PDF-1")
    (work / "secret.txt").write_text("not named")
    (home / "old.csv").write_text("a,b")
    db = sqlite3.connect(home / "state.db")
    db.execute("CREATE TABLE sessions (id TEXT, parent_session_id TEXT, cwd TEXT)")
    db.execute("CREATE TABLE messages (session_id TEXT, content TEXT)")
    db.executemany("INSERT INTO sessions VALUES (?,?,?)", [("s2", "s1", str(work)), ("s1", None, str(work))])
    db.executemany("INSERT INTO messages VALUES (?,?)", [
        ("s2", f"fix this\n@file:`{home}/attachments/sheet one.xlsx`"),
        ("s2", "Done:\nMEDIA:report.pdf\n"),
        ("s1", f"earlier\nMEDIA:{home}/old.csv"),  # before compression: still this chat
        ("other", f"MEDIA:{work}/secret.txt")])
    db.commit(); db.close()
    c, token = _profile_app(tmp_path, [], hermes_home=home)

    def get(sid, path, **kw):
        return c.get(f"/v1/sessions/{sid}/file", params={"path": str(path)}, **kw)

    r = get("s2", home / "attachments" / "sheet one.xlsx", headers=auth(token))
    assert r.status_code == 200 and r.content == b"PK-sheet"
    assert "sheet%20one.xlsx" in r.headers["content-disposition"] or "sheet one.xlsx" in r.headers["content-disposition"]
    assert get("s2", work / "report.pdf", headers=auth(token)).headers["content-type"] == "application/pdf"
    assert get("s2", "report.pdf", headers=auth(token)).status_code == 200  # as written, relative to the chat folder
    assert get("s2", home / "old.csv", headers=auth(token)).status_code == 200
    assert get("s2", work / "secret.txt", headers=auth(token)).status_code == 403  # another chat's file
    assert get("s2", work / ".." / "work" / "secret.txt", headers=auth(token)).status_code == 403
    assert get("nope", work / "report.pdf", headers=auth(token)).status_code == 403
    assert get("s2", work / "report.pdf").status_code == 401
    (work / "report.pdf").unlink()
    assert get("s2", work / "report.pdf", headers=auth(token)).status_code == 404



def test_chat_file_info_names_size_and_path_without_downloading(tmp_path):
    import sqlite3
    home = tmp_path / "hermes"
    work = tmp_path / "work"
    (home / "images").mkdir(parents=True)
    work.mkdir()
    (work / "report.pdf").write_bytes(b"%PDF-1234")
    (work / "secret.txt").write_text("not named")
    (home / "images" / "shot.png").write_bytes(b"\x89PNG")
    db = sqlite3.connect(home / "state.db")
    db.execute("CREATE TABLE sessions (id TEXT, parent_session_id TEXT, cwd TEXT)")
    db.execute("CREATE TABLE messages (session_id TEXT, content TEXT)")
    db.execute("INSERT INTO sessions VALUES ('s1', NULL, ?)", (str(work),))
    db.executemany("INSERT INTO messages VALUES (?,?)", [("s1", "MEDIA:report.pdf"), ("s1", "MEDIA:gone.zip")])
    db.commit(); db.close()
    c, token = _profile_app(tmp_path, [], hermes_home=home)

    def info(path, **kw):
        return c.get("/v1/sessions/s1/file/info", params={"path": str(path)}, **kw)

    r = info("report.pdf", headers=auth(token)).json()
    assert r["path"] == str(work / "report.pdf") and r["name"] == "report.pdf"
    assert r["size"] == 9 and r["mime"] == "application/pdf" and r["exists"] is True and r["modified"] > 0
    assert info(home / "images" / "shot.png", headers=auth(token)).json()["size"] == 4  # Hermes' image folder
    assert info("gone.zip", headers=auth(token)).json() == {"path": str(work / "gone.zip"), "name": "gone.zip", "exists": False}
    assert info(work / "secret.txt", headers=auth(token)).status_code == 403
    assert info("report.pdf").status_code == 401

def test_project_idea_reads_and_writes_only_the_projects_own_idea_md(tmp_path):
    import sqlite3
    home = tmp_path / "hermes"
    folder = tmp_path / "my app"
    home.mkdir(); folder.mkdir()
    db = sqlite3.connect(home / "projects.db")
    db.execute("CREATE TABLE projects (id TEXT PRIMARY KEY, primary_path TEXT)")
    db.executemany("INSERT INTO projects VALUES (?,?)", [("p_1", str(folder)), ("p_nofolder", None)])
    db.commit(); db.close()
    c, token = _profile_app(tmp_path, [], hermes_home=home)
    url = "/v1/projects/p_1/idea"

    r = c.get(url, headers=auth(token)).json()
    assert r == {"path": str(folder / "IDEA.md"), "exists": False, "text": ""}
    assert c.put(url, json={"text": "# My app\nA shop app."}, headers=auth(token)).status_code == 200
    assert (folder / "IDEA.md").read_text() == "# My app\nA shop app.\n"
    assert c.get(url, headers=auth(token)).json()["text"] == "# My app\nA shop app.\n"
    c.put(url, json={"text": "  "}, headers=auth(token))
    assert not (folder / "IDEA.md").exists()  # emptied = removed
    assert c.get("/v1/projects/p_nofolder/idea", headers=auth(token)).status_code == 404
    assert c.get("/v1/projects/nope/idea", headers=auth(token)).status_code == 404
    assert c.get("/v1/projects/..%2F..%2Fetc/idea", headers=auth(token)).status_code in (400, 404)
    assert c.get(url).status_code == 401
