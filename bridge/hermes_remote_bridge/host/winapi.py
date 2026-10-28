"""Win32/COM calls behind WindowsHost, so the bridge never spawns PowerShell (D9).

Everything Windows-only is imported inside the function that needs it, so the module imports
anywhere; tests replace ``windows.winapi`` with a fake that has the same functions. Not exercised
off Windows: see the planner's side-by-side Verify against the old PowerShell answers.
"""
from __future__ import annotations

import functools
import gc
import logging
import socket

log = logging.getLogger(__name__)

AF_INET = 2
NETWORK_LIST_MANAGER_CLSID = "{DCB00C01-570F-4A9B-8D69-199FDBA5723B}"
FW_PROFILES = (1, 2, 4)  # NET_FW_PROFILE2_DOMAIN, PRIVATE, PUBLIC
FW_DIRECTION_IN = 1
FW_PROTOCOL_TCP = 6
FW_ACTION_ALLOW = 1
FW_PROFILE_PRIVATE = 2
SEE_MASK_NOCLOSEPROCESS = 0x40
RPC_E_CHANGED_MODE = -2147417850  # 0x80010106: the thread already has COM in the other mode
BRIDGE_PORT = 8650
_NEIGHBOR_BAD_STATES = (0, 1)  # NlnsUnreachable, NlnsIncomplete


def _safe(default):
    """Public calls never raise (no Windows, no comtypes, COM errors): they return ``default``."""
    def deco(fn):
        @functools.wraps(fn)
        def wrapper(*a, **k):
            try:
                return fn(*a, **k)
            except Exception as e:  # noqa: BLE001 - includes ImportError and OSError
                log.debug("winapi.%s failed: %r", fn.__name__, e)
                return list(default) if isinstance(default, list) else default
        return wrapper
    return deco


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
    if lib.ConvertInterfaceLuidToAlias(c.byref(c.c_uint64(luid)), buf, c.c_size_t(257)) != 0:
        return ""
    return buf.value


@_safe([])
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
        if_metric = iface.Metric if lib.GetIpInterfaceEntry(c.byref(iface)) == 0 else 0
        out.append({"Alias": _alias(row.InterfaceLuid), "Gateway": _ipv4(row.NextHop),
                    "Metric": row.Metric + if_metric})
    return out


@_safe("")
def neighbor_mac(ip: str) -> str:
    """"AA-BB-CC-DD-EE-FF" for a reachable neighbour, "" if none."""
    _, _, NetRow, _ = _structs()
    for row in _table_rows("GetIpNetTable2", NetRow):
        if _ipv4(row.Address) == ip and row.State not in _NEIGHBOR_BAD_STATES and row.PhysicalAddressLength == 6:
            mac = bytes(row.PhysicalAddress[:6])
            if any(mac):
                return "-".join(f"{b:02X}" for b in mac)
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


def _com(fn):
    """Run ``fn`` with COM initialised for this thread (the bridge calls from to_thread workers).

    ``fn`` must return plain values. Its COM objects are released when its frame ends, which is
    BEFORE CoUninitialize here. A ``with`` block inside the function cannot give that order: the
    objects are still the function's locals when the block exits, so their Release() ran after
    CoUninitialize and crashed the process (access violation, seen on Windows 11).
    """
    @functools.wraps(fn)
    def wrapper(*a, **k):
        import comtypes
        try:
            comtypes.CoInitializeEx(comtypes.COINIT_MULTITHREADED)
            initialised = True
        except OSError as e:
            # Importing comtypes already made the MAIN thread an STA, so asking for MTA there fails
            # with RPC_E_CHANGED_MODE. COM is usable as it is; we just must not uninitialise it.
            if getattr(e, "winerror", None) != RPC_E_CHANGED_MODE:
                raise
            initialised = False
        try:
            return fn(*a, **k)
        finally:
            gc.collect()
            if initialised:
                comtypes.CoUninitialize()
    return wrapper


