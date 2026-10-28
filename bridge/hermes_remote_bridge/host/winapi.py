"""Win32/COM calls behind WindowsHost, so the bridge never spawns PowerShell (D9).

Everything Windows-only is imported inside the function that needs it, so the module imports
anywhere; tests replace ``windows.winapi`` with a fake that has the same functions. Not exercised
off Windows: see the planner's side-by-side Verify against the old PowerShell answers.
"""
from __future__ import annotations

import socket

AF_INET = 2
NETWORK_LIST_MANAGER_CLSID = "{DCB00C01-570F-4A9B-8D69-199FDBA5723B}"
FW_PROFILES = (1, 2, 4)  # NET_FW_PROFILE2_DOMAIN, PRIVATE, PUBLIC
FW_DIRECTION_IN = 1
BRIDGE_PORT = 8650
_NEIGHBOR_BAD_STATES = (0, 1)  # NlnsUnreachable, NlnsIncomplete


def instance_id(guid: str) -> str:
    """"{UPPER-CASE-GUID}", the form Get-NetConnectionProfile printed as InstanceID."""
    return "{" + guid.strip("{}").upper() + "}"


def _ip_helper():
    import ctypes
    from ctypes import wintypes  # noqa: F401 - import fails off Windows, as intended
    return ctypes, ctypes.WinDLL("iphlpapi")


def _structs():
    import ctypes as c

    class SockaddrInet(c.Union):
        _fields_ = [("raw", c.c_uint32 * 7)]  # 28 bytes, 4-aligned; word 1 is the IPv4 address

    class Prefix(c.Structure):
        _fields_ = [("Prefix", SockaddrInet), ("PrefixLength", c.c_uint8)]

    class ForwardRow(c.Structure):
        _fields_ = [("InterfaceLuid", c.c_uint64), ("InterfaceIndex", c.c_uint32),
                    ("DestinationPrefix", Prefix), ("NextHop", SockaddrInet),
                    ("SitePrefixLength", c.c_uint8), ("ValidLifetime", c.c_uint32),
                    ("PreferredLifetime", c.c_uint32), ("Metric", c.c_uint32),
                    ("Protocol", c.c_uint32), ("Loopback", c.c_uint8),
                    ("AutoconfigureAddress", c.c_uint8), ("Publish", c.c_uint8),
                    ("Immortal", c.c_uint8), ("Age", c.c_uint32), ("Origin", c.c_uint32)]

    class NetRow(c.Structure):
        _fields_ = [("Address", SockaddrInet), ("InterfaceIndex", c.c_uint32),
                    ("InterfaceLuid", c.c_uint64), ("PhysicalAddress", c.c_uint8 * 32),
                    ("PhysicalAddressLength", c.c_uint32), ("State", c.c_uint32),
                    ("Flags", c.c_uint8), ("ReachabilityTime", c.c_uint32)]

    class IfRow(c.Structure):
        _fields_ = [("Family", c.c_uint16), ("InterfaceLuid", c.c_uint64),
                    ("InterfaceIndex", c.c_uint32), ("MaxReassemblySize", c.c_uint32),
                    ("InterfaceIdentifier", c.c_uint64), ("MinRaInterval", c.c_uint32),
                    ("MaxRaInterval", c.c_uint32), ("Flags", c.c_uint8 * 9),
                    ("RouterDiscoveryBehavior", c.c_uint32), ("DadTransmits", c.c_uint32),
                    ("BaseReachableTime", c.c_uint32), ("RetransmitTime", c.c_uint32),
                    ("PathMtuDiscoveryTimeout", c.c_uint32), ("LinkLocalAddressBehavior", c.c_uint32),
                    ("LinkLocalAddressTimeout", c.c_uint32), ("ZoneIndices", c.c_uint32 * 16),
                    ("SitePrefixLength", c.c_uint32), ("Metric", c.c_uint32), ("NlMtu", c.c_uint32),
                    ("Connected", c.c_uint8), ("SupportsWakeUpPatterns", c.c_uint8),
                    ("SupportsNeighborDiscovery", c.c_uint8), ("SupportsRouterDiscovery", c.c_uint8),
                    ("ReachableTime", c.c_uint32), ("TransmitOffload", c.c_uint8),
                    ("ReceiveOffload", c.c_uint8), ("DisableDefaultRoutes", c.c_uint8)]

    return SockaddrInet, ForwardRow, NetRow, IfRow


