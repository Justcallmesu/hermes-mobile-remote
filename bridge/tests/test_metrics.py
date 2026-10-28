import json
import os
import re
import time

import pytest
from fastapi.testclient import TestClient
from starlette.websockets import WebSocketDisconnect
from test_app import (  # noqa: F401
    PHONE,
    FakeLocator,
    FakeTailnet,
    auth,
    client,
    fake_hermes,
    fake_http,
)

from hermes_remote_bridge import cli
from hermes_remote_bridge.app import create_app
from hermes_remote_bridge.backend import Backend
from hermes_remote_bridge.config import Config
from hermes_remote_bridge.devices import DeviceStore
from hermes_remote_bridge.metrics import (
    BridgeMetrics,
    Registry,
    ensure_metrics_token,
    read_metrics_token,
    summarize,
)

# One sample line of the 0.0.4 text format: name, optional {labels}, value.
SAMPLE = re.compile(r'^[a-zA-Z_:][a-zA-Z0-9_:]*(\{([a-zA-Z_][a-zA-Z0-9_]*="([^"\\]|\\.)*",?)*\})? [-+0-9.eEInf]+$')


def _rpc(rid, method, **params):
    return json.dumps({"jsonrpc": "2.0", "id": rid, "method": method, "params": params})


def _value(text: str, line_start: str) -> float:
    for line in text.splitlines():
        if line.startswith(line_start + " "):
            return float(line.rsplit(" ", 1)[1])
    raise AssertionError(f"no sample {line_start!r} in:\n{text}")


# ---------------------------------------------------------------- registry and format


def test_exposition_format_counters_gauges_histograms():
    r = Registry()
    c = r.counter("x_total", "Things.", ("kind",))
    c.inc(kind='a"b\\c\nd')
    c.inc(2, kind="z")
    r.gauge("g", "A gauge.").set(3.5)
    h = r.histogram("h_seconds", "Times.", ("op",), buckets=(0.1, 1.0))
    for v in (0.05, 0.5, 5.0):
        h.observe(v, op="read")
    text = r.render()
    assert text.endswith("\n")
    assert "# TYPE x_total counter" in text and "# TYPE g gauge" in text and "# TYPE h_seconds histogram" in text
    assert 'x_total{kind="a\\"b\\\\c\\nd"} 1' in text and 'x_total{kind="z"} 2' in text
    assert "g 3.5" in text
    assert r.counter("quiet_total", "Nothing yet.").lines() == ["quiet_total 0"]
    assert 'h_seconds_bucket{op="read",le="0.1"} 1' in text
    assert 'h_seconds_bucket{op="read",le="1"} 2' in text
    assert 'h_seconds_bucket{op="read",le="+Inf"} 3' in text
    assert 'h_seconds_count{op="read"} 3' in text and 'h_seconds_sum{op="read"} 5.55' in text
    for line in text.splitlines():
        assert line.startswith("# ") or SAMPLE.match(line), line


def test_labels_must_match_the_declaration():
    c = Registry().counter("c_total", "C.", ("a",))
    with pytest.raises(ValueError):
        c.inc(b="x")


def test_relay_bookkeeping_rpc_latency_questions_and_turns():
    now = [100.0]
    m = BridgeMetrics("9.9", 2, clock=lambda: now[0])
    a, b = m.connection(), m.connection()
    a.from_phone({"jsonrpc": "2.0", "id": 7, "method": "session.list"})
    now[0] += 0.25
    a.from_hermes(json.dumps({"jsonrpc": "2.0", "id": 7, "result": {}}))
    assert m.rpc_seconds.count(method="session.list") == 1
    # Two phones see one question: counted once, timed to the first settlement.
    ask = json.dumps({"jsonrpc": "2.0", "id": "srq-0123456789ab", "method": "approval", "params": {}})
    a.from_hermes(ask)
    b.from_hermes(ask)
    assert m.questions.get(method="approval") == 1 and m.questions_open.get() == 1
    now[0] += 30
    b.from_phone({"jsonrpc": "2.0", "id": "srq-0123456789ab", "result": {"choice": "once"}})
    a.from_hermes(json.dumps({"jsonrpc": "2.0", "method": "event", "params": {
        "type": "request.cancel", "payload": {"id": "srq-0123456789ab", "reason": "resolved"}}}))
    assert m.questions_ended.get(method="approval", outcome="answered") == 1
    assert m.questions_ended.get(method="approval", outcome="resolved") == 0
    assert m.question_wait.count(method="approval") == 1 and m.questions_open.get() == 0
    # A turn event delivered to both sockets is one turn.
    for conn in (a, b):
        conn.from_hermes(json.dumps({"jsonrpc": "2.0", "method": "event", "params": {
            "type": "message.complete", "session_id": "s", "seq": 4, "payload": {"status": "interrupted"}}}))
    assert m.turns_completed.get(status="interrupted") == 1
    # A question nobody else watches ends unobserved when its socket closes, untimed.
    a.from_hermes(json.dumps({"jsonrpc": "2.0", "id": "srq-aaaaaaaaaaaa", "method": "clarify", "params": {}}))
    a.close()
    assert m.questions_ended.get(method="clarify", outcome="unobserved") == 1
    assert m.question_wait.count(method="clarify") == 0
    # Software-answered requests are not questions.
    b.from_hermes(json.dumps({"jsonrpc": "2.0", "id": "srq-bbbbbbbbbbbb", "method": "preview.read", "params": {}}))
    assert m.questions.get(method="preview.read") == 0


