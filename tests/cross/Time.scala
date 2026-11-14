// Compiles windowslib's time.c, which is only built when something defines __SCALANATIVE_POSIX_TIME (javalib's
// java.util.Date and java.time do, examples/Hello.scala does not): the file must build against the UCRT headers
// of the mingw sysroot, which declare asctime_s/gmtime_s/localtime_s themselves.
import java.time.{Instant, ZoneOffset}
import java.util.Date

@main def timeCalls(): Unit =
  val epoch = new Date(0L).toString
  val utc = Instant.ofEpochSecond(86400L * 365).atZone(ZoneOffset.UTC).toString
  assert(epoch.contains("1970") || epoch.contains("1969"), s"unexpected Date: $epoch")
  assert(utc == "1971-01-01T00:00Z", s"unexpected Instant: $utc")
  println(s"time ok: $epoch, $utc")
