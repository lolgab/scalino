// A client that connects with a timeout to a server that writes right after accept: the client's socket is
// already readable when its non-blocking connect is polled, which javalib once reported as "Connection refused".
// Also checks that a real refusal is still a ConnectException.
import java.net.{ConnectException, InetAddress, InetSocketAddress, ServerSocket, Socket}

@main def connectWithData(): Unit =
  val lo = InetAddress.getLoopbackAddress()
  val server = new ServerSocket(0, 50, lo)
  val port = server.getLocalPort()
  var serverError: Throwable = null
  val serverThread = new Thread(() =>
    try
      val peer = server.accept()
      try
        peer.getOutputStream().write("hello".getBytes("UTF-8"))
        peer.getOutputStream().flush()
        peer.getInputStream().read() // hold the connection until the client has read
      finally peer.close()
    catch case e: Throwable => serverError = e
  )
  serverThread.start()

  val s = new Socket()
  try
    s.connect(new InetSocketAddress(lo, port), 5000)
    val buf = new Array[Byte](5)
    var n = 0
    while n < buf.length do
      val r = s.getInputStream().read(buf, n, buf.length - n)
      assert(r > 0, "unexpected end of stream")
      n += r
    assert(new String(buf, "UTF-8") == "hello", "unexpected payload")
  finally
    s.close()
    server.close()
  serverThread.join(5000)
  assert(serverError == null, s"server failed: $serverError")

  val refused = new Socket()
  try
    try
      refused.connect(new InetSocketAddress(lo, port), 5000)
      assert(false, "connect to a closed port succeeded")
    catch case _: ConnectException => ()
  finally refused.close()
  println("connect ok")
