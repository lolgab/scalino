//> using platform js
import scala.scalajs.js
import scala.scalajs.js.|

object App:
  def main(args: Array[String]): Unit =
    val f: js.Function1[Int, Int] = x => x + 1
    val u: js.UndefOr[Int] = 1
    val either: Int | String = "a"
    val opt: Option[Int] = None
    println(f(1) + u.getOrElse(0) + either.toString.length)
