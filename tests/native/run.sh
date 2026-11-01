#!/usr/bin/env bash
# End-to-end checks for the Scala Native `run` hooks, run against dist/ (build/07 first):
# --post-link (order, SCALINO_OUTPUT, failure, no double hooking on unchanged rebuilds),
# --run-env (run, and after a `run -w` restart) and SIGTERM/SIGINT handling of `run -w`.
#
# Usage: tests/native/run.sh [path-to-scalino]   (default: dist/scalino)
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
SCALINO="$(cd "$(dirname "${1:-$ROOT/dist/scalino}")" && pwd)/$(basename "${1:-$ROOT/dist/scalino}")"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"; [[ -z "${WATCH_PID:-}" ]] || kill -9 "$WATCH_PID" 2>/dev/null || true' EXIT
fail() { echo "FAIL: $*" >&2; exit 1; }
expect() { [[ "$1" == *"$2"* ]] || fail "expected '$2' in: $1"; }
size() { wc -c < "$1" | tr -d ' '; }

mkdir "$WORK/hello" && cd "$WORK/hello"
cat > Hello.scala <<'SCALA'
@main def hello() = println("hello FOO=" + sys.env.getOrElse("FOO", "unset"))
SCALA
# a hook that records its arguments and SCALINO_OUTPUT, then appends one byte to the binary
cat > "$WORK/hook.sh" <<'SH'
#!/bin/sh
echo "hook $1 last=$2 out=$SCALINO_OUTPUT cwd=$(pwd)" >> "$HOOKLOG"
printf X >> "$2"
SH
export HOOKLOG="$WORK/hooks.log"

