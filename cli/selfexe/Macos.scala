package selfexe

// Resolves the path to the currently running executable on macOS via
// _NSGetExecutablePath (libSystem, always linked) + realpath to strip any
// symlinks/relative components. See Linux.scala for why this isn't done
// through java.lang.ProcessHandle.
import scala.scalanative.unsafe.*
import scala.scalanative.unsigned.*

@extern
private object Dyld:
  def _NSGetExecutablePath(buf: CString, bufsize: Ptr[CUnsignedInt]): CInt = extern

@extern
private object CLib:
  def realpath(path: CString, resolvedPath: CString): CString = extern

object SelfExe:
  def path(): String = Zone.acquire { implicit z =>
    val cap = 4096
    val size = alloc[CUnsignedInt](1.toUInt)
    !size = cap.toUInt
    val buf = alloc[Byte](cap.toUInt)
    val rc = Dyld._NSGetExecutablePath(buf, size)
    if rc != 0 then throw new RuntimeException("sn-cli: _NSGetExecutablePath failed")
    val resolved = alloc[Byte](cap.toUInt)
    val res = CLib.realpath(buf, resolved)
    fromCString(if res == null then buf else resolved)
  }
