import java.io.File
import java.nio.file.{FileVisitResult, Files, Path, SimpleFileVisitor}
import java.nio.file.attribute.BasicFileAttributes
import java.util.zip.ZipFile

// Descriptors that javalib used to leak, one per call: a ZipFile whose constructor throws (a directory, a file
// that is not a zip) and a walkFileTree stopped with TERMINATE. 20000 of either passed macOS's OPEN_MAX (10240),
// after which every posix_spawn failed with "Could not adddup2 pipe file descriptor 0 [errno=9]".
object Main:
  def fdCount(): Int = new File("/dev/fd").list().length

  def fail(msg: String): Nothing =
    System.err.println("FAIL: " + msg)
    sys.exit(1)

  def main(args: Array[String]): Unit =
    val dir = new File(args(0))
    val notZip = new File(dir, "not-a-zip.txt")
    Files.write(notZip.toPath, "hello, I am not a zip file".getBytes("UTF-8"))
    val tree = new File(dir, "tree")
    for i <- 0 until 5 do Files.createDirectories(new File(tree, s"d$i/sub").toPath)
    Files.write(new File(tree, "d0/sub/f").toPath, Array[Byte](1))

    fdCount() // the first listing may initialize things; measure from the second
    val before = fdCount()

    var rejected = 0
    for _ <- 0 until 20000 do
      try new ZipFile(dir).close()
      catch case _: java.io.IOException => rejected += 1
      try new ZipFile(notZip).close()
      catch case _: java.io.IOException => rejected += 1
    if rejected != 40000 then fail(s"expected 40000 ZipFile constructors to throw, $rejected did")
    val afterZip = fdCount()
    if afterZip != before then fail(s"ZipFile constructors that threw leaked ${afterZip - before} descriptors")

    for _ <- 0 until 20000 do
      Files.walkFileTree(tree.toPath, new SimpleFileVisitor[Path]:
        override def preVisitDirectory(p: Path, a: BasicFileAttributes) =
          if p.getFileName.toString == "sub" then FileVisitResult.TERMINATE else FileVisitResult.CONTINUE
      )
    for _ <- 0 until 2000 do
      try Files.walkFileTree(tree.toPath, new SimpleFileVisitor[Path]:
        override def preVisitDirectory(p: Path, a: BasicFileAttributes) =
          if p.getFileName.toString == "sub" then throw new IllegalStateException("stop") else FileVisitResult.CONTINUE
      )
      catch case _: IllegalStateException => ()
    val afterWalk = fdCount()
    if afterWalk != before then fail(s"walkFileTree stopped early leaked ${afterWalk - before} descriptors")

    val exit = new ProcessBuilder("/usr/bin/true").inheritIO().start().waitFor()
    if exit != 0 then fail(s"/usr/bin/true exited with $exit")
    println(s"OK: 40000 failed ZipFile constructors and 22000 aborted walkFileTree calls left $before descriptors open, spawn works")
