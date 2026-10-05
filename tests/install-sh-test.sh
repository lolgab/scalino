#!/usr/bin/env bash
# Regression test for install.sh's GitHub access, using a stub `curl` -- no
# network. Checks that:
#   - a pinned $SCALINO_VERSION never touches api.github.com and builds
#     release-asset URLs matching release.yml's naming (scalino-<tag>-<target>)
#   - `latest` sends Authorization: Bearer from $GITHUB_TOKEN / $GH_TOKEN
#   - a 403/429 is reported as a rate limit, a 404 as "no release"
set -euo pipefail
here="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT
mkdir "$work/bin"

# Stub curl: logs "<args>" per call; API calls answer with $STUB_API_STATUS
# (200 => a canned release JSON), downloads write a tiny valid tarball.
cat > "$work/bin/curl" <<'STUB'
#!/usr/bin/env bash
echo "$*" >> "$STUB_LOG"
out="" ; url=""
while [ $# -gt 0 ]; do
  case "$1" in
    -o) out="$2"; shift 2 ;;
    -w|-H) shift 2 ;;
    -*) shift ;;
    *) url="$1"; shift ;;
  esac
done
case "$url" in
  https://api.github.com/*)
    status="${STUB_API_STATUS:-200}"
    if [ "$status" = 200 ]; then
      printf '{"tag_name": "v9.9.9", "assets": [{"browser_download_url": "https://github.com/lolgab/scalino/releases/download/v9.9.9/scalino-v9.9.9-%s.tar.gz"}]}' "$STUB_TARGET" > "$out"
    else
      : > "$out"
    fi
    printf '%s' "$status" ;;
  *.sha256) exit 22 ;;  # no checksum asset: install.sh just warns
  *) cp "$STUB_TARBALL" "$out" ;;
esac
STUB
chmod +x "$work/bin/curl"

case "$(uname -s)-$(uname -m)" in
  Linux-x86_64) target=linux-x86_64 ;;
  Linux-aarch64|Linux-arm64) target=linux-arm64 ;;
  Darwin-x86_64) target=macos-x86_64 ;;
  Darwin-arm64) target=macos-arm64 ;;
  *) echo "unsupported host for this test"; exit 0 ;;
esac

# Fake release tarball: one top-level dir with the three launchers.
mkdir -p "$work/pkg/scalino-vX-$target"
for f in scalino scalino-lsp scalino-cs; do : > "$work/pkg/scalino-vX-$target/$f"; done
tar czf "$work/scalino.tar.gz" -C "$work/pkg" "scalino-vX-$target"

export STUB_LOG="$work/curl.log" STUB_TARGET="$target" STUB_TARBALL="$work/scalino.tar.gz"
export PATH="$work/bin:$PATH"
fails=0
check() { if ! eval "$2"; then echo "FAIL: $1"; fails=$((fails + 1)); else echo "ok: $1"; fi; }
run() { # run <extra env...> -- ; stdout+stderr in $work/out, status in $?
  : > "$STUB_LOG"
  env -u GITHUB_TOKEN -u GH_TOKEN -u SCALINO_VERSION \
    SCALINO_INSTALL_DIR="$work/inst" SCALINO_BIN_DIR="$work/binout" "$@" \
    bash "$here/install.sh" > "$work/out" 2>&1
}

rm -rf "$work/inst"
run SCALINO_VERSION=v0.0.16 GITHUB_TOKEN=secret || true
check "pinned: no api.github.com call" '! grep -q api.github.com "$STUB_LOG"'
check "pinned: asset URL follows release.yml naming" \
  'grep -q "releases/download/v0.0.16/scalino-v0.0.16-$target.tar.gz" "$STUB_LOG"'
check "pinned: checksum URL requested" 'grep -q "scalino-v0.0.16-$target.tar.gz.sha256" "$STUB_LOG"'
check "pinned: installed under the tag" '[ -x "$work/inst/v0.0.16/scalino" ]'
check "pinned: token is not sent to github.com downloads" '! grep -q secret "$STUB_LOG"'

rm -rf "$work/inst"
run SCALINO_VERSION=0.0.16 || true
check "pinned without 'v': normalized to the v-tag" \
  'grep -q "releases/download/v0.0.16/scalino-v0.0.16-$target.tar.gz" "$STUB_LOG" && ! grep -q api.github.com "$STUB_LOG"'

rm -rf "$work/inst"
run GITHUB_TOKEN=tok1 || true
check "latest: Authorization from GITHUB_TOKEN" 'grep -q "Authorization: Bearer tok1" "$STUB_LOG"'
check "latest: resolves the tag from the API" '[ -x "$work/inst/v9.9.9/scalino" ]'

rm -rf "$work/inst"
run GH_TOKEN=tok2 || true
check "latest: Authorization falls back to GH_TOKEN" 'grep -q "Authorization: Bearer tok2" "$STUB_LOG"'

rm -rf "$work/inst"
run || true
check "latest: no Authorization header without a token" '! grep -qi "authorization" "$STUB_LOG"'
check "latest: still works with no env vars" '[ -x "$work/inst/v9.9.9/scalino" ]'

for code in 403 429; do
  if run STUB_API_STATUS=$code; then st=0; else st=$?; fi
  check "$code: fails, reported as rate limit" '[ "$st" -ne 0 ] && grep -qi "rate limit" "$work/out" && ! grep -q "has a release been published" "$work/out"'
done
if run STUB_API_STATUS=404; then st=0; else st=$?; fi
check "404: fails, reported as no release" '[ "$st" -ne 0 ] && grep -q "has a release been published" "$work/out" && ! grep -qi "rate limit" "$work/out"'

[ "$fails" -eq 0 ] || { echo "$fails check(s) failed"; exit 1; }
echo "all install.sh checks passed"
