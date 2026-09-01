import scala.scalanative.build._
import scala.scalanative.util.Scope
import java.nio.file.Paths

object LinkDriver:
  def main(args: Array[String]): Unit =
    val cp = args(0).split(":").toSeq.map(Paths.get(_))
    val workDir = Paths.get(args(1))
    val mainClass = args(2)

    val config = Config.empty
      .withBaseDir(workDir)
      .withModuleName(mainClass)
      .withMainClass(Some(mainClass))
      .withClassPath(cp)
      .withLogger(Logger.default)
      .withCompilerConfig(_.withClang(Paths.get(args(3))).withClangPP(Paths.get(args(4))))

    val outPath = Scope.apply[java.nio.file.Path] { (s: Scope) =>
      given Scope = s
      Build.buildCachedAwait(config)
    }
    println(s"LINKED: $outPath")
