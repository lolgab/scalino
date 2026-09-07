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
    linking: List[String] = Nil,
    compile: List[String] = Nil,
    cCompile: List[String] = Nil,
    cppCompile: List[String] = Nil
  )

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
        case "--linking" => o = o.copy(linking = o.linking :+ rest(i + 1)); i += 1
        case "--compile" => o = o.copy(compile = o.compile :+ rest(i + 1)); i += 1
        case "--c-compile" => o = o.copy(cCompile = o.cCompile :+ rest(i + 1)); i += 1
        case "--cpp-compile" => o = o.copy(cppCompile = o.cppCompile :+ rest(i + 1)); i += 1
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
    val cp = args(0).split(java.io.File.pathSeparator).toSeq.map(Paths.get(_))
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
          .withCOptions(opts.cCompile)
          .withCppOptions(opts.cppCompile)
          .withMode(mode)
          .withGC(gc)
          .withLTO(lto)
          .withBuildTarget(target)
          .withEmbedResources(opts.embedResources)
          .withMultithreading(if opts.multithreading then Some(true) else None)
      )

    val outPath = Scope.apply[java.nio.file.Path] { (s: Scope) =>
      given Scope = s
      Build.buildCachedAwait(config)
    }
    logger.debug(s"LINKED: $outPath")
