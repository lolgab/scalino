// Regression for patch 0069 (x86_64 safepoint trampoline): a thread stopped at a GC
// yield point resumed with %r10 overwritten by the resume address, and the trampoline
// frame was written over the 128-byte red zone below %rsp, where leaf functions keep
// spilled locals. Both silently corrupted whatever the interrupted code was computing.
//
// `crunch` is a leaf (no calls, raw pointer access so no bounds-check call) with more live
// values than registers, so LLVM spills into the red zone; its loop has a yield point.
// While the workers run it, another thread forces GCs, stopping them at those yield points.
// Every result must equal the single-threaded reference. Expected output (also see CI):
//   safepoint-trampoline: ok (<N> checks)
// Run with multithreading on, e.g.  scalino run examples/safepoint-trampoline/Main.scala --main-class Main
import scala.scalanative.unsafe.*
import scala.scalanative.libc.stdlib
import java.util.concurrent.atomic.AtomicBoolean

object Main:
  def crunch(p: Ptr[Long], iters: Int): Long =
    var s0 = 1L
    var s1 = 8L
    var s2 = 15L
    var s3 = 22L
    var s4 = 29L
    var s5 = 36L
    var s6 = 43L
    var s7 = 50L
    var s8 = 57L
    var s9 = 64L
    var s10 = 71L
    var s11 = 78L
    var s12 = 85L
    var s13 = 92L
    var s14 = 99L
    var s15 = 106L
    var s16 = 113L
    var s17 = 120L
    var s18 = 127L
    var s19 = 134L
    var s20 = 141L
    var s21 = 148L
    var i = 0
    while i < iters do
      val x = p(i & 255)
      s0 = (s0 ^ (s21 + x)) * 31L + 0L
      s1 = (s1 ^ (s0 + x)) * 31L + 1L
      s2 = (s2 ^ (s1 + x)) * 31L + 2L
      s3 = (s3 ^ (s2 + x)) * 31L + 3L
      s4 = (s4 ^ (s3 + x)) * 31L + 4L
      s5 = (s5 ^ (s4 + x)) * 31L + 5L
      s6 = (s6 ^ (s5 + x)) * 31L + 6L
      s7 = (s7 ^ (s6 + x)) * 31L + 7L
      s8 = (s8 ^ (s7 + x)) * 31L + 8L
      s9 = (s9 ^ (s8 + x)) * 31L + 9L
      s10 = (s10 ^ (s9 + x)) * 31L + 10L
      s11 = (s11 ^ (s10 + x)) * 31L + 11L
      s12 = (s12 ^ (s11 + x)) * 31L + 12L
      s13 = (s13 ^ (s12 + x)) * 31L + 13L
      s14 = (s14 ^ (s13 + x)) * 31L + 14L
      s15 = (s15 ^ (s14 + x)) * 31L + 15L
      s16 = (s16 ^ (s15 + x)) * 31L + 16L
      s17 = (s17 ^ (s16 + x)) * 31L + 17L
      s18 = (s18 ^ (s17 + x)) * 31L + 18L
      s19 = (s19 ^ (s18 + x)) * 31L + 19L
      s20 = (s20 ^ (s19 + x)) * 31L + 20L
      s21 = (s21 ^ (s20 + x)) * 31L + 21L
      i += 1
    s0 + s1 + s2 + s3 + s4 + s5 + s6 + s7 + s8 + s9 + s10 + s11 + s12 + s13 + s14 + s15 + s16 + s17 + s18 + s19 + s20 + s21

  def main(args: Array[String]): Unit =
    val nThreads = 4
    val rounds = if args.length > 0 then args(0).toInt else 100
    val iters = if args.length > 1 then args(1).toInt else 5000
    val data = stdlib.malloc(256 * 8).asInstanceOf[Ptr[Long]]
    var k = 0
    while k < 256 do
      data(k) = k.toLong * 0x9e3779b97f4a7c15L
      k += 1
    val expected = crunch(data, iters)

    val stop = new AtomicBoolean(false)
    val gcThread = new Thread(() =>
      while !stop.get() do
        System.gc()
        Thread.`yield`()
    )
    gcThread.start()

    val bad = new java.util.concurrent.atomic.AtomicInteger(0)
    val workers = (0 until nThreads).map { t =>
      val th = new Thread(() =>
        var r = 0
        while r < rounds do
          // allocate too, so the GC has real work and threads sit at yield points often
          val junk = new Array[Long](512)
          junk(r & 511) = r
          val got = crunch(data, iters)
          if got != expected || junk(r & 511) != r then
            bad.incrementAndGet()
            println(s"worker $t round $r: got $got expected $expected")
          r += 1
      )
      th.start()
      th
    }
    workers.foreach(_.join())
    stop.set(true)
    gcThread.join()
    stdlib.free(data)
    if bad.get() != 0 then
      println(s"safepoint-trampoline: FAILED (${bad.get()} bad results)")
      sys.exit(1)
    println(s"safepoint-trampoline: ok (${nThreads * rounds} checks)")
