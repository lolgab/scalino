package coursierapi

/** Stub for `io.get-coursier:interface`'s `coursierapi.Complete` (`import $ivy"..."` dependency
 *  completions), which has no Scala Native build. `complete()` always returns an empty result;
 *  `scala.meta.internal.mtags.CoursierComplete` is the only caller.
 */
final class Complete private () {
  def withScalaVersion(v: String): Complete = this
  def withScalaBinaryVersion(v: String): Complete = this
  def withInput(s: String): Complete = this
  def complete(): CompleteResult = CompleteResult.of(0, new java.util.ArrayList[String]())
}
object Complete {
  def create(): Complete = new Complete()
}
