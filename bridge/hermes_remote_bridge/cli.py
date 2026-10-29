"""Command-line entry point: run the bridge and manage paired devices and trusted networks."""
from __future__ import annotations

import argparse
import asyncio
import ipaddress
import json
import logging
import os
import re
import socket
import sys
import threading
import time
from datetime import datetime
from pathlib import Path

from .config import Config
from .devices import DeviceStore
from .host import host
from .local_api import devices_doc, pairing_payload, pairing_uri, qr_png, trust_doc
from .network import TrustStore, current_network, serving_lan_ips
from .tailnet import sync_tailscale_ips
from .supervise import EXIT_PORT_CONFLICT
from .tls import cert_pin, ensure_identity


def _plain_hosts(cfg: Config, retries: int) -> tuple[list[str], str | None]:
    """Loopback + Tailscale: plain HTTP (WireGuard already encrypts the tailnet)."""
    hosts: list[str] = []
    owner = None
    for entry in cfg.listen:
        if entry != "tailscale":
            hosts.append(entry)
            continue
        # Tailscale is an extension, not a requirement: keep serving loopback/LAN without it.
        for attempt in range(retries):  # tailscaled may still be starting at boot
            try:
                ips, owner = sync_tailscale_ips()
                hosts.extend(ips)
                break
            except Exception as exc:
                if attempt == retries - 1:
                    logging.getLogger("hermes_remote_bridge").warning(
                        "Tailscale unavailable (%s); serving without tailnet addresses", exc)
                else:
                    time.sleep(2)
    if any(ipaddress.ip_address(h).is_unspecified for h in hosts):
        raise SystemExit("Refusing to bind a wildcard address (0.0.0.0 / ::); list explicit addresses")
    return hosts, owner


def _port_in_use(host: str, port: int) -> bool:
    """True when something already accepts connections on ``host:port``.

    ``bind()`` cannot be relied on to notice: ``_bind`` sets SO_REUSEADDR, and on Windows that
    flag lets a *second* bind of the same address/port succeed (on Linux it only covers
    TIME_WAIT, so this matters most there). Two bridges would then silently share the port
    and connections would land on whichever socket the stack picked. A TCP connect is the
    check that behaves the same on every platform.
    """
    family = socket.AF_INET6 if ":" in host else socket.AF_INET
    probe = socket.socket(family, socket.SOCK_STREAM)
    try:
        probe.settimeout(2)
        probe.connect((host, port))
    except OSError:
        return False
    finally:
        probe.close()
    return True


def _bind(host: str, port: int) -> socket.socket:
    """Bind one listening socket, refusing if the port is already served.

    SO_REUSEADDR is set for TIME_WAIT (Linux) and is harmless there, but on Windows it also lets a
    *second* bind of the same address/port succeed, so bind() alone is not a guard. The check
    below is deliberately not atomic with the bind: between the connect and the bind another
    process can win the port, and then bind() raises and we surface that. What the check buys is
    a clear message for the common case (a bridge is already running) instead of a traceback.
    """
    if _port_in_use(host, port):
        # Exit 76 (not the generic SystemExit(message) = 1) so the supervisor can tell a port
        # conflict from a crash and stop instead of retrying.
        print(f"Port {port} is already in use on {host}; another bridge is probably running. "
              f"Stop it first (the tray: Quit Hermes Remote), or set a different port in config.toml.",
              file=sys.stderr)
        raise SystemExit(EXIT_PORT_CONFLICT)
    family = socket.AF_INET6 if ":" in host else socket.AF_INET
    sock = socket.socket(family, socket.SOCK_STREAM)
    sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    if family == socket.AF_INET6:
        sock.setsockopt(socket.IPPROTO_IPV6, socket.IPV6_V6ONLY, 1)
    sock.bind((host, port))
    sock.listen(128)
    sock.setblocking(False)
    return sock


def _desired(cfg: Config, trust: TrustStore) -> tuple[frozenset[str], frozenset[str]]:
    """(plain hosts, LAN TLS hosts) the bridge should be listening on right now."""
    plain = {h for h in cfg.listen if h != "tailscale"}
    if "tailscale" in cfg.listen:
        try:
            plain.update(sync_tailscale_ips()[0])
        except Exception:
            pass
    return frozenset(plain), frozenset(serving_lan_ips(cfg.lan, trust))


