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

  val root: String = BuildInfo.root
  val dist: String = s"$root/dist"

  def die(msg: String): Nothing =
    System.err.println(s"scli: $msg")
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
    if code != 0 || out.isEmpty then die(s"'$name' not found on PATH")
    out

  // ---------------------------------------------------------------------
  // Directives: `//> using dep "org::name:version"` / `//> using scala "x"`.
  // One per line, anywhere in the file -- not a real directive parser (no
  // multi-line, no other directive kinds), but covers the common case.
  // ---------------------------------------------------------------------

  case class Directives(deps: List[String], scalaVersion: Option[String])

  private val depRe = """//>\s*using\s+dep\s+"([^"]+)"""".r
  private val scalaRe = """//>\s*using\s+scala\s+"([^"]+)"""".r

  def parseDirectives(sources: List[Path]): Directives =
    var deps = List.empty[String]
    var scalaVersion = Option.empty[String]
    for src <- sources; line <- readFile(src).linesIterator do
      depRe.findFirstMatchIn(line).foreach(m => deps = deps :+ m.group(1))
      scalaRe.findFirstMatchIn(line).foreach(m => scalaVersion = Some(m.group(1)))
    Directives(deps.distinct, scalaVersion)

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
        if code != 0 then die(s"dependency resolution failed for: ${deps.mkString(", ")}")
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
        case Nil => die("no entry point found (looked for `@main def`, `extends App`, or `def main(args: Array[String])`) -- pass --main-class")
        case many => die(s"multiple possible entry points found (${many.mkString(", ")}) -- pass --main-class to pick one")
    }

  // ---------------------------------------------------------------------
  // Compile + link, mirroring bin/snc but with directive-resolved deps
  // folded into the classpath, and driven straight from this process
  // instead of shelling out to a shell script.
  // ---------------------------------------------------------------------

  def readListFile(name: String): String = readFile(Paths.get(s"$dist/$name")).trim

  def buildBinary(sources: List[Path], mainClass: String, extraClasspath: String, out: Path): Unit =
    val cacheRoot = Paths.get(".scli-build").resolve(mainClass)
    val classesDir = cacheRoot.resolve("classes")
    val linkDir = cacheRoot.resolve("link")
    Files.createDirectories(classesDir)

    val javaBase = s"$dist/java.base.jar"
    val compilerCp = readListFile("compiler.cp")
    val nativelibsCp = readListFile("nativelibs.cp")
    val pluginJar = readListFile("nscplugin.jar.txt")

    val compileCp =
      if extraClasspath.isEmpty then s"$compilerCp:$nativelibsCp"
      else s"$compilerCp:$nativelibsCp:$extraClasspath"

    val compileCmd = List(
      s"$dist/dotc-native",
      "-javabootclasspath", javaBase,
      "-classpath", compileCp,
      "-Xplugin:" + pluginJar, "-Xplugin-require:scalanative",
      "-Yretain-trees",
      "-d", classesDir.toString
    ) ++ sources.map(_.toString)

    val compileExit = runInherited(compileCmd)
    if compileExit != 0 then die("compilation failed")

    val linkCp =
      if extraClasspath.isEmpty then s"$classesDir:$nativelibsCp"
      else s"$classesDir:$nativelibsCp:$extraClasspath"
    val clang = findOnPath("clang")
    val clangpp = findOnPath("clang++")

    val linkExit = runInherited(
      List(s"$dist/linkdriver-native", linkCp, linkDir.toString, mainClass, clang, clangpp)
    )
    if linkExit != 0 then die("linking failed")

    val produced = linkDir.resolve(mainClass)
    val producedLower = linkDir.resolve(mainClass.toLowerCase)
    val actual = if Files.exists(produced) then produced else producedLower
    if !Files.exists(actual) then die(s"expected linked binary at $actual, not found")
    Files.copy(actual, out, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
    out.toFile.setExecutable(true)

  // ---------------------------------------------------------------------
  // CLI surface: `scli run <sources...> [--main-class X] [-- args...]`
  //              `scli compile <sources...> [--main-class X] -o <out>`
  // ---------------------------------------------------------------------

  def main(args: Array[String]): Unit =
    if args.isEmpty then die("usage: scli run|compile <sources...> [--main-class X] [-o out] [-- args...]")

    val cmd = args(0)
    var sources = List.empty[Path]
    var mainClassOpt = Option.empty[String]
    var out = Option.empty[String]
    var progArgs = List.empty[String]

    var i = 1
    var inProgArgs = false
    while i < args.length do
      if inProgArgs then progArgs = progArgs :+ args(i)
      else args(i) match
        case "--" => inProgArgs = true
        case "--main-class" => mainClassOpt = Some(args(i + 1)); i += 1
        case "-o" => out = Some(args(i + 1)); i += 1
        case f => sources = sources :+ Paths.get(f)
      i += 1

    if sources.isEmpty then die("no source files given")
    sources.find(!Files.exists(_)).foreach(p => die(s"no such file: $p"))

    val directives = parseDirectives(sources)
    directives.scalaVersion.foreach { v =>
      if !BuildInfo.scalaVersion.startsWith(v) then
        System.err.println(
          s"scli: warning: //> using scala \"$v\" requested, but this toolchain only supports ${BuildInfo.scalaVersion} -- ignoring"
        )
    }
    val extraClasspath = resolveDeps(directives.deps, Paths.get(".scli-build").resolve("deps-cache"))
    val mainClass = detectMainClass(sources, mainClassOpt)

    cmd match
      case "run" =>
        val binPath = Paths.get(".scli-build").resolve(mainClass).resolve("bin")
        buildBinary(sources, mainClass, extraClasspath, binPath)
        val exit = runInherited(binPath.toString :: progArgs)
        sys.exit(exit)

      case "compile" =>
        val outPath = Paths.get(out.getOrElse(die("-o <output> is required for `scli compile`")))
        buildBinary(sources, mainClass, extraClasspath, outPath)
        println(s"scli: wrote $outPath")

      case other =>
        die(s"unknown command '$other' -- expected 'run' or 'compile'")
