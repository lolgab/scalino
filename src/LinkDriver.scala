import scala.scalanative.build._
import scala.scalanative.util.Scope
import java.nio.file.{Path, Paths}

object LinkDriver:
  /** `Logger.default` (used unconditionally before) always prints debug/trace
   *  (raw clang invocations, full NativeConfig dumps) to stderr, with no way
   *  to dial it down -- scalino now passes its own resolved log level as an
   *  optional 6th arg ("quiet"|"info"|"verbose", default "info") so `-v`/`-q`
   *  actually affect the link step, not just scalino's own output. */
  def loggerFor(level: String): Logger = level match
    case "quiet" => Logger.apply(_ => (), _ => (), _ => (), _ => (), msg => System.err.println(s"[error] $msg"))
    case "verbose" => Logger.default
    case _ => Logger.apply(
      _ => (),
      _ => (),
      msg => println(s"[info] $msg"),
      msg => println(s"[warn] $msg"),
      msg => System.err.println(s"[error] $msg")
    )

  /** Everything past the 6 required positional args (cp, workDir, mainClass,
   *  clang, clang++, logLevel) is flag-style, one flag per `//> using
   *  native*`/`--native-*` option resolved by ScalinoCli's own NativeOpts --
   *  see cli/ScalinoCli.scala's buildBinary for the argv this is parsing. */
  case class Opts(
    mode: Option[String] = None,
    gc: Option[String] = None,
    lto: Option[String] = None,
    target: Option[String] = None,
    targetTriples: List[String] = Nil,
    // triple -> sysroot dir, for cross targets
    sysroots: Map[String, String] = Map.empty,
    // triple -> static library dirs (lib/, include/, optional linkflags), for cross targets
    libAddons: Map[String, List[String]] = Map.empty,
    // ld.lld to link with when the system has none (cli/Sysroot.ensureLld)
    ldPath: Option[String] = None,
    embedResources: Boolean = false,
    multithreading: Boolean = false,
    directCodegen: Boolean = false,
    compactHeaders: Boolean = true,
    // None: on, unless a library depending on the regular array layout is on
    // the classpath, see `LayoutDependentLibraries`
    compactByteArrays: Option[Boolean] = None,
    gcStwSweep: Boolean = false,
    heapHistogram: Boolean = false,
    incrementalCompilation: Boolean = false,
    optimize: Boolean = true,
    linking: List[String] = Nil,
    compile: List[String] = Nil,
    cCompile: List[String] = Nil,
    cppCompile: List[String] = Nil,
    prune: List[String] = Nil,
    longRunning: Boolean = false
  )

  val LayoutDependentLibraries = List("jsoniter-scala-core_native")

  def parseOpts(rest: Array[String]): Opts =
    var o = Opts()
    var i = 0
    while i < rest.length do
      rest(i) match
        case "--mode" => o = o.copy(mode = Some(rest(i + 1))); i += 1
        case "--gc" => o = o.copy(gc = Some(rest(i + 1))); i += 1
        case "--lto" => o = o.copy(lto = Some(rest(i + 1))); i += 1
        case "--target" => o = o.copy(target = Some(rest(i + 1))); i += 1
        case "--target-triple" => o = o.copy(targetTriples = o.targetTriples :+ rest(i + 1)); i += 1
        case "--sysroot" =>
          val Array(t, d) = rest(i + 1).split("=", 2)
          o = o.copy(sysroots = o.sysroots.updated(t, d)); i += 1
        case "--lib-addon" =>
          val Array(t, d) = rest(i + 1).split("=", 2)
          o = o.copy(libAddons = o.libAddons.updated(t, o.libAddons.getOrElse(t, Nil) :+ d)); i += 1
        case "--ld-path" => o = o.copy(ldPath = Some(rest(i + 1))); i += 1
        case "--embed-resources" => o = o.copy(embedResources = true)
        case "--multithreading" => o = o.copy(multithreading = true)
        case "--direct-codegen" => o = o.copy(directCodegen = true)
        case "--compact-headers" => o = o.copy(compactHeaders = true)
        case "--no-compact-headers" => o = o.copy(compactHeaders = false)
        case "--compact-byte-arrays" => o = o.copy(compactByteArrays = Some(true))
        case "--no-compact-byte-arrays" => o = o.copy(compactByteArrays = Some(false))
        case "--gc-stw-sweep" => o = o.copy(gcStwSweep = true)
        case "--heap-histogram" => o = o.copy(heapHistogram = true)
        case "--incremental-compilation" => o = o.copy(incrementalCompilation = true)
        case "--no-opt" => o = o.copy(optimize = false)
        case "--linking" => o = o.copy(linking = o.linking :+ rest(i + 1)); i += 1
        case "--compile" => o = o.copy(compile = o.compile :+ rest(i + 1)); i += 1
        case "--c-compile" => o = o.copy(cCompile = o.cCompile :+ rest(i + 1)); i += 1
        case "--cpp-compile" => o = o.copy(cppCompile = o.cppCompile :+ rest(i + 1)); i += 1
        case "--prune" => o = o.copy(prune = o.prune :+ rest(i + 1)); i += 1
        case "--long-running" => o = o.copy(longRunning = true)
        case other => System.err.println(s"scalino-linkdriver: ignoring unknown flag '$other'")
      i += 1
    o

  def parseBuildTarget(s: String): BuildTarget = s match
    case "app" | "application" => BuildTarget.application
    case "static" | "library-static" => BuildTarget.libraryStatic
    case "dynamic" | "library-dynamic" => BuildTarget.libraryDynamic
    case other => throw new IllegalArgumentException(s"Unknown native target: '$other' (expected app|static|dynamic)")

  def main(args: Array[String]): Unit =
    // File.pathSeparator, not a hardcoded ":" -- confirmed via Windows CI:
    // a hardcoded ":" shatters a real Windows path at its own drive-letter
    // colon ("C:\..."), producing garbage entries and "Discovered 0 classes"
    // no matter how correct each individual path string already is.
    val cp = args(0).split(java.io.File.pathSeparator).toSeq.map(s => Paths.get(s))
    val workDir = Paths.get(args(1))
    val mainClass = args(2)
    val logLevel = if args.length > 5 then args(5) else "info"
    val logger = loggerFor(logLevel)
    val opts = parseOpts(if args.length > 6 then args.drop(6) else Array.empty)

    def die(msg: String): Nothing =
      System.err.println(s"scalino-linkdriver: $msg")
      sys.exit(1)

    val mode = try opts.mode.map(Mode.apply).getOrElse(Mode.default) catch case e: IllegalArgumentException => die(e.getMessage)
    val gc = try opts.gc.map(GC.apply).getOrElse(GC.default) catch case e: IllegalArgumentException => die(e.getMessage)
    val lto = try opts.lto.map(LTO.apply).getOrElse(LTO.default) catch case e: IllegalArgumentException => die(e.getMessage)
    val target = try opts.target.map(parseBuildTarget).getOrElse(BuildTarget.default) catch case e: IllegalArgumentException => die(e.getMessage)

    // Libraries reading and writing the elements of Array[Byte] through raw
    // pointers, with a copy of the regular Scala Native array layout (for
    // instance jsoniter-scala-core's ByteArrayAccess). Compact byte arrays
    // would make them access the wrong bytes, even past the end of the array.
    val compactByteArrays = opts.compactByteArrays.getOrElse {
      LayoutDependentLibraries.find(lib => cp.exists(_.toString.contains(lib))) match
        case Some(lib) =>
          if logLevel != "quiet" then
            System.err.println(s"scalino-linkdriver: not using compact byte arrays, $lib reads them through raw pointers")
          false
        case None => true
    }

    val config = Config.empty
      .withBaseDir(workDir)
      .withModuleName(mainClass)
      .withMainClass(Some(mainClass))
      .withClassPath(cp)
      .withLogger(logger)
      .withCompilerConfig(
        _.withClang(Paths.get(args(3)))
          .withClangPP(Paths.get(args(4)))
          // `NativeConfig.empty` leaves these at `Seq.empty` -- real
          // scala-cli/sbt-scala-native wire in `Discover`'s defaults
          // (`/opt/homebrew/lib` etc. on macOS) so system libs a native
          // dependency needs (e.g. `@link("crypto")` from http4s-crypto)
          // are actually findable; without this, linking anything that
          // needs a Homebrew-installed system lib fails with "library
          // 'x' not found" even though the same project links fine
          // under real scala-cli on the same machine. User-supplied
          // `--linking`/`--compile` (from `//> using nativeLinking`/
          // `nativeCompile`) are appended on top, not substituted in
          // place of, these defaults.
          .withLinkingOptions(Discover.linkingOptions() ++ opts.linking)
          .withCompileOptions(Discover.compileOptions() ++ opts.compile)
          // SCALINO_HEAP_HISTOGRAM gates the whole live-heap-histogram GC
          // debug feature (patches/scala-native-0041, on top of the feature
          // itself from patches/scala-native-0005) at C compile time --
          // undefined by default, since the always-linked-in histogramTable
          // (~7.5MB static table) and Histogram_dump code otherwise bloat
          // every binary regardless of whether SCALANATIVE_HEAP_HISTOGRAM_FILE
          // is ever set at runtime.
          .withCOptions(opts.cCompile ++ (if opts.heapHistogram then Seq("-DSCALINO_HEAP_HISTOGRAM") else Seq.empty))
          .withCppOptions(opts.cppCompile)
          .withMode(mode)
          .withGC(gc)
          .withLTO(lto)
          .withBuildTarget(target)
          .withEmbedResources(opts.embedResources)
          .withMultithreading(if opts.multithreading then Some(true) else None)
          .withLLVMDirectCodeGen(opts.directCodegen)
          .withCompactHeaders(opts.compactHeaders)
          .withCompactByteArrays(compactByteArrays)
          .withGCStwSweep(opts.gcStwSweep)
          // DirectCodeGen now has its own (scoped) DIBuilder-based debug-info
          // support (Phase 3), so debug info no longer needs forcing off for
          // the flag to have an effect -- always on, same as every other
          // path, regardless of the direct-codegen flag.
          .withSourceLevelDebuggingConfig(SourceLevelDebuggingConfig.enabled)
          .withIncrementalCompilation(opts.incrementalCompilation)
          .withOptimize(opts.optimize)
          // `//> using nativePrune`: patterns travel to the linker's Reach
          // (patches/scala-native-0062) as a String link-time property, so
          // they are also part of the config hash and a changed list can
          // never reuse an up-to-date build or an incremental reach.
          .withLinktimeProperties(
            if opts.prune.isEmpty then Map.empty[String, Any]
            else Map[String, Any]("scalino.prune" -> opts.prune.mkString(";"))
          )
      )

    // Cross targets: one Config per `--target-triple`, each in its own
    // `<workDir>/<triple>` so object caches and binaries never mix. All of
    // them are linked by this one process, so the process-global NIR parse
    // cache (patches/scala-native-0047) is shared between targets.
    def normArch(a: String): String = a match
      case "arm64" => "aarch64"
      case "amd64" => "x86_64"
      case other => other
    def osOf(t: String): String =
      if t.contains("darwin") || t.contains("apple") then "macos"
      else if t.contains("linux") then "linux"
      else if t.contains("windows") || t.contains("mingw") then "windows"
      else t
    // Whether `t` is what clang already produces on this machine. Only then
    // are `Discover`'s host library/include dirs (/opt/homebrew/lib...) valid.
    lazy val hostTriple = Discover.targetTriple(Paths.get(args(3)))
    lazy val hasLld: Boolean =
      sys.env.getOrElse("PATH", "").split(java.io.File.pathSeparator).exists { d =>
        d.nonEmpty && List("ld.lld", "ld.lld.exe").exists(n => java.nio.file.Files.isExecutable(Paths.get(d, n)))
      }
    def isHostTarget(t: String): Boolean =
      normArch(t.takeWhile(_ != '-')) == normArch(hostTriple.takeWhile(_ != '-')) &&
        osOf(t) == osOf(hostTriple) && (osOf(t) != "linux" || t.endsWith("musl") == hostTriple.endsWith("musl"))
    /** `-rtlib=compiler-rt -resource-dir=...` for a `scalino sysroot build`
     *  sysroot `d` (layout in cli/SysrootBuild.scala). clang only looks for
     *  compiler-rt's builtins (and crtbegin/crtend) in its own resource dir,
     *  so a private one is assembled next to the build: the host clang's
     *  builtin headers, the sysroot's runtime libs. */
    def compilerRtFlags(d: java.nio.file.Path, targetDir: Path): List[String] =
      val rt = d.resolve("resource").resolve("lib")
      if !java.nio.file.Files.isDirectory(rt) then Nil
      else
        def run(cmd: String*): String =
          val p = new ProcessBuilder(cmd*).redirectErrorStream(false).start()
          val out = new String(p.getInputStream.readAllBytes()).trim
          if p.waitFor() != 0 then die(s"`${cmd.mkString(" ")}` failed")
          out
        val hostInclude = Paths.get(run(args(3), "-print-resource-dir")).resolve("include")
        // Absolute: clang's own cwd while linking is not ours.
        val res = targetDir.toAbsolutePath.resolve("clang-resource")
        java.nio.file.Files.createDirectories(res)
        run("ln", "-sfn", hostInclude.toString, res.resolve("include").toString)
        run("ln", "-sfn", rt.toString, res.resolve("lib").toString)
        List("-rtlib=compiler-rt", s"-resource-dir=$res")

    /** Link flags for a Linux sysroot: musl is linked statically, glibc
     *  dynamically; Scala Native brings its own unwinder. */
    def linuxSysrootLinkFlags(t: String, d: String, targetDir: Path): List[String] =
      val sysrootAbs = Paths.get(d).toAbsolutePath
      val rt = compilerRtFlags(sysrootAbs, targetDir)
      List(s"--sysroot=$sysrootAbs") ++ rt ++ (if rt.nonEmpty then List("--unwindlib=none") else Nil) ++
        (if t.endsWith("musl") then List("-static") else Nil)

    /** mingw-w64 sysroot (llvm-mingw's layout): libc++ and libunwind come
     *  with it, since the Windows runtime is built on C++ exceptions. */
    def windowsSysrootLinkFlags(d: String, targetDir: Path): List[String] =
      val sysrootAbs = Paths.get(d).toAbsolutePath
      List(s"--sysroot=$sysrootAbs", "-stdlib=libc++", "--unwindlib=libunwind", "-static") ++ compilerRtFlags(sysrootAbs, targetDir)

    def forTriple(t: String): Config =
      val withDir = config.withBaseDir(workDir.resolve(t))
      if isHostTarget(t) then withDir.withCompilerConfig(_.withTargetTriple(Some(t)))
      else
        // Cross target: Discover's host-only lib/include dirs (/opt/homebrew...)
        // are dropped, the target's sysroot (if any) takes their place.
        val sysroot = opts.sysroots.get(t)
        // An unversioned darwin triple makes clang assume a pre-10.6 deployment
        // target and ask the linker for crt1.o; pin a modern one.
        val macosVersion =
          if osOf(t) == "macos" && t.endsWith("darwin") then List("-mmacos-version-min=11.0") else Nil
        // Static libraries (OpenSSL's libcrypto...) the sysroot lacks. `linkflags`: system
        // libraries their archives call into, written by `scalino sysroot build`.
        val addons = opts.libAddons.getOrElse(t, Nil).map(Paths.get(_).toAbsolutePath)
        // C sources only: clang warns about an unused -isystem for every .ll file
        val addonCompile = addons.flatMap(d => List("-isystem", d.resolve("include").toString))
        val addonLink = addons.flatMap { d =>
          val flags = d.resolve("linkflags")
          List("-L" + d.resolve("lib")) ++
            (if java.nio.file.Files.exists(flags) then new String(java.nio.file.Files.readAllBytes(flags), "UTF-8").trim.split("\\s+").toList.filter(_.nonEmpty) else Nil)
        }
        val compileFlags = macosVersion ++ sysroot.toList.flatMap(d => osOf(t) match
          case "macos" => List("-isysroot", d)
          case _ => List(s"--sysroot=$d"))
        val linkFlags = macosVersion ++ addonLink ++ sysroot.toList.flatMap(d => osOf(t) match
          case "macos" => List("-isysroot", d)
          case "linux" => linuxSysrootLinkFlags(t, d, workDir.resolve(t))
          case "windows" => windowsSysrootLinkFlags(d, workDir.resolve(t))
          case _ => List(s"--sysroot=$d"))
        // Scala Native's Windows code declares its own pid_t (MSVC has none);
        // mingw-w64 defines one too unless _PID_T_ says it is already there.
        val cDefines = if osOf(t) == "windows" then List("-D_PID_T_") else Nil
        // C++ only: libc++'s headers shadow the C ones if added for C files too.
        val cppFlags = sysroot.toList.flatMap(d =>
          if osOf(t) == "windows" then
            List("-stdlib=libc++", "-isystem", Paths.get(d).toAbsolutePath.resolve("generic-w64-mingw32/include/c++/v1").toString)
          // Only the Windows runtime (eh.cpp) needs C++ std headers. Elsewhere
          // the sysroot has none, and clang would fall back to the host's
          // libstdc++ (/usr/include/c++/N on Debian/Ubuntu), which wants glibc's
          // __GLIBC_PREREQ and breaks libunwind against a musl sysroot.
          else List("-nostdinc++"))
        // Apple's ld only exists on a Mac, and can't produce ELF or PE.
        val userPicksLinker = opts.linking.exists(o => o.startsWith("-fuse-ld") || o.startsWith("--ld-path"))
        val needsLld = !userPicksLinker &&
          (osOf(t) == "windows" || osOf(t) != osOf(hostTriple) || osOf(t) == "linux")
        // clang's own "invalid linker name in argument '-fuse-ld=lld'" says nothing useful
        if needsLld && !hasLld && opts.ldPath.isEmpty then
          throw new RuntimeException(
            s"linking for $t needs lld (ld.lld) on PATH: install it (apt install lld, brew install lld) or pass -fuse-ld=/--ld-path via linking options")
        val lld =
          if userPicksLinker then Nil
          // Scala Native turns a literal -fuse-ld=lld into --start-lib/--end-lib,
          // which lld's MinGW mode rejects: pick the same linker by path.
          else if osOf(t) == "windows" then List(s"--ld-path=${opts.ldPath.getOrElse("ld.lld")}")
          // GNU ld is single-arch (an x86_64 host's can't write aarch64 ELF), so
          // every non-host Linux target links with lld too. Apple's ld handles
          // both Mac architectures.
          else if osOf(t) != osOf(hostTriple) || osOf(t) == "linux" then "-fuse-ld=lld" :: opts.ldPath.map(p => s"--ld-path=$p").toList
          else Nil
        withDir.withCompilerConfig(
          _.withTargetTriple(Some(t))
            .withLinkingOptions(linkFlags ++ lld ++ opts.linking)
            .withCompileOptions(compileFlags ++ opts.compile)
            .withCppOptions(cDefines ++ cppFlags ++ opts.cppCompile)
            .withCOptions(cDefines ++ addonCompile ++ opts.cCompile ++ (if opts.heapHistogram then Seq("-DSCALINO_HEAP_HISTOGRAM") else Seq.empty))
        )
    val configs: List[Config] =
      if opts.targetTriples.isEmpty then List(config) else opts.targetTriples.distinct.map(forTriple)

    if opts.longRunning then
      // Same protocol as scala-js-cli's `--longRunning` (scala-js-cli#64):
      // one Scope, one process, kept alive across every relink so the
      // patched scala.scalanative.linker.ClassPath's persistent NIR parse
      // cache (patches/scala-native-0047) actually survives between builds
      // -- a fresh Scope per build would tear down and reopen every jar's
      // NIO FileSystem, and a fresh ClassLoader.fromDisk call still hits the
      // same cached ClassPath.Impl either way (it's keyed process-globally,
      // not by Scope), but reusing one Scope avoids paying jar-FileSystem
      // open/close on every iteration for no reason.
      val stdin = new java.io.BufferedReader(new java.io.InputStreamReader(System.in))
      Scope.apply[Unit] { (s: Scope) =>
        given Scope = s
        var continue = true
        while continue do
          // A build failure (bad source, missing symbol, C compile error...)
          // is reported on the protocol (SCALINO_LINKING_FAILED) instead of
          // propagating. The caller (ScalinoCli.linkIncremental) then closes
          // our stdin, which ends this loop, and spawns a fresh process on
          // the next link -- failed builds can leave process-global caches
          // in a half-updated state, so the process isn't reused.
          try
            for c <- configs do
              val outPath = Build.buildCachedAwait(c)
              logger.debug(s"LINKED: $outPath")
            // Sentinel printed only after a successful link, mirroring
            // scala-js-cli's SCALA_JS_LINKING_DONE -- the caller (ScalinoCli,
            // in watch mode) blocks reading stdout for this exact line
            // before treating the rebuild as finished.
            println("SCALINO_LINKING_DONE")
          catch
            case e: Exception =>
              System.err.println(s"scalino-linkdriver: ${e.getMessage}")
              println("SCALINO_LINKING_FAILED")
          System.out.flush()
          // Blocks here until the caller writes a line to trigger the next
          // relink, or closes stdin to end the process gracefully.
          continue = stdin.readLine() != null
      }
    else
      Scope.apply[Unit] { (s: Scope) =>
        given Scope = s
        for c <- configs do
          val outPath = Build.buildCachedAwait(c)
          logger.debug(s"LINKED: $outPath")
      }
