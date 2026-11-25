//> using dep com.github.plokhotnyuk.jsoniter-scala::jsoniter-scala-core::2.38.2
//> using dep com.github.plokhotnyuk.jsoniter-scala::jsoniter-scala-macros::2.38.2

import com.github.plokhotnyuk.jsoniter_scala.core.*
import com.github.plokhotnyuk.jsoniter_scala.macros.*

case class Item(name: String, tags: List[String], price: Double, count: Long, data: Array[Byte])
case class Doc(id: Long, items: Vector[Item], note: Option[String])

given JsonValueCodec[Doc] = JsonCodecMaker.make

def same(a: Doc, b: Doc): Boolean =
  a.id == b.id && a.note == b.note &&
    a.items.map(i => (i.name, i.tags, i.price, i.count, i.data.toSeq)) ==
      b.items.map(i => (i.name, i.tags, i.price, i.count, i.data.toSeq))

@main def json(): Unit =
  // Long strings and numbers go through ByteArrayAccess (8 bytes at a time
  // through a raw pointer into the byte array)
  val doc = Doc(
    42,
    Vector.tabulate(300)(i =>
      Item(s"item-$i ünï € " + "abcdefgh" * (i % 40), List("a", "b" * i), i * 1.5, Long.MaxValue - i, Array.tabulate(i % 17)(_.toByte))
    ),
    Some("quote \" backslash \\ newline \n tab \t")
  )
  val bytes = writeToArray(doc)
  val fromBytes = readFromArray[Doc](bytes)
  val text = writeToString(doc)
  val fromText = readFromString[Doc](text)
  // (empty collections are left out by default)
  val expected = """{"id":42,"items":[{"name":"item-0 ünï € ","tags":["a",""],"price":0.0,"count":9223372036854775807},{"name":"item-1 ünï € abcdefgh","tags":["a","b"],"price":1.5,"count":9223372036854775806,"data":[0]}"""
  if (!same(doc, fromBytes)) throw new AssertionError("byte array round trip")
  if (!same(doc, fromText)) throw new AssertionError("string round trip")
  if (new String(bytes, "UTF-8") != text) throw new AssertionError("bytes and string differ")
  if (!text.startsWith(expected)) throw new AssertionError(s"unexpected JSON: ${text.take(200)}")
  println(s"${bytes.length} bytes, ALL OK")
