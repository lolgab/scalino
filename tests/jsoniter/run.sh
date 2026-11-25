#!/usr/bin/env bash
# jsoniter-scala-core reads and writes byte arrays through raw pointers, with
# its own copy of where their elements start (ByteArrayAccess). Compact headers
# keep byte array elements at that offset (CommonMemoryLayouts.ArrayHeader.
# byteLayout): JSON round trips with compact headers on and off.
#
# Usage: tests/jsoniter/run.sh [path-to-scalino]   (default: dist/scalino)
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
SCALINO="$(cd "$(dirname "${1:-$ROOT/dist/scalino}")" && pwd)/$(basename "${1:-$ROOT/dist/scalino}")"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT
fail() { echo "FAIL: $*" >&2; exit 1; }

cp "$ROOT/tests/jsoniter/Json.scala" "$WORK/"
cd "$WORK"
for opts in "--native-mode debug" "--native-mode release-fast" "--native-mode debug --native-compact-headers=false"; do
  echo "== $opts"
  # shellcheck disable=SC2086 # $opts is several words
  log="$("$SCALINO" package Json.scala -o json -q $opts 2>&1)" || fail "package $opts:
$log"
  out="$(./json 2>&1)" || fail "$opts exited non-zero:
$out"
  [[ "$out" == *"ALL OK"* ]] || fail "$opts:
$out"
  echo "$out"
done
echo "PASS"
