// scli: a mini scala-cli, self-hosted -- this file is itself compiled by
// dist/dotc-native + dist/linkdriver-native (see build/07-build-scli.sh) into
// a standalone Scala Native binary. No JVM anywhere in this tool or in
// anything it invokes: it drives dist/dotc-native and dist/linkdriver-native
// directly, and shells out to `cs` for dependency resolution -- coursier's
// own official launcher is itself a prebuilt GraalVM native-image binary, so
// that costs no JVM either. See docs/findings.md "Toward a build-tool
// experience without a JVM".
//
// Scope ("mini"): a single `//> using dep`/`//> using scala` directive
// parser (one dep per line, no version constraints/exclusions), a
// heuristic (not compiler-driven) entry-point scanner, and a persistent
// on-disk cache for both dependency resolution and compile/link output.
// No watch mode, no multi-Scala-version support (this binary only ever
// targets the one Scala/scala-native version it was built for -- a
// `//> using scala` directive that disagrees just gets a warning).

import java.io.{ByteArrayOutputStream, InputStream}
import java.lang.{ProcessBuilder => JProcessBuilder}
import java.nio.file.{Files, Path, Paths}
import scala.jdk.CollectionConverters.*

object Scli:

  // Resolved from the running binary's own path (not baked in at build time)
  // so a copied/relocated dist/ directory still works -- see
  // docs/findings.md "Packaging/relocatability". selfexe.SelfExe is one of
  // three OS-specific implementations (cli/selfexe/*.scala); build/07-build-
  // scli.sh picks the right one for the host OS at compile time.
  val root: String =
    val exe = selfexe.SelfExe.path()
    Paths.get(exe).toRealPath().getParent.getParent.toString
  val dist: String = s"$root/dist"

  def die(msg: String): Nothing =
    System.err.println(s"scli: $msg")
    sys.exit(1)

  /** Raised by anything in the compile/link/resolve pipeline that can fail
   *  and recover on the next rebuild (as opposed to a bad CLI invocation,
   *  which is a `die` -- always fatal, checked once before the pipeline
   *  ever runs). In watch mode (`-w`) a BuildFailed is caught and reported
   *  per-iteration instead of killing the watch loop; outside watch mode
   *  it's caught once at the top and turned into the same `die` exit. */
  case class BuildFailed(msg: String) extends RuntimeException(msg)
  def fail(msg: String): Nothing = throw BuildFailed(msg)

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
   *  output the user should just see directly: cs downloading, dotc-native's
   *  own errors, linkdriver-native's build log, and the final program.
   */
  def runInherited(cmd: List[String], cwd: Option[Path] = None): Int =
    val pb = new JProcessBuilder(cmd.asJava)
    pb.inheritIO()
    cwd.foreach(p => pb.directory(p.toFile))
    pb.start().waitFor()

  /** Runs a command capturing stdout, with stderr passed straight through
   *  (matches `cs fetch --classpath`: download progress on stderr, the
   *  classpath value on stdout). */
  def runCaptureStdout(cmd: List[String]): (Int, String) =
    val pb = new JProcessBuilder(cmd.asJava)
    pb.redirectError(JProcessBuilder.Redirect.INHERIT)
    val proc = pb.start()
    val out = readAll(proc.getInputStream)
    (proc.waitFor(), out.trim)

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
    scalaVersion: Option[String],
    mainClass: Option[String],
    options: List[String]
  )

  private val quotedRe = """"([^"]*)"""".r
  private def usingRe(key: String) = s"""//>\\s*using\\s+$key\\b(.*)$$""".r

  /** All quoted values on a `//> using <key> ...` line, trying each of
   *  `keys` in turn (so callers can accept e.g. both `dep` and `deps`). */
  private def directiveValues(line: String, keys: String*): Option[List[String]] =
    keys.iterator.flatMap { k =>
      usingRe(k).findFirstMatchIn(line).map(m => quotedRe.findAllMatchIn(m.group(1)).map(_.group(1)).toList)
    }.nextOption()

  def parseDirectives(sources: List[Path]): Directives =
    var deps = List.empty[String]
    var scalaVersion = Option.empty[String]
    var mainClass = Option.empty[String]
    var options = List.empty[String]
    for src <- sources; line <- readFile(src).linesIterator do
      directiveValues(line, "dep", "deps").foreach(vs => deps = deps ++ vs)
      directiveValues(line, "scala").foreach(_.headOption.foreach(v => scalaVersion = Some(v)))
      directiveValues(line, "mainClass").foreach(_.headOption.foreach(v => mainClass = Some(v)))
      directiveValues(line, "options", "option").foreach(vs => options = options ++ vs)
    Directives(deps.distinct, scalaVersion, mainClass, options)

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

  /** A `::name::version` (scala-native cross) dependency's own published
   *  build pulls its OWN version of scala-native's runtime jars transitively
   *  (whatever scala-native release it happened to be built against) --
   *  almost never the exact same patch version as ours. Left alone, both
   *  end up on the link classpath and clang fails with hundreds of
   *  duplicate-symbol errors (two copies of the GC, two copies of libc
   *  shims, ...). We always supply these ourselves (dist/nativelibs.cp), so
   *  they're excluded here rather than resolved a second time. */
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
  def resolveDeps(deps: List[String], cacheDir: Path): String =
    if deps.isEmpty then ""
    else
      Files.createDirectories(cacheDir)
      val key = sanitizeKey(deps.sorted.mkString(","))
      val cacheFile = cacheDir.resolve(s"deps-$key.cp")
      if Files.exists(cacheFile) then readFile(cacheFile)
      else
        val cs = findOnPath("cs")
        val coords = deps.map(toCoursierCoord)
        val excludeFlags = excludedArtifacts.flatMap(a => List("-E", a))
        System.err.println(s"scli: resolving ${deps.mkString(", ")}")
        val (code, cp) = runCaptureStdout(cs :: "fetch" :: coords ::: excludeFlags ::: List("--classpath"))
        if code != 0 then fail(s"dependency resolution failed for: ${deps.mkString(", ")}")
        Files.write(cacheFile, cp.getBytes("UTF-8"))
        cp

  // ---------------------------------------------------------------------
  // Entry-point detection: a heuristic text scan, NOT compiler-driven (this
  // binary has no reflection/introspection over its own compiled output to
  // fall back on -- see docs/findings.md). Recognizes `@main def foo`,
  // `object Foo extends App`, and `object Foo { ... def main(args: ... }`.
  // Brace-depth tracking is naive (doesn't understand strings/comments) --
  // fine for typical scripts, can misfire on adversarial formatting.
  // ---------------------------------------------------------------------

  private val objectRe = """^\s*object\s+(\w+)\b.*$""".r
  private val appRe = """^\s*object\s+(\w+)\s+extends\s+App\b.*$""".r
  private val mainDefRe = """def\s+main\s*\(\s*\w+\s*:\s*Array\[String\]\s*\)""".r
  private val atMainRe = """@main\s+def\s+(\w+)""".r

  def scanMainCandidates(text: String): List[String] =
    var depth = 0
    var currentObj: Option[(String, Int)] = None
    val found = scala.collection.mutable.LinkedHashSet.empty[String]
    for line <- text.linesIterator do
      atMainRe.findFirstMatchIn(line).foreach(m => found += m.group(1))
      appRe.findFirstMatchIn(line).foreach(m => found += m.group(1))
      objectRe.findFirstMatchIn(line).foreach(m => currentObj = Some((m.group(1), depth)))
      if mainDefRe.findFirstIn(line).isDefined then
        currentObj.foreach((name, _) => found += name)
      depth += line.count(_ == '{') - line.count(_ == '}')
    found.toList

  def detectMainClass(sources: List[Path], explicit: Option[String]): String =
    explicit.getOrElse {
      val candidates = sources.flatMap(s => scanMainCandidates(readFile(s))).distinct
      candidates match
        case List(one) => one
        case Nil => fail("no entry point found (looked for `@main def`, `extends App`, or `def main(args: Array[String])`) -- pass --main-class")
        case many => fail(s"multiple possible entry points found (${many.mkString(", ")}) -- pass --main-class to pick one")
    }

  // ---------------------------------------------------------------------
  // Source expansion: a directory argument (e.g. `scli run .`) means "every
  // .scala file under here", scala-cli-style -- skipping hidden dirs and
  // this tool's own build-output dirs.
  // ---------------------------------------------------------------------

  private val skipDirNames = Set(".scli-build", "target", "out")

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

  def expandSources(paths: List[Path]): List[Path] =
    paths.flatMap(p => if Files.isDirectory(p) then collectScalaFiles(p) else List(p))

  // ---------------------------------------------------------------------
  // Compile + link, mirroring bin/snc but with directive-resolved deps
  // folded into the classpath, and driven straight from this process
  // instead of shelling out to a shell script.
  // ---------------------------------------------------------------------

  def readListFile(name: String): String = readFile(Paths.get(s"$dist/$name")).trim

  // compiler.cp/nativelibs.cp/nscplugin.jar.txt store dist-relative paths
  // (e.g. "lib/foo.jar") so dist/ stays relocatable -- resolve to absolute.
  def resolveCp(raw: String): String = raw.split(":").map(e => s"$dist/$e").mkString(":")

  def buildBinary(
    sources: List[Path],
    mainClass: String,
    extraClasspath: String,
    out: Path,
    extraOptions: List[String] = Nil
  ): Unit =
    val cacheRoot = Paths.get(".scli-build").resolve(mainClass)
    val classesDir = cacheRoot.resolve("classes")
    val linkDir = cacheRoot.resolve("link")
    Files.createDirectories(classesDir)

    val javaBase = s"$dist/java.base.jar"
    val compilerCp = resolveCp(readListFile("compiler.cp"))
    val nativelibsCp = resolveCp(readListFile("nativelibs.cp"))
    val pluginJar = s"$dist/" + readListFile("nscplugin.jar.txt")

    val compileCp =
      if extraClasspath.isEmpty then s"$compilerCp:$nativelibsCp"
      else s"$compilerCp:$nativelibsCp:$extraClasspath"

    val compileCmd = List(
      s"$dist/dotc-native",
      "-javabootclasspath", javaBase,
      "-classpath", compileCp,
      "-Xplugin:" + pluginJar, "-Xplugin-require:scalanative",
      "-Yretain-trees"
    ) ++ extraOptions ++ List(
      "-d", classesDir.toString
    ) ++ sources.map(_.toString)

    val compileExit = runInherited(compileCmd)
    if compileExit != 0 then fail("compilation failed")

    val linkCp =
      if extraClasspath.isEmpty then s"$classesDir:$nativelibsCp"
      else s"$classesDir:$nativelibsCp:$extraClasspath"
    val clang = findOnPath("clang")
    val clangpp = findOnPath("clang++")

    val linkExit = runInherited(
      List(s"$dist/linkdriver-native", linkCp, linkDir.toString, mainClass, clang, clangpp)
    )
    if linkExit != 0 then fail("linking failed")

    val produced = linkDir.resolve(mainClass)
    val producedLower = linkDir.resolve(mainClass.toLowerCase)
    val actual = if Files.exists(produced) then produced else producedLower
    if !Files.exists(actual) then fail(s"expected linked binary at $actual, not found")
    Files.createDirectories(Option(out.getParent).getOrElse(Paths.get(".")))
    Files.copy(actual, out, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
    out.toFile.setExecutable(true)

  // ---------------------------------------------------------------------
  // CLI surface, scala-cli-shaped:
  //   scli <sources...>                       run (default command, like
  //                                            `scala-cli Foo.scala`)
  //   scli run <sources...> [options]
  //   scli compile <sources...> -o <out> [options]
  //   scli version / scli --help
  // options: --main-class X | -d/--dep coord | -S/--scala ver |
  //          -O/--scalac-option opt | -w/--watch | -o/--output path |
  //          -- <program args...>
  // ---------------------------------------------------------------------

  private val unsupportedCommands = Set(
    "test", "fmt", "repl", "package", "publish", "publish-local", "clean",
    "bsp", "export", "doctor", "setup-ide", "install-completions",
    "dependency-update", "shebang"
  )

  def printVersion(): Unit =
    println(s"scli (scala-native-compiler) -- Scala ${BuildInfo.scalaVersion}, scala-native ${BuildInfo.nativeBinaryVersion}.x")

  def printUsage(out: java.io.PrintStream): Unit =
    out.print(
      s"""scli: a mini scala-cli, self-hosted on scala-native-compiler (no JVM anywhere)
         |
         |usage:
         |  scli <sources...>                   run (default command)
         |  scli run <sources...> [options]     compile and run
         |  scli compile <sources...> [options] -o <out>   compile to a native binary
         |  scli version                        print version info
         |  scli --help                         this message
         |
         |a source argument may be a directory: every .scala file under it is
         |included (skipping hidden and build-output directories).
         |
         |options:
         |  --main-class <name>        explicit entry point (skips auto-detection)
         |  -d, --dep <coord>          add a dependency (repeatable)
         |  -S, --scala <version>      declare a Scala version (must match ${BuildInfo.scalaVersion})
         |  -O, --scalac-option <opt>  pass an extra compiler flag (repeatable)
         |  -w, --watch                rebuild (and, for `run`, rerun) on source changes
         |  -o, --output <path>        output path (compile only)
         |  -- <args...>               arguments passed to the program (run only)
         |
         |directives (in source files), one per line:
         |  //> using dep "org::name:version"
         |  //> using scala "3.x"
         |  //> using mainClass "Foo"
         |  //> using options "-flag1", "-flag2"
         |
         |not implemented (this is a minimal scala-cli-alike): ${unsupportedCommands.toList.sorted.mkString(", ")}.
         |""".stripMargin
    )

  case class RunOpts(
    sources: List[Path] = Nil,
    mainClassOpt: Option[String] = None,
    out: Option[String] = None,
    watch: Boolean = false,
    cliDeps: List[String] = Nil,
    cliScala: Option[String] = None,
    cliOptions: List[String] = Nil,
    progArgs: List[String] = Nil
  )

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
        case "-d" | "--dep" | "--dependency" => o = o.copy(cliDeps = o.cliDeps :+ args(i + 1)); i += 1
        case "-S" | "--scala" | "--scala-version" => o = o.copy(cliScala = Some(args(i + 1))); i += 1
        case "-O" | "--scalac-option" | "--scalac-opt" => o = o.copy(cliOptions = o.cliOptions :+ args(i + 1)); i += 1
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
          s"scli: warning: scala \"$v\" requested, but this toolchain only supports ${BuildInfo.scalaVersion} -- ignoring"
        )
    }
    val allDeps = (directives.deps ++ o.cliDeps).distinct
    val extraClasspath = resolveDeps(allDeps, Paths.get(".scli-build").resolve("deps-cache"))
    val mainClass = detectMainClass(expanded, o.mainClassOpt.orElse(directives.mainClass))
    val options = directives.options ++ o.cliOptions

    mode match
      case "run" =>
        val binPath = Paths.get(".scli-build").resolve(mainClass).resolve("bin")
        buildBinary(expanded, mainClass, extraClasspath, binPath, options)
        runInherited(binPath.toString :: o.progArgs)
      case "compile" =>
        val outPath = Paths.get(o.out.getOrElse(fail("-o <output> is required for `scli compile`")))
        buildBinary(expanded, mainClass, extraClasspath, outPath, options)
        println(s"scli: wrote $outPath")
        0

  /** Polls source mtimes every 500ms and reruns `attempt` on change --
   *  simple and portable (no reliance on java.nio.file.WatchService, whose
   *  support in this toolchain's javalib port is unverified). Runs once
   *  immediately, same as scala-cli's `-w`. */
  def watchLoop(sources: List[Path])(attempt: () => Unit): Unit =
    def mtimes(): Map[Path, Long] =
      sources.filter(Files.exists(_)).map(p => p -> Files.getLastModifiedTime(p).toMillis).toMap
    attempt()
    System.err.println("scli: watching for changes (Ctrl+C to stop)...")
    var last = mtimes()
    while true do
      Thread.sleep(500)
      val cur = mtimes()
      if cur != last then
        last = cur
        System.err.println("scli: change detected, rebuilding...")
        attempt()

  def handleRunOrCompile(mode: String, args: Array[String]): Unit =
    val o = parseRunOpts(args)
    if o.sources.isEmpty then die("no source files given")
    o.sources.find(!Files.exists(_)).foreach(p => die(s"no such file: $p"))
    val expanded = expandSources(o.sources)
    if expanded.isEmpty then die("no .scala files found")

    if o.watch then
      watchLoop(expanded) { () =>
        try buildAndMaybeRun(mode, expanded, o)
        catch case BuildFailed(msg) => System.err.println(s"scli: $msg")
      }
    else
      try sys.exit(buildAndMaybeRun(mode, expanded, o))
      catch case BuildFailed(msg) => die(msg)

  def main(args: Array[String]): Unit =
    if args.isEmpty then { printUsage(System.err); sys.exit(1) }
    args(0) match
      case "-h" | "--help" => printUsage(System.out)
      case "--version" | "version" => printVersion()
      case "run" => handleRunOrCompile("run", args.drop(1))
      case "compile" => handleRunOrCompile("compile", args.drop(1))
      case cmd if unsupportedCommands(cmd) =>
        die(s"'$cmd' is not implemented in this minimal scala-cli-alike -- supported: run, compile, version")
      case first if first.startsWith("-") || Files.exists(Paths.get(first)) =>
        handleRunOrCompile("run", args) // implicit `run`, e.g. `scli Foo.scala`
      case other =>
        die(s"unknown command or file '$other' -- run 'scli --help'")
