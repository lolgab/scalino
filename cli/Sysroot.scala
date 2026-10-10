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
   *  gave, else what's installed, else what `SysrootBuild` assembles on the
   *  spot. Fails for targets it can't build. */
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
        case None if Supported.contains(t) =>
          println(s"no sysroot for $t -- building it (`scalino sysroot build $t`)")
          Some(s"$t=${SysrootBuild.build(t, force = false)}")
        case None => fail(s"no sysroot for $t: pass --native-sysroot $t=<dir> (`scalino sysroot build` supports ${Supported.mkString(", ")})")
    }
    explicit ++ found

  /** The `ld.lld` for a build to `triples`, when one is needed and the system has none on PATH:
   *  GNU ld is single-arch and Apple's ld only exists on a Mac, so every target that isn't the host's own
   *  (and every Linux one) links with lld. Downloaded on first use, like the sysroots. */
  def ensureLld(triples: List[String]): Option[String] =
    val host = Packaging.detectHost()
    val needed = triples.exists { t =>
      val target = Packaging.hostFromTriple(t)
      target != host && !(target.os == "macos" && host.os == "macos") || target.os == "linux"
    }
    val onPath = Option(System.getenv("PATH")).getOrElse("").split(java.io.File.pathSeparator).exists(d =>
      d.nonEmpty && List("ld.lld", "ld.lld.exe").exists(n => Files.isExecutable(Paths.get(d, n))))
    if !needed || onPath then None
    else
      println("no ld.lld on PATH -- fetching one")
      SysrootBuild.hostLld(host).map(_.toString)

  // ---- addons: static C libraries ------------------------------------------

  /** Static libraries a sysroot doesn't have, by name, with what links them
   *  through `@link`: an artifact (as `/<artifact>_native` in a classpath path) or, when it has a `/`,
   *  a classpath path fragment.
   *  `scalino sysroot build` installs one under `<root>/addons/<lib>/<triple>`
   *  (lib/, include/, optionally `linkflags`), and `package` hands every
   *  cross target's to the link driver when the classpath has one of these. */
  val Addons: Map[String, List[String]] = Map(
    "openssl" -> List(
      "scala-native-crypto", // com.github.lolgab::scala-native-crypto
      "fs2-core", // fs2.hashing
      "http4s-crypto", // org.http4s::http4s-crypto, behind http4s-core
      "smithy4s-aws-kernel", // com.disneystreaming.smithy4s
      "skunk-core"),
    "idn2" -> List(
      "sttp/model/core_native"), // sttp-model's IDN support (idn2_to_ascii_8z)
    "s2n" -> List(
      "fs2-io"), // fs2.io.net.tls, so also skunk and http4s-ember; not on Windows (upstream has no port)
    "curl" -> List(
      "sttp/client3/core_native", "sttp/client4/core_native", // sttp's curl backend
      "scala-native-loop", "libcurl"))

  /** Addons a library needs installed and linked with it. */
  def addonDeps(lib: String, triple: String): List[String] = lib match
    case "s2n" => List("openssl")
    case "curl" => if triple.contains("windows") then List("openssl", "idn2") else List("openssl")
    case _ => Nil

  def withDeps(libs: List[String], triple: String): List[String] = (libs ++ libs.flatMap(addonDeps(_, triple))).distinct

  /** Targets a library has no build for: skipped silently, since the link only fails if its code is reached. */
  def addonSupports(lib: String, triple: String): Boolean = !(lib == "s2n" && triple.contains("windows"))

  def addonDir(lib: String, triple: String): Path = cacheRoot.resolve("addons").resolve(lib).resolve(triple)

  def findAddon(lib: String, triple: String): Option[Path] =
    roots.map(_.resolve("addons").resolve(lib).resolve(triple)).find(d => Files.isDirectory(d.resolve("lib")))

  /** The addons `classpath` (jar paths) asks for. */
  def neededAddons(classpath: String): List[String] =
    val entries = classpath.split(java.io.File.pathSeparator).toList.map(_.replace('\\', '/'))
    Addons.toList.sortBy(_._1).collect {
      case (lib, triggers) if entries.exists(e => triggers.exists(t => e.contains(if t.contains("/") then t else s"/${t}_native"))) => lib
    }

  /** `--native-lib-addon <triple>=<dir>` entries: for each cross target in
   *  `triples`, the addons `classpath` needs, installed on the spot when
   *  missing. A host target links the system's copy. */
  def resolveAddons(triples: List[String], classpath: String): List[String] =
    val libs = neededAddons(classpath)
    val host = Packaging.detectHost()
    for
      t <- triples.distinct
      if libs.nonEmpty && Supported.contains(t) && Packaging.hostFromTriple(t) != host
      lib <- withDeps(libs, t)
      if addonSupports(lib, t)
    yield
      val dir = findAddon(lib, t).getOrElse {
        println(s"no static $lib for $t -- installing it (`scalino sysroot build $t --with $lib`)")
        SysrootBuild.buildAddon(lib, t, force = false)
      }
      s"$t=$dir"

  def handle(args: Array[String]): Unit =
    args.toList match
      case "build" :: rest =>
        var force = false
        var libs = List.empty[String]
        val triples = List.newBuilder[String]
        var i = 0
        while i < rest.length do
          rest(i) match
            case "--force" => force = true
            case "--with" =>
              if i + 1 >= rest.length then die("sysroot build: --with needs a library name")
              libs = libs :+ rest(i + 1); i += 1
            case o if o.startsWith("-") => die(s"sysroot build: unknown option $o")
            case t => triples += t
          i += 1
        val ts = triples.result()
        if ts.isEmpty then die(s"sysroot build: expected a target triple, one of: ${SysrootBuild.Buildable.mkString(", ")}")
        ts.foreach(SysrootBuild.build(_, force))
        ensureLld(ts)
        for t <- ts; l <- withDeps(libs, t) if addonSupports(l, t) do SysrootBuild.buildAddon(l, t, force)
      case List("lld") =>
        val host = Packaging.detectHost()
        SysrootBuild.hostLld(host) match
          case Some(p) => println(p)
          case None => die(s"sysroot lld: no prebuilt lld for ${host.triple}, install lld with your package manager")
      case List("list") =>
        for t <- Supported do
          println(s"$t  ${find(t).map(_.toString).getOrElse("(not installed -- `scalino sysroot build " + t + "`)")}")
      case List("path", t) =>
        find(t) match
          case Some(p) => println(p)
          case None => die(s"sysroot: $t is not installed")
      case _ => die("usage: scalino sysroot <build <triple>... [--force] [--with <lib>] | lld | list | path <triple>>")
