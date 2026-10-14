"""Cron jobs from the phone: list, runs, pause/resume/run now, scoped to one Hermes profile."""
import json

import httpx
import pytest
from fastapi.testclient import TestClient

from hermes_remote_bridge.app import create_app
from hermes_remote_bridge.backend import Backend
from hermes_remote_bridge.config import Config
from hermes_remote_bridge.devices import DeviceStore

PHONE = "100.64.0.10"

# One job per profile, shaped like Hermes' /api/cron/jobs rows (including the fields the phone
# must not receive).
JOBS = {
    "default": [{"id": "a1b2c3d4e5f6", "name": "Daily digest", "schedule_display": "every day at 08:00",
                 "state": "scheduled", "enabled": True, "next_run_at": "2026-10-15T08:00:00+07:00",
                 "last_run_at": "2026-10-14T08:00:00+07:00", "last_status": "ok", "last_error": None,
                 "failure_streak": 0, "model": "sonnet", "prompt": "Summarise my inbox",
                 "script": None, "deliver": "telegram", "workdir": "/home/u/secret", "profile": "default"}],
    "bkm-reports-bot": [{"id": "ffffeeeedddd", "name": "Report", "schedule_display": "hourly",
                         "state": "scheduled", "enabled": True, "prompt": "Send the BKM report"}],
}


class Locator:
    async def get(self, *, start=True):
        return Backend(1234, "tok")

    def forget(self, backend=None):
        pass

    async def aclose(self):
        pass


class Tailnet:
    async def whois(self, addr):
        return {"login": "me@x", "node": "phone", "os": "android"}

    async def status_self_ips(self):
        return []

    async def aclose(self):
        pass


@pytest.fixture
def bridge(tmp_path):
    calls = []

    def handler(request: httpx.Request):
        calls.append(request)
        path, q = request.url.path, dict(request.url.params)
        if path == "/api/cron/jobs" and request.method == "GET":
            return httpx.Response(200, json=JOBS.get(q.get("profile"), []))
        if path.endswith("/runs"):
            return httpx.Response(200, json={"runs": [{"id": "cron_a1b2c3d4e5f6_1", "title": "Daily digest",
                                                       "preview": "3 new mails", "source": "cron",
                                                       "started_at": 1.0, "system_prompt": "secret"}],
                                             "limit": 10})
        if request.method == "POST" and path == "/api/cron/jobs":
            body = json.loads(request.content)
            return httpx.Response(200, json=dict(JOBS["default"][0], id="newjob000001", name=body["name"],
                                                 prompt=body["prompt"], state="scheduled"))
        if request.method == "POST":
            job = dict(JOBS["default"][0], state="paused" if path.endswith("/pause") else "scheduled")
            return httpx.Response(200, json=job)
        return httpx.Response(404, json={"detail": "nope"})

    def make(**cfg_kw):
        cfg = Config(devices_file=tmp_path / "d.json", audit_log=tmp_path / "a.log", **cfg_kw)
        store = DeviceStore(cfg.devices_file)
        _, token = store.pair("phone")
        app = create_app(cfg, locator=Locator(), http=httpx.AsyncClient(transport=httpx.MockTransport(handler)),
                         tailnet=Tailnet(), devices=store, owner_login="me@x")
        return TestClient(app, client=(PHONE, 50000)), {"Authorization": f"Bearer {token}"}

    return make, calls, tmp_path


def test_lists_the_profiles_jobs_without_what_they_run(bridge):
    make, calls, _ = bridge
    c, auth = make()
    jobs = c.get("/v1/cron/jobs", headers=auth).json()["jobs"]
    assert [j["name"] for j in jobs] == ["Daily digest"]
    assert jobs[0]["schedule_display"] == "every day at 08:00" and jobs[0]["last_status"] == "ok"
    assert {"prompt", "script", "deliver", "workdir"}.isdisjoint(jobs[0])
    # Without a profile the bridge still names one: Hermes would otherwise list every profile.
    assert dict(calls[-1].url.params)["profile"] == "default"
    assert c.get("/v1/cron/jobs?profile=bkm-reports-bot", headers=auth).json()["jobs"][0]["name"] == "Report"


