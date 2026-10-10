// `scalino sysroot build <triple>`: assembles a cross-compilation sysroot on the
// user's machine from upstream packages (scalino publishes none): musl (Debian
// 13's musl-dev) and gnu (Debian 11's glibc), both with Debian 12's prebuilt
// compiler-rt, all from .debs; apple (Darwin headers from Zig's source tarball +
// a generated libSystem stub) and windows (llvm-mingw's data dirs). Nothing is
// compiled. Versions and checksums come from versions.env (via
// BuildInfo.sysrootPins).
//
// Also the addons: static C libraries (OpenSSL's libcrypto) a classpath dependency
// links through `@link`, installed next to the sysroots (see `buildAddon`).
//
// Needs only `curl` and `tar` (with xz support) on PATH. Downloads are kept in
// <sysroots>/.sources, or $SCALINO_SYSROOT_SOURCES, so a directory pre-seeded
// with the upstream files works offline.

import java.nio.file.{Files, Path, Paths, StandardCopyOption}
import java.nio.charset.StandardCharsets.UTF_8
import ScalinoCli.{die, dist, runInherited, sha256Hex}

object SysrootBuild:

  val Buildable: List[String] = Sysroot.Supported

  private def snapshot = s"https://snapshot.debian.org/archive/debian/${pin("DEBIAN_SNAPSHOT")}/pool/main"

  private def pin(k: String): String =
    BuildInfo.sysrootPins.getOrElse(k, die(s"sysroot build: internal error, no pinned $k"))

  private def sources: Path =
    Option(System.getenv("SCALINO_SYSROOT_SOURCES")).filter(_.nonEmpty).map(Paths.get(_))
      .getOrElse(Sysroot.cacheRoot.resolve(".sources"))

  /** `name` from `url`, checked against `sha256`; cached in `sources`. */
  private def download(url: String, name: String, sha256: String, headers: List[String] = Nil): Path =
    Files.createDirectories(sources)
    val f = sources.resolve(name)
    if Files.exists(f) then
      if sha256Hex(Files.readAllBytes(f)) == sha256 then return f
      Files.delete(f)
    val part = sources.resolve(name + ".part")
    println(s"downloading $url")
    if runInherited(List("curl", "-fsSL") ++ headers.flatMap(h => List("-H", h)) ++ List("-o", part.toString, url)) != 0 then
      die(s"sysroot build: download failed: $url (or put $name in $sources)")
    val actual = sha256Hex(Files.readAllBytes(part))
    if actual != sha256 then
      Files.delete(part)
      die(s"sysroot build: checksum mismatch for $url (expected $sha256, got $actual)")
    Files.move(part, f, StandardCopyOption.REPLACE_EXISTING)
    f

  private def tar(args: String*): Unit =
    if runInherited("tar" :: args.toList) != 0 then die(s"sysroot build: `tar ${args.mkString(" ")}` failed")

  /** Unpacks the data.tar.* member of a Debian package into `into`. */
  private def unpackDeb(deb: Path, into: Path): Unit =
    val b = Files.readAllBytes(deb)
    def ascii(off: Int, len: Int) = new String(b, off, len, "US-ASCII").trim
    if ascii(0, 8) != "!<arch>" then die(s"sysroot build: $deb is not a .deb")
    var i = 8
    while i + 60 <= b.length do
      val name = ascii(i, 16).stripSuffix("/")
      val size = ascii(i + 48, 10).toInt
      val start = i + 60
      if name.startsWith("data.tar") then
        val member = Files.createTempFile(into.getParent, "data", name.stripPrefix("data"))
        try
          Files.write(member, java.util.Arrays.copyOfRange(b, start, start + size))
          Files.createDirectories(into)
          tar("-xf", member.toString, "-C", into.toString)
        finally Files.deleteIfExists(member)
        return
      i = start + size + (size % 2)
    die(s"sysroot build: no data.tar in $deb")

  private def copy(from: Path, to: Path): Unit =
    Files.createDirectories(to.getParent)
    Files.copy(from, to, StandardCopyOption.REPLACE_EXISTING)

  private def addLicense(out: Path, from: Path, name: String): Unit =
    copy(from, out.resolve("LICENSES").resolve(name))

  private def writeReadme(out: Path, triple: String, body: String, what: String = "sysroot"): Unit =
    val dir = Files.createDirectories(out.resolve("LICENSES"))
    val text =
      s"""scalino $what for $triple (built by `scalino sysroot build` from scalino ${BuildInfo.scalinoVersion}).
         |
         |This is NOT part of scalino's own Apache-2.0 licensed code: it is third-party software,
         |each component under its own licence, whose text is in this directory's LICENSES/.
         |
         |$body
         |""".stripMargin
    Files.write(dir.resolve("README"), text.getBytes(UTF_8))

  private def list(dir: Path): List[Path] =
    val s = Files.list(dir)
    try
      val b = List.newBuilder[Path]
      s.forEach(p => b += p)
      b.result().sortBy(_.toString)
    finally s.close()

  private def walk(dir: Path): List[Path] =
    val s = Files.walk(dir)
    try
      val b = List.newBuilder[Path]
      s.forEach(p => b += p)
      b.result()
    finally s.close()

  private def rm(p: Path): Unit = if Files.exists(p, java.nio.file.LinkOption.NOFOLLOW_LINKS) then ScalinoCli.deleteRecursively(p)

  /** Installs the sysroot for `triple` into the cache, building it from upstream. */
  def build(triple: String, force: Boolean): Path =
    if !Buildable.contains(triple) then die(s"sysroot build: unknown target '$triple', one of: ${Buildable.mkString(", ")}")
    val dest = Sysroot.cacheRoot.resolve(triple)
    if Files.isDirectory(dest) && !force then
      println(s"sysroot $triple already installed at $dest (--force to rebuild)")
      return dest
    Files.createDirectories(Sysroot.cacheRoot)
    val work = Files.createTempDirectory(Sysroot.cacheRoot, s".build-$triple-")
    try
      val out = work.resolve(s"scalino-sysroot-$triple")
      Files.createDirectories(out)
      val arch = triple.takeWhile(_ != '-')
      if triple.endsWith("linux-gnu") then buildGnu(triple, arch, work, out)
      else if triple.endsWith("linux-musl") then buildMusl(triple, arch, work, out)
      else if triple.endsWith("apple-darwin") then buildApple(triple, work, out)
      else buildWindows(triple, arch, work, out)
      rm(dest)
      Files.move(out, dest)
      println(s"installed sysroot $triple -> $dest")
      dest
    finally ScalinoCli.deleteRecursively(work)

  /** compiler-rt's builtins and crtbegin/crtend, prebuilt by Debian, into
   *  `out`/resource (where LinkDriver looks). Returns the package version. */
  private def installCompilerRt(arch: String, work: Path, out: Path): String =
    val debArch = if arch == "x86_64" then "amd64" else "arm64"
    val ver = pin("LIBCLANG_RT_DEB_VERSION")
    val deb = s"libclang-rt-14-dev_${ver}_$debArch.deb"
    val dir = work.resolve("deb-rt")
    unpackDeb(download(s"$snapshot/l/llvm-toolchain-14/$deb", deb, pin(s"LIBCLANG_RT_${debArch.toUpperCase}_SHA256")), dir)
    val lib = list(dir.resolve("usr/lib/llvm-14/lib/clang")).headOption.map(_.resolve("lib/linux"))
      .filter(Files.isDirectory(_)).getOrElse(die(s"sysroot build: no compiler-rt in $deb"))
    for f <- List(s"libclang_rt.builtins-$arch.a", s"clang_rt.crtbegin-$arch.o", s"clang_rt.crtend-$arch.o") do
      copy(lib.resolve(f), out.resolve("resource/lib/linux").resolve(f))
    addLicense(out, dir.resolve("usr/share/doc/libclang-rt-14-dev/copyright"), "debian-libclang-rt-14-dev-copyright")
    ver

  // ---- musl ----------------------------------------------------------------

  private def buildMusl(triple: String, arch: String, work: Path, out: Path): Unit =
    val debArch = if arch == "x86_64" then "amd64" else "arm64"
    val ver = pin("MUSL_DEB_VERSION")
    val deb = s"musl-dev_${ver}_$debArch.deb"
    unpackDeb(download(s"$snapshot/m/musl/$deb", deb, pin(s"MUSL_DEV_${debArch.toUpperCase}_SHA256")), out)
    val rtVer = installCompilerRt(arch, work, out)
    addLicense(out, out.resolve("usr/share/doc/musl-dev/copyright"), "debian-musl-dev-copyright")
    // Debian keeps musl in multiarch subdirs, which clang only searches for gnu
    // triples: flatten to usr/include and usr/lib.
    for sub <- List("usr/include", "usr/lib") do
      val multiarch = out.resolve(sub).resolve(s"$arch-linux-musl")
      if Files.isDirectory(multiarch) then
        for p <- list(multiarch) do Files.move(p, out.resolve(sub).resolve(p.getFileName))
        Files.delete(multiarch)
    // musl-gcc wrapper, docs: the link never reads them.
    for d <- List("usr/bin", "usr/share") do rm(out.resolve(d))
    writeReadme(out, triple,
      s"""Components (Debian 13 packages from snapshot.debian.org, files moved from the multiarch
         |directories to usr/include and usr/lib, otherwise unmodified):
         |  musl-dev $ver   musl libc (MIT; a few files under other permissive licences, see
         |                  debian-musl-dev-copyright), linked statically into binaries built against it.
         |                  Source: https://musl.libc.org, https://tracker.debian.org/pkg/musl
         |libclang-rt-14-dev $rtVer  compiler-rt builtins and crtbegin/crtend (resource/): Apache-2.0 with LLVM
         |                  Exceptions, see debian-libclang-rt-14-dev-copyright. Source: https://github.com/llvm/llvm-project""".stripMargin)

  // ---- gnu -----------------------------------------------------------------

  private def buildGnu(triple: String, arch: String, work: Path, out: Path): Unit =
    val debArch = if arch == "x86_64" then "amd64" else "arm64"
    val glibc = pin("GLIBC_DEB_VERSION")
    val linux = pin("LINUX_LIBC_DEV_VERSION")
    val key = debArch.toUpperCase
    val base = "https://archive.debian.org/debian/pool/main"
    val debs = List(
      (s"libc6_${glibc}_$debArch.deb", s"$base/g/glibc", pin(s"GLIBC_LIBC6_${key}_SHA256")),
      (s"libc6-dev_${glibc}_$debArch.deb", s"$base/g/glibc", pin(s"GLIBC_LIBC6_DEV_${key}_SHA256")),
      (s"linux-libc-dev_${linux}_$debArch.deb", s"$base/l/linux", pin(s"LINUX_LIBC_DEV_${key}_SHA256")))
    for (f, dir, sha) <- debs do unpackDeb(download(s"$dir/$f", f, sha), out)

    val rtVer = installCompilerRt(arch, work, out)

    // Debian's dev symlinks are absolute (/lib/...): make them relative so they
    // resolve inside the sysroot.
    for p <- walk(out) if Files.isSymbolicLink(p) do
      val target = Files.readSymbolicLink(p)
      if target.isAbsolute then
        val inside = out.resolve(target.toString.dropWhile(_ == '/'))
        Files.delete(p)
        Files.createSymbolicLink(p, p.getParent.relativize(inside))
    for pkg <- List("libc6", "libc6-dev", "linux-libc-dev") do
      val c = out.resolve(s"usr/share/doc/$pkg/copyright")
      if Files.exists(c) then addLicense(out, c, s"debian-$pkg-copyright")
    for d <- List("etc", "usr/share", "usr/lib/x86_64-linux-gnu/gconv", "usr/lib/aarch64-linux-gnu/gconv") do rm(out.resolve(d))
    val dsc = s"glibc_${glibc.takeWhile(_ != '+')}+${glibc.dropWhile(_ != '+').drop(1)}.dsc"
    writeReadme(out, triple,
      s"""Components (unmodified Debian 11 packages from archive.debian.org):
         |  libc6, libc6-dev $glibc   the GNU C Library, LGPL-2.1-or-later (some files under other
         |                                         licences, see debian-*-copyright). Binaries built against this
         |                                         link it dynamically.
         |  linux-libc-dev $linux Linux kernel UAPI headers, GPL-2.0 WITH Linux-syscall-note.
         |Corresponding source, as the LGPL asks for (written offer; valid for as long as these are served):
         |  https://archive.debian.org/debian/pool/main/g/glibc/$dsc
         |  https://archive.debian.org/debian/pool/main/g/glibc/  (glibc_2.31.orig.tar.xz, glibc_$glibc.debian.tar.xz)
         |  https://archive.debian.org/debian/pool/main/l/linux/  (linux $linux)
         |libclang-rt-14-dev $rtVer  compiler-rt builtins and crtbegin/crtend (resource/): Apache-2.0 with LLVM
         |                                         Exceptions, see debian-libclang-rt-14-dev-copyright. Source: https://github.com/llvm/llvm-project""".stripMargin)

  // ---- apple ---------------------------------------------------------------

  private val ZlibSymbols =
    ("adler32 adler32_combine adler32_z compress compress2 compressBound crc32 crc32_combine crc32_z deflate deflateBound " +
     "deflateCopy deflateEnd deflateGetDictionary deflateInit2_ deflateInit_ deflateParams deflatePending deflatePrime " +
     "deflateReset deflateResetKeep deflateSetDictionary deflateSetHeader deflateTune get_crc_table gzbuffer gzclearerr " +
     "gzclose gzclose_r gzclose_w gzdirect gzdopen gzeof gzerror gzflush gzfread gzfwrite gzgetc gzgets gzoffset gzopen " +
     "gzopen64 gzprintf gzputc gzputs gzread gzrewind gzseek gzseek64 gzsetparams gztell gztell64 gzungetc gzvprintf gzwrite " +
     "inflate inflateBack inflateBackEnd inflateBackInit_ inflateCopy inflateEnd inflateGetDictionary inflateGetHeader " +
     "inflateInit2_ inflateInit_ inflateMark inflatePrime inflateReset inflateReset2 inflateResetKeep inflateSetDictionary " +
     "inflateSync inflateSyncPoint inflateUndermine inflateValidate uncompress uncompress2 zError zlibCompileFlags zlibVersion"
    ).split(' ').toList

  private def readLines(p: Path): List[String] =
    if !Files.exists(p) then die(s"sysroot build: $p is missing (a scalino install without share/sysroot?)")
    new String(Files.readAllBytes(p), UTF_8).linesIterator.toList

  /** libSystem.tbd: the symbol names the Darwin headers declare, see
   *  build/gen-libsystem-symbols.py. Same output as the script's. */
  private def libSystemTbd(): String =
    val data = Paths.get(dist).resolve("share").resolve("sysroot")
    def load(arch: String) = readLines(data.resolve(s"libsystem-symbols-$arch.txt")).map(_.trim).filter(_.startsWith("_")).toSet
    val extra = readLines(data.resolve("libsystem-symbols-extra.txt")).map(_.trim).filter(l => l.nonEmpty && !l.startsWith("#")).toSet
    val x86 = load("x86_64") ++ extra
    val arm = load("arm64") ++ extra
    val groups = List(
      ("x86_64-macos, arm64-macos", x86 & arm), ("x86_64-macos", x86 -- arm), ("arm64-macos", arm -- x86))
    val sb = new StringBuilder
    sb ++= "--- !tapi-tbd\ntbd-version:     4\ntargets:         [ x86_64-macos, arm64-macos ]\n"
    sb ++= "install-name:    '/usr/lib/libSystem.B.dylib'\ncurrent-version: 1351\ncompatibility-version: 1\nexports:\n"
    for (targets, syms) <- groups if syms.nonEmpty do
      sb ++= s"  - targets:         [ $targets ]\n    symbols:         [ ${syms.toList.sorted.mkString(", ")} ]\n"
    sb ++= "...\n"
    sb.toString

  private def buildApple(triple: String, work: Path, out: Path): Unit =
    val zig = pin("ZIG_VERSION")
    val tarball = download(s"https://ziglang.org/download/$zig/zig-$zig.tar.xz", s"zig-$zig.tar.xz", pin("ZIG_SHA256"))
    val b = Files.createDirectories(work.resolve("zig"))
    tar("-xf", tarball.toString, "-C", b.toString, "--strip-components=1", s"zig-$zig/LICENSE", s"zig-$zig/lib/libc/include/any-darwin-any")
    val lib = Files.createDirectories(out.resolve("usr/lib"))
    Files.move(b.resolve("lib/libc/include/any-darwin-any"), out.resolve("usr/include"))
    val tbd = lib.resolve("libSystem.tbd")
    Files.write(tbd, libSystemTbd().getBytes(UTF_8))
    // Scala Native links -lpthread -ldl -lm (-lc): on macOS those are all libSystem.
    for l <- List("c", "m", "pthread", "dl") do Files.copy(tbd, lib.resolve(s"lib$l.tbd"))
    // -lz (java.util.zip): zlib's public API, a stub for /usr/lib/libz.1.dylib.
    val z =
      s"""--- !tapi-tbd
         |tbd-version:     4
         |targets:         [ x86_64-macos, arm64-macos ]
         |install-name:    '/usr/lib/libz.1.dylib'
         |current-version: 1.2.12
         |exports:
         |  - targets:         [ x86_64-macos, arm64-macos ]
         |    symbols:         [ ${ZlibSymbols.map("_" + _).mkString(", ")} ]
         |...
         |""".stripMargin
    Files.write(lib.resolve("libz.tbd"), z.getBytes(UTF_8))
    addLicense(out, b.resolve("LICENSE"), "zig-LICENSE-MIT")
    writeReadme(out, triple,
      s"""Components:
         |  usr/include/**   Darwin libc headers (Apple open source: mostly the Apple Public Source License,
         |                   some BSD-style), unmodified, from the Zig project's lib/libc (zig-$zig,
         |                   MIT, see zig-LICENSE-MIT). Each header keeps its own licence notice.
         |  usr/lib/libSystem.tbd, libc/libm/libpthread/libdl.tbd
         |                   generated by scalino (build/gen-libsystem-symbols.py): the names of the functions
         |                   and variables those headers declare, as a linker stub for
         |                   /usr/lib/libSystem.B.dylib. Symbol names only; not taken from Apple's SDK.
         |  usr/lib/libz.tbd generated by scalino from zlib's public API (symbol names only).
         |Source of the headers: https://github.com/apple-oss-distributions (Libc, xnu, ...).""".stripMargin)

  // ---- windows -------------------------------------------------------------

  private def buildWindows(triple: String, arch: String, work: Path, out: Path): Unit =
    val ver = pin("LLVM_MINGW_VERSION")
    val lm = s"llvm-mingw-$ver-ucrt-ubuntu-22.04-x86_64"
    val tarball = download(s"https://github.com/mstorsjo/llvm-mingw/releases/download/$ver/$lm.tar.xz", s"$lm.tar.xz", pin("LLVM_MINGW_SHA256"))
    val mw = s"$arch-w64-mingw32"
    val b = Files.createDirectories(work.resolve("llvm-mingw"))
    tar("-xf", tarball.toString, "-C", b.toString, s"$lm/LICENSE.TXT", s"$lm/generic-w64-mingw32", s"$lm/$mw", s"$lm/lib/clang")
    val root = b.resolve(lm)
    Files.move(root.resolve("generic-w64-mingw32"), out.resolve("generic-w64-mingw32"))
    Files.move(root.resolve(mw), out.resolve(mw))
    val builtins = list(root.resolve("lib/clang")).map(_.resolve(s"lib/windows/libclang_rt.builtins-$arch.a")).find(Files.exists(_))
      .getOrElse(die(s"sysroot build: no compiler-rt builtins for $arch in $lm"))
    copy(builtins, out.resolve("resource/lib/windows").resolve(builtins.getFileName))
    addLicense(out, root.resolve("LICENSE.TXT"), "llvm-LICENSE.TXT")
    // The mingw-w64 runtime's licensing files, written for binaries statically linked against it.
    val tag = pin("MINGW_W64_LICENSE_TAG")
    for (f, sha) <- List("COPYING.MinGW-w64-runtime" -> pin("MINGW_W64_RUNTIME_LICENSE_SHA256"), "COPYING.MinGW-w64" -> pin("MINGW_W64_LICENSE_SHA256")) do
      val p = download(s"https://raw.githubusercontent.com/mingw-w64/mingw-w64/$tag/$f/$f.txt", s"$f-$tag.txt", sha)
      addLicense(out, p, s"mingw-w64-$f.txt")
    writeReadme(out, triple,
      s"""Components (from the llvm-mingw $ver release, https://github.com/mstorsjo/llvm-mingw):
         |  $mw/, generic-w64-mingw32/   mingw-w64 headers, CRT and import libraries (ZPL-2.1, public domain and
         |                               BSD/MIT-style, see mingw-w64-COPYING.MinGW-w64*.txt; texts from mingw-w64 $tag),
         |                               winpthreads (MIT-style, same files), libc++, libc++abi and libunwind
         |                               (Apache-2.0 with LLVM Exceptions, see llvm-LICENSE.TXT).
         |  resource/lib/windows/        compiler-rt builtins (Apache-2.0 with LLVM Exceptions).
         |Source: https://github.com/mstorsjo/llvm-mingw, https://www.mingw-w64.org, https://github.com/llvm/llvm-project""".stripMargin)
    // shared-library import stubs and static libs we never link
    rm(out.resolve(mw).resolve("bin"))
    rm(out.resolve(mw).resolve("share"))
    rm(out.resolve("generic-w64-mingw32").resolve("share"))

  // ---- lld -------------------------------------------------------------------

  /** An `ld.lld` that runs on this host, for cross links when the system has none: taken from
   *  llvm-mingw's host tarball (statically linked, one binary for every target format) and kept
   *  in the sysroot cache. None when llvm-mingw has no build for this host. */
  def hostLld(host: Packaging.Host): Option[Path] =
    val ver = pin("LLVM_MINGW_VERSION")
    val (suffix, sha) = (host.os, host.arch, host.libc) match
      case ("linux", "x86_64", "gnu") => ("ubuntu-22.04-x86_64", pin("LLVM_MINGW_SHA256"))
      case ("linux", "aarch64", "gnu") => ("ubuntu-22.04-aarch64", pin("LLVM_MINGW_LINUX_AARCH64_SHA256"))
      case ("macos", _, _) => ("macos-universal", pin("LLVM_MINGW_MACOS_UNIVERSAL_SHA256"))
      case _ => return None
    val home = Sysroot.cacheRoot.resolve("tools").resolve(s"lld-llvm-mingw-$ver-$suffix")
    val dest = home.resolve("bin").resolve("ld.lld")
    if Files.isExecutable(dest) then return Some(dest)
    val lm = s"llvm-mingw-$ver-ucrt-$suffix"
    val tarball = download(s"https://github.com/mstorsjo/llvm-mingw/releases/download/$ver/$lm.tar.xz", s"$lm.tar.xz", sha)
    Files.createDirectories(Sysroot.cacheRoot)
    val work = Files.createTempDirectory(Sysroot.cacheRoot, ".build-lld-")
    try
      // lld links the shared libLLVM next to it (rpath ../lib); its names carry the LLVM version
      val (_, listing) = ScalinoCli.runCaptureStdout(List("tar", "-tf", tarball.toString))
      val libLlvm = listing.linesIterator.map(_.trim).filter(l => l.startsWith(s"$lm/lib/libLLVM") && !l.endsWith("/")).toList
      if libLlvm.isEmpty then die(s"sysroot build: no libLLVM in $lm")
      tar((List("-xf", tarball.toString, "-C", work.toString, "--strip-components=1", s"$lm/bin/lld", s"$lm/LICENSE.TXT") ++ libLlvm)*)
      // lld picks its flavor from argv[0], so the extracted `lld` is installed under the name ld.lld
      Files.move(work.resolve("bin/lld"), work.resolve("bin/ld.lld"))
      Files.move(work.resolve("LICENSE.TXT"), work.resolve("llvm-LICENSE.TXT"))
      rm(home)
      Files.createDirectories(home.getParent)
      Files.move(work, home)
      println(s"installed lld -> $dest")
      Some(dest)
    finally ScalinoCli.deleteRecursively(work)

  // ---- addons --------------------------------------------------------------

  /** Installs static `lib` (see `Sysroot.Addons`) for `triple` into the cache:
   *  the .a files in `lib/`, `include/`, `LICENSES/`, and `linkflags` (system libraries the
   *  static archives need, one line) when there are any. */
  def buildAddon(lib: String, triple: String, force: Boolean): Path =
    if !Sysroot.Addons.contains(lib) then die(s"sysroot build: unknown library '$lib', one of: ${Sysroot.Addons.keys.toList.sorted.mkString(", ")}")
    if !Buildable.contains(triple) then die(s"sysroot build: unknown target '$triple', one of: ${Buildable.mkString(", ")}")
    val dest = Sysroot.addonDir(lib, triple)
    if Files.isDirectory(dest) && !force then return dest
    Files.createDirectories(Sysroot.cacheRoot)
    val work = Files.createTempDirectory(Sysroot.cacheRoot, s".build-$lib-$triple-")
    try
      val out = Files.createDirectories(work.resolve(s"scalino-$lib-$triple"))
      val arch = triple.takeWhile(_ != '-')
      lib match
        case "openssl" => installOpenssl(triple, arch, work, out)
        case "idn2" => installIdn2(triple, arch, work, out)
        case "s2n" => installS2n(triple, work, out)
        case "curl" => installCurl(triple, work, out)
      rm(dest)
      Files.createDirectories(dest.getParent)
      Files.move(out, dest)
      println(s"installed static $lib for $triple -> $dest")
      dest
    finally ScalinoCli.deleteRecursively(work)

  private def copyTree(from: Path, to: Path): Unit =
    for p <- walk(from) if Files.isRegularFile(p) do copy(p, to.resolve(from.relativize(p).toString))

  private def installOpenssl(triple: String, arch: String, work: Path, out: Path): Unit =
    val ex = Files.createDirectories(work.resolve("extracted"))
    def libs(dir: Path): Unit = for l <- List("libcrypto.a", "libssl.a") do copy(dir.resolve(l), out.resolve("lib").resolve(l))
    def linkFlags(flags: String): Unit = Files.write(out.resolve("linkflags"), (flags + "\n").getBytes(UTF_8))
    val (components, source) =
      if triple.endsWith("linux-musl") then
        val ver = pin("OPENSSL_ALPINE_VERSION")
        val key = arch.toUpperCase
        for (pkg, k) <- List("openssl-libs-static" -> "STATIC", "openssl-dev" -> "DEV") do
          val apk = download(s"https://dl-cdn.alpinelinux.org/alpine/${pin("OPENSSL_ALPINE_BRANCH")}/main/$arch/$pkg-$ver.apk",
            s"$pkg-$ver-$arch.apk", pin(s"OPENSSL_ALPINE_${k}_${key}_SHA256"))
          // An .apk is concatenated gzip'd tars (signature, control, data); the first two only add dotfiles.
          tar("-xzf", apk.toString, "-C", ex.toString)
        libs(ex.resolve("usr/lib"))
        copyTree(ex.resolve("usr/include/openssl"), out.resolve("include/openssl"))
        val lv = pin("OPENSSL_ALPINE_LICENSE_VERSION")
        addLicense(out, download(s"https://raw.githubusercontent.com/openssl/openssl/openssl-$lv/LICENSE.txt", s"openssl-LICENSE-$lv.txt",
          pin("OPENSSL_ALPINE_LICENSE_SHA256")), "openssl-LICENSE.txt")
        (s"openssl-libs-static, openssl-dev $ver (Alpine ${pin("OPENSSL_ALPINE_BRANCH")}, OpenSSL $lv)  libcrypto.a, libssl.a built for musl, and headers: Apache-2.0, see openssl-LICENSE.txt.",
         "https://pkgs.alpinelinux.org/package/" + pin("OPENSSL_ALPINE_BRANCH") + "/main/" + arch + "/openssl, https://www.openssl.org")
      else if triple.endsWith("linux-gnu") then
        val ver = pin("OPENSSL_DEBIAN_VERSION")
        val debArch = if arch == "x86_64" then "amd64" else "arm64"
        val deb = s"libssl-dev_${ver}_$debArch.deb"
        unpackDeb(download(s"$snapshot/o/openssl/$deb", deb, pin(s"OPENSSL_DEBIAN_${debArch.toUpperCase}_SHA256")), ex)
        val ma = s"$arch-linux-gnu"
        libs(ex.resolve("usr/lib").resolve(ma))
        copyTree(ex.resolve("usr/include/openssl"), out.resolve("include/openssl"))
        // configuration.h and opensslconf.h differ per architecture
        copyTree(ex.resolve("usr/include").resolve(ma).resolve("openssl"), out.resolve("include/openssl"))
        addLicense(out, ex.resolve("usr/share/doc/libssl-dev/copyright"), "debian-libssl-dev-copyright")
        (s"libssl-dev $ver (Debian 12, OpenSSL 3.0)  libcrypto.a, libssl.a and headers, unmodified: Apache-2.0, see debian-libssl-dev-copyright.",
         "https://tracker.debian.org/pkg/openssl, https://www.openssl.org")
      else if triple.endsWith("apple-darwin") then
        val ver = pin("OPENSSL_BREW_VERSION")
        val (key, tag) = if arch == "x86_64" then ("X86_64", "sonoma") else ("ARM64", "arm64_sonoma")
        val sha = pin(s"OPENSSL_BREW_${key}_SHA256")
        // ghcr.io serves Homebrew's public bottles to an anonymous bearer token ("QQ==")
        val bottle = download(s"https://ghcr.io/v2/homebrew/core/openssl/3/blobs/sha256:$sha", s"openssl-brew-$ver-$tag.tar.gz", sha,
          List("Authorization: Bearer QQ=="))
        tar("-xzf", bottle.toString, "-C", ex.toString, "--strip-components=2")
        libs(ex.resolve("lib"))
        copyTree(ex.resolve("include/openssl"), out.resolve("include/openssl"))
        addLicense(out, ex.resolve("LICENSE.txt"), "openssl-LICENSE.txt")
        (s"openssl@3 $ver (Homebrew bottle $tag)  libcrypto.a, libssl.a and headers, unmodified: Apache-2.0, see openssl-LICENSE.txt.",
         "https://formulae.brew.sh/formula/openssl@3, https://www.openssl.org")
      else
        val ver = pin("OPENSSL_MSYS2_VERSION")
        val (env, pkgArch) = if arch == "x86_64" then ("clang64", "x86_64") else ("clangarm64", "aarch64")
        val f = s"mingw-w64-clang-$pkgArch-openssl-$ver-any.pkg.tar.zst"
        val pkg = download(s"https://repo.msys2.org/mingw/$env/$f", f, pin(s"OPENSSL_MSYS2_${pkgArch.toUpperCase}_SHA256"))
        // zstd: bsdtar reads it itself, GNU tar needs the zstd program
        tar("-xf", pkg.toString, "-C", ex.toString, s"$env/lib/libcrypto.a", s"$env/lib/libssl.a", s"$env/include/openssl", s"$env/share/licenses/openssl")
        libs(ex.resolve(env).resolve("lib"))
        // Windows libraries are often @link'ed by their file name (scala-native-crypto: "libcrypto"),
        // which makes lld look for liblibcrypto.a
        for l <- List("crypto", "ssl") do copy(out.resolve(s"lib/lib$l.a"), out.resolve(s"lib/liblib$l.a"))
        copyTree(ex.resolve(env).resolve("include/openssl"), out.resolve("include/openssl"))
        addLicense(out, ex.resolve(env).resolve("share/licenses/openssl/LICENSE"), "openssl-LICENSE.txt")
        // what libcrypto.a calls into: sockets, the certificate store, entropy and the window station (RAND)
        linkFlags("-lws2_32 -lgdi32 -ladvapi32 -lcrypt32 -luser32")
        (s"mingw-w64-$env-openssl $ver (MSYS2)  libcrypto.a, libssl.a and headers, unmodified: Apache-2.0, see openssl-LICENSE.txt.",
         "https://packages.msys2.org/base/mingw-w64-openssl, https://www.openssl.org")
    writeReadme(out, triple,
      s"""Components:
         |  $components
         |Source: $source""".stripMargin, what = "static OpenSSL")

  /** libidn2 (what sttp-model's `idn2_to_ascii_8z` is) and the libraries its archive calls into:
   *  libunistring everywhere, libiconv on Windows, libintl/libiconv/CoreFoundation on macOS. */
  private def installIdn2(triple: String, arch: String, work: Path, out: Path): Unit =
    val ex = Files.createDirectories(work.resolve("extracted"))
    val key = arch.toUpperCase
    def libs(dir: Path, names: String*): Unit =
      for l <- names do copy(dir.resolve(s"lib$l.a"), out.resolve("lib").resolve(s"lib$l.a"))
    def linkFlags(flags: String): Unit = Files.write(out.resolve("linkflags"), (flags + "\n").getBytes(UTF_8))
    // Alpine's packages carry no licence text: the upstream ones, from the tags.
    def upstreamLicenses(): Unit =
      val v = pin("IDN2_LICENSE_VERSION")
      val uv = pin("UNISTRING_LICENSE_VERSION")
      for (f, k) <- List("COPYING.LESSERv3" -> "IDN2_LICENSE_LESSER_SHA256", "COPYING.unicode" -> "IDN2_LICENSE_UNICODE_SHA256") do
        addLicense(out, download(s"https://gitlab.com/libidn/libidn2/-/raw/v$v/$f", s"libidn2-$v-$f", pin(k)), s"libidn2-$f")
      addLicense(out, download(s"https://git.savannah.gnu.org/cgit/libunistring.git/plain/COPYING.LIB?h=v$uv", s"libunistring-$uv-COPYING.LIB",
        pin("UNISTRING_LICENSE_SHA256")), "libunistring-COPYING.LIB")
    // A linker stub for a system library: the symbols the static archives import from it.
    def tbd(name: String, installName: String, symbols: List[String]): Unit =
      val text =
        s"""--- !tapi-tbd
           |tbd-version:     4
           |targets:         [ x86_64-macos, arm64-macos ]
           |install-name:    '$installName'
           |current-version: 1
           |exports:
           |  - targets:         [ x86_64-macos, arm64-macos ]
           |    symbols:         [ ${symbols.mkString(", ")} ]
           |...
           |""".stripMargin
      Files.createDirectories(out.resolve("lib"))
      Files.write(out.resolve("lib").resolve(s"lib$name.tbd"), text.getBytes(UTF_8))
    val (components, source) =
      if triple.endsWith("linux-musl") then
        val branch = pin("OPENSSL_ALPINE_BRANCH")
        for (pkg, ver, k) <- List(
            ("libidn2-static", pin("IDN2_ALPINE_VERSION"), "IDN2_ALPINE_STATIC"),
            ("libidn2-dev", pin("IDN2_ALPINE_VERSION"), "IDN2_ALPINE_DEV"),
            ("libunistring-static", pin("UNISTRING_ALPINE_VERSION"), "UNISTRING_ALPINE_STATIC")) do
          val apk = download(s"https://dl-cdn.alpinelinux.org/alpine/$branch/main/$arch/$pkg-$ver.apk", s"$pkg-$ver-$arch.apk", pin(s"${k}_${key}_SHA256"))
          tar("-xzf", apk.toString, "-C", ex.toString)
        libs(ex.resolve("usr/lib"), "idn2", "unistring")
        copy(ex.resolve("usr/include/idn2.h"), out.resolve("include/idn2.h"))
        upstreamLicenses()
        linkFlags("-lunistring")
        (s"libidn2-static, libidn2-dev ${pin("IDN2_ALPINE_VERSION")}, libunistring-static ${pin("UNISTRING_ALPINE_VERSION")} (Alpine $branch)  libidn2.a, libunistring.a built for musl, and idn2.h: LGPL-3.0-or-later (libidn2 also GPL-2.0-or-later), see libidn2-COPYING.* and libunistring-COPYING.LIB.",
         "https://pkgs.alpinelinux.org/package/" + branch + "/main/" + arch + "/libidn2, https://www.gnu.org/software/libidn/")
      else if triple.endsWith("linux-gnu") then
        val debArch = if arch == "x86_64" then "amd64" else "arm64"
        for (pkg, dir, ver, k) <- List(
            ("libidn2-dev", "libi/libidn2", pin("IDN2_DEBIAN_VERSION"), "IDN2_DEBIAN"),
            ("libunistring-dev", "libu/libunistring", pin("UNISTRING_DEBIAN_VERSION"), "UNISTRING_DEBIAN")) do
          val deb = s"${pkg}_${ver}_$debArch.deb"
          unpackDeb(download(s"$snapshot/$dir/$deb", deb, pin(s"${k}_${debArch.toUpperCase}_SHA256")), ex)
        libs(ex.resolve("usr/lib").resolve(s"$arch-linux-gnu"), "idn2", "unistring")
        copy(ex.resolve("usr/include/idn2.h"), out.resolve("include/idn2.h"))
        addLicense(out, ex.resolve("usr/share/doc/libidn2-dev/copyright"), "debian-libidn2-dev-copyright")
        addLicense(out, ex.resolve("usr/share/doc/libunistring-dev/copyright"), "debian-libunistring-dev-copyright")
        linkFlags("-lunistring")
        (s"libidn2-dev ${pin("IDN2_DEBIAN_VERSION")}, libunistring-dev ${pin("UNISTRING_DEBIAN_VERSION")} (Debian 12)  libidn2.a, libunistring.a and idn2.h, unmodified: LGPL-3.0-or-later (libidn2 also GPL-2.0-or-later), see debian-*-copyright.",
         "https://tracker.debian.org/pkg/libidn2, https://tracker.debian.org/pkg/libunistring")
      else if triple.endsWith("apple-darwin") then
        val (k, tag) = if arch == "x86_64" then ("X86_64", "sonoma") else ("ARM64", "arm64_sonoma")
        val bottles = for (formula, pfx) <- List("libidn2" -> "IDN2", "libunistring" -> "UNISTRING", "gettext" -> "GETTEXT") yield
          val ver = pin(s"${pfx}_BREW_VERSION")
          val sha = pin(s"${pfx}_BREW_${k}_SHA256")
          val b = download(s"https://ghcr.io/v2/homebrew/core/$formula/blobs/sha256:$sha", s"$formula-brew-$ver-$tag.tar.gz", sha, List("Authorization: Bearer QQ=="))
          tar("-xzf", b.toString, "-C", ex.toString)
          formula -> ex.resolve(formula).resolve(ver)
        val dirs = bottles.toMap
        libs(dirs("libidn2").resolve("lib"), "idn2")
        libs(dirs("libunistring").resolve("lib"), "unistring")
        libs(dirs("gettext").resolve("lib"), "intl")
        copy(dirs("libidn2").resolve("include/idn2.h"), out.resolve("include/idn2.h"))
        addLicense(out, dirs("libidn2").resolve("COPYING.LESSERv3"), "libidn2-COPYING.LESSERv3")
        addLicense(out, dirs("libidn2").resolve("COPYING.unicode"), "libidn2-COPYING.unicode")
        addLicense(out, dirs("libunistring").resolve("COPYING.LIB"), "libunistring-COPYING.LIB")
        addLicense(out, dirs("gettext").resolve("COPYING"), "gettext-COPYING")
        // libintl and libunistring import from these; every Mac has them, the sysroot's stubs do not
        tbd("iconv", "/usr/lib/libiconv.2.dylib", List("_iconv", "_iconv_open", "_iconv_close"))
        tbd("CoreFoundation", "/System/Library/Frameworks/CoreFoundation.framework/Versions/A/CoreFoundation",
          List("___CFConstantStringClassReference", "_CFArrayGetCount", "_CFArrayGetValueAtIndex", "_CFGetTypeID", "_CFLocaleCopyPreferredLanguages",
            "_CFPreferencesCopyAppValue", "_CFRelease", "_CFStringGetCString", "_CFStringGetTypeID", "_kCFPreferencesCurrentApplication"))
        linkFlags("-lunistring -lintl -liconv -lCoreFoundation")
        (s"libidn2 ${pin("IDN2_BREW_VERSION")}, libunistring ${pin("UNISTRING_BREW_VERSION")}, gettext ${pin("GETTEXT_BREW_VERSION")} (Homebrew bottles $tag)  libidn2.a, libunistring.a, libintl.a and idn2.h, unmodified: LGPL-3.0-or-later (libidn2 also GPL-2.0-or-later; libintl LGPL-2.1-or-later), see libidn2-COPYING.*, libunistring-COPYING.LIB, gettext-COPYING.\n  lib/libiconv.tbd, libCoreFoundation.tbd generated by scalino: symbol names only, linker stubs for the system's libiconv and CoreFoundation.",
         "https://formulae.brew.sh/formula/libidn2, https://formulae.brew.sh/formula/libunistring, https://formulae.brew.sh/formula/gettext")
      else
        val env = if arch == "x86_64" then "clang64" else "clangarm64"
        val pkgs = List(("libidn2", pin("IDN2_MSYS2_VERSION"), "IDN2"), ("libunistring", pin("UNISTRING_MSYS2_VERSION"), "UNISTRING"), ("libiconv", pin("ICONV_MSYS2_VERSION"), "ICONV"))
        for (pkg, ver, k) <- pkgs do
          val f = s"mingw-w64-clang-$arch-$pkg-$ver-any.pkg.tar.zst"
          val p = download(s"https://repo.msys2.org/mingw/$env/$f", f, pin(s"${k}_MSYS2_${key}_SHA256"))
          // only the static archive: the .dll.a import libraries next to it would win a -l lookup
          tar("-xf", p.toString, "-C", ex.toString, s"$env/lib/$pkg.a") // zstd, see installOpenssl
          if pkg == "libiconv" then tar("-xf", p.toString, "-C", ex.toString, s"$env/share/licenses/libiconv")
          if pkg == "libidn2" then tar("-xf", p.toString, "-C", ex.toString, s"$env/include/idn2.h")
        libs(ex.resolve(env).resolve("lib"), "idn2", "unistring", "iconv")
        copy(ex.resolve(env).resolve("include/idn2.h"), out.resolve("include/idn2.h"))
        upstreamLicenses() // MSYS2's libidn2 and libunistring packages carry none
        for f <- walk(ex.resolve(env).resolve("share/licenses/libiconv")) if Files.isRegularFile(f) do
          addLicense(out, f, s"msys2-libiconv-${f.getFileName}")
        linkFlags("-lunistring -liconv")
        (s"mingw-w64-$env libidn2 ${pin("IDN2_MSYS2_VERSION")}, libunistring ${pin("UNISTRING_MSYS2_VERSION")}, libiconv ${pin("ICONV_MSYS2_VERSION")} (MSYS2)  static archives and idn2.h, unmodified: LGPL-3.0-or-later (libidn2 also GPL-2.0-or-later; libiconv LGPL-2.0-or-later), see libidn2-COPYING.*, libunistring-COPYING.LIB, msys2-libiconv-*.",
         "https://packages.msys2.org/base/mingw-w64-libidn2")
    writeReadme(out, triple,
      s"""Components:
         |  $components
         |Source: $source
         |These libraries are LGPL: a static link means the linked program must let its users relink it
         |(distribute the object files or this addon's archives alongside, or link dynamically).""".stripMargin, what = "static libidn2")

  // ---- addons built from source (s2n-tls, libcurl) ---------------------------

  /** What `scalino sysroot build` compiles with: the host's clang, and llvm-ar (next to clang: Homebrew,
   *  apt's llvm-N/bin; or on PATH. Apple's ar can't index ELF objects). */
  private def clangAndAr(): (String, String) =
    val clang = ScalinoCli.findOnPath("clang")
    val ar = Option(Paths.get(clang).toRealPath().getParent.resolve("llvm-ar")).filter(Files.isExecutable(_)).map(_.toString)
      .getOrElse(ScalinoCli.findOnPath("llvm-ar"))
    (clang, ar)

  /** clang flags for compiling C for `triple` against its sysroot (the link driver's compile flags) and
   *  the OpenSSL addon's headers. */
  private def targetFlags(triple: String, sysroot: Path, ssl: Path): List[String] =
    List(s"--target=$triple") ++
      (if triple.contains("apple") then List("-isysroot", sysroot.toString, "-mmacos-version-min=11.0") else List(s"--sysroot=$sysroot")) ++
      List("-I" + ssl.resolve("include"))

  /** Compiles every file of `sources` with `clang flags` (in parallel) into `objs`; dies with the first errors. */
  private def compileAll(clang: String, flags: List[String], sources: List[Path], objs: Path, what: String): List[Path] =
    val pool = java.util.concurrent.Executors.newFixedThreadPool(Runtime.getRuntime.availableProcessors().max(1))
    val results =
      try
        sources.zipWithIndex.map { (c, i) =>
          pool.submit(new java.util.concurrent.Callable[(Path, Int, String)]:
            def call() =
              val o = objs.resolve(f"$i%04d-${c.getFileName}.o")
              val (code, output) = ScalinoCli.runCaptureAll(clang :: flags ++ List("-c", c.toString, "-o", o.toString))
              (o, code, output))
        }.map(_.get())
      finally pool.shutdown()
    val failed = results.filter(_._2 != 0)
    if failed.nonEmpty then die(s"sysroot build: compiling $what failed:\n${failed.take(3).map(_._3).mkString("\n")}")
    results.map(_._1)

  /** The static OpenSSL's built-in CA directory is the packager's (Homebrew's /usr/local/etc/openssl@3,
   *  Debian's /usr/lib/ssl...), which most machines lack. Appended to a source file that is always linked
   *  (so the archive member holding it is pulled in): point OpenSSL at the system's CA bundle, unless the
   *  user already chose one. */
  private def addCaBundleConstructor(file: Path): Unit =
    Files.write(file, (new String(Files.readAllBytes(file), UTF_8) +
      """
        |
        |/* scalino: default CA bundle, see `scalino sysroot build` */
        |#include <stdlib.h>
        |#include <unistd.h>
        |__attribute__((constructor)) static void scalino_default_ca_bundle(void)
        |{
        |    static const char *const bundles[] = {
        |        "/etc/ssl/certs/ca-certificates.crt", "/etc/pki/tls/certs/ca-bundle.crt", "/etc/ssl/ca-bundle.pem",
        |        "/etc/pki/tls/cacert.pem", "/etc/ssl/cert.pem", "/usr/local/share/certs/ca-root-nss.crt" };
        |    if (getenv("SSL_CERT_FILE") != NULL || getenv("SSL_CERT_DIR") != NULL) return;
        |    for (unsigned i = 0; i < sizeof(bundles) / sizeof(bundles[0]); i++) {
        |        if (access(bundles[i], R_OK) == 0) { setenv("SSL_CERT_FILE", bundles[i], 0); return; }
        |    }
        |}
        |""".stripMargin).getBytes(UTF_8))

  private def archive(ar: String, lib: Path, objs: List[Path]): Unit =
    Files.createDirectories(lib.getParent)
    if ScalinoCli.runInherited(List(ar, "rcs", lib.toString) ++ objs.map(_.toString)) != 0 then die(s"sysroot build: $ar failed")

  /** s2n-tls, compiled: the sources' own feature probes (tests/features) pick the -D flags, every
   *  library source goes through clang for `triple`, and llvm-ar makes libs2n.a. Links against the OpenSSL addon's libcrypto. */
  private def installS2n(triple: String, work: Path, out: Path): Unit =
    if triple.contains("windows") then die("sysroot build: s2n-tls does not support Windows")
    val ssl = buildAddon("openssl", triple, force = false)
    val sysroot = Sysroot.find(triple).getOrElse(build(triple, force = false))
    val ver = pin("S2N_VERSION")
    val tarball = download(s"https://github.com/aws/s2n-tls/archive/refs/tags/v$ver.tar.gz", s"s2n-tls-$ver.tar.gz", pin("S2N_SHA256"))
    val src = Files.createDirectories(work.resolve("s2n"))
    tar("-xzf", tarball.toString, "-C", src.toString, "--strip-components=1")
    addCaBundleConstructor(src.resolve("tls/s2n_config.c"))
    val (clang, ar) = clangAndAr()
    val target = targetFlags(triple, sysroot, ssl)
    def flagsOf(f: Path): List[String] = new String(Files.readAllBytes(f), UTF_8).split("\\s+").toList.filter(_.nonEmpty)
    val features = src.resolve("tests/features")
    val global = flagsOf(features.resolve("GLOBAL.flags"))
    val prelude = List("-I" + src, "-include", src.resolve("utils/s2n_prelude.h").toString)
    println(s"s2n-tls $ver: probing $triple")
    val defines = walk(features).filter(_.getFileName.toString.endsWith(".c")).sortBy(_.toString).flatMap { c =>
      val name = c.getFileName.toString.stripSuffix(".c")
      val (code, _) = ScalinoCli.runCaptureAll(clang :: target ++ prelude ++ List("-c") ++ global ++ flagsOf(features.resolve(s"$name.flags")) ++ List(c.toString, "-o", "/dev/null"))
      if code == 0 then Some(s"-D$name=1") else None
    }
    val sources = List("crypto", "error", "stuffer", "utils", "tls").flatMap(d => walk(src.resolve(d))).filter(_.getFileName.toString.endsWith(".c")).sortBy(_.toString)
    println(s"s2n-tls $ver: compiling ${sources.size} files for $triple")
    // -w: s2n's own warnings (it builds with -Werror in its CI), nothing for us to act on
    val flags = target ++ prelude ++ List("-std=gnu99", "-O2", "-fPIC", "-w", "-I" + src.resolve("api"), "-DS2N_BUILD_RELEASE=1") ++ defines
    archive(ar, out.resolve("lib/libs2n.a"), compileAll(clang, flags, sources, Files.createDirectories(work.resolve("s2n-obj")), s"s2n-tls for $triple"))
    copy(src.resolve("api/s2n.h"), out.resolve("include/s2n.h"))
    for h <- walk(src.resolve("api/unstable")) if Files.isRegularFile(h) do copy(h, out.resolve("include/s2n/unstable").resolve(h.getFileName.toString))
    addLicense(out, src.resolve("LICENSE"), "s2n-tls-LICENSE")
    addLicense(out, src.resolve("NOTICE"), "s2n-tls-NOTICE")
    // its libcrypto is the OpenSSL addon's (which `package` passes along)
    Files.write(out.resolve("linkflags"), "-lcrypto\n".getBytes(UTF_8))
    writeReadme(out, triple,
      s"""Components:
         |  s2n-tls $ver  libs2n.a compiled by scalino from the source (clang, -O2) for $triple, and its public headers:
         |  Apache-2.0, see s2n-tls-LICENSE and s2n-tls-NOTICE. Needs the OpenSSL addon's libcrypto. One addition: a
         |  constructor in tls/s2n_config.c that sets SSL_CERT_FILE to the system's CA bundle when neither it nor
         |  SSL_CERT_DIR is set (the static OpenSSL's built-in CA path is its packager's).
         |Source: https://github.com/aws/s2n-tls""".stripMargin, what = "static s2n-tls")

  /** libcurl on Linux and macOS, compiled: HTTP(S) with OpenSSL (the addon's), no zlib/brotli/zstd/HTTP2/
   *  libssh/idn/psl so nothing else has to be linked. curl's configure can't run for a cross target, so
   *  lib/curl_config.h is one scalino ships (build/curl_config-*.h, from a real configure run). */
  private def installCurl(triple: String, work: Path, out: Path): Unit =
    if triple.contains("windows") then return installCurlWindows(triple, work, out)
    val ssl = buildAddon("openssl", triple, force = false)
    val sysroot = Sysroot.find(triple).getOrElse(build(triple, force = false))
    val ver = pin("CURL_VERSION")
    val tarball = download(s"https://curl.se/download/curl-$ver.tar.xz", s"curl-$ver.tar.xz", pin("CURL_SHA256"))
    val src = Files.createDirectories(work.resolve("curl"))
    tar("-xf", tarball.toString, "-C", src.toString, "--strip-components=1")
    val apple = triple.contains("apple")
    val musl = triple.endsWith("linux-musl")
    val data = Paths.get(dist).resolve("share").resolve("sysroot")
    val config = readLines(data.resolve(if apple then "curl_config-darwin.h" else "curl_config-linux.h"))
      // musl's sysroot has no linux/ headers
      .filterNot(l => musl && l.contains("HAVE_LINUX_TCP_H"))
    Files.write(src.resolve("lib/curl_config.h"), (config.mkString("\n") + "\n").getBytes(UTF_8))
    if apple then
      // the IPv6-literal workaround that needs the SystemConfiguration framework (headers and stub we don't have)
      val h = src.resolve("lib/curl_setup.h")
      val text = new String(Files.readAllBytes(h), UTF_8)
      val from = "     defined(USE_IPV6)\n#    define CURL_MACOS_CALL_COPYPROXIES 1"
      if !text.contains(from) then die("sysroot build: curl_setup.h is not what scalino's patch expects (a curl bump?)")
      Files.write(h, text.replace(from, "     0 && defined(USE_IPV6)\n#    define CURL_MACOS_CALL_COPYPROXIES 1").getBytes(UTF_8))
    addCaBundleConstructor(src.resolve("lib/easy.c"))
    // zlib, whose objects go into libcurl.a too
    val zver = pin("ZLIB_VERSION")
    val zsrc = Files.createDirectories(work.resolve("zlib"))
    tar("-xzf", download(s"https://github.com/madler/zlib/releases/download/v$zver/zlib-$zver.tar.gz", s"zlib-$zver.tar.gz", pin("ZLIB_SHA256")).toString,
      "-C", zsrc.toString, "--strip-components=1")
    val (clang, ar) = clangAndAr()
    val zlibFiles = List("adler32", "compress", "crc32", "deflate", "gzclose", "gzlib", "gzread", "gzwrite", "infback", "inffast", "inflate", "inftrees", "trees", "uncompr", "zutil")
      .map(f => zsrc.resolve(s"$f.c"))
    val sources = walk(src.resolve("lib")).filter(_.getFileName.toString.endsWith(".c")).sortBy(_.toString)
    println(s"curl $ver, zlib $zver: compiling ${sources.size + zlibFiles.size} files for $triple")
    val os = if apple then Nil else List("-D_GNU_SOURCE", if musl then "-DHAVE_POSIX_STRERROR_R=1" else "-DHAVE_GLIBC_STRERROR_R=1")
    val zflags = List("-DZ_PREFIX", "-DZ_HAVE_UNISTD_H=1", "-I" + zsrc)
    val common = targetFlags(triple, sysroot, ssl) ++ os ++ List("-O2", "-fPIC", "-w") ++ zflags
    val flags = common ++ List("-DHAVE_CONFIG_H", "-DHAVE_LIBZ=1", "-DBUILDING_LIBCURL", "-DCURL_STATICLIB", "-I" + src.resolve("include"), "-I" + src.resolve("lib"))
    val objs = Files.createDirectories(work.resolve("curl-obj"))
    archive(ar, out.resolve("lib/libcurl.a"),
      compileAll(clang, flags, sources, objs, s"curl for $triple") ++
        compileAll(clang, common, zlibFiles, Files.createDirectories(work.resolve("zlib-obj")), s"zlib for $triple"))
    addLicense(out, zsrc.resolve("LICENSE"), "zlib-LICENSE")
    for h <- walk(src.resolve("include/curl")) if Files.isRegularFile(h) && h.getFileName.toString.endsWith(".h") do copy(h, out.resolve("include/curl").resolve(h.getFileName.toString))
    addLicense(out, src.resolve("COPYING"), "curl-COPYING")
    Files.write(out.resolve("linkflags"), "-lssl -lcrypto\n".getBytes(UTF_8))
    writeReadme(out, triple,
      s"""Components:
         |  curl $ver  libcurl.a compiled by scalino from the source (clang, -O2) for $triple, with its public headers:
         |  the curl licence (MIT-like), see curl-COPYING. HTTP(S), FTP... over the OpenSSL addon's libcrypto/libssl; no
         |  brotli, zstd, HTTP/2, libssh, libpsl or IDN; zlib $zver is compiled into libcurl.a (Z_PREFIX'd), see zlib-LICENSE. Additions: a constructor in lib/easy.c that sets
         |  SSL_CERT_FILE to the system's CA bundle when neither it nor SSL_CERT_DIR is set${if apple then "; curl_setup.h's macOS IPv6 literal workaround (SystemConfiguration) is switched off" else ""}.
         |Source: https://curl.se""".stripMargin, what = "static libcurl")

  /** libcurl on Windows: curl's own Windows configuration only supports MSVC, so MSYS2's static build, with
   *  everything it was built with (HTTP/2, HTTP/3, SSH, brotli, zstd, zlib, PSL). OpenSSL and libidn2 are the
   *  addons'. */
  private def installCurlWindows(triple: String, work: Path, out: Path): Unit =
    val ex = Files.createDirectories(work.resolve("extracted"))
    val arch = triple.takeWhile(_ != '-')
    val env = if arch == "x86_64" then "clang64" else "clangarm64"
    val key = arch.toUpperCase
    val pkgs = List(
      "curl" -> List("libcurl"), "libssh2" -> List("libssh2"), "zlib" -> List("libz"), "brotli" -> List("libbrotlicommon", "libbrotlidec"),
      "zstd" -> List("libzstd"), "nghttp2" -> List("libnghttp2"), "ngtcp2" -> List("libngtcp2", "libngtcp2_crypto_ossl"),
      "nghttp3" -> List("libnghttp3"), "libpsl" -> List("libpsl"))
    for (pkg, libs) <- pkgs do
      val ver = pin(s"${pkg.toUpperCase}_MSYS2_VERSION")
      val f = s"mingw-w64-clang-$arch-$pkg-$ver-any.pkg.tar.zst"
      val p = download(s"https://repo.msys2.org/mingw/$env/$f", f, pin(s"${pkg.toUpperCase}_MSYS2_${key}_SHA256"))
      // only the static archives: the .dll.a import libraries next to them would win a -l lookup. zstd, see installOpenssl
      tar(List("-xf", p.toString, "-C", ex.toString) ++ libs.map(l => s"$env/lib/$l.a") ++ List(s"$env/share/licenses/$pkg")*)
      for l <- libs do copy(ex.resolve(env).resolve(s"lib/$l.a"), out.resolve("lib").resolve(s"$l.a"))
      for f <- walk(ex.resolve(env).resolve("share/licenses").resolve(pkg)) if Files.isRegularFile(f) do addLicense(out, f, s"msys2-$pkg-${f.getFileName}")
      if pkg == "curl" then
        tar("-xf", p.toString, "-C", ex.toString, s"$env/include/curl")
        copyTree(ex.resolve(env).resolve("include/curl"), out.resolve("include/curl"))
    // Windows bindings @link the file name ("libcurl"), which makes lld look for liblibcurl.a
    copy(out.resolve("lib/libcurl.a"), out.resolve("lib/liblibcurl.a"))
    // what MSYS2's libcurl.pc lists (Libs.private), the OpenSSL/idn2 addons' libraries among them
    Files.write(out.resolve("linkflags"),
      ("-lssh2 -lidn2 -lssl -lcrypto -lz -lbrotlidec -lbrotlicommon -lzstd -lnghttp2 -lngtcp2_crypto_ossl -lngtcp2 -lnghttp3 -lpsl " +
        "-lwldap32 -lbcrypt -ladvapi32 -lcrypt32 -lsecur32 -lws2_32 -liphlpapi\n").getBytes(UTF_8))
    writeReadme(out, triple,
      s"""Components:
         |  mingw-w64-$env curl ${pin("CURL_MSYS2_VERSION")}, libssh2, zlib, brotli, zstd, nghttp2, ngtcp2, nghttp3, libpsl (MSYS2)
         |  static archives and curl's headers, unmodified; each under its own licence, see msys2-*.
         |  Needs the OpenSSL and libidn2 addons.
         |Source: https://packages.msys2.org/base/mingw-w64-curl""".stripMargin, what = "static libcurl")
