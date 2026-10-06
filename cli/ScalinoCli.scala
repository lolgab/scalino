// scalino: a mini scala-cli, self-hosted -- this file is itself compiled by
// dist/scalino-dotc + dist/scalino-linkdriver (see build/07-build-scalino.sh) into
// a standalone Scala Native binary. No JVM anywhere in this tool or in
// anything it invokes: it drives dist/scalino-dotc and dist/scalino-linkdriver
// directly, and shells out to dist/scalino-cs (coursier's own official `cs`
// launcher, itself a prebuilt GraalVM native-image binary -- bundled and
// renamed by build/06-package.sh so it never needs to be on the end user's
// own PATH) for dependency resolution. See docs/findings.md "Toward a
// build-tool experience without a JVM".
//
// Scope ("mini"): a single `//> using dep`/`//> using scala` directive
// parser (one dep per line, no version constraints/exclusions), a
// compiled-classfile entry-point scanner (scala-cli/Mill-style: scan the
// bytecode scalino-dotc emits for a real `public static void main(String[])`,
// not a source-text heuristic), and a persistent on-disk cache for both
// dependency resolution and compile/link output.
// No multi-Scala-version support (this binary only ever
// targets the one Scala/scala-native version it was built for -- a
// `//> using scala` directive that disagrees just gets a warning).

import java.io.{ByteArrayOutputStream, InputStream}
import java.lang.{ProcessBuilder => JProcessBuilder}
import java.nio.file.{Files, Path, Paths}
import scala.jdk.CollectionConverters.*
import scala.scalanative.{nir => snir}
import scala.scalanative.nir.serialization.deserializeBinary
import scala.scalanative.io.VirtualDirectory

