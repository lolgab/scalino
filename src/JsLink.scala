import java.io.{ByteArrayOutputStream, File, InputStream}
import java.nio.ByteBuffer
import java.nio.file.{Files, Path, Paths, StandardCopyOption}
import java.util.zip.ZipFile

import scala.collection.mutable
import scala.concurrent.{ExecutionContext, Future}
import scala.jdk.CollectionConverters.*

import org.scalajs.ir
import org.scalajs.linker.*
import org.scalajs.linker.interface.*
import org.scalajs.linker.standard.MemIRFileImpl
import org.scalajs.logging.*

/** scalino-jslink: the Scala.js linker (`scalajs-linker`, compiled to Scala Native by
 *  build/04c-build-scalino-jslink.sh) behind a small CLI, so `scalino` can link `.sjsir`
 *  without a JVM.
 *
 *  scalino-jslink --out DIR --main CLASS [options] CLASSPATH_ENTRY...
 *
 *  Class path entries are directories or jars; every `.sjsir` file in them is linked.
 *  `--main CLASS` runs `CLASS.main(Array[String])` at start-up, `--main-noargs CLASS` runs
 *  `CLASS.main()`, and `--module-initializer CLASS.method` runs a no-argument static method.
 *  `--long-running` keeps the process (and so the linker's analysis, optimizer and IR caches) alive
 *  for `scalino -w`: it links once straight away, then once for every line it reads on stdin, printing
 *  `SCALINO_LINKING_DONE` or `SCALINO_LINKING_FAILED` after each (the protocol of scalino-linkdriver's
 *  `--long-running`); it exits when stdin closes.
 *  `--main-arg A` (repeatable) is the `args` the `--main` classes get: Scala.js has no process
 *  arguments of its own, they are part of the linked program.
 *  Without any of these a library is linked from its `@JSExportTopLevel`s only.
 *
 *  Prints the output module file names, one per line, on stdout as `module: NAME`. An ES module is
 *  written as `.mjs`, so node runs it without a package.json.
 *
 *  scalino-jslink --find-mains CLASSPATH_ENTRY...
 *
 *  Prints, one per line, the classes on the class path with a static `main(Array[String])`.
 *
 *  scalino-jslink --class-table CLASSPATH_ENTRY...
 *
 *  Prints one tab-separated line per class on the class path: name, kind (class, module, interface or
 *  other), superclass (or `-`), comma-separated interfaces (or `-`), flags (comma-separated:
 *  `noargctor` = has a no-argument constructor, `abstract` = declares a method without a body). `scalino
 *  test` walks this to find test frameworks and suites: `.sjsir`-only jars have no class files. */
/** Single-threaded executor: `execute` queues, `await` runs the queue on the calling thread until
 *  the awaited future is done. */
final class RunLoop extends ExecutionContext:
  private val queue = new java.util.ArrayDeque[Runnable]

  def execute(task: Runnable): Unit = queue.add(task)
  def reportFailure(cause: Throwable): Unit = cause.printStackTrace()

  def await[T](f: Future[T]): T =
    while !f.isCompleted do
      val task = queue.poll()
      if task == null then throw new IllegalStateException("the linker is waiting on a future nothing will complete")
      task.run()
    f.value.get.get

