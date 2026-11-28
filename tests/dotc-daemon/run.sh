#!/usr/bin/env bash
# The background compiler (`scalino-dotc --daemon`, patches/scala3-0045, cli/DotcDaemon.scala): a
# sequence of edits through `scalino compile` with SCALINO_DOTC_DAEMON=verify, which compiles every
# batch again with a cold scalino-dotc and fails on any output difference. Covers the cases where
# warm state could go stale: renamed and removed classes and files, top-level definitions, a
# compile error followed by its fix (the daemon drops its state after a failure), a same-project
# macro, enum/sealed changes, plus that a daemon is reused, that `$(scalino ...)` doesn't wait for it,
# that a one-shot (--no-daemon) build in between is noticed, and `scalino daemon status|stop`.
#
# Usage: tests/dotc-daemon/run.sh [path-to-scalino]   (default: dist/scalino)
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
SCALINO="$(cd "$(dirname "${1:-$ROOT/dist/scalino}")" && pwd)/$(basename "${1:-$ROOT/dist/scalino}")"
WORK="$(mktemp -d)"
export SCALINO_DOTC_DAEMON_DIR="$WORK/daemons" SCALINO_DOTC_DAEMON=verify
unset CI
cleanup() { "$SCALINO" daemon stop > /dev/null 2>&1 || true; rm -rf "$WORK"; }
trap cleanup EXIT
fail() { echo "FAIL: $*" >&2; exit 1; }
cd "$WORK"
mkdir -p src

n=0
compile() { # compile <label> [expect-failure]
  n=$((n + 1))
  if "$SCALINO" compile . > "log$n" 2>&1; then
    [[ "${2:-}" != fail ]] || { cat "log$n" >&2; fail "$1: compiled, expected an error"; }
  else
    [[ "${2:-}" == fail ]] || { cat "log$n" >&2; fail "$1"; }
    grep -q 'dotc daemon verification failed\|differs from a cold one' "log$n" && { cat "log$n" >&2; fail "$1: verification"; }
  fi
  if [[ "${2:-}" != fail ]]; then
    grep -q 'Verified the dotc daemon' "log$n" || { cat "log$n" >&2; fail "$1: did not go through the daemon"; }
  fi
  echo "ok: $1"
}

printf 'package p\nobject A { def f: Int = 1 }\n' > src/A.scala
printf 'package p\nobject B { def g = A.f + 1 }\n' > src/B.scala
printf 'package q\ncase class C(x: Int)\nobject Top { def h = 1 }\n' > src/C.scala
printf '@main def hello() = println(p.B.g)\n' > src/Main.scala
compile "first build"
[[ "$(ls daemons/*.state | wc -l | tr -d ' ')" == 1 ]] || fail "expected one daemon"

printf 'package p\nobject A { def f: Int = 2 }\n' > src/A.scala
compile "edit a method body"
"$SCALINO" daemon status | grep -q 'warmRuns=1' || { "$SCALINO" daemon status; fail "second compile was not warm"; }

printf 'package p\nobject A2 { def f: Int = 3 }\n' > src/A.scala
printf 'package p\nobject B { def g = A2.f + 1 }\n' > src/B.scala
compile "rename a class"

printf 'package p\nobject B { def g = A.f + 1 }\n' > src/B.scala
compile "a reference to the old name fails" fail

printf 'package p\nobject B { def g = A2.f + q.C(4).x }\n' > src/B.scala
compile "fixed after the error"

rm src/C.scala
printf 'package p\nobject B { def g = A2.f }\n' > src/B.scala
compile "delete a file"

printf 'package p\nobject B { def g = A2.f + q.Top.h }\n' > src/B.scala
compile "a reference to the deleted file fails" fail

printf 'package p\nobject B { def g = A2.f + twice(4) }\n' > src/B.scala
printf 'package p\nobject A2 { def f: Int = 3 }\ndef twice(x: Int): Int = x * 2\n' > src/A.scala
compile "top-level def"

printf 'package m\nimport scala.quoted.*\nobject Mac {\n  inline def twice(inline x: Int): Int = ${ impl(%s) }\n  def impl(x: Expr[Int])(using Quotes): Expr[Int] = %s{ $x * 2 }\n}\n' "'x" "'" > src/M.scala
printf 'package m\nobject UseM { def v = Mac.twice(21) }\n' > src/UseM.scala
compile "same-project macro"
printf 'package m\nobject UseM { def v = Mac.twice(22) }\n' > src/UseM.scala
compile "edit the macro's use"

printf 'package i\nenum Color { case Red, Green }\nsealed trait Shape\ncase class Sq(s: Int) extends Shape\n' > src/I.scala
printf 'package i\nobject J { def f = Color.values.length; def g(s: Shape) = s match { case Sq(x) => x } }\n' > src/J.scala
compile "enum and sealed trait"
printf 'package i\nenum Color { case Red, Green, Blue }\nsealed trait Shape\ncase class Sq(s: Int) extends Shape\ncase class Ci(r: Int) extends Shape\n' > src/I.scala
compile "new enum case and sealed child"

echo "== a --no-daemon build in between makes the daemon start over"
printf 'package p\nobject A2 { def f: Int = 5 }\ndef twice(x: Int): Int = x * 2\n' > src/A.scala
"$SCALINO" compile --no-daemon . > log-nodaemon 2>&1 || { cat log-nodaemon >&2; fail "--no-daemon build"; }
grep -q 'Verified the dotc daemon' log-nodaemon && fail "--no-daemon went through the daemon"
printf 'package p\nobject A2 { def f: Int = 6 }\ndef twice(x: Int): Int = x * 2\n' > src/A.scala
compile "after a --no-daemon build"

echo "== the program built through the daemon runs"
out="$(SCALINO_DOTC_DAEMON=1 "$SCALINO" run . 2> run.log)" || { cat run.log >&2; fail "run"; }
[[ "$(printf "%s\n" "$out" | tail -1)" == 14 ]] || fail "expected 14, got: $out"

echo "== \$(scalino ...) returns without waiting for the daemon"
start=$(date +%s)
x="$(SCALINO_DOTC_DAEMON=1 "$SCALINO" compile . 2>&1)"
[[ $(( $(date +%s) - start )) -lt 30 ]] || fail "command substitution waited for the daemon"

echo "== the daemon's request log"
grep -q 'compile of .* for ' daemons/*.log || fail "no compile line in the daemon log"
grep -q 'done in .*SCALINO_COMPILE_DONE 0' daemons/*.log || fail "no done line in the daemon log"
[[ -z "$(ls daemons/*.busy 2>/dev/null)" ]] || fail "busy marker left behind"

echo "== daemon stop: only this directory's"
mkdir -p "$WORK/elsewhere"
out="$(cd "$WORK/elsewhere" && "$SCALINO" daemon stop)"
[[ "$out" == *"stopped 0 "*"1 scalino-dotc daemon of other directories left running"* ]] || fail "daemon stop from another directory: $out"
"$SCALINO" daemon status | grep -q 'pid=' || fail "daemon stop from another directory stopped this one"
"$SCALINO" daemon stop | grep -q 'stopped 1' || fail "daemon stop"
[[ -z "$(ls daemons/*.state 2>/dev/null)" ]] || fail "state file left behind"
echo "OK"
