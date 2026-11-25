#!/usr/bin/env bash
# End-to-end checks for `--coverage` (cli/Coverage.scala), run against dist/ (build/07 first):
# `test --coverage` measures the program and not the suites, `$COVERAGE-OFF$` regions are skipped,
# the statement map stays right after an incremental edit (statement ids are reassigned), every
# report format is written and parses, thresholds fail the command, `dependsOn` modules are measured,
# `run --coverage`, `scalino coverage` re-reports, the plain build is not instrumented,
# java.util.UUID.randomUUID (which the coverage runtime needs) links, Scala.js measures the same
# statements as Scala Native, and `run -w --coverage` reports each run. Needs network (munit) and node.
#
# Usage: tests/coverage/run.sh [path-to-scalino]   (default: dist/scalino)
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
SCALINO="$(cd "$(dirname "${1:-$ROOT/dist/scalino}")" && pwd)/$(basename "${1:-$ROOT/dist/scalino}")"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT
export SCALINO_DOTC_DAEMON=0 NO_COLOR=1
PY="$(command -v python3 || command -v python)"
case "$OSTYPE" in msys*|cygwin*) windows=1 ;; *) windows=0 ;; esac
fail() { echo "FAIL: $*" >&2; exit 1; }
expect() { [[ "$1" == *"$2"* ]] || fail "expected '$2' in: $1"; }
reject() { [[ "$1" != *"$2"* ]] || fail "did not expect '$2' in: $1"; }
row() { grep -E "^$2 " <<<"$1" | tr -s ' ' || fail "no row for $2 in: $1"; }

mkdir -p "$WORK/p/test" && cd "$WORK/p"
cat > project.scala <<'SCALA'
//> using test.dep org.scalameta::munit::1.0.4
SCALA
cat > Calc.scala <<'SCALA'
package calc

object Calc:
  def abs(x: Int): Int = if x >= 0 then x else -x

  def classify(x: Int): String =
    if x < 0 then "negative"
    else if x == 0 then "zero"
    else "positive"

  def neverCalled(): Int =
    val a = 1
    a + 2

  // $COVERAGE-OFF$
  def debugOnly(): String = "skip me"
  // $COVERAGE-ON$
SCALA
cat > Main.scala <<'SCALA'
package calc
@main def hello(): Unit = println(Calc.classify(3) + " " + java.util.UUID.randomUUID().toString.length)
SCALA
cat > test/CalcTest.scala <<'SCALA'
package calc
class CalcTest extends munit.FunSuite:
  test("abs")(assertEquals(Calc.abs(-2), 2))
  test("classify")(assertEquals(Calc.classify(0), "zero"))
SCALA

echo "== test --coverage: the program is measured, not the suites"
out="$("$SCALINO" test --coverage --coverage-report html,lcov,cobertura,scoverage,json 2>&1)"
expect "$out" "2 passed"
expect "$(row "$out" Calc.scala)" "Calc.scala 18 8 6 3 55.6% 9, 11-13"
expect "$(row "$out" Main.scala)" "Main.scala 4 4 0 0 0.0% 2"
reject "$out" "CalcTest.scala"
for f in index.html files/Calc.scala.html lcov.info cobertura.xml scoverage.xml coverage.json; do
  [[ -s "coverage/$f" ]] || fail "coverage/$f not written"
done
"$PY" - <<'PY' || fail "reports don't agree"
import json, xml.etree.ElementTree as ET
j = json.load(open("coverage/coverage.json"))
assert j["totals"]["statements"] == {"total": 22, "covered": 10, "percent": 45.45}, j["totals"]
c = ET.parse("coverage/cobertura.xml").getroot()
assert (c.get("lines-covered"), c.get("lines-valid"), c.get("branches-covered"), c.get("branches-valid")) == ("4", "9", "3", "6"), c.attrib
s = ET.parse("coverage/scoverage.xml").getroot()
assert (s.get("statement-count"), s.get("statements-invoked")) == ("22", "10"), s.attrib
lcov = open("coverage/lcov.info").read()
assert "SF:Calc.scala" in lcov and "DA:9,0" in lcov and "DA:4,1" in lcov and "BRDA:4,0,0,0" in lcov, lcov
PY

echo "== thresholds"
set +e
out="$("$SCALINO" coverage --coverage-fail-under 50 2>&1)"; code=$?
set -e
[[ $code == 1 ]] || fail "coverage --coverage-fail-under 50 exited $code"
expect "$out" "statement coverage 45.5% is below 50.0%"
"$SCALINO" coverage --coverage-fail-under 40 --coverage-fail-under-branches 50 >/dev/null || fail "thresholds met, but failed"
set +e
out="$("$SCALINO" coverage --coverage-fail-under-file 10 2>&1)"; code=$?
set -e
[[ $code == 1 ]] || fail "per-file threshold exited $code"
expect "$out" "Main.scala: statement coverage 0.0% is below 10.0%"

