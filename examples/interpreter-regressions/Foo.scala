import scala.quoted.*

// Regression coverage for a handful of own-implementation Interpreter.scala
// bugs found via real third-party macros (ip4s, cats, http4s) and fixed the
// same session -- each one runs here at MACRO EXPANSION time (interpreted,
// not compiled bytecode), same as the real-world macro that originally hit
// it. See docs/findings.md for the full writeup of each.
object Regressions:

  // `scala.util.matching.Regex#unapplySeq`'s own varargs/`_*` pattern shape
  // (`case Pattern(_*) => ...`), combined with `String#split` returning a
  // real `Array[String]` (not an eagerly-built `Seq`, which crashed deeper
  // inside real `ArrayOps` interpretation the first time `.iterator` was
  // called on it) -- ip4s's own `Hostname.fromString` is this exact shape.
  private val LabelPattern = "[a-zA-Z0-9](?:[a-zA-Z0-9\\-]*[a-zA-Z0-9])?".r
  def allLabelsValid(hostname: String): Boolean =
    hostname.split('.').iterator.forall {
      case LabelPattern(_*) => true
      case _ => false
    }

  // `new Base(() => a) {}` (anonymous-subclass instantiation): the real
  // constructor argument lives in the SUPERCLASS constructor call, in the
  // synthetic `$anon` class's own `Template.parents` -- which can be
  // declared arbitrarily deep inside a method body (not just at the top
  // level of a file), same shape as cats' own `Eval.defer`.
  sealed abstract class Thunked[A](val thunk: () => A)
  def runThunk[A](a: => A): A =
    val wrapped = new Thunked[A](() => a) {}
    wrapped.thunk()

  // `scala.runtime.ClassValueCompat`'s abstract `computeValue` -- only ever
  // gets a real body from a module overriding it (e.g. `scala.reflect
  // .ClassTag`'s own private `cache` object); exercised by any real
  // `ClassTag` construction.
  def classTagWorks[A](using ct: scala.reflect.ClassTag[A]): Boolean =
    ct.runtimeClass != null

  // A user-defined `case class Ident` collides by bare name with the
  // curated `quotes.reflect.Ident` extractor (matched by name only) --
  // cats' own private `Eval.Ident[A, B](ev: A <:< B)` is this exact shape.
  case class Ident[A, B](ev: A <:< B)
  def viaIdent[A](x: A): A =
    (Ident(implicitly[A <:< A]): Ident[A, A]) match
      case Ident(ev) => ev(x)

  def summary: String =
    val labels = allLabelsValid("host-1.example.com")
    val thunk = runThunk(21 + 21)
    val tag = classTagWorks[String]
    val ident = viaIdent("ok")
    s"labels=$labels thunk=$thunk tag=$tag ident=$ident"

object Foo:
  inline def myMacro(): String = ${ aMacroImplementation }
  def aMacroImplementation(using Quotes): Expr[String] = Expr(Regressions.summary)
