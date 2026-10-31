import scala.scalajs.js.|

object Union:
  // With -scalajs, `Int | Int` is a plain Scala 3 union (just Int), not js.|'s class, so it has no `merge`.
  // The error below is what shows the Scala.js platform (SJSPlatform, Scala2Unpickler) is active in the LSP.
  val noMerge: Int = (1: Int | Int).merge
