package org.scalajs.ir

/** Pure Scala SHA-1, replacing scalajs-ir's `java.security.MessageDigest` wrapper: javalib-native has
 *  no MessageDigest. build/04c-fetch-jslink-sources.sh copies this file over the original. The digest
 *  only versions IR trees, it is not used for anything security-relevant. */
object SHA1 {
  final class DigestBuilder {
    private val h = Array(0x67452301, 0xefcdab89, 0x98badcfe, 0x10325476, 0xc3d2e1f0)
    private val block = new Array[Byte](64)
    private var blockLen = 0
    private var totalLen = 0L
    private val w = new Array[Int](80)

    def update(b: Byte): Unit = {
      block(blockLen) = b
      blockLen += 1
      totalLen += 1
      if (blockLen == 64) processBlock()
    }

    def update(b: Array[Byte]): Unit =
      update(b, 0, b.length)

    def update(b: Array[Byte], off: Int, len: Int): Unit = {
      var i = off
      val end = off + len
      while (i < end) {
        update(b(i))
        i += 1
      }
    }

    def updateUTF8String(str: UTF8String): Unit =
      update(str.bytes)

    def finalizeDigest(): Array[Byte] = {
      val bitLen = totalLen * 8
      update(0x80.toByte)
      while (blockLen != 56) update(0.toByte)
      var i = 7
      while (i >= 0) {
        update((bitLen >>> (i * 8)).toByte)
        i -= 1
      }
      val out = new Array[Byte](20)
      i = 0
      while (i < 5) {
        out(i * 4) = (h(i) >>> 24).toByte
        out(i * 4 + 1) = (h(i) >>> 16).toByte
        out(i * 4 + 2) = (h(i) >>> 8).toByte
        out(i * 4 + 3) = h(i).toByte
        i += 1
      }
      out
    }

    private def processBlock(): Unit = {
      var t = 0
      while (t < 16) {
        w(t) = ((block(t * 4) & 0xff) << 24) | ((block(t * 4 + 1) & 0xff) << 16) |
          ((block(t * 4 + 2) & 0xff) << 8) | (block(t * 4 + 3) & 0xff)
        t += 1
      }
      while (t < 80) {
        w(t) = Integer.rotateLeft(w(t - 3) ^ w(t - 8) ^ w(t - 14) ^ w(t - 16), 1)
        t += 1
      }
      var a = h(0); var b = h(1); var c = h(2); var d = h(3); var e = h(4)
      t = 0
      while (t < 80) {
        val (f, k) =
          if (t < 20) ((b & c) | (~b & d), 0x5a827999)
          else if (t < 40) (b ^ c ^ d, 0x6ed9eba1)
          else if (t < 60) ((b & c) | (b & d) | (c & d), 0x8f1bbcdc)
          else (b ^ c ^ d, 0xca62c1d6)
        val tmp = Integer.rotateLeft(a, 5) + f + e + k + w(t)
        e = d; d = c; c = Integer.rotateLeft(b, 30); b = a; a = tmp
        t += 1
      }
      h(0) += a; h(1) += b; h(2) += c; h(3) += d; h(4) += e
      blockLen = 0
    }
  }
}
