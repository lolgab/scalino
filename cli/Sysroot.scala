// Cross-compilation sysroots: the target's libc headers and runtime libraries,
// which clang needs to compile and link Scala Native's C runtime for a
// platform other than the host's. `scalino sysroot build <triple>`
// (SysrootBuild.scala) assembles one from upstream packages into the cache;
// `package` finds it there automatically when given `--native-target-triple`.
//
// Linux targets are musl (linked statically, runs on every distro) or gnu
// (glibc 2.31 from Debian 11, dynamic, runs on any distro with glibc >= 2.31).
// macOS targets use a small stand-in for Apple's SDK (libSystem stubs + the
// open source Darwin libc headers); on a Mac itself none is needed, the
// installed Xcode SDK serves both architectures. Windows targets use
// llvm-mingw's mingw-w64 data.

import java.nio.file.{Files, Path, Paths}
import ScalinoCli.{die, fail, dist}

object Sysroot:

  /** Targets `build` can assemble a sysroot for. */
  val Supported: List[String] =
    List(
      "x86_64-unknown-linux-musl", "aarch64-unknown-linux-musl",
      "x86_64-unknown-linux-gnu", "aarch64-unknown-linux-gnu",
      "x86_64-apple-darwin", "aarch64-apple-darwin",
      "x86_64-pc-windows-gnu", "aarch64-pc-windows-gnu")

  private def env(name: String): Option[String] = Option(System.getenv(name)).filter(_.nonEmpty)

  /** Where `build` installs. */
  def cacheRoot: Path =
    env("SCALINO_SYSROOT_DIR").map(Paths.get(_)).getOrElse(
      env("XDG_CACHE_HOME").map(Paths.get(_)).getOrElse(Paths.get(env("HOME").getOrElse("."), ".cache")).resolve("scalino").resolve("sysroots")
    )

  /** Everything searched, in order. `<dist>/sysroots` is where OS packages
   *  (deb/rpm/brew) put theirs. */
  def roots: List[Path] = List(cacheRoot, Paths.get(dist).resolve("sysroots")).distinct

  /** Unix-style sysroots have usr/, mingw ones (Windows) <arch>-w64-mingw32/. */
  private def looksLikeSysroot(d: Path): Boolean =
    Files.isDirectory(d.resolve("usr")) || Files.isDirectory(d.resolve("generic-w64-mingw32"))

  def find(triple: String): Option[Path] =
    roots.map(_.resolve(triple)).find(looksLikeSysroot)

  /** `--native-sysroot <triple>=<dir>` entries for `triples`: what the user
   *  gave, else what's installed. Fails with the command to run if a target
   *  needs one that isn't there. */
  def resolve(triples: List[String], explicit: List[String]): List[String] =
    val explicitTriples = explicit.map(_.takeWhile(_ != '=')).toSet
    val host = Packaging.detectHost()
    val found = triples.filterNot(explicitTriples).flatMap { t =>
      val target = Packaging.hostFromTriple(t)
      if target == host then None
      // clang on a Mac targets both architectures from the installed SDK.
      else if target.os == "macos" && host.os == "macos" then None
      else find(t) match
        case Some(dir) => Some(s"$t=$dir")
        case None if Supported.contains(t) => fail(s"no sysroot for $t -- run `scalino sysroot build $t`")
        case None => fail(s"no sysroot for $t: pass --native-sysroot $t=<dir> (`scalino sysroot build` supports ${Supported.mkString(", ")})")
    }
    explicit ++ found

  def handle(args: Array[String]): Unit =
    args.toList match
      case "build" :: rest =>
        val force = rest.contains("--force")
        val triples = rest.filterNot(_ == "--force")
        triples.find(_.startsWith("-")).foreach(o => die(s"sysroot build: unknown option $o"))
        if triples.isEmpty then die(s"sysroot build: expected a target triple, one of: ${SysrootBuild.Buildable.mkString(", ")}")
        triples.foreach(SysrootBuild.build(_, force))
      case List("list") =>
        for t <- Supported do
          println(s"$t  ${find(t).map(_.toString).getOrElse("(not installed -- `scalino sysroot build " + t + "`)")}")
      case List("path", t) =>
        find(t) match
          case Some(p) => println(p)
          case None => die(s"sysroot: $t is not installed")
      case _ => die("usage: scalino sysroot <build <triple>... [--force] | list | path <triple>>")