def _wrap(item):
    """Enumerated items arrive as bare IDispatch pointers; get the typed interface."""
    import comtypes.client
    return comtypes.client.GetBestInterface(item)


@_safe(None)
def network_profile(alias: str) -> tuple[str, str] | None:
    """(InstanceID-style network GUID, name) of the network the interface is connected to."""
    guid = _adapter_guid(alias)
    if not guid:
        return None
    return _network_profile_com(alias, guid)


@_com
def _network_profile_com(alias: str, guid: str) -> tuple[str, str] | None:
    import comtypes.client
    nlm = comtypes.client.CreateObject(NETWORK_LIST_MANAGER_CLSID)
    matches = [conn for conn in map(_wrap, nlm.GetNetworkConnections())
               if str(conn.GetAdapterId()).strip("{}").lower() == guid.lower()]
    if not matches:
        return None
    conn = next((m for m in matches if m.GetConnectivity() != 0), matches[0])
    net = _wrap(conn.GetNetwork())
    # The id is the ADAPTER GUID, not INetwork.GetNetworkId(): Get-NetConnectionProfile's InstanceID
    # (what the PowerShell host stored for trusted networks) is the adapter GUID, checked side by
    # side on Windows 11. Returning the network id would untrust every network on upgrade.
    return instance_id(str(conn.GetAdapterId())), str(net.GetName() or alias)


def _policy():
    import comtypes.client
    return comtypes.client.CreateObject("HNetCfg.FwPolicy2")


@_safe(False)
@_com
def firewall_profiles_enabled() -> bool:
    policy = _policy()
    return any(bool(policy.FirewallEnabled(p)) for p in FW_PROFILES)


@_safe([])
@_com
def firewall_rules() -> list[dict]:
    return [{"Name": str(r.Name), "Enabled": bool(r.Enabled), "Inbound": r.Direction == FW_DIRECTION_IN,
             "LocalPorts": str(r.LocalPorts or "")} for r in map(_wrap, _policy().Rules)]


@_safe(False)
@_com
def firewall_add_rule(name: str, description: str, port: int, remote_addresses: str) -> bool:
    """Inbound TCP allow rule on the Private profile only. Needs an elevated process."""
    import comtypes.client
    rule = comtypes.client.CreateObject("HNetCfg.FWRule")
    rule.Name, rule.Description = name, description
    rule.Protocol, rule.LocalPorts = FW_PROTOCOL_TCP, str(port)
    rule.Direction, rule.Action = FW_DIRECTION_IN, FW_ACTION_ALLOW
    rule.Profiles, rule.RemoteAddresses, rule.Enabled = FW_PROFILE_PRIVATE, remote_addresses, True
    _policy().Rules.Add(rule)
    return True


@_safe(False)
@_com
def firewall_remove_rules(name: str) -> bool:
    """Delete every rule called ``name`` (and nothing else). Needs an elevated process."""
    rules = _policy().Rules
    while any(str(r.Name) == name for r in map(_wrap, rules)):
        rules.Remove(name)
    return True


def _shell():
    import ctypes as c
    return c.WinDLL("shell32", use_last_error=True), c.WinDLL("kernel32", use_last_error=True)


