package dotty.tools.pc.buildinfo

// Hand-written stand-in for the sbt-buildinfo output of the real presentation-compiler build.
object BuildInfo {
  val scalaVersion: String = dotty.tools.dotc.config.Properties.versionNumberString
}
