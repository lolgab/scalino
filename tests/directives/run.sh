#!/usr/bin/env bash
# `//> using` directives that decide what a build is made of: `exclude` (which sources) and
# `nativePkgConfig` (which C libraries, also for `scalino test`). Needs cc, ar and pkg-config; munit
# comes from Maven Central.
#
# Usage: tests/directives/run.sh [path-to-scalino]   (default: dist/scalino)
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
SCALINO="$(cd "$(dirname "${1:-$ROOT/dist/scalino}")" && pwd)/$(basename "${1:-$ROOT/dist/scalino}")"
WORK="$(mktemp -d)"; trap 'rm -rf "$WORK"' EXIT
export SCALINO_DOTC_DAEMON=0
fail() { echo "FAIL: $*" >&2; exit 1; }
cd "$WORK"

echo "== exclude: a directory, a glob, relative to the declaring file"
mkdir -p proj/data proj/src/fixtures
cat > proj/project.scala <<'SCALA'
//> using exclude data
//> using exclude "**/fixtures"
SCALA
printf '@main def main() = println("main")\n' > proj/Main.scala
# a second @main: "several main classes" if it were compiled with the project
printf '@main def buildData() = println("data")\n' > proj/data/build-data.scala
printf 'this does not parse\n' > proj/src/fixtures/Broken.scala
# from outside the project: the patterns are relative to project.scala, not to the cwd
out="$("$SCALINO" run -q proj 2> log)" || { tail -20 log >&2; fail "exclude: run"; }
[[ "$out" == "main" ]] || fail "exclude: unexpected output: $out"
echo "== exclude: a file named on the command line is still built"
out="$("$SCALINO" run -q proj/data/build-data.scala 2> log)" || { tail -20 log >&2; fail "exclude: run the excluded file"; }
[[ "$out" == "data" ]] || fail "exclude: unexpected output: $out"

echo "== nativePkgConfig reaches the test binary (and the test-framework probe)"
# The probe links the project's classes too (munit suites are reflectively instantiable), so a suite
# calling into the library failed there with an undefined symbol.
mkdir -p clib pc pkg/test
printf 'int scalino_answer(void) { return 42; }\n' > clib/answer.c
cc -c clib/answer.c -o clib/answer.o
ar rcs clib/libanswer.a clib/answer.o
cat > pc/answer.pc <<PC
Name: answer
Description: scalino test library
Version: 1.0
Libs: -L$WORK/clib -lanswer
Cflags:
PC
export PKG_CONFIG_PATH="$WORK/pc"
cd pkg
cat > project.scala <<'SCALA'
//> using nativePkgConfig answer
//> using test.dep org.scalameta::munit::1.2.1
SCALA
cat > Answer.scala <<'SCALA'
import scala.scalanative.unsafe.*

@extern object answerlib:
  def scalino_answer(): CInt = extern

@main def main() = println(answerlib.scalino_answer())
SCALA
cat > test/AnswerSuite.test.scala <<'SCALA'
class AnswerSuite extends munit.FunSuite:
  test("answer"):
    assertEquals(answerlib.scalino_answer(), 42)
SCALA
out="$("$SCALINO" run -q . 2> log)" || { tail -20 log >&2; fail "nativePkgConfig: run"; }
[[ "$out" == "42" ]] || fail "nativePkgConfig: unexpected output: $out"
"$SCALINO" test . > log 2>&1 || { tail -20 log >&2; fail "nativePkgConfig: test"; }
grep -q "1 passed, 0 failed" log || { tail -20 log >&2; fail "nativePkgConfig: suite did not pass"; }

echo "OK"
