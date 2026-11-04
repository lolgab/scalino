package selfexe

// Path of the running executable on Linux, via the /proc/self/exe symlink (already resolved).
// java.lang.ProcessHandle.current() is unimplemented in scala-native's javalib (unreachable-symbol
// link error), hence this POSIX binding.
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