def _table_rows(fn_name: str, row_type):
    """Call GetIpForwardTable2 / GetIpNetTable2 and yield the rows (header is 8 bytes: count + pad)."""
    import ctypes as c
    _, lib = _ip_helper()
    ptr = c.c_void_p()
    if getattr(lib, fn_name)(AF_INET, c.byref(ptr)) != 0 or not ptr.value:
        return []
    try:
        count = c.c_uint32.from_address(ptr.value).value
        size = c.sizeof(row_type)
        return [row_type.from_buffer_copy(c.string_at(ptr.value + 8 + i * size, size)) for i in range(count)]
    finally:
        lib.FreeMibTable(ptr)


def _ipv4(addr) -> str:
    return socket.inet_ntoa(addr.raw[1].to_bytes(4, "little"))


def _alias(luid: int) -> str:
    import ctypes as c
    _, lib = _ip_helper()
    buf = c.create_unicode_buffer(257)
    if lib.ConvertInterfaceLuidToAlias(c.byref(c.c_uint64(luid)), buf, 257) != 0:
        return ""
    return buf.value


def default_routes() -> list[dict]:
    """IPv4 0.0.0.0/0 routes as {Alias, Gateway, Metric (route + interface metric)}."""
    import ctypes as c
    _, lib = _ip_helper()
    _, ForwardRow, _, IfRow = _structs()
    out = []
    for row in _table_rows("GetIpForwardTable2", ForwardRow):
        if row.DestinationPrefix.PrefixLength != 0 or row.DestinationPrefix.Prefix.raw[1] != 0:
            continue
        iface = IfRow()
        iface.Family = AF_INET
        iface.InterfaceLuid = row.InterfaceLuid
        if lib.GetIpInterfaceEntry(c.byref(iface)) != 0:
            continue
        out.append({"Alias": _alias(row.InterfaceLuid), "Gateway": _ipv4(row.NextHop),
                    "Metric": row.Metric + iface.Metric})
    return out


def neighbor_mac(ip: str) -> str:
    """"AA-BB-CC-DD-EE-FF" for a reachable neighbour, "" if none."""
    _, _, NetRow, _ = _structs()
    for row in _table_rows("GetIpNetTable2", NetRow):
        if _ipv4(row.Address) == ip and row.State not in _NEIGHBOR_BAD_STATES and row.PhysicalAddressLength == 6:
            return "-".join(f"{b:02X}" for b in row.PhysicalAddress[:6])
    return ""


def _adapter_guid(alias: str) -> str | None:
    import ctypes as c
    _, lib = _ip_helper()
    luid = c.c_uint64()
    if lib.ConvertInterfaceAliasToLuid(alias, c.byref(luid)) != 0:
        return None
    guid = (c.c_ubyte * 16)()
    if lib.ConvertInterfaceLuidToGuid(c.byref(luid), guid) != 0:
        return None
    import uuid
    return str(uuid.UUID(bytes_le=bytes(guid)))


def network_profile(alias: str) -> tuple[str, str] | None:
    """(InstanceID-style network GUID, name) of the network the interface is connected to."""
    guid = _adapter_guid(alias)
    if not guid:
        return None
    import comtypes.client
    nlm = comtypes.client.CreateObject(NETWORK_LIST_MANAGER_CLSID)
    for conn in nlm.GetNetworkConnections():
        if str(conn.GetAdapterId()).strip("{}").lower() == guid.lower():
            net = conn.GetNetwork()
            return instance_id(str(net.GetNetworkId())), str(net.GetName() or alias)
    return None


def _policy():
    import comtypes.client
    return comtypes.client.CreateObject("HNetCfg.FwPolicy2")


def firewall_profiles_enabled() -> bool:
    policy = _policy()
    return any(policy.FirewallEnabled(p) for p in FW_PROFILES)


def firewall_rules() -> list[dict]:
    return [{"Name": r.Name, "Enabled": bool(r.Enabled), "Inbound": r.Direction == FW_DIRECTION_IN,
             "LocalPorts": r.LocalPorts or ""} for r in _policy().Rules]


def bridge_reachable() -> bool:
    """The bridge answers on its loopback port: that is what the supervisor/app reports as running."""
    try:
        with socket.create_connection(("127.0.0.1", BRIDGE_PORT), timeout=1.0):
            return True
    except OSError:
        return False
