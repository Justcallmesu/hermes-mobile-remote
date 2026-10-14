"""Phone hotspots get a new random gateway MAC on every start; trust must follow the profile."""
import json

from hermes_remote_bridge import network
from hermes_remote_bridge.network import Network, TrustStore, is_random_mac


def _net(monkeypatch, mac, profile=("uuid-hotspot", "iQOO Z10 5G")) -> Network:
    monkeypatch.setattr(network, "_default_route", lambda: ("wlan0", "10.42.0.1"))
    monkeypatch.setattr(network, "_interface_ips", lambda: {"wlan0": ["10.42.0.7"]})
    monkeypatch.setattr(network, "_network_profile", lambda iface: profile)
    monkeypatch.setattr(network, "_gateway_mac", lambda gw: mac)
    net = network.current_network(fresh=True)
    assert net is not None
    return net


def test_random_mac_detection():
    assert is_random_mac("da:a1:19:00:00:01") and is_random_mac("02:00:00:00:00:00")
    assert not is_random_mac("00:1a:2b:3c:4d:5e") and not is_random_mac("garbage")


def test_hotspot_stays_trusted_across_new_random_macs(tmp_path, monkeypatch):
    trust = TrustStore(tmp_path / "n.json")
    first = _net(monkeypatch, "da:00:00:00:00:01")
    assert first.roaming
    trust.trust(first)
    again = _net(monkeypatch, "de:00:00:00:00:02")
    assert again.id != first.id and trust.is_trusted(again)
    trust.trust(again)  # trusting it again replaces, never appends
    assert len(trust.list()) == 1


def test_real_router_still_needs_its_own_mac(tmp_path, monkeypatch):
    trust = TrustStore(tmp_path / "n.json")
    home = _net(monkeypatch, "00:1a:2b:3c:4d:5e", ("uuid-home", "HomeWiFi"))
    assert not home.roaming
    trust.trust(home)
    impostor = _net(monkeypatch, "00:99:99:99:99:99", ("uuid-home", "HomeWiFi"))
    assert not trust.is_trusted(impostor)
    # A random-MAC network on the same profile does not inherit a real router's trust either.
    spoof = _net(monkeypatch, "da:00:00:00:00:09", ("uuid-home", "HomeWiFi"))
    assert not trust.is_trusted(spoof)


def test_other_hotspot_profile_is_not_trusted(tmp_path, monkeypatch):
    trust = TrustStore(tmp_path / "n.json")
    trust.trust(_net(monkeypatch, "da:00:00:00:00:01"))
    other = _net(monkeypatch, "da:00:00:00:00:01", ("uuid-other", "Stranger"))
    assert not trust.is_trusted(other)


def test_no_os_profile_never_roams(tmp_path, monkeypatch):
    net = _net(monkeypatch, "da:00:00:00:00:01", None)
    assert not net.roaming and net.profile == ""


def test_old_duplicates_collapse_into_one_entry(tmp_path, monkeypatch):
    path = tmp_path / "n.json"
    path.write_text(json.dumps({"trusted": {
        "old1": {"name": "iQOO Z10 5G", "since": 1},
        "old2": {"name": "iQOO Z10 5G", "since": 2},
        "home": {"name": "HomeWiFi", "since": 3},
    }}))
    trust = TrustStore(path)
    net = _net(monkeypatch, "da:00:00:00:00:03")
    assert trust.is_trusted(net)  # migrated from the name-only entries
    entries = trust.list()
    assert sorted(e["name"] for e in entries.values()) == ["HomeWiFi", "iQOO Z10 5G"]
    assert entries[net.id]["roaming"] is True


def test_declined_hotspot_is_not_asked_again(tmp_path, monkeypatch):
    trust = TrustStore(tmp_path / "n.json")
    trust.decline(_net(monkeypatch, "da:00:00:00:00:01"))
    assert trust.is_declined(_net(monkeypatch, "da:00:00:00:00:05"))
    assert not trust.is_declined(Network("x", "x", "wlan0", ("10.0.0.2",)))