def test_runs_are_trimmed_to_what_the_list_shows(bridge):
    make, _, _ = bridge
    c, auth = make()
    runs = c.get("/v1/cron/jobs/a1b2c3d4e5f6/runs", headers=auth).json()["runs"]
    assert runs[0]["preview"] == "3 new mails" and "system_prompt" not in runs[0]


@pytest.mark.parametrize("action,state", [("pause", "paused"), ("resume", "scheduled"), ("trigger", "scheduled")])
def test_pause_resume_and_run_now_are_forwarded_and_audited(bridge, action, state):
    make, calls, tmp = bridge
    c, auth = make()
    r = c.post(f"/v1/cron/jobs/a1b2c3d4e5f6/{action}", headers=auth)
    assert r.status_code == 200 and r.json()["job"]["state"] == state
    assert calls[-1].method == "POST" and calls[-1].url.path == f"/api/cron/jobs/a1b2c3d4e5f6/{action}"
    assert f'"event": "cron_{action}"' in (tmp / "a.log").read_text()


def test_only_those_three_actions_exist(bridge):
    make, calls, _ = bridge
    c, auth = make()
    for action in ("delete", "update", "run"):
        assert c.post(f"/v1/cron/jobs/a1b2c3d4e5f6/{action}", headers=auth).status_code in (404, 405)
    assert c.delete("/v1/cron/jobs/a1b2c3d4e5f6", headers=auth).status_code in (404, 405)
    assert not any(r.method in ("DELETE", "PUT") for r in calls)


def test_a_job_of_another_profile_is_out_of_reach(bridge):
    """Hermes resolves a job in ANY profile when the named one does not hold it; the bridge must not."""
    make, calls, _ = bridge
    c, auth = make(allowed_profiles=["developer"])
    # The bot's job id, asked through the default profile.
    r = c.post("/v1/cron/jobs/ffffeeeedddd/trigger", headers=auth)
    assert r.status_code == 404 and r.json()["error"]["code"] == "job_not_found"
    assert c.get("/v1/cron/jobs/ffffeeeedddd/runs", headers=auth).status_code == 404
    # And through the disallowed profile itself.
    assert c.post("/v1/cron/jobs/ffffeeeedddd/trigger?profile=bkm-reports-bot", headers=auth).status_code == 403
    assert not any(r.method == "POST" for r in calls)


def test_needs_a_device_token_and_a_sane_id(bridge):
    make, _, _ = bridge
    c, auth = make()
    assert c.get("/v1/cron/jobs").status_code == 401
    assert c.post("/v1/cron/jobs/a1b2c3d4e5f6/pause").status_code == 401
    assert c.post("/v1/cron/jobs/..%2Fetc/pause", headers=auth).status_code in (400, 404)
    assert json.loads(c.post("/v1/cron/jobs/bad%20id/pause", headers=auth).content)["error"]["code"] == "invalid_id"


def test_creates_a_job_as_a_local_prompt_only(bridge):
    make, calls, _ = bridge
    client, auth = make()
    r = client.post("/v1/cron/jobs", headers=auth, json={
        "name": "Morning", "schedule": "every day 8am", "prompt": "Check the shop email",
        "script": "/tmp/evil.sh", "deliver": "telegram", "workdir": "/etc"})
    assert r.status_code == 200, r.text
    assert r.json()["job"]["id"] == "newjob000001" and "prompt" not in r.json()["job"]
    sent = json.loads(calls[-1].content)
    assert sent == {"name": "Morning", "schedule": "every day 8am", "prompt": "Check the shop email",
                    "deliver": "local", "paused": False}
    assert dict(calls[-1].url.params)["profile"] == "default"


def test_create_needs_auth_and_a_schedule_and_prompt(bridge):
    make, _, _ = bridge
    client, auth = make()
    assert client.post("/v1/cron/jobs", json={"schedule": "30m", "prompt": "x"}).status_code == 401
    assert client.post("/v1/cron/jobs", headers=auth, json={"schedule": "", "prompt": "x"}).status_code in (400, 422)
    assert client.post("/v1/cron/jobs", headers=auth, json={"schedule": "30m", "prompt": ""}).status_code in (400, 422)
