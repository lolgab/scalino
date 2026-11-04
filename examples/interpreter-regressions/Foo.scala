import scala.quoted.*

// Regression coverage for Interpreter.scala bugs found via real third-party macros (ip4s, cats,
// http4s). Each case runs at macro expansion time (interpreted, not compiled), like the macro
// that hit it. See docs/findings.md.
object Regressions:

  // `scala.util.matching.Regex#unapplySeq`'s varargs pattern (`case Pattern(_*) => ...`) with
  // `String#split` returning a real `Array[String]` (not an eager `Seq`, which crashed in
  // `ArrayOps` interpretation on `.iterator`); ip4s' `Hostname.fromString` has this shape.
  private val LabelPattern = "[a-zA-Z0-9](?:[a-zA-Z0-9\\-]*[a-zA-Z0-9])?".r
  def allLabelsValid(hostname: String): Boolean =
    hostname.split('.').iterator.forall {
      case LabelPattern(_*) => true
      case _ => false
    }

  // `new Base(() => a) {}`: the real constructor argument sits in the superclass call in the
  // synthetic `$anon` class's `Template.parents`, possibly deep inside a method body (cats'
  // `Eval.defer`).
  sealed abstract class Thunked[A](val thunk: () => A)
  def runThunk[A](a: => A): A =
    val wrapped = new Thunked[A](() => a) {}
    wrapped.thunk()

  // `scala.runtime.ClassValueCompat`'s abstract `computeValue`: only a module overriding it
  // (e.g. `ClassTag`'s private `cache`) gives it a body; any `ClassTag` construction exercises it.
  def classTagWorks[A](using ct: scala.reflect.ClassTag[A]): Boolean =
    ct.runtimeClass != null

  // A user-defined `case class Ident` collides by bare name with the curated
  // `quotes.reflect.Ident` extractor (cats' private `Eval.Ident[A, B]`).
  case class Ident[A, B](ev: A <:< B)
  def viaIdent[A](x: A): A =
    (Ident(implicitly[A <:< A]): Ident[A, A]) match
      case Ident(ev) => ev(x)

  // `var x: T = compiletime.uninitialized`: under `-Yretain-trees` the `UninitializedDefs`
  // MiniPhase has not rewritten it yet, so the interpreter ran its `@compileTimeOnly` body
  // (`throw NotImplementedError`). It must mean "no initializer" with the static type's default
  // (`0` for `Int`), not a blanket `null`.
  class Counted:
    private var n: Int = compiletime.uninitialized
    private var ref: String = compiletime.uninitialized
    def bump(): Int = { n += 1; n }
    def refIsNull: Boolean = ref == null
  def uninitializedVarsWork: Boolean =
    val c = new Counted
    c.bump() == 1 && c.refIsNull

  // A real host object's uncurated method reading/mutating its own private field (`ArrayBuffer`'s
  // `update`, incl. its `mutationCount` bookkeeping): the body is tree-interpreted with `this` bound
  // to the host object, which has no `.fields` map, so the field must be read/written via
  // reflection (else the original initializer is replayed or the write crashes).
  def hostObjectMutationVisible: Boolean =
    val buf = scala.collection.mutable.ArrayBuffer.empty[Int]
    buf += 1
    buf += 2
    buf.update(0, 99)
    buf(0) == 99 && buf.size == 2

  // `isInstanceOf`/typed-pattern precision for the idiom `f match { case wrapped: SomeWrapper[..]
  // => wrapped; case _ => Wrap(f) }` (cats' `AndThen.apply`/`#andThen`). A bare closure, never
  // built via `interpretNew`, fell through to the lenient "assume true" fallback, skipped the
  // `Single(f)` wrapping, and later caused a spurious `MatchError`.
  sealed abstract class Comp[-A, +B] extends (A => B):
    def apply(a: A): B = this match
      case Comp.Single(f) => f(a)
  object Comp:
    private case class Single[-A, +B](f: A => B) extends Comp[A, B]
    def apply[A, B](f: A => B): Comp[A, B] = f match
      case c: Comp[A, B] @unchecked => c
      case _ => Single(f)
  def bareClosureWrappingWorks: Boolean =
    Comp((x: Int) => x + 1)(5) == 6

  // `java.lang.String#regionMatches`: a JDK method with no retained Scala source or intrinsic.
  def regionMatchesWorks: Boolean =
    "Hello World".regionMatches(6, "World", 0, 5)
      && !"Hello World".regionMatches(6, "Word", 0, 4)
      && "HELLO".regionMatches(true, 0, "hello", 0, 5)

  // `java.lang.IndexOutOfBoundsException`: a JDK exception with no retained Scala source and no
  // curated constructor, thrown in normal control flow by stdlib code (e.g.
  // `ArrayBuffer#checkWithinBounds`).
  def indexOutOfBoundsConstructible: Boolean =
    try
      throw new IndexOutOfBoundsException("test")
      false
    catch case e: IndexOutOfBoundsException => e.getMessage == "test"

  // A top-level `val` is computed once and every read returns the same instance. Re-interpreting
  // the initializer built a fresh value per access, which breaks library `eq` fast-paths against
  // a cached top-level `val` (e.g. cats-parse's `Parser.unit`).
  object Sentinel:
    val unit: Object = new Object()
  def staticFieldIdentityStable: Boolean =
    Sentinel.unit eq Sentinel.unit

  // `Set#map` on a host `HashSet` (5+ elements; smaller sets are `Set1`..`Set4`) rebuilds through
  // `HashSet.newBuilder`; it used to yield a hybrid interpreted `HashSet` with the right `size` but
  // an empty iterator.
  def hashSetMapIterates: Boolean =
    val mapped = (1 to 40).toSet.map(_ + 1)
    mapped.size == 40 && mapped.toList.size == 40 && mapped.contains(41) &&
      Set(Seq("a"), Seq("b"), Seq("c", "d"), Seq("e"), Seq("f")).map(_.toList).toList.size == 5

  // JDK classes with no TASTy of their own (`java.util.StringJoiner`,
  // `Optional`, `UUID`, `java.net.URI`) run from scala-native's javalib TASTy
  // (`dist/lib/javalib-tasty.jar`, Interpreter's `JavalibOverlay`) instead of
  // needing a hand-written intrinsic per method: constructors, instance and
  // static methods, javalib-private helper classes (`URIEncoderDecoder`) and
  // a local-def `return` (`URI`'s parser) all go through the real bodies.
  def javalibBodies: Boolean =
    val joiner = new java.util.StringJoiner(", ", "[", "]")
    joiner.add("a").add("b")
    val uri = java.net.URI.create("http://a.b/c?d=1#e")
    joiner.toString == "[a, b]" && joiner.length == 6 &&
      java.util.Optional.of("x").map(_.toUpperCase).orElse("n") == "X" &&
      java.util.UUID.fromString("123e4567-e89b-12d3-a456-426614174000").toString == "123e4567-e89b-12d3-a456-426614174000" &&
      uri.getHost == "a.b" && uri.getPath == "/c" && uri.getQuery == "d=1" && uri.getFragment == "e"

  def summary: String =
    val labels = allLabelsValid("host-1.example.com")
    val thunk = runThunk(21 + 21)
    val tag = classTagWorks[String]
    val ident = viaIdent("ok")
    val uninit = uninitializedVarsWork
    val hostMut = hostObjectMutationVisible
    val wrap = bareClosureWrappingWorks
    val region = regionMatchesWorks
    val ioobe = indexOutOfBoundsConstructible
    val identity = staticFieldIdentityStable
    val hashSetMap = hashSetMapIterates
    val javalib = javalibBodies
    s"labels=$labels thunk=$thunk tag=$tag ident=$ident uninit=$uninit hostMut=$hostMut wrap=$wrap region=$region ioobe=$ioobe identity=$identity hashSetMap=$hashSetMap javalib=$javalib"

object Foo:
  inline def myMacro(): String = ${ aMacroImplementation }
  def aMacroImplementation(using Quotes): Expr[String] = Expr(Regressions.summary)
