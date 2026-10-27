#!/usr/bin/env bash
# Desktop suite: Rust unit tests, Vue unit tests, a production web build.
set -euo pipefail
cd "$(dirname "$0")"
[ -d node_modules ] || npm ci --no-audit --no-fund
(cd src-tauri && cargo test --quiet)
npx vitest run
npx vite build
