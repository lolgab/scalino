// The browser side: a Scala.js project, linked as a classic script (served as /client/main.js).
import scala.scalajs.js

object Client:
  def main(args: Array[String]): Unit =
    val document = js.Dynamic.global.document
    document.getElementById("app").innerHTML = s"<h1>${Greeting("Scala.js")}</h1><p>${Greeting.numbers.mkString(", ")}</p>"
