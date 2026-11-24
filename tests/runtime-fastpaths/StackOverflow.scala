import java.util.concurrent.atomic.AtomicInteger

object Rec:
  def depth(n: Int): Int = if n == 0 then 0 else 1 + depth(n - 1)
  // Not `1 + forever(n + 1)`: since the yieldpoint moved to method entry
  // (scala-native#5283) LLVM turns that into an accumulator loop in release
  // modes, which spins forever instead of overflowing.
  def forever(n: Long): Long = forever(n + 1) * 3 - n
  def withLock(n: Long): Long = this.synchronized { 1 + withLock(n + 1) }
  def fib(n: Int): Int = if n < 2 then n else fib(n - 1) + fib(n - 2)

def catchSO(f: => Any): String =
  try { f; "no error" } catch { case _: StackOverflowError => "SOE" }

@main def run(): Unit =
  // 1. repeated overflows on the main thread, guard page re-armed each time
  val main = (1 to 5).map(_ => catchSO(Rec.forever(0)))
  println(s"main: ${main.mkString(",")}")
  assert(main.forall(_ == "SOE"))

  // 2. overflow inside synchronized: monitor must be released
  println(s"locked: ${catchSO(Rec.withLock(0))}")
  val t = new Thread((() => Rec.synchronized { () }): Runnable); t.start(); t.join(1000)
  assert(!t.isAlive, "monitor still held after SOE")

  // 3. many threads overflowing at the same time, others doing normal recursion
  val soe = new AtomicInteger(0); val ok = new AtomicInteger(0)
  val ts: Seq[Thread] = (1 to 16).map { i =>
    new Thread((() => {
      if i % 2 == 0 then { if catchSO(Rec.forever(0)) == "SOE" then soe.incrementAndGet() }
      else { if Rec.fib(25) == 75025 && Rec.depth(1000) == 1000 then ok.incrementAndGet() }
      ()
    }): Runnable)
  }
  ts.foreach(_.start()); ts.foreach(t => { t.join(); () })
  println(s"threads: soe=${soe.get} ok=${ok.get}")
  assert(soe.get == 8 && ok.get == 8)

  // 4. after all that, recursion is fast again (no thread left pending)
  val t0 = System.nanoTime()
  var s = 0; var k = 0
  while k < 20 do { s += Rec.fib(30); k += 1 }
  println(s"fib bench: ${(System.nanoTime() - t0) / 1000000}ms ($s)")
  println("ALL OK")
