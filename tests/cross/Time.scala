// Compiles windowslib's time.c, which is only built when something defines __SCALANATIVE_POSIX_TIME (javalib's
// java.util.Date does, examples/Hello.scala does not): the file must build against the UCRT headers
// of the mingw sysroot, which declare asctime_s/gmtime_s/localtime_s themselves.
import java.util.Date

@main def timeCalls(): Unit =
  val epoch = new Date(0L).toString
  assert(epoch.contains("1970") || epoch.contains("1969"), s"unexpected Date: $epoch")
  println(s"time ok: $epoch")
