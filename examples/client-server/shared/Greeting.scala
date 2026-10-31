// Compiled into both the server (Scala Native) and the client (Scala.js): plain Scala, no platform APIs.
object Greeting:
  def apply(name: String): String = s"Hello, $name!"
  val numbers: List[Int] = List(1, 2, 3).map(_ * 2)
