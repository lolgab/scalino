#!/usr/bin/env bash
# `//> using toolkit`: the Scala Toolkit (os-lib, upickle, sttp; munit through toolkit-test) and the
# Typelevel Toolkit (cats-effect, fs2, circe, decline; weaver through toolkit-test). Nothing here
# goes to the network at run time, only to Maven Central for the toolkits themselves.
#
# Usage: tests/toolkit/run.sh [path-to-scalino]   (default: dist/scalino)
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
SCALINO="$(cd "$(dirname "${1:-$ROOT/dist/scalino}")" && pwd)/$(basename "${1:-$ROOT/dist/scalino}")"
WORK="$(mktemp -d)"; trap 'rm -rf "$WORK"' EXIT
fail() { echo "FAIL: $*" >&2; exit 1; }

echo "== toolkit default: run"
mkdir -p "$WORK/scala/test" && cd "$WORK/scala"
cat > Main.scala <<'SCALA'
//> using toolkit default

import sttp.client4.quick.*

case class Pet(name: String, kind: String) derives upickle.default.ReadWriter

def double(n: Int) = n * 2

@main def main() =
  val file = os.pwd / "pets.json"
  os.write.over(file, upickle.default.write(List(Pet("Rex", "dog"))))
  println(upickle.default.read[List[Pet]](os.read(file)))
  val json = ujson.read("""{"age": 13}""")
  json("age") = json("age").num + 1
  println(ujson.write(json))
  println(quickRequest.get(uri"https://example.com/pets?kind=dog").uri)
SCALA
cat > test/DoubleSuite.test.scala <<'SCALA'
class DoubleSuite extends munit.FunSuite:
  test("double"):
    assertEquals(double(21), 42)
SCALA
out="$("$SCALINO" run -q . 2> log)" || { tail -20 log >&2; fail "toolkit default: run"; }
expected=$'List(Pet(Rex,dog))\n{"age":14}\nhttps://example.com/pets?kind=dog'
[[ "$out" == "$expected" ]] || fail "toolkit default: unexpected output: $out"
grep -q "Unsupported directive" log && fail "toolkit default: directive reported as unsupported"

echo "== toolkit default: test (munit from toolkit-test)"
"$SCALINO" test . > log 2>&1 || { tail -20 log >&2; fail "toolkit default: test"; }
grep -q "1 passed, 0 failed" log || { tail -20 log >&2; fail "toolkit default: munit suite did not pass"; }

echo "== test.toolkit: the toolkit only in test scope"
mkdir -p "$WORK/testonly/test" && cd "$WORK/testonly"
printf '@main def main() = println("main")\n' > Main.scala
cat > test/OsSuite.test.scala <<'SCALA'
//> using test.toolkit default
class OsSuite extends munit.FunSuite:
  test("os-lib in tests"):
    assert(os.exists(os.pwd))
  test("munit's Location is the call site, not munit's MacroCompat.scala (#13)"):
    val loc = summon[munit.Location]
    assertEquals(loc.line, 6)
    assert(loc.path.endsWith("OsSuite.test.scala"), loc.path)
SCALA
"$SCALINO" test . > log 2>&1 || { tail -20 log >&2; fail "test.toolkit: test"; }
grep -q "2 passed, 0 failed" log || { tail -20 log >&2; fail "test.toolkit: suite did not pass"; }

echo "== toolkit typelevel:default: run"
mkdir -p "$WORK/typelevel" && cd "$WORK/typelevel"
cat > Main.scala <<'SCALA'
//> using toolkit typelevel:default

import cats.effect.*
import com.monovore.decline.*
import com.monovore.decline.effect.*
import fs2.io.file.{Files, Path}
import io.circe.*
import io.circe.syntax.*

case class Pet(name: String, age: Int)
given Codec[Pet] = Codec.forProduct2("name", "age")(Pet.apply)(p => (p.name, p.age))

object Main extends CommandIOApp("pets", "Typelevel Toolkit smoke test"):
  def main: Opts[IO[ExitCode]] = Opts.option[String]("name", "a pet").withDefault("Rex").map { name =>
    val file = Path("pets.json")
    for
      _ <- fs2.Stream.emit(List(Pet(name, 3)).asJson.noSpaces)
             .through(fs2.text.utf8.encode).through(Files[IO].writeAll(file)).compile.drain
      text <- Files[IO].readUtf8(file).compile.string
      pets <- IO.fromEither(io.circe.jawn.decode[List[Pet]](text))
      _ <- IO.println(pets)
    yield ExitCode.Success
  }
SCALA
out="$("$SCALINO" run -q . -- --name Fido 2> log)" || { tail -20 log >&2; fail "toolkit typelevel:default: run"; }
[[ "$out" == "List(Pet(Fido,3))" ]] || fail "toolkit typelevel:default: unexpected output: $out"

echo "OK"
