#!/usr/bin/env bash
# End-to-end checks for multi-module builds (`//> using dependsOn`, cli/Modules.scala), run against
# dist/ (build/07 first): a diamond module graph with a dependency passed down transitively, per-module
# incremental rebuilds (an unrelated upstream edit stays in its module, an API change reaches its
# dependents, a reverted breaking change recovers), test/package/compile, setup-ide's LSP projects,
# the graph errors, and one platform-less module shared by a Scala Native server and its Scala.js
# client. Needs network (dependencies) and node (the client).
#
# Usage: tests/modules/run.sh [path-to-scalino]   (default: dist/scalino)
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
SCALINO="$(cd "$(dirname "${1:-$ROOT/dist/scalino}")" && pwd)/$(basename "${1:-$ROOT/dist/scalino}")"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT
export SCALINO_DOTC_DAEMON=0
fail() { echo "FAIL: $*" >&2; exit 1; }
expect() { [[ "$1" == *"$2"* ]] || fail "expected '$2' in: $1"; }
reject() { [[ "$1" != *"$2"* ]] || fail "did not expect '$2' in: $1"; }

mkdir -p "$WORK/mm/app/test" "$WORK/mm/core" "$WORK/mm/util" && cd "$WORK/mm"
cat > util/Strings.scala <<'SCALA'
//> using dep com.lihaoyi::upickle::4.0.2
package util
object Strings:
  def shout(s: String): String = s.toUpperCase + "!"
  def json(xs: List[Int]): String = upickle.default.write(xs)
SCALA
cat > util/Other.scala <<'SCALA'
package util
object Other:
  val x = 1
SCALA
cat > util/Fmt.scala <<'SCALA'
package util
def fmt(i: Int): String = s"<$i>"
SCALA
cat > core/Core.scala <<'SCALA'
//> using dependsOn ../util
package core
case class Greeting(name: String):
  def render: String = util.Strings.shout(s"hello $name")
SCALA
cat > app/Main.scala <<'SCALA'
//> using dependsOn ../core
//> using dependsOn ../util
@main def main() =
  println(core.Greeting("world").render)
  println(util.Strings.json(List(1, 2, 3)))
  // upickle is util's dependency: modules pass theirs down
  println(upickle.default.write(Map("a" -> 1)))
  println(util.fmt(7))
SCALA
cat > app/test/MainTest.scala <<'SCALA'
//> using test.dep org.scalameta::munit::1.0.4
class MainTest extends munit.FunSuite:
  test("render")(assertEquals(core.Greeting("x").render, "HELLO X!"))
SCALA

echo "== diamond graph, modules compiled in dependency order"
out="$("$SCALINO" run app 2>&1)"
expect "$out" "HELLO WORLD!"; expect "$out" "[1,2,3]"; expect "$out" '{"a":1}'
util_at="$(grep -n "Compiling module util" <<<"$out" | cut -d: -f1)"
core_at="$(grep -n "Compiling module core" <<<"$out" | cut -d: -f1)"
[[ -n "$util_at" && -n "$core_at" && "$util_at" -lt "$core_at" ]] || fail "util must compile before core: $out"
[[ "$(grep -c "Compiling module util" <<<"$out")" == 1 ]] || fail "util compiled twice (diamond): $out"

echo "== no change: nothing recompiles"
out="$("$SCALINO" run app 2>&1)"; reject "$out" "Compiling"; expect "$out" "HELLO WORLD!"

echo "== an upstream edit nobody uses stays in its module"
printf 'package util\nobject Other:\n  val x = 2\n' > util/Other.scala
out="$("$SCALINO" run app 2>&1)"
expect "$out" "Compiling module util"; reject "$out" "Compiling module core"; reject "$out" "Compiling project"

echo "== an upstream change reaches its dependents"
sed -i.bak 's/"!"/"?"/' util/Strings.scala
out="$("$SCALINO" run app 2>&1)"
expect "$out" "Compiling module core"; expect "$out" "Compiling project"; expect "$out" "HELLO WORLD?"

echo "== a breaking upstream change fails in the dependent; reverting it recovers"
sed -i.bak 's/def shout(/def shout2(/' util/Strings.scala
out="$("$SCALINO" run app 2>&1)" && fail "expected a compile error: $out"
expect "$out" "value shout is not a member of object util.Strings"; expect "$out" "Error compiling module core"
sed -i.bak 's/def shout2(/def shout(/' util/Strings.scala
out="$("$SCALINO" run app 2>&1)"; expect "$out" "HELLO WORLD?"
sed -i.bak 's/"?"/"!"/' util/Strings.scala

echo "== a changed top-level def reaches its callers (a compile error, not a link error)"
sed -i.bak 's/def fmt(/def format(/' util/Fmt.scala
out="$("$SCALINO" run app 2>&1)" && fail "expected a compile error: $out"
expect "$out" "value fmt is not a member of util"
sed -i.bak 's/def format(/def fmt(/' util/Fmt.scala
out="$("$SCALINO" run app 2>&1)"; expect "$out" "<7>"

