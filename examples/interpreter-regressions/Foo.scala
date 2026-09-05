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

  // `var x: T = compiletime.uninitialized` (the 3.x replacement for `_`):
  // under `-Yretain-trees`, the real `UninitializedDefs` MiniPhase (which
  // normally rewrites this to the same placeholder plain `_` uses) hasn't
  // run yet by the time this interpreter walks the tree, so the ORIGINAL
  // `compiletime.uninitialized` call was left unrecognized -- genuinely
  // interpreting it (its real body is `throw new NotImplementedError(...)`,
  // a `@compileTimeOnly` marker never meant to actually run) instead of
  // treating it as "no initializer, use the zero value" like `_`. And that
  // zero value must be the STATIC TYPE's real default (`0` for `Int`), not
  // a blanket `null` -- `compiletime.uninitialized`'s whole point is
  // covering value types `_` alone couldn't.
  class Counted:
    private var n: Int = compiletime.uninitialized
    private var ref: String = compiletime.uninitialized
    def bump(): Int = { n += 1; n }
    def refIsNull: Boolean = ref == null
  def uninitializedVarsWork: Boolean =
    val c = new Counted
    c.bump() == 1 && c.refIsNull

  // A REAL host object's own uncurated method reading/mutating ITS OWN
  // private field: `ArrayBuffer`'s `update` has no curated intrinsic, so
  // its real method body is genuinely tree-interpreted with `this` bound to
  // the real host `ArrayBuffer` -- but that real object has no `.fields`
  // map, so a naive interpreter either replays the field's ORIGINAL
  // initializer forever (hiding real mutation already applied through a
  // curated intrinsic like `+=`, which calls the genuine host method) or
  // crashes outright trying to WRITE such a field (`ArrayBuffer#update`'s
  // own `mutationCount = mutationCount + 1` bookkeeping). Needs the real
  // field read/written via reflection instead.
  def hostObjectMutationVisible: Boolean =
    val buf = scala.collection.mutable.ArrayBuffer.empty[Int]
    buf += 1
    buf += 2
    buf.update(0, 99)
    buf(0) == 99 && buf.size == 2

  // `isInstanceOf`/pattern-typed-test precision, for the common real Scala
  // idiom `f match { case wrapped: SomeWrapper[..] => wrapped; case _ =>
  // Wrap(f) }` (an unchecked cast testing "is this plain function value
  // ALREADY the wrapped type") -- cats' own `AndThen.apply`/`#andThen` is
  // this exact shape. A bare closure (never itself constructed via
  // `interpretNew`) isn't a `ModuleValue`/`InterpretedInstance`/collection
  // either, so it fell through to the SAME lenient "assume true" fallback
  // as a genuinely-unrecognized real host object -- wrongly matching, and
  // skipping the real `Single(f)` wrapping a fresh `Comp` needs. The
  // unwrapped closure then flowed downstream wherever a real `Single` was
  // expected, and a later exhaustive pattern match over the real subtypes
  // threw a spurious `MatchError`.
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

  // `java.lang.String#regionMatches`: a real JDK method with no retained
  // Scala source (no built-in intrinsic existed for it at all before).
  def regionMatchesWorks: Boolean =
    "Hello World".regionMatches(6, "World", 0, 5)
      && !"Hello World".regionMatches(6, "Word", 0, 4)
      && "HELLO".regionMatches(true, 0, "hello", 0, 5)

  // `java.lang.IndexOutOfBoundsException`: a real JDK exception class with
  // no retained Scala source, and (unlike `IllegalArgumentException`/
  // `NoSuchElementException`/etc) no curated constructor before -- genuinely
  // thrown as part of normal control flow by real stdlib/library code (e.g.
  // `ArrayBuffer#checkWithinBounds`), not just an "unreachable" fallback.
  def indexOutOfBoundsConstructible: Boolean =
    try
      throw new IndexOutOfBoundsException("test")
      false
    catch case e: IndexOutOfBoundsException => e.getMessage == "test"

  // A top-level `val`'s real JVM semantics: computed once, every later read
  // returns that SAME instance. Re-interpreting the initializer tree on
  // every access instead built a FRESH value each time -- third-party
  // library internals routinely rely on `eq` (physical identity) against a
  // cached top-level `val` as a fast-path sentinel (e.g. cats-parse's own
  // `Parser.unit`), which a fresh instance on every read always fails.
  object Sentinel:
    val unit: Object = new Object()
  def staticFieldIdentityStable: Boolean =
    Sentinel.unit eq Sentinel.unit

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
    s"labels=$labels thunk=$thunk tag=$tag ident=$ident uninit=$uninit hostMut=$hostMut wrap=$wrap region=$region ioobe=$ioobe identity=$identity"

object Foo:
  inline def myMacro(): String = ${ aMacroImplementation }
  def aMacroImplementation(using Quotes): Expr[String] = Expr(Regressions.summary)
