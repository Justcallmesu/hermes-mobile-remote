#!/usr/bin/env bash
# Desktop suite: Rust unit tests, Vue unit tests, a production web build.
set -euo pipefail
cd "$(dirname "$0")"
# Never touch the real config/state/data of whoever runs the suite.
T=$(mktemp -d); trap 'rm -rf "$T"' EXIT
export XDG_CONFIG_HOME=$T/config XDG_STATE_HOME=$T/state XDG_DATA_HOME=$T/data
[ -d node_modules ] || npm ci --no-audit --no-fund
(cd src-tauri && cargo test --quiet)
npx vitest run
npx vite build
