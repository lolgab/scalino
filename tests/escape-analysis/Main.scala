// Regression test for the stack allocation transform (escape analysis, patch
// 0005): an object that a callee stores into the memory of a caller parameter
// must escape. The analysis models "callee stores parameter j into parameter i"
// as union + collapse of the two argument nodes; a collapsed node that is caller
// memory has no content node to carry the Ext flag, so the fresh object looked
// local and was stack allocated, then read after its frame was gone.
//
// Case 1 is the minimal shape (non-inlined store into a parameter, one real
// frame per call). Case 2 is the shape that broke the wes NES emulator: a
// `val x = new C` in a sealed trait, run in the trait's $init$ of anonymous
// subclasses and stored through the synthetic setter.
final class Env(var a: Int, var b: Int, var c: Int):
  def sum: Long = a.toLong * 1000000 + b.toLong * 1000 + c

final class Holder:
  var env: Env = null
  @noinline def put(e: Env): Unit = env = e

sealed trait Regs:
  val env = new Env(1, 2, 3)
  def offset: Int

object State:
  val r1 = new Regs { val offset: Int = -1 }
  val r2 = new Regs { val offset: Int = 7 }

object Main:
  var global: Holder = null

  // overwrite dead stack frames
  def churn(n: Int): Long =
    if n == 0 then 0L
    else
      val a = new Array[Long](24)
      var i = 0
      while i < a.length do { a(i) = n.toLong * 31 + i; i += 1 }
      churn(n - 1) + a(n % a.length)

  @noinline def store(h: Holder, e: Env): Unit = h.put(e)

  // not tail recursive: the Env is allocated in a frame of its own
  def init(h: Holder, k: Int, depth: Int): Int =
    if depth > 0 then init(h, k, depth - 1) + 1
    else
      val e = new Env(k, k + 1, k + 2)
      store(h, e)
      0

  def main(args: Array[String]): Unit =
    val k = args.length + 1
    global = new Holder
    val d = init(global, k, k)
    val c1 = churn(100 + k)
    println(s"direct c=${c1 != 0} d=$d sum=${global.env.sum}")

    val s1 = State.r1
    val s2 = State.r2
    val c2 = churn(100 + k)
    s1.env.a += 10
    s2.env.b += 20
    println(s"trait c=${c2 != 0} r1=${s1.env.sum} r2=${s2.env.sum}")