def cmd_serve(cfg: Config, _: argparse.Namespace) -> None:
    import uvicorn

    from .app import create_app

    logging.basicConfig(level=logging.INFO, format="%(levelname)s %(name)s: %(message)s")
    logging.getLogger("httpx").setLevel(logging.WARNING)  # the network watcher polls every 5 s
    log = logging.getLogger("hermes_remote_bridge")
    trust = TrustStore(cfg.trust_file)
    plain, owner = _plain_hosts(cfg, retries=3 if cfg.lan else 30)
    lan = serving_lan_ips(cfg.lan, trust)
    if not cfg.allowed_logins and not owner:
        if not cfg.lan:
            raise SystemExit("Could not determine the Tailscale owner; set allowed_logins in config.toml")
        log.warning("Tailscale unavailable; tailnet peers are refused until it is back")
    net = current_network()
    if cfg.lan:
        log.info("network: %s (%s)", net.name if net else "none",
                 "trusted, serving the local network" if lan else
                 "not trusted, local network off" if net else "offline")

    if cfg.metrics:
        from .metrics import ensure_metrics_token
        ensure_metrics_token(cfg.metrics_token_file)
    app = create_app(cfg, owner_login=owner, trust=trust)
    servers = [uvicorn.Server(uvicorn.Config(app, access_log=False, log_level="info",
                                             timeout_graceful_shutdown=5, proxy_headers=False))]
    # _bind checks each address for itself and reports the conflict by name, so there is no
    # separate sweep here to keep in sync with it.
    sockets = [[_bind(h, cfg.port) for h in plain]]
    pin = None
    if lan:
        cert, key = ensure_identity(cfg.tls_dir)
        pin = cert_pin(cert)
        # Same app, second server: TLS only on the LAN. lifespan off so housekeeping and
        # shutdown run once (owned by the plain server).
        servers.append(uvicorn.Server(uvicorn.Config(
            app, access_log=False, log_level="info", lifespan="off", timeout_graceful_shutdown=5,
            proxy_headers=False, ssl_certfile=str(cert), ssl_keyfile=str(key))))
        sockets.append([_bind(h, cfg.port) for h in lan])
    log.info("listening on %s%s", ", ".join(f"http://{h}:{cfg.port}" for h in plain),
             "".join(f", https://{h}:{cfg.port}" for h in lan))
    mdns = _advertise(cfg, pin, sorted(lan)) if lan and cfg.mdns else None
    if lan and cfg.mdns and mdns is None:
        log.warning("mDNS unavailable on this PC; the app can't follow an IP change by itself")
    if cfg.lan and net and not lan and not os.environ.get(NO_PROMPT_ENV):
        _ask_to_trust(trust, net)
    try:
        changed = asyncio.run(_serve_until_network_changes(servers, sockets, cfg, trust,
                                                          (frozenset(plain), frozenset(lan))))
    finally:
        if mdns:
            mdns.terminate()
    if changed:
        # Non-zero so the service manager (systemd Restart=always, a Scheduled Task's restart
        # policy) brings us back up on the new addresses.
        raise SystemExit(75)


def cmd_supervise(cfg: Config, args: argparse.Namespace) -> None:
    import signal

    from .supervise import Supervisor, popen_launcher

    sup = Supervisor(popen_launcher(args.config), host().state_dir() / "bridge.log")
    for name in ("SIGINT", "SIGTERM"):
        signal.signal(getattr(signal, name), lambda *_: sup.stop())
    sup.watch_stdin(sys.stdin)
    sys.exit(sup.run())


def _listener_closed(sockets) -> bool:
    """True when any bound listening socket has been closed (its descriptor is gone)."""
    return any(sock.fileno() == -1 for group in sockets for sock in group)


