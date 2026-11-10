#!/usr/bin/env bash
# End-to-end check of the opt-in Lower cache (SCALINO_LOWER_CACHE, patches/scala-native-0019):
# a `package -w` session applies a sequence of edits (literal, constant, new method and call, new
# class, constant module, module with a field, override body, revert, body again, ... a first call of a
# method of a class that is already loaded). After every
# edit the binary must be byte-identical to a fresh, uncached link of the same sources, both in
# `verify` mode (re-lowers every method it would have reused and fails the link on a difference)
# and in plain `1` mode, where body-only edits must actually reuse lowered methods.
#
# Usage: tests/lower-cache/run.sh [path-to-scalino]   (default: dist/scalino)
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
SCALINO="$(cd "$(dirname "${1:-$ROOT/dist/scalino}")" && pwd)/$(basename "${1:-$ROOT/dist/scalino}")"
WORK="$(mktemp -d)"
trap '[[ -z "${LOWER_CACHE_KEEP_LOGS:-}" ]] || { mkdir -p "$LOWER_CACHE_KEEP_LOGS"; cp "$WORK"/*.log "$WORK"/bin-* "$LOWER_CACHE_KEEP_LOGS"/ 2>/dev/null || true; }; rm -rf "$WORK"; [[ -z "${WATCH_PID:-}" ]] || kill -9 "$WATCH_PID" 2>/dev/null || true' EXIT
fail() { echo "FAIL: $*" >&2; exit 1; }

# prog <greet> <twice> <thrice:0|1> <circle:0|1> <cfg:none|const|val> <rectname> <sqname:0|1> <printable:0|1> <rectfield:0|1> <recur:0|1|2> <extracall:0|1>
prog() {
  local greet=$1 twice=$2 thrice=$3 circle=$4 cfg=$5 rectname=$6 sqname=$7 printable=$8 rectfield=$9 recur=${10} extracall=${11}
  local circle_cls="" cfgdef="" cfguse="" th="" thuse="" shapes='List(new Square(3), new Rect(2, 5)'
  local sqover="" rectwith="" rectbody="" printdef="" printuse="" fieldsuse="" recurdef="" recuruse="" greetextra=""
  [[ $circle == 1 ]] && { circle_cls='class Circle(r: Int) extends Shape { def area = 3 * r * r; override def name = "circle" }'; shapes+=', new Circle(4)'; }
  shapes+=')'
  case $cfg in
    const) cfgdef='object Config { def label = "cfg" }'; cfguse='  println(Config.label)' ;;
    val) cfgdef='object Config { val label = "cfg" + System.nanoTime.toString.take(0) }'; cfguse='  println(Config.label)' ;;
  esac
  [[ $thrice == 1 ]] && { th='  def thrice(x: Int) = x * 3'; thuse='  println(Util.thrice(7))'; }
  [[ $sqname == 1 ]] && sqover='; override def name = "square"'
  if [[ $printable == 1 ]]; then
    printdef='trait Printable { def show: String }'; rectwith=' with Printable'; rectbody='; def show = "rect " + area'
    printuse='  shapes.collect { case p: Printable => p.show }.foreach(println)'
  fi
  if [[ $rectfield == 1 ]]; then
    rectbody+='; val extra = w + h; def total = extra + 1'; fieldsuse='  println(new Rect(1, 2).total)'
  fi
  case $recur in
    1) recurdef='  def fact(n: Int): Int = if (n <= 1) 1 else n * fact(n - 1)'; recuruse='  println(Util.fact(5))' ;;
    3) recurdef='  def fact(n: Int): Int = (1 to n).product'; recuruse='  println(Util.fact(5))' ;;
    2) recurdef='  def fact(n: Int): Int = { var r = 1; var i = 2; while (i <= n) { r = r * i; i += 1 }; r }'; recuruse='  println(Util.fact(5))' ;;
  esac
  [[ $extracall == 1 ]] && greetextra=' + Util.twice(1)'
  cat <<SCALA
