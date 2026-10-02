import scala.scalanative.build._
import scala.scalanative.util.Scope
import java.nio.file.Paths

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
      )

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
          // must not kill this process -- ScalinoCli's watch mode expects it
          // to keep running (and keep its warm NIR parse cache) across a
          // save that temporarily broke the build, same as a one-shot
          // scalino-linkdriver invocation would just exit nonzero and let
          // the *caller* keep running. So catch here, report, and loop back
          // to waiting on stdin instead of propagating.
          try
            val outPath = Build.buildCachedAwait(config)
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
      val outPath = Scope.apply[java.nio.file.Path] { (s: Scope) =>
        given Scope = s
        Build.buildCachedAwait(config)
      }
      logger.debug(s"LINKED: $outPath")
