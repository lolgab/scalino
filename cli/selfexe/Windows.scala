package selfexe

// Path of the running executable on Windows via Win32 GetModuleFileNameA (kernel32). See
// Linux.scala for why not java.lang.ProcessHandle.
import scala.scalanative.unsafe.*
import scala.scalanative.unsigned.*

@extern
@link("kernel32")
private object Kernel32:
  def GetModuleFileNameA(hModule: Ptr[Byte], lpFilename: CString, nSize: CInt): CInt = extern

object SelfExe:
  def path(): String = Zone.acquire { implicit z =>
    val cap = 4096
    val buf = alloc[Byte](cap.toUInt)
    val n = Kernel32.GetModuleFileNameA(null, buf, cap)
    if n == 0 then throw new RuntimeException("scalino: GetModuleFileNameA failed")
    fromCString(buf)
  }