object JsLink:
  private val Usage =
    """usage: scalino-jslink --out DIR [--main CLASS | --main-noargs CLASS | --module-initializer CLASS.method]...
      |                      [--main-arg ARG]... [--module-kind esmodule|commonjs|none] [--mode fast|full] [--source-maps|--no-source-maps]
      |                      [--check-ir] [--pretty-print] [--log quiet|info|verbose] CLASSPATH_ENTRY...
      |       scalino-jslink --find-mains CLASSPATH_ENTRY...
      |       scalino-jslink --class-table CLASSPATH_ENTRY...""".stripMargin

  private case class Opts(
      out: Option[String] = None,
      findMains: Boolean = false,
      classTable: Boolean = false,
      longRunning: Boolean = false,
      inits: List[List[String] => ModuleInitializer] = Nil,
      mainArgs: List[String] = Nil,
      moduleKind: ModuleKind = ModuleKind.NoModule,
      full: Boolean = false,
      sourceMaps: Boolean = true,
      checkIR: Boolean = false,
      header: Option[String] = None,
      esVersion: Option[String] = None,
      avoidClasses: Boolean = false,
      avoidLets: Boolean = false,
      bigInts: Boolean = false,
      prettyPrint: Boolean = false,
      log: String = "info",
      cp: List[String] = Nil
  )

  private def parse(args: List[String], o: Opts): Opts = args match
    case Nil => o.copy(cp = o.cp.reverse, inits = o.inits.reverse)
    case "--out" :: v :: t => parse(t, o.copy(out = Some(v)))
    case "--find-mains" :: t => parse(t, o.copy(findMains = true))
    case "--class-table" :: t => parse(t, o.copy(classTable = true))
    case "--long-running" :: t => parse(t, o.copy(longRunning = true))
    case "--main" :: v :: t => parse(t, o.copy(inits = ((args: List[String]) => ModuleInitializer.mainMethodWithArgs(v, "main", args)) :: o.inits))
    case "--main-noargs" :: v :: t => parse(t, o.copy(inits = ((_: List[String]) => ModuleInitializer.mainMethod(v, "main")) :: o.inits))
    case "--main-arg" :: v :: t => parse(t, o.copy(mainArgs = o.mainArgs :+ v))
    case "--module-initializer" :: v :: t =>
      val i = v.lastIndexOf('.')
      if i <= 0 then fail(s"--module-initializer expects CLASS.method, got $v")
      parse(t, o.copy(inits = ((_: List[String]) => ModuleInitializer.mainMethod(v.take(i), v.drop(i + 1))) :: o.inits))
    case "--module-kind" :: v :: t =>
      val k = v match
        case "esmodule" => ModuleKind.ESModule
        case "commonjs" => ModuleKind.CommonJSModule
        case "none" | "nomodule" => ModuleKind.NoModule
        case _ => fail(s"unknown module kind: $v")
      parse(t, o.copy(moduleKind = k))
    case "--mode" :: v :: t =>
      if v != "fast" && v != "full" then fail(s"unknown mode: $v")
      parse(t, o.copy(full = v == "full"))
    case "--source-maps" :: t => parse(t, o.copy(sourceMaps = true))
    case "--no-source-maps" :: t => parse(t, o.copy(sourceMaps = false))
    case "--check-ir" :: t => parse(t, o.copy(checkIR = true))
    case "--header" :: v :: t => parse(t, o.copy(header = Some(v)))
    case "--es-version" :: v :: t => parse(t, o.copy(esVersion = Some(v)))
    case "--avoid-classes" :: t => parse(t, o.copy(avoidClasses = true))
    case "--avoid-lets-and-consts" :: t => parse(t, o.copy(avoidLets = true))
    case "--bigints-for-longs" :: t => parse(t, o.copy(bigInts = true))
    case "--pretty-print" :: t => parse(t, o.copy(prettyPrint = true))
    case "--log" :: v :: t => parse(t, o.copy(log = v))
    case flag :: _ if flag.startsWith("--") => fail(s"unknown option: $flag")
    case entry :: t => parse(t, o.copy(cp = entry :: o.cp))

  private def fail(msg: String): Nothing =
    System.err.println(s"scalino-jslink: $msg")
    System.err.println(Usage)
    sys.exit(2)

  private def readAll(in: InputStream): Array[Byte] =
    try
      val bos = new ByteArrayOutputStream()
      val buf = new Array[Byte](16384)
      var n = in.read(buf)
      while n >= 0 do
        bos.write(buf, 0, n)
        n = in.read(buf)
      bos.toByteArray
    finally in.close()

  /** The `.sjsir` entries of jars, kept between links of a `--long-running` process: key = jar path, mtime, size. */
  private val jarCache = mutable.HashMap.empty[(String, Long, Long), List[(String, Array[Byte])]]

  /** All `.sjsir` files of the class path as (path, bytes, stamp), in class path order. A class defined twice
   *  keeps its first definition (the linker rejects duplicates). The stamp (mtime and size of the file, or of
   *  the jar) says whether a file may have changed since the last link. */
  private def readIRStamped(cp: List[String]): List[(String, Array[Byte], Long)] =
    val files = List.newBuilder[(String, Array[Byte], Long)]
    val seen = mutable.HashSet.empty[String]
    def add(path: String, bytes: Array[Byte], stamp: Long): Unit =
      if seen.add(path) then files += ((path, bytes, stamp))
    for entry <- cp do
      val f = new File(entry)
      if f.isDirectory then
        val stream = Files.walk(f.toPath)
        try
          val paths = stream.iterator().asScala.filter(p => p.toString.endsWith(".sjsir") && Files.isRegularFile(p)).toList.sortBy(_.toString)
          for p <- paths do
            val file = p.toFile
            add(f.toPath.relativize(p).toString.replace('\\', '/'), Files.readAllBytes(p), file.lastModified * 31 + file.length)
        finally stream.close()
      else if f.isFile then
        val key = (entry, f.lastModified, f.length)
        val entries = jarCache.getOrElseUpdate(key, {
          val zip = new ZipFile(f)
          try zip.entries().asScala.filter(_.getName.endsWith(".sjsir")).map(e => e.getName -> readAll(zip.getInputStream(e))).toList
          finally zip.close()
        })
        for (name, bytes) <- entries do add(name, bytes, f.lastModified * 31 + f.length)
      // missing entries are ignored, like a JVM class path
    files.result()

  private def readIR(cp: List[String]): List[(String, Array[Byte])] =
    readIRStamped(cp).map((path, bytes, _) => (path, bytes))

  private def loadIR(cp: List[String]): List[IRFile] =
    readIRStamped(cp).map((path, bytes, stamp) => new MemIRFileImpl(path, ir.Version.fromLong(stamp), bytes))

  private val MainMethod = ir.Names.MethodName(
    "main", List(ir.Types.ArrayTypeRef(ir.Types.ClassRef(ir.Names.ClassName("java.lang.String")), 1)), ir.Types.VoidRef)

  /** Classes with a static `main(Array[String])`: what `@main` and `object X { def main }` compile to. */
  private def findMains(cp: List[String]): List[String] =
    val mains = List.newBuilder[String]
    for (_, bytes) <- readIR(cp) do
      val cls = ir.Serializers.deserialize(ByteBuffer.wrap(bytes))
      if cls.kind == ir.ClassKind.Class && cls.methods.exists(m => m.flags.namespace.isStatic && m.name.name == MainMethod) then
        mains += cls.name.name.nameString
    mains.result().sorted

  private def classTable(cp: List[String]): List[String] =
    for (_, bytes) <- readIR(cp) yield
      val cls = ir.Serializers.deserialize(ByteBuffer.wrap(bytes))
      val kind = cls.kind match
        case ir.ClassKind.Class => "class"
        case ir.ClassKind.ModuleClass => "module"
        case ir.ClassKind.Interface => "interface"
        case _ => "other"
      val flags = List(
        if cls.methods.exists(m => m.flags.namespace.isConstructor && !m.flags.namespace.isStatic && m.args.isEmpty) then "noargctor" else "",
        if cls.methods.exists(_.body.isEmpty) then "abstract" else ""
      ).filter(_.nonEmpty)
      def orDash(s: String) = if s.isEmpty then "-" else s
      List(
        cls.name.name.nameString, kind,
        orDash(cls.superClass.map(_.name.nameString).getOrElse("")),
        orDash(cls.interfaces.map(_.name.nameString).mkString(",")),
        orDash(flags.mkString(","))).mkString("\t")

  def main(args: Array[String]): Unit =
    if args.exists(a => a == "-h" || a == "--help") then
      println(Usage)
      return
    // javalib-native reports Java 8, which makes the linker warn about its JDK on every run.
    System.setProperty("java.version", "17")
    val o = parse(args.toList, Opts())
    if o.findMains then
      findMains(o.cp).foreach(println)
      return
    if o.classTable then
      classTable(o.cp).foreach(println)
      return
    val out = Paths.get(o.out.getOrElse(fail("--out is required")))

    val logger: Logger = o.log match
      case "quiet" => NullLogger
      case "verbose" => new ScalaConsoleLogger(Level.Debug)
      case _ => new ScalaConsoleLogger(Level.Warn)

    val config = StandardConfig()
      .withModuleKind(o.moduleKind)
      .withOptimizer(o.full)
      .withMinify(o.full)
      .withClosureCompilerIfAvailable(false)
      .withJSHeader(o.header.map(h => h.linesIterator.map(l => if l.startsWith("//") then l else "// " + l).mkString("", "\n", "\n")).getOrElse(""))
      .withSourceMap(o.sourceMaps)
      .withCheckIR(o.checkIR)
      .withPrettyPrint(o.prettyPrint)
      .withESFeatures(f => {
        val version = o.esVersion match
          case None => f.esVersion
          case Some("es5_1" | "es5") => ESVersion.ES5_1
          case Some("es2015") => ESVersion.ES2015
          case Some("es2016") => ESVersion.ES2016
          case Some("es2017") => ESVersion.ES2017
          case Some("es2018") => ESVersion.ES2018
          case Some("es2019") => ESVersion.ES2019
          case Some("es2020") => ESVersion.ES2020
          case Some("es2021") => ESVersion.ES2021
          case Some(other) => fail(s"unknown --es-version $other")
        f.withESVersion(version)
          .withAvoidClasses(o.avoidClasses || f.avoidClasses)
          .withAvoidLetsAndConsts(o.avoidLets || f.avoidLetsAndConsts)
          .withAllowBigIntsForLongs(o.bigInts || f.allowBigIntsForLongs)
      })
      .withOutputPatterns(if o.moduleKind == ModuleKind.ESModule then OutputPatterns.fromJSFile("%s.mjs") else OutputPatterns.Defaults)

    // The linker's futures are all CPU work (the file IO is ours, through MemIRFileImpl and
    // MemOutputDirectory), so they run on the main thread, off a queue. Running them inline breaks the
    // analyzer (ConcurrentHashMap.computeIfAbsent re-entered), and neither ExecutionContext.global nor
    // the single-thread executor `withParallel(false)` makes it create completes reliably on Scala
    // Native: the link hangs.
    val loop = new RunLoop
    given ExecutionContext = loop
    val linker = StandardImpl.linker(config)
    val irCache = StandardImpl.irFileCache().newCache
    val initializers = o.inits.map(_(o.mainArgs))

    /** One link: reads the class path, links, writes `out`, prints the modules. False if the program doesn't link. */
    def linkOnce(): Boolean =
      try
        val irFiles = loadIR(o.cp)
        if o.log == "verbose" then System.err.println(s"scalino-jslink: ${irFiles.size} .sjsir files from ${o.cp.size} class path entries")
        // The cache hands back files whose version is unchanged without parsing them again.
        val cached = loop.await(irCache.cached(irFiles.map(IRContainer.fromIRFile)))
        val mem = MemOutputDirectory()
        val result = loop.await(linker.link(cached, initializers, mem, logger))
        Files.createDirectories(out)
        for name <- mem.fileNames() do
          val target = out.resolve(name)
          val tmp = out.resolve(name + ".tmp")
          Files.write(tmp, mem.content(name).get)
          Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING)
        for m <- result.publicModules do println(s"module: ${m.jsFileName}")
        true
      catch
        case e: LinkingException =>
          System.err.println(s"scalino-jslink: ${e.getMessage}")
          false

    if !o.longRunning then
      if !linkOnce() then sys.exit(1)
    else
      var more = true
      while more do
        println(if linkOnce() then "SCALINO_LINKING_DONE" else "SCALINO_LINKING_FAILED")
        System.out.flush()
        more = scala.io.StdIn.readLine() != null
