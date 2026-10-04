// scalino's distribution-packaging half (`scalino package --format ...`): wraps
// the one native binary scalino already links into the formats Linux/macOS
// users actually install from -- .deb, .rpm, OCI/Docker image, a release
// tarball, and a Homebrew formula -- the nfpm/goreleaser/cargo-dist role,
// as opposed to sbt-native-packager's "generate a launcher script around a
// classpath" (there is no classpath here, just one executable).
//
// Everything that can be written in-process is (ar/tar/gzip/md5 for .deb,
// tar/gzip/sha256 for the tarball, Dockerfile + formula text), so no fpm /
// dpkg-deb is ever needed. The two formats that genuinely need an external
// tool shell out to it: .rpm -> `rpmbuild` (the RPM header/cpio payload
// format is not worth re-implementing, and rpmbuild also computes the
// shared-library Requires automatically), docker -> `docker`/`podman build`.
//
// There is no cross-compilation in this toolchain: a package always holds the
// binary built on the *host* it runs on (see `detectHost`). Build per-OS/arch
// on a CI matrix, point every leg at the same output directory, then run
// `--format brew` last to get one formula covering every tarball found there.

import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path, Paths}
import java.util.zip.GZIPOutputStream
import ScalinoCli.{fail, runInherited, runCaptureStdout, sha256Hex, Color}

