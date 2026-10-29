#!/usr/bin/env bash
# End-to-end contract test with no Hermes and no model calls: the real bridge in front of the demo
# backend, and the app's real socket/reducer code (DemoBridgeTest) talking to it. CI runs this on
# every push; it works the same on a laptop.
#
#   ./demo/e2e.sh                 # start the stack, run DemoBridgeTest, stop the stack
#   ./demo/e2e.sh --keep          # leave the stack running afterwards (prints the creds file)
#
# Everything lives in a fresh temporary state directory on loopback ports; your real bridge,
# devices and Hermes are never touched.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
PORT="${E2E_PORT:-18765}"
HERMES_PORT="${E2E_HERMES_PORT:-18766}"
STATE="$(mktemp -d "${TMPDIR:-/tmp}/hr-e2e.XXXXXX")"
KEEP="${1:-}"
PY="$ROOT/bridge/.venv/bin/python"
BRIDGE="$ROOT/bridge/.venv/bin/hermes-remote-bridge"

[ -x "$BRIDGE" ] || { echo "bridge/.venv missing. Run: (cd bridge && uv sync)" >&2; exit 1; }

# Loopback only, no LAN, no mDNS. allowed_logins is a placeholder: the bridge wants an owner when it
# has no LAN, and the fake Tailscale socket is not consulted for loopback requests.
cat > "$STATE/config.toml" <<EOF
port = $PORT
listen = ["127.0.0.1"]
lan = false
mdns = false
hermes_home = "$STATE/hermes-home"
start_hermes = false
hermes_bin = "$ROOT/demo/fake-hermes-cli"
allowed_logins = ["e2e@example.com"]
devices_file = "$STATE/devices.json"
trust_file = "$STATE/networks.json"
tls_dir = "$STATE/tls"
audit_log = "$STATE/audit.log"
EOF
printf '{"approvals.mode": "manual", "agent.reasoning_effort": "medium"}\n' > "$STATE/hermes-config.json"

cleanup() {
  [ -n "${MOCK_PID:-}" ] && kill "$MOCK_PID" 2>/dev/null || true
  [ -n "${BRIDGE_PID:-}" ] && kill "$BRIDGE_PID" 2>/dev/null || true
  wait 2>/dev/null || true  # ports are free once this returns
  rm -rf "$STATE"
}
[ "$KEEP" = "--keep" ] || trap cleanup EXIT INT TERM

wait_http() {  # url, what
  for _ in $(seq 1 60); do
    code=$(curl -s -o /dev/null -w '%{http_code}' "$1" || true)
    [ "$code" != "000" ] && return 0
    sleep 0.5
  done
  echo "$2 did not come up; log:" >&2
  cat "$STATE"/*.log >&2 || true
  exit 1
}

# DEMO_SPEED=0: the scripted turn streams without pauses.
DEMO_HERMES_PORT="$HERMES_PORT" DEMO_HERMES_HOME="$STATE/hermes-home" DEMO_SPEED=0 \
  "$PY" "$ROOT/demo/mock_hermes.py" > "$STATE/mock.log" 2>&1 &
MOCK_PID=$!
wait_http "http://127.0.0.1:$HERMES_PORT/" "the demo backend"

DEMO_HERMES_CONFIG="$STATE/hermes-config.json" \
  "$PY" "$ROOT/demo/serve.py" --config "$STATE/config.toml" serve > "$STATE/bridge.log" 2>&1 &
BRIDGE_PID=$!
wait_http "http://127.0.0.1:$PORT/v1/me" "the bridge"

"$BRIDGE" --config "$STATE/config.toml" pair e2e --url "http://127.0.0.1:$PORT" --token-file "$STATE/e2e.json"

if [ "$KEEP" = "--keep" ]; then
  echo "Stack left running. HERMES_REMOTE_DEMO_E2E=$STATE/e2e.json  (pids $MOCK_PID $BRIDGE_PID)"
fi

cd "$ROOT/android"
status=0
HERMES_REMOTE_DEMO_E2E="$STATE/e2e.json" ./gradlew testDebugUnitTest --tests '*DemoBridgeTest*' --rerun-tasks || status=$?

# A test that was skipped (no creds) would also pass: insist that all of them really ran.
python3 - "$ROOT/android/app/build/test-results/testDebugUnitTest" <<'EOF' || status=1
import glob, sys, xml.etree.ElementTree as ET
files = glob.glob(f"{sys.argv[1]}/*DemoBridgeTest*.xml")
if not files:
    sys.exit("DemoBridgeTest produced no results")
s = ET.parse(files[0]).getroot()
tests, skipped = int(s.get("tests")), int(s.get("skipped"))
fail = int(s.get("failures")) + int(s.get("errors"))
print(f"DemoBridgeTest: {tests} tests, {fail} failed, {skipped} skipped")
sys.exit(1 if fail or skipped or tests == 0 else 0)
EOF

if [ $status -ne 0 ]; then
  echo "--- bridge.log"; tail -n 40 "$STATE/bridge.log" || true
  echo "--- mock.log"; tail -n 40 "$STATE/mock.log" || true
fi
exit $status
