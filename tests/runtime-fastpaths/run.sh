#!/usr/bin/env bash
# Module loading and stack overflow detection after their fast paths moved into
# generated code (patches scala-native-0037: the module accessor checks an
# initialized slot itself and calls __scalanative_loadModule only otherwise;
# 0038: self-recursive methods poll a global count of threads with a pending
# StackOverflowError and call scalanative_StackOverflowGuards_check only when it
# is non-zero).
#
# ModuleInit.scala: 32 threads racing on a slow module ctor (one init), a failing
# ctor (its exception, then NoClassDefFoundError, also from other threads), a
# cyclic init on one thread. StackOverflow.scala: repeated overflows on the main
# thread, an overflow inside synchronized (monitor released), 8 threads
# overflowing while 8 others recurse normally. Both print a timing line that is
# not checked. Debug and release-fast, compact headers on and off.
#
# Usage: tests/runtime-fastpaths/run.sh [path-to-scalino]   (default: dist/scalino)
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
SCALINO="$(cd "$(dirname "${1:-$ROOT/dist/scalino}")" && pwd)/$(basename "${1:-$ROOT/dist/scalino}")"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT
fail() { echo "FAIL: $*" >&2; exit 1; }

for prog in ModuleInit StackOverflow; do
  mkdir "$WORK/$prog"
  cp "$ROOT/tests/runtime-fastpaths/$prog.scala" "$WORK/$prog/"
  for opts in "--native-mode debug" "--native-mode release-fast" "--native-compact-headers=false"; do
    echo "== $prog $opts"
    # shellcheck disable=SC2086 # $opts is one or two words
    out="$(cd "$WORK/$prog" && "$SCALINO" run "$prog.scala" -q $opts 2>&1)" || fail "$prog $opts exited non-zero:
$out"
    echo "$out" | grep -E "bench|hot ok" || true
    [[ "$out" == *"ALL OK"* ]] || fail "$prog $opts:
$out"
  done
done
echo "PASS"
