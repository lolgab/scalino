// Code coverage: `scalino test --coverage` (and `run --coverage`) builds the program with the Scala 3
// compiler's own instrumentation (`-coverage-out`, the scoverage data format), runs it, and turns the
// recorded statement ids into reports: a terminal table, HTML, LCOV, Cobertura XML, scoverage XML and
// JSON. `--coverage-fail-under` (or `//> using coverage.failUnder`) makes the command fail below a
// threshold, as `coverage report --fail-under` and `cargo llvm-cov --fail-under-lines` do.
//
// scala-cli only passes `-coverage-out` through and leaves the reporting to sbt-scoverage's tools; the
// command-line shape here follows cargo-llvm-cov, coverage.py and the JavaScript test runners instead.
//
// Build state: instrumented classes go to their own directories (`classes-coverage` next to
// `classes`, for the root and for every `dependsOn` module), so a coverage run never invalidates the
// plain incremental build. All of them record into one data directory, `.scalino-build/coverage-data`:
// the compiler keeps `scoverage.coverage` (the statement map) up to date across incremental compiles
// itself, replacing the statements of every file it recompiles. The statement ids baked into the
// classes are only meaningful with that file, so when it is missing the instrumented classes are
// thrown away and rebuilt.
//
// What is measured: main-scope sources of the root and of its modules. Test sources (`isTestSource`),
// `//> using coverage.exclude <glob>` files and `// $COVERAGE-OFF$` ... `// $COVERAGE-ON$` regions
// (handled by the compiler) are not instrumented.

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path, Paths}
import scala.collection.mutable
import scala.jdk.CollectionConverters.*
import ScalinoCli.{BuildFailed, Color, die, fail, readFile}

