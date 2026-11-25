import java.util.concurrent.{Executors, TimeUnit}
import java.util.concurrent.atomic.AtomicInteger

// Lock paths of the object monitors, see tests/object-locks/run.sh.
object Locks:
  final class Box(var n: Long)
  def check(cond: Boolean, what: => String): Unit =
    if !cond then { System.err.println(s"FAIL: $what"); sys.exit(1) }

  // recursion deeper than the 8-entry lock stack, and nested over other objects
  def recurse(o: AnyRef, depth: Int): Int =
    if depth == 0 then 0 else o.synchronized { 1 + recurse(o, depth - 1) }
  def interleave(a: AnyRef, b: AnyRef, depth: Int): Int =
    if depth == 0 then 0
    else a.synchronized { b.synchronized { a.synchronized { 1 + interleave(a, b, depth - 1) } } }

  def run(): Unit =
    val o = new Object
    check(recurse(o, 3) == 3, "shallow recursion")
    check(recurse(o, 100) == 100, "deep recursion")
    check(interleave(new Object, new Object, 20) == 20, "interleaved")
    check(!Thread.holdsLock(o), "lock released after recursion")
    o.synchronized { check(Thread.holdsLock(o), "holdsLock") }

    // contention: increments under one lock from many threads
    val box = new Box(0)
    val threads = (1 to 8).map(_ => new Thread(() => {
      var i = 0
      while i < 200000 do { box.synchronized { box.n += 1 }; i += 1 }
    }))
    threads.foreach(_.start()); threads.foreach(_.join())
    check(box.n == 8 * 200000L, s"contended counter ${box.n}")

    // the same object locked from many threads, nested with thread-local ones
    val shared = new Box(0)
    val ts2 = (1 to 8).map(_ => new Thread(() => {
      var i = 0
      while i < 20000 do
        val mine = new Object
        mine.synchronized { shared.synchronized { mine.synchronized { shared.n += 1 } } }
        i += 1
    }))
    ts2.foreach(_.start()); ts2.foreach(_.join())
    check(shared.n == 160000L, s"nested contended ${shared.n}")

    // wait/notify: producer/consumer on a fast lock that gets inflated
    val q = scala.collection.mutable.Queue.empty[Int]
    val consumed = new AtomicInteger(0)
    val cons = new Thread(() => {
      var got = 0
      while got < 10000 do
        q.synchronized {
          while q.isEmpty do q.wait()
          q.dequeue(); got += 1
          q.notifyAll()
        }
      consumed.set(got)
    })
    cons.start()
    var k = 0
    while k < 10000 do
      q.synchronized { while q.size > 10 do q.wait(); q.enqueue(k); q.notifyAll() }
      k += 1
    cons.join()
    check(consumed.get == 10000, "producer/consumer")

    // wait with timeout from inside nested recursion
    val w = new Object
    w.synchronized { w.synchronized { w.wait(5) } ; check(Thread.holdsLock(w), "after wait") }
    check(!Thread.holdsLock(w), "released after wait")

    // IllegalMonitorStateException without the lock
    val notOwned =
      try { new Object().notify(); false }
      catch { case _: IllegalMonitorStateException => true }
    check(notOwned, "notify without lock")

    // exceptions unwinding through synchronized
    val e = new Object
    var j = 0
    while j < 1000 do
      try e.synchronized { e.synchronized { throw new RuntimeException("x") } }
      catch { case _: RuntimeException => () }
      j += 1
    check(!Thread.holdsLock(e), "released after exceptions")
    val other = new Thread(() => e.synchronized { () }); other.start(); other.join(2000)
    check(!other.isAlive, "lock free for other threads after exceptions")

    // static objects: classes and string literals
    val lits = (1 to 4).map(_ => new Thread(() => {
      var i = 0
      while i < 50000 do { "a literal".synchronized { classOf[Box].synchronized { box.n += 1 } }; i += 1 }
    }))
    box.n = 0
    lits.foreach(_.start()); lits.foreach(_.join())
    check(box.n == 200000L, s"static objects ${box.n}")

    // many inflated monitors, most of them garbage: the table rebuilds
    var round = 0
    while round < 20 do
      val objs = Array.fill(20000)(new Object)
      // entering deeper than the lock stack inflates
      objs.foreach(o => check(recurse(o, 10) == 10, "inflating recursion"))
      // inflated monitors still work, also from another thread
      val t = new Thread(() => objs.take(100).foreach(o => o.synchronized { o.notifyAll() }))
      t.start(); t.join()
      check(objs.forall(o => !Thread.holdsLock(o)), "inflated released")
      round += 1
      System.gc()

    // virtual threads, if any: they always inflate
    try
      val ex = Executors.newVirtualThreadPerTaskExecutor()
      val vbox = new Box(0)
      (1 to 100).foreach(_ => ex.submit((() => { var i = 0; while i < 1000 do { vbox.synchronized { vbox.n += 1 }; i += 1 } }): Runnable))
      ex.shutdown(); ex.awaitTermination(60, TimeUnit.SECONDS)
      check(vbox.n == 100000L, s"virtual threads ${vbox.n}")
    catch case _: UnsupportedOperationException => println("no virtual threads")

    // hashCode on locked objects and locking hashed objects
    val h = new Object
    val hc = h.hashCode
    h.synchronized { check(h.hashCode == hc, "hash while locked") }

    // uncontended speed, printed only
    val t0 = System.nanoTime()
    val u = new Box(0)
    var i = 0
    while i < 50000000 do { u.synchronized { u.n += 1 }; i += 1 }
    println(s"bench 50M uncontended locks: ${(System.nanoTime() - t0) / 1000000}ms")
    println("ALL OK")

@main def main(): Unit = Locks.run()
