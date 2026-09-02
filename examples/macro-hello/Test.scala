//> using dep com.github.plokhotnyuk.jsoniter-scala::jsoniter-scala-core::2.40.1
//> using compileOnly.dep com.github.plokhotnyuk.jsoniter-scala::jsoniter-scala-macros::2.40.1

import com.github.plokhotnyuk.jsoniter_scala.macros._
import com.github.plokhotnyuk.jsoniter_scala.core._

case class Device(id: Int, model: String)

case class User(name: String, devices: Seq[Device])

given userCodec: JsonValueCodec[User] = JsonCodecMaker.make

object Test extends App {
  val user = readFromString[User]("""{"name":"John","devices":[{"id":1,"model":"HTC One X"}]}""")
  val json = writeToString(User("John", Seq(Device(2, "iPhone X"))))
}
