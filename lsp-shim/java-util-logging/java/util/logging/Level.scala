package java.util.logging

/** Minimal shim for the 3 constants presentation-compiler/mtags-shared use (INFO/WARNING/SEVERE),
 *  as `java.util.logging` has no Scala Native port. See Logger.scala.
 */
final class Level private (name: String) {
  override def toString(): String = name
}
object Level {
  val SEVERE: Level = new Level("SEVERE")
  val WARNING: Level = new Level("WARNING")
  val INFO: Level = new Level("INFO")
}
