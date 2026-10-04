// Regression for patch 0051 (Interflow phi-merge scalar join): merging two
// distinct virtual objects through an `if` used to copy them, so mutations
// through the merged value were lost whenever the originals were still
// referenced elsewhere. Run in every mode, e.g.
//   SCALANATIVE_INLINE_ALLOC=1 scalino run examples/interflow-phi-alias
// Expected output:
//   direct sel: a=2 b=1
//   inline sel: c=1 d=0
//   via val: e=2 f=0
//   partition: (List(a, c),List(b))
final class Cnt:
  var n = 0
  def inc(): Unit = n += 1

object Main:
  def sel(c: Boolean, a: Cnt, b: Cnt): Unit = (if (c) a else b).inc()

  def main(args: Array[String]): Unit =
    val t = args.length == 0
    val a, b = new Cnt
    sel(t, a, b); sel(!t, a, b); sel(t, a, b)
    println(s"direct sel: a=${a.n} b=${b.n}")
    val c, d = new Cnt
    (if (t) c else d).inc()
    println(s"inline sel: c=${c.n} d=${d.n}")
    val e, f = new Cnt
    val x = if (t) e else f
    x.inc(); x.inc()
    println(s"via val: e=${e.n} f=${f.n}")
    println(s"partition: ${List("a", "b", "c").partition(_ != "b")}")
