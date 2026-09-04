package selfexe

// Resolves the path to the currently running executable on Windows via the
// standard Win32 GetModuleFileNameA (kernel32, always linked). Untested --
// Windows support in this toolchain is best-effort/experimental. See
// Linux.scala for why this isn't done through java.lang.ProcessHandle.
import scala.scalanative.unsafe.*
import scala.scalanative.unsigned.*

@extern
@link("Kernel32")
private object Kernel32:
  def GetModuleFileNameA(hModule: Ptr[Byte], lpFilename: CString, nSize: CInt): CInt = extern

object SelfExe:
  def path(): String = Zone.acquire { implicit z =>
    val cap = 4096
    val buf = alloc[Byte](cap.toUInt)
    val n = Kernel32.GetModuleFileNameA(null, buf, cap)
    if n == 0 then throw new RuntimeException("sn-cli: GetModuleFileNameA failed")
    fromCString(buf)
  }
