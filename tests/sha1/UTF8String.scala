// Stand-in for scalajs-ir's UTF8String, the one type tools/jslink/SHA1.scala needs from the rest of the library.
package org.scalajs.ir

final class UTF8String(val bytes: Array[Byte])