async def _serve_until_network_changes(servers, sockets, cfg: Config, trust: TrustStore,
                                       bound: tuple[frozenset[str], frozenset[str]]) -> bool:
    """Serve until Wi-Fi/DHCP/Tailscale/trust changes what we should listen on.

    Sockets are bound to explicit addresses (never 0.0.0.0), so a new network would otherwise
    leave the phone unable to connect until someone restarts the service.
    """
    changed = False

    async def watch():
        nonlocal changed
        tick = 0
        while not any(s.should_exit for s in servers):
            await asyncio.sleep(1)
            tick += 1
            if _listener_closed(sockets):
                # asyncio on Windows closes a listening socket when one accept() fails (a client
                # resetting mid-connect is enough). The process would stay up, deaf. Restart.
                logging.getLogger("hermes_remote_bridge").error(
                    "a listening socket was closed underneath us; restarting")
                changed = True
                for s in servers:
                    s.should_exit = True
                return
            if tick % 5:
                continue
            now = await asyncio.to_thread(_desired, cfg, trust)
            if now != bound and (now[0] or now[1]):
                logging.getLogger("hermes_remote_bridge").info(
                    "listen set changed (%s -> %s); restarting",
                    sorted(bound[0] | bound[1]), sorted(now[0] | now[1]))
                changed = True
                for s in servers:
                    s.should_exit = True

    task = asyncio.create_task(watch())
    try:
        await asyncio.gather(*(srv.serve(sockets=socks) for srv, socks in zip(servers, sockets)))
    finally:
        task.cancel()
    return changed


def _advertise(cfg: Config, pin: str | None, lan_ips: list[str]):
    """Announce the bridge over mDNS so the app finds it when the PC's IP changes.

    The TXT record carries only a short prefix of the certificate pin, so the app can tell its
    own bridge from another one; the full pin is still checked on the TLS connection. ``ip=``
    lists the addresses we actually serve, so a resolver never hands the phone a Docker bridge.
    """
    return host().advertise_mdns(cfg.port, pin, lan_ips)


# Set by the desktop app (its unit and its child): the app asks about new networks itself (D44).
NO_PROMPT_ENV = "HERMES_REMOTE_NO_TRUST_PROMPT"


def _ask_to_trust(trust: TrustStore, net) -> None:
    """Desktop prompt: offer to trust the network the PC just joined (once per network)."""
    if trust.is_declined(net):
        return

    def ask():
        # Re-read the network rather than trusting the value captured at startup: the tray is
        # long-lived, and by the time this runs the PC may be somewhere else. current_network is
        # cached for a second, which is fresh enough for a prompt.
        current = current_network()
        if current is None or current.id != net.id:
            return  # the network changed again; the next watcher pass will ask about the new one
        answer = host().prompt_trust(current.name)
        if answer == "trust":
            trust.trust(current)  # the watcher sees it and restarts with the LAN listener
        elif answer == "no":
            trust.decline(current)

    threading.Thread(target=ask, daemon=True).start()


def _bridge_urls(cfg: Config) -> list[str]:
    """Addresses for the pairing code: trusted LAN (HTTPS) first, Tailscale as the fallback."""
    lan = serving_lan_ips(cfg.lan, TrustStore(cfg.trust_file))
    try:
        tailnet = [ip for ip in sync_tailscale_ips()[0] if "." in ip]
    except Exception:
        tailnet = []  # Tailscale is optional
    urls = [f"https://{ip}:{cfg.port}" for ip in lan] + [f"http://{ip}:{cfg.port}" for ip in tailnet]
    if not urls:
        raise SystemExit("No trusted local network and no Tailscale: nothing to put in the pairing code. "
                         "Run `hermes-remote-bridge trust` on a network you control, or start Tailscale.")
    return urls


def _pairing_warnings(cfg: Config, urls: list[str]) -> list[str]:
    """Reasons the address in the pairing code may be unreachable from the phone.

    Printed before the QR, because "the app says it cannot reach the PC" is the usual first
    outcome and it is nearly always one of these two.
    """
    from . import firewall

    out: list[str] = []
    lan = [u for u in urls if u.startswith("https://")]  # LAN entries are HTTPS; tailnet is HTTP
    if not lan and cfg.lan:
        out.append("No trusted local network, so the code points at Tailscale only. The phone can "
                   "only use it with Tailscale installed and logged into the same tailnet.\n"
                   f"    Fix: hermes-remote-bridge trust   (on your home or office Wi-Fi)")
    if lan:
        state = firewall.state(cfg.port)
        if state.active and state.port_open is False:
            out.append(f"{state.kind} firewall is blocking TCP {cfg.port}, so nothing on this network "
                       "can reach the bridge. This is on by default on Windows.\n"
                       f"    Fix: hermes-remote-bridge firewall   (asks first)")
        elif state.active and state.port_open is None:
            out.append(f"{state.kind} firewall is active and I cannot read its rules. If the app cannot "
                       f"reach the PC, open TCP {cfg.port} from your local network:\n"
                       f"    Fix: hermes-remote-bridge firewall")
        out.append("The phone must be on the SAME Wi-Fi as this PC, not on mobile data or a different "
                   "network. Guest Wi-Fi often blocks device-to-device traffic.")
    return out