@_safe(None)
def run_elevated(exe: str, params: str) -> int | None:
    """Run ``exe params`` through the UAC prompt (ShellExecuteExW "runas"), wait, return its exit
    code; None when the prompt was cancelled or it could not start."""
    import ctypes as c
    shell32, kernel32 = _shell()

    class ExecInfo(c.Structure):
        _fields_ = [("cbSize", c.c_uint32), ("fMask", c.c_ulong), ("hwnd", c.c_void_p),
                    ("lpVerb", c.c_wchar_p), ("lpFile", c.c_wchar_p), ("lpParameters", c.c_wchar_p),
                    ("lpDirectory", c.c_wchar_p), ("nShow", c.c_int), ("hInstApp", c.c_void_p),
                    ("lpIDList", c.c_void_p), ("lpClass", c.c_wchar_p), ("hkeyClass", c.c_void_p),
                    ("dwHotKey", c.c_uint32), ("hIcon", c.c_void_p), ("hProcess", c.c_void_p)]

    info = ExecInfo()
    info.cbSize, info.fMask = c.sizeof(ExecInfo), SEE_MASK_NOCLOSEPROCESS
    info.lpVerb, info.lpFile, info.lpParameters, info.nShow = "runas", exe, params, 0
    if not shell32.ShellExecuteExW(c.byref(info)):
        return None  # ERROR_CANCELLED (1223) when the user declines, anything else: not started
    if not info.hProcess:
        return None
    try:
        kernel32.WaitForSingleObject(c.c_void_p(info.hProcess), 0xFFFFFFFF)
        code = c.c_uint32()
        if not kernel32.GetExitCodeProcess(c.c_void_p(info.hProcess), c.byref(code)):
            return None
        return code.value
    finally:
        kernel32.CloseHandle(c.c_void_p(info.hProcess))


@_safe(False)
def bridge_reachable(port: int = BRIDGE_PORT) -> bool:
    """The bridge answers on its loopback port: that is what the supervisor/app reports as running."""
    with socket.create_connection(("127.0.0.1", port), timeout=1.0):
        return True


SYSTEM_PROCESS_INFORMATION = 5
STATUS_INFO_LENGTH_MISMATCH = 0xC0000004
# SYSTEM_PROCESS_INFORMATION (x64) offsets, from the Windows SDK / winternl.h layout.
_SPI_NEXT, _SPI_CREATE, _SPI_USER, _SPI_KERNEL, _SPI_NAME_LEN, _SPI_NAME_BUF = 0, 32, 40, 48, 56, 64
_SPI_PID, _SPI_WORKING_SET = 80, 144


def parse_process_snapshot(buf: bytes, read_name) -> list[dict]:
    """Walk a SystemProcessInformation buffer. ``read_name(ptr, nbytes)`` decodes an ImageName."""
    import struct
    out, off = [], 0
    while True:
        nxt = struct.unpack_from("<I", buf, off + _SPI_NEXT)[0]
        create, user, kernel = struct.unpack_from("<qqq", buf, off + _SPI_CREATE)
        name_len = struct.unpack_from("<H", buf, off + _SPI_NAME_LEN)[0]
        name_ptr = struct.unpack_from("<Q", buf, off + _SPI_NAME_BUF)[0]
        pid = struct.unpack_from("<Q", buf, off + _SPI_PID)[0]
        rss = struct.unpack_from("<Q", buf, off + _SPI_WORKING_SET)[0]
        out.append({"pid": pid, "name": read_name(name_ptr, name_len) if name_ptr else "",
                    "cpu_s": (user + kernel) / 1e7, "rss": rss, "created": create})
        if not nxt:
            return out
        off += nxt


@_safe(None)
def process_snapshot() -> list[dict] | None:
    """Every process's name, CPU seconds, working set and start time, in ONE kernel call.

    psutil asks per process, and for every process a normal user may not open (services, other
    sessions) it falls back to this same system-wide query once per field: ~13 ms each, 7+ s for a
    process list. One query answers for all of them in milliseconds.
    """
    import ctypes as c
    ntdll = c.WinDLL("ntdll")
    ntdll.NtQuerySystemInformation.restype = c.c_uint32
    size = 1 << 20
    for _ in range(8):
        buf = c.create_string_buffer(size)
        needed = c.c_uint32()
        status = ntdll.NtQuerySystemInformation(SYSTEM_PROCESS_INFORMATION, buf, size, c.byref(needed))
        if status == STATUS_INFO_LENGTH_MISMATCH:
            size = max(size * 2, needed.value + (64 << 10))
            continue
        if status != 0:
            return None
        raw = buf.raw  # copy; ImageName pointers point into buf, which stays alive below
        return parse_process_snapshot(raw, lambda ptr, n: c.wstring_at(ptr, n // 2))
    return None
