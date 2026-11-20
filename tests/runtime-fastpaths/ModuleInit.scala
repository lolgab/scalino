import java.util.concurrent.{CountDownLatch, atomic}

object Slow:
  val inits = new atomic.AtomicInteger(0)
  Thread.sleep(50)
  inits.incrementAndGet()
  val value = 42

object Failing:
  val attempts = new atomic.AtomicInteger(0)
  if Ping.flag then throw new IllegalStateException("boom")
  val v = 1

object Ping:
  @volatile var flag = true

// cyclic init on one thread: A's ctor reads B, B's ctor reads A (sees A under construction)
object CycA:
  val b = CycB.x
  val y = 7
object CycB:
  val seenA = CycA.y // 0: A not finished yet
  val x = 3

object M1:
  var c = 0L
  @noinline def inc(): Unit = c += 1
object M2:
  var c = 0L
  @noinline def inc(): Unit = c += 1

object Hot:
  var counter = 0L
  def bump(): Unit = counter += 1

@main def run(): Unit =
  // 1. many threads race on first access of a slow module
  val n = 32
  val start = new CountDownLatch(1)
  val results = new java.util.concurrent.ConcurrentLinkedQueue[Int]()
  val ts: Seq[Thread] = (1 to n).map(_ => new Thread((() => { start.await(); results.add(Slow.value); () }): Runnable))
  ts.foreach(_.start()); start.countDown(); ts.foreach(t => { t.join(); () })
  assert(results.size == n && results.stream().allMatch(_ == 42), s"race results $results")
  assert(Slow.inits.get == 1, s"Slow initialized ${Slow.inits.get} times")
  println(s"race ok: $n threads, inits=${Slow.inits.get}")

  // 2. failing constructor: ExceptionInInitializerError, then NoClassDefFoundError-like on retry
  def tryFailing(): String =
    try { Failing.v.toString } catch { case e: Throwable => e.getClass.getSimpleName }
  val first = tryFailing()
  val second = tryFailing()
  val third = (1 to 4).map(_ => { var r = ""; val t = new Thread((() => r = tryFailing()): Runnable); t.start(); t.join(); r })
  println(s"failing: first=$first second=$second threads=${third.distinct.mkString(",")}")
  assert(first != "1" && second != "1" && third.forall(_ != "1"))

  // 3. cyclic init
  println(s"cyclic: CycA.b=${CycA.b} CycB.seenA=${CycB.seenA} CycA.y=${CycA.y}")
  assert(CycA.b == 3 && CycA.y == 7)

  // 4. hot loop over module access from several threads (fast path)
  val ws: Seq[Thread] = (1 to 4).map(_ => new Thread((() => { var i = 0; while i < 1000000 do { Hot.synchronized(Hot.bump()); i += 1 } }): Runnable))
  val t0 = System.nanoTime()
  ws.foreach(_.start()); ws.foreach(t => { t.join(); () })
  assert(Hot.counter == 4000000L, Hot.counter)
  println(s"hot ok counter=${Hot.counter} in ${(System.nanoTime()-t0)/1000000}ms")
  // 5. single-thread module access microbench
  val t1 = System.nanoTime()
  var j = 0
  while j < 200000000 do { M1.inc(); M2.inc(); j += 1 }
  assert(M1.c == 200000000L && M2.c == 200000000L)
  println(s"bench 400M module accesses: ${(System.nanoTime()-t1)/1000000}ms")
  println("ALL OK")