def _show_qr(uri: str, cfg: Config) -> bool:
    """Show the pairing QR. Returns True when it was opened as an image file.

    A terminal QR is drawn with block characters. That works in a Linux terminal, but a Windows
    console may not have the glyphs (or the code page) and the result is unscannable or an
    exception, so there it opens as a picture; it is also the fallback if printing fails.
    """
    import segno

    qr = segno.make(uri, error="m")
    if not host().qr_as_image():
        try:
            qr.terminal(compact=True)
            return False
        except UnicodeEncodeError:
            pass
    path = cfg.audit_log.parent / "pairing-qr.png"
    path.parent.mkdir(parents=True, exist_ok=True)
    fd = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
    with os.fdopen(fd, "wb") as fh:
        qr.save(fh, kind="png", scale=8, border=3, dark="#000", light="#fff")
    if host().open_file(path):
        return True
    print(f"Open this picture and scan it: {path}  (delete it afterwards)")
    return False


def cmd_pair(cfg: Config, args: argparse.Namespace) -> None:
    store = DeviceStore(cfg.devices_file)
    device, token = store.pair(args.name, replace=getattr(args, "replace", False))
    urls = [args.url] if args.url else _bridge_urls(cfg)
    payload = pairing_payload(device, token, urls, cfg.tls_dir)
    if args.token_file:  # non-interactive use; never print the token
        fd = os.open(args.token_file, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
        with os.fdopen(fd, "w") as fh:
            json.dump(payload, fh)
        print(f"Paired {device.name} ({device.id}); credentials written to {args.token_file}")
        return
    if args.qr_png:
        fd = os.open(args.qr_png, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
        with os.fdopen(fd, "wb") as fh:
            fh.write(qr_png(pairing_uri(payload)))
        print(f"Paired {device.name} ({device.id}); QR written to {args.qr_png} (delete it after scanning)")
        return
    print(f"Paired device {device.name!r} (id {device.id}).")
    warnings = [] if args.url else _pairing_warnings(cfg, urls)
    if warnings:
        print("\nBEFORE you scan, check this:")
        for w in warnings:
            print(f"  * {w}")
        print()
    print("Scan this QR code in the Hermes Remote app. It is shown ONCE; the token is not stored.\n")
    shown_as_image = _show_qr(uri, cfg)
    print(f"\nManual entry -> URL: {urls[0]}\n                 Token: {token}\n")
    if shown_as_image:
        print("The QR code was opened as a picture. Close it and delete it once the app has paired.")
    print(f"Revoke any time: hermes-remote-bridge revoke {device.name!r}")
    _confirm_reachable(urls[0], cfg)


def _confirm_reachable(url: str, cfg: Config) -> None:
    """Try the address in the pairing code, so a blocked port shows up here and not only on the phone.

    On the LAN this is HTTPS with the bridge's own self-signed certificate, so we load that
    certificate as the trust anchor instead of the system store: a plain verification failure would
    be reported as "unreachable" when the port is in fact open.
    """
    import ssl
    import urllib.error
    import urllib.request

    ctx = ssl.create_default_context()
    if url.startswith("https://"):
        # The certificate names no IP and is not signed by anyone, so the app pins its fingerprint
        # instead of trusting a CA. Check it the same way here, or every healthy bridge looks dead.
        # check_hostname is off because there is no hostname to match.
        try:
            cert_path, _key_path = ensure_identity(cfg.tls_dir)
            ctx = ssl.SSLContext(ssl.PROTOCOL_TLS_CLIENT)
            ctx.check_hostname = False
            ctx.verify_mode = ssl.CERT_REQUIRED
            ctx.load_verify_locations(cadata=cert_path.read_text())
        except (OSError, ssl.SSLError):
            ctx = ssl.create_default_context()
    try:
        with urllib.request.urlopen(url + "/v1/me", timeout=5, context=ctx) as r:
            print(f"Reachable: {url} (HTTP {r.status})")
            return
    except urllib.error.HTTPError as exc:
        # Any HTTP answer means the port is open and something is listening. 401 is correct.
        print(f"Reachable: {url} (HTTP {exc.code})")
        return
    except Exception as exc:  # noqa: BLE001 - the point is to report whatever went wrong
        print(f"NOT reachable from this PC: {url} ({exc}).")
        print("  The phone will not reach it either. Run: hermes-remote-bridge doctor")


def cmd_revoke(cfg: Config, args: argparse.Namespace) -> None:
    device = DeviceStore(cfg.devices_file).revoke(args.device)
    print(f"Revoked {device.name} ({device.id}). Takes effect on its next request.")


def cmd_trust(cfg: Config, args: argparse.Namespace) -> None:
    trust = TrustStore(cfg.trust_file)
    if args.list:
        current = current_network()
        rows = trust.list()
        if getattr(args, "json", False):
            print(json.dumps(trust_doc(trust, current), indent=2))
            return
        if not rows:
            print("No trusted networks.")
        for net_id, info in rows.items():
            mark = "  <- current" if current and current.id == net_id else ""
            print(f"{net_id}  {info.get('name', '?')}{mark}")
        if current and current.id not in rows:
            print(f"Current network {current.name!r} ({current.id}) is not trusted.")
        return
    if args.remove:
        print("Removed." if trust.untrust(args.remove) else f"No trusted network {args.remove!r}.")
        return
    net = current_network()
    if net is None:
        raise SystemExit("Not on a private network (or the router has not been seen yet).")
    trust.trust(net)
    print(f"Trusted {net.name!r} ({net.id}). The bridge picks it up within a few seconds.")


def cmd_devices(cfg: Config, args: argparse.Namespace) -> None:
    fmt = lambda ts: datetime.fromtimestamp(ts).strftime("%Y-%m-%d %H:%M") if ts else "-"
    store = DeviceStore(cfg.devices_file)
    rows = store.list()
    if getattr(args, "json", False):
        print(json.dumps(devices_doc(store), indent=2))
        return
    if not rows:
        print("No paired devices.")
    for d in rows:
        state = "active" if d.active else f"revoked {fmt(d.revoked_at)}"
        print(f"{d.id}  {d.name:<24} paired {fmt(d.created_at)}  last seen {fmt(d.last_seen_at)}  {state}")


def cmd_doctor(cfg: Config, args: argparse.Namespace) -> None:
    from . import doctor
    if getattr(args, "json", False):
        print(json.dumps({"checks": doctor.collect(cfg)}, indent=2))
        return  # the JSON document is the whole answer; a summary line would corrupt it
    sys.exit(doctor.run(cfg))


def _fetch_metrics(cfg: Config) -> dict:
    """The running bridge's metrics, over loopback with the local metrics token."""
    import urllib.error
    import urllib.request

    from .metrics import read_metrics_token

    if not cfg.metrics:
        raise SystemExit("Metrics are off (metrics = false in config.toml).")
    token = read_metrics_token(cfg.metrics_token_file)
    if token is None:
        raise SystemExit(f"No metrics token at {cfg.metrics_token_file}; start the bridge once to create it.")
    loopback = next((h for h in cfg.listen if h != "tailscale" and ipaddress.ip_address(h).is_loopback), None)
    if loopback is None:
        raise SystemExit("The bridge does not listen on loopback (see listen in config.toml).")
    host_part = f"[{loopback}]" if ":" in loopback else loopback
    req = urllib.request.Request(f"http://{host_part}:{cfg.port}/metrics?format=json",
                                 headers={"Authorization": f"Bearer {token}"})
    try:
        with urllib.request.urlopen(req, timeout=5) as r:
            return json.loads(r.read())
    except urllib.error.HTTPError as exc:
        raise SystemExit(f"The bridge refused the metrics request (HTTP {exc.code}).")
    except OSError as exc:
        raise SystemExit(f"The bridge is not answering on {loopback}:{cfg.port} ({exc}).")


def cmd_metrics(cfg: Config, args: argparse.Namespace) -> None:
    from .metrics import summarize

    data = _fetch_metrics(cfg)
    if getattr(args, "json", False):
        print(json.dumps(data, indent=2))
        return
    print(summarize(data))


def cmd_notes(cfg: Config, args: argparse.Namespace) -> None:
    """The notes the phone shows, for Hermes and scripts: list, show, add, append, edit, check, rm."""
    from .notes import NoteStore

    store = NoteStore(cfg.notes_dir)
    js = getattr(args, "json", False)
    # `add` and `list` take no id: argparse put their first word in the id slot.
    if args.action in ("add", "list", "path") and args.id is not None:
        args.text = [args.id, *(args.text or [])]
        args.id = None
    if args.action == "list" and args.text and not args.query:
        args.query = " ".join(args.text)

    def out(n) -> None:
        if js:
            print(json.dumps(n.to_dict(), ensure_ascii=False, indent=2)); return
        flags = " ".join(x for x in ("📌" if n.pinned else "", "[archived]" if n.archived else "",
                                      " ".join("#" + l for l in n.labels)) if x)
        print(f"# {n.title or '(untitled)'}  [{n.id}] {flags}".rstrip())
        print(n.body.rstrip())

    def text_arg() -> str:
        t = " ".join(args.text or [])
        return sys.stdin.read() if t == "-" else t.replace("\\n", "\n")

    try:
        a = args.action
        if a == "list":
            items = store.list(args.query or "", None if args.all else False, args.label)
            if js:
                print(json.dumps([n.to_dict() for n in items], ensure_ascii=False, indent=2)); return
            for n in items:
                first = next((l.strip() for l in n.body.splitlines() if l.strip()), "")
                print(f"{n.id}  {'📌 ' if n.pinned else ''}{n.title or first[:60] or '(empty)'}"
                      + (f"  #{' #'.join(n.labels)}" if n.labels else "") + ("  [archived]" if n.archived else ""))
            if not items:
                print("No notes.")
        elif a == "show":
            out(store.get(args.id))
        elif a == "add":
            out(store.create(title=args.title or "", body=text_arg(), labels=args.label_add or [],
                             color=args.color or "default", pinned=args.pin))
        elif a == "append":
            out(store.append(args.id, text_arg()))
        elif a == "edit":
            fields = {"title": args.title, "color": args.color}
            if args.text:
                fields["body"] = text_arg()
            if args.pin or args.unpin:
                fields["pinned"] = bool(args.pin)
            if args.archive or args.unarchive:
                fields["archived"] = bool(args.archive)
            if args.label_add or args.label_remove:
                cur = store.get(args.id).labels
                fields["labels"] = [x for x in cur + (args.label_add or []) if x not in (args.label_remove or [])]
            out(store.update(args.id, **fields))
        elif a == "check":
            n = store.get(args.id)
            needle = " ".join(args.text or []).lower()
            lines, hit = n.body.splitlines(), False
            for i, l in enumerate(lines):
                m = re.match(r"^(\s*[-*] \[)([ xX])(\] )(.*)$", l)
                if m and needle in m[4].lower():
                    lines[i] = m[1] + (" " if args.uncheck else "x") + m[3] + m[4]
                    hit = True
                    break
            if not hit:
                sys.exit(f"No checklist item matching {needle!r} in {args.id}")
            out(store.update(args.id, body="\n".join(lines) + "\n"))
        elif a == "rm":
            store.delete(args.id)
            print(f"Deleted {args.id}")
        elif a == "path":
            print(cfg.notes_dir)
    except KeyError:
        sys.exit(f"No note {getattr(args, 'id', '')!r}; `hermes-remote-bridge notes list` shows the ids.")


def cmd_update(cfg: Config, args: argparse.Namespace) -> None:
    """Check for a newer release and update to it. Pairings and settings are kept."""
    from . import selfupdate

    if getattr(args, "apply", False):
        sys.exit(selfupdate.run_apply())
    if getattr(args, "restart", False):
        sys.exit(selfupdate.run_restart())
    info = selfupdate.check()
    if getattr(args, "json", False):
        print(json.dumps(info, indent=2))
        return
    print(f"Installed: {info['current'] or 'unknown'}{' (' + info['commit'] + ')' if info.get('commit') else ''}")
    print(f"Newest:    {info['latest'] or 'unknown'}")
    if info["reason"]:
        sys.exit(info["reason"])
    if not info["available"]:
        print("Already up to date.")
        return
    if getattr(args, "check", False):
        print("An update is available: run `hermes-remote-bridge update`.")
        return
    if not getattr(args, "yes", False) and sys.stdin.isatty():
        if input(f"Update to {info['latest']} and restart the bridge? [Y/n] ").strip().lower() not in ("", "y", "yes"):
            return
    selfupdate.launch()
    print(f"Updating in the background; log: {selfupdate.log_file()}")
    print("The bridge restarts at the end. Paired phones keep their pairing and reconnect on their own;")
    print("Hermes and its chats keep running.")


def cmd_firewall(cfg: Config, args: argparse.Namespace) -> None:
    from . import firewall
    port = args.port or cfg.port
    if args.elevated:  # the UAC-relaunched process: write (or delete) the rule, report by exit code
        h = host()
        ok = h.firewall_remove() if args.action == "remove" else h.firewall_add(port)
        sys.exit(0 if ok else 1)
    if args.action == "remove":
        print("Removed." if host().elevate_cli(["firewall", "remove", "--port", str(port), "--elevated"])
              else "Not removed (cancelled or failed).")
        return
    s = firewall.state(port)
    if s.kind == "none" or not s.active:
        print(f"No active firewall; port {port} is reachable.")
        return
    if s.port_open:
        print(f"{s.kind}: port {port} is already allowed from private networks.")
        return
    if s.kind == "unknown" or not firewall.open_commands(s.kind, port):
        print(f"Can't manage this firewall automatically. Allow TCP {port} from your local network yourself.")
        return
    if args.if_needed and s.port_open is None and not sys.stdin.isatty():
        return
    print(f"{s.kind} is active. To let your phone reach the bridge over Wi-Fi, this will be added")
    print("(private networks only; the bridge only listens on Wi-Fi you trusted):")
    for c in firewall.open_commands(s.kind, port):
        print("  " + " ".join(c))
    if not args.yes:
        try:
            if input("Apply now? You'll be asked for your password"
                         f"{' (a UAC prompt)' if s.kind == 'windows' else ''}. [Y/n] ").strip().lower() not in ("", "y", "yes"):
                return
        except EOFError:
            return
    print("Done." if firewall.open_port(s.kind, port) else "Not applied (cancelled or failed).")


def _no_command_help(parser: argparse.ArgumentParser) -> None:
    """Running the program with nothing after it: say what to type, and don't vanish.

    On Windows people double-click the .exe; without this the window shows an argparse error and
    closes before it can be read.
    """
    print("Hermes Mobile Remote bridge\n")
    print("This program needs a command after its name. The ones you want most:\n")
    print("  hermes-remote-bridge pair phone     show a QR code to connect your phone")
    print("  hermes-remote-bridge doctor         find out why the phone cannot connect")
    print("  hermes-remote-bridge devices        list paired phones")
    print("  hermes-remote-bridge trust          trust the Wi-Fi network you are on now")
    if host().name == "windows":
        print("\nEasiest: open Hermes Remote from the Start menu and choose Pair a device.")
    print("\nAll commands: hermes-remote-bridge --help")
    if host().launched_by_double_click():
        input("\nPress Enter to close this window...")


def main(argv: list[str] | None = None) -> None:
    parser = argparse.ArgumentParser(prog="hermes-remote-bridge")
    parser.add_argument("--config", type=Path, help="config.toml (default ~/.config/hermes-remote/config.toml)")
    sub = parser.add_subparsers(dest="command", required=True)
    sub.add_parser("serve", help="Run the bridge")
    sub.add_parser("supervise", help="Run the bridge and restart it if it stops")
    p = sub.add_parser("pair", help="Pair a new device and show its QR code")
    p.add_argument("name", help="Device name, e.g. 'phone' or 'tablet'")
    p.add_argument("--url", help="Override the bridge URL put in the QR code")
    p.add_argument("--token-file", type=Path, help=argparse.SUPPRESS)
    p.add_argument("--qr-png", type=Path, help="Write the pairing QR to a PNG (mode 600) instead of the terminal")
    p.add_argument("--replace", action="store_true",
                   help="Re-pair: revoke the active device with this name and issue it a new code")
    r = sub.add_parser("revoke", help="Revoke a paired device")
    r.add_argument("device", help="Device name or id")
    d = sub.add_parser("devices", help="List paired devices")
    d.add_argument("--json", action="store_true", help="Machine-readable output (for the tray app)")
    t = sub.add_parser("trust", help="Trust the network this PC is on (serve the LAN there)")
    t.add_argument("--list", action="store_true", help="List trusted networks")
    t.add_argument("--remove", metavar="ID", help="Stop trusting a network")
    t.add_argument("--json", action="store_true", help="Machine-readable output, with --list")
    doc = sub.add_parser("doctor", help="Check why the phone can't connect")
    doc.add_argument("--json", action="store_true", help="Machine-readable checks (for the tray app)")
    nt = sub.add_parser("notes", help="Read and edit the notes the phone shows (for Hermes and scripts)")
    nt.add_argument("action", choices=("list", "show", "add", "append", "edit", "check", "rm", "path"))
    nt.add_argument("id", nargs="?", help="Note id (from list); not for list/add/path")
    nt.add_argument("text", nargs="*", help="Body text; '-' reads stdin; \\n is a newline. For check: words of the item")
    nt.add_argument("--title")
    nt.add_argument("--color")
    nt.add_argument("--label", help="list: only this label")
    nt.add_argument("--add-label", dest="label_add", action="append")
    nt.add_argument("--remove-label", dest="label_remove", action="append")
    nt.add_argument("--pin", action="store_true")
    nt.add_argument("--unpin", action="store_true")
    nt.add_argument("--archive", action="store_true")
    nt.add_argument("--unarchive", action="store_true")
    nt.add_argument("--uncheck", action="store_true", help="check: untick instead")
    nt.add_argument("--query", "-q", help="list: search title, text and labels")
    nt.add_argument("--all", action="store_true", help="list: include archived")
    nt.add_argument("--json", action="store_true")
    u = sub.add_parser("update", help="Update the bridge to the newest release (keeps pairings)")
    u.add_argument("--check", action="store_true", help="Only say whether an update is available")
    u.add_argument("--yes", action="store_true", help="Don't ask for confirmation")
    u.add_argument("--json", action="store_true", help="Machine-readable check result")
    u.add_argument("--apply", action="store_true", help=argparse.SUPPRESS)
    u.add_argument("--restart", action="store_true", help=argparse.SUPPRESS)
    m = sub.add_parser("metrics", help="Show what the running bridge has done since it started")
    m.add_argument("--json", action="store_true", help="Every metric as JSON")
    f = sub.add_parser("firewall", help="Allow the bridge port from the local network (asks first)")
    f.add_argument("action", nargs="?", choices=("add", "remove"), default="add",
                   help="add (default) or remove the Windows rule")
    f.add_argument("--port", type=int, help="Port (default: the configured one)")
    f.add_argument("--elevated", action="store_true", help=argparse.SUPPRESS)
    f.add_argument("--yes", action="store_true", help="Don't ask for confirmation")
    f.add_argument("--if-needed", action="store_true", help=argparse.SUPPRESS)
    if not (argv if argv is not None else sys.argv[1:]):
        _no_command_help(parser)
        return
    args = parser.parse_args(argv)
    cfg = Config.load(args.config)
    handler = {"serve": cmd_serve, "pair": cmd_pair, "revoke": cmd_revoke, "devices": cmd_devices,
               "trust": cmd_trust, "doctor": cmd_doctor, "firewall": cmd_firewall,
               "supervise": cmd_supervise, "metrics": cmd_metrics, "update": cmd_update,
               "notes": cmd_notes}[args.command]
    try:
        handler(cfg, args)
    except ValueError as exc:
        sys.exit(f"error: {exc}")


if __name__ == "__main__":
    main()
