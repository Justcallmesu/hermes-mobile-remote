#!/usr/bin/env bash
# Fail when shipped code changed between two refs but CHANGELOG.md did not.
#
#   .github/scripts/check-changelog.sh <base> <head>
#
# A pull request runs it against its base; a release runs it against the previous tag. Whether the
# entries actually describe the diff is a judgement call this script cannot make: it prints the
# commits and the files so the reviewer (or the agent cutting the release) can compare them.
set -euo pipefail

base=${1:?base ref}
head=${2:-HEAD}

# What users get: the app, the bridge and the installers. Tests, docs, demo and CI are not listed.
shipped='^(android/app/src/main/|bridge/hermes_remote_bridge/|bridge/windows/|bridge/pyproject\.toml|install\.(sh|ps1|cmd)$|pair\.cmd$|tray\.cmd$)'

files=$(git diff --name-only "$base" "$head")
code=$(grep -E "$shipped" <<<"$files" || true)

echo "Commits $base..$head:"
git log --format='  %h %an: %s' "$base..$head"
echo

if [ -z "$code" ]; then
  echo "No shipped code changed; CHANGELOG.md not required."
  exit 0
fi

if grep -qx 'CHANGELOG.md' <<<"$files"; then
  echo "Shipped code changed and CHANGELOG.md was updated:"
  git diff "$base" "$head" -- CHANGELOG.md | grep -E '^\+[^+]' | sed 's/^+/  /' || true
  exit 0
fi

echo "::error::Shipped code changed but CHANGELOG.md did not. Add a line under '## Unreleased'" \
  "(or label the pull request 'no changelog' if users will not notice the change)."
echo "Changed files:"
sed 's/^/  /' <<<"$code"
exit 1
