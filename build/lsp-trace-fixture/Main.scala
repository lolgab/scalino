@main def run(): Unit =
  val a = Point(0, 0)
  val b = Point(3, 4)
  val d = Point.distance(a, b)
  println(s"distance = $d")

  val g = Greeter("world")
  println(g.greet())

  val o = Point.origin
  println(o)