def test_metrics_token_file_is_private_and_stable(tmp_path):
    path = tmp_path / "metrics.token"
    assert read_metrics_token(path) is None
    token = ensure_metrics_token(path)
    assert token.startswith("hrm_") and ensure_metrics_token(path) == token == read_metrics_token(path)
    if os.name == "posix":
        assert oct(os.stat(path).st_mode & 0o777) == "0o600"


# ---------------------------------------------------------------- the endpoint


def _app(tmp_path, port=1234, **cfg_kw):
    cfg = Config(devices_file=tmp_path / "d.json", audit_log=tmp_path / "a.log",
                 metrics_token_file=tmp_path / "metrics.token", **cfg_kw)
    store = DeviceStore(cfg.devices_file)
    _, token = store.pair("alices-pixel")
    http = fake_http({("GET", "/api/sessions"): {"sessions": [], "total": 0}})
    app = create_app(cfg, locator=FakeLocator(Backend(port, "tok")), http=http,
                     tailnet=FakeTailnet({PHONE: "me@example.com"}), devices=store, owner_login="me@example.com")
    return app, token


def test_metrics_need_a_token_even_on_loopback(tmp_path):
    app, token = _app(tmp_path)
    for ip in ("127.0.0.1", PHONE):
        assert client(app, ip).get("/metrics").status_code == 401
        assert client(app, ip).get("/metrics", headers=auth("hrm_wrong")).status_code == 401
    assert client(app, "192.168.1.50").get("/metrics", headers=auth(token)).status_code == 403
    r = client(app, PHONE).get("/metrics", headers=auth(token))
    assert r.status_code == 200 and r.headers["content-type"].startswith("text/plain; version=0.0.4")


def test_local_metrics_token_opens_metrics_and_nothing_else(tmp_path):
    ensure_metrics_token(tmp_path / "metrics.token")
    app, _ = _app(tmp_path)
    local = read_metrics_token(tmp_path / "metrics.token")
    c = client(app, "127.0.0.1")
    assert c.get("/metrics", headers=auth(local)).status_code == 200
    assert c.get("/v1/me", headers=auth(local)).status_code == 401


def test_metrics_off(tmp_path):
    app, token = _app(tmp_path, metrics=False)
    assert client(app, PHONE).get("/metrics", headers=auth(token)).status_code == 404


def test_rest_counters_use_route_templates_not_paths(tmp_path):
    app, token = _app(tmp_path)
    c = client(app, PHONE)
    c.get("/v1/sessions", headers=auth(token))
    c.get("/v1/me")  # 401
    c.patch("/v1/sessions/secret-session-id", headers=auth(token), json={"title": "my private title"})
    c.get("/v1/media", params={"path": "/home/me/private.png"}, headers=auth(token))
    c.get("/no/such/thing-xyz", headers=auth(token))
    text = c.get("/metrics", headers=auth(token)).text
    assert _value(text, 'bridge_http_requests_total{route="/v1/sessions",method="GET",status="200"}') == 1
    assert _value(text, 'bridge_http_requests_total{route="/v1/me",method="GET",status="401"}') == 1
    assert 'route="/v1/sessions/{session_id}",method="PATCH"' in text
    assert 'route="unmatched"' in text
    assert 'bridge_http_request_seconds_count{route="/v1/sessions"} 1' in text
    assert _value(text, "bridge_paired_devices") == 1
    assert 'bridge_info{version="' in text
    for secret in ("secret-session-id", "my private title", "private.png", "thing-xyz", token, "alices-pixel"):
        assert secret not in text


