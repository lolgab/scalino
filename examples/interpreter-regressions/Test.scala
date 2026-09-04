object Test extends App:
  val result = Foo.myMacro()
  assert(result == "labels=true thunk=42 tag=true ident=ok", s"unexpected: $result")
  println(result)
