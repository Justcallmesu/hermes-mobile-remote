"""winapi on Linux: struct layout, row filtering, never-raise, and COM use through a fake comtypes."""
import contextlib
import ctypes as c
import sys
import types

import pytest

from hermes_remote_bridge.host import winapi


def _ip(a, b, cc, d):
    return int.from_bytes(bytes([a, b, cc, d]), "little")


def test_struct_sizes_and_key_offsets():
    Sock, Fwd, Net, If = winapi._structs()
    assert (c.sizeof(Sock), c.sizeof(Fwd), c.sizeof(Net), c.sizeof(If)) == (28, 104, 88, 168)
    assert Fwd.DestinationPrefix.offset == 12 and Fwd.NextHop.offset == 44 and Fwd.Metric.offset == 84
    assert Net.InterfaceIndex.offset == 28 and Net.PhysicalAddress.offset == 40
    assert Net.PhysicalAddressLength.offset == 72 and Net.State.offset == 76
    assert If.Metric.offset == 148


def _fwd_row(gw, prefix_len=0, dest=0, metric=10, luid=7):
    _, Fwd, _, _ = winapi._structs()
    r = Fwd()
    r.InterfaceLuid = luid
    r.DestinationPrefix.PrefixLength = prefix_len
    r.DestinationPrefix.Prefix.raw[1] = dest
    r.NextHop.raw[1] = _ip(*gw)
    r.Metric = metric
    return r


class _Lib:
    def __init__(self, status, metric=5):
        self.status, self.metric = status, metric

    def GetIpInterfaceEntry(self, ref):
        if self.status == 0:
            ref._obj.Metric = self.metric
        return self.status


def test_default_routes_ipv4_order_and_metric_fallback(monkeypatch):
    monkeypatch.setattr(winapi, "_table_rows", lambda name, t: [_fwd_row((192, 168, 1, 1)),
                                                                _fwd_row((10, 0, 0, 1), prefix_len=24, dest=1)])
    monkeypatch.setattr(winapi, "_alias", lambda luid: "Ethernet")
    monkeypatch.setattr(winapi, "_ip_helper", lambda: (c, _Lib(0, metric=5)))
    assert winapi.default_routes() == [{"Alias": "Ethernet", "Gateway": "192.168.1.1", "Metric": 15}]
    # interface lookup fails: keep the route with interface metric 0
    monkeypatch.setattr(winapi, "_ip_helper", lambda: (c, _Lib(1)))
    assert winapi.default_routes() == [{"Alias": "Ethernet", "Gateway": "192.168.1.1", "Metric": 10}]


def _net_row(ip, mac, state=2, length=6):
    _, _, Net, _ = winapi._structs()
    r = Net()
    r.Address.raw[1] = _ip(*ip)
    for i, b in enumerate(mac):
        r.PhysicalAddress[i] = b
    r.PhysicalAddressLength = length
    r.State = state
    return r


def test_neighbor_mac_skips_bad_state_and_zero_mac(monkeypatch):
    gw = (192, 168, 1, 1)
    rows = [_net_row(gw, [0xAA] * 6, state=1), _net_row(gw, [0] * 6),
            _net_row(gw, [0xAA, 0xBB, 0xCC, 0xDD, 0xEE, 0xFF])]
    monkeypatch.setattr(winapi, "_table_rows", lambda name, t: rows)
    assert winapi.neighbor_mac("192.168.1.1") == "AA-BB-CC-DD-EE-FF"
    assert winapi.neighbor_mac("10.0.0.1") == ""


def _boom(*a, **k):
    raise OSError("boom")


@pytest.mark.parametrize("name,args,default", [
    ("default_routes", (), []), ("neighbor_mac", ("1.2.3.4",), ""), ("network_profile", ("Wi-Fi",), None),
    ("firewall_profiles_enabled", (), False), ("firewall_rules", (), []), ("bridge_reachable", (8650,), False)])
def test_public_functions_return_default_when_the_os_raises(monkeypatch, name, args, default):
    monkeypatch.setattr(winapi, "_table_rows", _boom)
    monkeypatch.setattr(winapi, "_adapter_guid", _boom)
    monkeypatch.setattr(winapi, "_policy", _boom)
    monkeypatch.setattr(winapi.socket, "create_connection", _boom)
    monkeypatch.setattr(winapi, "_ip_helper", _boom)
    monkeypatch.setitem(sys.modules, "comtypes", None)  # import raises ImportError
    assert getattr(winapi, name)(*args) == default


class _Obj:
    """Stands in for a COM object; `wrapped` marks that GetBestInterface was applied."""
    def __init__(self, **kw):
        self.wrapped = False
        self.__dict__.update(kw)