echo "== --post-link: in order, before the program starts, SCALINO_OUTPUT == last argument"
out="$("$SCALINO" run . -q --post-link "sh $WORK/hook.sh first" --post-link "sh $WORK/hook.sh second" 2>&1)"
expect "$out" "hello FOO=unset"
[[ "$(sed -n 1p "$HOOKLOG")" == "hook first last="* ]] || fail "first hook not first: $(cat "$HOOKLOG")"
[[ "$(sed -n 2p "$HOOKLOG")" == "hook second last="* ]] || fail "second hook not second: $(cat "$HOOKLOG")"
line="$(sed -n 1p "$HOOKLOG")"
last="${line#*last=}"; last="${last%% out=*}"; outv="${line#*out=}"; outv="${outv%% cwd=*}"
[[ "$last" == "$outv" && "$last" == /* && -x "$last" ]] || fail "SCALINO_OUTPUT/last argument mismatch: $line"
[[ "$(cd "${line#*cwd=}" && pwd -P)" == "$(pwd -P)" ]] || fail "hook cwd is not the start directory: $line"

echo "== --post-link directive, together with a flag (directive first)"
rm -f "$HOOKLOG"
mkdir "$WORK/dir" && cd "$WORK/dir"
cat > Hello.scala <<SCALA
//> using postLink sh $WORK/hook.sh directive
@main def hello() = println("hello")
SCALA
"$SCALINO" run . -q --post-link "sh $WORK/hook.sh flag" >/dev/null 2>&1
[[ "$(sed -n 1p "$HOOKLOG")" == "hook directive "* && "$(sed -n 2p "$HOOKLOG")" == "hook flag "* ]] || fail "directive/flag order: $(cat "$HOOKLOG")"
cd "$WORK/hello"

echo "== --post-link failure: named command and exit code, the program is not started"
set +e; out="$("$SCALINO" run . -q --post-link "sh -c 'exit 7' x" 2>&1)"; code=$?; set -e
[[ $code -ne 0 ]] || fail "a failing hook must fail the build"
expect "$out" "post-link command \`sh -c 'exit 7' x\` failed with exit code 7"
[[ "$out" != *"hello FOO"* ]] || fail "the program ran after a failed hook"

echo "== package: the hooked binary is what is written (and a failed hook writes nothing)"
rm -f "$HOOKLOG" app
"$SCALINO" package . -q -o app --post-link "sh $WORK/hook.sh p" >/dev/null 2>&1
[[ -x app ]] || fail "no binary"; [[ "$(tail -c1 app)" == X ]] || fail "package output is not the hooked binary"
rm -f app
if "$SCALINO" package . -q -o app --post-link "false" >/dev/null 2>&1; then fail "package with a failing hook must fail"; fi
[[ ! -e app ]] || fail "package left a binary after a failed hook"

echo "== no double hooking: unchanged rebuilds start from the unhooked link output"
rm -f "$HOOKLOG" app
for _ in 1 2 3; do "$SCALINO" package . -q -o app --post-link "sh $WORK/hook.sh again" >/dev/null 2>&1; done
linked="$(find .scalino-build -path '*/link/*' -type f -perm -u+x -name 'hello' | head -1)"
[[ -n "$linked" ]] || fail "link output not found"
[[ "$(wc -l < "$HOOKLOG" | tr -d ' ')" == 3 ]] || fail "the hook must run on every build, ran: $(cat "$HOOKLOG")"
[[ $(( $(size app) - $(size "$linked") )) -eq 1 ]] || fail "binary hooked $(( $(size app) - $(size "$linked") )) times (expected exactly once)"
# a real relink (changed source) too
echo '// changed' >> Hello.scala
"$SCALINO" package . -q -o app --post-link "sh $WORK/hook.sh relink" >/dev/null 2>&1
[[ $(( $(size app) - $(size "$linked") )) -eq 1 ]] || fail "binary hooked more than once after a relink"
[[ "$(wc -l < "$HOOKLOG" | tr -d ' ')" == 4 ]] || fail "hook count after relink: $(cat "$HOOKLOG")"

echo "== --run-env (flag over directive, repeated flags)"
out="$("$SCALINO" run . -q --run-env FOO=bar 2>&1)"; expect "$out" "hello FOO=bar"
out="$("$SCALINO" run . -q --run-env FOO=a --run-env FOO=b=c 2>&1)"; expect "$out" "hello FOO=b=c"
cd "$WORK/dir"
cat > Hello.scala <<'SCALA'
//> using runEnv FOO=fromdirective
@main def hello() = println("hello FOO=" + sys.env.getOrElse("FOO", "unset"))
SCALA
out="$("$SCALINO" run . -q 2>&1)"; expect "$out" "hello FOO=fromdirective"
out="$("$SCALINO" run . -q --run-env FOO=fromflag 2>&1)"; expect "$out" "hello FOO=fromflag"
if "$SCALINO" run . -q --run-env NOEQUALS >/dev/null 2>&1; then fail "--run-env without = must be rejected"; fi

echo "== run -w: --run-env and --post-link survive restarts, a failed hook keeps the old program"
mkdir "$WORK/watch" && cd "$WORK/watch"
cat > W.scala <<'SCALA'
import scala.scalanative.unsafe.*
import scala.scalanative.libc.signal
object W:
  @volatile var term = 0
  def onSignal(s: CInt): Unit = term = s
  def main(args: Array[String]): Unit =
    signal.signal(signal.SIGTERM, CFuncPtr1.fromScalaFunction(onSignal))
    println("started v1 FOO=" + sys.env.getOrElse("FOO", "unset") + " pid=" + scala.scalanative.posix.unistd.getpid())
    while term == 0 do Thread.sleep(20)
    println("stopping")
    Thread.sleep(700)
    println("stopped")
    sys.exit(42)
SCALA
cat > "$WORK/maybe-fail.sh" <<'SH'
#!/bin/sh
echo "hooked" >> "$HOOKLOG"
[ ! -e "$FAILFLAG" ] || exit 3
SH
export FAILFLAG="$WORK/failflag"; rm -f "$HOOKLOG"
"$SCALINO" run -w . --run-env FOO=watch --post-link "sh $WORK/maybe-fail.sh" > watch.log 2>&1 &
WATCH_PID=$!
wait_for() { for _ in $(seq 1 300); do grep -q "$1" watch.log && return 0; sleep 0.2; done; fail "'$1' not in watch.log: $(cat watch.log)"; }
wait_for "started v1 FOO=watch"
sed -i.bak 's/v1/v2/' W.scala && rm -f W.scala.bak
wait_for "started v2 FOO=watch"
[[ "$(grep -c hooked "$HOOKLOG")" == 2 ]] || fail "expected one hook per build, got: $(cat "$HOOKLOG")"
# the old program was told to stop and was waited for before the new one started
awk '/started v1/{a=NR} /stopped/{b=NR} /started v2/{c=NR} END{exit !(a && b && c && a<b && b<c)}' watch.log || fail "the restart did not wait for the old program: $(cat watch.log)"
pid2="$(sed -n 's/.*started v2.*pid=\([0-9]*\).*/\1/p' watch.log)"
touch "$FAILFLAG"
sed -i.bak 's/v2/v3/' W.scala && rm -f W.scala.bak
for _ in $(seq 1 300); do [[ "$(grep -c hooked "$HOOKLOG")" == 3 ]] && break; sleep 0.2; done
sleep 1
! grep -q "started v3" watch.log || fail "restarted after a failed hook"
grep -q "failed with exit code 3" watch.log || fail "no hook failure reported: $(cat watch.log)"
kill -0 "$pid2" 2>/dev/null || fail "the running program was stopped by a failed hook"
rm -f "$FAILFLAG"

echo "== run -w: SIGTERM is forwarded, scalino waits and exits with the program's exit code"
kill -TERM "$WATCH_PID"
set +e; wait "$WATCH_PID"; code=$?; set -e; WATCH_PID=
[[ $code -eq 42 ]] || fail "scalino exited with $code, expected the program's 42: $(cat watch.log)"
grep -q "^stopped" watch.log || fail "scalino did not wait for the program to finish stopping"
! kill -0 "$pid2" 2>/dev/null || fail "the program outlived scalino"

echo "== run -w: SIGINT too (not from a terminal: forwarded as SIGTERM-equivalent default action)"
cat > W.scala <<'SCALA'
@main def w() =
  println("started pid=" + scala.scalanative.posix.unistd.getpid())
  while true do Thread.sleep(50)
SCALA
rm -rf .scalino-build
"$SCALINO" run -w . > watch.log 2>&1 < /dev/null &
WATCH_PID=$!
wait_for "started pid="
pid="$(sed -n 's/.*started pid=\([0-9]*\).*/\1/p' watch.log | head -1)"
kill -INT "$WATCH_PID"
set +e; wait "$WATCH_PID"; code=$?; set -e; WATCH_PID=
[[ $code -ne 0 ]] || fail "expected the program's non-zero exit after SIGINT, got $code"
! kill -0 "$pid" 2>/dev/null || fail "the program outlived scalino after SIGINT"

echo "OK"