object ScalinoCli:

  // `dist` is wherever this binary itself lives, resolved from its own path
  // (not baked in at build time) so a copied/relocated/extracted-from-a-
  // release-tarball dist/ still works -- see docs/findings.md "Packaging/
  // relocatability". Deliberately NOT "two levels up from the binary,
  // then + /dist": that hardcoded a directory literally named `dist`,
  // which release.yml's tarball layout (scalino sitting directly next to
  // lib/, compiler.cp, etc., with no `dist/` wrapper) doesn't have.
  // selfexe.SelfExe is one of three OS-specific implementations
  // (cli/selfexe/*.scala); build/07-build-scalino.sh picks the right one for
  // the host OS at compile time.
  val dist: String =
    val exe = selfexe.SelfExe.path()
    Paths.get(exe).toRealPath().getParent.toString

  // Every classpath string this file builds/parses uses the platform's real
  // path separator (";" on Windows, ":" elsewhere) -- confirmed via CI: a
  // hardcoded ":" shatters a real Windows path at its own drive-letter
  // colon ("C:\..."), the same bug found and fixed in src/LinkDriver.scala.
  // dist/*.cp itself is already written with this same separator (see
  // build/06-package.sh's vendor_cp), so this just has to match it.
  val CP_SEP: String = java.io.File.pathSeparator

  // ---------------------------------------------------------------------
  // Color output, cargo/scala-cli-style: on by default when the relevant
  // stream is a real terminal, auto-disabled when it's redirected to a
  // file/pipe (so scripted/CI usage and `scalino ... | tee log` both stay
  // plain), and overridable either way via the same conventions cargo and
  // scala-cli already both respect -- NO_COLOR (https://no-color.org: any
  // non-empty value, unconditionally off), CLICOLOR_FORCE (BSD/cargo
  // convention: force color even off a real terminal, e.g. piped into
  // `less -R`), TERM=dumb, and an explicit `--color always|auto|never`
  // flag (cargo's own spelling and values), which always wins over both
  // env vars. `--color` is pulled out of argv once in `main`, before any
  // subcommand-specific parsing ever sees it -- cargo accepts it anywhere
  // on the line, not just as the first argument.
  // ---------------------------------------------------------------------

  enum ColorMode:
    case Always, Never, Auto

  private var colorMode: ColorMode = ColorMode.Auto

  private def setColorMode(value: String): Unit =
    colorMode = value match
      case "always" => ColorMode.Always
      case "never" => ColorMode.Never
      case "auto" => ColorMode.Auto
      case other => die(s"invalid --color value: '$other' (expected always, auto, or never)")

  /** Strips a `--color always|auto|never` (or `--color=<value>`) out of
   *  `args` wherever it appears and sets `colorMode` from it, returning
   *  `args` with that flag removed so no subcommand's own option parser
   *  ever has to know about it. A no-op (mode stays Auto) if absent. */
  def extractColorFlag(args: Array[String]): Array[String] =
    val eqIdx = args.indexWhere(_.startsWith("--color="))
    if eqIdx >= 0 then
      setColorMode(args(eqIdx).stripPrefix("--color="))
      args.patch(eqIdx, Nil, 1)
    else
      val idx = args.indexOf("--color")
      if idx < 0 then args
      else
        if idx + 1 >= args.length then die("--color requires a value: always, auto, or never")
        setColorMode(args(idx + 1))
        args.patch(idx, Nil, 2)

  object Color:
    private val RESET = "[0m"
    private val BOLD = "[1m"
    private val BOLD_RED = "[1;31m"
    private val BOLD_GREEN = "[1;32m"
    private val BOLD_YELLOW = "[1;33m"

    /** Real `isatty(3)` on the given fd (1 = stdout, 2 = stderr) --
     *  posixlib is already on this toolchain's own default native
     *  classpath (dist/nativelibs.cp, see build/01-fetch-deps.sh), so this
     *  links with no extra `//> using dep` anywhere. Defensive try/catch:
     *  a failed color *decision* should never be why the CLI itself
     *  crashes. */
    private def isTty(fd: Int): Boolean =
      try scala.scalanative.posix.unistd.isatty(fd) != 0
      catch case _: Throwable => false

    private def envSet(name: String): Boolean =
      val v = System.getenv(name)
      v != null && v.nonEmpty

    def enabled(stream: java.io.PrintStream): Boolean =
      colorMode match
        case ColorMode.Always => true
        case ColorMode.Never => false
        case ColorMode.Auto =>
          if envSet("NO_COLOR") then false
          else if envSet("CLICOLOR_FORCE") && System.getenv("CLICOLOR_FORCE") != "0" then true
          else if System.getenv("TERM") == "dumb" then false
          else isTty(if stream eq System.err then 2 else 1)

    private def paint(code: String, s: String, stream: java.io.PrintStream): String =
      if enabled(stream) then s"$code$s$RESET" else s

    /** Fatal errors (`die`, a caught `BuildFailed`) -- cargo's bold red. */
    def error(s: String, stream: java.io.PrintStream = System.err): String = paint(BOLD_RED, s, stream)
    /** Recoverable warnings (unsupported directive, scala-version mismatch). */
    def warn(s: String, stream: java.io.PrintStream = System.err): String = paint(BOLD_YELLOW, s, stream)
    /** Build/run progress and success ("resolving", "compiling", "wrote
     *  <path>", ...) -- cargo's own bold green for every such verb, success
     *  included, not just a distinct "done" color. */
    def action(s: String, stream: java.io.PrintStream = System.err): String = paint(BOLD_GREEN, s, stream)
    /** scala-cli's dim gray for its own "Compiling project (...)" progress lines. */
    def gray(s: String, stream: java.io.PrintStream = System.err): String = paint("\u001b[90m", s, stream)
    /** scala-cli's plain (non-bold) red/yellow for its `[error]`/`[warn]` tags. */
    def red(s: String, stream: java.io.PrintStream = System.err): String = paint("\u001b[31m", s, stream)
    def yellow(s: String, stream: java.io.PrintStream = System.err): String = paint("\u001b[33m", s, stream)
    /** Plain emphasis, no color -- section headers in `--help` output. */
    def bold(s: String, stream: java.io.PrintStream = System.out): String = paint(BOLD, s, stream)

  def die(msg: String): Nothing =
    System.err.println(Color.error(s"scalino: $msg"))
    sys.exit(1)

  /** Raised by anything in the compile/link/resolve pipeline that can fail
   *  and recover on the next rebuild (as opposed to a bad CLI invocation,
   *  which is a `die` -- always fatal, checked once before the pipeline
   *  ever runs). In watch mode (`-w`) a BuildFailed is caught and reported
   *  per-iteration instead of killing the watch loop; outside watch mode
   *  it's caught once at the top and turned into the same `die` exit. */
  case class BuildFailed(msg: String) extends RuntimeException(msg)
  def fail(msg: String): Nothing = throw BuildFailed(msg)

  /** scala-cli prints a bare "Compilation failed" after a failed compile (the
   *  diagnostics and the "Error compiling project" line already came before
   *  it) -- not a `scalino:`-prefixed red fatal like every other failure. */
  val CompilationFailed = "Compilation failed"

  def reportBuildFailure(msg: String): Unit =
    if msg == CompilationFailed then System.err.println(msg)
    else System.err.println(Color.error(s"scalino: $msg"))

  /** scala-cli's own wording and tag (note the double space). */
  def fileNotFound(p: Path): Nothing =
    System.err.println(s"[${Color.red("error")}]  File not found: ${p.toAbsolutePath.normalize}")
    sys.exit(1)

  def exitBuildFailure(msg: String): Nothing =
    reportBuildFailure(msg)
    sys.exit(1)

  def readFile(p: Path): String =
    new String(Files.readAllBytes(p), "UTF-8")

  def readAll(in: InputStream): String =
    val buf = new ByteArrayOutputStream()
    val tmp = new Array[Byte](4096)
    var n = in.read(tmp)
    while n != -1 do
      buf.write(tmp, 0, n)
      n = in.read(tmp)
    new String(buf.toByteArray, "UTF-8")

  /** Runs a command with inherited stdio (its own stdout/stderr/stdin pass
   *  straight through), returning the exit code. Used for every step whose
   *  output the user should just see directly: cs downloading, scalino-dotc's
   *  own errors, scalino-linkdriver's build log, and the final program.
   */
  def runInherited(cmd: List[String], cwd: Option[Path] = None, extraEnv: Map[String, String] = Map.empty): Int =
    val pb = new JProcessBuilder(cmd.asJava)
    pb.inheritIO()
    cwd.foreach(p => pb.directory(p.toFile))
    extraEnv.foreach { case (k, v) => pb.environment().put(k, v) }
    pb.start().waitFor()

  /** Runs scalino-dotc with stdout+stderr merged and captured (stdin still
   *  inherited), so its plain-text diagnostics can be re-rendered
   *  scala-cli-style by `Diagnostics`. */
  def runCaptureAll(cmd: List[String]): (Int, String) =
    val pb = new JProcessBuilder(cmd.asJava)
    pb.redirectErrorStream(true)
    pb.redirectInput(JProcessBuilder.Redirect.INHERIT)
    val proc = pb.start()
    val out = readAll(proc.getInputStream)
    (proc.waitFor(), out)

  /** Re-renders dotc's `-color:never` diagnostics the way scala-cli prints
   *  them: every line behind a colored `[warn]`/`[error]` tag, the location
   *  as `./path:line:col` (1-based column), then the message, then the source
   *  line with its caret -- no `-- [E006] Not Found Error ---` rule, no
   *  line-number gutter, no "longer explanation" footer, no "N errors found"
   *  summary. Anything that doesn't look like a diagnostic passes through. */
  object Diagnostics:
    private final case class Header(isWarning: Boolean, path: String, line: Int, col: Int)

    private def parseHeader(l: String): Option[Header] =
      if !l.startsWith("-- ") then None
      else
        val body = l.drop(3).reverse.dropWhile(_ == '-').reverse.trim
        val sep = body.indexOf(": ")
        if sep < 0 then None
        else
          val title = body.substring(0, sep)
          val loc = body.substring(sep + 2)
          val c2 = loc.lastIndexOf(':')
          val c1 = if c2 > 0 then loc.lastIndexOf(':', c2 - 1) else -1
          if c1 < 0 then None
          else
            (loc.substring(c1 + 1, c2).toIntOption, loc.substring(c2 + 1).toIntOption) match
              case (Some(line), Some(col)) => Some(Header(title.contains("Warning"), loc.substring(0, c1), line, col))
              case _ => None

    /** Index of the gutter bar if `l` is a diagnostic body line (`  |...` or `12 |...`). */
    private def gutter(l: String): Int =
      val bar = l.indexOf('|')
      if bar < 1 then -1
      else if l.substring(0, bar).forall(c => c == ' ' || c.isDigit) then bar
      else -1

    private def isSourceLine(l: String): Boolean =
      val bar = gutter(l)
      bar > 0 && l.substring(0, bar).exists(_.isDigit)

    private def isCaretLine(l: String): Boolean =
      val bar = gutter(l)
      bar > 0 && !isSourceLine(l) && l.substring(bar + 1).trim.nonEmpty && l.substring(bar + 1).forall(c => c == ' ' || c == '^')

    private def isSummary(l: String): Boolean =
      l.nonEmpty && l.head.isDigit && l.endsWith(" found") && (l.contains(" error") || l.contains(" warning"))

    def emit(isWarning: Boolean, lines: List[String]): Unit =
      val tag = if isWarning then Color.yellow("warn") else Color.red("error")
      lines.foreach(l => System.err.println(s"[$tag] $l"))

    def displayPath(path: String): String =
      val cwd = Paths.get("").toAbsolutePath.toString
      if !Paths.get(path).isAbsolute then "./" + (if path.startsWith("./") then path.drop(2) else path)
      else if path.startsWith(cwd + "/") then
        val rest = path.drop(cwd.length + 1)
        "./" + (if rest.startsWith("./") then rest.drop(2) else rest)
      else path

    private def render(h: Header, body: List[String]): List[String] =
      val tag = if h.isWarning then Color.yellow("warn") else Color.red("error")
      val prefix = s"[$tag] "
      val (snippet, msg0) =
        val (a, b) = body.span(l => isSourceLine(l) || isCaretLine(l))
        (a.map(l => l.substring(gutter(l) + 1)), b.map(l => l.substring(gutter(l) + 1)))
      val firstIndent = msg0.find(_.trim.nonEmpty).map(_.takeWhile(_ == ' ').length).getOrElse(0)
      val msg = msg0
        .map(l => l.drop(math.min(firstIndent, l.takeWhile(_ == ' ').length)))
        .filterNot(_.trim.startsWith("longer explanation available when compiling with"))
        .reverse.dropWhile(_.trim.isEmpty).reverse
      (s"${displayPath(h.path)}:${h.line}:${h.col + 1}" :: msg ::: snippet).map(prefix + _)

    def render(raw: String): List[String] =
      val lines = raw.split("\n", -1).toList.map(_.stripSuffix("\r"))
      val out = scala.collection.mutable.ListBuffer.empty[String]
      var rest = lines
      while rest.nonEmpty do
        val l = rest.head
        parseHeader(l) match
          case Some(h) =>
            val (body, tail) = rest.tail.span(b => gutter(b) >= 0)
            out ++= render(h, body)
            rest = tail
          case None =>
            if l.trim.nonEmpty && !isSummary(l) then out += l
            rest = rest.tail
      out.toList

  /** scala-cli's progress-line label: `Scala 3.9.0, JVM (27)` there, the
   *  platform this toolchain actually targets here. */
  def buildLabel: String = s"Scala ${BuildInfo.scalaVersion}, Scala Native ${BuildInfo.nativeVersion}"

  /** Runs a command capturing stdout, with stderr passed straight through
   *  (matches `cs fetch --classpath`: download progress on stderr, the
   *  classpath value on stdout). */
  def runCaptureStdout(cmd: List[String]): (Int, String) =
    val pb = new JProcessBuilder(cmd.asJava)
    pb.redirectError(JProcessBuilder.Redirect.INHERIT)
    // Only `cs` reads these; harmless for any other command routed through here.
    envOpt("SCALINO_CACHE").foreach(c => pb.environment().put("COURSIER_CACHE", c))
    if offlineMode then pb.environment().put("COURSIER_MODE", "offline")
    val proc = pb.start()
    val out = readAll(proc.getInputStream)
    (proc.waitFor(), out.trim)

  /** One already-running `dist/scalino-linkdriver --long-running` process,
   *  reused across `-w`/`--watch` rebuilds (LinkDriver.scala's stdin/stdout
   *  protocol, mirroring scala-js-cli#64's `--longRunning`). Kept alive so
   *  its patched scala.scalanative.linker.ClassPath in-memory NIR parse
   *  cache (patches/scala-native-0049) actually survives between rebuilds --
   *  a one-shot (non-watch) build never touches this, and still goes
   *  through `runInherited` below, spawning and exiting once as before. */
  private case class LongRunningLinker(
    argv: List[String],
    proc: Process,
    out: java.io.BufferedReader,
    in: java.io.OutputStream
  )
  private var longRunningLinker: Option[LongRunningLinker] = None

  /** Closes stdin (graceful exit), then force-kills if it doesn't exit in 2s. */
  private def terminateLinker(d: LongRunningLinker): Unit =
    scala.util.Try(d.in.close())
    if !d.proc.waitFor(2, java.util.concurrent.TimeUnit.SECONDS) then d.proc.destroyForcibly()

  /** Drives `argv` (the same argv `runInherited` would have used, minus the
   *  trailing `--long-running` this adds itself) through the persistent
   *  linkdriver process, respawning it if this is the first call, the
   *  previous process died, or `argv` changed (different classpath, main
   *  class, or any native flag -- e.g. switching which file is being run
   *  under `-w`). Streams the driver's stdout straight to our own stdout
   *  (matching `runInherited`'s inherited-IO look) until the sentinel line
   *  LinkDriver.scala prints at the end of each relink, and returns 0/1 the
   *  same way `runInherited`'s exit code would.
   */
  def linkIncremental(argv: List[String]): Int =
    val (driver, alreadyBuilding) = longRunningLinker match
      case Some(d) if d.argv == argv && d.proc.isAlive => (d, false)
      case existing =>
        existing.foreach(terminateLinker)
        val pb = new JProcessBuilder((argv :+ "--long-running").asJava)
        pb.redirectError(JProcessBuilder.Redirect.INHERIT)
        val proc = pb.start()
        val d = LongRunningLinker(
          argv,
          proc,
          new java.io.BufferedReader(new java.io.InputStreamReader(proc.getInputStream)),
          proc.getOutputStream
        )
        longRunningLinker = Some(d)
        (d, true)

    // A freshly-spawned process is already linking on its own; an existing
    // one is blocked waiting on stdin (LinkDriver.scala's `readLine()`) for
    // exactly this trigger.
    if !alreadyBuilding then
      driver.in.write('\n'.toInt)
      driver.in.flush()

    var line = driver.out.readLine()
    while line != null && line != "SCALINO_LINKING_DONE" && line != "SCALINO_LINKING_FAILED" do
      println(line)
      line = driver.out.readLine()

    if line == "SCALINO_LINKING_DONE" then 0
    else if line == "SCALINO_LINKING_FAILED" then
      // A failed link may leave the process's process-global caches (NIR
      // parse cache, previousReach, Interflow state) half-updated, so
      // don't reuse it: terminate it and let the next link spawn a fresh one.
      terminateLinker(driver)
      longRunningLinker = None
      1
    else
      longRunningLinker = None
      1

  /** Never follows symlinks: a link (even a dangling one) is removed, not its
   *  target. Build dirs hold links into shared dirs (`clang-resource/lib` ->
   *  the sysroot's compiler-rt). */
  def deleteRecursively(p: Path): Unit =
    import java.nio.file.LinkOption.NOFOLLOW_LINKS
    if Files.exists(p, NOFOLLOW_LINKS) then
      if Files.isDirectory(p, NOFOLLOW_LINKS) then
        Option(p.toFile.listFiles()).foreach(_.foreach(f => deleteRecursively(f.toPath)))
      Files.delete(p)

  def findOnPath(name: String): String =
    val (code, out) = runCaptureStdout(List("sh", "-c", s"command -v $name"))
    if code != 0 || out.isEmpty then fail(s"'$name' not found on PATH")
    out

  // ---------------------------------------------------------------------
  // Directives: `//> using <key> "value"[, "value2"...]`, one per line,
  // anywhere in the file. Not a real directive parser (no multi-line `//>
  // using` blocks, no `-`/`.`-namespaced keys beyond what's listed below),
  // but covers scala-cli's common single-line quoted-value form, including
  // its plural aliases (`dep`/`deps`, `option`/`options`).
  // ---------------------------------------------------------------------

  case class Directives(
    deps: List[String],
    compileOnlyDeps: List[String],
    testDeps: List[String],
    scalaVersion: Option[String],
    mainClass: Option[String],
    options: List[String],
    testFramework: Option[String],
    nativeMode: Option[String],
    nativeGc: Option[String],
    nativeLto: Option[String],
    nativeClang: Option[String],
    nativeClangPP: Option[String],
    nativeLinking: List[String],
    nativeCompile: List[String],
    nativeCCompile: List[String],
    nativeCppCompile: List[String],
    nativePrune: List[String],
    nativeTarget: Option[String],
    nativeEmbedResources: Option[Boolean],
    nativeMultithreading: Option[Boolean],
    nativeDirectCodegen: Option[Boolean],
    nativeCompactHeaders: Option[Boolean],
    nativeCompactByteArrays: Option[Boolean],
    nativeGcStwSweep: Option[Boolean],
    nativeHeapHistogram: Option[Boolean],
    nativeOptimize: Option[Boolean],
    jars: List[String],
    testOptions: List[String],
    resourceDirs: List[String],
    repositories: List[String],
    pkg: Map[String, List[String]] = Map.empty,
    nativeTargetTriples: List[String] = Nil,
    nativeSysroots: List[String] = Nil
  )

  private val quotedRe = """"([^"]*)"""".r
  private def usingRe(key: String) = s"""//>\\s*using\\s+${java.util.regex.Pattern.quote(key)}\\b(.*)$$""".r

  /** Every `//> using <key>` this parser actually understands -- kept in
   *  sync by hand with parseDirectives below, so an unrecognized key (e.g.
   *  scala-cli's `platform`/`jvm`/`toolkit`/`resourceDir`/`publish.*`) can be
   *  flagged instead of silently doing nothing. */
  private val recognizedDirectiveKeys = Set(
    "dep", "deps", "dependency", "dependencies",
    "compileOnly.dep", "compileOnly.deps", "compileOnly.dependency", "compileOnly.dependencies",
    "test.dep", "test.deps", "test.dependency", "test.dependencies",
    "scala", "mainClass", "options", "option", "scalacOption", "scalacOptions",
    "test.scalacOption", "test.scalacOptions",
    "testFramework", "test.framework", "jar", "jars", "resourceDir", "resourceDirs",
    "repository", "repositories", "file", "files", "exclude",
    "nativeMode", "nativeGc", "nativeLto", "nativeClang", "nativeClangPP", "nativeClangPp",
    "nativeLinking", "nativeCompile", "nativeCCompile", "nativeCppCompile", "nativePrune", "nativeTarget",
    "nativeTargetTriple", "nativeTargetTriples", "nativeSysroot",
    "nativeEmbedResources", "nativeMultithreading", "nativeDirectCodegen", "nativeCompactHeaders", "nativeCompactByteArrays", "nativeGcStwSweep", "nativeHeapHistogram", "nativeOptimize",
    // `scalino package --format ...` metadata -- see cli/Packaging.scala
    "packageName", "packageVersion", "packageDescription", "packageMaintainer", "packageLicense",
    "packageHomepage", "packageDep", "packageDeps", "packageFile", "packageFiles",
    "packageDockerBase", "packageDockerImage", "packageReleaseUrl"
    // deliberately NOT "nativeVersion": this toolchain only ever targets the
    // one pinned scala-native version it was built for (see the "scala"
    // directive's own version-mismatch warning below for the same reason) --
    // an unrecognized "nativeVersion" directive falls through to the generic
    // unsupported-directive warning instead.
  )
  private val anyDirectiveKeyRe = """//>\s*using\s+(\S+)""".r

  /** All values on a `//> using <key> ...` line, trying each of `keys` in
   *  turn (so callers can accept e.g. both `dep` and `deps`). Values are
   *  normally quoted (scala-cli's `"org::name:version"` form), but a lone
   *  unquoted token (`//> using dep org::name:version`, no quotes) is also
   *  accepted as a single bare value, matching real scala-cli. */
  private def directiveValues(line: String, keys: String*): Option[List[String]] =
    keys.iterator.flatMap { k =>
      usingRe(k).findFirstMatchIn(line).map { m =>
        val rest = m.group(1)
        val quoted = quotedRe.findAllMatchIn(rest).map(_.group(1)).toList
        if quoted.nonEmpty then quoted
        else rest.trim match
          case "" => Nil
          case bare => List(bare)
      }
    }.nextOption()

  /** Same as `directiveValues`, for the handful of boolean-valued directives
   *  (`nativeEmbedResources`/`nativeMultithreading`) -- `//> using nativeMultithreading true`
   *  (or a bare `//> using nativeMultithreading` with no value, treated as `true`). */
  private def directiveBool(line: String, keys: String*): Option[Boolean] =
    directiveValues(line, keys*).map(_.headOption.map(_.trim.toLowerCase)).flatMap {
      case Some("false") => Some(false)
      case Some(_) | None => Some(true)
    }

  def parseDirectives(sources: List[Path]): Directives =
    var deps = List.empty[String]
    var compileOnlyDeps = List.empty[String]
    var testDeps = List.empty[String]
    var scalaVersion = Option.empty[String]
    var mainClass = Option.empty[String]
    var options = List.empty[String]
    var testFramework = Option.empty[String]
    var nativeMode = Option.empty[String]
    var nativeGc = Option.empty[String]
    var nativeLto = Option.empty[String]
    var nativeClang = Option.empty[String]
    var nativeClangPP = Option.empty[String]
    var nativeLinking = List.empty[String]
    var nativeCompile = List.empty[String]
    var nativeCCompile = List.empty[String]
    var nativeCppCompile = List.empty[String]
    var nativePrune = List.empty[String]
    var nativeTarget = Option.empty[String]
    var nativeTargetTriples = List.empty[String]
    var nativeSysroots = List.empty[String]
    var nativeEmbedResources = Option.empty[Boolean]
    var nativeMultithreading = Option.empty[Boolean]
    var nativeDirectCodegen = Option.empty[Boolean]
    var nativeCompactHeaders = Option.empty[Boolean]
    var nativeCompactByteArrays = Option.empty[Boolean]
    var nativeGcStwSweep = Option.empty[Boolean]
    var nativeHeapHistogram = Option.empty[Boolean]
    var nativeOptimize = Option.empty[Boolean]
    var jars = List.empty[String]
    var testOptions = List.empty[String]
    var resourceDirs = List.empty[String]
    var repositories = List.empty[String]
    var pkg = Map.empty[String, List[String]]
    val warnedKeys = scala.collection.mutable.Set.empty[String]
    // Directive lines must be a comment-only line, `//>` as its first
    // non-blank characters -- NOT just "contains `//>` anywhere". An
    // unanchored scan also matches `//> using ...` mentioned in prose
    // (scaladoc explaining the directive syntax, a `printUsage` help-text
    // string literal like `|  //> using dep "..."`, etc.), which isn't a
    // directive at all -- this repo's own cli/ScalinoCli.scala is full of such
    // mentions, and scanning "." pulls that file in as a source.
    for src <- sources; (rawLine, lineIdx) <- readFile(src).linesIterator.zipWithIndex do
      val line = rawLine.trim
      if line.startsWith("//>") then
        anyDirectiveKeyRe.findFirstMatchIn(line).foreach { m =>
          val key = m.group(1)
          if !recognizedDirectiveKeys(key) && warnedKeys.add(key) then
            // scala-cli-style diagnostic (it hard-errors on these; we keep going).
            val keyIdx = rawLine.indexOf(key, rawLine.indexOf("using") + 5)
            val values = rawLine.substring(keyIdx + key.length).trim
            Diagnostics.emit(true, List(
              s"${Diagnostics.displayPath(src.toString)}:${lineIdx + 1}:${keyIdx + 1}",
              s"Unsupported directive: $key" + (if values.nonEmpty then s" with values: $values" else "") + " -- ignoring",
              rawLine,
              " " * keyIdx + "^" * key.length
            ))
        }
        directiveValues(line, "dep", "deps", "dependency", "dependencies").foreach(vs => deps = deps ++ vs)
        directiveValues(line, "compileOnly.dep", "compileOnly.deps", "compileOnly.dependency", "compileOnly.dependencies").foreach(vs => compileOnlyDeps = compileOnlyDeps ++ vs)
        directiveValues(line, "test.dep", "test.deps", "test.dependency", "test.dependencies").foreach(vs => testDeps = testDeps ++ vs)
        directiveValues(line, "scala").foreach(_.headOption.foreach(v => scalaVersion = Some(v)))
        directiveValues(line, "mainClass").foreach(_.headOption.foreach(v => mainClass = Some(v)))
        directiveValues(line, "options", "option", "scalacOption", "scalacOptions").foreach(vs => options = options ++ vs)
        directiveValues(line, "test.scalacOption", "test.scalacOptions").foreach(vs => testOptions = testOptions ++ vs)
        directiveValues(line, "testFramework", "test.framework").foreach(_.headOption.foreach(v => testFramework = Some(v)))
        directiveValues(line, "jar", "jars").foreach(vs => jars = jars ++ vs)
        directiveValues(line, "resourceDir", "resourceDirs").foreach(vs => resourceDirs = resourceDirs ++ vs)
        directiveValues(line, "repository", "repositories").foreach(vs => repositories = repositories ++ vs)
        directiveValues(line, "nativeMode").foreach(_.headOption.foreach(v => nativeMode = Some(v)))
        directiveValues(line, "nativeGc").foreach(_.headOption.foreach(v => nativeGc = Some(v)))
        directiveValues(line, "nativeLto").foreach(_.headOption.foreach(v => nativeLto = Some(v)))
        directiveValues(line, "nativeClang").foreach(_.headOption.foreach(v => nativeClang = Some(v)))
        directiveValues(line, "nativeClangPP", "nativeClangPp").foreach(_.headOption.foreach(v => nativeClangPP = Some(v)))
        directiveValues(line, "nativeLinking").foreach(vs => nativeLinking = nativeLinking ++ vs)
        directiveValues(line, "nativeCompile").foreach(vs => nativeCompile = nativeCompile ++ vs)
        directiveValues(line, "nativeCCompile").foreach(vs => nativeCCompile = nativeCCompile ++ vs)
        directiveValues(line, "nativeCppCompile").foreach(vs => nativeCppCompile = nativeCppCompile ++ vs)
        directiveValues(line, "nativePrune").foreach(vs => nativePrune = nativePrune ++ vs)
        directiveValues(line, "nativeTarget").foreach(_.headOption.foreach(v => nativeTarget = Some(v)))
        directiveValues(line, "nativeTargetTriple", "nativeTargetTriples").foreach(vs => nativeTargetTriples = nativeTargetTriples ++ vs)
        directiveValues(line, "nativeSysroot").foreach(vs => nativeSysroots = nativeSysroots ++ vs)
        directiveBool(line, "nativeEmbedResources").foreach(v => nativeEmbedResources = Some(v))
        directiveBool(line, "nativeMultithreading").foreach(v => nativeMultithreading = Some(v))
        directiveBool(line, "nativeDirectCodegen").foreach(v => nativeDirectCodegen = Some(v))
        directiveBool(line, "nativeCompactHeaders").foreach(v => nativeCompactHeaders = Some(v))
        directiveBool(line, "nativeCompactByteArrays").foreach(v => nativeCompactByteArrays = Some(v))
        directiveBool(line, "nativeGcStwSweep").foreach(v => nativeGcStwSweep = Some(v))
        directiveBool(line, "nativeHeapHistogram").foreach(v => nativeHeapHistogram = Some(v))
        directiveBool(line, "nativeOptimize").foreach(v => nativeOptimize = Some(v))
        for (spelling, canonical) <- Packaging.DirectiveAliases do
          directiveValues(line, spelling).foreach(vs => pkg = pkg.updated(canonical, pkg.getOrElse(canonical, Nil) ++ vs))
    Directives(
      deps.distinct, compileOnlyDeps.distinct, testDeps.distinct, scalaVersion, mainClass, options, testFramework,
      nativeMode, nativeGc, nativeLto, nativeClang, nativeClangPP,
      nativeLinking, nativeCompile, nativeCCompile, nativeCppCompile, nativePrune,
      nativeTarget, nativeEmbedResources, nativeMultithreading, nativeDirectCodegen, nativeCompactHeaders, nativeCompactByteArrays, nativeGcStwSweep, nativeHeapHistogram, nativeOptimize,
      jars.distinct, testOptions, resourceDirs.distinct, repositories.distinct, pkg,
      nativeTargetTriples, nativeSysroots
    )

  /** scala-cli's three dependency formats:
   *   - `org:name:version`   exact artifact (sbt `%`)              -> unchanged
   *   - `org::name:version`  Scala-version cross (sbt `%%`)        -> `org:name_3:version`
   *   - `org::name::version` platform+Scala cross (sbt `%%%`)      -> `org:name_native<binVer>_3:version`
   *  (there is no `org:name::version` form -- scala-cli doesn't have a
   *  "platform-only, not Scala-version" cross suffix, so a lone `::` before
   *  the version with a single-colon name prefix is treated as malformed
   *  and passed through unchanged rather than guessed at.)
   */
  def toCoursierCoord(dep: String): String =
    dep.split("::") match
      case Array(_) => dep // no "::" at all: org:name:version, unchanged
      case Array(org, nameAndVersion) =>
        val i = nameAndVersion.indexOf(':')
        if i < 0 then dep
        else s"$org:${nameAndVersion.substring(0, i)}_3:${nameAndVersion.substring(i + 1)}"
      case Array(org, name, version) =>
        s"$org:${name}_native${BuildInfo.nativeBinaryVersion}_3:$version"
      case _ => dep

  def sanitizeKey(s: String): String =
    s.map(c => if c.isLetterOrDigit then c else '_')

  /** FNV-1a 64-bit, hex-encoded -- for cache keys derived from a *resolved
   *  classpath* (many absolute paths) rather than a short dep-coordinate
   *  list: `sanitizeKey` only substitutes characters 1:1, so a classpath's
   *  worth of them still blows past the filesystem's filename-length limit
   *  ("File name too long") -- this collapses it to a fixed-size digest
   *  instead. */
  def hashKey(s: String): String =
    var hash = 0xcbf29ce484222325L
    val prime = 0x100000001b3L
    for b <- s.getBytes("UTF-8") do
      hash = (hash ^ (b & 0xffL)) * prime
    java.lang.Long.toHexString(hash)

  /** A `::name::version` (scala-native cross) dependency's own published
   *  build pulls its OWN version of scala-native's runtime jars transitively
   *  (whatever scala-native release it happened to be built against) --
   *  almost never the exact same patch version as ours. Left alone, both
   *  end up on the link classpath and clang fails with hundreds of
   *  duplicate-symbol errors (two copies of the GC, two copies of libc
   *  shims, ...). We always supply these ourselves (dist/nativelibs.cp), so
   *  they're excluded here rather than resolved a second time. */
  /** Root coordinates always added to every `cs fetch`, regardless of what
   *  the user asked for -- `cs fetch` (unlike a real build tool's own
   *  dependency management) resolves Maven `provided`-scope dependencies as
   *  excluded by default, with no CLI flag to change that (confirmed
   *  against real `cs fetch --help`/`cs resolve --help`: neither exposes a
   *  scope/configuration override). A dependency listed explicitly as a
   *  ROOT coordinate, though, always resolves at ITS OWN default scope
   *  (compile) regardless of what scope it'd have as someone else's
   *  transitive dependency -- so re-requesting it directly here is enough
   *  to pull it in.
   *
   *  `org.typelevel:scalac-compat-annotation_3` is exactly this case: it's
   *  a `provided`-scope dependency of `org.typelevel::literally` (itself
   *  pulled in by `http4s-core` and others), needed at MACRO-EXPANSION
   *  time (an inlined reference to one of its annotation classes shows up
   *  in the retained tree literally's own macro splices), not at runtime --
   *  found via `scalino test .` against a real project (`~/scala/ape`)
   *  using http4s's `uri"..."` literal macro, which failed to even TYPE
   *  (a generic "undefined: ..." dotc error, unrelated to macro
   *  interpretation) because the annotation class was simply missing from
   *  the resolved classpath. Tiny (a handful of annotation-only classes,
   *  no real behavior) and broadly needed by any typelevel library using
   *  the same `literally`-based macro pattern, not just this one project --
   *  same category of "always supply this ourselves" as the scala-native
   *  runtime libs below, just for a compile-time gap instead of a runtime
   *  duplicate-symbol one.
   */
  private def alwaysIncludedArtifacts: List[String] =
    List("org.typelevel:scalac-compat-annotation_3:0.1.4")

  private def excludedArtifacts: List[String] =
    val v = BuildInfo.nativeBinaryVersion
    List(
      s"org.scala-native:nativelib_native${v}_3",
      s"org.scala-native:javalib_native${v}_3",
      s"org.scala-native:auxlib_native${v}_3",
      s"org.scala-native:posixlib_native${v}_3",
      s"org.scala-native:clib_native${v}_3",
      s"org.scala-native:windowslib_native${v}_3",
      s"org.scala-native:scala3lib_native${v}_3",
      s"org.scala-native:scalalib_native${v}_2.13",
      "org.scala-lang:scala3-library_3",
      "org.scala-lang:scala-library"
    )

  /** Resolves `deps` to a classpath via native `cs fetch --classpath`,
   *  cached on disk keyed by the sorted dependency list -- `cs` itself does
   *  its own artifact caching, this just skips invoking it at all (and its
   *  network round-trip) when we've already resolved this exact dependency
   *  set before. */
  /** True if every classpath entry in `cp` still exists on disk -- a cache
   *  hit whose entries point into `~/.cache`/`~/Library/Caches/Coursier`
   *  can go stale if that cache is cleared out from under it (by `cs
   *  cache clear`, a disk-cleanup tool, etc), independently of this
   *  cache's own key/mtime. Re-resolving in that case beats handing
   *  scalino-dotc a classpath full of dangling paths and watching it
   *  report every symbol from those jars as "not found". */
  def cachedClasspathStillValid(cp: String): Boolean =
    cp.split(CP_SEP).filter(_.nonEmpty).forall(e => Files.exists(Paths.get(e)))

  def resolveDeps(deps: List[String], cacheDir: Path, repositories: List[String] = Nil): String =
    if deps.isEmpty then ""
    else
      // scalino.lock.json (see `scalino lock`) wins over everything: when it
      // covers this dependency set and its artifacts are on disk, no `cs`
      // process (and no network) is involved at all.
      lockedClasspath(lockKeyFor(deps, repositories)).getOrElse {
        if !offlineMode && loadLock().nonEmpty then
          System.err.println(Color.warn(
            s"scalino: warning: ${deps.mkString(", ")} not covered by $LockFileName -- run `scalino lock` to update it"))
        Files.createDirectories(cacheDir)
        val key = sanitizeKey((deps.sorted ::: repositories.sorted).mkString(","))
        val cacheFile = cacheDir.resolve(s"deps-$key.cp")
        val cached = Option.when(Files.exists(cacheFile))(readFile(cacheFile)).filter(cachedClasspathStillValid)
        cached.getOrElse {
          System.err.println(Color.action(s"scalino: resolving ${deps.mkString(", ")}"))
          val cp = csFetchClasspath(deps, repositories)
          Files.write(cacheFile, cp.getBytes("UTF-8"))
          cp
        }
      }

  /** The raw `cs fetch --classpath` call behind `resolveDeps` (no caching, no
   *  lockfile) -- also what `scalino lock` runs to (re)generate the lock. */
  private def csFetchClasspath(deps: List[String], repositories: List[String]): String =
    val cs = s"$dist/scalino-cs"
    val coords = deps.map(toCoursierCoord) ::: alwaysIncludedArtifacts
    val excludeFlags = excludedArtifacts.flatMap(a => List("-E", a))
    val repoFlags = repositories.flatMap(r => List("-r", r))
    val (code, cp) = runCaptureStdout(cs :: "fetch" :: coords ::: excludeFlags ::: repoFlags ::: List("--classpath"))
    if code != 0 then
      val hint = if offlineMode then " (offline mode: every artifact must already be in the cache -- see `scalino lock`)" else ""
      fail(s"dependency resolution failed for: ${deps.mkString(", ")}$hint")
    cp

  // ---------------------------------------------------------------------
  // Offline mode + lockfile, for hermetic builds (Nix, Bazel, air-gapped CI).
  //
  // `scalino lock` records, per dependency set, the exact jars `cs fetch`
  // resolved: cache-relative path, source URL and sha256. With a lockfile
  // present `resolveDeps` builds the classpath straight from it (no coursier
  // resolution => no POMs needed, transitive versions pinned), and an
  // external tool can fetch every `{url, sha256}` up front (Nix:
  // `pkgs.fetchurl` per artifact) and lay the files out at `path` under
  // $SCALINO_CACHE. `--offline`/SCALINO_OFFLINE=1 additionally forbids any
  // network use (cs runs with COURSIER_MODE=offline).
  // ---------------------------------------------------------------------

  val LockFileName = "scalino.lock.json"

  private def envOpt(name: String): Option[String] =
    Option(System.getenv(name)).filter(_.nonEmpty)

  /** Set by `--offline` (parseRunOpts) or SCALINO_OFFLINE=1. */
  private var offlineMode: Boolean = envOpt("SCALINO_OFFLINE").exists(_ != "0")

  /** Where coursier keeps downloaded artifacts: SCALINO_CACHE, else
   *  COURSIER_CACHE (used as-is, laid out as `<root>/https/<host>/...`),
   *  else coursier's platform default. */
  def coursierCacheRoot: Path =
    envOpt("SCALINO_CACHE").orElse(envOpt("COURSIER_CACHE")).map(Paths.get(_)).getOrElse {
      val home = System.getProperty("user.home")
      if System.getProperty("os.name", "").contains("Mac") then Paths.get(home, "Library", "Caches", "Coursier", "v1")
      else Paths.get(envOpt("XDG_CACHE_HOME").getOrElse(s"$home/.cache"), "coursier", "v1")
    }

  /** One locked jar. `path` is relative to the cache root (`https/<host>/...`)
   *  unless `local`, in which case it is an absolute path to an artifact that
   *  came from a `file:` repository (not reproducible; kept so the lock still
   *  yields a complete classpath on the machine that made it). */
  case class LockArtifact(path: String, url: Option[String], sha256: Option[String], local: Boolean)

  def lockKeyFor(deps: List[String], repositories: List[String]): String =
    (deps.sorted ::: repositories.sorted).mkString(",")

  private val Sha256K: Array[Int] = Array(
    0x428a2f98, 0x71374491, 0xb5c0fbcf, 0xe9b5dba5, 0x3956c25b, 0x59f111f1, 0x923f82a4, 0xab1c5ed5,
    0xd807aa98, 0x12835b01, 0x243185be, 0x550c7dc3, 0x72be5d74, 0x80deb1fe, 0x9bdc06a7, 0xc19bf174,
    0xe49b69c1, 0xefbe4786, 0x0fc19dc6, 0x240ca1cc, 0x2de92c6f, 0x4a7484aa, 0x5cb0a9dc, 0x76f988da,
    0x983e5152, 0xa831c66d, 0xb00327c8, 0xbf597fc7, 0xc6e00bf3, 0xd5a79147, 0x06ca6351, 0x14292967,
    0x27b70a85, 0x2e1b2138, 0x4d2c6dfc, 0x53380d13, 0x650a7354, 0x766a0abb, 0x81c2c92e, 0x92722c85,
    0xa2bfe8a1, 0xa81a664b, 0xc24b8b70, 0xc76c51a3, 0xd192e819, 0xd6990624, 0xf40e3585, 0x106aa070,
    0x19a4c116, 0x1e376c08, 0x2748774c, 0x34b0bcb5, 0x391c0cb3, 0x4ed8aa4a, 0x5b9cca4f, 0x682e6ff3,
    0x748f82ee, 0x78a5636f, 0x84c87814, 0x8cc70208, 0x90befffa, 0xa4506ceb, 0xbef9a3f7, 0xc67178f2
  )

  /** Hand-rolled SHA-256 (FIPS 180-4): this toolchain's javalib has no
   *  java.security.MessageDigest, and the digest must match what Nix's
   *  fetchurl computes over the same bytes. */
  def sha256Hex(data: Array[Byte]): String =
    val h = Array(0x6a09e667, 0xbb67ae85, 0x3c6ef372, 0xa54ff53a, 0x510e527f, 0x9b05688c, 0x1f83d9ab, 0x5be0cd19)
    // message || 0x80 || zero pad || 64-bit big-endian bit length, to a multiple of 64 bytes
    val padded = new Array[Byte]((data.length + 9 + 63) / 64 * 64)
    System.arraycopy(data, 0, padded, 0, data.length)
    padded(data.length) = 0x80.toByte
    val bitLen = data.length.toLong * 8
    for k <- 0 until 8 do padded(padded.length - 1 - k) = ((bitLen >>> (8 * k)) & 0xff).toByte
    val w = new Array[Int](64)
    for block <- 0 until padded.length / 64 do
      for t <- 0 until 16 do
        val o = block * 64 + t * 4
        w(t) = ((padded(o) & 0xff) << 24) | ((padded(o + 1) & 0xff) << 16) | ((padded(o + 2) & 0xff) << 8) | (padded(o + 3) & 0xff)
      for t <- 16 until 64 do
        val s0 = Integer.rotateRight(w(t - 15), 7) ^ Integer.rotateRight(w(t - 15), 18) ^ (w(t - 15) >>> 3)
        val s1 = Integer.rotateRight(w(t - 2), 17) ^ Integer.rotateRight(w(t - 2), 19) ^ (w(t - 2) >>> 10)
        w(t) = w(t - 16) + s0 + w(t - 7) + s1
      var a = h(0); var b = h(1); var c = h(2); var d = h(3)
      var e = h(4); var f = h(5); var g = h(6); var hh = h(7)
      for t <- 0 until 64 do
        val bigS1 = Integer.rotateRight(e, 6) ^ Integer.rotateRight(e, 11) ^ Integer.rotateRight(e, 25)
        val ch = (e & f) ^ (~e & g)
        val t1 = hh + bigS1 + ch + Sha256K(t) + w(t)
        val bigS0 = Integer.rotateRight(a, 2) ^ Integer.rotateRight(a, 13) ^ Integer.rotateRight(a, 22)
        val maj = (a & b) ^ (a & c) ^ (b & c)
        val t2 = bigS0 + maj
        hh = g; g = f; f = e; e = d + t1; d = c; c = b; b = a; a = t1 + t2
      h(0) += a; h(1) += b; h(2) += c; h(3) += d; h(4) += e; h(5) += f; h(6) += g; h(7) += hh
    h.map(x => (0 until 8).map(k => Integer.toHexString((x >>> (28 - 4 * k)) & 0xf)).mkString).mkString

  private def sha256Hex(p: Path): String = sha256Hex(Files.readAllBytes(p))

  private def lockArtifactFor(classpathEntry: String): LockArtifact =
    val abs = Paths.get(classpathEntry).toAbsolutePath.normalize
    val root = coursierCacheRoot.toAbsolutePath.normalize
    val rel: Option[String] =
      if abs.startsWith(root) then Some(root.relativize(abs).toString)
      else
        val s = abs.toString
        List("/https/", "/http/").map(s.indexOf(_)).filter(_ >= 0).minOption.map(i => s.substring(i + 1))
    rel.filter(r => r.startsWith("https/") || r.startsWith("http/")) match
      case Some(r) =>
        val slash = r.indexOf('/')
        LockArtifact(r, Some(r.substring(0, slash) + "://" + r.substring(slash + 1)), Some(sha256Hex(abs)), false)
      case None =>
        System.err.println(Color.warn(s"scalino: warning: $abs is not from a remote repository -- lock entry is machine-local"))
        LockArtifact(abs.toString, None, None, true)

  private var lockCache: Option[Map[String, List[LockArtifact]]] = None

  /** Parsed `scalino.lock.json` (dependency-set key -> artifacts in classpath
   *  order); empty if the file doesn't exist. */
  private def loadLock(): Map[String, List[LockArtifact]] =
    lockCache.getOrElse {
      val p = Paths.get(LockFileName)
      val parsed =
        if !Files.exists(p) then Map.empty[String, List[LockArtifact]]
        else
          try
            def obj(a: Any): Map[String, Any] = a.asInstanceOf[Map[String, Any]]
            def arr(a: Any): List[Any] = a.asInstanceOf[List[Any]]
            val sets = obj(obj(new MiniJsonParser(readFile(p)).parseDocument())("sets"))
            sets.map { case (key, set) =>
              key -> arr(obj(set)("artifacts")).map { a =>
                val m = obj(a)
                LockArtifact(
                  m("path").asInstanceOf[String],
                  m.get("url").map(_.asInstanceOf[String]),
                  m.get("sha256").map(_.asInstanceOf[String]),
                  m.get("local").contains(true)
                )
              }
            }
          catch case scala.util.control.NonFatal(e) => fail(s"$LockFileName: malformed (${e.getMessage}) -- regenerate with `scalino lock`")
      lockCache = Some(parsed)
      parsed
    }

  private def lockedClasspath(key: String): Option[String] =
    loadLock().get(key).flatMap { arts =>
      val root = coursierCacheRoot
      val paths = arts.map(a => if a.local then Paths.get(a.path) else root.resolve(a.path))
      val missing = paths.filterNot(Files.exists(_))
      if missing.isEmpty then Some(paths.map(_.toString).mkString(CP_SEP))
      else if offlineMode then
        fail(s"offline: ${missing.size} artifact(s) from $LockFileName missing under $root, e.g. ${missing.head} " +
          "-- pre-populate the cache (SCALINO_CACHE) from the lockfile")
      else None // not downloaded yet: fall back to a normal `cs fetch`
    }

  /** The `-sources.jar`s `setup-ide` wants (see `fetchSourcesBestEffort`),
   *  recorded in the lock so an external fetcher can place them next to the
   *  classes jars -- that sibling layout is how scalino-lsp finds them.
   *  Best-effort like its sibling: a dependency tree where `cs` can't find
   *  every sources jar just yields none for that set. */
  private def csFetchSourceJars(deps: List[String], repositories: List[String]): List[String] =
    val repoFlags = repositories.flatMap(r => List("-r", r))
    val (code, out) = runCaptureStdout(
      s"$dist/scalino-cs" :: "fetch" :: deps.map(toCoursierCoord) ::: repoFlags ::: List("--classifier", "sources"))
    if code != 0 then
      System.err.println(Color.warn(s"scalino: warning: no sources jars recorded for ${deps.mkString(", ")} (not all are published)"))
      Nil
    else out.linesIterator.map(_.trim).filter(_.nonEmpty).toList

  /** `scalino lock [sources...]`: resolves every dependency set the other
   *  commands would resolve (main, test.dep, compile-only; with and without
   *  test/ sources' directives) and writes `scalino.lock.json`. */
  def handleLock(args: Array[String]): Unit =
    val o = defaultToCwd(parseRunOpts(args))
    o.sources.find(!Files.exists(_)).foreach(p => fileNotFound(p))
    val allExpanded = expandSources(o.sources)
    if allExpanded.isEmpty then die("no .scala files found")
    val (mainSources, _) = partitionSources(allExpanded)

    val groups = scala.collection.mutable.LinkedHashMap.empty[String, (List[String], List[String])]
    for srcs <- List(mainSources, allExpanded).filter(_.nonEmpty) do
      val d = parseDirectives(srcs)
      val repos = (d.repositories ++ o.cliRepositories).distinct
      val sets = List(
        (d.deps ++ o.cliDeps).distinct,
        d.testDeps,
        (d.compileOnlyDeps ++ o.cliCompileOnlyDeps).distinct
      )
      for ds <- sets if ds.nonEmpty do groups.getOrElseUpdate(lockKeyFor(ds, repos), (ds, repos))

    try
      val resolved = groups.toList.sortBy(_._1).map { case (key, (ds, repos)) =>
        System.err.println(Color.action(s"scalino: locking ${ds.mkString(", ")}"))
        val cp = csFetchClasspath(ds, repos)
        (key, ds, repos, cp.split(CP_SEP).filter(_.nonEmpty).toList.map(lockArtifactFor), csFetchSourceJars(ds, repos).map(lockArtifactFor).filter(!_.local))
      }
      def artifactJson(a: LockArtifact): String =
        val fields =
          List(Some("path" -> jsonStr(a.path)), a.url.map(u => "url" -> jsonStr(u)),
            a.sha256.map(h => "sha256" -> jsonStr(h)), Option.when(a.local)("local" -> "true")).flatten
        fields.map((k, v) => s"${jsonStr(k)}: $v").mkString("{", ", ", "}")
      def artifactsJson(arts: List[LockArtifact]): String =
        if arts.isEmpty then "[]"
        else arts.map(a => "        " + artifactJson(a)).mkString("[\n", ",\n", "\n      ]")
      val setsJson = resolved.map { case (key, ds, repos, arts, srcs) =>
        s"""    ${jsonStr(key)}: {
           |      "deps": ${jsonArr(ds)},
           |      "repositories": ${jsonArr(repos)},
           |      "artifacts": ${artifactsJson(arts)},
           |      "sources": ${artifactsJson(srcs)}
           |    }""".stripMargin
      }
      val json =
        s"""{
           |  "version": 1,
           |  "scalino": ${jsonStr(BuildInfo.scalinoVersion)},
           |  "sets": {
           |${setsJson.mkString(",\n")}
           |  }
           |}
           |""".stripMargin
      Files.write(Paths.get(LockFileName), json.getBytes("UTF-8"))
      println(s"Wrote $LockFileName (${resolved.map(_._4.size).sum} artifacts in ${resolved.size} dependency set(s))")
    catch case BuildFailed(msg) => exitBuildFailure(msg)

  /** Just enough JSON to read back `scalino.lock.json`: objects -> Map,
   *  arrays -> List, strings, numbers (Double), booleans, null. */
  private final class MiniJsonParser(s: String):
    private var i = 0

    private def err(msg: String): Nothing = throw new IllegalArgumentException(s"$msg at offset $i")
    private def ws(): Unit = while i < s.length && s(i).isWhitespace do i += 1
    private def peek: Char = if i < s.length then s(i) else err("unexpected end of input")

    def parseDocument(): Any =
      val v = value()
      ws()
      if i != s.length then err("trailing data")
      v

    private def value(): Any =
      ws()
      peek match
        case '{' => obj()
        case '[' => arr()
        case '"' => str()
        case 't' => lit("true", true)
        case 'f' => lit("false", false)
        case 'n' => lit("null", null)
        case _ => num()

    private def lit(word: String, v: Any): Any =
      if !s.startsWith(word, i) then err(s"expected $word")
      i += word.length
      v

    private def num(): Double =
      val start = i
      while i < s.length && "+-0123456789.eE".indexOf(s(i)) >= 0 do i += 1
      if start == i then err("unexpected character")
      s.substring(start, i).toDouble

    private def str(): String =
      i += 1
      val sb = new StringBuilder
      while peek != '"' do
        if s(i) == '\\' then
          i += 1
          peek match
            case 'n' => sb.append('\n')
            case 't' => sb.append('\t')
            case 'r' => sb.append('\r')
            case 'b' => sb.append('\b')
            case 'f' => sb.append('\f')
            case 'u' =>
              sb.append(Integer.parseInt(s.substring(i + 1, i + 5), 16).toChar)
              i += 4
            case c => sb.append(c)
        else sb.append(s(i))
        i += 1
      i += 1
      sb.toString

    private def arr(): List[Any] =
      i += 1
      ws()
      if peek == ']' then { i += 1; Nil }
      else
        val b = List.newBuilder[Any]
        var more = true
        while more do
          b += value()
          ws()
          peek match
            case ',' => i += 1
            case ']' => i += 1; more = false
            case _ => err("expected ',' or ']'")
        b.result()

    private def obj(): Map[String, Any] =
      i += 1
      ws()
      if peek == '}' then { i += 1; Map.empty }
      else
        val b = Map.newBuilder[String, Any]
        var more = true
        while more do
          ws()
          if peek != '"' then err("expected string key")
          val k = str()
          ws()
          if peek != ':' then err("expected ':'")
          i += 1
          b += k -> value()
          ws()
          peek match
            case ',' => i += 1
            case '}' => i += 1; more = false
            case _ => err("expected ',' or '}'")
        b.result()

  /** Best-effort: also fetches `-sources.jar` classifiers for `deps`
   *  (transitively, so a click into a transitive dependency's symbols works
   *  too) into the coursier cache, as siblings of the classes jars
   *  `resolveDeps` already resolved -- that's where scalino-lsp's
   *  go-to-definition-into-library-sources (patches/scala3-0014) looks for
   *  them. Never fails the caller: a dependency that doesn't publish
   *  sources, or a network hiccup, just means go-to-def won't resolve into
   *  that one library, same as before this existed. Only called from
   *  `setup-ide` (sources are otherwise dead weight for `run`/`compile`/
   *  `test`), and only attempted once per dependency set -- a marker file,
   *  not the classpath cache file itself, since this is a separate network
   *  round-trip from `resolveDeps`'s own `cs fetch --classpath` and
   *  shouldn't block or duplicate it. */
  def fetchSourcesBestEffort(deps: List[String], cacheDir: Path, repositories: List[String] = Nil): Unit =
    if deps.nonEmpty then
      try
        Files.createDirectories(cacheDir)
        val key = sanitizeKey((deps.sorted ::: repositories.sorted).mkString(","))
        val marker = cacheDir.resolve(s"deps-$key.sources-fetched")
        if !offlineMode && !Files.exists(marker) then
          val cs = s"$dist/scalino-cs"
          val coords = deps.map(toCoursierCoord)
          val repoFlags = repositories.flatMap(r => List("-r", r))
          System.err.println(Color.action(s"scalino: fetching sources for ${deps.mkString(", ")} (best-effort, for go-to-definition)"))
          runCaptureStdout(cs :: "fetch" :: coords ::: repoFlags ::: List("--classifier", "sources"))
          Files.write(marker, Array.emptyByteArray)
      catch case scala.util.control.NonFatal(_) => ()

  /** Same idea as `fetchSourcesBestEffort`, but for the vendored
   *  scala-library/scala3-library jars themselves (`dist/lib/scala-library-
   *  $scalaVersion.jar`, `dist/lib/scala3-library_3-$scalaVersion.jar`) --
   *  those never go through `resolveDeps` at all (`excludedArtifacts`
   *  deliberately keeps the toolchain's own stdlib jars off the coursier-
   *  resolved classpath, see that val's comment), so without this, go-to-
   *  definition into stdlib symbols (`println`, `List`, ...) can never find a
   *  sources jar no matter how many user dependencies get theirs fetched.
   *  patches/scala3-0014 looks for `<jar>-sources.jar` as a sibling of the
   *  jar actually on the classpath, so the fetched jar is placed directly in
   *  `dist/lib` under that exact name -- same layout already used by the
   *  vendored `-sources.jar`s for scala-native's own runtime libs. Best-
   *  effort and marker-gated like its sibling above: never fails the caller,
   *  and only attempted once per `dist` (the version is fixed for a given
   *  build, so there's nothing to key on beyond "have we tried").
   */
  def fetchStdlibSourcesBestEffort(): Unit =
    try
      val libDir = Paths.get(dist, "lib")
      Files.createDirectories(libDir)
      val marker = libDir.resolve(s".stdlib-sources-fetched-${BuildInfo.scalaVersion}")
      if !offlineMode && !Files.exists(marker) then
        val cs = s"$dist/scalino-cs"
        val artifacts = List(
          ("org.scala-lang", "scala-library"),
          ("org.scala-lang", "scala3-library_3")
        )
        for (group, name) <- artifacts do
          val jarName = s"$name-${BuildInfo.scalaVersion}.jar"
          val destSourcesJar = libDir.resolve(s"$name-${BuildInfo.scalaVersion}-sources.jar")
          if Files.exists(libDir.resolve(jarName)) && !Files.exists(destSourcesJar) then
            val (code, path) = runCaptureStdout(
              List(cs, "fetch", "--classifier", "sources", "--intransitive", s"$group:$name:${BuildInfo.scalaVersion}")
            )
            if code == 0 then
              path.linesIterator.map(_.trim).find(_.nonEmpty).foreach { fetched =>
                Files.copy(Paths.get(fetched), destSourcesJar, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
              }
        Files.write(marker, Array.emptyByteArray)
    catch case scala.util.control.NonFatal(_) => ()

  // ---------------------------------------------------------------------
  // Entry-point detection, scala-cli/Mill-style: not a source-text
  // heuristic, but a scan of the *compiled* .class files for a real
  // `public static void main(String[])` method. scalino-dotc emits plain
  // JVM classfiles alongside .nir even though the final artifact is a
  // native binary, so this needs no reflection/classloading machinery --
  // just reading the classfile format directly (stable, tiny, and fully
  // specified, so a hand-rolled parser is cheap and exact). `@main def`,
  // `extends App`, and a hand-written `def main` inside an object all
  // lower to exactly this static forwarder shape, so one check covers all
  // three with no special-casing -- and, unlike a text scan, can't misfire
  // on a `{`/`}` inside a string/comment or a non-top-level main-like def.
  // ---------------------------------------------------------------------

  private val classMagic = 0xCAFEBABEL

  private class ClassReader(bytes: Array[Byte]):
    private var pos = 0
    def u1(): Int = { val v = bytes(pos) & 0xFF; pos += 1; v }
    def u2(): Int = { val hi = u1(); val lo = u1(); (hi << 8) | lo }
    def u4(): Long = { val hi = u2().toLong; val lo = u2().toLong; (hi << 16) | lo }
    def skip(n: Int): Unit = pos += n
    def bytesOf(n: Int): Array[Byte] = { val r = bytes.slice(pos, pos + n); pos += n; r }

  /** Parses just enough of a classfile to answer "does this class declare a
   *  public static `main(String[]): Unit`, and what's its own name" --
   *  constant pool (to resolve the this_class name and method
   *  name/descriptor Utf8s), access flags, and the method table. Field
   *  bodies/other attributes are skipped, not decoded. */
  def scanClassFile(bytes: Array[Byte]): Option[String] =
    try
      val r = ClassReader(bytes)
      if r.u4() != classMagic then return None
      r.skip(4) // minor + major version
      val cpCount = r.u2()
      // Constant-pool entries are 1-indexed; index 0 is unused. Long/Double
      // entries (tags 5/6) take up two consecutive indices per the spec.
      // Only Utf8 (name/descriptor text) and Class (name_index, to resolve
      // this_class) entries are ever looked up afterwards, so only those
      // are retained.
      val utf8 = new Array[String](cpCount)
      val classNameIdx = new Array[Int](cpCount)
      var i = 1
      while i < cpCount do
        val tag = r.u1()
        tag match
          case 1 => // Utf8
            val len = r.u2()
            utf8(i) = new String(r.bytesOf(len), "UTF-8")
          case 7 => classNameIdx(i) = r.u2() // Class: name_index
          case 8 | 16 | 19 | 20 => r.skip(2) // String/MethodType/Module/Package
          case 15 => r.skip(3) // MethodHandle
          case 3 | 4 => r.skip(4) // Integer/Float
          case 5 | 6 => r.skip(8); i += 1 // Long/Double: wide entry
          case 9 | 10 | 11 | 12 | 17 | 18 => r.skip(4) // Fieldref/Methodref/InterfaceMethodref/NameAndType/Dynamic/InvokeDynamic
          case _ => return None // unrecognized tag -- bail rather than misparse
        i += 1
      r.skip(2) // access_flags
      val thisClassIdx = r.u2()
      r.skip(2) // super_class
      val ifaceCount = r.u2()
      r.skip(2 * ifaceCount)
      val fieldCount = r.u2()
      def skipMember(): Unit =
        r.skip(6) // access_flags, name_index, descriptor_index
        val attrCount = r.u2()
        for _ <- 0 until attrCount do
          r.skip(2) // attribute_name_index
          val len = r.u4()
          r.skip(len.toInt)
      for _ <- 0 until fieldCount do skipMember()
      val methodCount = r.u2()
      var hasMain = false
      for _ <- 0 until methodCount do
        val accessFlags = r.u2()
        val nameIdx = r.u2()
        val descIdx = r.u2()
        val attrCount = r.u2()
        for _ <- 0 until attrCount do
          r.skip(2)
          val len = r.u4()
          r.skip(len.toInt)
        val isPublicStatic = (accessFlags & 0x0001) != 0 && (accessFlags & 0x0008) != 0
        if isPublicStatic && utf8(nameIdx) == "main" && utf8(descIdx) == "([Ljava/lang/String;)V" then
          hasMain = true
      if !hasMain then None
      else Some(utf8(classNameIdx(thisClassIdx)).replace('/', '.'))
    catch case _: Exception => None

  /** Recursively scans every `.class` file under `classesDir` (scalino-dotc's
   *  `-d` output) and returns the distinct set of classes declaring a public
   *  static `main(String[])`, in the order first seen. */
  def findMainClasses(classesDir: Path): List[String] =
    def walk(dir: Path): List[Path] =
      val entries = Option(dir.toFile.listFiles()).map(_.toList).getOrElse(Nil).sortBy(_.getName)
      entries.flatMap(f => if f.isDirectory then walk(f.toPath) else if f.getName.endsWith(".class") then List(f.toPath) else Nil)
    val found = scala.collection.mutable.LinkedHashSet.empty[String]
    for f <- walk(classesDir) do
      scanClassFile(Files.readAllBytes(f)).foreach(found.+=)
    found.toList

  // ---------------------------------------------------------------------
  // NIR-based fallback for the entry-point/test-discovery scan above: a
  // self-hosted scalino-dotc (no backend/jvm/ASM baked in at all -- see
  // docs/findings.md's self-hosting arc) never writes real .class files
  // alongside .nir, so `findMainClasses`/`discoverTestClasses`'s classfile
  // scanner finds nothing there, ever -- not a bug in the scanner, just a
  // format it was never given. NIR is scala-native's own binary IR, already
  // produced by every compile regardless of backend, and it's flat and
  // post-erasure (structurally close to a JVM classfile) rather than
  // TASTy's pickled tree shape, so this ports the *exact same* heuristic
  // the classfile scanner uses -- same field names below, same semantics --
  // just reading `nir.Defn`/`nir.Sig` instead of raw bytecode. Used ONLY
  // when `classesDir` has no `.class` files at all (see call sites below);
  // real GraalVM-built scalino-dotc always emits `.class`, so its behavior
  // is completely unchanged by any of this.
  // ---------------------------------------------------------------------

  private def walkNirFiles(dir: Path): List[Path] =
    val entries = Option(dir.toFile.listFiles()).map(_.toList).getOrElse(Nil).sortBy(_.getName)
    entries.flatMap(f => if f.isDirectory then walkNirFiles(f.toPath) else if f.getName.endsWith(".nir") then List(f.toPath) else Nil)

  /** Deserializes one `.nir` file. Same fault-tolerance posture as the
   *  classfile reader: a stale/corrupt/unreadable file is skipped (empty
   *  result), not fatal -- one bad file under `classesDir` shouldn't break
   *  discovery for everything else in it. */
  private def readNirDefns(f: Path): Seq[snir.Defn] =
    try
      val dir = VirtualDirectory.local(f.getParent)
      deserializeBinary(dir, Paths.get(f.getFileName.toString))
    catch case _: Exception => Nil

  /** Does this `Defn.Define` declare `main(Array[String]): Unit` with no
   *  receiver -- i.e. the same "public static `main(String[])`" shape the
   *  classfile scanner looks for, which is exactly how nscplugin lowers an
   *  object's `@main def`/`extends App`/hand-written `def main`: a
   *  synthetic top-level *class* (not the module itself) declaring `main`
   *  with a plain `(Array[String]) => Unit` signature and no `this`
   *  parameter (the module's own instance `main` -- two args, `this` first
   *  -- is a different, uninteresting definition in a separate `.nir`
   *  file). */
  private def isNirMainDefine(d: snir.Defn.Define): Boolean =
    (d.ty.args match
      case Seq(snir.Type.Array(snir.Type.Ref(snir.Global.Top("java.lang.String"), _, _), _)) => d.ty.ret == snir.Type.Unit
      case _ => false)
    && (try
      d.name.sig.unmangled match
        case snir.Sig.Method(id, _, _) => id == "main"
        case _                         => false
      catch case _: Exception => false)

  /** NIR equivalent of `findMainClasses` -- one real top-level *class*
   *  (never a module: see `isNirMainDefine`'s doc) per `.nir` file declaring
   *  a matching `main`, keyed by that class's own name (dollar-free, same
   *  as the classfile scanner's `this_class`). */
  def findMainClassesNir(classesDir: Path): List[String] =
    val found = scala.collection.mutable.LinkedHashSet.empty[String]
    for f <- walkNirFiles(classesDir) do
      val defns = readNirDefns(f)
      defns.collectFirst { case c: snir.Defn.Class => c }.foreach { c =>
        if defns.exists { case d: snir.Defn.Define => isNirMainDefine(d); case _ => false } then found += c.name.id
      }
    found.toList

  def detectMainClass(classesDir: Path, explicit: Option[String]): String =
    explicit.getOrElse {
      val candidates =
        if walkClassFiles(classesDir).nonEmpty then findMainClasses(classesDir)
        else findMainClassesNir(classesDir)
      candidates match
        case List(one) => one
        case Nil => fail("no entry point found (looked for a compiled `public static void main(String[])`) -- pass --main-class")
        case many => fail(s"multiple possible entry points found (${many.sorted.mkString(", ")}) -- pass --main-class to pick one")
    }

  // ---------------------------------------------------------------------
  // `scalino test`: bridging sbt's test-interface (`sbt.testing.Framework` et
  // al -- the same API munit/utest/scalatest/zio-test's *native* ports all
  // implement, since it's the one interface every scala-native test
  // framework port targets) into a JVM-free build, with no reflection and
  // no sbt/ComRunner on either end.
  //
  // Real (JVM) sbt-scala-native drives this from the sbt/JVM side: it loads
  // the framework's classfile on a JVM classloader just to call
  // `.fingerprints()` (cheap metadata, not real test execution), and
  // generates a `TestMain` that talks back to that JVM process over a
  // socket for every actual test run. None of that is available here --
  // there's no JVM anywhere in this toolchain's chain, at build time or
  // runtime. Instead:
  //
  //   1. Framework discovery: scan every class in the resolved test
  //      classpath's jars (not just a hardcoded list of known frameworks --
  //      structurally, via the same classfile-ancestry walk used for test
  //      discovery below) for a concrete, public, no-arg-constructible class
  //      that implements `sbt.testing.Framework`. This is exactly the same
  //      check sbt itself does, just done by us via a hand-rolled classfile
  //      reader instead of a JVM classloader.
  //   2. Fingerprint probing: `Framework#fingerprints()` returns real
  //      `Fingerprint` values (which marker superclass/annotation identifies
  //      a test class, whether it's a Scala object or a plain class) that
  //      are *computed at runtime* by that framework's own code -- there's
  //      no way to know them without actually running it, and we have no
  //      bytecode interpreter to run arbitrary 3rd-party code at build time.
  //      So: compile+link a throwaway "probe" binary (via the exact same
  //      scalino-dotc/scalino-linkdriver pipeline as any other build) whose
  //      only job is to instantiate the discovered framework(s) and print
  //      their fingerprints, run it, and parse its output. Cached (like
  //      dependency resolution) keyed by the framework classes + classpath,
  //      so this only costs a real compile+link once per project.
  //   3. Test discovery: with real fingerprint data in hand, scan the
  //      user's *compiled* test sources (classesDir) the same way -- does
  //      this class's ancestry (superclass chain + interfaces, walked
  //      transitively across both classesDir and the classpath jars)
  //      include the fingerprint's marker superclass, and does it look like
  //      a Scala object or a plain class to match `isModule`.
  //   4. Driver codegen: generate a small `ScalinoCliTestMain.scala` that
  //      literally instantiates the framework(s) by name (`new
  //      munit.Framework()` -- direct, static, no reflection) and builds
  //      `TaskDef`s from the discovered class names, then compiles it in
  //      alongside the user's sources for a normal final compile+link. The
  //      actual test-object instantiation inside a discovered `Task` is the
  //      framework's own problem at that point (its native port relies on
  //      scala-native's own `@EnableReflectiveInstantiation` support, not
  //      the (dummy, unimplemented on scala-native) `ClassLoader` passed to
  //      `Framework#runner` -- that's supplied only because the API
  //      requires *some* `ClassLoader` value, never actually invoked).
  //
  // Known gaps vs real scala-cli: only `SubclassFingerprint`-based
  // frameworks are supported (covers munit/utest/scalatest/zio-test-sbt --
  // every framework this was verified against); `AnnotatedFingerprint`
  // (JUnit4-style `@Test`-per-method discovery) is not, since JUnit's own
  // sbt-scala-native support is a separate compiler-plugin-level mechanism,
  // not a plain Framework probe. No per-test-method selector filtering --
  // only whole test classes are ever selected (`SuiteSelector`); `--`
  // arguments are forwarded to the framework's own `Runner` untouched
  // (munit/utest both accept a `"*NameSubstring*"`-style filter there,
  // matching real scala-cli's own `-- <pattern>` convention).
  // ---------------------------------------------------------------------

  def readAllBytes(in: InputStream): Array[Byte] =
    val buf = new ByteArrayOutputStream()
    val tmp = new Array[Byte](8192)
    var n = in.read(tmp)
    while n != -1 do
      buf.write(tmp, 0, n)
      n = in.read(tmp)
    buf.toByteArray

  /** A superset of what `scanClassFile` extracts -- also the superclass/
   *  interface names (for ancestry walks) and enough field/method info to
   *  tell a Scala object from a plain class and detect a public no-arg
   *  constructor. Kept separate from `scanClassFile` (some parsing logic is
   *  duplicated) rather than refactoring that already-relied-upon function,
   *  to keep this purely additive. */
  case class ClassInfo(
    thisClass: String,
    superClass: Option[String],
    interfaces: List[String],
    accessFlags: Int,
    isModule: Boolean,
    hasPublicNoArgCtor: Boolean
  )

  def analyzeClassFile(bytes: Array[Byte]): Option[ClassInfo] =
    try
      val r = ClassReader(bytes)
      if r.u4() != classMagic then return None
      r.skip(4)
      val cpCount = r.u2()
      val utf8 = new Array[String](cpCount)
      val classNameIdx = new Array[Int](cpCount)
      var i = 1
      while i < cpCount do
        val tag = r.u1()
        tag match
          case 1 =>
            val len = r.u2()
            utf8(i) = new String(r.bytesOf(len), "UTF-8")
          case 7 => classNameIdx(i) = r.u2()
          case 8 | 16 | 19 | 20 => r.skip(2)
          case 15 => r.skip(3)
          case 3 | 4 => r.skip(4)
          case 5 | 6 => r.skip(8); i += 1
          case 9 | 10 | 11 | 12 | 17 | 18 => r.skip(4)
          case _ => return None
        i += 1
      val accessFlags = r.u2()
      val thisClassIdx = r.u2()
      val superClassIdx = r.u2()
      val ifaceCount = r.u2()
      val ifaceIdxs = (0 until ifaceCount).map(_ => r.u2()).toList
      def className(classCpIdx: Int): String = utf8(classNameIdx(classCpIdx)).replace('/', '.')
      val thisClass = className(thisClassIdx)
      val superClass = if superClassIdx == 0 then None else Some(className(superClassIdx))
      val interfaces = ifaceIdxs.map(className)

      val fieldCount = r.u2()
      var isModule = false
      for _ <- 0 until fieldCount do
        val fAccess = r.u2()
        val fNameIdx = r.u2()
        val fDescIdx = r.u2()
        val attrCount = r.u2()
        for _ <- 0 until attrCount do
          r.skip(2)
          val len = r.u4()
          r.skip(len.toInt)
        val isStatic = (fAccess & 0x0008) != 0
        if isStatic && utf8(fNameIdx) == "MODULE$" then isModule = true

      val methodCount = r.u2()
      var hasPublicNoArgCtor = false
      for _ <- 0 until methodCount do
        val mAccess = r.u2()
        val mNameIdx = r.u2()
        val mDescIdx = r.u2()
        val attrCount = r.u2()
        for _ <- 0 until attrCount do
          r.skip(2)
          val len = r.u4()
          r.skip(len.toInt)
        val isPublic = (mAccess & 0x0001) != 0
        if isPublic && utf8(mNameIdx) == "<init>" && utf8(mDescIdx) == "()V" then hasPublicNoArgCtor = true

      Some(ClassInfo(thisClass, superClass, interfaces, accessFlags, isModule, hasPublicNoArgCtor))
    catch case _: Exception => None

  /** Every `.class` file under `dir`, recursively. Shared by main-class
   *  detection and test discovery. */
  def walkClassFiles(dir: Path): List[Path] =
    val entries = Option(dir.toFile.listFiles()).map(_.toList).getOrElse(Nil).sortBy(_.getName)
    entries.flatMap(f => if f.isDirectory then walkClassFiles(f.toPath) else if f.getName.endsWith(".class") then List(f.toPath) else Nil)

  def readClassFromJar(jarPath: String, internalDottedName: String): Option[Array[Byte]] =
    try
      val zf = new java.util.zip.ZipFile(jarPath)
      try
        Option(zf.getEntry(internalDottedName.replace('.', '/') + ".class")).map(e => readAllBytes(zf.getInputStream(e)))
      finally zf.close()
    catch case _: Exception => None

  /** NIR equivalent of `analyzeClassFile` -- same `ClassInfo` shape, sourced
   *  from a `Defn.Class`/`Defn.Module`'s own `parent`/`traits` fields
   *  directly (no ancestry reconstruction needed at all: unlike bytecode,
   *  NIR already carries them as real fields, not something to re-derive
   *  from a constant pool) plus a same-file scan for a public no-arg
   *  constructor (`isCtor`, `!isPrivate`, and exactly one NIR-level arg --
   *  the implicit receiver, since NIR method signatures always include
   *  `this` explicitly, unlike a JVM descriptor). `accessFlags` is left `0`
   *  (unused by any LOCAL-classesDir caller below; only the jar-based
   *  `findFrameworkClasses` reads it, and that path stays classfile-only,
   *  real dependency jars always have `.class`). */
  private def hasPublicNoArgCtorNir(selfTop: snir.Global.Top, defns: Seq[snir.Defn]): Boolean =
    defns.exists {
      case d: snir.Defn.Define =>
        d.name match
          case snir.Global.Member(owner, sig) if owner == selfTop =>
            (try sig.isCtor && !sig.isPrivate catch case _: Exception => false) && d.ty.args.size == 1
          case _ => false
      case _ => false
    }

  private def nirClassInfoOf(defns: Seq[snir.Defn]): Option[ClassInfo] =
    defns.collectFirst {
      case c: snir.Defn.Class =>
        ClassInfo(c.name.id, c.parent.map(_.id), c.traits.map(_.id).toList, accessFlags = 0, isModule = false, hasPublicNoArgCtorNir(c.name, defns))
      case m: snir.Defn.Module =>
        ClassInfo(m.name.id, m.parent.map(_.id), m.traits.map(_.id).toList, accessFlags = 0, isModule = true, hasPublicNoArgCtorNir(m.name, defns))
    }

  /** Resolves a (dotted) class name to its `ClassInfo`, checking the local
   *  compiled-output directory first (`.class` if present -- real
   *  GraalVM-built scalino-dotc always has it; else `.nir`, the only format
   *  a self-hosted scalino-dotc ever produces), then every jar on the
   *  classpath in order -- used by `isAssignable`'s ancestry walk, which
   *  needs to follow a chain that starts in user code and typically ends
   *  inside a dependency jar (e.g. `MySuite extends munit.FunSuite extends
   *  munit.Suite`); dependency jars are always real `.class`-bearing JVM
   *  artifacts regardless of which toolchain built scalino-dotc, so that
   *  half is untouched. */
  def findClassInfo(name: String, classesDir: Path, jars: List[String]): Option[ClassInfo] =
    val localClass = classesDir.resolve(name.replace('.', java.io.File.separatorChar) + ".class")
    if Files.exists(localClass) then analyzeClassFile(Files.readAllBytes(localClass))
    else
      val localNir = classesDir.resolve(name.replace('.', java.io.File.separatorChar) + ".nir")
      if Files.exists(localNir) then nirClassInfoOf(readNirDefns(localNir))
      else jars.iterator.flatMap(j => readClassFromJar(j, name).iterator).nextOption().flatMap(analyzeClassFile)

  /** Does `startClass`'s ancestry (superclass chain and, at every step,
   *  every implemented interface -- since Scala traits compile to
   *  interfaces, and a `SubclassFingerprint`'s marker is very often a trait
   *  like `munit.Suite`/`utest.TestSuite`) include `target`? A from-scratch,
   *  classfile-level substitute for the JVM's `Class#isAssignableFrom`. */
  def isAssignable(startClass: String, target: String, classesDir: Path, jars: List[String]): Boolean =
    val visited = scala.collection.mutable.Set.empty[String]
    def go(name: String): Boolean =
      if name == target then true
      else if name == "java.lang.Object" || !visited.add(name) then false
      else
        findClassInfo(name, classesDir, jars) match
          case None => false
          case Some(info) => info.superClass.exists(go) || info.interfaces.exists(go)
    go(startClass)

  private val sbtTestingFramework = "sbt.testing.Framework"

  /** Structurally scans every class in `jars` for a concrete (public,
   *  non-abstract), no-arg-constructible implementor of
   *  `sbt.testing.Framework` -- no hardcoded per-framework list, so any
   *  framework with a scala-native port that follows the standard
   *  sbt-testing-interface contract (munit, utest, scalatest, zio-test-sbt,
   *  and anything else shaped the same way) is found the same way. Synthetic
   *  classes (lambdas, anonymous classes, nested `$`-named classes) are
   *  skipped -- framework entry points are always a plain top-level class. */
  def findFrameworkClasses(jars: List[String]): List[String] =
    val found = scala.collection.mutable.LinkedHashSet.empty[String]
    for jar <- jars do
      try
        val zf = new java.util.zip.ZipFile(jar)
        try
          val entries = zf.entries()
          while entries.hasMoreElements do
            val e = entries.nextElement()
            if e.getName.endsWith(".class") && !e.getName.contains("$") then
              analyzeClassFile(readAllBytes(zf.getInputStream(e))).foreach { info =>
                val isPublic = (info.accessFlags & 0x0001) != 0
                val isAbstract = (info.accessFlags & 0x0400) != 0
                val isInterface = (info.accessFlags & 0x0200) != 0
                if isPublic && !isAbstract && !isInterface && info.hasPublicNoArgCtor
                   && info.thisClass != sbtTestingFramework
                   && isAssignable(info.thisClass, sbtTestingFramework, Paths.get("."), jars)
                then found += info.thisClass
              }
        finally zf.close()
      catch case _: Exception => ()
    found.toList

  /** A `SubclassFingerprint` a framework reported, as probed from a real
   *  (compiled+linked+run) instance of it -- see the probe step above. */
  case class SubclassSpec(frameworkFqcn: String, superclassName: String, isModule: Boolean, requireNoArgConstructor: Boolean)

  def probeSourceFor(frameworkFqcns: List[String]): String =
    val entries = frameworkFqcns.map(fqcn => s"""    "$fqcn" -> new $fqcn()""").mkString(",\n")
    s"""object ScalinoCliProbeMain:
       |  def main(args: Array[String]): Unit =
       |    val fws: List[(String, sbt.testing.Framework)] = List(
       |$entries
       |    )
       |    for (name, fw) <- fws; fp <- fw.fingerprints() do
       |      fp match
       |        case s: sbt.testing.SubclassFingerprint =>
       |          println("SUB\\t" + name + "\\t" + s.superclassName() + "\\t" + s.isModule() + "\\t" + s.requireNoArgConstructor())
       |        case a: sbt.testing.AnnotatedFingerprint =>
       |          println("ANN\\t" + name + "\\t" + a.annotationName() + "\\t" + a.isModule())
       |        case _ => ()
       |""".stripMargin

  def parseProbeOutput(output: String): List[SubclassSpec] =
    output.linesIterator.flatMap { line =>
      line.split("\t") match
        case Array("SUB", fqcn, superclassName, isModule, requireNoArgCtor) =>
          List(SubclassSpec(fqcn, superclassName, isModule.toBoolean, requireNoArgCtor.toBoolean))
        case _ => Nil // ANN (AnnotatedFingerprint) or an unrecognized line -- not supported, see note above
    }.toList

  /** Compiles+links+runs the probe binary described above and parses its
   *  output into fingerprint specs, cached on disk keyed by the framework
   *  classes + resolved test classpath (a real compile+link, so worth
   *  skipping on repeat runs the same way `resolveDeps` skips re-resolving).
   *  `userClassesDir` (pass 1's compiled output) is put on the probe's
   *  classpath too -- not needed for a normal published-dependency
   *  framework (those are already on `testClasspath`), but harmless, and
   *  lets a project-local `sbt.testing.Framework` implementation (picked via
   *  explicit `--test-framework`, since auto-detection only scans jars)
   *  resolve as well. */
  def probeFingerprints(frameworkFqcns: List[String], testClasspath: String, cc: CompileClasspath, userClassesDir: Path, cacheDir: Path, logLevel: String): List[SubclassSpec] =
    Files.createDirectories(cacheDir)
    val key = sanitizeKey(frameworkFqcns.sorted.mkString(",")) + "-" + hashKey(testClasspath)
    val cacheFile = cacheDir.resolve(s"probe-$key.tsv")
    if Files.exists(cacheFile) then parseProbeOutput(readFile(cacheFile))
    else
      val scratch = Paths.get(".scalino-build", "_scratch", "probe")
      Files.createDirectories(scratch)
      val probeSrc = scratch.resolve("ScalinoCliProbeMain.scala")
      Files.write(probeSrc, probeSourceFor(frameworkFqcns).getBytes("UTF-8"))
      val probeClasses = scratch.resolve("classes")
      val ccWithUserClasses = cc.copy(compileCp = s"$userClassesDir$CP_SEP${cc.compileCp}")
      compileToClasses(List(probeSrc), probeClasses, ccWithUserClasses)
      val linkDir = scratch.resolve("link")
      val linkCp = s"$probeClasses$CP_SEP$userClassesDir$CP_SEP${cc.nativelibsCp}$CP_SEP$testClasspath"
      val clang = findOnPath("clang")
      val clangpp = findOnPath("clang++")
      val linkExit = runInherited(List(s"$dist/scalino-linkdriver", linkCp, linkDir.toString, "ScalinoCliProbeMain", clang, clangpp, logLevel))
      if linkExit != 0 then fail("linking the test-framework probe failed")
      val produced = linkDir.resolve("ScalinoCliProbeMain")
      val actual = if Files.exists(produced) then produced else linkDir.resolve("sncliprobemain")
      if !Files.exists(actual) then fail(s"expected probe binary at $actual, not found")
      val (exit, out) = runCaptureStdout(List(actual.toString))
      if exit != 0 then fail("test-framework probe binary failed to run")
      Files.write(cacheFile, out.getBytes("UTF-8"))
      parseProbeOutput(out)

  case class TestMatch(className: String, isModule: Boolean, frameworkFqcn: String, superclassName: String)

  /** Scans every compiled class under `classesDir` and matches it against
   *  `specs`, the framework(s)' real fingerprints. A module's reported name
   *  drops the compiler-generated trailing `$` (its companion object's
   *  *module class* is what actually carries the ancestry/`MODULE$` field;
   *  sbt-testing's own convention is that a module fingerprint's
   *  fullyQualifiedName excludes it). */
  def discoverTestClasses(classesDir: Path, specs: List[SubclassSpec], jars: List[String]): List[TestMatch] =
    val found = scala.collection.mutable.LinkedHashMap.empty[String, TestMatch]
    // Same NIR fallback as `detectMainClass`: only reached when `classesDir`
    // has no `.class` files at all (a self-hosted scalino-dotc). Every
    // `.nir` file's single top-level `Defn.Class`/`Defn.Module` is scanned
    // the same way a `.class` file would be -- see `nirClassInfoOf`.
    val localInfos: List[ClassInfo] =
      if walkClassFiles(classesDir).nonEmpty then
        walkClassFiles(classesDir).flatMap(f => analyzeClassFile(Files.readAllBytes(f)))
      else
        walkNirFiles(classesDir).flatMap(f => nirClassInfoOf(readNirDefns(f)))
    for info <- localInfos do
      val isModuleClass = info.thisClass.endsWith("$") && info.isModule
      val reportedName = if isModuleClass then info.thisClass.stripSuffix("$") else info.thisClass
      if !found.contains(reportedName) then
        specs
          .find(s => s.isModule == isModuleClass
                     && (s.isModule || !s.requireNoArgConstructor || info.hasPublicNoArgCtor)
                     && isAssignable(info.thisClass, s.superclassName, classesDir, jars))
          .foreach(s => found(reportedName) = TestMatch(reportedName, isModuleClass, s.frameworkFqcn, s.superclassName))
    found.values.toList

  /** Generates `ScalinoCliTestMain.scala`: one real (statically instantiated, no
   *  reflection) `Framework` per distinct `frameworkFqcn` among `matches`,
   *  a `TaskDef` per discovered test class (looking its real `Fingerprint`
   *  back up from that framework's own `fingerprints()` at run time, by the
   *  same (superclassName, isModule) pair used to discover it), and a
   *  synchronous drain-loop `Task.execute` runner with a plain pass/fail/
   *  error/skipped tally -- see the design note above for why this can be
   *  this simple (no ComRunner protocol, no JVM classloader tricks). */
  def generateTestMain(matches: List[TestMatch]): String =
    val byFramework = matches.groupBy(_.frameworkFqcn).toList
    val groups = byFramework.zipWithIndex.map { case ((fqcn, ms), idx) =>
      val taskDefs = ms.map { m =>
        s"""      new sbt.testing.TaskDef(${'"'}${m.className}${'"'}, pick$idx(${'"'}${m.superclassName}${'"'}, ${m.isModule}), false, Array(new sbt.testing.SuiteSelector))"""
      }.mkString(",\n")
      s"""    val fw$idx: sbt.testing.Framework = new $fqcn()
         |    val fps$idx = fw$idx.fingerprints()
         |    def pick$idx(superclassName: String, isModule: Boolean): sbt.testing.Fingerprint =
         |      fps$idx.collectFirst { case s: sbt.testing.SubclassFingerprint if s.superclassName() == superclassName && s.isModule() == isModule => s }.get
         |    val taskDefs$idx: Array[sbt.testing.TaskDef] = Array(
         |$taskDefs
         |    )
         |    val runner$idx = fw$idx.runner(args, Array.empty[String], getClass.getClassLoader)
         |    var tasks$idx = runner$idx.tasks(taskDefs$idx)
         |    while tasks$idx.nonEmpty do
         |      tasks$idx = tasks$idx.flatMap(t => t.execute(handler, loggers))
         |    val summary$idx = runner$idx.done()
         |    if summary$idx != null && summary$idx.nonEmpty then println(summary$idx)""".stripMargin
    }.mkString("\n\n")

    s"""object ScalinoCliTestMain:
       |  def main(args: Array[String]): Unit =
       |    // Same NO_COLOR/CLICOLOR_FORCE/TERM=dumb/isatty convention as
       |    // scalino itself (cli/ScalinoCli.scala's own Color object) --
       |    // duplicated here, not shared, since this file is compiled as
       |    // a wholly separate program from the `scalino` binary itself.
       |    val colorOn =
       |      System.getenv("NO_COLOR") == null &&
       |      ((System.getenv("CLICOLOR_FORCE") != null && System.getenv("CLICOLOR_FORCE") != "0") ||
       |       (System.getenv("TERM") != "dumb" && scala.scalanative.posix.unistd.isatty(1) != 0))
       |    def paint(code: String, s: String): String = if colorOn then code + s + "\\u001b[0m" else s
       |    def red(s: String): String = paint("\\u001b[1;31m", s)
       |    def green(s: String): String = paint("\\u001b[1;32m", s)
       |
       |    val logger = new sbt.testing.Logger:
       |      def ansiCodesSupported(): Boolean = false
       |      def error(msg: String): Unit = System.err.println(msg)
       |      def warn(msg: String): Unit = System.err.println(msg)
       |      def info(msg: String): Unit = println(msg)
       |      def debug(msg: String): Unit = ()
       |      def trace(t: Throwable): Unit = t.printStackTrace()
       |    val loggers = Array[sbt.testing.Logger](logger)
       |
       |    var passed = 0
       |    var failed = 0
       |    var errored = 0
       |    var skipped = 0
       |    val handler = new sbt.testing.EventHandler:
       |      def handle(e: sbt.testing.Event): Unit =
       |        e.status() match
       |          case sbt.testing.Status.Success => passed += 1
       |          case sbt.testing.Status.Failure =>
       |            failed += 1
       |            println(red("FAILED: " + e.fullyQualifiedName()))
       |            if e.throwable().isDefined() then e.throwable().get().printStackTrace()
       |          case sbt.testing.Status.Error =>
       |            errored += 1
       |            println(red("ERROR: " + e.fullyQualifiedName()))
       |            if e.throwable().isDefined() then e.throwable().get().printStackTrace()
       |          case sbt.testing.Status.Skipped | sbt.testing.Status.Ignored | sbt.testing.Status.Pending | sbt.testing.Status.Canceled =>
       |            skipped += 1
       |
       |$groups
       |
       |    val summaryLine = "scalino: " + passed + " passed, " + failed + " failed, " + errored + " errored, " + skipped + " skipped"
       |    println(if failed > 0 || errored > 0 then red(summaryLine) else green(summaryLine))
       |    if failed > 0 || errored > 0 then sys.exit(1)
       |""".stripMargin

  // ---------------------------------------------------------------------
  // Source expansion: a directory argument (e.g. `scalino run .`) means "every
  // .scala file under here", scala-cli-style -- skipping hidden dirs and
  // this tool's own build-output dirs.
  // ---------------------------------------------------------------------

  // "vendor" excluded because vendor/scala3 is a full gitignored dotty
  // checkout (~18k .scala files) -- sweeping it into IDE/run/compile source
  // scanning made `scalino setup-ide .` (and any other directory-arg command)
  // hang for minutes walking+directive-parsing files that aren't this
  // project's own sources.
  private val skipDirNames = Set(".scalino-build", "target", "out", "vendor")

  def collectScalaFiles(dir: Path): List[Path] =
    val entries = Option(dir.toFile.listFiles()).map(_.toList).getOrElse(Nil).sortBy(_.getName)
    entries.flatMap { f =>
      val name = f.getName
      if f.isDirectory then
        if name.startsWith(".") || skipDirNames(name) then Nil
        else collectScalaFiles(f.toPath)
      else if name.endsWith(".scala") then List(f.toPath)
      else Nil
    }

  /** Translates a scala-cli-style glob (`*` within a path segment, `**`
   *  across segments, `?` a single char) to an anchored regex -- hand-rolled
   *  rather than `java.nio.file.FileSystem#getPathMatcher("glob:...")`,
   *  since that relies on JDK-internal glob-to-regex machinery whose support
   *  in this toolchain's from-scratch javalib port is unverified. */
  private def globToRegex(glob: String): scala.util.matching.Regex =
    val sb = new StringBuilder("^")
    var i = 0
    while i < glob.length do
      glob(i) match
        case '*' =>
          if i + 1 < glob.length && glob(i + 1) == '*' then { sb.append(".*"); i += 1 }
          else sb.append("[^/]*")
        case '?' => sb.append("[^/]")
        case c if "\\.^$+{}()|[]".indexOf(c) >= 0 => sb.append('\\').append(c)
        case c => sb.append(c)
      i += 1
    sb.append("$")
    sb.toString.r

  /** `//> using file`/`files` (extra source files, resolved relative to the
   *  directory of the source that declares them -- mainly useful when a
   *  single explicit source file needs a sibling not otherwise on the
   *  command line) and `//> using exclude` (glob, matched against each
   *  collected source's path relative to the current working directory,
   *  scala-cli-style) applied over the directory-expanded source set. Only
   *  scans directives declared by sources already in `base` -- an `exclude`
   *  or `file` inside a `file`-referenced source is not itself expanded a
   *  second time, matching scala-cli's own one-level (not transitive)
   *  behavior for this directive. */
  private def applyFileAndExcludeDirectives(base: List[Path]): List[Path] =
    def directiveValuesOf(src: Path, keys: String*): List[String] =
      if !Files.exists(src) then Nil
      else readFile(src).linesIterator.filter(_.trim.startsWith("//>")).flatMap { rawLine =>
        directiveValues(rawLine.trim, keys*).getOrElse(Nil)
      }.toList
    val extraFiles = base.flatMap { src =>
      directiveValuesOf(src, "file", "files").map(f => src.toAbsolutePath.getParent.resolve(f).normalize())
    }
    val excludeGlobs = base.flatMap(src => directiveValuesOf(src, "exclude"))
    val combined = (base ++ extraFiles).distinct
    if excludeGlobs.isEmpty then combined
    else
      val cwd = Paths.get("").toAbsolutePath.normalize()
      val patterns = excludeGlobs.map(globToRegex)
      combined.filterNot { p =>
        val rel = cwd.relativize(p.toAbsolutePath.normalize()).toString.replace(java.io.File.separatorChar, '/')
        patterns.exists(_.matches(rel))
      }

  def expandSources(paths: List[Path]): List[Path] =
    val base = paths.flatMap(p => if Files.isDirectory(p) then collectScalaFiles(p) else List(p)).distinct
    applyFileAndExcludeDirectives(base)

  /** `--watching`/`--watching-path`: extra paths polled for mtime changes
   *  alongside the real sources, same recursive dir-walk as
   *  `collectScalaFiles` but keeping every file (not just `.scala`) --
   *  these are meant for arbitrary resources a build might depend on. */
  def expandWatchPaths(paths: List[Path]): List[Path] =
    def collectAll(dir: Path): List[Path] =
      val entries = Option(dir.toFile.listFiles()).map(_.toList).getOrElse(Nil).sortBy(_.getName)
      entries.flatMap { f =>
        val name = f.getName
        if f.isDirectory then
          if name.startsWith(".") || skipDirNames(name) then Nil else collectAll(f.toPath)
        else List(f.toPath)
      }
    paths.flatMap(p => if Files.isDirectory(p) then collectAll(p) else List(p))

  // ---------------------------------------------------------------------
  // Compilation scopes, scala-cli-style: every source is "main" scope
  // except one that has a path segment literally named "test" -- covers
  // both a flat top-level `test/` folder and the sbt-style nested
  // `src/test/scala/...` layout. `run`/`compile` only build the main
  // scope (test sources typically reference a test framework like munit
  // that's declared via `//> using test.dep`, not a plain `dep`, and so
  // isn't even on the main scope's classpath -- compiling them in would
  // just fail). `scalino test` (handleTest, below) compiles both scopes
  // together instead, since test sources depend on main scope.
  // ---------------------------------------------------------------------

  def isTestSource(p: Path): Boolean =
    p.iterator().asScala.exists(_.toString == "test")

  def partitionSources(sources: List[Path]): (List[Path], List[Path]) =
    sources.partition(s => !isTestSource(s))

  // ---------------------------------------------------------------------
  // Compile + link, mirroring bin/scalino-bootstrap but with directive-resolved deps
  // folded into the classpath, and driven straight from this process
  // instead of shelling out to a shell script.
  // ---------------------------------------------------------------------

  def readListFile(name: String): String = readFile(Paths.get(s"$dist/$name")).trim

  // compiler.cp/nativelibs.cp/nscplugin.jar.txt store dist-relative paths
  // (e.g. "lib/foo.jar") so dist/ stays relocatable -- resolve to absolute.
  def resolveCp(raw: String): String = raw.split(CP_SEP).map(e => s"$dist/$e").mkString(CP_SEP)

  /** Compiles `sources`, then detects (or takes, if `explicitMainClass` is
   *  set) the entry point from the *compiled* classfiles -- see
   *  `detectMainClass` -- links, and copies the resulting binary to
   *  `outFor(mainClass)`. Compilation always targets a single
   *  mainClass-independent scratch dir (never mainClass-keyed, so nothing
   *  is lost by not knowing the entry point's name up front, and it
   *  sidesteps the chicken-and-egg problem of needing a compiled classfile
   *  to name a directory the compiler is about to compile into) -- but,
   *  since `compileToClasses` below is now incremental, that dir persists
   *  across builds rather than being wiped every time. Only linking (whose
   *  C-object cache genuinely benefits from a stable, persistent path) is
   *  keyed by the resolved mainClass.
   *  Returns the resolved mainClass. */
  /** javaBase/pluginJar/compileCp for a scalino-dotc invocation -- shared by
   *  `buildBinary` (compile/run) and `handleSetupIde` (`setup-ide`) so the
   *  IDE server type-checks against the exact same classpath/flags a real
   *  build would use. `nativelibsCp` is returned undropped (see below) since
   *  `buildBinary` also needs it, unmodified, for linking. */
  case class CompileClasspath(javaBase: String, pluginJar: String, compileCp: String, nativelibsCp: String)

  def computeCompileClasspath(extraClasspath: String, extraCompileOnlyClasspath: String = ""): CompileClasspath =
    val javaBase = s"$dist/java.base.jar"
    val compilerCp = resolveCp(readListFile("compiler.cp"))
    val nativelibsCp = resolveCp(readListFile("nativelibs.cp"))
    val pluginJar = s"$dist/" + readListFile("nscplugin.jar.txt")
    // dist/lib/scalalib-retained.jar (build/03b-build-scalalib-retained.sh):
    // the same scala-library source nativelibsCp's plain jar has, recompiled
    // with dotc instead of scalac -- real TASTy, so our own-implementation
    // macro interpreter can run List/Option/Seq/etc's *actual* bodies
    // (Interpreter.scala's resolveExternalDefTree) instead of needing a
    // hand-written intrinsic for every stdlib method a macro's own code
    // happens to call. Compile-only (ahead of the plain one on this
    // classpath, but never on linkCp below) -- linking still uses the real
    // scala-native-blessed artifacts, untouched.
    val scalalibRetained = s"$dist/lib/scalalib-retained.jar"

    // `compilerCp`/`nativelibsCp` each carry their own plain, scalac-compiled
    // scala-library jar -- having *both* that jar and scalalibRetained's
    // recompiled one on the same classpath is genuinely ambiguous, not just
    // redundant: which one dotc's classpath scanning resolves
    // scala.Option/scala.Some/etc to (and so whether resolveExternalDefTree
    // finds a real body at all) turned out to depend on timing/caching, not
    // just declaration order -- non-deterministic between otherwise-identical
    // runs. Drop the plain jar entirely from the compile classpath so
    // scalalibRetained is the only one providing it -- UNLESS it's the
    // pinned Scala version's OWN jar (`scala-library-<SCALA_VERSION>.jar`):
    // under Scala's unified 2.13/3.x versioning (3.8.x+), that filename is
    // no longer just the legacy Scala-2 stdlib duplicate scalalibRetained
    // substitutes for -- it's the ONLY place scala.caps/scala.compiletime/
    // etc (and, per the real jar shipping genuine per-class .tasty now,
    // arguably scalalibRetained's whole reason to exist) live at all;
    // `scala3-library_3` itself publishes as a near-empty compat shim.
    // Dropping it unconditionally removes essential Scala 3 stdlib content,
    // not just a redundant duplicate.
    def dropPlainScalaLibrary(cp: String): String =
      cp.split(CP_SEP).filterNot(j => j.matches(""".*/scala-library-[0-9.]+\.jar""") && !j.endsWith(s"/scala-library-${BuildInfo.scalaVersion}.jar")).mkString(CP_SEP)

    val compileCp =
      List(scalalibRetained, dropPlainScalaLibrary(compilerCp), dropPlainScalaLibrary(nativelibsCp), extraClasspath, extraCompileOnlyClasspath)
        .filter(_.nonEmpty).mkString(CP_SEP)

    CompileClasspath(javaBase, pluginJar, compileCp, nativelibsCp)

  // ---------------------------------------------------------------------
  // Incremental compilation -- own implementation, not real Zinc (see
  // docs/findings.md remaining-work item 7 for the design writeup). Real
  // sbt Zinc gets its invalidation graph from a "compiler bridge" hooked
  // deep into the compiler's own symbol table -- exactly the kind of
  // JVM/dotty-internals tie this project's vendor+patch+splice approach
  // (see the top-level project summary) is built to avoid taking on
  // wholesale. Instead of a real bridge, this reconstructs an
  // *approximate* dependency graph purely from source text: which
  // top-level names each file declares, and which of those names each
  // *other* file's text mentions.
  //
  // On a rebuild: hash every given source file's content, diff against
  // the last successful build's manifest to get the changed set, then
  // widen it by walking the textual "who mentions one of this file's
  // declared names" graph to a fixed point. Only that closure is handed
  // to scalino-dotc; everything else is resolved from its already-compiled
  // .class/.tasty sitting in classesDir (added to the compile classpath),
  // the same way real Zinc reuses unaffected compilation units instead of
  // recompiling the whole project.
  //
  // This is strictly more conservative than real Zinc's name-hashing --
  // it invalidates a dependent on ANY change to a file it mentions, not
  // just an API-visible one (real Zinc can skip recompiling a dependent
  // when only a method body changed) -- but it can never *under*-invalidate:
  // any textual mention of a changed or removed name recompiles the file
  // mentioning it, so a stale binary isn't a risk this approach can create.
  // A whole-project fingerprint (compile classpath, scalac options,
  // scalino-dotc's own mtime) forces a full rebuild whenever any of those
  // change, since none of them are tracked per-file.
  // ---------------------------------------------------------------------

  case class FileRecord(hash: String, pkg: String, declaredNames: List[String])
  case class IncrManifest(fingerprint: String, files: Map[String, FileRecord])

  // Top-level only by convention, not by indentation-tracking: matches
  // scala-cli-shaped single-file examples fine, and under-matching (e.g. a
  // name declared only inside a nested object) just means that name's
  // edges are missing from the graph -- conservative, since a file with an
  // untracked declared name still gets recompiled itself on any of its own
  // changes, it just can't ripple further to a file that only mentions a
  // nested name of its own.
  private val topLevelDeclRe =
    """(?m)^\s*(?:@\w+(?:\([^)]*\))?\s+)*(?:(?:final|sealed|abstract|open|case|private|protected|implicit|inline|transparent|opaque|infix)(?:\[\w+\])?\s+)*(?:class|trait|object|enum|given)\s+([A-Za-z_][A-Za-z0-9_]*)""".r
  private val packageRe = """(?m)^\s*package\s+([A-Za-z0-9_.]+)""".r

  def extractPackage(text: String): String =
    packageRe.findFirstMatchIn(text).map(_.group(1)).getOrElse("")

  def extractDeclaredNames(text: String): List[String] =
    topLevelDeclRe.findAllMatchIn(text).map(_.group(1)).toList.distinct

  /** Whole-project fingerprint: anything that isn't tracked per-file
   *  (dependency classpath, scalac flags, the compiler binary itself)
   *  forces a full rebuild when it changes, rather than risk silently
   *  reusing classfiles built under a now-stale environment. */
  def computeFingerprint(cc: CompileClasspath, extraOptions: List[String]): String =
    val dotcMtime = Files.getLastModifiedTime(Paths.get(s"$dist/scalino-dotc")).toMillis
    hashKey(List(cc.compileCp, cc.javaBase, cc.pluginJar, extraOptions.mkString(" "), dotcMtime.toString).mkString(" "))

  /** Parses the manifest this module itself wrote (see `saveManifest`) --
   *  tab/newline-delimited like this project's other on-disk caches
   *  (`deps-*.cp`, the test-probe cache), rather than pulling in a JSON
   *  parser for one internal format nothing else needs to read. Any parse
   *  trouble (missing file, corrupted line, a manifest from an old,
   *  incompatible version of this code) is treated as a cache miss --
   *  falling back to a full rebuild is always safe, just slower. */
  def loadManifest(path: Path): Option[IncrManifest] =
    try
      if !Files.exists(path) then None
      else
        readFile(path).linesIterator.toList match
          case fingerprint :: rest =>
            val files = rest.filter(_.nonEmpty).map { line =>
              val parts = line.split("\t", -1)
              val names = if parts(3).isEmpty then Nil else parts(3).split(",").toList
              parts(0) -> FileRecord(parts(1), parts(2), names)
            }.toMap
            Some(IncrManifest(fingerprint, files))
          case Nil => None
    catch case _: Exception => None

  def saveManifest(path: Path, m: IncrManifest): Unit =
    Files.createDirectories(Option(path.getParent).getOrElse(Paths.get(".")))
    val lines = m.fingerprint :: m.files.toList.map { case (p, r) =>
      s"$p\t${r.hash}\t${r.pkg}\t${r.declaredNames.mkString(",")}"
    }
    Files.write(path, lines.mkString("\n").getBytes("UTF-8"))

  private def manifestPathFor(classesDir: Path): Path =
    classesDir.resolveSibling(classesDir.getFileName.toString + ".incr-manifest")

  /** Deletes whatever `classesDir` holds for the given (package, declared
   *  top-level names) -- `Name.class`/`Name.tasty`/`Name.nir` plus anything dotc
   *  nests under it (`Name$.class` for an object's static forwarder,
   *  `Name$Inner.class`, ...), matched by filename prefix. Called before
   *  recompiling a changed file (in case a class inside it was renamed or
   *  removed since the last build -- leaving the old file behind would
   *  keep shipping a stale class on the link classpath) and for files
   *  dropped from the source set entirely. */
  def purgeArtifactsFor(classesDir: Path, pkg: String, names: List[String]): Unit =
    val dir = if pkg.isEmpty then classesDir else pkg.split("\\.").foldLeft(classesDir)(_.resolve(_))
    if Files.exists(dir) then
      names.foreach { n =>
        Option(dir.toFile.listFiles((_, name) => name == s"$n.class" || name == s"$n.tasty" || name == s"$n.nir" || name.startsWith(s"$n$$")))
          .foreach(_.foreach(f => Files.deleteIfExists(f.toPath)))
      }

  /** Just the scalino-dotc compile step (shared by `buildBinary` and the
   *  `scalino test` pipeline, which needs to compile once to scan the resulting
   *  classfiles for test discovery before a mainClass is known/generated).
   *  Incremental by default (see the design note above) -- pass
   *  `incremental = false` to always fully recompile (`--no-incremental`). */
  def compileToClasses(
    sources: List[Path],
    classesDir: Path,
    cc: CompileClasspath,
    extraOptions: List[String] = Nil,
    incremental: Boolean = true
  ): Unit =
    val manifestPath = manifestPathFor(classesDir)
    val fingerprint = computeFingerprint(cc, extraOptions)
    val prev = if incremental then loadManifest(manifestPath) else None
    // Keyed by path.toString throughout (not Path) -- the manifest itself
    // is string-keyed (see FileRecord/IncrManifest), and the dependency
    // graph below needs a plain-value key it can put in a Set/Queue.
    val texts = sources.map(p => p.toString -> readFile(p)).toMap
    val curHash = texts.view.mapValues(hashKey).toMap
    // `<File>$package` is where dotc puts top-level defs/vals/types, so it
    // belongs to the file even though no declaration in the text names it.
    val curDeclared = texts.map { case (p, t) =>
      val base = Paths.get(p).getFileName.toString.stripSuffix(".scala").stripSuffix(".sc")
      p -> (extractDeclaredNames(t) :+ s"$base$$package").distinct
    }
    val curPkg = texts.view.mapValues(extractPackage).toMap

    val fullRebuild = !incremental || prev.isEmpty || prev.exists(_.fingerprint != fingerprint) || !Files.isDirectory(classesDir)

    val (toCompile, removedRecords) =
      if fullRebuild then (sources, Nil)
      else
        val m = prev.get
        val curPaths = sources.map(_.toString).toSet
        val removed = m.files.filter { case (p, _) => !curPaths(p) }
        val changed = sources.filter(p => m.files.get(p.toString).forall(_.hash != curHash(p.toString)))

        // name -> declaring file(s), recomputed fresh from the CURRENT
        // source set every run -- this is what stands in for dotc's own
        // symbol table: any textual mention of a name is treated as a
        // dependency edge on whoever currently declares it.
        val nameToFile = curDeclared.toList.flatMap { case (p, names) => names.map(_ -> p) }.groupMap(_._1)(_._2)
        // names that vanished because their declaring file was removed
        // still need to invalidate whoever references them -- dotc will
        // then fail that file with a real "not found" error, correctly.
        val removedNames = removed.values.flatMap(_.declaredNames).toSet
        val allKnownNames = nameToFile.keySet ++ removedNames

        // Single pass over each text, tokenizing into identifiers once,
        // then intersecting against the known-names set -- avoids
        // recompiling/rescanning with one regex per known name (was
        // O(files * names * textLen), now O(files * textLen)).
        val identifierRe = "[A-Za-z_][A-Za-z0-9_]*".r
        def usedNames(text: String): Set[String] =
          identifierRe.findAllMatchIn(text).map(_.matched).toSet.intersect(allKnownNames)
        val usedBy = texts.view.mapValues(usedNames).toMap
        def dependentsOf(n: String): Iterable[String] = usedBy.collect { case (p, used) if used(n) => p }

        // BFS closure: if file A is invalidated, anyone whose text
        // mentions any name A declares is invalidated too (conservative:
        // on ANY change to A, not just an API-visible one -- see the
        // design note above).
        val invalid = scala.collection.mutable.LinkedHashSet.empty[String]
        val queue = scala.collection.mutable.Queue.empty[String]
        def markInvalid(p: String): Unit = if invalid.add(p) then queue.enqueue(p)
        changed.foreach(p => markInvalid(p.toString))
        removedNames.foreach(n => dependentsOf(n).foreach(markInvalid))
        while queue.nonEmpty do
          val p = queue.dequeue()
          curDeclared.getOrElse(p, Nil).foreach(n => dependentsOf(n).foreach(markInvalid))

        (sources.filter(p => invalid(p.toString)), removed.toList)

    if fullRebuild then
      deleteRecursively(classesDir)
      Files.createDirectories(classesDir)
    else
      Files.createDirectories(classesDir)
      for p <- toCompile do
        prev.flatMap(_.files.get(p.toString)).foreach(r => purgeArtifactsFor(classesDir, r.pkg, r.declaredNames))
      for (_, r) <- removedRecords do
        purgeArtifactsFor(classesDir, r.pkg, r.declaredNames)

    if toCompile.nonEmpty then
      // scala-cli/sbt both print a line before a real compile -- scalino-dotc
      // itself stays silent on success (no "compiling..." banner of its own),
      // so without this a multi-second compile looks like scalino hung.
      System.err.println(Color.gray(s"Compiling project ($buildLabel)"))
      // classesDir on the compile classpath (harmless on a full rebuild --
      // it's freshly emptied above) is what lets scalino-dotc resolve
      // symbols from files it isn't recompiling this round out of their
      // already-compiled .tasty/.class instead of needing their source.
      val compileCp = s"$classesDir$CP_SEP${cc.compileCp}"
      val compileCmd = List(
        s"$dist/scalino-dotc",
        "-javabootclasspath", cc.javaBase,
        "-classpath", compileCp,
        "-Xplugin:" + cc.pluginJar, "-Xplugin-require:scalanative",
        "-Yretain-trees",
        // The reduced dotc has no JVM backend, so this early (pipelined)
        // output is the only way a .tasty gets written next to the .nir.
        "-Xearly-tasty-output", classesDir.toString
      ) ++ extraOptions ++ List(
        "-color:never", "-d", classesDir.toString
      ) ++ toCompile.map(_.toString)

      val (compileExit, compileOut) = runCaptureAll(compileCmd)
      Diagnostics.render(compileOut).foreach(System.err.println)
      if compileExit != 0 then
        System.err.println(Color.gray(s"Error compiling project ($buildLabel)"))
        fail(CompilationFailed)
      System.err.println(Color.gray(s"Compiled project ($buildLabel)"))
      // On failure, the manifest is deliberately left untouched: it still
      // describes the last known-good state, so the next attempt treats
      // every file in this failed batch as still-changed and retries it
      // (plus dependents) rather than caching a broken result as if it
      // compiled.

    if incremental then
      val toCompileSet = toCompile.toSet
      val newFiles = sources.map { p =>
        val key = p.toString
        val record =
          if fullRebuild || toCompileSet(p) then FileRecord(curHash(key), curPkg(key), curDeclared(key))
          else prev.get.files(key)
        key -> record
      }.toMap
      saveManifest(manifestPath, IncrManifest(fingerprint, newFiles))
    else
      Files.deleteIfExists(manifestPath)

  /** User-facing Scala Native build settings, resolved from `//> using
   *  native*` directives and/or `--native-*` CLI flags (directives win --
   *  same precedence as `explicitMainClass`/`options` above) and forwarded to
   *  dist/scalino-linkdriver as extra flag-style argv (see LinkDriver.scala's
   *  own parser). No `nativeVersion`: this toolchain only ever targets the
   *  one pinned scala-native version it was built for. */
  case class NativeOpts(
    mode: Option[String] = None,
    gc: Option[String] = None,
    lto: Option[String] = None,
    clang: Option[String] = None,
    clangpp: Option[String] = None,
    target: Option[String] = None,
    // Cross targets, e.g. aarch64-unknown-linux-musl; empty: the host.
    targetTriples: List[String] = Nil,
    // `<triple>=<dir>` per cross target
    sysroots: List[String] = Nil,
    embedResources: Boolean = false,
    multithreading: Boolean = true,
    directCodegen: Boolean = true,
    compactHeaders: Boolean = true,
    // None: left to scalino-linkdriver, on unless a library reading byte
    // arrays through raw pointers (jsoniter-scala-core) is on the classpath
    compactByteArrays: Option[Boolean] = None,
    gcStwSweep: Boolean = false,
    heapHistogram: Boolean = false,
    optimize: Boolean = true,
    linking: List[String] = Nil,
    compile: List[String] = Nil,
    cCompile: List[String] = Nil,
    cppCompile: List[String] = Nil,
    prune: List[String] = Nil
  )

  /** Directives win over the equivalent `--native-*` CLI flag for
   *  single-valued settings (matches `explicitMainClass`'s own precedence);
   *  list-valued and boolean settings combine both sources instead, matching
   *  how `options`/`deps` already combine directive and CLI values above. */
  def targetTriplesOf(directives: Directives, o: RunOpts): List[String] =
    (directives.nativeTargetTriples ++ o.cliNativeTargetTriples).flatMap(_.split(',').toList).map(_.trim).filter(_.nonEmpty).distinct

  def resolveNativeOpts(directives: Directives, o: RunOpts): NativeOpts =
    // Windows is gated on the target when cross-compiling, else on the host.
    val triples = targetTriplesOf(directives, o)
    val windowsTarget =
      if triples.nonEmpty then triples.exists(t => t.contains("windows") || t.contains("mingw"))
      else sys.props.getOrElse("os.name", "").toLowerCase.startsWith("windows")
    NativeOpts(
      mode = directives.nativeMode.orElse(o.cliNativeMode),
      gc = directives.nativeGc.orElse(o.cliNativeGc),
      lto = directives.nativeLto.orElse(o.cliNativeLto),
      clang = directives.nativeClang.orElse(o.cliNativeClang),
      clangpp = directives.nativeClangPP.orElse(o.cliNativeClangpp),
      target = directives.nativeTarget.orElse(o.cliNativeTarget),
      targetTriples = triples,
      sysroots = directives.nativeSysroots ++ o.cliNativeSysroots,
      embedResources = directives.nativeEmbedResources.getOrElse(false) || o.cliEmbedResources,
      // Default-on, same opt-out shape as directCodegen above:
      // either source wins outright over the default if given explicitly.
      multithreading = directives.nativeMultithreading.orElse(o.cliNativeMultithreading).getOrElse(true),
      // Default-on everywhere except Windows (DirectCodeGen.Capability.
      // wholeBuildGatesOk gates out Windows permanently -- WindowsCompat is
      // unported), with an explicit opt-out via the directive or
      // `--native-direct-codegen=false` -- either one, if given,
      // wins outright over the OS-based default. The OS is the target's
      // when `--native-target-triple` is given, else the host's.
      directCodegen = directives.nativeDirectCodegen
        .orElse(o.cliNativeDirectCodegen)
        .getOrElse(!windowsTarget),
      // Default-on everywhere except Windows (untested there), with an explicit
      // opt-out via the directive or `--native-compact-headers=false`. Only
      // takes effect for 64-bit targets linked with the immix GC (see
      // NativeConfig.compactHeadersEnabled), anything else silently falls back
      // to regular object headers.
      compactHeaders = directives.nativeCompactHeaders
        .orElse(o.cliNativeCompactHeaders)
        .getOrElse(!windowsTarget),
      // Default-on, but scalino-linkdriver turns it off when the classpath has
      // a library reading byte arrays through raw pointers (jsoniter-scala-core),
      // unless given explicitly. See NativeConfig.compactByteArrays.
      compactByteArrays = directives.nativeCompactByteArrays.orElse(o.cliNativeCompactByteArrays),
      // Opt-in, commix GC only: see NativeConfig.gcStwSweep.
      gcStwSweep = directives.nativeGcStwSweep.getOrElse(false) || o.cliNativeGcStwSweep,
      // Opt-in: gates SCALINO_HEAP_HISTOGRAM at C compile time -- see
      // LinkDriver.scala's withCOptions. Off by default since the feature's
      // always-linked static table otherwise bloats every binary by ~7.5MB
      // regardless of whether it's ever used at runtime.
      heapHistogram = directives.nativeHeapHistogram.getOrElse(false) || o.cliNativeHeapHistogram,
      // Default-on, same opt-out shape as multithreading/directCodegen above.
      optimize = directives.nativeOptimize.orElse(o.cliNativeOptimize).getOrElse(true),
      linking = directives.nativeLinking ++ o.cliNativeLinking,
      compile = directives.nativeCompile ++ o.cliNativeCompile,
      cCompile = directives.nativeCCompile ++ o.cliNativeCCompile,
      cppCompile = directives.nativeCppCompile ++ o.cliNativeCppCompile,
      prune = directives.nativePrune ++ o.cliNativePrune
    )

  /** The compile-only half of a build: parses/resolves nothing itself (that's
   *  already done by the caller), just runs sources through scalino-dotc and
   *  returns the resulting classesDir -- no entry-point detection, no link
   *  step. Shared by `scalino compile` (which stops here) and `buildBinary`
   *  (which goes on to link). */
  def compileOnly(
    sources: List[Path],
    extraClasspath: String,
    extraOptions: List[String] = Nil,
    extraCompileOnlyClasspath: String = "",
    incremental: Boolean = true
  ): Path =
    val classesDir = Paths.get(".scalino-build", "_scratch", "classes")
    val cc = computeCompileClasspath(extraClasspath, extraCompileOnlyClasspath)
    compileToClasses(sources, classesDir, cc, extraOptions, incremental)
    classesDir

  /** Mirrors `src` into `dst` (hard links, copy fallback) minus every class
   *  file belonging to one of `excluded` (fully-qualified names): `Foo.class`/
   *  `Foo.nir`/`Foo.tasty` plus anything nested or companion (`Foo$...`).
   *  Used to link a test binary without the unselected suites -- see
   *  `testNote` for why leaving them out of the driver isn't enough. */
  def filteredLinkClasses(src: Path, dst: Path, excluded: Set[String]): Path =
    deleteRecursively(dst)
    Files.createDirectories(dst)
    val excludedPaths = excluded.map(_.replace('.', '/'))
    def isExcluded(rel: String): Boolean =
      val base = rel.lastIndexOf('.') match
        case -1 => rel
        case i => rel.substring(0, i)
      excludedPaths.exists(e => base == e || base.startsWith(e + "$"))
    // Relative paths built by hand (not `Path#relativize`): `File#listFiles`
    // on this toolchain's javalib can hand back an absolute path for a
    // relative parent, and relativize then rejects the mixed pair.
    def walk(rel: String): Unit =
      for f <- Option(src.resolve(rel).toFile.listFiles()).map(_.toList).getOrElse(Nil) do
        val childRel = if rel.isEmpty then f.getName else rel + "/" + f.getName
        if f.isDirectory then
          Files.createDirectories(dst.resolve(childRel))
          walk(childRel)
        else if !isExcluded(childRel) then
          val target = dst.resolve(childRel)
          try Files.createLink(target, src.resolve(childRel))
          catch case _: Exception => Files.copy(src.resolve(childRel), target, java.nio.file.StandardCopyOption.COPY_ATTRIBUTES)
    walk("")
    dst

  /** A binary can't be written over an existing directory. */
  def checkBinaryOutPath(out: Path): Unit =
    if Files.isDirectory(out) then fail(s"output path '$out' is a directory")

  def buildBinary(
    sources: List[Path],
    explicitMainClass: Option[String],
    extraClasspath: String,
    outFor: String => Path,
    extraOptions: List[String] = Nil,
    extraCompileOnlyClasspath: String = "",
    logLevel: String = "info",
    incremental: Boolean = true,
    nativeOpts: NativeOpts = NativeOpts(),
    longRunning: Boolean = false,
    excludeFromLink: Set[String] = Set.empty,
    // Where each cross target's binary goes; required when
    // `nativeOpts.targetTriples` is non-empty (`outFor` serves the host build).
    outForTriple: (String, String) => Path = (_, _) => Paths.get("unused")
  ): String =
    val compiledDir = compileOnly(sources, extraClasspath, extraOptions, extraCompileOnlyClasspath, incremental)
    val cc = computeCompileClasspath(extraClasspath, extraCompileOnlyClasspath)

    val mainClass = detectMainClass(compiledDir, explicitMainClass)
    // Same check as handleRunOrCompile's, for the default name (no -o) that
    // is only known now -- still before the (slow) link step.
    if nativeOpts.targetTriples.isEmpty then checkBinaryOutPath(outFor(mainClass))
    else nativeOpts.targetTriples.foreach(t => checkBinaryOutPath(outForTriple(mainClass, t)))
    val linkDir = Paths.get(".scalino-build").resolve(mainClass).resolve("link")
    val classesDir =
      if excludeFromLink.isEmpty then compiledDir
      else filteredLinkClasses(compiledDir, Paths.get(".scalino-build").resolve(mainClass).resolve("link-classes"), excludeFromLink)

    val linkCp =
      if extraClasspath.isEmpty then s"$classesDir$CP_SEP${cc.nativelibsCp}"
      else s"$classesDir$CP_SEP${cc.nativelibsCp}$CP_SEP$extraClasspath"
    val clang = nativeOpts.clang.getOrElse(findOnPath("clang"))
    val clangpp = nativeOpts.clangpp.getOrElse(findOnPath("clang++"))

    val nativeFlags =
      nativeOpts.mode.toList.flatMap(v => List("--mode", v)) ++
      nativeOpts.gc.toList.flatMap(v => List("--gc", v)) ++
      nativeOpts.lto.toList.flatMap(v => List("--lto", v)) ++
      nativeOpts.target.toList.flatMap(v => List("--target", v)) ++
      nativeOpts.targetTriples.flatMap(v => List("--target-triple", v)) ++
      nativeOpts.sysroots.flatMap(v => List("--sysroot", v)) ++
      (if nativeOpts.embedResources then List("--embed-resources") else Nil) ++
      (if nativeOpts.multithreading then List("--multithreading") else Nil) ++
      (if incremental then List("--incremental-compilation") else Nil) ++
      (if nativeOpts.directCodegen then List("--direct-codegen") else Nil) ++
      (if nativeOpts.compactHeaders then List("--compact-headers") else List("--no-compact-headers")) ++
      nativeOpts.compactByteArrays.map(v => if v then "--compact-byte-arrays" else "--no-compact-byte-arrays").toList ++
      (if nativeOpts.gcStwSweep then List("--gc-stw-sweep") else Nil) ++
      (if nativeOpts.heapHistogram then List("--heap-histogram") else Nil) ++
      (if !nativeOpts.optimize then List("--no-opt") else Nil) ++
      nativeOpts.linking.flatMap(v => List("--linking", v)) ++
      nativeOpts.compile.flatMap(v => List("--compile", v)) ++
      nativeOpts.cCompile.flatMap(v => List("--c-compile", v)) ++
      nativeOpts.cppCompile.flatMap(v => List("--cpp-compile", v)) ++
      nativeOpts.prune.flatMap(v => List("--prune", v))

    val linkCmd = List(s"$dist/scalino-linkdriver", linkCp, linkDir.toString, mainClass, clang, clangpp, logLevel) ++ nativeFlags
    val linkExit = if longRunning then linkIncremental(linkCmd) else runInherited(linkCmd)
    if linkExit != 0 then fail("linking failed")

    def collect(dir: Path, out: Path): Unit =
      val candidates = List(mainClass, mainClass.toLowerCase).flatMap(n => List(n, n + ".exe")).map(dir.resolve)
      val actual = candidates.find(Files.exists(_)).getOrElse(fail(s"expected linked binary at ${candidates.head}, not found"))
      Files.createDirectories(Option(out.getParent).getOrElse(Paths.get(".")))
      Files.copy(actual, out, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
      out.toFile.setExecutable(true)
    if nativeOpts.targetTriples.isEmpty then collect(linkDir, outFor(mainClass))
    else nativeOpts.targetTriples.foreach(t => collect(linkDir.resolve(t), outForTriple(mainClass, t)))
    mainClass

  // ---------------------------------------------------------------------
  // CLI surface, scala-cli-shaped:
  //   scalino <sources...>                       run (default command, like
  //                                            `scala-cli Foo.scala`)
  //   scalino run <sources...> [options]
  //   scalino compile [options]      (sources default to `.`; explicit
  //                                  <sources...> accepted, scala-cli compat)
  //   scalino package <sources...> -o <out> [options]
  //   scalino version / scalino --help
  // options: --main-class X | --dep coord | -S/--scala ver |
  //          -O/--scalac-option opt | -w/--watch | -o/--output path
  //          (package only) | -v/--verbose | -q/--quiet | -- <program args...>
  // ---------------------------------------------------------------------

  def printVersion(): Unit =
    println(s"scalino ${BuildInfo.scalinoVersion} -- Scala ${BuildInfo.scalaVersion}, Scala Native ${BuildInfo.nativeVersion}")

  private def oMain: String =
    s"""|  --main-class <name>        explicit entry point (skips auto-detection)
       |""".stripMargin

  private def oDeps: String =
    s"""|  --dep <coord>              add a dependency (repeatable; no -d short form -- real
       |                             scala-cli's -d means --output, not --dependency)
       |  --compile-dep <coord>      add a compile-time-only dependency (repeatable)
       |""".stripMargin

  private def oRepo: String =
    s"""|  -r, --repository <repo>   extra Maven repository for dependency resolution (repeatable;
       |                             passed straight through to `cs fetch -r`, e.g. a URL or
       |                             `sonatype:snapshots`)
       |""".stripMargin

  private def oScala: String =
    s"""|  -S, --scala <version>      declare a Scala version (must match ${BuildInfo.scalaVersion})
       |""".stripMargin

  private def oScalac: String =
    s"""|  -O, --scalac-option <opt>  pass an extra compiler flag (repeatable)
       |""".stripMargin

  private def oWatch: String =
    s"""|  -w, --watch                rebuild (and, for `run`, rerun) on source changes
       |  --watching, --watching-path <path>   extra path to watch under -w (repeatable;
       |                             file or directory, watched recursively)
       |""".stripMargin

  private def oArgsFile: String =
    s"""|  --args-file <path>         expand to the file's contents as extra scalac options
       |                             (dotc's own native `@file` response-file expansion --
       |                             one option per line, `#` starts a line comment)
       |""".stripMargin

  private def oOut: String =
    s"""|  -o, --output <path>        output path (package only)
       |""".stripMargin

  private def oPackage: String =
    s"""|  --format <list>            distribution format(s), comma-separated or repeated:
       |                               binary  bare native executable (default; uses -o <file>)
       |                               tar     <name>-<ver>-<triple>.tar.gz + .sha256 (Linux, macOS)
       |                               deb     Debian/Ubuntu package (Linux/glibc)
       |                               rpm     RHEL/Fedora/SUSE package (Linux/glibc; needs rpmbuild)
       |                               docker  Dockerfile + image (builds with docker/podman if present)
       |                               brew    Homebrew formula over every tarball in the output dir
       |                             with any non-binary format, -o is an output *directory* (default: packages)
       |  --pkg-name <name>         package name (default: the main class, lowercased)
       |  --pkg-version <version>   package version
       |  --release-url <url>       where the tarballs will be published (brew)
       |""".stripMargin

  private def packageDirectivesBlock: String =
    s"""|packaging directives (for `--format`):
       |  //> using packageName "my-app"
       |  //> using packageVersion "1.2.3"
       |  //> using packageDescription "One-line summary"
       |  //> using packageMaintainer "Jane Doe <jane@example.com>"
       |  //> using packageLicense "MIT"
       |  //> using packageHomepage "https://example.com"
       |  //> using packageDep "libssl3"             (extra runtime dependency, repeatable; deb/rpm)
       |  //> using packageFile "./my-app.service:/lib/systemd/system/my-app.service"   (extra installed file)
       |  //> using packageDockerBase "gcr.io/distroless/cc-debian13"   (default debian:stable-slim, alpine on musl)
       |  //> using packageDockerImage "ghcr.io/me/my-app"
       |  //> using packageReleaseUrl "https://github.com/me/my-app/releases/download/v1.2.3"
       |
       |There is no cross-compilation: every package holds the binary built on this host. Build
       |each OS/arch on its own CI runner into the same output dir, then run `--format brew` last.
       |""".stripMargin

  private def oVerbose: String =
    s"""|  -v, --verbose              show full build-tool debug output (raw clang/linker invocations)
       |""".stripMargin

  private def oQuiet: String =
    s"""|  -q, --quiet                only show warnings/errors
       |""".stripMargin

  private def oColor: String =
    s"""|  --color <always|auto|never>   colorize output (auto by default; also honors
       |                             NO_COLOR/CLICOLOR_FORCE, cargo/scala-cli-style)
       |""".stripMargin

  private def oTestFw: String =
    s"""|  --test-framework <class>   explicit test framework class (skips auto-detection; test only)
       |""".stripMargin

  private def oTestOnly: String =
    s"""|  --test-only <glob>         only run test classes whose fully-qualified name matches the glob
       |                             (test only); the rest are left out of the linked binary entirely
       |""".stripMargin

  private def oNoInc: String =
    s"""|  --no-incremental           always fully recompile (skip the incremental-compile cache)
       |""".stripMargin

  private def oOffline: String =
    s"""|  --offline                  never touch the network: resolve from $LockFileName and the
       |                             local artifact cache only (also SCALINO_OFFLINE=1). Cache dir:
       |                             $$SCALINO_CACHE (-> COURSIER_CACHE), else coursier's default
       |""".stripMargin

  private def lockNote: String =
    s"""|lockfile:
       |  `scalino lock <sources...>` resolves all dependencies and writes $LockFileName
       |  (per artifact: cache-relative path, URL, sha256). While it exists, builds use it
       |  instead of resolving -- reproducible, and fetchable up front by Nix and friends.
       |""".stripMargin

  private def oProgArgs: String =
    s"""|  -- <args...>               program args (run) or test-framework filter args (test),
       |                             e.g. `scalino test . -- "*MySuite*"` (munit/utest-style filter)
       |""".stripMargin

  private def nativeOpts: String =
    s"""|  --native-mode <mode>       debug|release-fast|release-size|release-full (debug by default)
       |  --native-gc <gc>           immix|commix|boehm|none (immix by default)
       |  --native-lto <lto>         none|thin|full (none by default)
       |  --native-clang <path>      path to the clang command (autodetected from PATH by default)
       |  --native-clangpp <path>    path to the clang++ command (autodetected from PATH by default)
       |  --native-linking <opt>     extra option passed to clang verbatim during linking (repeatable)
       |  --native-compile <opt>     extra compile option, all sources (repeatable)
       |  --native-c-compile <opt>   extra compile option, C files only (repeatable)
       |  --native-cpp-compile <opt> extra compile option, C++ files only (repeatable)
       |  --native-prune <pattern>   link-time prune: replace the bodies of every method of a class
       |                             (`pkg.Class`), package (`pkg.sub`) or method (`pkg.Class#name`) with a
       |                             throw, dropping whatever was reachable only through them (repeatable)
       |  --native-target <target>  app|static|dynamic (app by default)
       |  --native-target-triple <t> cross-compile for this target triple, e.g. aarch64-unknown-linux-musl
       |                             (repeatable or comma-separated; one link process builds all of them,
       |                             `package` only; several targets write `<out>-<triple>`)
       |  --native-sysroot <t>=<dir> sysroot for cross target <t> (repeatable)
       |  --embed-resources          embed resources into the binary (readable via the Java resources API)
       |  --native-multithreading[=true|false]  Scala Native multithreading support
       |                                        (on by default; pass =false to opt out)
       |  --native-direct-codegen[=true|false]  skip .ll text + per-file clang,
       |                                        building object files straight from libLLVM's C
       |                                        API (on by default, except on Windows; needs the
       |                                        toolchain itself running as compiled Scala Native
       |                                        code with a discoverable libLLVM, else it's a
       |                                        silent no-op; pass =false to opt out)
       |  --native-compact-headers[=true|false]  4 byte object headers (class id and lock
       |                                        bits) instead of 8-16 bytes: less memory for
       |                                        every object and array (on by default, except
       |                                        on Windows; only for 64-bit targets with the
       |                                        immix GC; pass =false to opt out)
       |  --native-compact-byte-arrays[=true|false]  also give Array[Byte] the compact header
       |                                        (on by default, except when jsoniter-scala-core is
       |                                        on the classpath: it reads byte arrays through raw
       |                                        pointers, with the regular layout; needs compact
       |                                        headers)
       |  --native-gc-stw-sweep      commix GC only: keep mutators paused through the
       |                             parallel sweep too, instead of resuming them once
       |                             marking finishes (off by default; throughput over
       |                             concurrency -- trades commix's concurrent/lazy sweep
       |                             bookkeeping for a longer, fully parallel STW pause)
       |  --native-optimize[=true|false]  Scala Native's Interflow NIR optimizer pass
       |                                  (on by default; pass =false to skip it, e.g. for
       |                                  faster iterative builds or clearer debug binaries)
       |""".stripMargin

  private def directivesBlock: String =
    s"""|directives (in source files), one per line -- `dep`/`options`/etc also
       |accept scala-cli's own longer spellings (`dependency`/`scalacOption`/...):
       |  //> using dep "org::name:version"
       |  //> using compileOnly.dep "org::name:version"
       |  //> using test.dep "org::name:version"    (test scope; e.g. munit, utest, scalatest, zio-test-sbt)
       |  //> using testFramework "fully.qualified.Framework"   (explicit override)
       |  //> using scala "3.x"
       |  //> using mainClass "Foo"
       |  //> using options "-flag1", "-flag2"
       |  //> using test.scalacOption "-flag"        (test scope only, in addition to `options` above)
       |  //> using jar "./lib/local.jar"            (add a local jar straight to the classpath)
       |  //> using resourceDir "./resources"        (dir on the classpath; combine with
       |                                             --embed-resources/nativeEmbedResources
       |                                             to actually bake its files into the binary)
       |  //> using repository "https://my.org/maven"   (extra Maven repo, repeatable)
       |  //> using file "./Other.scala"             (extra source, relative to this file's dir)
       |  //> using exclude "generated/**"            (glob; drop matching sources, cwd-relative)
       |  //> using nativeMode "release-fast"
       |  //> using nativeGc "immix"
       |  //> using nativeLto "thin"
       |  //> using nativeClang "/path/to/clang"
       |  //> using nativeClangPP "/path/to/clang++"
       |  //> using nativeLinking "-L/opt/homebrew/lib"
       |  //> using nativeCompile "-flag"
       |  //> using nativeCCompile "-flag"
       |  //> using nativeCppCompile "-flag"
       |  //> using nativePrune "org.http4s.ember.core.h2"   (class, package or Class#method; bodies become a throw)
       |  //> using nativeTarget "application"   (application|library-dynamic|library-static)
       |  //> using nativeTargetTriple "aarch64-unknown-linux-musl"   (cross target, repeatable)
       |  //> using nativeSysroot "aarch64-unknown-linux-musl=/path/to/sysroot"
       |  //> using nativeEmbedResources true
       |  //> using nativeMultithreading false   (on by default)
       |  //> using nativeDirectCodegen false   (on by default except on Windows)
       |  //> using nativeCompactHeaders false   (on by default except on Windows)
       |  //> using nativeCompactByteArrays true   (on by default, see --native-compact-byte-arrays)
       |  //> using nativeGcStwSweep true   (commix GC only; off by default)
       |  //> using nativeHeapHistogram true   (live-heap histogram GC debug dump; off by default, adds ~7.5MB to binary)
       |  //> using nativeOptimize false   (Interflow NIR optimizer; on by default)
       |""".stripMargin

  private def testNote: String =
    s"""|`test` auto-detects the test framework structurally (scans the resolved
       |test classpath for a class implementing sbt.testing.Framework -- no
       |hardcoded list, so any framework with a scala-native port works, not
       |just the ones this was verified against). Only SubclassFingerprint-based
       |frameworks are supported (covers munit/utest/scalatest/zio-test-sbt);
       |JUnit4-style @Test-annotated discovery is not. Whole test classes are
       |selected (no per-test-method filtering) -- use `-- <pattern>` to filter,
       |forwarded to the framework's own runner untouched.
       |
       |`--test-only <glob>` (scala-cli's own flag; `*`/`**` match any characters,
       |`?` one) keeps only the test classes whose fully-qualified name matches, and
       |leaves every other discovered test class out of the link. Scala Native roots
       |each reflectively-instantiable class's static initializer, so merely not
       |mentioning a suite in the generated driver would not let the optimizer drop
       |it; excluding its NIR from the link classpath does. The link is therefore
       |smaller and faster. Ancestors of a selected test stay linked; if a selected
       |test references another test class some other way and the trimmed link
       |fails, scalino retries with every test class linked.
       |""".stripMargin

  private def setupIdeNote: String =
    s"""|`setup-ide` writes `.scalino-build/scalino-lsp.json` -- dotty's own
       |pre-Metals IDE config format (compilerArguments/sourceDirectories/
       |dependencyClasspath), read by dist/scalino-lsp on
       |startup. Same command name as scala-cli's `setup-ide`, but a
       |different output file: this toolchain's LSP speaks that format
       |directly, no BSP layer needed.
       |""".stripMargin

  private def completionsNote: String =
    s"""|`completions` prints a shell completion script to stdout, cargo/
       |scala-cli-style. A brew/apt/dnf/arch/nix install already places
       |these for you (pre-generated at build time -- see
       |build/07-build-scalino.sh); this is mainly for install.sh/manual
       |installs, e.g.:
       |  bash:  scalino completions bash > /etc/bash_completion.d/scalino
       |  zsh:   scalino completions zsh > "$${fpath[1]}/_scalino"
       |  fish:  scalino completions fish > ~/.config/fish/completions/scalino.fish
       |""".stripMargin

  private def sourcesNote: String =
    s"""|a source argument may be a directory: every .scala file under it is
       |included (skipping hidden and build-output directories). files under
       |a `test/` directory (or sbt-style `src/test/scala/`) are test scope
       |and excluded from `run`/`compile` -- scala-cli's convention. `test`
       |compiles both scopes together (test sources depend on main scope).
       |""".stripMargin

  private def incrementalNote: String =
    s"""|compilation is incremental by default: unchanged sources (and
       |anything that doesn't textually mention a changed name) are reused
       |from the last build instead of being recompiled -- pass
       |--no-incremental to always fully recompile.
       |""".stripMargin

  private def nativeHeader: String =
    """|Scala Native options (no --native-version -- this toolchain only ever
       |targets the one pinned scala-native version it was built for):
       |""".stripMargin

  def printUsage(out: java.io.PrintStream): Unit =
    out.print(
      s"""scalino: a mini scala-cli, self-hosted on scalino (no JVM anywhere)
         |
         |${Color.bold("usage:", out)}
         |  scalino <sources...>                   run (default command)
         |  scalino run <sources...> [options]     compile and run
         |  scalino compile [options]               compile only, no link (see build output)
         |  scalino package <sources...> [options] -o <out>   compile and link a native binary
         |  scalino test [options] [-- <framework args>]   compile and run tests in the current directory
         |  scalino setup-ide <sources...> [options]   write .scalino-build/scalino-lsp.json for editor LSP support
         |  scalino lock <sources...> [options]    resolve dependencies and write scalino.lock.json
         |  scalino completions <bash|zsh|fish>    print a shell completion script to stdout
         |  scalino sysroot build <triple>...      set up the sysroot for cross-compiling to <triple> (see --native-target-triple)
         |  scalino clean                         delete the .scalino-build directory
         |  scalino version                        print version info
         |  scalino --help                         this message
         |  scalino <command> --help               help for one command
         |
         |$sourcesNote
         |$incrementalNote
         |${Color.bold("options:", out)}
         |$oMain$oDeps$oRepo$oScala$oScalac$oWatch$oArgsFile$oOut$oVerbose$oQuiet$oColor$oTestFw$oTestOnly$oNoInc$oOffline$oProgArgs
         |$nativeHeader$nativeOpts
         |$directivesBlock
         |$lockNote
         |$testNote
         |$setupIdeNote
         |$completionsNote""".stripMargin
    )

  val HelpCommands: Set[String] =
    Set("run", "compile", "package", "test", "setup-ide", "lock", "completions", "clean", "sysroot", "version")

  /** `scalino <command> --help`: usage plus only the options/notes that apply to
   *  that command. */
  def printCommandHelp(cmd: String, out: java.io.PrintStream): Unit =
    def head(usage: String, desc: String): String =
      s"$desc\n\n${Color.bold("usage:", out)}\n  $usage\n\n"
    def opts(parts: String*): String =
      s"${Color.bold("options:", out)}\n${parts.mkString}\n"
    val common = oDeps + oRepo + oScala + oScalac + oArgsFile + oVerbose + oQuiet + oColor + oNoInc + oOffline
    val text = cmd match
      case "run" =>
        head("scalino run <sources...> [options] [-- <program args>]", "Compile and run.") +
          sourcesNote + "\n" + incrementalNote + "\n" +
          opts(oMain + common + oWatch + oProgArgs) +
          nativeHeader + nativeOpts + "\n" + directivesBlock
      case "compile" =>
        head("scalino compile [options]", "Compile only, no link (see build output), the current directory by default.") +
          sourcesNote + "\n" + incrementalNote + "\n" +
          opts(common + oWatch) + directivesBlock
      case "package" =>
        head("scalino package <sources...> -o <out> [--format <formats>] [options]", "Compile and link a native binary, or package it (deb, rpm, docker, tar, brew).") +
          sourcesNote + "\n" + incrementalNote + "\n" +
          opts(oMain + common + oOut + oPackage + oWatch) +
          nativeHeader + nativeOpts + "\n" + directivesBlock + "\n" + packageDirectivesBlock
      case "test" =>
        head("scalino test [options] [-- <framework args>]", "Compile and run tests in the current directory.") +
          sourcesNote + "\n" + incrementalNote + "\n" +
          opts(common + oWatch + oTestFw + oTestOnly + oProgArgs) +
          nativeHeader + nativeOpts + "\n" + directivesBlock + "\n" + testNote
      case "setup-ide" =>
        head("scalino setup-ide <sources...> [options]", "Write .scalino-build/scalino-lsp.json for editor LSP support.") +
          sourcesNote + "\n" + opts(common) + directivesBlock + "\n" + setupIdeNote
      case "lock" =>
        head("scalino lock <sources...> [options]", s"Resolve all dependencies and write $LockFileName.") +
          sourcesNote + "\n" + opts(oDeps + oRepo + oScala + oScalac + oArgsFile + oVerbose + oQuiet + oColor + oOffline) +
          directivesBlock + "\n" + lockNote
      case "completions" =>
        head("scalino completions <bash|zsh|fish>", "Print a shell completion script to stdout.") + completionsNote
      case "clean" =>
        head("scalino clean", "Delete the .scalino-build directory.")
      case "sysroot" =>
        head("scalino sysroot <build|list|path> ...", "Manage the sysroots used to cross-compile (`--native-target-triple`).") +
          s"""|  scalino sysroot build <triple>... [--force]
              |      assemble the sysroot from upstream packages (checksum-verified; needs curl and tar)
              |      into ${Sysroot.cacheRoot} (override with $$SCALINO_SYSROOT_DIR; downloads are
              |      cached in <that>/.sources, or $$SCALINO_SYSROOT_SOURCES, which can be pre-filled)
              |  scalino sysroot list           supported targets and whether they are installed
              |  scalino sysroot path <triple>  where an installed sysroot lives
              |
              |available: ${Sysroot.Supported.mkString(", ")}
              |Linux targets are static musl binaries that run on any distro. macOS targets need no
              |sysroot on a Mac (the Xcode SDK serves both architectures).
              |""".stripMargin
      case "version" =>
        head("scalino version", "Print version info.")
    out.print(text)


  case class RunOpts(
    sources: List[Path] = Nil,
    mainClassOpt: Option[String] = None,
    out: Option[String] = None,
    watch: Boolean = false,
    cliWatchingPaths: List[String] = Nil,
    cliDeps: List[String] = Nil,
    cliCompileOnlyDeps: List[String] = Nil,
    cliRepositories: List[String] = Nil,
    cliScala: Option[String] = None,
    cliOptions: List[String] = Nil,
    progArgs: List[String] = Nil,
    verbose: Boolean = false,
    quiet: Boolean = false,
    testFrameworkOpt: Option[String] = None,
    testOnly: Option[String] = None,
    noIncremental: Boolean = false,
    cliNativeMode: Option[String] = None,
    cliNativeGc: Option[String] = None,
    cliNativeLto: Option[String] = None,
    cliNativeClang: Option[String] = None,
    cliNativeClangpp: Option[String] = None,
    cliNativeTarget: Option[String] = None,
    cliNativeTargetTriples: List[String] = Nil,
    cliNativeSysroots: List[String] = Nil,
    cliNativeLinking: List[String] = Nil,
    cliNativeCompile: List[String] = Nil,
    cliNativeCCompile: List[String] = Nil,
    cliNativeCppCompile: List[String] = Nil,
    cliNativePrune: List[String] = Nil,
    cliEmbedResources: Boolean = false,
    cliNativeMultithreading: Option[Boolean] = None,
    cliNativeDirectCodegen: Option[Boolean] = None,
    cliNativeCompactHeaders: Option[Boolean] = None,
    cliNativeCompactByteArrays: Option[Boolean] = None,
    cliNativeGcStwSweep: Boolean = false,
    cliNativeHeapHistogram: Boolean = false,
    cliNativeOptimize: Option[Boolean] = None,
    formats: List[String] = Nil,
    pkgName: Option[String] = None,
    pkgVersion: Option[String] = None,
    releaseUrl: Option[String] = None
  ):
    // scala-cli-style: -v shows the full build-tool debug trace (raw
    // clang/linker invocations, NativeConfig dumps), the default ("info")
    // shows just progress lines, -q drops those too and only surfaces
    // warnings/errors. -v wins if both are passed.
    def logLevel: String = if verbose then "verbose" else if quiet then "quiet" else "info"

  /** No source args -> default to the current directory, scala-cli-alike. */
  def defaultToCwd(o: RunOpts): RunOpts =
    if o.sources.isEmpty then o.copy(sources = List(Paths.get("."))) else o

  def parseRunOpts(args: Array[String]): RunOpts =
    var o = RunOpts()
    var i = 0
    var inProgArgs = false
    while i < args.length do
      if inProgArgs then o = o.copy(progArgs = o.progArgs :+ args(i))
      else args(i) match
        case "--" => inProgArgs = true
        case "--main-class" => o = o.copy(mainClassOpt = Some(args(i + 1))); i += 1
        case "-o" | "--output" => o = o.copy(out = Some(args(i + 1))); i += 1
        case "-w" | "--watch" => o = o.copy(watch = true)
        case "--watching" | "--watching-path" => o = o.copy(cliWatchingPaths = o.cliWatchingPaths :+ args(i + 1)); i += 1
        case "--args-file" => o = o.copy(cliOptions = o.cliOptions :+ s"@${args(i + 1)}"); i += 1
        // no `-d` short form: real scala-cli's `-d` means `--output`, not `--dependency` -- don't collide with it.
        case "--dep" | "--dependency" => o = o.copy(cliDeps = o.cliDeps :+ args(i + 1)); i += 1
        case "--compile-dep" | "--compile-only-dependency" => o = o.copy(cliCompileOnlyDeps = o.cliCompileOnlyDeps :+ args(i + 1)); i += 1
        case "-r" | "--repo" | "--repository" => o = o.copy(cliRepositories = o.cliRepositories :+ args(i + 1)); i += 1
        case "-S" | "--scala" | "--scala-version" => o = o.copy(cliScala = Some(args(i + 1))); i += 1
        case "-O" | "--scalac-option" | "--scalac-opt" => o = o.copy(cliOptions = o.cliOptions :+ args(i + 1)); i += 1
        case "-v" | "--verbose" => o = o.copy(verbose = true)
        case "-q" | "--quiet" => o = o.copy(quiet = true)
        case "--test-framework" => o = o.copy(testFrameworkOpt = Some(args(i + 1))); i += 1
        case "--test-only" => o = o.copy(testOnly = Some(args(i + 1))); i += 1
        case "--no-incremental" => o = o.copy(noIncremental = true)
        case "--offline" => offlineMode = true
        case "--native-version" =>
          die("--native-version is not supported -- this toolchain only ever targets the one pinned scala-native version it was built for")
        case "--native-mode" => o = o.copy(cliNativeMode = Some(args(i + 1))); i += 1
        case "--native-gc" => o = o.copy(cliNativeGc = Some(args(i + 1))); i += 1
        case "--native-lto" => o = o.copy(cliNativeLto = Some(args(i + 1))); i += 1
        case "--native-clang" => o = o.copy(cliNativeClang = Some(args(i + 1))); i += 1
        case "--native-clangpp" => o = o.copy(cliNativeClangpp = Some(args(i + 1))); i += 1
        case "--native-target" => o = o.copy(cliNativeTarget = Some(args(i + 1))); i += 1
        case "--native-target-triple" => o = o.copy(cliNativeTargetTriples = o.cliNativeTargetTriples :+ args(i + 1)); i += 1
        case "--native-sysroot" => o = o.copy(cliNativeSysroots = o.cliNativeSysroots :+ args(i + 1)); i += 1
        case "--native-linking" => o = o.copy(cliNativeLinking = o.cliNativeLinking :+ args(i + 1)); i += 1
        case "--native-compile" => o = o.copy(cliNativeCompile = o.cliNativeCompile :+ args(i + 1)); i += 1
        case "--native-c-compile" => o = o.copy(cliNativeCCompile = o.cliNativeCCompile :+ args(i + 1)); i += 1
        case "--native-cpp-compile" => o = o.copy(cliNativeCppCompile = o.cliNativeCppCompile :+ args(i + 1)); i += 1
        case "--native-prune" => o = o.copy(cliNativePrune = o.cliNativePrune :+ args(i + 1)); i += 1
        case "--format" | "--formats" =>
          Packaging.parseFormats(List(args(i + 1))) match
            case Left(err) => die(err)
            case Right(fs) => o = o.copy(formats = (o.formats ++ fs).distinct)
          i += 1
        case "--pkg-name" | "--package-name" => o = o.copy(pkgName = Some(args(i + 1))); i += 1
        case "--pkg-version" | "--package-version" => o = o.copy(pkgVersion = Some(args(i + 1))); i += 1
        case "--release-url" => o = o.copy(releaseUrl = Some(args(i + 1))); i += 1
        case "--embed-resources" => o = o.copy(cliEmbedResources = true)
        case "--native-multithreading" => o = o.copy(cliNativeMultithreading = Some(true))
        case f if f.startsWith("--native-multithreading=") =>
          val v = f.drop("--native-multithreading=".length)
          if (v != "true" && v != "false")
            die(s"--native-multithreading=$v: expected true or false")
          o = o.copy(cliNativeMultithreading = Some(v == "true"))
        case "--native-direct-codegen" => o = o.copy(cliNativeDirectCodegen = Some(true))
        case f if f.startsWith("--native-direct-codegen=") =>
          val v = f.drop("--native-direct-codegen=".length)
          if (v != "true" && v != "false")
            die(s"--native-direct-codegen=$v: expected true or false")
          o = o.copy(cliNativeDirectCodegen = Some(v == "true"))
        case "--native-compact-headers" => o = o.copy(cliNativeCompactHeaders = Some(true))
        case f if f.startsWith("--native-compact-headers=") =>
          val v = f.drop("--native-compact-headers=".length)
          if (v != "true" && v != "false")
            die(s"--native-compact-headers=$v: expected true or false")
          o = o.copy(cliNativeCompactHeaders = Some(v == "true"))
        case "--native-compact-byte-arrays" => o = o.copy(cliNativeCompactByteArrays = Some(true))
        case f if f.startsWith("--native-compact-byte-arrays=") =>
          val v = f.drop("--native-compact-byte-arrays=".length)
          if (v != "true" && v != "false")
            die(s"--native-compact-byte-arrays=$v: expected true or false")
          o = o.copy(cliNativeCompactByteArrays = Some(v == "true"))
        case "--native-gc-stw-sweep" => o = o.copy(cliNativeGcStwSweep = true)
        case "--native-heap-histogram" => o = o.copy(cliNativeHeapHistogram = true)
        case "--native-optimize" => o = o.copy(cliNativeOptimize = Some(true))
        case f if f.startsWith("--native-optimize=") =>
          val v = f.drop("--native-optimize=".length)
          if (v != "true" && v != "false")
            die(s"--native-optimize=$v: expected true or false")
          o = o.copy(cliNativeOptimize = Some(v == "true"))
        case f if f.startsWith("-") => die(s"unknown option: $f")
        case f => o = o.copy(sources = o.sources :+ Paths.get(f))
      i += 1
    o

  /** Re-run for every rebuild: re-parses directives (they may have changed
   *  under `-w`), resolves deps, detects the entry point, compiles+links,
   *  and for `run` also executes the result. Throws BuildFailed on any
   *  recoverable failure -- never calls `die`/`sys.exit` directly, so a
   *  watch-mode caller can catch it and keep watching. */
  def buildAndMaybeRun(mode: String, expanded: List[Path], o: RunOpts): Int =
    val directives = parseDirectives(expanded)
    directives.scalaVersion.orElse(o.cliScala).foreach { v =>
      if !BuildInfo.scalaVersion.startsWith(v) then
        System.err.println(
          Color.warn(s"scalino: warning: scala \"$v\" requested, but this toolchain only supports ${BuildInfo.scalaVersion} -- ignoring")
        )
    }
    val allDeps = (directives.deps ++ o.cliDeps).distinct
    val repos = (directives.repositories ++ o.cliRepositories).distinct
    val depsCache = Paths.get(".scalino-build").resolve("deps-cache")
    val extraClasspath = (List(resolveDeps(allDeps, depsCache, repos)) ++ directives.jars ++ directives.resourceDirs).filter(_.nonEmpty).mkString(CP_SEP)
    val extraCompileOnlyClasspath = resolveDeps((directives.compileOnlyDeps ++ o.cliCompileOnlyDeps).distinct, depsCache, repos)
    val explicitMainClass = o.mainClassOpt.orElse(directives.mainClass)
    val options = directives.options ++ o.cliOptions
    val nativeOpts0 = resolveNativeOpts(directives, o)
    // Cross targets need a sysroot each: the one given, else the installed one.
    val nativeOpts =
      if mode == "package" && nativeOpts0.targetTriples.nonEmpty then
        nativeOpts0.copy(sysroots = Sysroot.resolve(nativeOpts0.targetTriples, nativeOpts0.sysroots))
      else nativeOpts0

    mode match
      case "run" =>
        // Only the host's own binary can be run here.
        if nativeOpts.targetTriples.exists(t => Packaging.hostFromTriple(t) != Packaging.detectHost()) || nativeOpts.targetTriples.size > 1 then
          fail(s"run: can't execute a binary for ${nativeOpts.targetTriples.mkString(", ")} on this machine -- use `scalino package`")
        val hostOpts = nativeOpts.copy(targetTriples = Nil, sysroots = Nil)
        def binPathFor(mc: String): Path = Paths.get(".scalino-build").resolve(mc).resolve("bin")
        val mainClass = buildBinary(expanded, explicitMainClass, extraClasspath, binPathFor, options, extraCompileOnlyClasspath, o.logLevel, !o.noIncremental, hostOpts, o.watch)
        runInherited(binPathFor(mainClass).toString :: o.progArgs)
      case "package" if o.formats.nonEmpty && o.formats != List("binary") =>
        def binPathFor(mc: String): Path = Paths.get(".scalino-build").resolve(mc).resolve("bin")
        def binPathForTriple(mc: String, t: String): Path = Paths.get(".scalino-build").resolve(mc).resolve(s"bin-$t")
        val mainClass = buildBinary(expanded, explicitMainClass, extraClasspath, binPathFor, options, extraCompileOnlyClasspath, o.logLevel, !o.noIncremental, nativeOpts, o.watch, outForTriple = binPathForTriple)
        val meta = Packaging.resolveMeta(directives.pkg, mainClass, o.pkgName, o.pkgVersion, o.releaseUrl)
        try
          if nativeOpts.targetTriples.isEmpty then
            Packaging.packageAll(o.formats, binPathFor(mainClass), meta, Paths.get(o.out.getOrElse("packages")), o.quiet)
          else
            for t <- nativeOpts.targetTriples do
              Packaging.packageAll(o.formats, binPathForTriple(mainClass, t), meta, Paths.get(o.out.getOrElse("packages")), o.quiet, Some(t))
        catch case e: java.io.IOException => fail(s"packaging failed: ${e.getMessage}")
        0
      case "package" =>
        def outPathFor(mc: String): Path = Paths.get(o.out.getOrElse(mc.substring(mc.lastIndexOf('.') + 1)))
        // One cross target: -o as given. Several: `<out>-<triple>`.
        def outPathForTriple(mc: String, t: String): Path =
          val base = outPathFor(mc)
          val named = if nativeOpts.targetTriples.size == 1 then base else base.resolveSibling(s"${base.getFileName}-$t")
          // Windows binaries only run with .exe
          if t.contains("windows") && !named.getFileName.toString.endsWith(".exe") then named.resolveSibling(s"${named.getFileName}.exe") else named
        val mainClass = buildBinary(expanded, explicitMainClass, extraClasspath, outPathFor, options, extraCompileOnlyClasspath, o.logLevel, !o.noIncremental, nativeOpts, o.watch, outForTriple = outPathForTriple)
        val outPaths =
          if nativeOpts.targetTriples.isEmpty then List(outPathFor(mainClass)) else nativeOpts.targetTriples.map(outPathForTriple(mainClass, _))
        if !o.quiet then
          for outPath <- outPaths do
            val shown = if outPath.isAbsolute || outPath.startsWith("..") then outPath.toString else "./" + outPath
            if nativeOpts.targetTriples.isEmpty then println(s"Wrote ${outPath.toAbsolutePath.normalize}, run it with\n  $shown")
            else println(s"Wrote ${outPath.toAbsolutePath.normalize}")
        0
      case "compile" =>
        compileOnly(expanded, extraClasspath, options, extraCompileOnlyClasspath, !o.noIncremental)
        0

  /** Polls source mtimes every 500ms and reruns `attempt` on change --
   *  simple and portable (no reliance on java.nio.file.WatchService, whose
   *  support in this toolchain's javalib port is unverified). Runs once
   *  immediately, same as scala-cli's `-w`. */
  def watchLoop(sources: List[Path])(attempt: () => Unit): Unit =
    def mtimes(): Map[Path, Long] =
      sources.filter(Files.exists(_)).map(p => p -> Files.getLastModifiedTime(p).toMillis).toMap
    // Same wording scala-cli prints after every iteration, success or failure.
    def watching(): Unit =
      System.err.println(Color.gray("Watching sources, press Ctrl+C to exit, or press Enter to re-run."))
    // Enter on stdin forces a rebuild (scala-cli's behavior); swallow whatever
    // was typed. A closed/non-interactive stdin just never reports bytes.
    def enterPressed(): Boolean =
      try
        val n = System.in.available()
        if n > 0 then { System.in.read(new Array[Byte](n)); true } else false
      catch case _: java.io.IOException => false
    attempt()
    watching()
    var last = mtimes()
    while true do
      Thread.sleep(500)
      val cur = mtimes()
      val enter = enterPressed()
      if cur != last || enter then
        last = cur
        attempt()
        watching()

  def handleRunOrCompile(mode: String, args: Array[String]): Unit =
    val o = defaultToCwd(parseRunOpts(args))
    if mode == "compile" && o.out.isDefined then
      die("compile: -o/--output is not valid here -- use 'scalino package -o' to produce a binary")
    // Fail before compiling if the output path can't possibly work.
    if mode == "package" then
      val packagesOnly = o.formats.nonEmpty && o.formats != List("binary")
      o.out.map(Paths.get(_)).foreach { out =>
        if packagesOnly then
          if Files.exists(out) && !Files.isDirectory(out) then die(s"output path '$out' is not a directory")
        else if Files.isDirectory(out) then die(s"output path '$out' is a directory")
      }
    o.sources.find(!Files.exists(_)).foreach(p => fileNotFound(p))
    val allExpanded = expandSources(o.sources)
    if allExpanded.isEmpty then die("no .scala files found")
    val (expanded, testSources) = partitionSources(allExpanded)

    if o.watch then
      val watchPaths = expanded ++ expandWatchPaths(o.cliWatchingPaths.map(Paths.get(_)))
      watchLoop(watchPaths) { () =>
        try buildAndMaybeRun(mode, expanded, o)
        catch case BuildFailed(msg) => reportBuildFailure(msg)
      }
    else
      try sys.exit(buildAndMaybeRun(mode, expanded, o))
      catch case BuildFailed(msg) => exitBuildFailure(msg)

  /** `scalino test` -- see the design note above `readAllBytes`/`ClassInfo` for
   *  the overall approach (structural framework discovery, a probe binary
   *  for real fingerprint data, structural test discovery, generated
   *  driver). Unlike `run`/`compile`, compiles main *and* test scope
   *  together (test sources depend on main scope) in one pass, so it
   *  doesn't call `partitionSources` at all. */
  def handleTest(args: Array[String]): Unit =
    val o = defaultToCwd(parseRunOpts(args))
    o.sources.find(!Files.exists(_)).foreach(p => fileNotFound(p))
    val expanded = expandSources(o.sources)
    if expanded.isEmpty then die("no .scala files found")

    def attempt(): Int =
      val directives = parseDirectives(expanded)
      directives.scalaVersion.orElse(o.cliScala).foreach { v =>
        if !BuildInfo.scalaVersion.startsWith(v) then
          System.err.println(
            s"scalino: warning: scala \"$v\" requested, but this toolchain only supports ${BuildInfo.scalaVersion} -- ignoring"
          )
      }
      val repos = (directives.repositories ++ o.cliRepositories).distinct
      val depsCache = Paths.get(".scalino-build").resolve("deps-cache")
      val mainClasspath = resolveDeps((directives.deps ++ o.cliDeps).distinct, depsCache, repos)
      val testOnlyClasspath = resolveDeps(directives.testDeps, depsCache, repos)
      val extraCompileOnlyClasspath = resolveDeps((directives.compileOnlyDeps ++ o.cliCompileOnlyDeps).distinct, depsCache, repos)
      val testClasspath = (List(mainClasspath, testOnlyClasspath) ++ directives.jars ++ directives.resourceDirs).filter(_.nonEmpty).mkString(CP_SEP)
      val options = directives.options ++ o.cliOptions ++ directives.testOptions
      val cc = computeCompileClasspath(testClasspath, extraCompileOnlyClasspath)

      // Pass 1: compile the user's own sources only, to scan the result for
      // test discovery before the driver (which references those classes
      // by name) is even generated.
      val classesDir = Paths.get(".scalino-build", "_scratch", "test-classes")
      compileToClasses(expanded, classesDir, cc, options, !o.noIncremental)

      val testJars = testClasspath.split(CP_SEP).filter(_.nonEmpty).toList
      val explicitFramework = o.testFrameworkOpt.orElse(directives.testFramework)
      val frameworkFqcns = explicitFramework match
        case Some(fqcn) => List(fqcn)
        case None =>
          val cacheDir = Paths.get(".scalino-build").resolve("test-framework-cache")
          Files.createDirectories(cacheDir)
          val key = hashKey(testClasspath)
          val cacheFile = cacheDir.resolve(s"fw-$key.txt")
          if Files.exists(cacheFile) then readFile(cacheFile).linesIterator.filter(_.nonEmpty).toList
          else
            val fws = findFrameworkClasses(testJars)
            Files.write(cacheFile, fws.mkString("\n").getBytes("UTF-8"))
            fws
      if frameworkFqcns.isEmpty then
        fail("no test framework found on the classpath -- add a `//> using test.dep \"org::name:version\"` for " +
          "a framework with a scala-native port (e.g. munit, utest, scalatest, zio-test-sbt), or pass --test-framework <class>")

      val probeCacheDir = Paths.get(".scalino-build").resolve("test-probe-cache")
      val specs = probeFingerprints(frameworkFqcns, testClasspath, cc, classesDir, probeCacheDir, o.logLevel)
      if specs.isEmpty then
        fail(s"${frameworkFqcns.mkString(", ")}: no SubclassFingerprint reported -- annotation-based " +
          "(JUnit4-style) test discovery isn't supported")

      val discovered = discoverTestClasses(classesDir, specs, testJars)
      val matches = o.testOnly match
        case Some(glob) =>
          val re = globToRegex(glob)
          discovered.filter(m => re.matches(m.className))
        case None => discovered
      // A selected test's own ancestors must stay linkable even when they are
      // themselves discovered test classes (e.g. a concrete base suite): the
      // linker can only keep what's still on its classpath.
      val keep = scala.collection.mutable.Set.empty[String]
      def collectAncestors(name: String): Unit =
        if keep.add(name) then
          findClassInfo(name, classesDir, testJars).foreach { info =>
            info.superClass.foreach(collectAncestors)
            info.interfaces.foreach(collectAncestors)
          }
      matches.foreach(m => collectAncestors(if m.isModule then m.className + "$" else m.className))
      val excluded = discovered.filterNot(matches.contains).map(_.className).filterNot(keep).toSet
      if matches.isEmpty then
        if !o.quiet then
          val why = if discovered.isEmpty then "no tests found" else s"no test class matches --test-only ${o.testOnly.get}"
          println(Color.action(s"scalino: $why", System.out))
        if discovered.isEmpty || o.testOnly.isEmpty then 0 else 1
      else
        val driverSrc = Paths.get(".scalino-build", "_scratch", "ScalinoCliTestMain.scala")
        Files.write(driverSrc, generateTestMain(matches).getBytes("UTF-8"))
        val binPath = Paths.get(".scalino-build", "ScalinoCliTestMain", "bin")
        def build(exclude: Set[String]) =
          buildBinary(expanded :+ driverSrc, Some("ScalinoCliTestMain"), testClasspath, _ => binPath, options, extraCompileOnlyClasspath, o.logLevel, !o.noIncremental, resolveNativeOpts(directives, o), o.watch, exclude)
        // Besides inheritance (handled above), a selected test could reference
        // an excluded test class some other way -- if the trimmed link fails,
        // fall back to the full link rather than failing the run.
        try build(excluded)
        catch case BuildFailed(_) if excluded.nonEmpty =>
          System.err.println("scalino: warning: link with --test-only exclusions failed (a selected test references an excluded test class?) -- retrying with every test class linked")
          build(Set.empty)
        runInherited(binPath.toString :: o.progArgs)

    if o.watch then
      watchLoop(expanded) { () =>
        try attempt()
        catch case BuildFailed(msg) => reportBuildFailure(msg)
      }
    else
      try sys.exit(attempt())
      catch case BuildFailed(msg) => exitBuildFailure(msg)

  /** JSON string/array literals for `.scalino-build/scalino-lsp.json` -- hand-rolled rather
   *  than pulling in a JSON library: the shape is fixed (see ProjectConfig
   *  below) and every value here is either a plain path string or a flag
   *  list, so escaping quotes/backslashes is all that's needed. */
  def jsonStr(s: String): String = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
  def jsonArr(xs: List[String]): String = xs.map(jsonStr).mkString("[", ", ", "]")

  /** `scalino setup-ide` -- same command name as scala-cli's own `setup-ide`,
   *  but writes `.scalino-build/scalino-lsp.json` (dotty's pre-Metals IDE config format --
   *  `ProjectConfig.java`, read by dist/scalino-lsp on `initialize`,
   *  see DottyLanguageServer.IDE_CONFIG_FILE) instead of scala-cli's BSP
   *  connection file: this toolchain's LSP speaks that format directly, no
   *  BSP layer in between. Mirrors buildBinary's own compile flags/
   *  classpath exactly (via computeCompileClasspath), so the IDE
   *  type-checks against the same inputs a real build would use. */
  def handleSetupIde(args: Array[String]): Unit =
    val o = defaultToCwd(parseRunOpts(args))
    o.sources.find(!Files.exists(_)).foreach(p => fileNotFound(p))
    val allExpanded = expandSources(o.sources)
    if allExpanded.isEmpty then die("no .scala files found")
    val (expanded, testSources) = partitionSources(allExpanded)
    if expanded.isEmpty then die("no main-scope .scala files found (only test sources under test/)")

    val directives = parseDirectives(expanded)
    val allDeps = (directives.deps ++ o.cliDeps).distinct
    val repos = (directives.repositories ++ o.cliRepositories).distinct
    val depsCache = Paths.get(".scalino-build").resolve("deps-cache")
    val extraClasspath = (List(resolveDeps(allDeps, depsCache, repos)) ++ directives.jars ++ directives.resourceDirs).filter(_.nonEmpty).mkString(CP_SEP)
    val compileOnlyDeps = (directives.compileOnlyDeps ++ o.cliCompileOnlyDeps).distinct
    val extraCompileOnlyClasspath = resolveDeps(compileOnlyDeps, depsCache, repos)
    fetchSourcesBestEffort((allDeps ++ compileOnlyDeps).distinct, depsCache, repos)
    fetchStdlibSourcesBestEffort()
    val options = directives.options ++ o.cliOptions

    val cc = computeCompileClasspath(extraClasspath, extraCompileOnlyClasspath)
    // Unlike buildBinary, deliberately drop -Xplugin/-Xplugin-require:scalanative here:
    // InteractiveCompiler (vendor/scala3 dotc.interactive) hardcodes its own phases
    // list (Parser/TyperPhase/SetRootTree/CookComments only, no backend/scalanative
    // phase ever runs), and scalino-lsp's native-image build never bakes in
    // nscplugin's classes -- so requiring the plugin here always fails to load it
    // reflectively and every request after `initialize` (which needs a driver built
    // from these arguments) times out, regardless of native-image or not.
    val compilerArguments =
      List("-javabootclasspath", cc.javaBase, "-Yretain-trees") ++ options

    // Unlike buildBinary/run (main-scope only), the IDE config also covers
    // testSources -- an editor should get diagnostics/completion in test/ code too,
    // even though `partitionSources` keeps it out of the actual build.
    val sourceDirectories = (expanded ++ testSources).map(_.toAbsolutePath.getParent.toString).distinct.sorted
    val dependencyClasspath = cc.compileCp.split(CP_SEP).filter(_.nonEmpty).toList

    val json =
      s"""[
         |  {
         |    "compilerArguments": ${jsonArr(compilerArguments)},
         |    "sourceDirectories": ${jsonArr(sourceDirectories)},
         |    "dependencyClasspath": ${jsonArr(dependencyClasspath)}
         |  }
         |]
         |""".stripMargin

    // Lives inside .scalino-build/ (patches/scala3-0015 moves scalino-lsp's
    // own DottyLanguageServer.IDE_CONFIG_FILE to match) rather than at the
    // project root as dotty's original ".dotty-ide.json" did, so a project's
    // .gitignore only needs one entry (.scalino-build/) to cover it, not a
    // second one just for this file.
    val configPath = Paths.get(".scalino-build", "scalino-lsp.json")
    Files.write(configPath, json.getBytes("UTF-8"))
    println(s"Wrote configuration file for ide in: ${configPath.toAbsolutePath}")

    // No editor-specific settings.json is written here anymore: install.sh
    // symlinks scalino-lsp onto PATH alongside scalino, and both editor
    // extensions already fall back to a PATH lookup when no explicit binary
    // path is configured (vscode-extension/src/extension.ts's findOnPath,
    // zed-extension/src/lib.rs's worktree.which) -- so a project checkout
    // needs zero editor-specific config for the common case. Zed users with
    // metals-zed also installed still need the `file_types` override
    // documented in zed-extension/README.md to avoid Zed arbitrarily
    // picking metals-zed's "Scala" language for .scala files -- that's a
    // per-project editor preference, not something this command should
    // guess at or overwrite.

  // ---------------------------------------------------------------------
  // `scalino completions <bash|zsh|fish>` -- static, hand-written scripts
  // (no completion-generation library on this toolchain, and the command
  // surface is small/stable enough not to need one) covering subcommand
  // names, every long/short option `parseRunOpts`/`main` actually accept,
  // and the handful of options with a closed value set (`--color`,
  // `--native-mode`, `--native-gc`, `--native-lto`, `--native-target`,
  // `--native-target-triple` -- completed from `Sysroot.Supported`, the same
  // list `scalino sysroot build` accepts) plus `sysroot`'s own sub-arguments.
  // Same convention as cargo/rustup/scala-cli's own `completions` command:
  // prints the script to stdout, for the user to source or install
  // themselves (e.g. `scalino completions bash > /etc/bash_completion.d/scalino`).
  // ---------------------------------------------------------------------

  private val completionSubcommands = "run compile package test setup-ide lock clean sysroot version completions"
  private val completionSysrootActions = "build list path"
  private def completionTriples: String = Sysroot.Supported.mkString(" ")
  private val completionOptions =
    "--main-class --dep --dependency --compile-dep --compile-only-dependency " +
    "-r --repo --repository -S --scala --scala-version -O --scalac-option --scalac-opt " +
    "-w --watch --watching --watching-path --args-file -o --output --format --pkg-name --pkg-version --release-url -v --verbose -q --quiet " +
    "--color --test-framework --test-only --no-incremental --offline --native-mode --native-gc --native-lto " +
    "--native-target-triple --native-sysroot --native-clang --native-clangpp --native-linking --native-compile --native-c-compile " +
    "--native-cpp-compile --native-prune --native-target --embed-resources --native-multithreading " +
    "--native-direct-codegen --native-compact-headers --native-compact-byte-arrays --native-gc-stw-sweep --native-heap-histogram --native-optimize " +
    "--formats --package-name --package-version -h --help"

  private def bashCompletion: String =
    s"""_scalino() {
       |  local cur prev
       |  cur="$${COMP_WORDS[COMP_CWORD]}"
       |  prev="$${COMP_WORDS[COMP_CWORD-1]}"
       |  local subcommands="$completionSubcommands"
       |  local options="$completionOptions"
       |
       |  if [[ "$${COMP_WORDS[1]}" == sysroot ]]; then
       |    case $$COMP_CWORD in
       |      2) COMPREPLY=($$(compgen -W "$completionSysrootActions" -- "$$cur")); return ;;
       |      *)
       |        case "$${COMP_WORDS[2]}" in
       |          build) COMPREPLY=($$(compgen -W "$completionTriples --force" -- "$$cur")); return ;;
       |          path) COMPREPLY=($$(compgen -W "$completionTriples" -- "$$cur")); return ;;
       |        esac
       |        return ;;
       |    esac
       |  fi
       |
       |  case "$$prev" in
       |    --color) COMPREPLY=($$(compgen -W "always auto never" -- "$$cur")); return ;;
       |    --native-mode) COMPREPLY=($$(compgen -W "debug release-fast release-size release-full" -- "$$cur")); return ;;
       |    --native-gc) COMPREPLY=($$(compgen -W "immix commix boehm none" -- "$$cur")); return ;;
       |    --native-lto) COMPREPLY=($$(compgen -W "none thin full" -- "$$cur")); return ;;
       |    --native-target) COMPREPLY=($$(compgen -W "app static dynamic" -- "$$cur")); return ;;
       |    --native-target-triple) COMPREPLY=($$(compgen -W "$completionTriples" -- "$$cur")); return ;;
       |    --format|--formats) COMPREPLY=($$(compgen -W "binary tar deb rpm docker brew" -- "$$cur")); return ;;
       |    --pkg-name|--pkg-version|--package-name|--package-version|--release-url) return ;;
       |    completions) COMPREPLY=($$(compgen -W "bash zsh fish" -- "$$cur")); return ;;
       |    --main-class|--dep|--dependency|--compile-dep|--compile-only-dependency|-r|--repo|--repository| \\
       |    -S|--scala|--scala-version|-O|--scalac-option|--scalac-opt|--watching|--watching-path| \\
       |    --args-file|-o|--output|--test-framework|--test-only|--native-sysroot|--native-clang|--native-clangpp|--native-linking| \\
       |    --native-compile|--native-c-compile|--native-cpp-compile|--native-prune)
       |      COMPREPLY=($$(compgen -f -- "$$cur")); return ;;
       |  esac
       |
       |  if [[ "$$cur" == -* ]]; then
       |    COMPREPLY=($$(compgen -W "$$options" -- "$$cur"))
       |  elif [[ $$COMP_CWORD -eq 1 ]]; then
       |    COMPREPLY=($$(compgen -W "$$subcommands" -- "$$cur") $$(compgen -f -- "$$cur"))
       |  else
       |    COMPREPLY=($$(compgen -f -- "$$cur"))
       |  fi
       |}
       |complete -F _scalino scalino
       |""".stripMargin

  private def zshCompletion: String =
    val subQuoted = completionSubcommands.split(" ").map(s => s"'$s'").mkString(" ")
    val optQuoted = completionOptions.split(" ").map(s => s"'$s'").mkString(" ")
    s"""#compdef scalino
       |
       |_scalino() {
       |  local -a subcommands options
       |  subcommands=($subQuoted)
       |  options=($optQuoted)
       |
       |  if [[ "$${words[2]}" == sysroot ]]; then
       |    case $$CURRENT in
       |      3) _values 'action' $completionSysrootActions; return ;;
       |      *)
       |        case "$${words[3]}" in
       |          build) _values 'target triple' $completionTriples --force; return ;;
       |          path) _values 'target triple' $completionTriples; return ;;
       |        esac
       |        return ;;
       |    esac
       |  fi
       |
       |  case "$${words[CURRENT-1]}" in
       |    --color) _values 'color' always auto never; return ;;
       |    --native-mode) _values 'mode' debug release-fast release-size release-full; return ;;
       |    --native-gc) _values 'gc' immix commix boehm none; return ;;
       |    --native-lto) _values 'lto' none thin full; return ;;
       |    --native-target) _values 'target' app static dynamic; return ;;
       |    --native-target-triple) _values 'target triple' $completionTriples; return ;;
       |    --format|--formats) _values 'format' binary tar deb rpm docker brew; return ;;
       |    --pkg-name|--pkg-version|--package-name|--package-version|--release-url) return ;;
       |    completions) _values 'shell' bash zsh fish; return ;;
       |    --main-class|--dep|--dependency|--compile-dep|--compile-only-dependency|-r|--repo|--repository|\\
       |    -S|--scala|--scala-version|-O|--scalac-option|--scalac-opt|--watching|--watching-path|\\
       |    --args-file|-o|--output|--test-framework|--test-only|--native-sysroot|--native-clang|--native-clangpp|--native-linking|\\
       |    --native-compile|--native-c-compile|--native-cpp-compile|--native-prune)
       |      _files; return ;;
       |  esac
       |
       |  if [[ "$$words[CURRENT]" == -* ]]; then
       |    _describe 'option' options
       |  else
       |    _alternative 'subcommands:subcommand:(($$subcommands))' 'files:file:_files'
       |  fi
       |}
       |
       |_scalino "$$@"
       |""".stripMargin

  private def fishCompletion: String =
    val subFish = completionSubcommands.split(" ")
      .map(s => s"complete -c scalino -n '__fish_use_subcommand' -f -a $s")
      .mkString("\n")
    val optFish = completionOptions.split(" ")
      .filter(_.startsWith("--"))
      .map(o => s"complete -c scalino -l ${o.stripPrefix("--")}")
      .mkString("\n")
    s"""function __fish_use_subcommand
       |    set -l cmd (commandline -opc)
       |    test (count $$cmd) -eq 1
       |end
       |
       |$subFish
       |
       |$optFish
       |
       |complete -c scalino -l color -x -a "always auto never"
       |complete -c scalino -l native-mode -x -a "debug release-fast release-size release-full"
       |complete -c scalino -l native-gc -x -a "immix commix boehm none"
       |complete -c scalino -l native-lto -x -a "none thin full"
       |complete -c scalino -l native-target -x -a "app static dynamic"
       |complete -c scalino -l native-target-triple -x -a "$completionTriples"
       |complete -c scalino -l format -x -a "binary tar deb rpm docker brew"
       |complete -c scalino -l formats -x -a "binary tar deb rpm docker brew"
       |complete -c scalino -l pkg-name -x
       |complete -c scalino -l pkg-version -x
       |complete -c scalino -l package-name -x
       |complete -c scalino -l package-version -x
       |complete -c scalino -l release-url -x
       |complete -c scalino -n '__fish_seen_subcommand_from sysroot; and not __fish_seen_subcommand_from $completionSysrootActions' -f -a "$completionSysrootActions"
       |complete -c scalino -n '__fish_seen_subcommand_from sysroot; and __fish_seen_subcommand_from build path' -f -a "$completionTriples"
       |complete -c scalino -n '__fish_seen_subcommand_from sysroot; and __fish_seen_subcommand_from build' -l force
       |complete -c scalino -n '__fish_seen_subcommand_from completions' -f -a "bash zsh fish"
       |""".stripMargin

  def handleCompletions(args: Array[String]): Unit =
    if args.length != 1 then die("completions: expected exactly one shell argument: bash, zsh, or fish")
    args(0) match
      case "bash" => print(bashCompletion)
      case "zsh" => print(zshCompletion)
      case "fish" => print(fishCompletion)
      case other => die(s"completions: unsupported shell '$other' -- expected bash, zsh, or fish")

  def handleClean(args: Array[String]): Unit =
    if args.nonEmpty then die(s"clean: unexpected argument '${args(0)}'")
    val buildDir = Paths.get(".scalino-build")
    if Files.exists(buildDir) then
      deleteRecursively(buildDir)

  // Boolean flags accepted before the sub-command too (`scalino -w compile`),
  // mill/bun-style: they're hoisted to just after it. Flags that take a value
  // aren't hoisted -- can't tell their value from the sub-command position.
  private val HoistableFlags = Set("-w", "--watch", "-v", "--verbose", "-q", "--quiet", "--no-incremental", "--offline")
  private val HoistTargets = Set("run", "compile", "package", "test", "setup-ide", "lock")

  def hoistLeadingFlags(args: Array[String]): Array[String] =
    val lead = args.takeWhile(HoistableFlags.contains)
    val rest = args.drop(lead.length)
    if lead.nonEmpty && rest.nonEmpty && HoistTargets.contains(rest(0)) then rest(0) +: (lead ++ rest.drop(1))
    else args

  /** Last-resort handler: an exception nothing else caught still has to say
   *  what it was. Every step is guarded -- the runtime's own uncaught-exception
   *  path reports only the class name when printing the exception itself throws. */
  def main(rawArgs: Array[String]): Unit =
    try run(rawArgs)
    catch case e: Throwable =>
      def safe[A](a: => A, default: A): A = try a catch case _: Throwable => default
      val msg = safe(e.getMessage, null)
      val what = if msg != null then s"${e.getClass.getName}: $msg" else e.getClass.getName
      System.err.println(Color.error(s"scalino: unexpected error: $what"))
      safe(e.printStackTrace(), ())
      sys.exit(1)

  def run(rawArgs: Array[String]): Unit =
    val args = hoistLeadingFlags(extractColorFlag(rawArgs))
    if args.isEmpty then { printUsage(System.err); sys.exit(1) }
    // `scalino <command> --help`: per-command help. Stops at `--` (program/
    // framework args) so `scalino run . -- --help` still reaches the program.
    if HelpCommands.contains(args(0)) && args.drop(1).takeWhile(_ != "--").exists(a => a == "-h" || a == "--help") then
      printCommandHelp(args(0), System.out)
      return
    args(0) match
      case "-h" | "--help" => printUsage(System.out)
      case "--version" | "version" => printVersion()
      case "run" => handleRunOrCompile("run", args.drop(1))
      // `compile` only compiles (like real scala-cli's `compile .`): no link
      // step, no native binary, no -o requirement. `package` is the one that
      // links a native binary and requires -o.
      case "compile" => handleRunOrCompile("compile", args.drop(1))
      case "package" => handleRunOrCompile("package", args.drop(1))
      case "test" => handleTest(args.drop(1))
      case "setup-ide" => handleSetupIde(args.drop(1))
      case "completions" => handleCompletions(args.drop(1))
      case "clean" => handleClean(args.drop(1))
      case "sysroot" => Sysroot.handle(args.drop(1))
      case "lock" => handleLock(args.drop(1))
      case first if first.startsWith("-") || Files.exists(Paths.get(first)) =>
        handleRunOrCompile("run", args) // implicit `run`, e.g. `scalino Foo.scala`
      case other =>
        die(s"unknown command or file '$other' -- run 'scalino --help'")
