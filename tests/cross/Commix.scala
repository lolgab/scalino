//> using nativeGc commix
// Links the commix GC, whose Phase.c includes <sys/posix_sem.h> (PSEMNAMLEN): a cross sysroot without that
// header fails to build this, while the default immix GC (examples/Hello.scala) never compiles Phase.c.
@main def commixAlloc(): Unit =
  var keep: List[Array[Byte]] = Nil
  var i = 0
  while i < 2000 do
    keep = new Array[Byte](64 * 1024) :: keep.take(20)
    i += 1
  System.gc()
  println(s"commix ok: ${keep.size} live arrays")
