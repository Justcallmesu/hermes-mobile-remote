"""Session search from the phone: Hermes' own message search, shaped like the sidebar list."""
import httpx
import pytest
from fastapi.testclient import TestClient

from hermes_remote_bridge.app import SNIPPET_MAX_CHARS, create_app
from hermes_remote_bridge.config import Config
from hermes_remote_bridge.devices import DeviceStore

from test_cron import Locator, Tailnet

PHONE = "100.64.0.10"

# Shaped like Hermes' /api/sessions/search results: a text hit, an archived chat and a cron run.
RESULTS = [
    {"session_id": "tip1", "lineage_root": "root1", "id": "tip1", "title": "Router setup",
     "preview": "help me", "source": "desktop", "model": "sonnet", "message_count": 12,
     "last_active": None, "started_at": 100.0, "archived": False, "role": "assistant",
     "snippet": "...set the >>>firewall<<< rule\non port 22...", "input_tokens": 9},
    {"session_id": "old", "title": "Archived", "source": "desktop", "archived": True, "snippet": "firewall"},
    {"session_id": "cron_x_1", "title": "Digest", "source": "cron", "archived": False, "snippet": "firewall"},
]


@pytest.fixture
def bridge(tmp_path):
    calls = []

    def handler(request: httpx.Request):
        calls.append(request)
        if request.url.path == "/api/sessions/search":
            return httpx.Response(200, json={"results": RESULTS})
        return httpx.Response(404, json={"detail": "nope"})

    def make(**cfg_kw):
        cfg = Config(devices_file=tmp_path / "d.json", audit_log=tmp_path / "a.log", **cfg_kw)
        store = DeviceStore(cfg.devices_file)
        _, token = store.pair("phone")
        app = create_app(cfg, locator=Locator(), http=httpx.AsyncClient(transport=httpx.MockTransport(handler)),
                         tailnet=Tailnet(), devices=store, owner_login="me@x")
        return TestClient(app, client=(PHONE, 50000)), {"Authorization": f"Bearer {token}"}

    return make, calls


def test_search_returns_sidebar_rows_with_a_clean_snippet(bridge):
    make, calls = bridge
    c, auth = make()
    body = c.get("/v1/sessions/search?q=firewall", headers=auth).json()
    assert body == {"sessions": [{"id": "tip1", "title": "Router setup", "preview": "help me", "source": "desktop",
                                  "message_count": 12, "last_active": 100.0, "model": "sonnet", "pinned": False,
                                  "snippet": "...set the firewall rule on port 22..."}]}
    q = dict(calls[-1].url.params)
    assert calls[-1].url.path == "/api/sessions/search"
    assert q["q"] == "firewall" and q["exclude_sources"] == "cron" and "profile" not in q


def test_search_is_profile_scoped(bridge):
    make, calls = bridge
    c, auth = make(allowed_profiles=["developer"])
    assert c.get("/v1/sessions/search?q=fire&profile=developer", headers=auth).status_code == 200
    assert dict(calls[-1].url.params)["profile"] == "developer"
    assert c.get("/v1/sessions/search?q=fire&profile=bkm-reports-bot", headers=auth).status_code == 403


def test_search_validates_query_and_caps_limit(bridge):
    make, calls = bridge
    c, auth = make()
    for bad in ("", "%20%20", "x" * 201):
        r = c.get(f"/v1/sessions/search?q={bad}", headers=auth)
        assert r.status_code == 400 and r.json()["error"]["code"] == "invalid_query"
    assert not calls
    c.get("/v1/sessions/search?q=fire&limit=10000", headers=auth)
    assert int(dict(calls[-1].url.params)["limit"]) <= 100


def test_search_needs_a_paired_device(bridge):
    make, calls = bridge
    c, _ = make()
    assert c.get("/v1/sessions/search?q=fire").status_code == 401
    assert not calls


def test_long_snippets_are_cut():
    from hermes_remote_bridge.app import _snippet
    assert len(_snippet("word " * 200)) == SNIPPET_MAX_CHARS
    assert _snippet(None) == ""
