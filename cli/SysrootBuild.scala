// `scalino sysroot build <triple>`: assembles a cross-compilation sysroot on the
// user's machine from upstream packages (scalino publishes none): musl (Debian
// 13's musl-dev) and gnu (Debian 11's glibc), both with Debian 12's prebuilt
// compiler-rt, all from .debs; apple (Darwin headers from Zig's source tarball +
// a generated libSystem stub) and windows (llvm-mingw's data dirs). Nothing is
// compiled. Versions and checksums come from versions.env (via
// BuildInfo.sysrootPins).
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
  private def download(url: String, name: String, sha256: String): Path =
    Files.createDirectories(sources)
    val f = sources.resolve(name)
    if Files.exists(f) then
      if sha256Hex(Files.readAllBytes(f)) == sha256 then return f
      Files.delete(f)
    val part = sources.resolve(name + ".part")
    println(s"downloading $url")
    if runInherited(List("curl", "-fsSL", "-o", part.toString, url)) != 0 then
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

  private def writeReadme(out: Path, triple: String, body: String): Unit =
    val dir = Files.createDirectories(out.resolve("LICENSES"))
    val text =
      s"""scalino sysroot for $triple (built by `scalino sysroot build` from scalino ${BuildInfo.scalinoVersion}).
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