def test_relay_counters_move(tmp_path, fake_hermes):  # noqa: F811 - the fixture
    port, _ = fake_hermes
    app, token = _app(tmp_path, port)
    c = client(app, PHONE)
    with c.websocket_connect("/v1/ws", headers=auth(token)) as ws:
        assert ws.receive_json()["params"]["type"] == "gateway.ready"
        mid = c.get("/metrics", headers=auth(token)).text
        assert _value(mid, 'bridge_ws_connections{endpoint="live"}') == 1
        assert _value(mid, "bridge_live_devices") == 1
        ws.send_text(_rpc(1, "prompt.submit", session_id="a", text="hush, a prompt"))
        ws.receive_json()
        ws.receive_json()
        question = ws.receive_json()
        ws.send_text(json.dumps({"jsonrpc": "2.0", "id": question["id"], "result": {"choice": "once"}}))
        ws.send_text(_rpc(2, "cli.exec", argv=["x"]))
        assert ws.receive_json()["error"]["code"] == -32601
        ws.send_text("not json")
        ws.receive_json()
        ws.send_text(_rpc(3, "ping"))
        assert ws.receive_json()["id"] == 3
    for _ in range(50):  # the relay's close bookkeeping runs just after the socket goes away
        text = c.get("/metrics", headers=auth(token)).text
        if _value(text, 'bridge_ws_connections{endpoint="live"}') == 0:
            break
        time.sleep(0.05)
    assert _value(text, 'bridge_ws_connections_total{endpoint="live"}') == 1
    assert _value(text, "bridge_live_devices") == 0
    assert _value(text, 'bridge_relay_frames_forwarded_total{method="prompt.submit"}') == 1
    assert _value(text, 'bridge_relay_frames_forwarded_total{method="response"}') == 1
    assert _value(text, 'bridge_relay_frames_refused_total{reason="method"}') == 1
    assert _value(text, 'bridge_relay_frames_refused_total{reason="not_json"}') == 1
    assert _value(text, "bridge_relay_frames_from_hermes_total") == 5
    assert _value(text, 'bridge_hermes_rpc_seconds_count{method="ping"}') == 1
    assert _value(text, 'bridge_hermes_rpc_seconds_count{method="prompt.submit"}') == 1
    assert _value(text, 'bridge_questions_total{method="approval"}') == 1
    assert _value(text, 'bridge_questions_ended_total{method="approval",outcome="answered"}') == 1
    assert _value(text, 'bridge_question_wait_seconds_count{method="approval"}') == 1
    assert "hush" not in text and "cli.exec" not in text


def test_ws_rejections_are_counted(tmp_path):
    app, _ = _app(tmp_path)
    with pytest.raises(WebSocketDisconnect), client(app, PHONE).websocket_connect(
            "/v1/ws", headers=auth("hrb_wrong")) as ws:
        ws.receive_json()
    snap = app.state.metrics.registry.snapshot()
    rejected = {tuple(s["labels"].values()): s["value"] for s in snap["bridge_ws_rejected_total"]["samples"]}
    assert rejected == {("live", "unauthorized"): 1}


# ---------------------------------------------------------------- the CLI


def test_cli_summary_reads_the_running_bridge(tmp_path, capsys, monkeypatch):
    ensure_metrics_token(tmp_path / "metrics.token")
    app, token = _app(tmp_path)
    local = TestClient(app, client=("127.0.0.1", 50000))
    local.get("/v1/sessions", headers=auth(token))

    def fetch(cfg):
        r = local.get("/metrics?format=json", headers=auth(read_metrics_token(cfg.metrics_token_file)))
        assert r.status_code == 200
        return r.json()

    monkeypatch.setattr(cli, "_fetch_metrics", fetch)
    cfg_path = tmp_path / "config.toml"
    cfg_path.write_text(f"metrics_token_file = '{(tmp_path / 'metrics.token').as_posix()}'\n")
    cli.main(["--config", str(cfg_path), "metrics", "--json"])
    data = json.loads(capsys.readouterr().out)
    assert data["bridge_http_requests_total"]["type"] == "counter"
    cli.main(["--config", str(cfg_path), "metrics"])
    out = capsys.readouterr().out
    assert out.startswith("Bridge v") and "Devices: 1 paired" in out and "/v1/sessions 1" in out
    assert summarize(data).splitlines()[0].startswith("Bridge v")
