#!/usr/bin/env bash
# Object monitors (synchronized, wait/notify) in every lock state: fast locks
# with recursion past the 8-entry lock stack, nesting, contention that inflates,
# wait/notify, exceptions unwinding, static objects (classes, string literals),
# thousands of inflated monitors becoming garbage (MonitorTable rebuilds),
# virtual threads. Compact headers (lightweight locking, see
# runtime.monitor.CompactMonitor) and the regular lock word; with evacuation
# too, since inflated objects must not move.
#
# Usage: tests/object-locks/run.sh [path-to-scalino]   (default: dist/scalino)
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
SCALINO="$(cd "$(dirname "${1:-$ROOT/dist/scalino}")" && pwd)/$(basename "${1:-$ROOT/dist/scalino}")"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT
fail() { echo "FAIL: $*" >&2; exit 1; }

cp "$ROOT/tests/object-locks/Locks.scala" "$WORK/"
cd "$WORK"
for opts in "--native-mode debug" "--native-mode release-fast" "--native-mode release-fast --native-compact-headers=false"; do
  # shellcheck disable=SC2086 # $opts is several words
  "$SCALINO" package Locks.scala -o locks -q $opts >/dev/null 2>&1 || fail "package $opts"
  for env in "" "SCALANATIVE_EVAC_ENABLE=1 GC_INITIAL_HEAP_SIZE=4M"; do
    echo "== $opts ${env:-(no evac)}"
    out="$(env $env ./locks 2>&1)" || fail "$opts $env exited non-zero:
$out"
    echo "$out" | grep -E "bench|virtual" || true
    [[ "$out" == *"ALL OK"* ]] || fail "$opts $env:
$out"
  done
done
echo "PASS"