echo "== an edit reassigns statement ids; the map follows"
"$PY" - <<'PY'
p = "Calc.scala"; s = open(p).read()
open(p, "w").write(s.replace("object Calc:\n", "object Calc:\n  def twice(x: Int): Int = x * 2\n\n"))
p = "test/CalcTest.scala"; s = open(p).read()
open(p, "w").write(s + '  test("more")(assertEquals(Calc.neverCalled() + Calc.abs(2), 5))\n')
PY
out="$("$SCALINO" test --coverage 2>&1)"
expect "$out" "3 passed"
expect "$(row "$out" Calc.scala)" "Calc.scala 20 6 6 2 70.0% 4, 11"

echo "== the plain build is not instrumented"
rm -rf .scalino-build/coverage-data
"$SCALINO" test >/dev/null 2>&1 || fail "plain test failed"
[[ ! -e .scalino-build/coverage-data/scoverage.coverage ]] || fail "plain test wrote coverage data"
reject "$(ls .scalino-build/coverage-data 2>/dev/null || true)" "measurements"

echo "== data gone: instrumented classes are rebuilt"
out="$("$SCALINO" test --coverage 2>&1)"
expect "$(row "$out" Calc.scala)" "Calc.scala 20 6 6 2 70.0% 4, 11"

echo "== a failing test keeps its exit code and still reports"
echo '  test("broken")(assert(false))' >> test/CalcTest.scala
set +e
out="$("$SCALINO" test --coverage --coverage-fail-under 1 2>&1)"; code=$?
set -e
[[ $code == 1 ]] || fail "failing test exited $code"
expect "$out" "TOTAL"

echo "== coverage.exclude"
sed -i.bak '$d' test/CalcTest.scala
echo '//> using coverage.exclude "Main.scala"' >> project.scala
out="$("$SCALINO" test --coverage 2>&1)"
reject "$out" "Main.scala"
expect "$(row "$out" Calc.scala)" "Calc.scala 20 6 6 2 70.0% 4, 11"

echo "== Scala.js: the same statements as on Scala Native"
out="$("$SCALINO" test --js --coverage 2>&1)"
expect "$out" "3 passed"
expect "$(row "$out" Calc.scala)" "Calc.scala 20 6 6 2 70.0% 4, 11"
reject "$out" "ScalinoCoverageInvoker"

echo "== run --coverage with a dependsOn module, and directives"
mkdir -p "$WORK/m/core" "$WORK/m/app" && cd "$WORK/m/app"
cat > ../core/Core.scala <<'SCALA'
package core
object Core:
  def greet(n: String): String = if n.isEmpty then "hi" else s"hi $n"
  def unused: Int = 42
SCALA
cat > Main.scala <<'SCALA'
//> using dependsOn ../core
//> using coverage.report json
//> using coverage.dir out
object Main:
  def main(args: Array[String]): Unit = println(core.Core.greet(args.headOption.getOrElse("")))
SCALA
out="$("$SCALINO" run --coverage . -- bob 2>&1)"
expect "$out" "hi bob"
expect "$(row "$out" ../core/Core.scala)" "../core/Core.scala 7 4 2 1 42.9% 4"
expect "$(row "$out" Main.scala)" "Main.scala 4 0 0 0 100.0%"
[[ -s out/coverage.json ]] || fail "coverage.dir/report directives ignored"
"$SCALINO" run . -- x >/dev/null 2>&1 || fail "plain run after a coverage run failed"
out="$("$SCALINO" run --js --coverage . -- bob 2>&1)"
expect "$out" "hi bob"
expect "$(row "$out" ../core/Core.scala)" "../core/Core.scala 7 4 2 1 42.9% 4"
expect "$(row "$out" Main.scala)" "Main.scala 4 0 0 0 100.0%"

if [[ $windows == 0 ]]; then
  echo "== run -w --coverage: a report when the program is stopped for a rebuild, and on SIGTERM"
  mkdir -p "$WORK/w" && cd "$WORK/w"
  cat > Calc.scala <<'SCALA'
object Calc:
  def classify(x: Int): String = if x < 0 then "negative" else "positive"
SCALA
  cat > Main.scala <<'SCALA'
@main def serve(): Unit =
  println("up " + Calc.classify(1))
  while true do Thread.sleep(100)
SCALA
  "$SCALINO" run -w --coverage . > out.log 2>&1 < /dev/null &
  pid=$!
  waitFor() { for _ in $(seq 1 300); do [[ "$(grep -c "$1" out.log)" -ge $2 ]] && return 0; sleep 1; done; fail "timed out waiting for '$1' x$2: $(cat out.log)"; }
  waitFor "up positive" 1
  echo '  def neg = classify(-1)' >> Calc.scala
  waitFor "up positive" 2
  sleep 1
  kill -TERM $pid; wait $pid || true
  out="$(tr -s ' ' < out.log)"
  [[ "$(grep -c '^TOTAL' out.log)" == 2 ]] || fail "expected two reports in: $out"
  expect "$out" "Calc.scala 6 2 2 1 66.7%"
  expect "$out" "Calc.scala 8 4 2 1 50.0% 3"
fi

echo "== unsupported combinations"
set +e
out="$("$SCALINO" compile --coverage . 2>&1)"; code=$?
set -e
[[ $code != 0 ]] || fail "compile --coverage succeeded"
expect "$out" "only \`run\` and \`test\` measure coverage"

echo "OK"
