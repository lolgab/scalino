// Links javalib's zlib binding (java.util.zip, `@link("z")`): a cross sysroot without zlib.h/libz.a fails to
// build this, which a program that never touches java.util.zip (examples/Hello.scala) does not notice.
import java.io.{ByteArrayInputStream, ByteArrayOutputStream}
import java.util.zip.{CRC32, GZIPInputStream, GZIPOutputStream}

@main def zipRoundTrip(): Unit =
  val data = ("hello zlib " * 200).getBytes("UTF-8")
  val bos = new ByteArrayOutputStream()
  val gz = new GZIPOutputStream(bos)
  gz.write(data)
  gz.close()
  val packed = bos.toByteArray
  val back = new GZIPInputStream(new ByteArrayInputStream(packed)).readAllBytes()
  val crc = new CRC32()
  crc.update(data)
  assert(java.util.Arrays.equals(back, data), "the gzip round trip changed the data")
  assert(packed.length < data.length, "gzip did not compress")
  println(s"zip ok: ${data.length} -> ${packed.length} bytes, crc32 ${crc.getValue}")
