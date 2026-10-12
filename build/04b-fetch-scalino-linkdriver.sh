#!/usr/bin/env bash
# Bootstrap scalino-linkdriver: downloads the one shipped in the latest (or
# $SCALINO_BOOTSTRAP_VERSION-pinned) scalino release instead of building it on
# the JVM (04-build-scalino-linkdriver.sh). 03/07/08 then link scalino-dotc,
# scalino and scalino-lsp with it directly, so no JVM-hosted LinkDriver run is
# needed anywhere in the pipeline.
#
# Trade-off: the downloaded linkdriver carries the tools_3/nativelib patches of
# the release it came from, not of this checkout, so tools-side patch changes
# only reach the *output* binaries one release later.
#
# Usage: 04b-fetch-scalino-linkdriver.sh   (writes $DIST/scalino-linkdriver)
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")"
source ./00-env.sh

REPO="${SCALINO_REPO:-lolgab/scalino}"
VERSION="${SCALINO_BOOTSTRAP_VERSION:-latest}"

case "$(uname -s)" in
  Darwin) os=macos ;;
  Linux)  os=linux ;;
  *) echo "no released scalino-linkdriver for $(uname -s); use 04-build-scalino-linkdriver.sh" >&2; exit 1 ;;
esac
case "$(uname -m)" in
  arm64|aarch64) arch=arm64 ;;
  x86_64|amd64)  arch=x86_64 ;;
  *) echo "unsupported arch $(uname -m)" >&2; exit 1 ;;
esac
TARGET="$os-$arch"

API="https://api.github.com/repos/$REPO/releases"
if [[ "$VERSION" == latest ]]; then API="$API/latest"; else API="$API/tags/$VERSION"; fi
AUTH=()
[[ -n "${GITHUB_TOKEN:-}" ]] && AUTH=(-H "Authorization: Bearer $GITHUB_TOKEN")
JSON="$(curl -fsSL ${AUTH[@]+"${AUTH[@]}"} "$API")"
TAG="$(printf '%s' "$JSON" | grep -o '"tag_name": *"[^"]*"' | head -1 | sed -E 's/.*"([^"]+)"$/\1/')"
URL="$(printf '%s' "$JSON" | grep -o "\"browser_download_url\": *\"[^\"]*-${TARGET}\\.tar\\.gz\"" | head -1 | sed -E 's/.*"(https[^"]+)"/\1/')"
[[ -n "$TAG" && -n "$URL" ]] || { echo "no $TARGET release asset found via $API" >&2; exit 1; }

CACHE="$WORK/bootstrap/$TAG-$TARGET"
if [[ ! -x "$CACHE/scalino-linkdriver" ]]; then
  echo "== downloading $URL"
  rm -rf "$CACHE"; mkdir -p "$CACHE"
  curl -fsSL "$URL" -o "$CACHE/release.tar.gz"
  curl -fsSL "$URL.sha256" -o "$CACHE/release.tar.gz.sha256"
  expected="$(awk '{print $1}' "$CACHE/release.tar.gz.sha256")"
  actual="$( (shasum -a 256 "$CACHE/release.tar.gz" 2>/dev/null || sha256sum "$CACHE/release.tar.gz") | awk '{print $1}')"
  [[ "$expected" == "$actual" ]] || { echo "sha256 mismatch for $URL" >&2; exit 1; }
  tar -xzf "$CACHE/release.tar.gz" -C "$CACHE" --strip-components=1 --wildcards '*/scalino-linkdriver' 2>/dev/null \
    || tar -xzf "$CACHE/release.tar.gz" -C "$CACHE" --strip-components=1
  [[ -f "$CACHE/scalino-linkdriver" ]] || { echo "release tarball has no scalino-linkdriver" >&2; exit 1; }
  chmod +x "$CACHE/scalino-linkdriver"
fi

mkdir -p "$DIST"
# rm first: see 04-build-scalino-linkdriver.sh (macOS stale code-signature cache)
rm -f "$DIST/scalino-linkdriver"
cp "$CACHE/scalino-linkdriver" "$DIST/scalino-linkdriver"
echo "OK: $DIST/scalino-linkdriver (bootstrapped from $TAG)"
