// Multi-module builds: `//> using dependsOn ../core` makes the directory `../core` a module the
// declaring sources depend on. A module is a directory with its own sources and `//> using` lines
// (no build file), compiled by itself, in dependency order, into its own classes directory with its
// own incremental state; the module named on the command line (the root) is compiled and linked as
// before, with its upstream modules' classes and dependencies on its class path. scala-cli has no
// equivalent (one project per invocation), so the syntax follows this CLI's `jsClient`/`shared`.
//
// What flows downstream: `dep`, `jar`, `resourceDir`, `repository` (as with sbt's `dependsOn`).
// What doesn't: `compileOnly.dep`, `options`, test scope. Linking options (`mainClass`, `native*`,
// `js*`, `postLink`, `runEnv`, `package*`) only count in the root: they describe the program.
// A module without `//> using platform` builds for the platform of whatever depends on it, so one
// `core` serves a native server and its Scala.js client.

import java.nio.file.{Files, Path, Paths}
import ScalinoCli.*

object Modules:

  /** An upstream module, compiled for `platform` (`native` or `js`); `sources` is its main scope. */
  final case class Module(dir: Path, platform: String, sources: List[Path], directives: Directives, dependsOn: List[Module]):
    def name: String = Option(dir.getFileName).map(_.toString).getOrElse("root")
    /** Keyed by the absolute directory: two modules may share a name (`a/core`, `b/core`). */
    def classesDir: Path =
      Paths.get(".scalino-build", "modules", s"$name-${hashKey(dir.toString).take(8)}", Coverage.classesDirName(if platform == "js" then "classes-js" else "classes"))
    /** This module and everything it depends on, upstream first. */
    def closure: List[Module] = (dependsOn.flatMap(_.closure) :+ this).distinctBy(m => (m.dir, m.platform))

  private def shown(dir: Path): String =
    val cwd = Paths.get("").toAbsolutePath.normalize
    if dir == cwd then "."
    else if dir.startsWith(cwd) then cwd.relativize(dir).toString
    else dir.toString

  /** The modules the root (`rootSources`, with `rootDirectives`) depends on, transitively, in
   *  dependency order (each after its own upstream modules). Fails on a cycle, a missing or empty
   *  directory, or a module declaring another platform than its dependent's. */
  def resolve(rootDirectives: Directives, rootSources: List[Path], platform: String): List[Module] =
    val loaded = scala.collection.mutable.LinkedHashMap.empty[(Path, String), Module]
    val rootFiles = rootSources.map(_.toAbsolutePath.normalize)
    def load(dir: Path, platform: String, dependent: String, chain: List[Path]): Module =
      if chain.contains(dir) then
        fail(s"dependsOn cycle: ${(dir :: chain).reverse.map(shown).mkString(" -> ")}")
      if rootFiles.exists(_.startsWith(dir)) then
        fail(s"dependsOn cycle: ${shown(dir)} contains the sources being built, which depend on it")
      loaded.get((dir, platform)) match
        case Some(m) => m
        case None =>
          if !Files.isDirectory(dir) then fail(s"dependsOn: '${shown(dir)}' (from $dependent) is not a directory")
          val (main, _) = partitionSources(expandSources(List(dir)))
          val d0 = parseDirectives(main)
          d0.platform.map(platformName).foreach { declared =>
            if declared != platform then
              fail(s"module ${shown(dir)} is built for ${if declared == "js" then "scala-js" else "scala-native"}, " +
                s"but $dependent, which depends on it, is ${if platform == "js" then "scala-js" else "scala-native"}")
          }
          val sources = forPlatform(main, platform)
          if sources.isEmpty then fail(s"dependsOn: no .scala files in module '${shown(dir)}'")
          val d = if sources.size == main.size then d0 else parseDirectives(sources)
          val ups = d.dependsOn.map(u => load(Paths.get(u), platform, s"module ${shown(dir)}", dir :: chain))
          val m = Module(dir, platform, sources, d, ups)
          // Inserted after its upstream modules: the map's order is a valid build order.
          loaded((dir, platform)) = m
          m
    rootDirectives.dependsOn.foreach(u => load(Paths.get(u), platform, "the root module", Nil))
    loaded.values.toList

  /** Its id in the LSP config (setup-ide): a function of the module alone, so editing one module never
   *  renames another's project. The directory's path from where setup-ide runs (`core`, `server/core`,
   *  `../lib/core`, always with `/`), which no other module has; `js:` in front for a Scala.js build
   *  (a colon can't be in a Windows path, so this can't be another module's path). A module at
   *  `./main`, `./test` or `./client` would take the root's own project ids: it keeps the `./`. */
  def lspId(m: Module): String =
    val path = shown(m.dir).replace(java.io.File.separatorChar, '/')
    val unambiguous = if Set("main", "test", "client")(path) then s"./$path" else path
    if m.platform == "js" then s"js:$unambiguous" else unambiguous

  /** The root's directives with what its upstream modules pass downstream folded in. */
  def withUpstream(d: Directives, modules: List[Module]): Directives =
    if modules.isEmpty then d
    else
      val ups = modules.map(_.directives)
      d.copy(
        deps = (d.deps ++ ups.flatMap(_.deps)).distinct,
        jars = (d.jars ++ ups.flatMap(_.jars)).distinct,
        resourceDirs = (d.resourceDirs ++ ups.flatMap(_.resourceDirs)).distinct,
        repositories = (d.repositories ++ ups.flatMap(_.repositories)).distinct)

  /** The class path a module (or, with its upstream modules, the root) compiles against beyond its
   *  own classes: the upstream classes directories, then the resolved dependencies, jars and
   *  resources of `closure`. */
  def classpathOf(closure: List[Module], self: Option[Module], o: RunOpts, depsCache: Path): String =
    val ups = closure.filterNot(m => self.contains(m))
    val ds = closure.map(_.directives)
    val repos = (ds.flatMap(_.repositories) ++ o.cliRepositories).distinct
    val deps = ds.flatMap(_.deps).distinct
    val resolved = if self.exists(_.platform == "js") then resolveJsDeps((jsBaseDeps ++ deps).distinct, depsCache, repos) else resolveDeps(deps, depsCache, repos)
    (ups.map(_.classesDir.toString) ++ List(resolved) ++ ds.flatMap(_.jars) ++ ds.flatMap(_.resourceDirs)).filter(_.nonEmpty).distinct.mkString(CP_SEP)

  /** Compiles every module of `modules` (in the order `resolve` returned) that has changed, or whose
   *  upstream modules have. `--option`s from the command line apply to every module. */
  def compileAll(modules: List[Module], o: RunOpts): Unit =
    val depsCache = Paths.get(".scalino-build").resolve("deps-cache")
    for m <- modules do
      val js = m.platform == "js"
      val cp = classpathOf(m.closure, Some(m), o, depsCache)
      val compileOnlyCp = resolveDeps(m.directives.compileOnlyDeps.distinct, depsCache, (m.directives.repositories ++ o.cliRepositories).distinct)
      val cc = if js then computeJsCompileClasspath(cp, compileOnlyCp) else computeCompileClasspath(cp, compileOnlyCp)
      // --coverage: modules are instrumented too, into their own classes-coverage (cli/Coverage.scala)
      val coverageOptions = Coverage.optionsFor(m.sources)
      compileToClasses(m.sources, m.classesDir, cc, m.directives.options ++ o.cliOptions ++ coverageOptions, !o.noIncremental, js, what = s"module ${shown(m.dir)}")

  /** The root's half of a multi-module build: resolves and compiles its upstream modules, and returns
   *  its directives with theirs folded in (`withUpstream`) plus their classes directories, which go
   *  first on the root's class path. A root without `dependsOn` is returned unchanged. */
  def prepare(d: Directives, rootSources: List[Path], platform: String, o: RunOpts): (Directives, List[String]) =
    if d.dependsOn.isEmpty then (d, Nil)
    else
      val saved = jsPlatform
      jsPlatform = platform == "js"
      try
        val modules = resolve(d, rootSources, platform)
        compileAll(modules, o)
        (withUpstream(d, modules), modules.map(_.classesDir.toString))
      finally jsPlatform = saved

  /** Every source of the root's modules, for `-w` to watch (each module's whole directory). */
  def watchedSources(d: Directives): List[Path] =
    if d.dependsOn.isEmpty then Nil
    else
      try
        val start = d.dependsOn.map(Paths.get(_)).filter(Files.isDirectory(_))
        (start ++ start.flatMap(dir => moduleDirsReachableFrom(collectScalaFiles(dir)))).distinct
          .filter(Files.isDirectory(_)).flatMap(collectScalaFiles)
      catch case _: java.io.IOException => Nil