object Coverage:

  val Formats: List[String] = List("html", "lcov", "cobertura", "scoverage", "json")

  // ---- options ----------------------------------------------------------------------------------

  /** What the command line asked for. Every `--coverage-*` flag implies `--coverage`. */
  final case class Flags(
    enabled: Boolean = false,
    formats: List[String] = Nil,
    outDir: Option[String] = None,
    failUnder: Option[Double] = None,
    failUnderBranches: Option[Double] = None,
    failUnderFile: Option[Double] = None
  )

  /** `//> using coverage.<key>` settings. */
  final case class Settings(
    formats: List[String] = Nil,
    outDir: Option[String] = None,
    failUnder: Option[Double] = None,
    failUnderBranches: Option[Double] = None,
    failUnderFile: Option[Double] = None,
    exclude: List[String] = Nil,
    excludeClasses: List[String] = Nil
  )

  /** Flags over directives. The table is always printed; `formats` are written to `outDir`. */
  final case class Config(
    formats: List[String],
    outDir: Path,
    failUnder: Option[Double],
    failUnderBranches: Option[Double],
    failUnderFile: Option[Double],
    exclude: List[String],
    excludeClasses: List[String]
  )

  def config(f: Flags, s: Settings): Config =
    Config(
      formats = (if f.formats.nonEmpty then f.formats else s.formats).distinct,
      outDir = Paths.get(f.outDir.orElse(s.outDir).getOrElse("coverage")),
      failUnder = f.failUnder.orElse(s.failUnder),
      failUnderBranches = f.failUnderBranches.orElse(s.failUnderBranches),
      failUnderFile = f.failUnderFile.orElse(s.failUnderFile),
      exclude = s.exclude,
      excludeClasses = s.excludeClasses
    )

  private def parseFormats(v: String): List[String] =
    val fs = v.split(",").map(_.trim.toLowerCase).filter(_.nonEmpty).toList
    fs.find(f => !Formats.contains(f)).foreach(bad =>
      die(s"unknown coverage report format '$bad' -- expected one of ${Formats.mkString(", ")}"))
    fs

  private def parsePercent(flag: String, v: String): Double =
    val d = v.stripSuffix("%").toDoubleOption.getOrElse(die(s"$flag $v: expected a percentage, e.g. 80"))
    if d < 0 || d > 100 then die(s"$flag $v: expected a percentage between 0 and 100")
    d

  private val ValueFlags = Set("--coverage-report", "--coverage-dir", "--coverage-fail-under",
    "--coverage-fail-under-branches", "--coverage-fail-under-file")

  def isFlag(a: String): Boolean =
    a == "--coverage" || ValueFlags.exists(f => a == f || a.startsWith(f + "="))

  /** Parses the flag at `args(i)`; returns the updated flags and how many extra arguments it used. */
  def parseFlag(args: Array[String], i: Int, f: Flags): (Flags, Int) =
    val a = args(i)
    if a == "--coverage" then (f.copy(enabled = true), 0)
    else
      val eq = a.indexOf('=')
      val (name, value, used) =
        if eq > 0 then (a.substring(0, eq), a.substring(eq + 1), 0)
        else if i + 1 < args.length then (a, args(i + 1), 1)
        else die(s"$a: missing value")
      val g = f.copy(enabled = true)
      val updated = name match
        case "--coverage-report" => g.copy(formats = (g.formats ++ parseFormats(value)).distinct)
        case "--coverage-dir" => g.copy(outDir = Some(value))
        case "--coverage-fail-under" => g.copy(failUnder = Some(parsePercent(name, value)))
        case "--coverage-fail-under-branches" => g.copy(failUnderBranches = Some(parsePercent(name, value)))
        case "--coverage-fail-under-file" => g.copy(failUnderFile = Some(parsePercent(name, value)))
      (updated, used)

  private val DirectiveKeys = Set("report", "dir", "failUnder", "failUnderBranches", "failUnderFile", "exclude", "excludeClass")

  def isKnownDirective(key: String): Boolean =
    key.startsWith("coverage.") && DirectiveKeys(key.stripPrefix("coverage."))

  private val coverageRe = """//>\s*using\s+coverage\.(\S+)(.*)$""".r
  private val quotedRe = """"([^"]*)"""".r

  def parseDirectiveSettings(sources: List[Path]): Settings =
    var s = Settings()
    for src <- sources; raw <- readFile(src).linesIterator do
      val line = raw.trim
      if line.startsWith("//>") then
        coverageRe.findFirstMatchIn(line).foreach { m =>
          val rest = m.group(2)
          val quoted = quotedRe.findAllMatchIn(rest).map(_.group(1)).toList
          val values = if quoted.nonEmpty then quoted else rest.trim.split("""\s*,\s*|\s+""").toList.filter(_.nonEmpty)
          def one = values.lastOption
          def pct(key: String) = one.map(v => parsePercent(s"//> using coverage.$key", v))
          m.group(1) match
            case "report" => s = s.copy(formats = (s.formats ++ values.flatMap(parseFormats)).distinct)
            case "dir" => s = s.copy(outDir = one.map(v => ScalinoCli.directivePath(src, v).toString))
            case k @ "failUnder" => s = s.copy(failUnder = pct(k))
            case k @ "failUnderBranches" => s = s.copy(failUnderBranches = pct(k))
            case k @ "failUnderFile" => s = s.copy(failUnderFile = pct(k))
            case "exclude" => s = s.copy(exclude = s.exclude ++ values)
            case "excludeClass" => s = s.copy(excludeClasses = s.excludeClasses ++ values)
            case _ => // reported by parseDirectives
        }
    s

  // ---- build ------------------------------------------------------------------------------------

  private def buildDir: Path = Paths.get(".scalino-build").toAbsolutePath.normalize
  def dataDir: Path = buildDir.resolve("coverage-data")
  private def coverageFile: Path = dataDir.resolve("scoverage.coverage")
  private val MeasurementsPrefix = "scoverage.measurements."

  /** Set for the duration of an instrumented build; read by `classesDirName` and `Modules.compileAll`. */
  @volatile var active: Option[Config] = None
  /** Every source compiled with instrumentation in this build (absolute, normalized). */
  private val instrumented = mutable.LinkedHashSet.empty[Path]
  private val excludedSources = mutable.Set.empty[Path]

  /** `classes` -> `classes-coverage` while instrumenting. */
  def classesDirName(base: String): String = if active.isDefined then s"$base-coverage" else base

  /** Starts an instrumented build: drops instrumented classes whose statement map is gone, and the
   *  measurements of the previous run. */
  def begin(cfg: Config): Unit =
    active = Some(cfg)
    instrumented.clear()
    excludedSources.clear()
    if !Files.exists(coverageFile) then
      val stale = List(buildDir.resolve("_scratch").resolve("classes-coverage")) ++
        Option(buildDir.resolve("modules")).filter(Files.isDirectory(_)).toList.flatMap { modules =>
          val s = Files.list(modules)
          try s.iterator.asScala.map(_.resolve("classes-coverage")).toList finally s.close()
        }
      stale.filter(Files.exists(_)).foreach(ScalinoCli.deleteRecursively)
    clearMeasurements()

  def end(): Unit = active = None

  def clearMeasurements(): Unit =
    if Files.isDirectory(dataDir) then
      val s = Files.list(dataDir)
      try s.iterator.asScala.filter(_.getFileName.toString.startsWith(MeasurementsPrefix)).toList.foreach(Files.deleteIfExists)
      finally s.close()

  private def cwd: Path = Paths.get("").toAbsolutePath.normalize

  private def relToCwd(p: Path): String =
    val abs = p.toAbsolutePath.normalize
    // `../core/Core.scala` for a `dependsOn` module beside the project; absolute on another drive.
    try cwd.relativize(abs).toString
    catch case _: IllegalArgumentException => abs.toString

  /** The scalac options that instrument `sources` (minus `notMeasured`, e.g. test sources compiled in
   *  the same run), or Nil when no instrumented build is in progress. */
  def optionsFor(sources: List[Path], notMeasured: List[Path] = Nil): List[String] =
    active match
      case None => Nil
      case Some(cfg) =>
        val globs = cfg.exclude.map(ScalinoCli.globToRegex)
        val byGlob = sources.filter(p => globs.exists(_.matches(relToCwd(p).replace(java.io.File.separatorChar, '/'))))
        val excluded = (notMeasured ++ byGlob).map(_.toAbsolutePath.normalize).distinct
        excludedSources ++= excluded
        instrumented ++= sources.map(_.toAbsolutePath.normalize).filterNot(excluded.contains)
        // The compiler matches each regex against the whole source path as it was passed (without
        // `.scala`), so allow any directory prefix in front of the path relative to here.
        // Separators are matched either way: the compiler sees the path as the build passed it, which on
        // Windows may use either.
        val fileRegexes = excluded.map { p =>
          val segments = relToCwd(p).stripSuffix(".scala").stripSuffix(".sc").split("""[/\\\\]""").toList
          "(?:.*[/\\\\])?" + segments.map(java.util.regex.Pattern.quote).mkString("[/\\\\]")
        }
        Files.createDirectories(dataDir)
        List(s"-coverage-out:$dataDir", "-sourceroot", cwd.toString) ++
          (if fileRegexes.nonEmpty then List(s"-coverage-exclude-files:${fileRegexes.mkString(",")}") else Nil) ++
          (if cfg.excludeClasses.nonEmpty then List(s"-coverage-exclude-classlikes:${cfg.excludeClasses.mkString(",")}") else Nil)

  /** Scala.js: the coverage runtime in the Scala.js library is the JVM one, which writes its
   *  measurements with java.io and doesn't link. This replacement is compiled with the program (its
   *  classes come first on the linker's class path, so they win) and appends to the same measurement
   *  files through the JavaScript runtime's `fs` (node, bun) or `Deno`. */
  def jsRuntimeSource(): Path =
    val p = buildDir.resolve("_scratch").resolve("coverage-js").resolve("ScalinoCoverageInvoker.scala")
    val bytes = JsInvoker.getBytes(UTF_8)
    // Rewritten only when it changed, so the incremental compile leaves it alone.
    if !Files.exists(p) || !java.util.Arrays.equals(Files.readAllBytes(p), bytes) then
      Files.createDirectories(p.getParent)
      Files.write(p, bytes)
    p

  private val JsInvoker: String =
    """|// $COVERAGE-OFF$
       |// Generated by scalino (cli/Coverage.scala): the Scala.js coverage runtime.
       |package scala.runtime.coverage
       |
       |import scala.scalajs.js
       |import scala.scalajs.js.Dynamic.{global => g}
       |
       |object Invoker {
       |  private val MeasurementsPrefix = "scoverage.measurements."
       |  // One file per run: a JavaScript program has one thread.
       |  private val runId: String =
       |    java.lang.Long.toHexString(js.Date.now().toLong) + "-" + Integer.toHexString((js.Math.random() * 0x7fffffff).toInt)
       |  private val seen = scala.collection.mutable.Map.empty[String, scala.collection.mutable.BitSet]
       |  private var append: js.Function2[String, String, Unit] = null
       |
       |  private def appender(): js.Function2[String, String, Unit] = {
       |    if (append == null) {
       |      val fs: js.Dynamic =
       |        if (js.typeOf(g.process) == "object" && js.typeOf(g.process.getBuiltinModule) == "function")
       |          g.process.getBuiltinModule("fs")
       |        else if (js.typeOf(g.require) == "function") g.require("fs")
       |        else null
       |      append =
       |        if (fs != null) {
       |          val fds = scala.collection.mutable.Map.empty[String, js.Dynamic]
       |          (file: String, text: String) => {
       |            val fd = fds.getOrElseUpdate(file, fs.openSync(file, "a"))
       |            fs.writeSync(fd, text)
       |            ()
       |          }
       |        } else if (js.typeOf(g.Deno) == "object")
       |          (file: String, text: String) => { g.Deno.writeTextFileSync(file, text, js.Dynamic.literal(append = true)); () }
       |        else {
       |          g.console.error("scalino: coverage: no file system API in this JavaScript runtime, nothing is recorded")
       |          (file: String, text: String) => ()
       |        }
       |    }
       |    append
       |  }
       |
       |  def invoked(id: Int, dataDir: String): Unit = {
       |    val set = seen.getOrElseUpdate(dataDir, scala.collection.mutable.BitSet.empty)
       |    if (set.add(id)) appender()(dataDir + "/" + MeasurementsPrefix + runId + ".1", Integer.toString(id) + "\n")
       |  }
       |}
       |""".stripMargin

  /** The sources the last build instrumented, to keep files that left the build out of the report. */
  def instrumentedSources: Set[Path] = instrumented.toSet

  // ---- data -------------------------------------------------------------------------------------

  final case class Statement(
    id: Int, source: String, pkg: String, cls: String, classType: String, fullClass: String, method: String,
    start: Int, end: Int, line: Int, symbol: String, tree: String, branch: Boolean, ignored: Boolean, desc: String
  )

  private def unescape(s: String): String =
    if s.indexOf('\\') < 0 then s
    else
      val b = new StringBuilder(s.length)
      var i = 0
      while i < s.length do
        val c = s(i)
        if c == '\\' && i + 1 < s.length then
          s(i + 1) match
            case 'n' => b += '\n'
            case 'r' => b += '\r'
            case 'f' => b += '\f'
            case other => b += other
          i += 2
        else { b += c; i += 1 }
      b.toString

  /** `scoverage.coverage`, format 3.0 (dotty.tools.dotc.coverage.Serializer). */
  def readStatements(file: Path): Vector[Statement] =
    val lines = new String(Files.readAllBytes(file), UTF_8).split("\n", -1).iterator.map(_.stripSuffix("\r"))
    if !lines.hasNext then return Vector.empty
    val header = lines.next()
    if header != "# Coverage data, format version: 3.0" then
      fail(s"$file: unsupported coverage data format ('$header')")
    val body = lines.dropWhile(_.startsWith("#")).buffered
    val out = Vector.newBuilder[Statement]
    while body.hasNext do
      val rec = mutable.ArrayBuffer.empty[String]
      while body.hasNext && body.head != "\f" do rec += body.next()
      if body.hasNext then body.next() // the '\f' separator
      if rec.size >= 15 then
        out += Statement(
          id = rec(0).toInt, source = unescape(rec(1)), pkg = unescape(rec(2)), cls = unescape(rec(3)),
          classType = rec(4), fullClass = unescape(rec(5)), method = unescape(rec(6)),
          start = rec(7).toInt, end = rec(8).toInt, line = rec(9).toInt, symbol = unescape(rec(10)),
          tree = rec(11), branch = rec(12).toBoolean, ignored = rec(14).toBoolean,
          desc = unescape(rec.drop(15).mkString("\n")))
    out.result()

  /** The ids recorded by `scala.runtime.coverage.Invoker`: one measurements file per thread (and
   *  process), one id per line. */
  def readInvoked(dir: Path): Set[Int] =
    if !Files.isDirectory(dir) then Set.empty
    else
      val s = Files.list(dir)
      val files = try s.iterator.asScala.filter(_.getFileName.toString.startsWith(MeasurementsPrefix)).toList finally s.close()
      files.flatMap { f =>
        new String(Files.readAllBytes(f), UTF_8).linesIterator.map(_.trim).filter(_.nonEmpty).flatMap(_.toIntOption)
      }.toSet

  // ---- model ------------------------------------------------------------------------------------

  final case class Counts(total: Int, covered: Int):
    def +(o: Counts): Counts = Counts(total + o.total, covered + o.covered)
    def missed: Int = total - covered
    def percent: Double = if total == 0 then 100.0 else covered * 100.0 / total

  /** One line's statements (those starting on it). */
  final case class LineInfo(statements: Counts, branches: Counts):
    def hit: Boolean = statements.covered > 0
    def partial: Boolean = statements.covered > 0 && statements.missed > 0

  final case class FileCoverage(path: Path, display: String, statements: Vector[Statement], invoked: Int => Boolean):
    val measured: Vector[Statement] = statements.filterNot(_.ignored)
    val stmts: Counts = Counts(measured.size, measured.count(s => invoked(s.id)))
    val branches: Counts =
      val bs = measured.filter(_.branch)
      Counts(bs.size, bs.count(s => invoked(s.id)))
    val lines: Map[Int, LineInfo] = measured.groupBy(_.line).view.mapValues { ss =>
      val bs = ss.filter(_.branch)
      LineInfo(Counts(ss.size, ss.count(s => invoked(s.id))), Counts(bs.size, bs.count(s => invoked(s.id))))
    }.toMap
    val lineCounts: Counts = Counts(lines.size, lines.count(_._2.hit))
    def missedLines: List[Int] = lines.filter(!_._2.hit).keys.toList.sorted
    def partialLines: List[Int] = lines.filter(_._2.partial).keys.toList.sorted

  final case class Report(files: Vector[FileCoverage], invoked: Set[Int], root: Path):
    val stmts: Counts = files.map(_.stmts).foldLeft(Counts(0, 0))(_ + _)
    val branches: Counts = files.map(_.branches).foldLeft(Counts(0, 0))(_ + _)
    val lines: Counts = files.map(_.lineCounts).foldLeft(Counts(0, 0))(_ + _)

  /** Loads the data directory; `only` keeps the given sources (the ones the last build instrumented). */
  def load(only: Option[Set[Path]] = None, exclude: List[String] = Nil): Report =
    if !Files.exists(coverageFile) then
      fail(s"no coverage data in ${relToCwd(dataDir)} -- run `scalino test --coverage` first")
    val root = cwd
    val invoked = readInvoked(dataDir)
    val globs = exclude.map(ScalinoCli.globToRegex)
    val files = readStatements(coverageFile).groupBy(_.source).toVector.flatMap { case (src, ss) =>
      val abs = root.resolve(src).normalize
      val display = relToCwd(abs).replace(java.io.File.separatorChar, '/')
      val keep = only.forall(_.contains(abs)) && !globs.exists(_.matches(display)) && Files.exists(abs)
      Option.when(keep)(FileCoverage(abs, display, ss.sortBy(s => (s.start, s.end)), invoked))
    }.sortBy(_.display)
    Report(files, invoked, root)

  // ---- reporting --------------------------------------------------------------------------------

  /** Prints the table, writes the requested report files and checks the thresholds.
   *  Returns false when a threshold failed. */
  def report(cfg: Config, only: Option[Set[Path]], quiet: Boolean = false): Boolean =
    val r = load(only, cfg.exclude)
    printText(r, System.out)
    val written = mutable.ListBuffer.empty[Path]
    val out = cfg.outDir.toAbsolutePath.normalize
    def write(name: String, content: String): Unit =
      Files.createDirectories(out)
      val p = out.resolve(name)
      Files.write(p, content.getBytes(UTF_8))
      written += p
    for f <- cfg.formats do f match
      case "html" => writeHtml(r, out); written += out.resolve("index.html")
      case "lcov" => write("lcov.info", lcov(r))
      case "cobertura" => write("cobertura.xml", cobertura(r))
      case "scoverage" => write("scoverage.xml", scoverageXml(r))
      case "json" => write("coverage.json", json(r))
      case _ =>
    if !quiet then written.foreach(p => println(Color.gray(s"Wrote ${relToCwd(p)}", System.out)))
    checkThresholds(r, cfg)

  private def pct(d: Double): String = f"$d%.1f%%"
  private def pct2(d: Double): String = f"$d%.2f"

  private def checkThresholds(r: Report, cfg: Config): Boolean =
    val failures = mutable.ListBuffer.empty[String]
    cfg.failUnder.foreach { min =>
      if r.stmts.percent < min then failures += s"statement coverage ${pct(r.stmts.percent)} is below ${pct(min)}"
    }
    cfg.failUnderBranches.foreach { min =>
      if r.branches.percent < min then failures += s"branch coverage ${pct(r.branches.percent)} is below ${pct(min)}"
    }
    cfg.failUnderFile.foreach { min =>
      for f <- r.files if f.stmts.percent < min do
        failures += s"${f.display}: statement coverage ${pct(f.stmts.percent)} is below ${pct(min)}"
    }
    failures.foreach(m => System.err.println(Color.error(s"scalino: coverage: $m")))
    failures.isEmpty

  private def ranges(lines: List[Int]): String =
    if lines.isEmpty then ""
    else
      val b = mutable.ListBuffer.empty[String]
      var start = lines.head
      var prev = lines.head
      for l <- lines.tail do
        if l != prev + 1 then
          b += (if start == prev then s"$start" else s"$start-$prev")
          start = l
        prev = l
      b += (if start == prev then s"$start" else s"$start-$prev")
      b.mkString(", ")

  def printText(r: Report, out: java.io.PrintStream): Unit =
    def paint(p: Double, s: String) =
      if p >= 80 then Color.green(s, out) else if p >= 50 then Color.yellow(s, out) else Color.red(s, out)
    val header = List("File", "Stmts", "Miss", "Branch", "BrMiss", "Cover", "Missing")
    val rows = r.files.map { f =>
      (List(f.display, f.stmts.total.toString, f.stmts.missed.toString, f.branches.total.toString,
        f.branches.missed.toString, pct(f.stmts.percent), ranges(f.missedLines)), f.stmts.percent)
    }
    val total = (List("TOTAL", r.stmts.total.toString, r.stmts.missed.toString, r.branches.total.toString,
      r.branches.missed.toString, pct(r.stmts.percent), ""), r.stmts.percent)
    val all = header :: (rows :+ total).map(_._1).toList
    val widths = (0 until 6).map(i => all.map(_(i).length).max)
    def fmt(cells: List[String], cover: String => String = identity): String =
      val left = cells.head.padTo(widths(0), ' ')
      val nums = (1 until 6).map { i =>
        val padded = " " * (widths(i) - cells(i).length) + cells(i)
        if i == 5 then cover(padded) else padded
      }.mkString("  ")
      val missing = if cells(6).isEmpty then "" else "  " + cells(6)
      s"$left  $nums$missing"
    val rule = "-" * (all.map(c => fmt(c).length).max)
    out.println(Color.bold(fmt(header), out))
    out.println(rule)
    for (cells, p) <- rows do out.println(fmt(cells, paint(p, _)))
    out.println(rule)
    out.println(Color.bold(fmt(total._1), out))
    if r.branches.total > 0 then
      out.println(s"Branches: ${r.branches.covered}/${r.branches.total} (${pct(r.branches.percent)}), lines: ${r.lines.covered}/${r.lines.total} (${pct(r.lines.percent)})")
    else out.println(s"Lines: ${r.lines.covered}/${r.lines.total} (${pct(r.lines.percent)})")

  // ---- LCOV -------------------------------------------------------------------------------------

  private def methodKey(s: Statement): String =
    if s.fullClass.isEmpty || s.fullClass == "<empty>" then s.method else s"${s.fullClass}.${s.method}"

  def lcov(r: Report): String =
    val b = new StringBuilder
    for f <- r.files do
      b ++= "TN:\nSF:" ++= f.display += '\n'
      val methods = f.measured.groupBy(methodKey).toVector.map { case (name, ss) =>
        (name, ss.map(_.line).min, ss.exists(s => f.invoked(s.id)))
      }.sortBy(m => (m._2, m._1))
      for (name, line, _) <- methods do b ++= s"FN:$line,$name\n"
      for (name, _, hit) <- methods do b ++= s"FNDA:${if hit then 1 else 0},$name\n"
      b ++= s"FNF:${methods.size}\nFNH:${methods.count(_._3)}\n"
      val branches = f.measured.filter(_.branch).sortBy(s => (s.line, s.start))
      for (line, bs) <- branches.groupBy(_.line).toVector.sortBy(_._1); (s, i) <- bs.zipWithIndex do
        b ++= s"BRDA:$line,0,$i,${if f.invoked(s.id) then 1 else 0}\n"
      b ++= s"BRF:${f.branches.total}\nBRH:${f.branches.covered}\n"
      for (line, info) <- f.lines.toVector.sortBy(_._1) do b ++= s"DA:$line,${if info.hit then 1 else 0}\n"
      b ++= s"LF:${f.lineCounts.total}\nLH:${f.lineCounts.covered}\nend_of_record\n"
    b.toString

  // ---- XML --------------------------------------------------------------------------------------

  private def xml(s: String): String =
    val b = new StringBuilder(s.length)
    s.foreach {
      case '<' => b ++= "&lt;"
      case '>' => b ++= "&gt;"
      case '&' => b ++= "&amp;"
      case '"' => b ++= "&quot;"
      case '\'' => b ++= "&apos;"
      case c if c < ' ' && c != '\n' && c != '\t' && c != '\r' => ()
      case c => b += c
    }
    b.toString

  private def rate(c: Counts): String = f"${if c.total == 0 then 1.0 else c.covered.toDouble / c.total}%.4f"
  private def timestamp: Long = System.currentTimeMillis()

  /** Cobertura 4 (what GitLab, Azure DevOps, Jenkins, Codecov and Codacy read): one `<class>` per
   *  Scala class, its lines being the start lines of its statements. */
  def cobertura(r: Report): String =
    val b = new StringBuilder
    b ++= """<?xml version="1.0" encoding="UTF-8"?>""" += '\n'
    b ++= """<!DOCTYPE coverage SYSTEM "http://cobertura.sourceforge.net/xml/coverage-04.dtd">""" += '\n'
    b ++= s"""<coverage line-rate="${rate(r.lines)}" branch-rate="${rate(r.branches)}" lines-covered="${r.lines.covered}" lines-valid="${r.lines.total}" branches-covered="${r.branches.covered}" branches-valid="${r.branches.total}" complexity="0" version="scalino ${BuildInfo.scalinoVersion}" timestamp="${timestamp / 1000}">""" += '\n'
    b ++= s"  <sources>\n    <source>${xml(r.root.toString)}</source>\n  </sources>\n  <packages>\n"
    def linesXml(f: FileCoverage, ss: Vector[Statement], indent: String): Unit =
      for (line, group) <- ss.groupBy(_.line).toVector.sortBy(_._1) do
        val hit = group.exists(s => f.invoked(s.id))
        val bs = group.filter(_.branch)
        if bs.isEmpty then b ++= s"""$indent<line number="$line" hits="${if hit then 1 else 0}" branch="false"/>""" += '\n'
        else
          val c = Counts(bs.size, bs.count(s => f.invoked(s.id)))
          b ++= s"""$indent<line number="$line" hits="${if hit then 1 else 0}" branch="true" condition-coverage="${c.percent.round}% (${c.covered}/${c.total})"/>""" += '\n'
    val byPackage = r.files.flatMap(f => f.measured.map(s => (s.pkg, f, s))).groupBy(_._1).toVector.sortBy(_._1)
    for (pkg, entries) <- byPackage do
      val pkgStmts = entries.map(_._3)
      val pkgLines = entries.groupBy(e => (e._2.display, e._3.line)).values.toVector
      val lc = Counts(pkgLines.size, pkgLines.count(_.exists(e => e._2.invoked(e._3.id))))
      val bs = entries.filter(_._3.branch)
      val bc = Counts(bs.size, bs.count(e => e._2.invoked(e._3.id)))
      b ++= s"""    <package name="${xml(if pkg == "<empty>" then "" else pkg)}" line-rate="${rate(lc)}" branch-rate="${rate(bc)}" complexity="0">\n      <classes>\n"""
      for ((cls, file), es) <- entries.groupBy(e => (e._3.fullClass, e._2)).toVector.sortBy(e => (e._1._2.display, e._1._1)) do
        val ss = es.map(_._3)
        val lines = ss.groupBy(_.line).values.toVector
        val clc = Counts(lines.size, lines.count(_.exists(s => file.invoked(s.id))))
        val cbs = ss.filter(_.branch)
        val cbc = Counts(cbs.size, cbs.count(s => file.invoked(s.id)))
        b ++= s"""        <class name="${xml(cls)}" filename="${xml(file.display)}" line-rate="${rate(clc)}" branch-rate="${rate(cbc)}" complexity="0">\n          <methods>\n"""
        for (m, ms) <- ss.groupBy(_.method).toVector.sortBy(_._1) do
          val mlines = ms.groupBy(_.line).values.toVector
          val mlc = Counts(mlines.size, mlines.count(_.exists(s => file.invoked(s.id))))
          val mbs = ms.filter(_.branch)
          val mbc = Counts(mbs.size, mbs.count(s => file.invoked(s.id)))
          b ++= s"""            <method name="${xml(m)}" signature="" line-rate="${rate(mlc)}" branch-rate="${rate(mbc)}" complexity="0">\n              <lines>\n"""
          linesXml(file, ms, "                ")
          b ++= "              </lines>\n            </method>\n"
        b ++= "          </methods>\n          <lines>\n"
        linesXml(file, ss, "            ")
        b ++= "          </lines>\n        </class>\n"
      b ++= "      </classes>\n    </package>\n"
    b ++= "  </packages>\n</coverage>\n"
    b.toString

  /** scoverage's own XML report (`scoverage.xml`, as sbt-scoverage writes it), statement-level. */
  def scoverageXml(r: Report): String =
    val b = new StringBuilder
    def counts(ss: Vector[(FileCoverage, Statement)]): (Counts, Counts) =
      val bs = ss.filter(_._2.branch)
      (Counts(ss.size, ss.count(e => e._1.invoked(e._2.id))), Counts(bs.size, bs.count(e => e._1.invoked(e._2.id))))
    def attrs(c: (Counts, Counts)): String =
      s"""statement-count="${c._1.total}" statements-invoked="${c._1.covered}" statement-rate="${pct2(c._1.percent)}" branch-rate="${pct2(c._2.percent)}""""
    val all = r.files.flatMap(f => f.measured.map(s => (f, s)))
    b ++= """<?xml version="1.0" encoding="UTF-8"?>""" += '\n'
    b ++= s"""<scoverage ${attrs(counts(all))} version="1.0" timestamp="$timestamp">\n  <packages>\n"""
    for (pkg, ps) <- all.groupBy(_._2.pkg).toVector.sortBy(_._1) do
      b ++= s"""    <package name="${xml(pkg)}" ${attrs(counts(ps))}>\n      <classes>\n"""
      for ((cls, file), cs) <- ps.groupBy(e => (e._2.fullClass, e._1)).toVector.sortBy(e => (e._1._2.display, e._1._1)) do
        b ++= s"""        <class name="${xml(cls)}" filename="${xml(file.path.toString)}" ${attrs(counts(cs))}>\n          <methods>\n"""
        for (m, ms) <- cs.groupBy(_._2.method).toVector.sortBy(_._1) do
          val name = s"${cls.replace('.', '/')}/$m"
          b ++= s"""            <method name="${xml(name)}" ${attrs(counts(ms))}>\n              <statements>\n"""
          for (f, s) <- ms.sortBy(_._2.start) do
            b ++= s"""                <statement package="${xml(s.pkg)}" class="${xml(s.cls)}" class-type="${xml(s.classType)}" full-class-name="${xml(s.fullClass)}" source="${xml(f.path.toString)}" method="${xml(s.method)}" start="${s.start}" end="${s.end}" line="${s.line}" symbol="${xml(s.symbol)}" tree="${xml(s.tree)}" branch="${s.branch}" invocation-count="${if f.invoked(s.id) then 1 else 0}" ignored="${s.ignored}">${xml(s.desc)}</statement>\n"""
          b ++= "              </statements>\n            </method>\n"
        b ++= "          </methods>\n        </class>\n"
      b ++= "      </classes>\n    </package>\n"
    b ++= "  </packages>\n</scoverage>\n"
    b.toString

  // ---- JSON -------------------------------------------------------------------------------------

  private def js(s: String): String = ScalinoCli.jsonStr(s)

  private def countsJson(c: Counts): String =
    s"""{"total": ${c.total}, "covered": ${c.covered}, "percent": ${pct2(c.percent)}}"""

  def json(r: Report): String =
    val files = r.files.map { f =>
      s"""    {
         |      "path": ${js(f.display)},
         |      "statements": ${countsJson(f.stmts)},
         |      "branches": ${countsJson(f.branches)},
         |      "lines": ${countsJson(f.lineCounts)},
         |      "missingLines": [${f.missedLines.mkString(", ")}],
         |      "partialLines": [${f.partialLines.mkString(", ")}]
         |    }""".stripMargin
    }
    s"""{
       |  "tool": ${js(s"scalino ${BuildInfo.scalinoVersion}")},
       |  "timestamp": $timestamp,
       |  "totals": {
       |    "statements": ${countsJson(r.stmts)},
       |    "branches": ${countsJson(r.branches)},
       |    "lines": ${countsJson(r.lines)}
       |  },
       |  "files": [
       |${files.mkString(",\n")}
       |  ]
       |}
       |""".stripMargin

  // ---- HTML -------------------------------------------------------------------------------------

  private def html(s: String): String =
    val b = new StringBuilder(s.length)
    s.foreach {
      case '<' => b ++= "&lt;"
      case '>' => b ++= "&gt;"
      case '&' => b ++= "&amp;"
      case '"' => b ++= "&quot;"
      case c => b += c
    }
    b.toString

  private val Css =
    """:root{--bg:#fff;--fg:#1f2328;--muted:#656d76;--line:#d0d7de;--hit:#dafbe1;--miss:#ffebe9;--part:#fff8c5;--missmark:#ff818266;--bar:#1a7f37;--barbg:#d0d7de;--link:#0969da}
      |@media (prefers-color-scheme:dark){:root{--bg:#0d1117;--fg:#e6edf3;--muted:#8d96a0;--line:#30363d;--hit:#12261e;--miss:#2d1214;--part:#272115;--missmark:#f8514966;--bar:#3fb950;--barbg:#30363d;--link:#4493f8}}
      |body{margin:0;padding:16px 24px;background:var(--bg);color:var(--fg);font:14px/1.45 -apple-system,BlinkMacSystemFont,"Segoe UI",Helvetica,Arial,sans-serif}
      |a{color:var(--link);text-decoration:none}a:hover{text-decoration:underline}
      |h1{font-size:20px;margin:0 0 4px}.sub{color:var(--muted);margin:0 0 16px}
      |table.files{border-collapse:collapse;width:100%;max-width:1100px}
      |table.files th,table.files td{padding:4px 10px;border-bottom:1px solid var(--line);text-align:right;white-space:nowrap}
      |table.files th:first-child,table.files td:first-child{text-align:left;white-space:normal;word-break:break-all}
      |table.files th{cursor:pointer;user-select:none;color:var(--muted);font-weight:600}
      |.bar{display:inline-block;width:80px;height:8px;background:var(--barbg);border-radius:4px;vertical-align:middle;margin-right:6px;overflow:hidden}
      |.bar span{display:block;height:100%;background:var(--bar)}
      |.totals td{font-weight:600}
      |pre.src{margin:0;font:12px/1.5 ui-monospace,SFMono-Regular,Menlo,Consolas,monospace}
      |table.src{border-collapse:collapse;width:100%}table.src td{padding:0 8px;vertical-align:top}
      |td.ln{color:var(--muted);text-align:right;user-select:none;width:1%;white-space:nowrap}
      |td.ln a{color:inherit}
      |tr.hit td.code{background:var(--hit)}tr.miss td.code{background:var(--miss)}tr.part td.code{background:var(--part)}
      |mark{background:var(--missmark);color:inherit;border-radius:2px}
      |.legend span{display:inline-block;padding:0 6px;margin-right:8px;border-radius:3px}
      |""".stripMargin

  private val SortScript =
    """<script>document.querySelectorAll('table.files th').forEach((th,i)=>th.addEventListener('click',()=>{
      |const tb=th.closest('table').tBodies[0];const rows=[...tb.rows];const asc=th.dataset.asc!=='1';th.dataset.asc=asc?'1':'0';
      |rows.sort((a,b)=>{const x=a.cells[i].dataset.v??a.cells[i].textContent,y=b.cells[i].dataset.v??b.cells[i].textContent;
      |const n=parseFloat(x)-parseFloat(y);return (isNaN(n)?x.localeCompare(y):n)*(asc?1:-1)});rows.forEach(r=>tb.appendChild(r))}))</script>""".stripMargin

  private def page(title: String, body: String): String =
    s"""<!doctype html>
       |<html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
       |<title>${html(title)}</title><style>$Css</style></head>
       |<body>
       |$body
       |</body></html>
       |""".stripMargin

  /** `yyyy-mm-dd hh:mm UTC` (no java.time in Scala Native's javalib): Howard Hinnant's civil_from_days. */
  private def utcNow(): String =
    val secs = System.currentTimeMillis() / 1000
    val days = Math.floorDiv(secs, 86400L)
    val sod = Math.floorMod(secs, 86400L)
    val z = days + 719468
    val era = Math.floorDiv(z, 146097L)
    val doe = z - era * 146097
    val yoe = (doe - doe / 1460 + doe / 36524 - doe / 146096) / 365
    val doy = doe - (365 * yoe + yoe / 4 - yoe / 100)
    val mp = (5 * doy + 2) / 153
    val d = doy - (153 * mp + 2) / 5 + 1
    val m = if mp < 10 then mp + 3 else mp - 9
    val y = yoe + era * 400 + (if m <= 2 then 1 else 0)
    f"$y%04d-$m%02d-$d%02d ${sod / 3600}%02d:${sod % 3600 / 60}%02d UTC"

  private def bar(p: Double): String = f"""<span class="bar"><span style="width:$p%.1f%%"></span></span>"""

  private def pageName(f: FileCoverage): String =
    f.display.replaceAll("[^A-Za-z0-9._-]", "_") + ".html"

  def writeHtml(r: Report, out: Path): Unit =
    val filesDir = out.resolve("files")
    Files.createDirectories(filesDir)
    val when = utcNow()
    def row(name: String, s: Counts, b: Counts, cls: String = ""): String =
      val cells =
        s"""<td data-v="${pct2(s.percent)}">${bar(s.percent)}${pct(s.percent)}</td><td>${s.total}</td><td>${s.missed}</td>""" +
        s"""<td data-v="${pct2(b.percent)}">${if b.total == 0 then "-" else pct(b.percent)}</td><td>${b.total}</td><td>${b.missed}</td>"""
      s"""<tr$cls><td>$name</td>$cells</tr>"""
    val rows = r.files.map(f => row(s"""<a href="files/${html(pageName(f))}">${html(f.display)}</a>""", f.stmts, f.branches))
    val index =
      s"""<h1>Coverage</h1>
         |<p class="sub">Statements ${pct(r.stmts.percent)} (${r.stmts.covered}/${r.stmts.total}) &middot; branches ${pct(r.branches.percent)} (${r.branches.covered}/${r.branches.total}) &middot; lines ${pct(r.lines.percent)} (${r.lines.covered}/${r.lines.total}) &middot; $when</p>
         |<table class="files"><thead><tr><th>File</th><th>Statement cover</th><th>Stmts</th><th>Miss</th><th>Branch cover</th><th>Branches</th><th>BrMiss</th></tr></thead>
         |<tbody>
         |${rows.mkString("\n")}
         |</tbody>
         |<tfoot>${row("Total", r.stmts, r.branches, " class=\"totals\"")}</tfoot>
         |</table>
         |$SortScript""".stripMargin
    Files.write(out.resolve("index.html"), page("Coverage", index).getBytes(UTF_8))
    for f <- r.files do Files.write(filesDir.resolve(pageName(f)), page(f.display, filePage(f, when)).getBytes(UTF_8))

  private def filePage(f: FileCoverage, when: String): String =
    val text = try new String(Files.readAllBytes(f.path), UTF_8) catch case _: java.io.IOException => ""
    // Characters inside a statement that never ran: marked, on top of the line's colour.
    val missed = new java.util.BitSet(text.length + 1)
    for s <- f.measured if !f.invoked(s.id) do missed.set(math.max(0, s.start), math.min(text.length, math.max(s.start, s.end)))
    val b = new StringBuilder
    b ++= s"""<p><a href="../index.html">&larr; all files</a></p>
             |<h1>${html(f.display)}</h1>
             |<p class="sub">Statements ${pct(f.stmts.percent)} (${f.stmts.covered}/${f.stmts.total}) &middot; branches ${pct(f.branches.percent)} (${f.branches.covered}/${f.branches.total}) &middot; $when</p>
             |<p class="legend"><span style="background:var(--hit)">ran</span><span style="background:var(--part)">partly ran</span><span style="background:var(--miss)">never ran</span><mark>statement not run</mark></p>
             |<table class="src"><tbody>
             |""".stripMargin
    var offset = 0
    var lineNo = 1
    val lines = text.split("\n", -1)
    for raw <- lines do
      val cls = f.lines.get(lineNo) match
        case Some(i) if i.partial => " class=\"part\""
        case Some(i) if i.hit => " class=\"hit\""
        case Some(_) => " class=\"miss\""
        case None => ""
      val content = raw.stripSuffix("\r")
      val code = new StringBuilder
      var inMark = false
      var i = 0
      while i < content.length do
        val m = missed.get(offset + i)
        if m != inMark then { code ++= (if m then "<mark>" else "</mark>"); inMark = m }
        code ++= html(content(i).toString)
        i += 1
      if inMark then code ++= "</mark>"
      val title = f.lines.get(lineNo).map(i => s""" title="${i.statements.covered}/${i.statements.total} statements${if i.branches.total > 0 then s", ${i.branches.covered}/${i.branches.total} branches" else ""}"""").getOrElse("")
      b ++= s"""<tr id="L$lineNo"$cls$title><td class="ln"><a href="#L$lineNo">$lineNo</a></td><td class="code"><pre class="src">${if code.isEmpty then " " else code}</pre></td></tr>\n"""
      offset += raw.length + 1
      lineNo += 1
    b ++= "</tbody></table>"
    b.toString

  // ---- `scalino coverage` -----------------------------------------------------------------------

  /** `scalino coverage [report] [options]`: the reports again from the last instrumented run's data,
   *  without building or running anything (`coverage report`, `cargo llvm-cov report`). */
  def handle(args: Array[String]): Unit =
    val rest = if args.headOption.contains("report") then args.drop(1) else args
    var flags = Flags(enabled = true)
    var sources = List.empty[Path]
    var quiet = false
    var i = 0
    while i < rest.length do
      rest(i) match
        case a if isFlag(a) => val (f, used) = parseFlag(rest, i, flags); flags = f; i += used
        case "-q" | "--quiet" => quiet = true
        case a if a.startsWith("-") => die(s"unknown option: $a")
        case a => sources = sources :+ Paths.get(a)
      i += 1
    val srcs = if sources.isEmpty then List(Paths.get(".")) else sources
    val settings = try parseDirectiveSettings(ScalinoCli.expandSources(srcs)) catch case _: java.io.IOException => Settings()
    val ok =
      try report(config(flags, settings), None, quiet)
      catch case BuildFailed(msg) => ScalinoCli.exitBuildFailure(msg)
    sys.exit(if ok then 0 else 1)
