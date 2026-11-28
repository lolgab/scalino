// Client side of `scalino-dotc --daemon` (patches/scala3-0045-dotc-daemon.patch, ScalinoDaemon.scala):
// a compile server that keeps dotc's symbol table, loaded library TASTy and macro-interpreter caches
// warm between compiles of one output directory. Measured in docs/ideas/01-dotc-daemon.md: on a
// 30-file http4s/upickle project, 60-95% of a small edit's compile time was reloading those.
//
// One daemon per (classes directory, scalino-dotc binary). It is spawned on first use, detached so it
// outlives this scalino (and a Ctrl+C in the terminal), exits after `IdleSeconds` without a request,
// and is reached over loopback TCP: the state file `<stateDir>/<key>.state` holds `<port> <token>`
// and the directory is owner-only, since a request runs the project's macros. Used by
// `compileToClasses` for `-w` and for separate invocations alike; `--no-daemon` or
// SCALINO_DOTC_DAEMON=0 turn it off, and it is off by default when $CI is set.
//
// The daemon drops its warm state on its own whenever it can't vouch for it (see ScalinoDaemon's
// doc comment). The generation file next to the classes directory is how it learns that something
// other than itself wrote that directory: every successful daemon compile records a fresh id there
// and the daemon remembers it; any other compile deletes the file.

import java.io.{BufferedReader, InputStreamReader, OutputStreamWriter, PrintWriter}
import java.lang.{ProcessBuilder => JProcessBuilder}
import java.net.{InetSocketAddress, Socket}
import java.nio.channels.FileChannel
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path, Paths, StandardOpenOption}
import scala.jdk.CollectionConverters.*
import ScalinoCli.{dist, isWindows, readFile, hashKey, tool, Color}

