#!/usr/bin/env bash
# `scalino run --interpret`, `.sc` scripts and `scalino repl` (scalino-dotc's `--interpret`/`--repl`,
# patches/scala3-0051/0052): programs run on the tree interpreter must print exactly what the linked
# binary prints; code that needs the native runtime falls back to linking; the REPL keeps state
# across inputs.
#
# Usage: tests/interpreter/run.sh [path-to-scalino]   (default: dist/scalino)
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
SCALINO="$(cd "$(dirname "${1:-$ROOT/dist/scalino}")" && pwd)/$(basename "${1:-$ROOT/dist/scalino}")"
WORK="$(mktemp -d)"
export SCALINO_DOTC_DAEMON=0
cleanup() { rm -rf "$WORK"; }
trap cleanup EXIT
fail() { echo "FAIL: $*" >&2; exit 1; }
cd "$WORK"

# --- one program, interpreted and linked: same output, same exit code ---
mkdir prog
cat > prog/Main.scala <<'EOF'
import scala.collection.mutable

enum Color:
  case Red, Green, Blue

sealed trait Shape
case class Circle(r: Double) extends Shape
case class Rect(w: Double, h: Double) extends Shape

trait Greeter:
  def name: String
  def greet: String = s"Hello, $name!"

class Person(val name: String, age: Int) extends Greeter:
  override def toString = s"Person($name, $age)"

class MyError(msg: String) extends Exception(msg)

object Counter:
  private var n = 0
  def next(): Int = { n += 1; n }

@main def run(args: String*): Unit =
  println(Color.values.toList)
  println(Color.valueOf("Green").ordinal)
  val areas = List(Circle(1), Rect(2, 3)).map {
    case Circle(r) => math.Pi * r * r
    case Rect(w, h) => w * h
  }
  println(areas.map(a => (a * 100).round / 100.0))
  val p = Person("Ann", 30)
  println(p.greet + " " + p)
  try throw MyError("boom")
  catch case e: MyError => println("caught " + e.getMessage)
  println(try "abc".toInt catch case _: NumberFormatException => -1)
  val m = mutable.HashMap.empty[String, Int]
  for w <- "a b a c b a".split(" ") do m(w) = m.getOrElse(w, 0) + 1
  println(m.toList.sortBy(_._1))
  println(Option(3).map(_ * 2).getOrElse(0))
  println(Counter.next() + Counter.next())
  val buf = mutable.ArrayBuffer(3, 1, 2)
  buf += 5
  println(buf.sorted.mkString("[", ",", "]"))
  println(List(1, 2, 3).foldLeft(0)(_ + _) + " " + "Scala".reverse.toUpperCase)
  println(args.mkString("|"))
  lazy val lz = { println("init lz"); 42 }
  println(lz + lz)
  val arr = Array.fill(3)(0)
  arr(1) = 7
  println(arr.toList)
  println(f"${math.sqrt(2.0)}%.3f ${(1 to 10).filter(_ % 3 == 0).sum}")
  assert(areas.size == 2)
  sys.exit(4)
EOF
set +e
"$SCALINO" run prog -q -- x y > linked.out 2> linked.err; linked=$?
"$SCALINO" run prog -q --interpret -- x y > interp.out 2> interp.err; interp=$?
set -e
[[ $linked == 4 ]] || { cat linked.out linked.err >&2; fail "linked run exited $linked, expected 4"; }
[[ $interp == 4 ]] || { cat interp.out interp.err >&2; fail "interpreted run exited $interp, expected 4"; }
diff linked.out interp.out > /dev/null || { diff linked.out interp.out >&2; fail "interpreted output differs from the linked binary's"; }
echo "ok: --interpret prints what the linked binary prints"

# --- @main in a package (the main class is `app.hello`, not in the empty package) ---
mkdir -p pkg/src/app
printf 'package app\n\n@main\ndef hello =\n  println("hi from app")\n' > pkg/src/app/main.scala
out="$(cd pkg && "$SCALINO" run -q --interpret)"
[[ "$out" == "hi from app" ]] || fail "@main in a package: got '$out'"
echo "ok: @main in a package"

# --- an uncaught exception: message on stderr, exit 1 ---
mkdir boom
printf 'class Oops(m: String) extends RuntimeException(m)\nobject Boom:\n  def main(args: Array[String]): Unit =\n    println("before")\n    throw Oops("bad")\n' > boom/Boom.scala
set +e
"$SCALINO" run boom -q --interpret > boom.out 2> boom.err; code=$?
set -e
[[ $code == 1 ]] || fail "uncaught exception: exit $code, expected 1"
grep -qx 'before' boom.out || fail "uncaught exception: output before it is missing"
grep -q 'Exception in thread "main" Oops: bad' boom.err || { cat boom.err >&2; fail "uncaught exception: no message"; }
echo "ok: uncaught exception"

# --- native-only code falls back to linking ---
mkdir native
cat > native/N.scala <<'EOF'
import scala.scalanative.unsafe.*
object N:
  def main(args: Array[String]): Unit =
    Zone:
      println(fromCString(toCString("from native")))
EOF
"$SCALINO" run native --interpret > native.out 2> native.err || { cat native.err >&2; fail "fallback run failed"; }
grep -qx 'from native' native.out || fail "fallback: wrong output"
grep -q "can't interpret this program" native.err || { cat native.err >&2; fail "fallback: no reason given"; }
grep -q "Linking it instead" native.err || { cat native.err >&2; fail "fallback: did not link"; }
echo "ok: native-only code is linked instead"

