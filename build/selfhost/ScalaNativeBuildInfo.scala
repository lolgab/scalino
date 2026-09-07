package scala.scalanative.nir

// Hand-written stub of scala-native's own sbt-buildinfo-generated object
// (see vendor/scala-native/project/Build.scala:183, the `nir` project's
// own `.withBuildInfo(Compile, Some("scala.scalanative.nir"))` --
// overrides the generic default package, "scala.scalanative.buildinfo",
// matching `Versions.scala`'s unqualified same-package reference) --
// normally produced by the sbt-buildinfo plugin at scala-native's own build
// time, never checked into vendor/scala-native's own source. Values pinned
// to match this project's own versions.env exactly; keep in sync if that
// file's SCALA_NATIVE_VERSION/SCALA_VERSION ever changes.
object ScalaNativeBuildInfo {
  val version: String = "0.5.12"
  val scalaVersion: String = "3.8.4"
}
