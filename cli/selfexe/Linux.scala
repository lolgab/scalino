package selfexe

// Resolves the path to the currently running executable on Linux, via the
// kernel-maintained /proc/self/exe symlink (already fully resolved -- no
// realpath needed). java.lang.ProcessHandle.current() would be the portable
// JVM way to do this, but it's unimplemented in scala-native's javalib port
// (unreachable-symbol link error), hence this hand-rolled POSIX binding.
import scala.scalanative.unsafe.*
import scala.scalanative.unsigned.*

@extern
private object CLib:
  def readlink(path: CString, buf: CString, bufsize: CSize): CSSize = extern

object SelfExe:
  def path(): String = Zone.acquire { implicit z =>
    val cap = 4096
    val buf = alloc[Byte](cap.toUInt)
    val n = CLib.readlink(c"/proc/self/exe", buf, cap.toUInt)
    if n <= 0 then throw new RuntimeException("scalino: readlink(/proc/self/exe) failed")
    buf(n) = 0.toByte
    fromCString(buf)
  }