# --- .sc scripts: top-level statements, args, directives, diagnostics point at the script ---
cat > hello.sc <<'EOF'
//> using scala 3
val who = if args.isEmpty then "world" else args.mkString(" ")
def shout(s: String) = s.toUpperCase
case class Msg(text: String)
println(Msg(s"hello, ${shout(who)}"))
EOF
out="$("$SCALINO" run hello.sc -q --interpret -- you there)"
[[ "$out" == "Msg(hello, YOU THERE)" ]] || fail "interpreted script: got '$out'"
out="$("$SCALINO" run hello.sc -q)"
[[ "$out" == "Msg(hello, WORLD)" ]] || fail "linked script: got '$out'"
printf 'val x: Int = "nope"\n' > broken.sc
if "$SCALINO" run broken.sc -q --interpret > broken.out 2>&1; then fail "broken script compiled"; fi
grep -q 'broken.sc:1' broken.out || { cat broken.out >&2; fail "script diagnostics don't name the script"; }
echo "ok: .sc scripts"

# --- repl: state across inputs, definitions, errors don't end the session ---
cat > repl.in <<'EOF'
val x = 20
x + 1
def twice(n: Int) = n * 2
case class P(a: Int)
P(twice(x))
val bad: String = 1
import scala.collection.mutable
val m = mutable.Map("k" -> 1)
m("k") + res0
var c = 0
c += 5
c
List(1, 2,
  3).sum
:type x.toString
println("printed")
throw new IllegalStateException("oops")
x
case class Q(a: Int, b: String)
Q(1, "s")
Array(1, 2, 3)
1 to 3
case object Done
Done
EOF
"$SCALINO" repl -q < repl.in > repl.out 2>&1 || { cat repl.out >&2; fail "repl exited non-zero"; }
expect() { grep -qxF "$1" repl.out || { cat repl.out >&2; fail "repl: missing line '$1'"; }; }
expect 'val x: Int = 20'
expect 'val res0: Int = 21'
expect 'def twice(n: Int): Int'
expect '// defined case class P'
expect 'val res1: P = P(40)'
expect 'val m: mutable.Map[String, Int] = HashMap("k" -> 1)'
expect 'val res2: Int = 22'
expect 'var c: Int = 0'
expect 'val res3: Int = 5'
expect 'val res4: Int = 6'
expect 'String'
expect 'printed'
expect 'java.lang.IllegalStateException: oops'
expect 'val res5: Int = 20'
expect 'val res6: Q = Q(a = 1, b = "s")'
expect 'val res7: Array[Int] = Array(1, 2, 3)'
expect 'val res8: Range.Inclusive = Range(1, 2, 3)'
expect '// defined object Done'
expect 'val res9: Done.type = Done'
grep -q 'Type Mismatch' repl.out || { cat repl.out >&2; fail "repl: the type error was not reported"; }
echo "ok: repl"

# --- repl with a project's sources and --repl-init-script ---
mkdir lib
printf 'package lib\nobject Greet:\n  def hi(n: String) = s"hi $n"\n' > lib/Greet.scala
out="$("$SCALINO" repl lib -q --repl-init-script 'println(lib.Greet.hi("init"))' --repl-quit-after-init < /dev/null)"
[[ "$out" == "hi init" ]] || fail "repl with sources: got '$out'"
echo "ok: repl with sources and an init script"

# --- the line editor, through a pseudo-terminal: completion, a multi-line input, history ---
if command -v python3 > /dev/null; then
  mkdir home
  cat > drive.py <<'EOF'
import os, pty, select, sys, time
pid, fd = pty.fork()
if pid == 0:
    os.execv(sys.argv[1], [sys.argv[1], "repl", "-q", "--color", "never"])
out = bytearray()
def drain(t):
    end = time.time() + t
    while time.time() < end:
        r, _, _ = select.select([fd], [], [], 0.05)
        if r:
            try: d = os.read(fd, 65536)
            except OSError: return
            if not d: return
            out.extend(d)
def wait_prompt():
    start = len(out)
    end = time.time() + 120
    while b"scala> " not in out[start:] and time.time() < end: drain(0.2)
wait_prompt()
for keys in ["List(7, 8).hea\t\r", "def twice(n: Int) =\r  n * 2\r\r", "twice(21)\r"]:
    os.write(fd, keys.encode()); wait_prompt()
os.write(fd, b"\x04"); drain(2)
sys.stdout.write(out.decode("utf-8", "replace"))
EOF
  HOME="$WORK/home" python3 drive.py "$SCALINO" > edit.out 2>&1 || true
  tr -d '\r' < edit.out | grep -q 'val res0: Int = 7' || { cat -v edit.out >&2; fail "line editor: completion of .hea did not run List(7, 8).head"; }
  tr -d '\r' < edit.out | grep -q 'def twice(n: Int): Int' || { cat -v edit.out >&2; fail "line editor: the multi-line definition was not submitted"; }
  tr -d '\r' < edit.out | grep -q 'val res1: Int = 42' || { cat -v edit.out >&2; fail "line editor: wrong result"; }
  grep -qxF 'List(7, 8).head' home/.scalino_history || { cat home/.scalino_history >&2; fail "line editor: history not saved"; }
  echo "ok: line editor"
fi

echo "PASS"
