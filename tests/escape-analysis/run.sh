#!/usr/bin/env bash
# Regression test for a stack allocation miscompile (escape analysis, patch 0005):
# a callee that stores one parameter into another made the stored object look
# local to the caller. See Main.scala.
#
# Needs a scalino built with the patch. The mutation check relinks with
# SCALANATIVE_EA_X=mutate-collapsedext (re-introduces the bug) and requires the
# program to then misbehave: proof that this test can fail.
#
# Usage: tests/escape-analysis/run.sh [path-to-scalino]   (default: dist/scalino)
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
SCALINO="$(cd "$(dirname "${1:-$ROOT/dist/scalino}")" && pwd)/$(basename "${1:-$ROOT/dist/scalino}")"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT
fail() { echo "FAIL: $*" >&2; exit 1; }
cp "$ROOT/tests/escape-analysis/Main.scala" "$WORK/"
cd "$WORK"

EXPECTED='direct c=true d=1 sum=1002003
trait c=true r1=11002003 r2=1022003'

# run <label> [VAR=value...]: link release-fast from scratch (the link cache is
# not keyed on the environment), run, print the program's output (or CRASH)
run() {
  local label=$1; shift
  rm -rf .scalino-build
  env "$@" "$SCALINO" package Main.scala --main-class Main --native-mode release-fast -o "$WORK/main-$label" >"$WORK/link-$label.log" 2>&1 \
    || { cat "$WORK/link-$label.log" >&2; fail "link ($label)"; }
  "$WORK/main-$label" 2>/dev/null || echo "CRASH($?)"
}

echo "== reference: stack objects off"
out="$(run off SCALANATIVE_STACK_OBJECTS=0)"
[[ "$out" == "$EXPECTED" ]] || fail "reference output wrong: $out"

echo "== stack objects on"
out="$(run on SCALANATIVE_STACK_OBJECTS=1)"
[[ "$out" == "$EXPECTED" ]] || fail "release-fast output differs: $out"

echo "== stack objects on, dead stack poisoned"
out="$(run poison SCALANATIVE_STACK_OBJECTS=1 SCALANATIVE_STACK_POISON=1)"
[[ "$out" == "$EXPECTED" ]] || fail "poisoned output differs: $out"

echo "== mutation: bug re-introduced must be caught"
out="$(run mutant SCALANATIVE_STACK_OBJECTS=1 SCALANATIVE_STACK_POISON=1 SCALANATIVE_EA_X=mutate-collapsedext)"
[[ "$out" != "$EXPECTED" ]] || fail "mutant not caught: the test cannot detect the bug"

echo "PASS"
