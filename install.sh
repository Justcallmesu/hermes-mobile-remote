#!/usr/bin/env bash
# Install the Hermes Mobile Remote bridge on this PC (Linux, systemd user service).
#
#   ./install.sh              install or update, enable the service, offer firewall + pairing
#   ./install.sh --uninstall  stop and remove the service (keeps paired devices and config)
#
# Safe to re-run. Never needs sudo except an optional firewall change, which asks first.
set -euo pipefail

REPO="$(cd "$(dirname "$(readlink -f "$0")")" && pwd)"
BRIDGE="$REPO/bridge"
UNIT_NAME="hermes-remote-bridge.service"
UNIT_DIR="${XDG_CONFIG_HOME:-$HOME/.config}/systemd/user"
CONF_DIR="${XDG_CONFIG_HOME:-$HOME/.config}/hermes-remote"
HERMES_HOME="${HERMES_HOME:-$HOME/.hermes}"
BIN="$BRIDGE/.venv/bin/hermes-remote-bridge"
TRAY="$BRIDGE/linux/tray.py"
APPS_DIR="${XDG_DATA_HOME:-$HOME/.local/share}/applications"
AUTOSTART_DIR="${XDG_CONFIG_HOME:-$HOME/.config}/autostart"

bold() { printf '\033[1m%s\033[0m\n' "$*"; }
info() { printf '  %s\n' "$*"; }
die()  { printf '\033[31mError:\033[0m %s\n' "$*" >&2; exit 1; }
ask()  { local a; read -r -p "  $1 [Y/n] " a </dev/tty || a=n; [[ -z "$a" || "$a" =~ ^[Yy] ]]; }

if [[ "${1:-}" == "--uninstall" ]]; then
  systemctl --user disable --now "$UNIT_NAME" 2>/dev/null || true
  rm -f "$UNIT_DIR/$UNIT_NAME" "$APPS_DIR/hermes-remote-tray.desktop" "$AUTOSTART_DIR/hermes-remote-tray.desktop"
  pkill -f "$REPO/bridge/linux/tray.py" 2>/dev/null || true
  systemctl --user daemon-reload
  echo "Service removed. Paired devices and settings are still in $CONF_DIR (delete it to forget them)."
  exit 0
fi

bold "1/5 Checking requirements"
[[ "$(uname -s)" == "Linux" ]] || die "The bridge supports Linux only."
command -v systemctl >/dev/null || die "systemd is required (systemctl not found)."
command -v uv >/dev/null || die "uv is required: https://docs.astral.sh/uv/getting-started/installation/"
[[ -d "$HERMES_HOME" ]] || die "Hermes Agent not found at $HERMES_HOME. Install Hermes first: https://hermes-agent.nousresearch.com"
command -v avahi-publish-service >/dev/null || info "Optional: install avahi (avahi-utils) so the app finds the PC when its IP changes."
command -v tailscale >/dev/null || info "Optional: Tailscale is not installed; the phone will only connect on trusted Wi-Fi."
info "ok"

bold "2/5 Hermes"
info "The phone shares the Hermes the desktop app runs (or the bridge starts one); nothing to enable."

bold "3/5 Installing the bridge"
(cd "$BRIDGE" && uv sync --quiet --no-dev --inexact)
mkdir -p "$CONF_DIR"; chmod 700 "$CONF_DIR"
[[ -f "$CONF_DIR/config.toml" ]] || printf '# See bridge/hermes_remote_bridge/config.py for all keys.\nlan = true\n' > "$CONF_DIR/config.toml"
info "installed into $BRIDGE/.venv"

bold "4/5 Background service"
mkdir -p "$UNIT_DIR"
mkdir -p "${XDG_STATE_HOME:-$HOME/.local/state}/hermes-remote"
rm -f "$UNIT_DIR/$UNIT_NAME"  # may be an old symlink into the repo; never write through it
sed "s|@BIN@|$BIN|; s|@REPO@|$REPO|" "$BRIDGE/systemd/$UNIT_NAME" > "$UNIT_DIR/$UNIT_NAME"
systemctl --user daemon-reload
systemctl --user enable --now "$UNIT_NAME" >/dev/null
systemctl --user restart "$UNIT_NAME"
if [[ "$(loginctl show-user "$USER" -p Linger --value 2>/dev/null)" != "yes" ]]; then
  if ask "Keep the bridge running after you log out / start it at boot (loginctl enable-linger)?"; then
    loginctl enable-linger "$USER" || info "Couldn't enable linger; the bridge will run while you're logged in."
  fi
fi
info "running (journalctl --user -u hermes-remote-bridge -f for logs)"

bold "5/5 Network"
if ask "Is this your home or office network (let the phone connect over this Wi-Fi without Tailscale)?"; then
  "$BIN" trust || true
fi
"$BIN" firewall --if-needed || true

bold "Tray app (optional)"
if [[ -n "${WAYLAND_DISPLAY:-}${DISPLAY:-}" ]]; then
  if python3 -c 'import PyQt6.QtWidgets' 2>/dev/null; then
    mkdir -p "$APPS_DIR"
    python3 "$TRAY" --desktop-entry > "$APPS_DIR/hermes-remote-tray.desktop"
    info "added 'Hermes Remote' to the app menu"
    if [[ -f "$AUTOSTART_DIR/hermes-remote-tray.desktop" ]] || ask "Show the Hermes Remote tray icon at login?"; then
      mkdir -p "$AUTOSTART_DIR"
      python3 "$TRAY" --desktop-entry > "$AUTOSTART_DIR/hermes-remote-tray.desktop"
    fi
    (setsid python3 "$TRAY" >/dev/null 2>&1 &)  # exits by itself if one is already running
  else
    info "The tray needs PyQt6 (e.g. sudo pacman -S python-pyqt6 / sudo apt install python3-pyqt6), then: python3 $TRAY"
  fi
fi

echo
"$BIN" doctor || true
echo
bold "Next: pair your phone"
info "Install the app (APK from the GitHub releases page), then run:"
info "  $BIN pair phone"
info "and scan the QR code with the app (or tray icon -> Devices -> Pair a new device...)."
