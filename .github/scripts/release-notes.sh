#!/usr/bin/env bash
# Release notes for tag $1 (e.g. v1.35.0) from CHANGELOG.md, written to stdout:
#   - a Download line at the top (direct APK link), so nobody scrolls past the changelog to find it
#   - this version's changes
#   - every older version folded in <details>, so someone skipping versions still sees them
#     (the app's update card reads all of them; it ignores the HTML lines)
set -euo pipefail
tag="$1"
repo="${GITHUB_REPOSITORY:-nideta231/hermes-mobile-remote}"
changelog="${2:-CHANGELOG.md}"
version="${tag#v}"
apk="HermesRemote-$version.apk"
url="https://github.com/$repo/releases/download/$tag/$apk"

current=$(awk -v tag="## $tag" '$0 == tag {on = 1; next} on && /^## / {exit} on' "$changelog")
older=$(awk -v tag="## $tag" '$0 == tag {on = 1; next} on && /^## / {rest = 1} rest' "$changelog")
[ -n "$current" ] || { echo "CHANGELOG has no '## $tag' section" >&2; exit 1; }

echo "**Download: [$apk]($url)** · [SHA-256]($url.sha256) · or update from the app (it updates in place)."
echo
echo "## $tag"
printf '%s\n' "$current" | sed -e '/./,$!d'
if [ -n "$older" ]; then
  echo
  echo "<details><summary>Earlier versions</summary>"
  echo
  printf '%s\n' "$older"
  echo
  echo "</details>"
fi
