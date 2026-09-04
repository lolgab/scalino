/** A simple point in 2D space. */
case class Point(x: Int, y: Int)

/** Companion object with helper constructors and math. */
object Point:
  /** The origin point, (0, 0). */
  val origin: Point = Point(0, 0)

  /** Computes the Euclidean distance between two points. */
  def distance(a: Point, b: Point): Double =
    val dx = (a.x - b.x).toDouble
    val dy = (a.y - b.y).toDouble
    math.sqrt(dx * dx + dy * dy)
