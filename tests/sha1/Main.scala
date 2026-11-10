// Prints the SHA-1 of each file argument, hashed through every way tools/jslink/SHA1.scala can be fed
// (whole array, slices of odd sizes, byte by byte, a mix), one line per file: "<hex> <path>".
// tests/sha1/run.sh compares them with `shasum -a 1`.
package check

import org.scalajs.ir.SHA1

object Sha1Check {
  private def hex(a: Array[Byte]): String = a.map(b => f"${b & 0xff}%02x").mkString

  @main def main(paths: String*): Unit = {
    for (path <- paths) {
      val data = java.nio.file.Files.readAllBytes(java.nio.file.Paths.get(path))
      def digest(feed: SHA1.DigestBuilder => Unit): String = {
        val d = new SHA1.DigestBuilder; feed(d); hex(d.finalizeDigest())
      }
      val whole = digest(_.update(data))
      def sliced(size: Int) = digest { d =>
        var o = 0
        while (o < data.length) { val n = math.min(size, data.length - o); d.update(data, o, n); o += n }
      }
      val results = Seq(
        whole,
        sliced(1), sliced(7), sliced(63), sliced(64), sliced(65), sliced(1000),
        digest(d => data.foreach(b => d.update(b))),
        digest { d => // bytes, then slices, then bytes: crosses the buffered-tail paths in both directions
          var o = 0; var k = 0
          while (o < data.length) {
            val n = math.min(Seq(1, 3, 61, 64, 130)(k % 5), data.length - o)
            if (n == 1) d.update(data(o)) else d.update(data, o, n)
            o += n; k += 1
          }
        }
      )
      println(s"${if (results.distinct.size == 1) results.head else "INCONSISTENT:" + results.mkString(",")} $path")
    }
  }
}