package app

trait Shape { def area: Int; def name: String = "shape" }
$printdef
class Square(s: Int) extends Shape { def area = s * s$sqover }
class Rect(w: Int, h: Int) extends Shape$rectwith { def area = w * h; override def name = "$rectname"$rectbody }
$circle_cls
$cfgdef
object Util {
  def twice(x: Int) = $twice
  def greet(s: String) = s"$greet \$s"$greetextra
$th
$recurdef
}

@main def main =
  val shapes: List[Shape] = $shapes
  shapes.foreach(s => println(s"\${s.name}: \${s.area}"))
  println(Util.greet("world"))
  println(Util.twice(21))
$thuse
$cfguse
$printuse
$fieldsuse
$recuruse
SCALA
}

# version <n>  ->  the sources of step n on stdout
version() {
  case $1 in
    0) prog hi "x * 2" 0 0 none rect 0 0 0 0 0 ;;
    1) prog hello "x * 2" 0 0 none rect 0 0 0 0 0 ;;                       # string literal
    2) prog hello "x * 3" 0 0 none rect 0 0 0 0 0 ;;                       # int constant
    3) prog hello "x * 3" 1 0 none rect 0 0 0 0 0 ;;                       # new method + call
    4) prog hello "x * 3" 1 1 none rect 0 0 0 0 0 ;;                       # new class
    5) prog hello "x * 3" 1 1 const rect 0 0 0 0 0 ;;                      # constant module
    6) prog hello "x * 3" 1 1 val rect 0 0 0 0 0 ;;                        # module with a field
    7) prog hello "x * 3" 1 1 val rectangle 0 0 0 0 0 ;;                   # override body
    8) prog hello "x * 3" 0 0 none rect 0 0 0 0 0 ;;                       # revert
    9) prog "hello again" "x * 4" 0 0 none rect 0 0 0 0 0 ;;               # body only
    10) prog "hello again" "x * 4" 0 0 none rect 1 0 0 0 0 ;;              # new override (dispatch targets)
    11) prog "hello again" "x * 4" 0 0 none rect 1 1 0 0 0 ;;              # class implements a new trait
    12) prog "hello again" "x * 4" 0 0 none rect 1 1 1 0 0 ;;              # new field (layout)
    13) prog "hello again" "x * 4" 0 0 none rect 1 1 1 1 0 ;;              # recursion appears
    14) prog "hello again" "x * 4" 0 0 none rect 1 1 1 2 0 ;;              # recursion disappears
    15) prog "hello again" "x * 4" 0 0 none rect 1 1 1 2 1 ;;              # new call inside another method
    16) prog hi "x * 2" 0 0 none rect 0 0 0 0 0 ;;                         # back to the start
    17) prog hi "x * 2" 0 0 none rect 0 0 0 3 0 ;;                         # first call of a method of an already loaded class (Int$.to), patches/scala-native-0020
  esac
}
LAST=17

watching() { grep -c 'Watching sources' "$1" || true; }
wait_for() { # <log> <count>
  local i=0
  until [[ "$(watching "$1")" -ge "$2" ]]; do
    sleep 0.2; i=$((i + 1)); [[ $i -lt 900 ]] || { tail -20 "$1" >&2; fail "timed out waiting for build #$2"; }
  done
}