echo "== test: the root's tests see its modules"
out="$("$SCALINO" test app 2>&1)"; expect "$out" "1 passed"

echo "== package and compile"
"$SCALINO" package app -q -o "$WORK/app-bin" >/dev/null 2>&1
expect "$("$WORK/app-bin")" "HELLO WORLD!"
"$SCALINO" clean >/dev/null 2>&1 || rm -rf .scalino-build
out="$("$SCALINO" compile app 2>&1)"; expect "$out" "Compiled module util"; expect "$out" "Compiled module core"; expect "$out" "Compiled project"

echo "== setup-ide: one LSP project per module"
"$SCALINO" setup-ide app >/dev/null 2>&1
lsp="$(tr -d ' \n' < .scalino-build/scalino-lsp.json)"
expect "$lsp" '"id":"main","platform":"native","dependsOn":["core","util"]'
expect "$lsp" '"id":"test","platform":"native","dependsOn":["main","core","util"]'
expect "$lsp" '"id":"core","platform":"native","dependsOn":["util"]'
expect "$lsp" '"id":"util","platform":"native","dependsOn":[]'

echo "== setup-ide: a module's id is its path, whatever the other modules are called"
mkdir -p "$WORK/ids/app" "$WORK/ids/a/core" "$WORK/ids/b/core" "$WORK/ids/main" && cd "$WORK/ids"
printf 'package a\nobject A\n' > a/core/A.scala
printf 'package b\nobject B\n' > b/core/B.scala
printf 'package m\nobject M\n' > main/M.scala
printf '//> using dependsOn ../a/core ../b/core ../main\n@main def m = println(a.A.toString + b.B + m.M)\n' > app/Main.scala
"$SCALINO" setup-ide app >/dev/null 2>&1
ids="$(grep '"id"' .scalino-build/scalino-lsp.json | tr -d ' ",' | sed 's/^id://' | sort | tr '\n' ' ')"
[[ "$ids" == "./main a/core b/core main " ]] || fail "unexpected LSP ids: $ids"
lsp="$(tr -d ' \n' < .scalino-build/scalino-lsp.json)"
expect "$lsp" '"id":"main","platform":"native","dependsOn":["a/core","b/core","./main"]'

echo "== graph errors"
mkdir -p "$WORK/err/a" "$WORK/err/b" "$WORK/err/js" "$WORK/err/app" && cd "$WORK/err"
printf '//> using dependsOn ../b\nobject A\n' > a/A.scala
printf '//> using dependsOn ../a\nobject B\n' > b/B.scala
printf '//> using platform js\nobject J\n' > js/J.scala
printf '//> using dependsOn ../a\n@main def m = ()\n' > app/M.scala
out="$("$SCALINO" compile app 2>&1)" && fail "cycle accepted"; expect "$out" "dependsOn cycle: a -> b -> a"
printf '//> using dependsOn ../js\n@main def m = ()\n' > app/M.scala
out="$("$SCALINO" compile app 2>&1)" && fail "platform mismatch accepted"; expect "$out" "module js is built for scala-js, but the root module"
printf '//> using dependsOn ../nope\n@main def m = ()\n' > app/M.scala
out="$("$SCALINO" compile app 2>&1)" && fail "missing module accepted"; expect "$out" "'nope' (from the root module) is not a directory"

echo "== one module for a Scala Native server and its Scala.js client"
if command -v node >/dev/null; then
  mkdir -p "$WORK/cs/model" "$WORK/cs/server" "$WORK/cs/client" && cd "$WORK/cs"
  cat > model/Msg.scala <<'SCALA'
//> using dep com.lihaoyi::upickle::4.0.2
package model
case class Msg(text: String) derives upickle.default.ReadWriter
object Msg:
  def encode(m: Msg): String = upickle.default.write(m)
SCALA
  cat > server/Server.scala <<'SCALA'
//> using jsClient ../client
//> using jsClientOut ../out
//> using dependsOn ../model
@main def server() = println("server " + model.Msg.encode(model.Msg("native")))
SCALA
  cat > client/Client.scala <<'SCALA'
//> using platform js
//> using dependsOn ../model
@main def client() = println("client " + model.Msg.encode(model.Msg("js")))
SCALA
  out="$("$SCALINO" run server 2>&1)"
  expect "$out" "Compiled module model (Scala 3"; expect "$out" "Scala Native"; expect "$out" 'server {"text":"native"}'
  expect "$(grep "Compiled module model" <<<"$out" | tail -1)" "Scala.js"
  expect "$(node out/main.js)" 'client {"text":"js"}'
  "$SCALINO" setup-ide server >/dev/null 2>&1
  lsp="$(tr -d ' \n' < .scalino-build/scalino-lsp.json)"
  expect "$lsp" '"id":"client","platform":"js","dependsOn":["js:model"]'
  expect "$lsp" '"id":"js:model","platform":"js"'
  expect "$lsp" '"id":"model","platform":"native"'
else
  echo "   (skipped: node not found)"
fi

echo "OK"