object DotcDaemon:

  enum Mode:
    case Off, On, Verify

  private def env(name: String): Option[String] = Option(System.getenv(name)).filter(_.nonEmpty)

  /** SCALINO_DOTC_DAEMON: 0/off, 1/on, verify (compile through the daemon, then again cold, and fail
   *  on any difference in the outputs). Unset: on, except under CI (one-shot builds, nobody to reuse
   *  a warm daemon, and a background process must not outlive the job). `--no-daemon` wins. */
  var mode: Mode = env("SCALINO_DOTC_DAEMON").map(_.toLowerCase) match
    case Some("0" | "off" | "false" | "no") => Mode.Off
    case Some("verify") => Mode.Verify
    case Some(_) => Mode.On
    case None => if env("CI").exists(_ != "false") then Mode.Off else Mode.On

  private val IdleSeconds: Int = env("SCALINO_DOTC_DAEMON_IDLE").flatMap(_.toIntOption).getOrElse(15 * 60)
  /** More daemons than this (one per classes directory) and the least recently used ones are stopped. */
  private val MaxDaemons: Int = env("SCALINO_DOTC_DAEMON_MAX").flatMap(_.toIntOption).getOrElse(3)
  /** A daemon whose resident set grows past this is stopped after its compile; the next one starts cold. */
  private val MaxRssMb: Long = env("SCALINO_DOTC_DAEMON_MAX_RSS_MB").flatMap(_.toLongOption).getOrElse(4096L)

  def stateDir: Path =
    env("SCALINO_DOTC_DAEMON_DIR").map(Paths.get(_)).getOrElse(
      if isWindows then
        Paths.get(env("LOCALAPPDATA").getOrElse(Paths.get(System.getProperty("user.home"), "AppData", "Local").toString), "scalino", "dotc-daemon")
      else
        env("XDG_CACHE_HOME").map(Paths.get(_)).getOrElse(Paths.get(env("HOME").getOrElse("."), ".cache")).resolve("scalino").resolve("dotc-daemon")
    )

  private def ensureStateDir(): Path =
    val d = stateDir
    if !Files.isDirectory(d) then
      Files.createDirectories(d)
      if !isWindows then
        try Files.setPosixFilePermissions(d, java.nio.file.attribute.PosixFilePermissions.fromString("rwx------"))
        catch case _: Exception => ()
    d

  private def genFile(classesDir: Path): Path =
    classesDir.resolveSibling(classesDir.getFileName.toString + ".dotc-gen")

  /** Called by every compile that does not go through the daemon: its warm symbols no longer match
   *  the directory. */
  def forgetGeneration(classesDir: Path): Unit =
    try Files.deleteIfExists(genFile(classesDir))
    catch case _: java.io.IOException => ()

  private def newGen(): String =
    java.lang.Long.toHexString(System.nanoTime() ^ (System.currentTimeMillis() << 20)) +
      java.lang.Integer.toHexString(System.identityHashCode(new Object))

  private final case class Endpoint(key: String, port: Int, token: String)

  private def keyFor(classesDir: Path): String =
    val dotc = Paths.get(tool("scalino-dotc"))
    val mtime = try Files.getLastModifiedTime(dotc).toMillis catch case _: java.io.IOException => 0L
    // The daemon keeps the environment it was spawned with: scalino-dotc's own debug switches pick
    // a daemon of their own.
    val debugEnv = List("SCALINO_INTERP_DEBUG", "SCALINO_INTERP_DEBUG_FN", "SCALINO_INTERP_DEBUG_MATCH", "SCALINO_OUTER_DIAG")
      .flatMap(n => env(n).map(v => s"$n=$v"))
    hashKey((List(classesDir.toAbsolutePath.normalize.toString, dotc.toString, mtime.toString) ++ debugEnv).mkString("\u0000"))

  private def statePath(key: String): Path = stateDir.resolve(s"$key.state")
  private def infoPath(key: String): Path = stateDir.resolve(s"$key.info")

  private def readEndpoint(key: String): Option[Endpoint] =
    try
      readFile(statePath(key)).trim.split(' ') match
        case Array(port, token) => port.toIntOption.map(Endpoint(key, _, token))
        case _ => None
    catch case _: java.io.IOException => None

  private def connect(ep: Endpoint): Option[Socket] =
    try
      val s = new Socket()
      s.connect(new InetSocketAddress("127.0.0.1", ep.port), 2000)
      Some(s)
    catch case _: java.io.IOException => None

  /** A request on a fresh connection; the response lines up to and including the one starting with
   *  `until`, or None when the daemon is gone. */
  private def request(ep: Endpoint, lines: Seq[String], until: String, timeoutMs: Int = 0): Option[List[String]] =
    connect(ep).flatMap { s =>
      try
        // the daemon serves one request at a time: without a timeout, a STATUS or STOP sent while it
        // compiles waits for that compile, forever if it is stuck
        if timeoutMs > 0 then s.setSoTimeout(timeoutMs)
        val out = new PrintWriter(new OutputStreamWriter(s.getOutputStream, UTF_8), false)
        out.print((ep.token +: lines).mkString("", "\n", "\n"))
        out.flush()
        val in = new BufferedReader(new InputStreamReader(s.getInputStream, UTF_8))
        val buf = List.newBuilder[String]
        var line = in.readLine()
        var done = false
        while line != null && !done do
          buf += line
          done = line.startsWith(until)
          if !done then line = in.readLine()
        if done then Some(buf.result()) else None
      catch case _: java.io.IOException => None
      finally s.close()
    }

  private def pidOf(key: String): Option[Long] =
    try readFile(infoPath(key)).linesIterator.toList.headOption.flatMap(_.trim.toLongOption)
    catch case _: java.io.IOException => None

  private def busyPath(key: String): Path = stateDir.resolve(s"$key.busy")
  private def logPath(key: String): Path = stateDir.resolve(s"$key.log")

  /** `<start millis> <what>` while a compile request is running in the daemon for `key`. */
  private def busy(key: String): Option[(Long, String)] =
    try
      val line = readFile(busyPath(key)).trim
      val (ms, what) = line.span(_ != ' ')
      ms.toLongOption.map(_ -> what.trim)
    catch case _: java.io.IOException => None

  /** One line in the daemon's log: the requests it got and how long each took. A compile that never
   *  finished is the last "compile" line without a "done" after it. */
  private def log(key: String, line: String): Unit =
    try
      val stamp = utcStamp(System.currentTimeMillis())
      Files.write(logPath(key), s"$stamp $line\n".getBytes(UTF_8), StandardOpenOption.CREATE, StandardOpenOption.APPEND)
    catch case _: Exception => ()

  /** `2026-11-28T14:03:09Z`. By hand: javalib has no java.time. */
  private def utcStamp(millis: Long): String =
    val secs = Math.floorDiv(millis, 1000L)
    val days = Math.floorDiv(secs, 86400L)
    val rem = Math.floorMod(secs, 86400L)
    // days since 1970-01-01 to a civil date (Howard Hinnant's algorithm)
    val z = days + 719468
    val era = Math.floorDiv(z, 146097L)
    val doe = z - era * 146097
    val yoe = (doe - doe / 1460 + doe / 36524 - doe / 146096) / 365
    val doy = doe - (365 * yoe + yoe / 4 - yoe / 100)
    val mp = (5 * doy + 2) / 153
    val d = doy - (153 * mp + 2) / 5 + 1
    val m = if mp < 10 then mp + 3 else mp - 9
    val y = yoe + era * 400 + (if m <= 2 then 1 else 0)
    f"$y%04d-$m%02d-$d%02dT${rem / 3600}%02d:${rem % 3600 / 60}%02d:${rem % 60}%02dZ"

  private def kill(pid: Long, sig: Int): Unit =
    if !scala.scalanative.meta.LinktimeInfo.isWindows then scala.scalanative.posix.signal.kill(pid.toInt, sig)

  /** Asks the daemon to exit; one that does not answer (it is in the middle of a compile) is killed. */
  private def stop(key: String): Unit =
    val pid = pidOf(key)
    val answered = readEndpoint(key).flatMap(ep => request(ep, List("STOP"), "SCALINO_STOPPING", timeoutMs = 3000)).isDefined
    if !answered && !isWindows then
      for p <- pid if alive(p) do
        log(key, s"stop: no answer from pid $p${busy(key).map((t, w) => s", ${(System.currentTimeMillis() - t) / 1000}s into $w").getOrElse("")}, killing it")
        kill(p, 15)
        val deadline = System.nanoTime() + 2_000_000_000L
        while alive(p) && System.nanoTime() < deadline do Thread.sleep(20)
        if alive(p) then kill(p, 9)
    for p <- List(statePath(key), infoPath(key), busyPath(key)) do
      try Files.deleteIfExists(p) catch case _: java.io.IOException => ()

  private def liveKeys(): List[String] =
    val d = stateDir
    if !Files.isDirectory(d) then Nil
    else
      val s = Files.list(d)
      try s.iterator.asScala.map(_.getFileName.toString).filter(_.endsWith(".state")).map(_.stripSuffix(".state")).toList
      finally s.close()

  /** Least recently used first (each compile touches its state file). */
  private def byLastUse(keys: List[String]): List[String] =
    keys.sortBy(k => try Files.getLastModifiedTime(statePath(k)).toMillis catch case _: java.io.IOException => 0L)

  /** Spawns a daemon for `key` and waits for its state file. Unix: through `sh`, in the background with
   *  SIGINT/SIGHUP ignored, so neither this process exiting nor a Ctrl+C or a closed terminal ends it. */
  private def spawn(key: String, classesDir: Path): Option[Endpoint] =
    val others = liveKeys().filter(_ != key)
    if others.size >= MaxDaemons then byLastUse(others).take(others.size - MaxDaemons + 1).foreach(stop)
    Files.deleteIfExists(statePath(key))
    val log = stateDir.resolve(s"$key.log")
    val daemonArgs = List(tool("scalino-dotc"), "--daemon", "--state-file", statePath(key).toString, "--idle-seconds", IdleSeconds.toString)
    val pid: Option[Long] =
      if isWindows then
        val pb = new JProcessBuilder(daemonArgs.asJava)
        pb.redirectErrorStream(true)
        pb.redirectOutput(JProcessBuilder.Redirect.appendTo(log.toFile))
        pb.redirectInput(JProcessBuilder.Redirect.from(new java.io.File("NUL")))
        Some(pb.start().pid())
      else
        val script = """trap '' INT HUP; "$0" "$@" </dev/null >>"$SCALINO_DAEMON_LOG" 2>&1 & echo $!"""
        val pb = new JProcessBuilder((List("/bin/sh", "-c", script) ++ daemonArgs).asJava)
        pb.environment().put("SCALINO_DAEMON_LOG", log.toString)
        // Same reason as the long-running linker: a long-lived process fragments the Immix heap.
        pb.environment().putIfAbsent("SCALANATIVE_EVAC_ENABLE", "1")
        pb.redirectError(JProcessBuilder.Redirect.INHERIT)
        val proc = pb.start()
        val out = ScalinoCli.readAll(proc.getInputStream).trim
        proc.waitFor()
        out.toLongOption
    pid.foreach(p => Files.write(infoPath(key), s"$p\n${classesDir.toAbsolutePath.normalize}\n".getBytes(UTF_8)))
    val deadline = System.nanoTime() + 10_000_000_000L
    var ep = readEndpoint(key)
    while ep.isEmpty && System.nanoTime() < deadline && pid.forall(alive) do
      Thread.sleep(5)
      ep = readEndpoint(key)
    ep

  private def alive(pid: Long): Boolean =
    // no kill(2) on Windows (a link-time constant, so the call is not even linked there); the 10s
    // deadline bounds the wait instead
    if scala.scalanative.meta.LinktimeInfo.isWindows then true
    else scala.scalanative.posix.signal.kill(pid.toInt, 0) == 0

  /** The daemon for `classesDir`: the running one, or a new one. None if it can't be reached or started. */
  private def endpointFor(classesDir: Path): Option[Endpoint] =
    val key = keyFor(classesDir)
    readEndpoint(key).filter(ep => connect(ep).map(_.close()).isDefined).orElse {
      ensureStateDir()
      // Two scalino processes building the same directory must not both spawn a daemon.
      val ch = FileChannel.open(stateDir.resolve(s"$key.lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE)
      try
        val lock = ch.lock()
        try readEndpoint(key).filter(ep => connect(ep).map(_.close()).isDefined).orElse(spawn(key, classesDir))
        finally lock.release()
      finally ch.close()
    }

  /** Compiles `files` (dotc `options` carry `-d classesDir`) through the daemon. `stale`: sources whose
   *  outputs were purged before this compile (recompiled or removed). Returns (exit code, output), or
   *  None when the daemon was unavailable, in which case the caller compiles the usual way. */
  def compile(classesDir: Path, options: List[String], files: List[String], stale: List[String], fullRebuild: Boolean): Option[(Int, String)] =
    if mode == Mode.Off then None
    else
      try
        endpointFor(classesDir).flatMap { ep =>
          val gf = genFile(classesDir)
          val prevGen =
            if fullRebuild || !Files.exists(gf) then "-"
            else try readFile(gf).trim catch case _: java.io.IOException => "-"
          val gen = newGen()
          val header = s"COMPILE $prevGen $gen ${options.size} ${files.size} ${stale.size}"
          val what = s"compile of ${files.size} file${if files.size == 1 then "" else "s"}${if prevGen == "-" then " (cold)" else ""} for ${classesDir.toAbsolutePath.normalize}"
          val started = System.currentTimeMillis()
          try Files.write(busyPath(ep.key), s"$started $what".getBytes(UTF_8)) catch case _: java.io.IOException => ()
          log(ep.key, what)
          val watchdog = slowCompileWatchdog(ep.key, what)
          val answer =
            try request(ep, (header :: options) ++ files ++ stale, "SCALINO_COMPILE_DONE")
            finally
              watchdog.interrupt()
              try Files.deleteIfExists(busyPath(ep.key)) catch case _: java.io.IOException => ()
          log(ep.key, s"done in ${(System.currentTimeMillis() - started) / 1000.0}s: ${answer.map(_.last).getOrElse("no answer (the daemon went away)")}")
          answer.map { lines =>
            val exit = lines.last.stripPrefix("SCALINO_COMPILE_DONE").trim.toIntOption.getOrElse(1)
            if exit == 0 then Files.write(gf, gen.getBytes(UTF_8)) else forgetGeneration(classesDir)
            try Files.setLastModifiedTime(statePath(ep.key), java.nio.file.attribute.FileTime.fromMillis(System.currentTimeMillis()))
            catch case _: java.io.IOException => ()
            checkRss(ep.key)
            (exit, lines.init.mkString("\n"))
          }.flatMap {
            case (3, out) =>
              // The compiler crashed inside the daemon (its warm state is already dropped): keep the
              // trace for a bug report and compile without it this time.
              val log = stateDir.resolve(s"${ep.key}.log")
              try Files.write(log, (out + "\n").getBytes(UTF_8), StandardOpenOption.CREATE, StandardOpenOption.APPEND)
              catch case _: java.io.IOException => ()
              System.err.println(Color.warn(s"scalino: the dotc daemon failed (see $log), compiling without it"))
              None
            case result => Some(result)
          }
        }
      catch case e: Exception =>
        System.err.println(Color.warn(s"scalino: dotc daemon unavailable (${e.getMessage}), compiling without it"))
        None

  /** A compile in the daemon has no time limit (a big project legitimately takes minutes), so a daemon
   *  that spins is only visible as a scalino that never returns. After `SlowSeconds` this says so once,
   *  with the daemon's pid and how to get out, and on macOS saves a stack sample of the daemon next to
   *  its log: what a bug report about a stuck compile needs, and what is gone once the daemon is stopped. */
  private val SlowSeconds: Int = env("SCALINO_DOTC_DAEMON_SLOW").flatMap(_.toIntOption).getOrElse(5 * 60)

  private def slowCompileWatchdog(key: String, what: String): Thread =
    val t = new Thread(() =>
      try
        Thread.sleep(SlowSeconds * 1000L)
        val pid = pidOf(key)
        val sampleFile = stateDir.resolve(s"$key.sample.txt")
        val sampled = pid.exists { p =>
          // macOS's sampler, by path: a `sample` on PATH can be anything
          val sample = Paths.get("/usr/bin/sample")
          Files.isExecutable(sample) && (
            try ScalinoCli.runCaptureAll(List(sample.toString, p.toString, "3", "-file", sampleFile.toString))._1 == 0
            catch case _: Exception => false)
        }
        log(key, s"still running after ${SlowSeconds}s${if sampled then s", stack sample in $sampleFile" else ""}")
        System.err.println(Color.warn(
          s"scalino: the dotc daemon${pid.map(p => s" (pid $p)").getOrElse("")} is still on this $what after ${SlowSeconds / 60} minutes.\n" +
            "  If it is stuck: `scalino daemon stop` ends it, and --no-daemon compiles without one.\n" +
            (if sampled then s"  A stack sample of it is in $sampleFile; please attach it and ${logPath(key)} to a bug report."
             else s"  Its request log is ${logPath(key)}.")))
      catch case _: InterruptedException => ()
    )
    t.setDaemon(true)
    t.start()
    t

  private def rssMb(pid: Long): Option[Long] =
    if isWindows then None
    else
      try
        val (code, out) = ScalinoCli.runCaptureAll(List("ps", "-o", "rss=", "-p", pid.toString))
        if code == 0 then out.trim.toLongOption.map(_ / 1024) else None
      catch case _: Exception => None

  private def checkRss(key: String): Unit =
    for pid <- pidOf(key); mb <- rssMb(pid) if mb > MaxRssMb do stop(key)

  /** `scalino daemon status|stop [all]`. */
  def handle(args: Array[String]): Unit =
    def plural(n: Int) = s"$n scalino-dotc daemon${if n == 1 then "" else "s"}"
    args.toList match
      case Nil | List("status") =>
        val keys = liveKeys()
        var shown = 0
        for key <- byLastUse(keys).reverse do
          val info = try readFile(infoPath(key)).linesIterator.toList catch case _: java.io.IOException => Nil
          val pid = info.headOption.flatMap(_.toLongOption)
          val rss = pid.flatMap(rssMb).map(m => s" rss=${m}MB").getOrElse("")
          val status = readEndpoint(key).flatMap(ep => request(ep, List("STATUS"), "SCALINO_STATUS", timeoutMs = 2000))
          status match
            case Some(lines) =>
              shown += 1
              println(s"pid=${pid.getOrElse("?")}$rss ${lines.last.stripPrefix("SCALINO_STATUS ")} ${info.lift(1).getOrElse("")}")
            case None if !isWindows && pid.exists(alive) =>
              // no answer but alive: it is compiling (one request at a time)
              shown += 1
              val doing = busy(key).map((t, w) => s"busy for ${(System.currentTimeMillis() - t) / 1000}s: $w").getOrElse(s"not answering ${info.lift(1).getOrElse("")}")
              println(s"pid=${pid.get}$rss $doing")
            case None =>
              // left behind by a daemon that was killed
              for p <- List(statePath(key), infoPath(key), busyPath(key)) do
                try Files.deleteIfExists(p) catch case _: java.io.IOException => ()
        if shown == 0 then println("no scalino-dotc daemon running")
      case List("stop") =>
        // only this project's: another project's daemon may be in the middle of a build
        val here = keysUnder(Paths.get("."))
        here.foreach(stop)
        val others = liveKeys().size
        println(s"stopped ${plural(here.size)} of this directory" +
          (if others > 0 then s" (${plural(others)} of other directories left running: `scalino daemon stop all`)" else ""))
      case List("stop", "all") =>
        val keys = liveKeys()
        keys.foreach(stop)
        println(s"stopped ${plural(keys.size)}")
      case other => ScalinoCli.die(s"daemon: unexpected arguments '${other.mkString(" ")}' -- usage: scalino daemon [status|stop [all]]")

  /** The daemons serving a classes directory under `dir`. */
  private def keysUnder(dir: Path): List[String] =
    val root = dir.toAbsolutePath.normalize
    liveKeys().filter { key =>
      val classes = try readFile(infoPath(key)).linesIterator.toList.lift(1) catch case _: java.io.IOException => None
      classes.exists(c => Paths.get(c).startsWith(root))
    }

  /** Stops the daemons serving a classes directory under `dir` (`scalino clean`). */
  def stopUnder(dir: Path): Unit = keysUnder(dir).foreach(stop)
