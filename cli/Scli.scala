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
// compiled-classfile entry-point scanner (scala-cli/Mill-style: scan the
// bytecode dotc-native emits for a real `public static void main(String[])`,
// not a source-text heuristic), and a persistent on-disk cache for both
// dependency resolution and compile/link output.
// No watch mode, no multi-Scala-version support (this binary only ever
// targets the one Scala/scala-native version it was built for -- a
// `//> using scala` directive that disagrees just gets a warning).

import java.io.{ByteArrayOutputStream, InputStream}
import java.lang.{ProcessBuilder => JProcessBuilder}
import java.nio.file.{Files, Path, Paths}
import scala.jdk.CollectionConverters.*

object Scli:

  // `dist` is wherever this binary itself lives, resolved from its own path
  // (not baked in at build time) so a copied/relocated/extracted-from-a-
  // release-tarball dist/ still works -- see docs/findings.md "Packaging/
  // relocatability". Deliberately NOT "two levels up from the binary,
  // then + /dist": that hardcoded a directory literally named `dist`,
  // which release.yml's tarball layout (scli sitting directly next to
  // lib/, compiler.cp, etc., with no `dist/` wrapper) doesn't have.
  // selfexe.SelfExe is one of three OS-specific implementations
  // (cli/selfexe/*.scala); build/07-build-scli.sh picks the right one for
  // the host OS at compile time.
  val dist: String =
    val exe = selfexe.SelfExe.path()
    Paths.get(exe).toRealPath().getParent.toString

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

  def deleteRecursively(p: Path): Unit =
    if Files.exists(p) then
      if Files.isDirectory(p) then
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
    options: List[String]
  )

  private val quotedRe = """"([^"]*)"""".r
  private def usingRe(key: String) = s"""//>\\s*using\\s+${java.util.regex.Pattern.quote(key)}\\b(.*)$$""".r

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

  def parseDirectives(sources: List[Path]): Directives =
    var deps = List.empty[String]
    var compileOnlyDeps = List.empty[String]
    var testDeps = List.empty[String]
    var scalaVersion = Option.empty[String]
    var mainClass = Option.empty[String]
    var options = List.empty[String]
    for src <- sources; line <- readFile(src).linesIterator do
      directiveValues(line, "dep", "deps").foreach(vs => deps = deps ++ vs)
      directiveValues(line, "compileOnly.dep", "compileOnly.deps").foreach(vs => compileOnlyDeps = compileOnlyDeps ++ vs)
      directiveValues(line, "test.dep", "test.deps").foreach(vs => testDeps = testDeps ++ vs)
      directiveValues(line, "scala").foreach(_.headOption.foreach(v => scalaVersion = Some(v)))
      directiveValues(line, "mainClass").foreach(_.headOption.foreach(v => mainClass = Some(v)))
      directiveValues(line, "options", "option").foreach(vs => options = options ++ vs)
    Directives(deps.distinct, compileOnlyDeps.distinct, testDeps.distinct, scalaVersion, mainClass, options)

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
  // Entry-point detection, scala-cli/Mill-style: not a source-text
  // heuristic, but a scan of the *compiled* .class files for a real
  // `public static void main(String[])` method. dotc-native emits plain
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

  /** Recursively scans every `.class` file under `classesDir` (dotc-native's
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

  def detectMainClass(classesDir: Path, explicit: Option[String]): String =
    explicit.getOrElse {
      findMainClasses(classesDir) match
        case List(one) => one
        case Nil => fail("no entry point found (looked for a compiled `public static void main(String[])`) -- pass --main-class")
        case many => fail(s"multiple possible entry points found (${many.sorted.mkString(", ")}) -- pass --main-class to pick one")
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
  // Compilation scopes, scala-cli-style: every source is "main" scope
  // except one that has a path segment literally named "test" -- covers
  // both a flat top-level `test/` folder and the sbt-style nested
  // `src/test/scala/...` layout. `run`/`compile` only build the main
  // scope (test sources typically reference a test framework like munit
  // that's declared via `//> using test.dep`, not a plain `dep`, and so
  // isn't even on the main scope's classpath -- compiling them in would
  // just fail). There's no `scli test` yet (still in unsupportedCommands)
  // to actually execute the test scope.
  // ---------------------------------------------------------------------

  def isTestSource(p: Path): Boolean =
    p.iterator().asScala.exists(_.toString == "test")

  def partitionSources(sources: List[Path]): (List[Path], List[Path]) =
    sources.partition(s => !isTestSource(s))

  // ---------------------------------------------------------------------
  // Compile + link, mirroring bin/snc but with directive-resolved deps
  // folded into the classpath, and driven straight from this process
  // instead of shelling out to a shell script.
  // ---------------------------------------------------------------------

  def readListFile(name: String): String = readFile(Paths.get(s"$dist/$name")).trim

  // compiler.cp/nativelibs.cp/nscplugin.jar.txt store dist-relative paths
  // (e.g. "lib/foo.jar") so dist/ stays relocatable -- resolve to absolute.
  def resolveCp(raw: String): String = raw.split(":").map(e => s"$dist/$e").mkString(":")

  /** Compiles `sources`, then detects (or takes, if `explicitMainClass` is
   *  set) the entry point from the *compiled* classfiles -- see
   *  `detectMainClass` -- links, and copies the resulting binary to
   *  `outFor(mainClass)`. Compilation always targets a single
   *  mainClass-independent scratch dir (wiped before every build): compiling
   *  isn't incremental here regardless of directory identity (every `scli`
   *  build fully recompiles), so nothing is lost by not knowing the entry
   *  point's name up front, and it sidesteps the chicken-and-egg problem of
   *  needing a compiled classfile to name a directory the compiler is about
   *  to compile into. Only linking (whose C-object cache genuinely benefits
   *  from a stable, persistent path) is keyed by the resolved mainClass.
   *  Returns the resolved mainClass. */
  /** javaBase/pluginJar/compileCp for a dotc-native invocation -- shared by
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
      cp.split(":").filterNot(j => j.matches(""".*/scala-library-[0-9.]+\.jar""") && !j.endsWith(s"/scala-library-${BuildInfo.scalaVersion}.jar")).mkString(":")

    val compileCp =
      List(scalalibRetained, dropPlainScalaLibrary(compilerCp), dropPlainScalaLibrary(nativelibsCp), extraClasspath, extraCompileOnlyClasspath)
        .filter(_.nonEmpty).mkString(":")

    CompileClasspath(javaBase, pluginJar, compileCp, nativelibsCp)

  def buildBinary(
    sources: List[Path],
    explicitMainClass: Option[String],
    extraClasspath: String,
    outFor: String => Path,
    extraOptions: List[String] = Nil,
    extraCompileOnlyClasspath: String = "",
    logLevel: String = "info"
  ): String =
    val classesDir = Paths.get(".scli-build", "_scratch", "classes")
    deleteRecursively(classesDir)
    Files.createDirectories(classesDir)

    val cc = computeCompileClasspath(extraClasspath, extraCompileOnlyClasspath)

    val compileCmd = List(
      s"$dist/dotc-native",
      "-javabootclasspath", cc.javaBase,
      "-classpath", cc.compileCp,
      "-Xplugin:" + cc.pluginJar, "-Xplugin-require:scalanative",
      "-Yretain-trees"
    ) ++ extraOptions ++ List(
      "-d", classesDir.toString
    ) ++ sources.map(_.toString)

    val compileExit = runInherited(compileCmd)
    if compileExit != 0 then fail("compilation failed")

    val mainClass = detectMainClass(classesDir, explicitMainClass)
    val linkDir = Paths.get(".scli-build").resolve(mainClass).resolve("link")

    val linkCp =
      if extraClasspath.isEmpty then s"$classesDir:${cc.nativelibsCp}"
      else s"$classesDir:${cc.nativelibsCp}:$extraClasspath"
    val clang = findOnPath("clang")
    val clangpp = findOnPath("clang++")

    val linkExit = runInherited(
      List(s"$dist/linkdriver-native", linkCp, linkDir.toString, mainClass, clang, clangpp, logLevel)
    )
    if linkExit != 0 then fail("linking failed")

    val produced = linkDir.resolve(mainClass)
    val producedLower = linkDir.resolve(mainClass.toLowerCase)
    val actual = if Files.exists(produced) then produced else producedLower
    if !Files.exists(actual) then fail(s"expected linked binary at $actual, not found")
    val out = outFor(mainClass)
    Files.createDirectories(Option(out.getParent).getOrElse(Paths.get(".")))
    Files.copy(actual, out, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
    out.toFile.setExecutable(true)
    mainClass

  // ---------------------------------------------------------------------
  // CLI surface, scala-cli-shaped:
  //   scli <sources...>                       run (default command, like
  //                                            `scala-cli Foo.scala`)
  //   scli run <sources...> [options]
  //   scli compile <sources...> -o <out> [options]
  //   scli version / scli --help
  // options: --main-class X | -d/--dep coord | -S/--scala ver |
  //          -O/--scalac-option opt | -w/--watch | -o/--output path |
  //          -v/--verbose | -q/--quiet | -- <program args...>
  // ---------------------------------------------------------------------

  private val unsupportedCommands = Set(
    "test", "fmt", "repl", "package", "publish", "publish-local", "clean",
    "bsp", "export", "doctor", "install-completions",
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
         |  scli setup-ide <sources...> [options]   write .dotty-ide.json for editor LSP support
         |  scli version                        print version info
         |  scli --help                         this message
         |
         |a source argument may be a directory: every .scala file under it is
         |included (skipping hidden and build-output directories). files under
         |a `test/` directory (or sbt-style `src/test/scala/`) are test scope
         |and excluded from `run`/`compile` -- scala-cli's convention.
         |
         |options:
         |  --main-class <name>        explicit entry point (skips auto-detection)
         |  -d, --dep <coord>          add a dependency (repeatable)
         |  -S, --scala <version>      declare a Scala version (must match ${BuildInfo.scalaVersion})
         |  -O, --scalac-option <opt>  pass an extra compiler flag (repeatable)
         |  -w, --watch                rebuild (and, for `run`, rerun) on source changes
         |  -o, --output <path>        output path (compile only)
         |  -v, --verbose              show full build-tool debug output (raw clang/linker invocations)
         |  -q, --quiet                only show warnings/errors
         |  -- <args...>               arguments passed to the program (run only)
         |
         |directives (in source files), one per line:
         |  //> using dep "org::name:version"
         |  //> using compileOnly.dep "org::name:version"
         |  //> using test.dep "org::name:version"    (test scope only; parsed, not yet run)
         |  //> using scala "3.x"
         |  //> using mainClass "Foo"
         |  //> using options "-flag1", "-flag2"
         |
         |`setup-ide` writes `.dotty-ide.json` at the project root -- dotty's
         |own pre-Metals IDE config format (compilerArguments/
         |sourceDirectories/dependencyClasspath/classDirectory), read by
         |dist/dotty-lsp-native on startup. Same command name as scala-cli's
         |`setup-ide`, but a different output file: this toolchain's LSP
         |speaks that format directly, no BSP layer needed.
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
    progArgs: List[String] = Nil,
    verbose: Boolean = false,
    quiet: Boolean = false
  ):
    // scala-cli-style: -v shows the full build-tool debug trace (raw
    // clang/linker invocations, NativeConfig dumps), the default ("info")
    // shows just progress lines, -q drops those too and only surfaces
    // warnings/errors. -v wins if both are passed.
    def logLevel: String = if verbose then "verbose" else if quiet then "quiet" else "info"

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
        case "-v" | "--verbose" => o = o.copy(verbose = true)
        case "-q" | "--quiet" => o = o.copy(quiet = true)
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
    val depsCache = Paths.get(".scli-build").resolve("deps-cache")
    val extraClasspath = resolveDeps(allDeps, depsCache)
    val extraCompileOnlyClasspath = resolveDeps(directives.compileOnlyDeps, depsCache)
    val explicitMainClass = o.mainClassOpt.orElse(directives.mainClass)
    val options = directives.options ++ o.cliOptions

    mode match
      case "run" =>
        def binPathFor(mc: String): Path = Paths.get(".scli-build").resolve(mc).resolve("bin")
        val mainClass = buildBinary(expanded, explicitMainClass, extraClasspath, binPathFor, options, extraCompileOnlyClasspath, o.logLevel)
        runInherited(binPathFor(mainClass).toString :: o.progArgs)
      case "compile" =>
        val outPath = Paths.get(o.out.getOrElse(fail("-o <output> is required for `scli compile`")))
        val mainClass = buildBinary(expanded, explicitMainClass, extraClasspath, _ => outPath, options, extraCompileOnlyClasspath, o.logLevel)
        if !o.quiet then println(s"scli: wrote $outPath (main class: $mainClass)")
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
    val allExpanded = expandSources(o.sources)
    if allExpanded.isEmpty then die("no .scala files found")
    val (expanded, testSources) = partitionSources(allExpanded)
    if testSources.nonEmpty then
      System.err.println(s"scli: excluding ${testSources.length} test source(s) under test/ from `$mode` (test scope isn't run yet)")
    if expanded.isEmpty then die("no main-scope .scala files found (only test sources under test/)")

    if o.watch then
      watchLoop(expanded) { () =>
        try buildAndMaybeRun(mode, expanded, o)
        catch case BuildFailed(msg) => System.err.println(s"scli: $msg")
      }
    else
      try sys.exit(buildAndMaybeRun(mode, expanded, o))
      catch case BuildFailed(msg) => die(msg)

  /** JSON string/array literals for `.dotty-ide.json` -- hand-rolled rather
   *  than pulling in a JSON library: the shape is fixed (see ProjectConfig
   *  below) and every value here is either a plain path string or a flag
   *  list, so escaping quotes/backslashes is all that's needed. */
  def jsonStr(s: String): String = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
  def jsonArr(xs: List[String]): String = xs.map(jsonStr).mkString("[", ", ", "]")

  /** `scli setup-ide` -- same command name as scala-cli's own `setup-ide`,
   *  but writes `.dotty-ide.json` (dotty's pre-Metals IDE config format --
   *  `ProjectConfig.java`, read by dist/dotty-lsp-native on `initialize`,
   *  see DottyLanguageServer.IDE_CONFIG_FILE) instead of scala-cli's BSP
   *  connection file: this toolchain's LSP speaks that format directly, no
   *  BSP layer in between. Mirrors buildBinary's own compile flags/
   *  classpath exactly (via computeCompileClasspath), so the IDE
   *  type-checks against the same inputs a real build would use. */
  def handleSetupIde(args: Array[String]): Unit =
    val o = parseRunOpts(args)
    if o.sources.isEmpty then die("no source files given")
    o.sources.find(!Files.exists(_)).foreach(p => die(s"no such file: $p"))
    val allExpanded = expandSources(o.sources)
    if allExpanded.isEmpty then die("no .scala files found")
    val (expanded, testSources) = partitionSources(allExpanded)
    if testSources.nonEmpty then
      System.err.println(s"scli: excluding ${testSources.length} test source(s) under test/ from IDE config (test scope isn't run yet)")
    if expanded.isEmpty then die("no main-scope .scala files found (only test sources under test/)")

    val directives = parseDirectives(expanded)
    val allDeps = (directives.deps ++ o.cliDeps).distinct
    val depsCache = Paths.get(".scli-build").resolve("deps-cache")
    val extraClasspath = resolveDeps(allDeps, depsCache)
    val extraCompileOnlyClasspath = resolveDeps(directives.compileOnlyDeps, depsCache)
    val options = directives.options ++ o.cliOptions

    val cc = computeCompileClasspath(extraClasspath, extraCompileOnlyClasspath)
    val compilerArguments =
      List("-javabootclasspath", cc.javaBase, "-Xplugin:" + cc.pluginJar, "-Xplugin-require:scalanative", "-Yretain-trees") ++ options

    val sourceDirectories = expanded.map(_.toAbsolutePath.getParent.toString).distinct.sorted
    val dependencyClasspath = cc.compileCp.split(":").filter(_.nonEmpty).toList
    val classDirectory = Paths.get(".scli-build", ".dotty-ide-classes").toAbsolutePath
    Files.createDirectories(classDirectory)
    val projectId = Option(Paths.get(".").toAbsolutePath.normalize.getFileName).map(_.toString).getOrElse("root")

    val json =
      s"""[
         |  {
         |    "id": ${jsonStr(projectId)},
         |    "compilerVersion": ${jsonStr(BuildInfo.scalaVersion)},
         |    "compilerArguments": ${jsonArr(compilerArguments)},
         |    "sourceDirectories": ${jsonArr(sourceDirectories)},
         |    "dependencyClasspath": ${jsonArr(dependencyClasspath)},
         |    "classDirectory": ${jsonStr(classDirectory.toString)},
         |    "projectDependencies": []
         |  }
         |]
         |""".stripMargin

    val configPath = Paths.get(".dotty-ide.json")
    Files.write(configPath, json.getBytes("UTF-8"))
    println(s"scli: wrote ${configPath.toAbsolutePath} -- point dist/dotty-lsp-native (or an editor's LSP binary override) at this project")

  def main(args: Array[String]): Unit =
    if args.isEmpty then { printUsage(System.err); sys.exit(1) }
    args(0) match
      case "-h" | "--help" => printUsage(System.out)
      case "--version" | "version" => printVersion()
      case "run" => handleRunOrCompile("run", args.drop(1))
      case "compile" => handleRunOrCompile("compile", args.drop(1))
      case "setup-ide" => handleSetupIde(args.drop(1))
      case cmd if unsupportedCommands(cmd) =>
        die(s"'$cmd' is not implemented in this minimal scala-cli-alike -- supported: run, compile, version")
      case first if first.startsWith("-") || Files.exists(Paths.get(first)) =>
        handleRunOrCompile("run", args) // implicit `run`, e.g. `scli Foo.scala`
      case other =>
        die(s"unknown command or file '$other' -- run 'scli --help'")