@pytest.fixture
def fake_comtypes(monkeypatch):
    log = []
    ct = types.ModuleType("comtypes")
    ct.CoInitializeEx = lambda flag: log.append("init")
    ct.CoUninitialize = lambda: log.append("uninit")
    client = types.ModuleType("comtypes.client")

    def best(item):
        item.wrapped = True
        return item
    client.GetBestInterface = best
    client.objects = {}
    client.CreateObject = lambda progid: (log.append("create"), client.objects[progid])[1]
    ct.client = client
    monkeypatch.setitem(sys.modules, "comtypes", ct)
    monkeypatch.setitem(sys.modules, "comtypes.client", client)
    return client, log


def test_network_profile_wraps_items_inside_com(monkeypatch, fake_comtypes):
    client, log = fake_comtypes
    monkeypatch.setattr(winapi, "_adapter_guid", lambda alias: "aaaaaaaa-0000-0000-0000-000000000001")
    net = _Obj(GetNetworkId=lambda: "{3f2a9c1e-0000-4b1b-9c11-aabbccddeeff}", GetName=lambda: "HomeWiFi")
    other = _Obj(GetAdapterId=lambda: "{aaaaaaaa-0000-0000-0000-000000000001}", GetConnectivity=lambda: 0,
                 GetNetwork=lambda: _Obj(GetNetworkId=lambda: "{11111111-0000-0000-0000-000000000000}",
                                         GetName=lambda: "Stale"))
    conn = _Obj(GetAdapterId=lambda: "{AAAAAAAA-0000-0000-0000-000000000001}", GetConnectivity=lambda: 4,
                GetNetwork=lambda: net)
    client.objects[winapi.NETWORK_LIST_MANAGER_CLSID] = _Obj(GetNetworkConnections=lambda: [other, conn])
    assert winapi.network_profile("Wi-Fi") == ("{3F2A9C1E-0000-4B1B-9C11-AABBCCDDEEFF}", "HomeWiFi")
    assert other.wrapped and conn.wrapped and net.wrapped
    assert log == ["init", "create", "uninit"]


def test_firewall_rules_wrap_items_inside_com(fake_comtypes):
    client, log = fake_comtypes
    rule = _Obj(Name="Hermes Mobile Remote", Enabled=True, Direction=1, LocalPorts="8650")
    client.objects["HNetCfg.FwPolicy2"] = _Obj(Rules=[rule])
    assert winapi.firewall_rules() == [{"Name": "Hermes Mobile Remote", "Enabled": True, "Inbound": True,
                                        "LocalPorts": "8650"}]
    assert rule.wrapped and log == ["init", "create", "uninit"]


class _Rules:
    def __init__(self, names=()):
        self.items = [_Obj(Name=n) for n in names]
        self.removed = []

    def Add(self, rule):
        self.items.append(rule)

    def Remove(self, name):
        self.removed.append(name)
        for r in self.items:
            if r.Name == name:
                self.items.remove(r)
                return

    def __iter__(self):
        return iter(list(self.items))


def test_firewall_add_rule_sets_exact_properties(fake_comtypes):
    client, _ = fake_comtypes
    rules = _Rules()
    client.objects["HNetCfg.FwPolicy2"] = _Obj(Rules=rules)
    rule = _Obj()
    client.objects["HNetCfg.FWRule"] = rule
    assert winapi.firewall_add_rule("Hermes Mobile Remote", "desc", 8650, "192.168.0.0/16,10.0.0.0/8") is True
    assert rules.items == [rule]
    assert (rule.Name, rule.Description, rule.Protocol, rule.LocalPorts) == ("Hermes Mobile Remote", "desc", 6, "8650")
    assert (rule.Direction, rule.Action, rule.Profiles, rule.Enabled) == (1, 1, 2, True)
    assert rule.RemoteAddresses == "192.168.0.0/16,10.0.0.0/8"


def test_firewall_remove_rules_deletes_only_the_named_rule(fake_comtypes):
    client, _ = fake_comtypes
    rules = _Rules(["Hermes Mobile Remote", "Other", "Hermes Mobile Remote"])
    client.objects["HNetCfg.FwPolicy2"] = _Obj(Rules=rules)
    assert winapi.firewall_remove_rules("Hermes Mobile Remote") is True
    assert [r.Name for r in rules.items] == ["Other"]
    assert rules.removed == ["Hermes Mobile Remote"] * 2


def test_run_elevated_returns_none_when_uac_is_cancelled(monkeypatch):
    class Shell32:
        def ShellExecuteExW(self, ref):
            return 0

    class Kernel32:
        def GetLastError(self):
            return 1223
    monkeypatch.setattr(winapi, "_shell", lambda: (Shell32(), Kernel32()))
    assert winapi.run_elevated("bridge.exe", "firewall add") is None
