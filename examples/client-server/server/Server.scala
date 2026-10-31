// The server side: a Scala Native program that serves the Scala.js client built from ../client.
//
//   cd examples/client-server && scalino run server
//   open http://localhost:8080
//
// `scalino package server -o app` links the client in release mode and embeds it in the binary.
// ../shared is compiled into both sides (`//> using shared`).
//> using jsClient ../client
//> using shared ../shared
import java.net.ServerSocket

object Server:
  private val page =
    """<!doctype html><meta charset="utf-8"><title>scalino</title><div id="app"></div><script src="/client/main.js"></script>"""

  private def resource(path: String): Option[Array[Byte]] =
    Option(getClass.getResourceAsStream(path)).map(_.readAllBytes())

  def main(args: Array[String]): Unit =
    val port = args.headOption.map(_.toInt).getOrElse(8080)
    // a second argument limits the number of requests served (handy for scripted checks)
    var left = args.lift(1).map(_.toInt).getOrElse(Int.MaxValue)
    val server = new ServerSocket(port)
    println(s"${Greeting("Scala Native")} listening on http://localhost:$port")
    while left > 0 do
      left -= 1
      val socket = server.accept()
      try
        val in = new java.io.BufferedReader(new java.io.InputStreamReader(socket.getInputStream))
        val target = Option(in.readLine()).map(_.split(" ")).filter(_.length > 1).map(_(1)).getOrElse("/")
        Iterator.continually(in.readLine()).takeWhile(l => l != null && l.nonEmpty).foreach(_ => ())
        val (status, contentType, body) = target match
          case "/" => ("200 OK", "text/html", page.getBytes("UTF-8"))
          case p if p.startsWith("/client/") =>
            resource(p) match
              case Some(bytes) => ("200 OK", if p.endsWith(".map") then "application/json" else "text/javascript", bytes)
              case None => ("404 Not Found", "text/plain", "not found".getBytes("UTF-8"))
          case _ => ("404 Not Found", "text/plain", "not found".getBytes("UTF-8"))
        val out = socket.getOutputStream
        out.write(s"HTTP/1.1 $status\r\nContent-Type: $contentType\r\nContent-Length: ${body.length}\r\nConnection: close\r\n\r\n".getBytes("UTF-8"))
        out.write(body)
        out.flush()
      finally socket.close()
