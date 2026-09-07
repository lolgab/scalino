// scalino: a mini scala-cli, self-hosted -- this file is itself compiled by
// dist/scalino-dotc + dist/scalino-linkdriver (see build/07-build-scalino.sh) into
// a standalone Scala Native binary. No JVM anywhere in this tool or in
// anything it invokes: it drives dist/scalino-dotc and dist/scalino-linkdriver
// directly, and shells out to `cs` for dependency resolution -- coursier's
// own official launcher is itself a prebuilt GraalVM native-image binary, so
// that costs no JVM either. See docs/findings.md "Toward a build-tool
// experience without a JVM".
//
// Scope ("mini"): a single `//> using dep`/`//> using scala` directive
// parser (one dep per line, no version constraints/exclusions), a
// compiled-classfile entry-point scanner (scala-cli/Mill-style: scan the
// bytecode scalino-dotc emits for a real `public static void main(String[])`,
// not a source-text heuristic), and a persistent on-disk cache for both
// dependency resolution and compile/link output.
// No watch mode, no multi-Scala-version support (this binary only ever
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

  def die(msg: String): Nothing =
    System.err.println(s"scalino: $msg")
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
   *  output the user should just see directly: cs downloading, scalino-dotc's
   *  own errors, scalino-linkdriver's build log, and the final program.
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
    nativeTarget: Option[String],
    nativeEmbedResources: Option[Boolean],
    nativeMultithreading: Option[Boolean],
    jars: List[String],
    testOptions: List[String],
    resourceDirs: List[String],
    repositories: List[String]
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
    "nativeLinking", "nativeCompile", "nativeCCompile", "nativeCppCompile", "nativeTarget",
    "nativeEmbedResources", "nativeMultithreading"
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
    var nativeTarget = Option.empty[String]
    var nativeEmbedResources = Option.empty[Boolean]
    var nativeMultithreading = Option.empty[Boolean]
    var jars = List.empty[String]
    var testOptions = List.empty[String]
    var resourceDirs = List.empty[String]
    var repositories = List.empty[String]
    val warnedKeys = scala.collection.mutable.Set.empty[String]
    // Directive lines must be a comment-only line, `//>` as its first
    // non-blank characters -- NOT just "contains `//>` anywhere". An
    // unanchored scan also matches `//> using ...` mentioned in prose
    // (scaladoc explaining the directive syntax, a `printUsage` help-text
    // string literal like `|  //> using dep "..."`, etc.), which isn't a
    // directive at all -- this repo's own cli/ScalinoCli.scala is full of such
    // mentions, and scanning "." pulls that file in as a source.
    for src <- sources; rawLine <- readFile(src).linesIterator do
      val line = rawLine.trim
      if line.startsWith("//>") then
        anyDirectiveKeyRe.findFirstMatchIn(line).foreach { m =>
          val key = m.group(1)
          if !recognizedDirectiveKeys(key) && warnedKeys.add(key) then
            System.err.println(s"scalino: warning: unsupported directive '//> using $key' in $src -- ignoring")
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
        directiveValues(line, "nativeTarget").foreach(_.headOption.foreach(v => nativeTarget = Some(v)))
        directiveBool(line, "nativeEmbedResources").foreach(v => nativeEmbedResources = Some(v))
        directiveBool(line, "nativeMultithreading").foreach(v => nativeMultithreading = Some(v))
    Directives(
      deps.distinct, compileOnlyDeps.distinct, testDeps.distinct, scalaVersion, mainClass, options, testFramework,
      nativeMode, nativeGc, nativeLto, nativeClang, nativeClangPP,
      nativeLinking, nativeCompile, nativeCCompile, nativeCppCompile,
      nativeTarget, nativeEmbedResources, nativeMultithreading,
      jars.distinct, testOptions, resourceDirs.distinct, repositories.distinct
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
    cp.split(":").filter(_.nonEmpty).forall(e => Files.exists(Paths.get(e)))

  def resolveDeps(deps: List[String], cacheDir: Path, repositories: List[String] = Nil): String =
    if deps.isEmpty then ""
    else
      Files.createDirectories(cacheDir)
      val key = sanitizeKey((deps.sorted ::: repositories.sorted).mkString(","))
      val cacheFile = cacheDir.resolve(s"deps-$key.cp")
      val cached = Option.when(Files.exists(cacheFile))(readFile(cacheFile)).filter(cachedClasspathStillValid)
      cached match
        case Some(cp) => cp
        case None =>
          val cs = findOnPath("cs")
          val coords = deps.map(toCoursierCoord) ::: alwaysIncludedArtifacts
          val excludeFlags = excludedArtifacts.flatMap(a => List("-E", a))
          val repoFlags = repositories.flatMap(r => List("-r", r))
          System.err.println(s"scalino: resolving ${deps.mkString(", ")}")
          val (code, cp) = runCaptureStdout(cs :: "fetch" :: coords ::: excludeFlags ::: repoFlags ::: List("--classpath"))
          if code != 0 then fail(s"dependency resolution failed for: ${deps.mkString(", ")}")
          Files.write(cacheFile, cp.getBytes("UTF-8"))
          cp

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
        if !Files.exists(marker) then
          val cs = findOnPath("cs")
          val coords = deps.map(toCoursierCoord)
          val repoFlags = repositories.flatMap(r => List("-r", r))
          System.err.println(s"scalino: fetching sources for ${deps.mkString(", ")} (best-effort, for go-to-definition)")
          runCaptureStdout(cs :: "fetch" :: coords ::: repoFlags ::: List("--classifier", "sources"))
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
      val ccWithUserClasses = cc.copy(compileCp = s"$userClassesDir:${cc.compileCp}")
      compileToClasses(List(probeSrc), probeClasses, ccWithUserClasses)
      val linkDir = scratch.resolve("link")
      val linkCp = s"$probeClasses:$userClassesDir:${cc.nativelibsCp}:$testClasspath"
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
       |            println("FAILED: " + e.fullyQualifiedName())
       |            if e.throwable().isDefined() then e.throwable().get().printStackTrace()
       |          case sbt.testing.Status.Error =>
       |            errored += 1
       |            println("ERROR: " + e.fullyQualifiedName())
       |            if e.throwable().isDefined() then e.throwable().get().printStackTrace()
       |          case sbt.testing.Status.Skipped | sbt.testing.Status.Ignored | sbt.testing.Status.Pending | sbt.testing.Status.Canceled =>
       |            skipped += 1
       |
       |$groups
       |
       |    println("scalino: " + passed + " passed, " + failed + " failed, " + errored + " errored, " + skipped + " skipped")
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
  def resolveCp(raw: String): String = raw.split(":").map(e => s"$dist/$e").mkString(":")

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
      cp.split(":").filterNot(j => j.matches(""".*/scala-library-[0-9.]+\.jar""") && !j.endsWith(s"/scala-library-${BuildInfo.scalaVersion}.jar")).mkString(":")

    val compileCp =
      List(scalalibRetained, dropPlainScalaLibrary(compilerCp), dropPlainScalaLibrary(nativelibsCp), extraClasspath, extraCompileOnlyClasspath)
        .filter(_.nonEmpty).mkString(":")

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
    """(?m)^\s*(?:(?:final|sealed|abstract|open|case)\s+)*(?:class|trait|object|enum|given)\s+([A-Za-z_][A-Za-z0-9_]*)""".r
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
   *  top-level names) -- `Name.class`/`Name.tasty` plus anything dotc
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
        Option(dir.toFile.listFiles((_, name) => name == s"$n.class" || name == s"$n.tasty" || name.startsWith(s"$n$$")))
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
    val curDeclared = texts.view.mapValues(extractDeclaredNames).toMap
    val curPkg = texts.view.mapValues(extractPackage).toMap

    val fullRebuild = !incremental || prev.isEmpty || prev.exists(_.fingerprint != fingerprint) || !Files.isDirectory(classesDir)

    val (toCompile, removedRecords): (List[Path], List[(String, FileRecord)]) =
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

        def usedNames(text: String): Set[String] =
          allKnownNames.filter(n => ("\\b" + java.util.regex.Pattern.quote(n) + "\\b").r.findFirstIn(text).isDefined)
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
      // classesDir on the compile classpath (harmless on a full rebuild --
      // it's freshly emptied above) is what lets scalino-dotc resolve
      // symbols from files it isn't recompiling this round out of their
      // already-compiled .tasty/.class instead of needing their source.
      val compileCp = s"$classesDir:${cc.compileCp}"
      val compileCmd = List(
        s"$dist/scalino-dotc",
        "-javabootclasspath", cc.javaBase,
        "-classpath", compileCp,
        "-Xplugin:" + cc.pluginJar, "-Xplugin-require:scalanative",
        "-Yretain-trees"
      ) ++ extraOptions ++ List(
        "-d", classesDir.toString
      ) ++ toCompile.map(_.toString)

      val compileExit = runInherited(compileCmd)
      if compileExit != 0 then fail("compilation failed")
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
    embedResources: Boolean = false,
    multithreading: Boolean = false,
    linking: List[String] = Nil,
    compile: List[String] = Nil,
    cCompile: List[String] = Nil,
    cppCompile: List[String] = Nil
  )

  /** Directives win over the equivalent `--native-*` CLI flag for
   *  single-valued settings (matches `explicitMainClass`'s own precedence);
   *  list-valued and boolean settings combine both sources instead, matching
   *  how `options`/`deps` already combine directive and CLI values above. */
  def resolveNativeOpts(directives: Directives, o: RunOpts): NativeOpts =
    NativeOpts(
      mode = directives.nativeMode.orElse(o.cliNativeMode),
      gc = directives.nativeGc.orElse(o.cliNativeGc),
      lto = directives.nativeLto.orElse(o.cliNativeLto),
      clang = directives.nativeClang.orElse(o.cliNativeClang),
      clangpp = directives.nativeClangPP.orElse(o.cliNativeClangpp),
      target = directives.nativeTarget.orElse(o.cliNativeTarget),
      embedResources = directives.nativeEmbedResources.getOrElse(false) || o.cliEmbedResources,
      multithreading = directives.nativeMultithreading.getOrElse(false) || o.cliNativeMultithreading,
      linking = directives.nativeLinking ++ o.cliNativeLinking,
      compile = directives.nativeCompile ++ o.cliNativeCompile,
      cCompile = directives.nativeCCompile ++ o.cliNativeCCompile,
      cppCompile = directives.nativeCppCompile ++ o.cliNativeCppCompile
    )

  def buildBinary(
    sources: List[Path],
    explicitMainClass: Option[String],
    extraClasspath: String,
    outFor: String => Path,
    extraOptions: List[String] = Nil,
    extraCompileOnlyClasspath: String = "",
    logLevel: String = "info",
    incremental: Boolean = true,
    nativeOpts: NativeOpts = NativeOpts()
  ): String =
    val classesDir = Paths.get(".scalino-build", "_scratch", "classes")
    val cc = computeCompileClasspath(extraClasspath, extraCompileOnlyClasspath)
    compileToClasses(sources, classesDir, cc, extraOptions, incremental)

    val mainClass = detectMainClass(classesDir, explicitMainClass)
    val linkDir = Paths.get(".scalino-build").resolve(mainClass).resolve("link")

    val linkCp =
      if extraClasspath.isEmpty then s"$classesDir:${cc.nativelibsCp}"
      else s"$classesDir:${cc.nativelibsCp}:$extraClasspath"
    val clang = nativeOpts.clang.getOrElse(findOnPath("clang"))
    val clangpp = nativeOpts.clangpp.getOrElse(findOnPath("clang++"))

    val nativeFlags =
      nativeOpts.mode.toList.flatMap(v => List("--mode", v)) ++
      nativeOpts.gc.toList.flatMap(v => List("--gc", v)) ++
      nativeOpts.lto.toList.flatMap(v => List("--lto", v)) ++
      nativeOpts.target.toList.flatMap(v => List("--target", v)) ++
      (if nativeOpts.embedResources then List("--embed-resources") else Nil) ++
      (if nativeOpts.multithreading then List("--multithreading") else Nil) ++
      nativeOpts.linking.flatMap(v => List("--linking", v)) ++
      nativeOpts.compile.flatMap(v => List("--compile", v)) ++
      nativeOpts.cCompile.flatMap(v => List("--c-compile", v)) ++
      nativeOpts.cppCompile.flatMap(v => List("--cpp-compile", v))

    val linkExit = runInherited(
      List(s"$dist/scalino-linkdriver", linkCp, linkDir.toString, mainClass, clang, clangpp, logLevel) ++ nativeFlags
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
  //   scalino <sources...>                       run (default command, like
  //                                            `scala-cli Foo.scala`)
  //   scalino run <sources...> [options]
  //   scalino compile <sources...> -o <out> [options]
  //   scalino version / scalino --help
  // options: --main-class X | --dep coord | -S/--scala ver |
  //          -O/--scalac-option opt | -w/--watch | -o/--output path |
  //          -v/--verbose | -q/--quiet | -- <program args...>
  // ---------------------------------------------------------------------

  private val unsupportedCommands = Set(
    "fmt", "repl", "publish", "publish-local", "clean",
    "bsp", "export", "doctor", "install-completions",
    "dependency-update", "shebang"
  )

  def printVersion(): Unit =
    println(s"scalino (scalino) -- Scala ${BuildInfo.scalaVersion}, scala-native ${BuildInfo.nativeBinaryVersion}.x")

  def printUsage(out: java.io.PrintStream): Unit =
    out.print(
      s"""scalino: a mini scala-cli, self-hosted on scalino (no JVM anywhere)
         |
         |usage:
         |  scalino <sources...>                   run (default command)
         |  scalino run <sources...> [options]     compile and run
         |  scalino compile <sources...> [options] -o <out>   compile to a native binary
         |                                     (alias: package -- unlike real scala-cli,
         |                                     there's no separate typecheck-only mode)
         |  scalino test <sources...> [options] [-- <framework args>]   compile and run tests
         |  scalino setup-ide <sources...> [options]   write .scalino-build/scalino-lsp.json for editor LSP support
         |  scalino version                        print version info
         |  scalino --help                         this message
         |
         |a source argument may be a directory: every .scala file under it is
         |included (skipping hidden and build-output directories). files under
         |a `test/` directory (or sbt-style `src/test/scala/`) are test scope
         |and excluded from `run`/`compile` -- scala-cli's convention. `test`
         |compiles both scopes together (test sources depend on main scope).
         |
         |compilation is incremental by default: unchanged sources (and
         |anything that doesn't textually mention a changed name) are reused
         |from the last build instead of being recompiled -- pass
         |--no-incremental to always fully recompile.
         |
         |options:
         |  --main-class <name>        explicit entry point (skips auto-detection)
         |  --dep <coord>              add a dependency (repeatable; no -d short form -- real
         |                             scala-cli's -d means --output, not --dependency)
         |  --compile-dep <coord>      add a compile-time-only dependency (repeatable)
         |  -r, --repository <repo>   extra Maven repository for dependency resolution (repeatable;
         |                             passed straight through to `cs fetch -r`, e.g. a URL or
         |                             `sonatype:snapshots`)
         |  -S, --scala <version>      declare a Scala version (must match ${BuildInfo.scalaVersion})
         |  -O, --scalac-option <opt>  pass an extra compiler flag (repeatable)
         |  -w, --watch                rebuild (and, for `run`, rerun) on source changes
         |  --watching, --watching-path <path>   extra path to watch under -w (repeatable;
         |                             file or directory, watched recursively)
         |  --args-file <path>         expand to the file's contents as extra scalac options
         |                             (dotc's own native `@file` response-file expansion --
         |                             one option per line, `#` starts a line comment)
         |  -o, --output <path>        output path (compile only)
         |  -v, --verbose              show full build-tool debug output (raw clang/linker invocations)
         |  -q, --quiet                only show warnings/errors
         |  --test-framework <class>   explicit test framework class (skips auto-detection; test only)
         |  --no-incremental           always fully recompile (skip the incremental-compile cache)
         |  -- <args...>               program args (run) or test-framework filter args (test),
         |                             e.g. `scalino test . -- "*MySuite*"` (munit/utest-style filter)
         |
         |Scala Native options (no --native-version -- this toolchain only ever
         |targets the one pinned scala-native version it was built for):
         |  --native-mode <mode>       debug|release-fast|release-size|release-full (debug by default)
         |  --native-gc <gc>           immix|commix|boehm|none (immix by default)
         |  --native-lto <lto>         none|thin|full (none by default)
         |  --native-clang <path>      path to the clang command (autodetected from PATH by default)
         |  --native-clangpp <path>    path to the clang++ command (autodetected from PATH by default)
         |  --native-linking <opt>     extra option passed to clang verbatim during linking (repeatable)
         |  --native-compile <opt>     extra compile option, all sources (repeatable)
         |  --native-c-compile <opt>   extra compile option, C files only (repeatable)
         |  --native-cpp-compile <opt> extra compile option, C++ files only (repeatable)
         |  --native-target <target>  app|static|dynamic (app by default)
         |  --embed-resources          embed resources into the binary (readable via the Java resources API)
         |  --native-multithreading    enable Scala Native multithreading support
         |
         |directives (in source files), one per line -- `dep`/`options`/etc also
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
         |  //> using nativeTarget "application"   (application|library-dynamic|library-static)
         |  //> using nativeEmbedResources true
         |  //> using nativeMultithreading true
         |
         |`test` auto-detects the test framework structurally (scans the resolved
         |test classpath for a class implementing sbt.testing.Framework -- no
         |hardcoded list, so any framework with a scala-native port works, not
         |just the ones this was verified against). Only SubclassFingerprint-based
         |frameworks are supported (covers munit/utest/scalatest/zio-test-sbt);
         |JUnit4-style @Test-annotated discovery is not. Whole test classes are
         |selected (no per-test-method filtering) -- use `-- <pattern>` to filter,
         |forwarded to the framework's own runner untouched.
         |
         |`setup-ide` writes `.scalino-build/scalino-lsp.json` -- dotty's own
         |pre-Metals IDE config format (compilerArguments/sourceDirectories/
         |dependencyClasspath/classDirectory), read by dist/scalino-lsp on
         |startup. Same command name as scala-cli's `setup-ide`, but a
         |different output file: this toolchain's LSP speaks that format
         |directly, no BSP layer needed.
         |
         |not implemented (this is a minimal scala-cli-alike): ${unsupportedCommands.toList.sorted.mkString(", ")}.
         |""".stripMargin
    )

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
    noIncremental: Boolean = false,
    cliNativeMode: Option[String] = None,
    cliNativeGc: Option[String] = None,
    cliNativeLto: Option[String] = None,
    cliNativeClang: Option[String] = None,
    cliNativeClangpp: Option[String] = None,
    cliNativeTarget: Option[String] = None,
    cliNativeLinking: List[String] = Nil,
    cliNativeCompile: List[String] = Nil,
    cliNativeCCompile: List[String] = Nil,
    cliNativeCppCompile: List[String] = Nil,
    cliEmbedResources: Boolean = false,
    cliNativeMultithreading: Boolean = false
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
        case "--no-incremental" => o = o.copy(noIncremental = true)
        case "--native-version" =>
          die("--native-version is not supported -- this toolchain only ever targets the one pinned scala-native version it was built for")
        case "--native-mode" => o = o.copy(cliNativeMode = Some(args(i + 1))); i += 1
        case "--native-gc" => o = o.copy(cliNativeGc = Some(args(i + 1))); i += 1
        case "--native-lto" => o = o.copy(cliNativeLto = Some(args(i + 1))); i += 1
        case "--native-clang" => o = o.copy(cliNativeClang = Some(args(i + 1))); i += 1
        case "--native-clangpp" => o = o.copy(cliNativeClangpp = Some(args(i + 1))); i += 1
        case "--native-target" => o = o.copy(cliNativeTarget = Some(args(i + 1))); i += 1
        case "--native-linking" => o = o.copy(cliNativeLinking = o.cliNativeLinking :+ args(i + 1)); i += 1
        case "--native-compile" => o = o.copy(cliNativeCompile = o.cliNativeCompile :+ args(i + 1)); i += 1
        case "--native-c-compile" => o = o.copy(cliNativeCCompile = o.cliNativeCCompile :+ args(i + 1)); i += 1
        case "--native-cpp-compile" => o = o.copy(cliNativeCppCompile = o.cliNativeCppCompile :+ args(i + 1)); i += 1
        case "--embed-resources" => o = o.copy(cliEmbedResources = true)
        case "--native-multithreading" => o = o.copy(cliNativeMultithreading = true)
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
          s"scalino: warning: scala \"$v\" requested, but this toolchain only supports ${BuildInfo.scalaVersion} -- ignoring"
        )
    }
    val allDeps = (directives.deps ++ o.cliDeps).distinct
    val repos = (directives.repositories ++ o.cliRepositories).distinct
    val depsCache = Paths.get(".scalino-build").resolve("deps-cache")
    val extraClasspath = (List(resolveDeps(allDeps, depsCache, repos)) ++ directives.jars ++ directives.resourceDirs).filter(_.nonEmpty).mkString(":")
    val extraCompileOnlyClasspath = resolveDeps((directives.compileOnlyDeps ++ o.cliCompileOnlyDeps).distinct, depsCache, repos)
    val explicitMainClass = o.mainClassOpt.orElse(directives.mainClass)
    val options = directives.options ++ o.cliOptions
    val nativeOpts = resolveNativeOpts(directives, o)

    mode match
      case "run" =>
        def binPathFor(mc: String): Path = Paths.get(".scalino-build").resolve(mc).resolve("bin")
        val mainClass = buildBinary(expanded, explicitMainClass, extraClasspath, binPathFor, options, extraCompileOnlyClasspath, o.logLevel, !o.noIncremental, nativeOpts)
        runInherited(binPathFor(mainClass).toString :: o.progArgs)
      case "compile" =>
        val outPath = Paths.get(o.out.getOrElse(fail("-o <output> is required for `scalino compile`")))
        val mainClass = buildBinary(expanded, explicitMainClass, extraClasspath, _ => outPath, options, extraCompileOnlyClasspath, o.logLevel, !o.noIncremental, nativeOpts)
        if !o.quiet then println(s"scalino: wrote $outPath (main class: $mainClass)")
        0

  /** Polls source mtimes every 500ms and reruns `attempt` on change --
   *  simple and portable (no reliance on java.nio.file.WatchService, whose
   *  support in this toolchain's javalib port is unverified). Runs once
   *  immediately, same as scala-cli's `-w`. */
  def watchLoop(sources: List[Path])(attempt: () => Unit): Unit =
    def mtimes(): Map[Path, Long] =
      sources.filter(Files.exists(_)).map(p => p -> Files.getLastModifiedTime(p).toMillis).toMap
    attempt()
    System.err.println("scalino: watching for changes (Ctrl+C to stop)...")
    var last = mtimes()
    while true do
      Thread.sleep(500)
      val cur = mtimes()
      if cur != last then
        last = cur
        System.err.println("scalino: change detected, rebuilding...")
        attempt()

  def handleRunOrCompile(mode: String, args: Array[String]): Unit =
    val o = parseRunOpts(args)
    if o.sources.isEmpty then die("no source files given")
    o.sources.find(!Files.exists(_)).foreach(p => die(s"no such file: $p"))
    val allExpanded = expandSources(o.sources)
    if allExpanded.isEmpty then die("no .scala files found")
    val (expanded, testSources) = partitionSources(allExpanded)
    if testSources.nonEmpty then
      System.err.println(s"scalino: excluding ${testSources.length} test source(s) under test/ from `$mode` (test scope isn't run yet)")
    if expanded.isEmpty then die("no main-scope .scala files found (only test sources under test/)")

    if o.watch then
      val watchPaths = expanded ++ expandWatchPaths(o.cliWatchingPaths.map(Paths.get(_)))
      watchLoop(watchPaths) { () =>
        try buildAndMaybeRun(mode, expanded, o)
        catch case BuildFailed(msg) => System.err.println(s"scalino: $msg")
      }
    else
      try sys.exit(buildAndMaybeRun(mode, expanded, o))
      catch case BuildFailed(msg) => die(msg)

  /** `scalino test` -- see the design note above `readAllBytes`/`ClassInfo` for
   *  the overall approach (structural framework discovery, a probe binary
   *  for real fingerprint data, structural test discovery, generated
   *  driver). Unlike `run`/`compile`, compiles main *and* test scope
   *  together (test sources depend on main scope) in one pass, so it
   *  doesn't call `partitionSources` at all. */
  def handleTest(args: Array[String]): Unit =
    val o = parseRunOpts(args)
    if o.sources.isEmpty then die("no source files given")
    o.sources.find(!Files.exists(_)).foreach(p => die(s"no such file: $p"))
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
      val testClasspath = (List(mainClasspath, testOnlyClasspath) ++ directives.jars ++ directives.resourceDirs).filter(_.nonEmpty).mkString(":")
      val options = directives.options ++ o.cliOptions ++ directives.testOptions
      val cc = computeCompileClasspath(testClasspath, extraCompileOnlyClasspath)

      // Pass 1: compile the user's own sources only, to scan the result for
      // test discovery before the driver (which references those classes
      // by name) is even generated.
      val classesDir = Paths.get(".scalino-build", "_scratch", "test-classes")
      compileToClasses(expanded, classesDir, cc, options, !o.noIncremental)

      val testJars = testClasspath.split(":").filter(_.nonEmpty).toList
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

      val matches = discoverTestClasses(classesDir, specs, testJars)
      if matches.isEmpty then
        if !o.quiet then println("scalino: no tests found")
        0
      else
        if !o.quiet then println(s"scalino: found ${matches.length} test class(es): ${matches.map(_.className).sorted.mkString(", ")}")
        val driverSrc = Paths.get(".scalino-build", "_scratch", "ScalinoCliTestMain.scala")
        Files.write(driverSrc, generateTestMain(matches).getBytes("UTF-8"))
        val binPath = Paths.get(".scalino-build", "ScalinoCliTestMain", "bin")
        buildBinary(expanded :+ driverSrc, Some("ScalinoCliTestMain"), testClasspath, _ => binPath, options, extraCompileOnlyClasspath, o.logLevel, !o.noIncremental, resolveNativeOpts(directives, o))
        runInherited(binPath.toString :: o.progArgs)

    if o.watch then
      watchLoop(expanded) { () =>
        try attempt()
        catch case BuildFailed(msg) => System.err.println(s"scalino: $msg")
      }
    else
      try sys.exit(attempt())
      catch case BuildFailed(msg) => die(msg)

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
    val o = parseRunOpts(args)
    if o.sources.isEmpty then die("no source files given")
    o.sources.find(!Files.exists(_)).foreach(p => die(s"no such file: $p"))
    val allExpanded = expandSources(o.sources)
    if allExpanded.isEmpty then die("no .scala files found")
    val (expanded, testSources) = partitionSources(allExpanded)
    if testSources.nonEmpty then
      System.err.println(s"scalino: excluding ${testSources.length} test source(s) under test/ from IDE config (test scope isn't run yet)")
    if expanded.isEmpty then die("no main-scope .scala files found (only test sources under test/)")

    val directives = parseDirectives(expanded)
    val allDeps = (directives.deps ++ o.cliDeps).distinct
    val repos = (directives.repositories ++ o.cliRepositories).distinct
    val depsCache = Paths.get(".scalino-build").resolve("deps-cache")
    val extraClasspath = (List(resolveDeps(allDeps, depsCache, repos)) ++ directives.jars ++ directives.resourceDirs).filter(_.nonEmpty).mkString(":")
    val compileOnlyDeps = (directives.compileOnlyDeps ++ o.cliCompileOnlyDeps).distinct
    val extraCompileOnlyClasspath = resolveDeps(compileOnlyDeps, depsCache, repos)
    fetchSourcesBestEffort((allDeps ++ compileOnlyDeps).distinct, depsCache, repos)
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

    val sourceDirectories = expanded.map(_.toAbsolutePath.getParent.toString).distinct.sorted
    val dependencyClasspath = cc.compileCp.split(":").filter(_.nonEmpty).toList
    val classDirectory = Paths.get(".scalino-build", ".dotty-ide-classes").toAbsolutePath
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

    // Lives inside .scalino-build/ (patches/scala3-0015 moves scalino-lsp's
    // own DottyLanguageServer.IDE_CONFIG_FILE to match) rather than at the
    // project root as dotty's original ".dotty-ide.json" did, so a project's
    // .gitignore only needs one entry (.scalino-build/) to cover both this
    // and classDirectory, not a second one just for this file.
    val configPath = Paths.get(".scalino-build", "scalino-lsp.json")
    Files.write(configPath, json.getBytes("UTF-8"))
    println(s"scalino: wrote ${configPath.toAbsolutePath} -- point dist/scalino-lsp (or an editor's LSP binary override) at this project")

    // Also pin Zed's scalino-lsp binary path (zed-extension/README.md
    // step 4, "optional" there) so opening the project in Zed works without
    // dist/ on PATH -- `dist` is resolved from scalino's own binary location
    // (see val dist above), so this is correct however the toolchain was
    // installed. Plus a `file_types` override assigning .scala to the
    // zed-extension's own "Scala (scalino)" language (zed-extension/
    // languages/scala/config.toml) -- without it, if metals-zed is also
    // installed, Zed arbitrarily picks one extension's "Scala"-named
    // language for .scala files, and that pick isn't stable across a
    // dev-extension reinstall (see zed-extension/README.md). Only written
    // if .zed/settings.json doesn't exist yet: a real settings file may
    // hold unrelated keys this hand-rolled JSON writer isn't equipped to
    // merge into.
    val lspBinaryPath = Paths.get(dist, "scalino-lsp").toString
    val scalaFileTypes = jsonArr(List("scala"))
    val zedSettingsPath = Paths.get(".zed", "settings.json")
    if !Files.exists(zedSettingsPath) then
      Files.createDirectories(zedSettingsPath.getParent)
      val zedJson =
        s"""{
           |  "lsp": {
           |    "scalino-lsp": {
           |      "binary": {
           |        "path": ${jsonStr(lspBinaryPath)},
           |        "arguments": ${jsonArr(List("-stdio"))}
           |      }
           |    }
           |  },
           |  "file_types": {
           |    "Scala (scalino)": $scalaFileTypes
           |  }
           |}
           |""".stripMargin
      Files.write(zedSettingsPath, zedJson.getBytes("UTF-8"))
      println(s"scalino: wrote ${zedSettingsPath.toAbsolutePath} -- pins Zed's scalino-lsp binary to $lspBinaryPath and assigns .scala to the \"Scala (scalino)\" language")
    else
      println(s"scalino: ${zedSettingsPath.toAbsolutePath} already exists -- leaving it alone; add this to pin the LSP binary and avoid colliding with metals-zed's own \"Scala\" language if needed:")
      println(s"""  "lsp": { "scalino-lsp": { "binary": { "path": ${jsonStr(lspBinaryPath)} } } },""")
      println(s"""  "file_types": { "Scala (scalino)": $scalaFileTypes }""")

  def main(args: Array[String]): Unit =
    if args.isEmpty then { printUsage(System.err); sys.exit(1) }
    args(0) match
      case "-h" | "--help" => printUsage(System.out)
      case "--version" | "version" => printVersion()
      case "run" => handleRunOrCompile("run", args.drop(1))
      // Note: unlike real scala-cli, `compile` here always links a native binary
      // (there's no separate typecheck-only mode) -- `package` is the name real
      // scala-cli uses for that, so accept it too rather than only the surprising name.
      case "compile" | "package" => handleRunOrCompile("compile", args.drop(1))
      case "test" => handleTest(args.drop(1))
      case "setup-ide" => handleSetupIde(args.drop(1))
      case cmd if unsupportedCommands(cmd) =>
        die(s"'$cmd' is not implemented in this minimal scala-cli-alike -- supported: run, compile, test, setup-ide, version")
      case first if first.startsWith("-") || Files.exists(Paths.get(first)) =>
        handleRunOrCompile("run", args) // implicit `run`, e.g. `scalino Foo.scala`
      case other =>
        die(s"unknown command or file '$other' -- run 'scalino --help'")
