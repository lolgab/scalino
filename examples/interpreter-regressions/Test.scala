object Test extends App:
  val result = Foo.myMacro()
  assert(
    result == "labels=true thunk=42 tag=true ident=ok uninit=true hostMut=true wrap=true region=true ioobe=true identity=true",
    s"unexpected: $result"
  )
  println(result)
