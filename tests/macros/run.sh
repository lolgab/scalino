#!/usr/bin/env bash
# Macros evaluated by scalino-dotc's tree interpreter (vendor/scala3 .../quoted/Interpreter.scala):
# small same-project macros, one per shape that once failed.
#
# Usage: tests/macros/run.sh [path-to-scalino]   (default: dist/scalino)
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
SCALINO="$(cd "$(dirname "${1:-$ROOT/dist/scalino}")" && pwd)/$(basename "${1:-$ROOT/dist/scalino}")"
WORK="$(mktemp -d)"; trap 'rm -rf "$WORK"' EXIT
fail() { echo "FAIL: $*" >&2; exit 1; }

echo "== quote pattern with a type variable inside an intersection (skunk's sql\"...\")"
# skunk folds its encoders with `case ('{ \$a: Encoder[a] }, '{ \$acc: Encoder[t & Tuple] })`: with two
# or more interpolated encoders no case matched and the macro died with a MatchError.
mkdir -p "$WORK/andtype" && cd "$WORK/andtype"
cat > Mac.scala <<'SCALA'
package m
import scala.quoted.*

class Enc[A](val names: List[String])
object Enc:
  val int = Enc[Int](List("int"))
  val str = Enc[String](List("str"))
  val bool = Enc[Boolean](List("bool"))
  def single[A](a: Enc[A]): Enc[A *: EmptyTuple] = Enc(a.names)
  def prepend[A, B <: Tuple](a: Enc[A], b: Enc[B]): Enc[A *: B] = Enc(a.names ++ b.names)

object Mac:
  transparent inline def all(inline encs: Any*): Any = ${ impl('encs) }
  def impl(encs: Expr[Seq[Any]])(using Quotes): Expr[Any] =
    val Varargs(es) = encs: @unchecked
    val last: Expr[Any] = es.last match
      case '{ $a: Enc[a] } => '{ Enc.single($a) }
    es.init.foldRight(last) { case ('{ $a: Enc[a] }, '{ $acc: Enc[t & Tuple] }) =>
      '{ Enc.prepend($a, $acc) }
    }
SCALA
cat > Main.scala <<'SCALA'
import m.*

@main def main() =
  val one: Enc[Int *: EmptyTuple] = Mac.all(Enc.int)
  val two: Enc[Int *: String *: EmptyTuple] = Mac.all(Enc.int, Enc.str)
  val three: Enc[Int *: String *: Boolean *: EmptyTuple] = Mac.all(Enc.int, Enc.str, Enc.bool)
  println(one.names ++ two.names ++ three.names)
SCALA
out="$("$SCALINO" run -q . 2> log)" || { tail -20 log >&2; fail "intersection type variable: run"; }
[[ "$out" == "List(int, int, str, int, str, bool)" ]] || fail "intersection type variable: unexpected output: $out"

echo "OK"
