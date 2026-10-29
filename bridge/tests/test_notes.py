"""Notes: Markdown files the phone and Hermes share."""
import httpx
from fastapi.testclient import TestClient

from hermes_remote_bridge import cli
from hermes_remote_bridge.app import create_app
from hermes_remote_bridge.config import Config
from hermes_remote_bridge.devices import DeviceStore
from hermes_remote_bridge.notes import NoteStore, parse, render

from test_cron import Locator, Tailnet


def test_round_trip_and_hand_written_files(tmp_path):
    s = NoteStore(tmp_path)
    n = s.create(title="Weekend", body="- [ ] paint\n", labels=["plans", "plans"], color="yellow", pinned=True)
    got = s.get(n.id)
    assert (got.title, got.labels, got.color, got.pinned) == ("Weekend", ["plans"], "yellow", True)
    assert parse(n.id, render(got)) == got
    (tmp_path / "abcd-hand.md").write_text("just text Hermes wrote\n")  # no header at all
    hand = s.get("abcd-hand")
    assert hand.body == "just text Hermes wrote\n" and hand.updated > 0
    assert [x.id for x in s.list()][0] == n.id  # pinned first


def test_search_archive_and_bad_ids(tmp_path):
    s = NoteStore(tmp_path)
    a = s.create(title="Odoo", body="invoice report")
    s.create(title="Groceries", body="milk")
    s.update(a.id, archived=True)
    assert [n.title for n in s.list("invoice")] == []
    assert [n.title for n in s.list("invoice", archived=True)] == ["Odoo"]
    s.append(a.id, "- [ ] check totals")
    assert s.get(a.id).body.endswith("- [ ] check totals\n")
    for bad in ("../x", "a/b", ""):
        try:
            s.get(bad)
            assert False
        except KeyError:
            pass


def test_cli_for_hermes(tmp_path, capsys, monkeypatch):
    monkeypatch.setattr(cli.Config, "load", classmethod(lambda c, p=None: Config(notes_dir=tmp_path)))
    cli.main(["notes", "add", "--title", "Plan", "- [ ] one\\n- [ ] two"])
    nid = NoteStore(tmp_path).list()[0].id
    cli.main(["notes", "check", nid, "two"])
    assert "- [x] two" in NoteStore(tmp_path).get(nid).body
    cli.main(["notes", "edit", nid, "--add-label", "work", "--pin"])
    capsys.readouterr()
    cli.main(["notes", "list"])
    assert "Plan" in capsys.readouterr().out and NoteStore(tmp_path).list()[0].labels == ["work"]


def test_phone_routes(tmp_path):
    cfg = Config(devices_file=tmp_path / "d.json", audit_log=tmp_path / "a.log", notes_dir=tmp_path / "notes")
    store = DeviceStore(cfg.devices_file)
    _, token = store.pair("phone")
    app = create_app(cfg, locator=Locator(), http=httpx.AsyncClient(transport=httpx.MockTransport(lambda r: httpx.Response(404))),
                     tailnet=Tailnet(), devices=store, owner_login="me@x")
    c, h = TestClient(app, client=("100.64.0.10", 50000)), {"Authorization": f"Bearer {token}"}
    n = c.post("/v1/notes", json={"title": "Idea", "body": "x"}, headers=h).json()
    assert c.patch(f"/v1/notes/{n['id']}", json={"pinned": True}, headers=h).json()["pinned"] is True
    assert [x["title"] for x in c.get("/v1/notes", headers=h).json()["notes"]] == ["Idea"]
    assert c.get("/v1/notes/nope-9999", headers=h).status_code == 404
    assert c.get("/v1/notes", params={"q": "x"}).status_code == 401
    assert c.delete(f"/v1/notes/{n['id']}", headers=h).json()["deleted"]
