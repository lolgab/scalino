import scala.scalanative.build._
import scala.scalanative.util.Scope
import java.nio.file.Paths

object LinkDriver:
  /** `Logger.default` (used unconditionally before) always prints debug/trace
   *  (raw clang invocations, full NativeConfig dumps) to stderr, with no way
   *  to dial it down -- sn-cli now passes its own resolved log level as an
   *  optional 6th arg ("quiet"|"info"|"verbose", default "info") so `-v`/`-q`
   *  actually affect the link step, not just sn-cli's own output. */
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

  def main(args: Array[String]): Unit =
    val cp = args(0).split(":").toSeq.map(Paths.get(_))
    val workDir = Paths.get(args(1))
    val mainClass = args(2)
    val logLevel = if args.length > 5 then args(5) else "info"
    val logger = loggerFor(logLevel)

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
          // under real scala-cli on the same machine.
          .withLinkingOptions(Discover.linkingOptions())
          .withCompileOptions(Discover.compileOptions())
      )

    val outPath = Scope.apply[java.nio.file.Path] { (s: Scope) =>
      given Scope = s
      Build.buildCachedAwait(config)
    }
    logger.debug(s"LINKED: $outPath")
