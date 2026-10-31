#!/usr/bin/env bash
# End-to-end checks for the Scala.js target, run against dist/ (build/04d + 03 + 07 first):
# a program with arguments, optimized ES module packaging, Scala.js dependencies (macro
# derivation included), scalino test on node, and the client/server example (Scala Native
# server serving the Scala.js client it embeds). Needs node, curl and network (dependencies).
#
# Usage: tests/js/run.sh [path-to-scalino]   (default: dist/scalino)
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
SCALINO="$(cd "$(dirname "${1:-$ROOT/dist/scalino}")" && pwd)/$(basename "${1:-$ROOT/dist/scalino}")"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"; for pid in "${SERVER_PID:-}" "${WATCH_PID:-}"; do [[ -z "$pid" ]] || kill "$pid" 2>/dev/null || true; done' EXIT
command -v node >/dev/null || { echo "node not found" >&2; exit 1; }
fail() { echo "FAIL: $*" >&2; exit 1; }
expect() { [[ "$1" == *"$2"* ]] || fail "expected '$2' in: $1"; }

echo "== shell completion of the Scala.js option values (bash)"
"$SCALINO" completions bash > "$WORK/scalino.bash"
complete_words() { bash -c 'source "$1"; shift; COMP_WORDS=("$@"); COMP_CWORD=$((${#COMP_WORDS[@]}-1)); COMPREPLY=(); _scalino; echo "${COMPREPLY[*]}"' _ "$WORK/scalino.bash" "$@"; }
expect "$(complete_words scalino run --js-module-kind "")" "esmodule"
expect "$(complete_words scalino run --js-mode "")" "fastLinkJS"
expect "$(complete_words scalino run --platform "")" "scala-js"
expect "$(complete_words scalino run --js-runtime "")" "deno"

echo "== program arguments (platform from a directive, plain script output)"
mkdir "$WORK/hello" && cd "$WORK/hello"
cat > Hello.scala <<'SCALA'
//> using platform scala-js
@main def hello(args: String*) = println("hello " + args.mkString(",") + " " + List(1, 2, 3).map(_ * 2))
SCALA
out="$("$SCALINO" run . -q -- a b 2>&1)"; expect "$out" "hello a,b List(2, 4, 6)"

echo "== release + esmodule package"
"$SCALINO" package . -q --js-mode release --js-module-kind esmodule -o app.mjs >/dev/null
out="$(node app.mjs)"; expect "$out" "hello  List(2, 4, 6)"
[[ ! -e app.mjs.map ]] || fail "source map written without --js-emit-source-maps"

echo "== target.platform drops files for the other platform"
cat > NativeOnly.scala <<'SCALA'
//> using target.platform scala-native
object NativeOnly { val x: scala.scalanative.unsafe.CInt = 1 }
SCALA
out="$("$SCALINO" run . -q 2>&1)"; expect "$out" "hello"

echo "== Scala.js dependencies (macro derivation + cats)"
mkdir "$WORK/deps" && cd "$WORK/deps"
cat > App.scala <<'SCALA'
//> using platform js
//> using dep com.lihaoyi::upickle::4.0.2
//> using dep org.typelevel::cats-core::2.12.0
import upickle.default.*
import cats.syntax.all.*
case class User(name: String, tags: List[String]) derives ReadWriter
object App:
  def main(args: Array[String]): Unit =
    val j = write(User("ann", List("a", "b")))
    println(j)
    println(read[User](j))
    println((1.some, 2.some).mapN(_ + _))
SCALA
out="$("$SCALINO" run . -q 2>&1)"
expect "$out" '{"name":"ann","tags":["a","b"]}'; expect "$out" "User(ann,List(a, b))"; expect "$out" "Some(3)"

echo "== scalino lock, then an offline build from the lockfile"
cd "$WORK/deps"
"$SCALINO" lock . >/dev/null 2>&1
grep -q 'platform=js' scalino.lock.json || fail "the lockfile has no Scala.js dependency set"
rm -rf .scalino-build
out="$("$SCALINO" run . -q --offline 2>&1)"; expect "$out" "Some(3)"
rm -f scalino.lock.json

echo "== a build with no dependencies needs no network (vendored base libraries)"
cd "$WORK/hello"
if [[ -f "$(dirname "$SCALINO")/jslibs.cp" ]]; then
  rm -rf .scalino-build
  out="$(SCALINO_OFFLINE=1 "$SCALINO" run . -q --offline -- x 2>&1)"; expect "$out" "hello x"
fi

echo "== run -w: restart the program after a rebuild, keep it on a failed build"
mkdir "$WORK/watch" && cd "$WORK/watch"
cat > W.scala <<'SCALA'
//> using platform js
@main def w() =
  println("version 1")
  scala.scalajs.js.timers.setInterval(1000)(())
SCALA
"$SCALINO" run -w . > watch.log 2>&1 &
WATCH_PID=$!
wait_for() { for _ in $(seq 1 150); do grep -q "$1" watch.log && return 0; sleep 0.2; done; fail "'$1' not in watch.log: $(cat watch.log)"; }
wait_for "version 1"
sed -i.bak 's/version 1/version 2/' W.scala && rm -f W.scala.bak
wait_for "version 2"; grep -q "Restarting the application" watch.log || fail "no restart message"
printf 'val broken: Int = "x"\n' >> W.scala
sleep 6; ! grep -q "version 3" watch.log || fail "restarted on a failed build"
pgrep -f '\.scalino-build/w/js/main\.js' >/dev/null || fail "the running program was stopped by a failed build"
kill "$WATCH_PID" 2>/dev/null || true; wait "$WATCH_PID" 2>/dev/null || true
cd "$WORK"

echo "== scalino test (munit on node)"
mkdir -p "$WORK/tests/test" && cd "$WORK/tests"
cat > Lib.scala <<'SCALA'
//> using platform js
//> using test.dep org.scalameta::munit::1.0.2
object Lib { def double(i: Int) = i * 2 }
SCALA
cat > test/LibSuite.scala <<'SCALA'
import scala.concurrent.Future
import scala.concurrent.ExecutionContext.Implicits.global
class LibSuite extends munit.FunSuite:
  test("double") { assertEquals(Lib.double(2), 4) }
  test("async") { Future(21).map(v => assertEquals(v * 2, 42)) }
SCALA
out="$("$SCALINO" test . 2>&1)"; expect "$out" "2 passed, 0 failed"
cat > test/Failing.scala <<'SCALA'
class Failing extends munit.FunSuite:
  test("fails") { assertEquals(1 + 1, 3) }
SCALA
if "$SCALINO" test . >/dev/null 2>&1; then fail "a failing test must give a non-zero exit code"; fi

echo "== client/server example"
cp -R "$ROOT/examples/client-server" "$WORK/cs" && cd "$WORK/cs"
"$SCALINO" package server -q -o app >/dev/null
PORT=$((20000 + RANDOM % 20000))
./app "$PORT" 3 > server.log 2>&1 &
SERVER_PID=$!
for _ in $(seq 1 50); do grep -q listening server.log 2>/dev/null && break; sleep 0.2; done
expect "$(curl -s "localhost:$PORT/")" '<script src="/client/main.js">'
curl -s "localhost:$PORT/client/main.js" -o client.js
node -e 'global.document = { getElementById: () => ({ set innerHTML(v) { console.log("DOM <- " + v) } }) }; require("./client.js")' > dom.log
expect "$(cat dom.log)" "<h1>Hello, Scala.js!</h1><p>2, 4, 6</p>"
expect "$(cat server.log)" "Hello, Scala Native!"

echo "OK: Scala.js smoke tests passed"
