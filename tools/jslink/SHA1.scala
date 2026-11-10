package org.scalajs.ir

/** Pure Scala SHA-1, replacing scalajs-ir's `java.security.MessageDigest` wrapper: javalib-native has
 *  no MessageDigest. build/04c-fetch-jslink-sources.sh copies this file over the original. The digest
 *  only versions IR trees, it is not used for anything security-relevant.
 *
 *  Whole blocks are hashed straight from the caller's array (only the tail of a block is copied), each
 *  group of 20 rounds has its own loop (no per-round tuple of function and constant), and the state is
 *  in plain fields rather than an array: tests/sha1/run.sh checks it against
 *  `shasum`. */
object SHA1 {
  final class DigestBuilder {
    private var h0 = 0x67452301
    private var h1 = 0xefcdab89
    private var h2 = 0x98badcfe
    private var h3 = 0x10325476
    private var h4 = 0xc3d2e1f0
    private val block = new Array[Byte](64)
    private var blockLen = 0
    private var totalLen = 0L
    private val w = new Array[Int](80)

    def update(b: Byte): Unit = {
      block(blockLen) = b
      blockLen += 1
      totalLen += 1
      if (blockLen == 64) {
        processBlock(block, 0)
        blockLen = 0
      }
    }

    def update(b: Array[Byte]): Unit =
      update(b, 0, b.length)

    def update(b: Array[Byte], off: Int, len: Int): Unit = {
      totalLen += len
      var o = off
      var left = len
      if (blockLen > 0) {
        val n = math.min(64 - blockLen, left)
        System.arraycopy(b, o, block, blockLen, n)
        blockLen += n
        o += n
        left -= n
        if (blockLen == 64) {
          processBlock(block, 0)
          blockLen = 0
        }
      }
      while (left >= 64) {
        processBlock(b, o)
        o += 64
        left -= 64
      }
      if (left > 0) {
        System.arraycopy(b, o, block, 0, left)
        blockLen = left
      }
    }

    def updateUTF8String(str: UTF8String): Unit =
      update(str.bytes)

    def finalizeDigest(): Array[Byte] = {
      val bitLen = totalLen * 8
      val padding = new Array[Byte](
        (if (blockLen < 56) 56 - blockLen else 120 - blockLen) + 8
      )
      padding(0) = 0x80.toByte
      var i = 0
      while (i < 8) {
        padding(padding.length - 1 - i) = (bitLen >>> (i * 8)).toByte
        i += 1
      }
      update(padding, 0, padding.length)
      val h = Array(h0, h1, h2, h3, h4)
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

    private def processBlock(src: Array[Byte], off: Int): Unit = {
      val w = this.w
      var t = 0
      while (t < 16) {
        val j = off + (t << 2)
        w(t) = ((src(j) & 0xff) << 24) | ((src(j + 1) & 0xff) << 16) |
          ((src(j + 2) & 0xff) << 8) | (src(j + 3) & 0xff)
        t += 1
      }
      while (t < 80) {
        val v = w(t - 3) ^ w(t - 8) ^ w(t - 14) ^ w(t - 16)
        w(t) = (v << 1) | (v >>> 31)
        t += 1
      }
      var a = h0; var b = h1; var c = h2; var d = h3; var e = h4
      t = 0
      while (t < 20) {
        val tmp = ((a << 5) | (a >>> 27)) + ((b & c) | (~b & d)) + e + 0x5a827999 + w(t)
        e = d; d = c; c = (b << 30) | (b >>> 2); b = a; a = tmp
        t += 1
      }
      while (t < 40) {
        val tmp = ((a << 5) | (a >>> 27)) + (b ^ c ^ d) + e + 0x6ed9eba1 + w(t)
        e = d; d = c; c = (b << 30) | (b >>> 2); b = a; a = tmp
        t += 1
      }
      while (t < 60) {
        val tmp = ((a << 5) | (a >>> 27)) + ((b & c) | (b & d) | (c & d)) + e + 0x8f1bbcdc + w(t)
        e = d; d = c; c = (b << 30) | (b >>> 2); b = a; a = tmp
        t += 1
      }
      while (t < 80) {
        val tmp = ((a << 5) | (a >>> 27)) + (b ^ c ^ d) + e + 0xca62c1d6 + w(t)
        e = d; d = c; c = (b << 30) | (b >>> 2); b = a; a = tmp
        t += 1
      }
      h0 += a; h1 += b; h2 += c; h3 += d; h4 += e
    }
  }
}
