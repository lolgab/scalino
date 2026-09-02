// Adapted from vendor/scala3/tests/run-macros/quote-simple-macro (originally
// split across quoted_1.scala/quoted_2.scala for separate compilation; split
// into two same-run files here instead, since Phase 1 of the own-implementation
// interpreter only supports same-run macro bodies -- see docs/findings.md).
import scala.quoted.*

object Macros {
  inline def foo(inline i: Int, dummy: Int, j: Int): Int = ${ bar('i, 'j) }
  def bar(x: Expr[Int], y: Expr[Int]) (using Quotes): Expr[Int] = '{ ${Expr(x.valueOrAbort)} + $y }
}