object Packaging:

  val Formats: List[String] = List("binary", "tar", "deb", "rpm", "docker", "brew")

  /** `//> using <spelling>` -> canonical key in the `Directives.pkg` map. */
  val DirectiveAliases: Map[String, String] = Map(
    "packageName" -> "packageName",
    "packageVersion" -> "packageVersion",
    "packageDescription" -> "packageDescription",
    "packageMaintainer" -> "packageMaintainer",
    "packageLicense" -> "packageLicense",
    "packageHomepage" -> "packageHomepage",
    "packageDep" -> "packageDep",
    "packageDeps" -> "packageDep",
    "packageFile" -> "packageFile",
    "packageFiles" -> "packageFile",
    "packageDockerBase" -> "packageDockerBase",
    "packageDockerImage" -> "packageDockerImage",
    "packageReleaseUrl" -> "packageReleaseUrl"
  )

  /** Parses a `--format a,b` value list: validates, resolves aliases, dedupes. */
  def parseFormats(raw: List[String]): Either[String, List[String]] =
    val names = raw.flatMap(_.split(",").toList).map(_.trim).filter(_.nonEmpty).map {
      case "tar.gz" | "tgz" | "archive" => "tar"
      case "homebrew" => "brew"
      case other => other
    }
    names.find(!Formats.contains(_)) match
      case Some(bad) => Left(s"unknown package format '$bad' (expected one of: ${Formats.mkString(", ")})")
      case None =>
        val distinct = names.distinct
        if distinct.contains("binary") && distinct.length > 1 then
          Left("--format binary (a bare native executable) can't be combined with other formats")
        else Right(distinct)

  // ---------------------------------------------------------------------
  // Host / metadata
  // ---------------------------------------------------------------------

  case class Host(os: String, arch: String, libc: String):
    def triple: String = os match
      case "linux" => s"$arch-unknown-linux-$libc"
      case "macos" => s"$arch-apple-darwin"
      case other => s"$arch-$other"

  private def uname(flag: String): String =
    val (code, out) = runCaptureStdout(List("uname", flag))
    if code != 0 then fail(s"`uname $flag` failed") else out.trim

  /** The OS/arch/libc of the binary scalino just linked -- always the host's,
   *  since there is no cross-compilation. `SCALINO_PACKAGE_TRIPLE` overrides
   *  it (a testing aid, and a way to package a binary built elsewhere). */
  def detectHost(): Host =
    Option(System.getenv("SCALINO_PACKAGE_TRIPLE")).filter(_.nonEmpty) match
      case Some(t) =>
        val arch = normalizeArch(t.takeWhile(_ != '-'))
        if t.contains("linux") then Host("linux", arch, if t.endsWith("musl") then "musl" else "gnu")
        else if t.contains("darwin") then Host("macos", arch, "")
        else fail(s"SCALINO_PACKAGE_TRIPLE='$t': expected a linux or darwin target triple")
      case None =>
        val os = uname("-s") match
          case "Linux" => "linux"
          case "Darwin" => "macos"
          case other => other.toLowerCase
        val arch = normalizeArch(uname("-m"))
        val musl = os == "linux" && Files.exists(Paths.get(s"/lib/ld-musl-$arch.so.1"))
        Host(os, arch, if os != "linux" then "" else if musl then "musl" else "gnu")

  private def normalizeArch(a: String): String = a match
    case "arm64" => "aarch64"
    case "amd64" => "x86_64"
    case other => other

  case class ExtraFile(src: Path, dest: String)

  case class Meta(
    name: String,
    version: String,
    description: String,
    maintainer: String,
    license: Option[String],
    homepage: Option[String],
    deps: List[String],
    files: List[ExtraFile],
    dockerBase: Option[String],
    dockerImage: Option[String],
    releaseUrl: Option[String]
  )

  private def sanitizeName(s: String): String =
    val n = s.toLowerCase.map(c => if c.isLetterOrDigit && c < 128 || c == '+' || c == '.' || c == '-' then c else '-')
    val trimmed = n.dropWhile(c => !c.isLetterOrDigit)
    if trimmed.length < 2 then fail(s"package name '$s' is not usable -- set //> using packageName \"...\" (or --pkg-name)")
    trimmed

  def resolveMeta(
    d: Map[String, List[String]],
    mainClass: String,
    cliName: Option[String],
    cliVersion: Option[String],
    cliReleaseUrl: Option[String]
  ): Meta =
    def one(k: String): Option[String] = d.get(k).flatMap(_.lastOption)
    val name = sanitizeName(cliName.orElse(one("packageName")).getOrElse(mainClass.substring(mainClass.lastIndexOf('.') + 1)))
    val version = cliVersion.orElse(one("packageVersion")).getOrElse {
      System.err.println(Color.warn("scalino: warning: no package version -- using 0.1.0 (set //> using packageVersion \"...\" or --pkg-version)"))
      "0.1.0"
    }
    val maintainer = one("packageMaintainer").getOrElse {
      System.err.println(Color.warn("scalino: warning: no //> using packageMaintainer \"Name <email>\" -- using a placeholder"))
      "Unknown Maintainer <unknown@example.com>"
    }
    val files = d.getOrElse("packageFile", Nil).map { spec =>
      val i = spec.indexOf(':')
      if i <= 0 || !spec.substring(i + 1).startsWith("/") then
        fail(s"packageFile \"$spec\": expected \"<local path>:<absolute install path>\"")
      ExtraFile(Paths.get(spec.substring(0, i)), spec.substring(i + 1))
    }
    Meta(
      name, version,
      one("packageDescription").getOrElse(s"$name, a Scala Native application built with scalino"),
      maintainer, one("packageLicense"), one("packageHomepage"),
      d.getOrElse("packageDep", Nil), files,
      one("packageDockerBase"), one("packageDockerImage"),
      cliReleaseUrl.orElse(one("packageReleaseUrl")).map(_.stripSuffix("/"))
    )

  // ---------------------------------------------------------------------
  // Payload model + archive writers
  // ---------------------------------------------------------------------

  private val Mode755 = 0x1ed
  private val Mode644 = 0x1a4

  /** One path in the installed tree (relative, no leading slash). */
  case class Entry(path: String, isDir: Boolean, mode: Int, data: Array[Byte] = Array.empty)

  private def parents(path: String): List[String] =
    val segs = path.split("/").toList.filter(_.nonEmpty)
    segs.indices.drop(1).map(i => segs.take(i).mkString("/")).toList

  /** The filesystem layout every Linux format installs: the binary at
   *  /usr/bin/<name> plus any `packageFile` extras, with parent directories. */
  def payloadEntries(meta: Meta, binary: Path): List[Entry] =
    val bin = Entry(s"usr/bin/${meta.name}", false, Mode755, Files.readAllBytes(binary))
    val extras = meta.files.map { f =>
      if !Files.isRegularFile(f.src) then fail(s"packageFile: ${f.src} is not a file")
      Entry(f.dest.stripPrefix("/"), false, if Files.isExecutable(f.src) then Mode755 else Mode644, Files.readAllBytes(f.src))
    }
    val files = bin :: extras
    files.groupBy(_.path).collectFirst { case (p, es) if es.length > 1 => p }.foreach(p => fail(s"two package entries install to /$p"))
    val dirs = files.flatMap(e => parents(e.path)).distinct.sorted.map(Entry(_, true, Mode755))
    dirs ++ files

  private def sourceDateEpoch(): Long =
    Option(System.getenv("SOURCE_DATE_EPOCH")).flatMap(_.toLongOption).getOrElse(System.currentTimeMillis() / 1000)

  private def octal(h: Array[Byte], off: Int, len: Int, v: Long): Unit =
    val s = java.lang.Long.toOctalString(v)
    if s.length > len - 1 then fail(s"tar: value $v does not fit a $len-byte header field")
    val padded = "0" * (len - 1 - s.length) + s
    System.arraycopy(padded.getBytes(UTF_8), 0, h, off, padded.length)

  private def putStr(h: Array[Byte], off: Int, max: Int, s: String): Unit =
    val b = s.getBytes(UTF_8)
    if b.length > max then fail(s"tar: '$s' is too long for its header field")
    System.arraycopy(b, 0, h, off, b.length)

  private def tarHeader(name: String, e: Entry, mtime: Long): Array[Byte] =
    val h = new Array[Byte](512)
    val (prefix, base) =
      if name.length <= 100 then ("", name)
      else
        name.indices
          .filter(i => name(i) == '/' && i < name.length - 1 && i <= 155 && name.length - i - 1 <= 100)
          .headOption
          .map(i => (name.substring(0, i), name.substring(i + 1)))
          .getOrElse(fail(s"package path too long for tar: $name"))
    putStr(h, 0, 100, base)
    octal(h, 100, 8, e.mode)
    octal(h, 108, 8, 0)
    octal(h, 116, 8, 0)
    octal(h, 124, 12, if e.isDir then 0 else e.data.length.toLong)
    octal(h, 136, 12, mtime)
    h(156) = (if e.isDir then '5' else '0').toByte
    putStr(h, 257, 6, "ustar")
    putStr(h, 263, 2, "00")
    putStr(h, 265, 32, "root")
    putStr(h, 297, 32, "root")
    putStr(h, 345, 155, prefix)
    for i <- 148 until 156 do h(i) = ' '.toByte
    val sum = h.foldLeft(0L)((acc, b) => acc + (b & 0xff))
    octal(h, 148, 7, sum)
    h(155) = ' '.toByte
    h

  /** Uncompressed ustar archive, root:root, every path prefixed with `prefix`. */
  def tar(entries: List[Entry], prefix: String = ""): Array[Byte] =
    val mtime = sourceDateEpoch()
    val out = new ByteArrayOutputStream
    entries.foreach { e =>
      val raw = prefix + e.path
      val name = if e.isDir && !raw.endsWith("/") then raw + "/" else raw
      out.write(tarHeader(name, e, mtime))
      if !e.isDir then
        out.write(e.data)
        val pad = (512 - e.data.length % 512) % 512
        out.write(new Array[Byte](pad))
    }
    out.write(new Array[Byte](1024))
    out.toByteArray

  /** gzip with a zero header mtime, so identical input gives identical bytes. */
  def gzip(bytes: Array[Byte]): Array[Byte] =
    val bo = new ByteArrayOutputStream
    val g = new GZIPOutputStream(bo)
    g.write(bytes)
    g.close()
    bo.toByteArray

  private def padRight(s: String, n: Int): String =
    if s.length > n then fail(s"ar: '$s' does not fit a $n-byte header field") else s + " " * (n - s.length)

  /** Common `ar` archive (what a .deb is). */
  def ar(members: List[(String, Array[Byte])]): Array[Byte] =
    val mtime = sourceDateEpoch()
    val out = new ByteArrayOutputStream
    out.write("!<arch>\n".getBytes(UTF_8))
    members.foreach { case (name, data) =>
      val header = padRight(name, 16) + padRight(mtime.toString, 12) + padRight("0", 6) + padRight("0", 6) +
        padRight("100644", 8) + padRight(data.length.toString, 10) + "`\n"
      out.write(header.getBytes(UTF_8))
      out.write(data)
      if data.length % 2 == 1 then out.write('\n')
    }
    out.toByteArray

  private val Md5Shift = Array(
    7, 12, 17, 22, 7, 12, 17, 22, 7, 12, 17, 22, 7, 12, 17, 22,
    5, 9, 14, 20, 5, 9, 14, 20, 5, 9, 14, 20, 5, 9, 14, 20,
    4, 11, 16, 23, 4, 11, 16, 23, 4, 11, 16, 23, 4, 11, 16, 23,
    6, 10, 15, 21, 6, 10, 15, 21, 6, 10, 15, 21, 6, 10, 15, 21
  )
  private val Md5K = Array.tabulate(64)(i => (math.abs(math.sin(i + 1.0)) * 4294967296.0).toLong.toInt)

  /** Hand-rolled MD5 (RFC 1321) -- this javalib has no MessageDigest. Only for
   *  the .deb `md5sums` file, which is an integrity manifest, not security. */
  def md5Hex(data: Array[Byte]): String =
    val padded = new Array[Byte](((data.length + 8) / 64 + 1) * 64)
    System.arraycopy(data, 0, padded, 0, data.length)
    padded(data.length) = 0x80.toByte
    val bitLen = data.length.toLong * 8
    for k <- 0 until 8 do padded(padded.length - 8 + k) = ((bitLen >>> (8 * k)) & 0xff).toByte
    var a0 = 0x67452301; var b0 = 0xefcdab89; var c0 = 0x98badcfe; var d0 = 0x10325476
    val m = new Array[Int](16)
    for block <- 0 until padded.length / 64 do
      for t <- 0 until 16 do
        val o = block * 64 + t * 4
        m(t) = (padded(o) & 0xff) | ((padded(o + 1) & 0xff) << 8) | ((padded(o + 2) & 0xff) << 16) | ((padded(o + 3) & 0xff) << 24)
      var a = a0; var b = b0; var c = c0; var d = d0
      for i <- 0 until 64 do
        val (f, g) =
          if i < 16 then ((b & c) | (~b & d), i)
          else if i < 32 then ((d & b) | (~d & c), (5 * i + 1) % 16)
          else if i < 48 then (b ^ c ^ d, (3 * i + 5) % 16)
          else (c ^ (b | ~d), (7 * i) % 16)
        val ff = f + a + Md5K(i) + m(g)
        a = d; d = c; c = b
        b = b + Integer.rotateLeft(ff, Md5Shift(i))
      a0 += a; b0 += b; c0 += c; d0 += d
    val sb = new StringBuilder
    for w <- List(a0, b0, c0, d0); k <- 0 until 4 do
      val byte = (w >>> (8 * k)) & 0xff
      sb.append(Character.forDigit(byte >> 4, 16)).append(Character.forDigit(byte & 15, 16))
    sb.toString

  // ---------------------------------------------------------------------
  // Filesystem helpers
  // ---------------------------------------------------------------------

  private def deleteTree(f: java.io.File): Unit =
    if f.isDirectory then Option(f.listFiles()).foreach(_.foreach(deleteTree))
    f.delete()

  private def writeTree(entries: List[Entry], root: Path): Unit =
    Files.createDirectories(root)
    entries.foreach { e =>
      val p = root.resolve(e.path)
      if e.isDir then Files.createDirectories(p)
      else
        Files.createDirectories(p.getParent)
        Files.write(p, e.data)
        if (e.mode & 0x40) != 0 then p.toFile.setExecutable(true, false)
    }

  private def findFiles(dir: java.io.File, suffix: String): List[java.io.File] =
    Option(dir.listFiles()).toList.flatten.flatMap { f =>
      if f.isDirectory then findFiles(f, suffix) else if f.getName.endsWith(suffix) then List(f) else Nil
    }

  /** True if `cmd --version` can be started at all (i.e. it's on PATH). */
  private def haveTool(cmd: String): Boolean =
    try
      val pb = new java.lang.ProcessBuilder(cmd, "--version")
      pb.redirectErrorStream(true)
      val p = pb.start()
      p.getInputStream.readAllBytes()
      p.waitFor()
      true
    catch case _: java.io.IOException => false

  private def requireLinux(host: Host, fmt: String): Unit =
    if host.os != "linux" then
      fail(s"--format $fmt needs a Linux binary, but this host builds ${host.triple} binaries -- run this on a Linux machine or CI runner (scalino has no cross-compilation yet)")

  private def requireGlibc(host: Host, fmt: String): Unit =
    requireLinux(host, fmt)
    if host.libc == "musl" then
      fail(s"--format $fmt targets glibc distributions, but this host builds musl binaries (${host.triple})")

  // ---------------------------------------------------------------------
  // Formats
  // ---------------------------------------------------------------------

  private def debArch(arch: String): String = arch match
    case "x86_64" => "amd64"
    case "aarch64" => "arm64"
    case "armv7l" => "armhf"
    case "i686" | "i386" => "i386"
    case "riscv64" => "riscv64"
    case other => fail(s"no Debian architecture name known for '$other'")

  private def debVersion(v: String): String =
    val raw = v.stripPrefix("v").replace('-', '~')
    if raw.isEmpty || !raw.head.isDigit then fail(s"package version '$v' must start with a digit for a .deb")
    raw

  def buildDeb(meta: Meta, host: Host, entries: List[Entry], outDir: Path): Path =
    requireGlibc(host, "deb")
    val arch = debArch(host.arch)
    val version = debVersion(meta.version)
    val files = entries.filterNot(_.isDir)
    val data = gzip(tar(entries, "./"))
    val md5sums = files.map(e => s"${md5Hex(e.data)}  ${e.path}\n").mkString
    val installedKb = (files.map(_.data.length.toLong).sum + 1023) / 1024
    val descLines = meta.description.linesIterator.toList
    val description = (descLines.headOption.getOrElse(meta.name) :: descLines.drop(1).map(l => if l.trim.isEmpty then " ." else " " + l)).mkString("\n")
    val depends = ("libc6" :: meta.deps).distinct.mkString(", ")
    val control =
      s"""Package: ${meta.name}
         |Version: $version
         |Architecture: $arch
         |Maintainer: ${meta.maintainer}
         |Installed-Size: $installedKb
         |Depends: $depends
         |Section: utils
         |Priority: optional
         |${meta.homepage.map(h => s"Homepage: $h\n").getOrElse("")}Description: $description
         |""".stripMargin
    val controlTar = gzip(tar(List(
      Entry("", true, Mode755),
      Entry("control", false, Mode644, control.getBytes(UTF_8)),
      Entry("md5sums", false, Mode644, md5sums.getBytes(UTF_8))
    ), "./"))
    val deb = ar(List(
      "debian-binary" -> "2.0\n".getBytes(UTF_8),
      "control.tar.gz" -> controlTar,
      "data.tar.gz" -> data
    ))
    val out = outDir.resolve(s"${meta.name}_${version}_$arch.deb")
    Files.write(out, deb)
    out

  private def rpmEscape(s: String): String = s.replace("%", "%%")

  def buildRpm(meta: Meta, host: Host, entries: List[Entry], outDir: Path): Path =
    requireGlibc(host, "rpm")
    if !haveTool("rpmbuild") then
      fail("--format rpm needs `rpmbuild` on PATH (apt-get install rpm | dnf install rpm-build | brew install rpm)")
    val arch = host.arch
    val version = meta.version.stripPrefix("v").replace('-', '_')
    val work = Files.createTempDirectory("scalino-rpm")
    try
      val staging = work.resolve("staging")
      writeTree(entries, staging)
      val descLines = meta.description.linesIterator.toList
      val files = entries.filterNot(_.isDir)
      val filesSection = files.map { e =>
        val mode = Integer.toOctalString(e.mode)
        s"""%attr(0$mode,root,root) "/${e.path}""""
      }.mkString("\n")
      val spec =
        s"""%global debug_package %{nil}
           |%global __strip /bin/true
           |%global __os_install_post %{nil}
           |%global _build_id_links none
           |%global _missing_build_ids_terminate_build 0
           |Name: ${meta.name}
           |Version: ${rpmEscape(version)}
           |Release: 1
           |Summary: ${rpmEscape(descLines.headOption.getOrElse(meta.name))}
           |License: ${rpmEscape(meta.license.getOrElse("Unspecified"))}
           |${meta.homepage.map(h => s"URL: $h\n").getOrElse("")}Packager: ${rpmEscape(meta.maintainer)}
           |${meta.deps.map(d => s"Requires: $d\n").mkString}
           |%description
           |${rpmEscape(meta.description)}
           |
           |%install
           |mkdir -p %{buildroot}
           |cp -a %{stagingdir}/. %{buildroot}/
           |
           |%files
           |$filesSection
           |""".stripMargin
      val specPath = work.resolve(s"${meta.name}.spec")
      Files.write(specPath, spec.getBytes(UTF_8))
      val rpmDir = work.resolve("rpms")
      val code = runInherited(List(
        "rpmbuild", "-bb", "--quiet", "--target", s"$arch-linux",
        "--define", s"_topdir ${work.resolve("top")}",
        "--define", s"_rpmdir $rpmDir",
        "--define", s"stagingdir $staging",
        "--define", "_binary_payload w9.gzdio",
        specPath.toString
      ))
      if code != 0 then fail(s"rpmbuild failed (exit $code)")
      val built = findFiles(rpmDir.toFile, ".rpm").headOption.getOrElse(fail("rpmbuild produced no .rpm"))
      val out = outDir.resolve(built.getName)
      Files.write(out, Files.readAllBytes(built.toPath))
      out
    finally deleteTree(work.toFile)

  private def tarballName(meta: Meta, host: Host): String = s"${meta.name}-${meta.version}-${host.triple}"

  private val DocFiles = List("README.md", "README", "LICENSE", "LICENSE.md", "LICENSE.txt", "NOTICE")

  /** `<name>-<version>-<triple>.tar.gz` (cargo-dist layout: one top directory
   *  holding the executable plus README/LICENSE) and its `.sha256`. */
  def buildTar(meta: Meta, host: Host, binary: Path, outDir: Path): Path =
    val top = tarballName(meta, host)
    val docs = DocFiles.map(Paths.get(_)).filter(Files.isRegularFile(_)).map { p =>
      Entry(s"$top/${p.getFileName}", false, Mode644, Files.readAllBytes(p))
    }
    val entries =
      Entry(top, true, Mode755) ::
        Entry(s"$top/${meta.name}", false, Mode755, Files.readAllBytes(binary)) :: docs
    val bytes = gzip(tar(entries))
    val out = outDir.resolve(s"$top.tar.gz")
    Files.write(out, bytes)
    Files.write(outDir.resolve(s"$top.tar.gz.sha256"), s"${sha256Hex(bytes)}  ${out.getFileName}\n".getBytes(UTF_8))
    out

  def buildDocker(meta: Meta, host: Host, entries: List[Entry], outDir: Path): Path =
    requireLinux(host, "docker")
    val ctx = outDir.resolve("docker")
    deleteTree(ctx.toFile)
    writeTree(entries, ctx.resolve("rootfs"))
    val base = meta.dockerBase.getOrElse(if host.libc == "musl" then "alpine:latest" else "debian:stable-slim")
    def label(k: String, v: String) = s"""LABEL org.opencontainers.image.$k="${v.replace("\\", "\\\\").replace("\"", "\\\"")}""""
    val labels = List(
      Some(label("title", meta.name)),
      Some(label("version", meta.version)),
      Some(label("description", meta.description.linesIterator.nextOption().getOrElse(meta.name))),
      meta.license.map(label("licenses", _)),
      meta.homepage.map(label("url", _))
    ).flatten
    val dockerfile =
      s"""FROM $base
         |COPY rootfs/ /
         |${labels.mkString("\n")}
         |ENTRYPOINT ["/usr/bin/${meta.name}"]
         |""".stripMargin
    Files.write(ctx.resolve("Dockerfile"), dockerfile.getBytes(UTF_8))
    val image = meta.dockerImage.getOrElse(meta.name)
    val tagVersion = meta.version.stripPrefix("v").map(c => if c.isLetterOrDigit && c < 128 || c == '.' || c == '-' || c == '_' then c else '-')
    val tags = List(s"$image:$tagVersion", s"$image:latest")
    List("docker", "podman").find(haveTool) match
      case None =>
        println(s"No docker/podman found; build context written. Build it with:\n  docker build ${tags.flatMap(t => List("-t", t)).mkString(" ")} $ctx")
      case Some(engine) =>
        val code = runInherited(List(engine, "build") ++ tags.flatMap(t => List("-t", t)) ++ List(ctx.toString))
        if code != 0 then fail(s"$engine build failed (exit $code)")
        println(s"Built image ${tags.mkString(", ")}")
    ctx.resolve("Dockerfile")

  private def formulaClass(name: String): String =
    name.split("[^A-Za-z0-9]+").filter(_.nonEmpty).map(s => s.head.toUpper + s.tail).mkString

  /** Homebrew formula pointing at every `<name>-<version>-<triple>.tar.gz`
   *  found in `outDir` (so a CI matrix can drop each leg's tarball there and
   *  run `--format brew` once at the end). */
  def buildBrew(meta: Meta, outDir: Path): Path =
    val base = meta.releaseUrl.getOrElse(
      fail("--format brew needs the URL the tarballs will be published at: --release-url <url> or //> using packageReleaseUrl \"...\"")
    )
    val prefix = s"${meta.name}-${meta.version}-"
    val tarballs = Option(outDir.toFile.listFiles()).toList.flatten
      .filter(f => f.getName.startsWith(prefix) && f.getName.endsWith(".tar.gz"))
      .sortBy(_.getName)
    def block(f: java.io.File): Option[(String, String, String)] =
      val triple = f.getName.stripPrefix(prefix).stripSuffix(".tar.gz")
      val cpu = triple.takeWhile(_ != '-') match
        case "aarch64" => Some("on_arm")
        case "x86_64" => Some("on_intel")
        case _ => None
      val os =
        if triple.endsWith("apple-darwin") then Some("on_macos")
        else if triple.endsWith("linux-gnu") then Some("on_linux")
        else None
      for o <- os; c <- cpu yield (o, c, triple)
    val entries = tarballs.flatMap(f => block(f).map { case (os, cpu, _) => (os, cpu, f) })
    if entries.isEmpty then fail(s"no $prefix*.tar.gz for macOS/Linux (glibc) found in $outDir -- build with --format tar first")
    val body = entries.groupBy(_._1).toList.sortBy(_._1).map { case (os, es) =>
      val cpus = es.sortBy(_._2).map { case (_, cpu, f) =>
        s"""    $cpu do
           |      url "$base/${f.getName}"
           |      sha256 "${sha256Hex(Files.readAllBytes(f.toPath))}"
           |    end""".stripMargin
      }.mkString("\n")
      s"  $os do\n$cpus\n  end"
    }.mkString("\n\n")
    val formula =
      s"""class ${formulaClass(meta.name)} < Formula
         |  desc "${meta.description.linesIterator.nextOption().getOrElse(meta.name).replace("\"", "\\\"")}"
         |${meta.homepage.orElse(Some(base)).map(h => s"""  homepage "$h"\n""").get}  version "${meta.version.stripPrefix("v")}"
         |${meta.license.map(l => s"""  license "$l"\n""").getOrElse("")}
         |$body
         |
         |  def install
         |    bin.install "${meta.name}"
         |  end
         |
         |  test do
         |    assert_predicate bin/"${meta.name}", :executable?
         |  end
         |end
         |""".stripMargin
    val out = outDir.resolve(s"${meta.name}.rb")
    Files.write(out, formula.getBytes(UTF_8))
    out

  // ---------------------------------------------------------------------
  // Entry point
  // ---------------------------------------------------------------------

  /** Builds every requested format from the already-linked `binary` into
   *  `outDir`. `brew` always runs last (it indexes the tarballs). */
  def packageAll(formats: List[String], binary: Path, meta: Meta, outDir: Path, quiet: Boolean): Unit =
    val host = detectHost()
    Files.createDirectories(outDir)
    def wrote(p: Path): Unit =
      if !quiet then println(s"Wrote ${p.toAbsolutePath.normalize}")
    lazy val entries = payloadEntries(meta, binary)
    val ordered = formats.filter(_ != "brew") ++ formats.filter(_ == "brew")
    ordered.foreach {
      case "tar" => wrote(buildTar(meta, host, binary, outDir))
      case "deb" => wrote(buildDeb(meta, host, entries, outDir))
      case "rpm" => wrote(buildRpm(meta, host, entries, outDir))
      case "docker" => wrote(buildDocker(meta, host, entries, outDir))
      case "brew" =>
        if !formats.contains("tar") then wrote(buildTar(meta, host, binary, outDir))
        wrote(buildBrew(meta, outDir))
      case other => fail(s"unknown package format '$other'")
    }
