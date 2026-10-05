// Cross-compilation sysroots: the target's libc headers and runtime libraries,
// which clang needs to compile and link Scala Native's C runtime for a
// platform other than the host's. Built by build/12-build-sysroot.sh, published
// with every release as scalino-sysroot-<triple>.tar.gz, installed by
// `scalino sysroot fetch <triple>`, and found automatically by `package` when
// given `--native-target-triple`.
//
// Linux targets are musl (linked statically, runs on every distro) or gnu
// (glibc 2.31 from Debian 11, dynamic, runs on any distro with glibc >= 2.31). macOS targets use a small stand-in for Apple's SDK (libSystem stubs +
// the open source Darwin libc headers); on a Mac itself none is needed, the
// installed Xcode SDK serves both architectures.

import java.nio.file.{Files, Path, Paths}
import ScalinoCli.{die, fail, dist, runCaptureStdout, runInherited, sha256Hex}

object Sysroot:

  /** Targets with a published sysroot. */
  val Fetchable: List[String] =
    List(
      "x86_64-unknown-linux-musl", "aarch64-unknown-linux-musl",
      "x86_64-unknown-linux-gnu", "aarch64-unknown-linux-gnu",
      "x86_64-apple-darwin", "aarch64-apple-darwin",
      "x86_64-pc-windows-gnu", "aarch64-pc-windows-gnu")

  private def env(name: String): Option[String] = Option(System.getenv(name)).filter(_.nonEmpty)

  /** Where `fetch` installs. */
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

  private def releaseUrl(triple: String): String =
    val base = env("SCALINO_SYSROOT_URL").getOrElse(
      s"https://github.com/lolgab/scalino/releases/download/v${BuildInfo.scalinoVersion}")
    s"${base.stripSuffix("/")}/scalino-sysroot-$triple.tar.gz"

  private def download(url: String, to: Path): Unit =
    val code = runInherited(List("curl", "-fsSL", "-o", to.toString, url))
    if code != 0 then die(s"sysroot: download failed: $url")

  /** Installs the sysroot for `triple` into the cache. `from` is a tarball
   *  path or URL to use instead of the release asset (offline installs,
   *  testing a locally built one). */
  def fetch(triple: String, from: Option[String], force: Boolean): Path =
    val dest = cacheRoot.resolve(triple)
    if Files.isDirectory(dest) && !force then
      println(s"sysroot $triple already installed at $dest (--force to reinstall)")
      return dest
    val source = from.getOrElse(releaseUrl(triple))
    Files.createDirectories(cacheRoot)
    val tmp = Files.createTempDirectory(cacheRoot, s".fetch-$triple-")
    try
      val tarball = tmp.resolve("sysroot.tar.gz")
      val isUrl = source.startsWith("http://") || source.startsWith("https://")
      if isUrl then download(source, tarball)
      else
        if !Files.exists(Paths.get(source)) then die(s"sysroot: no such file: $source")
        Files.copy(Paths.get(source), tarball)
      // The checksum published next to the tarball.
      val sumFile = tmp.resolve("sysroot.sha256")
      val haveSum =
        if isUrl then runCaptureStdout(List("curl", "-fsSL", "-o", sumFile.toString, "-w", "%{http_code}", s"$source.sha256")) match
          case (0, _) => true
          case _ => false
        else
          val local = Paths.get(s"$source.sha256")
          if Files.exists(local) then { Files.copy(local, sumFile); true } else false
      if haveSum then
        val expected = new String(Files.readAllBytes(sumFile), "UTF-8").trim.takeWhile(!_.isWhitespace)
        val actual = sha256Hex(Files.readAllBytes(tarball))
        if expected != actual then die(s"sysroot: checksum mismatch for $source (expected $expected, got $actual)")
      else if isUrl then die(s"sysroot: no checksum published at $source.sha256")
      else System.err.println(s"scalino: warning: no $source.sha256 next to the tarball, not verifying it")
      val extracted = tmp.resolve("x")
      Files.createDirectories(extracted)
      if runInherited(List("tar", "-xzf", tarball.toString, "-C", extracted.toString, "--strip-components=1")) != 0 then
        die(s"sysroot: could not extract $source")
      if !looksLikeSysroot(extracted) then die(s"sysroot: $source has no usr/ directory, not a scalino sysroot")
      if Files.exists(dest) then deleteRecursively(dest)
      Files.move(extracted, dest)
      println(s"installed sysroot $triple -> $dest")
      dest
    finally deleteRecursively(tmp)

  private def deleteRecursively(p: Path): Unit = ScalinoCli.deleteRecursively(p)

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
        case None if Fetchable.contains(t) => fail(s"no sysroot for $t -- run `scalino sysroot fetch $t`")
        case None => fail(s"no sysroot for $t: pass --native-sysroot $t=<dir> (scalino publishes sysroots for ${Fetchable.mkString(", ")})")
    }
    explicit ++ found

  def handle(args: Array[String]): Unit =
    args.toList match
      case "fetch" :: rest =>
        var triples = List.empty[String]
        var from = Option.empty[String]
        var force = false
        var i = 0
        while i < rest.length do
          rest(i) match
            case "--from" if i + 1 < rest.length => from = Some(rest(i + 1)); i += 1
            case "--force" => force = true
            case f if f.startsWith("-") => die(s"sysroot fetch: unknown option $f")
            case t => triples = triples :+ t
          i += 1
        if triples.isEmpty then die(s"sysroot fetch: expected a target triple, one of: ${Fetchable.mkString(", ")}")
        if from.isDefined && triples.size != 1 then die("sysroot fetch: --from takes exactly one triple")
        triples.foreach { t =>
          if from.isEmpty && !Fetchable.contains(t) then die(s"sysroot fetch: no published sysroot for '$t', available: ${Fetchable.mkString(", ")}")
          fetch(t, from, force)
        }
      case List("list") =>
        for t <- Fetchable do
          println(s"$t  ${find(t).map(_.toString).getOrElse("(not installed -- `scalino sysroot fetch " + t + "`)")}")
      case List("path", t) =>
        find(t) match
          case Some(p) => println(p)
          case None => die(s"sysroot: $t is not installed")
      case _ => die("usage: scalino sysroot <fetch <triple>... [--from <tarball|url>] [--force] | list | path <triple>>")