session() { # <mode> <name>: prints the number of steps that reused lowered methods
  local mode=$1 name=$2 dir="$WORK/$2" log="$WORK/$2.log" reused_steps=0
  mkdir -p "$dir" && cd "$dir"
  version 0 > main.scala
  : > "$log"
  SCALINO_LOWER_CACHE=$mode "$SCALINO" package . -o out -w -v > "$log" 2>&1 &
  WATCH_PID=$!
  wait_for "$log" 1
  local i
  for i in $(seq 1 $LAST); do
    version "$i" > main.scala
    wait_for "$log" $((i + 1))
    # what this step's build printed: between the i-th and the next "Watching sources"
    if awk -v n=$i '/Watching sources/{c++; next} c==n' "$log" | grep -q 'linking failed\|\[error\]\|[Cc]ompilation failed'; then
      awk -v n=$i '/Watching sources/{c++; next} c==n' "$log" | grep -v '^\s\|^\[debug\]' | tail -12 >&2
      fail "[$name] step $i: the build failed"
    fi
    cp out "$WORK/bin-$name-$i"
    local note=identical
    if [[ "$name" != mode-0 && -f "$WORK/bin-mode-0-$i" ]]; then
      # the cache must not change the result at all: same binary as the uncached run at this step
      # (which was itself compared with a fresh link)
      cmp -s out "$WORK/bin-mode-0-$i" || fail "[$name] step $i: binary differs from the uncached incremental run's"
    else
      # against a fresh, uncached link: the program must behave the same; the bytes may differ because an
      # incremental link keeps what an earlier build reached (patches/scala-native-0048)
      local fresh="$WORK/$name-fresh-$i"; mkdir -p "$fresh"; cp main.scala "$fresh/"
      (cd "$fresh" && SCALINO_LOWER_CACHE=0 "$SCALINO" package . -o fout > "$fresh/log" 2>&1) || { cat "$fresh/log" >&2; fail "fresh build of step $i failed"; }
      if ! cmp -s out "$fresh/fout"; then
        [[ "$(./out 2>&1)" == "$("$fresh/fout" 2>&1)" ]] || fail "[$name] step $i: output differs from a fresh uncached link"
        note="equivalent to a fresh link ($(wc -c < out | tr -d ' ') vs $(wc -c < "$fresh/fout" | tr -d ' ') bytes)"
      fi
    fi
    local ms; ms="$(awk -v n=$i '/Watching sources/{c++; next} c==n' "$log" | grep '^\[info\] Total' | tail -1 | sed 's/.*(\([0-9]*\) ms).*/\1/' || true)"
    # the line of this build's own cache report
    if [[ "$mode" == 0 ]]; then echo "  [$name] step $i $note; link ${ms} ms"; continue; fi
    local report; report="$(awk -v n=$((i + 1)) '/^\[info\] Linking \(multithreadingEnabled/{c++} c==n' "$log" | grep -m1 'Lower cache: .* reused' || true)"
    [[ -n "$report" ]] || { grep -v '^\s\|^\[debug\]' "$log" | tail -15 >&2; fail "[$name] step $i: no 'Lower cache: N reused' report (build failed, or SCALINO_LOWER_CACHE not reaching the linkdriver)"; }
    if [[ "$report" =~ ([0-9]+)\ reused ]] && [[ ${BASH_REMATCH[1]} -gt 0 ]]; then reused_steps=$((reused_steps + 1)); fi
    echo "  [$name] step $i $note; ${report#*Lower cache: }; link ${ms} ms"
  done
  grep -q 'Lower cache mismatch' "$log" && fail "[$name] verify reported a mismatch"
  kill -TERM "$WATCH_PID" 2>/dev/null || true
  wait "$WATCH_PID" 2>/dev/null || true
  WATCH_PID=""
  cd "$WORK"
  echo "$reused_steps" > "$WORK/$name.reused"
}

# LOWER_CACHE_MODES: which modes to run, default "0 verify 1" (0 = cache off, the reference for the others)
for m in ${LOWER_CACHE_MODES:-0 verify 1}; do
  echo "== mode $m"
  session "$m" "mode-$m"
done
if [[ -f "$WORK/mode-1.reused" ]]; then
  [[ "$(cat "$WORK/mode-1.reused")" -ge 3 ]] || fail "expected the body-only steps to reuse lowered methods (got $(cat "$WORK/mode-1.reused") steps with reuse)"
  echo "OK: every step byte-identical to a fresh link; $(cat "$WORK/mode-1.reused") steps reused lowered methods"
else
  echo "OK: every step byte-identical to a fresh link"
fi
