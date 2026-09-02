object Test {
  def main(args: Array[String]): Unit = {
    def x = 2
    println(Macros.foo(1, 2, x))
  }
}
