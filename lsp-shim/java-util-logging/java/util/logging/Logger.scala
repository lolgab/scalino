package java.util.logging

/** Minimal shim for the 2 `log` overloads presentation-compiler/mtags-shared call
 *  (`CompilerSearchVisitor`, `CompilerAccess`, `PackageIndex`), as `java.util.logging` has no
 *  Scala Native port. Writes to stderr so compiler crashes and classpath scan failures stay
 *  visible (see Main.scala's `Log`).
 */
final class Logger private (name: String) {
  def log(level: Level, msg: String): Unit =
    System.err.println(s"[$level] $name: $msg")
  def log(level: Level, msg: String, thrown: Throwable): Unit = {
    System.err.println(s"[$level] $name: $msg")
    if (thrown != null) thrown.printStackTrace()
  }
}
object Logger {
  def getLogger(name: String): Logger = new Logger(name)
}
