// Regression test for stack allocation of non-escaping objects (escape
// analysis, patch 0061): every case here either keeps an object local or lets
// it escape in a way a wrong analysis would miss. A dangling stack object
// shows up as garbage / a crash once `churn` has overwritten the dead frames.
// The output must be identical with SCALANATIVE_STACK_OBJECTS=0.
import scala.collection.mutable

object Main:
  // overwrite dead stack frames
  def churn(n: Int): Long =
    if n == 0 then 0L
    else
      val a = new Array[Long](24)
      var i = 0
      while i < a.length do { a(i) = n.toLong * 31 + i; i += 1 }
      churn(n - 1) + a(n % a.length)

  final class Point(var x: Int, var y: Int):
    override def toString = s"Point($x,$y)"

  final class Holder:
    var f: Int => Int = null
    var it: Iterator[Int] = null
    var p: Point = null
    def set(g: Int => Int): Unit = f = g
    def setIt(i: Iterator[Int]): Unit = it = i
    def setP(q: Point): Unit = p = q

  class CapturingException(val f: Int => Int, val p: Point) extends Exception("captured")

  // higher-order helpers (callee only calls the closure, or stores it)
  @noinline def applyTwice(f: Int => Int, x: Int): Int = f(f(x))
  @noinline def applyAll(fs: List[Int => Int], x: Int): Int = fs.foldLeft(x)((a, f) => f(a))
  @noinline def keep(h: Holder, f: Int => Int): Int = { h.set(f); f(1) }
  @noinline def giveBack(f: Int => Int): Int => Int = f
  @noinline def throwing(f: Int => Int, p: Point): Int =
    if p.x >= 0 then throw new CapturingException(f, p) else f(p.x)
  @noinline def recur(f: Int => Int, n: Int): Int = if n == 0 then f(0) else recur(f, n - 1) + f(n)
  @noinline def pick(i: Int)(a: Int => Int, b: Int => Int): Int => Int = if i % 2 == 0 then a else b

  // a by-name wrapper like util.ScopedVar.scoped: the closure handed to it
  // calls another closure, which leaks what it captured
  object Scoped:
    @noinline def scoped[T](body: => T): T = body
  @noinline def runIn(f: Int => Unit): Unit = Scoped.scoped { f(1) }
  @noinline def twice(f: => Int): Int = f + f
  var stash: Point = null
  var stashedFn: Int => Int = null

  def main(args: Array[String]): Unit =
    var sum = 0L

    // 1. closure only called
    var k = 3
    sum += applyTwice(x => x * k + 1, 5)

    // 2. closure mutating captured state
    var counter = 0
    val xs = List(1, 2, 3, 4)
    xs.foreach(x => counter += x * k)
    sum += counter

    // 3. closure stored by callee, used after the frame is gone
    val h = new Holder
    def register(): Int =
      val base = new Point(7, 9)
      keep(h, x => x + base.x * base.y)
    sum += register()
    churn(300)
    sum += h.f(10)

    // 4. closure returned
    def make(n: Int): Int => Int =
      val p = new Point(n, n + 1)
      giveBack(x => x * p.x + p.y)
    val g = make(5)
    churn(300)
    sum += g(4)

    // 5. closures in a buffer, called later
    val fs = mutable.ListBuffer.empty[Int => Int]
    def fill(): Unit =
      var i = 0
      while i < 5 do
        val c = i
        fs += (x => x + c)
        i += 1
    fill()
    churn(300)
    sum += fs.map(f => f(100)).sum

    // 6. closure captured by a thrown exception, read after unwinding
    def thrower(): Unit =
      val p = new Point(3, 4)
      throwing(x => x + p.y, p)
    try thrower()
    catch case e: CapturingException => { churn(300); sum += e.f(1) + e.p.x }

    // 7. MatchError keeps the scrutinee; its message is built lazily
    def failMatch(i: Int): String =
      val p = new Point(i, i * 2)
      try (p: Any) match { case s: String => s }
      catch case e: MatchError => { churn(300); e.getMessage }
    sum += failMatch(21).length

    // 8. iterators: local, stored, passed on
    def localIter(n: Int): Int =
      val it = List.tabulate(n)(identity).iterator
      var s = 0
      while it.hasNext do s += it.next()
      s
    sum += localIter(10)
    val h2 = new Holder
    def storeIter(): Unit = h2.setIt(Iterator.from(List(5, 6, 7)))
    storeIter()
    churn(300)
    sum += h2.it.sum

    // 9. a fresh object stored by a callee
    def storeP(): Unit = h2.setP(new Point(11, 12))
    storeP()
    churn(300)
    sum += h2.p.x + h2.p.y

    // 10. recursion passing a closure down
    sum += recur(x => x + k, 6)

    // 11. different closure classes reaching the same callee
    sum += pick(0)(x => x + 1, x => x - 1)(10) + pick(1)(x => x + 1, x => x - 1)(10)

    // 12. closures run on another thread
    val results = new Array[Int](2)
    val threads = (0 until 2).map { i =>
      val t = new Thread(() => { results(i) = applyTwice(x => x + i, 10); () })
      t.start(); t
    }
    threads.foreach(_.join())
    sum += results.sum

    // 13. local monitors
    val lock = new Object
    var shared = 0
    lock.synchronized { shared += 5 }
    sum += shared

    // 14. Option / tuple results
    def find(i: Int): Option[(Int, Int)] = if i > 2 then Some((i, i * i)) else None
    sum += (0 to 5).flatMap(find).map(_._2).sum

    // 15. lazy values and by-name arguments
    def byName(a: => Int, b: => Int): Int = a + b
    lazy val lz = { k += 1; k * 2 }
    sum += byName(lz, lz)

    // 16. a closure applied through a Function2 / PartialFunction
    val pf: PartialFunction[Int, Int] = { case x if x % 2 == 0 => x * 10 }
    sum += List(1, 2, 3, 4).collect(pf).sum
    sum += List(1, 2, 3).foldLeft(0)((a, b) => a * 10 + b)

    // 17. closure -> by-name closure -> closure that stores what it captured
    def stashing(): Unit =
      val p = new Point(41, 42)
      runIn(_ => stash = p)
    stashing()
    churn(300)
    sum += stash.x + stash.y

    // 18. nested by-name closures stashing a closure that captures a local
    def stashing2(): Int =
      val p = new Point(3, 5)
      twice { stashedFn = (y: Int) => y + p.x * p.y; stashedFn(1) }
    sum += stashing2()
    churn(300)
    sum += stashedFn(10)

    // 19. a lazy iterator pipeline outliving the frame that built it
    def lazyIt(): Iterator[Int] =
      val p = new Point(10, 20)
      Iterator(1, 2, 3).map(_ + p.x)
    val li = lazyIt()
    churn(300)
    sum += li.sum

    // 20. closures inside Option / Either / tuples handed out of a frame
    def boxed(): (Option[Int => Int], Either[String, Int => Int]) =
      val p = new Point(5, 6)
      (Some(x => x + p.x), Right(x => x * p.y))
    val (o, e) = boxed()
    churn(300)
    sum += o.get(1) + e.toOption.get(2)

    println(s"sum=$sum")
