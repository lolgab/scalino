import domain.*

// Still-open gap, NOT wired into CI (still failing) -- see docs/findings.md
// item 10's "still-open gap" section for the full writeup. `domain/*.scala`
// here are `~/scala/ape`'s real files, copied verbatim (that's where this
// was originally found) -- no db/http/porcupine/cats-effect involved, this
// is purely about `derives ReadWriter` on a `enum` with more than one case.
//
// Every enum here serializes ONLY its ordinal-0 (first-declared) case
// correctly; every other case throws `scala.MatchError: null` inside
// `upickle.core.Types$TaggedWriter.write0`. Confirmed NOT related to
// default parameters, nested case classes, or field position/count in
// `BuildingInput` (the shape that originally surfaced this) -- those were
// red herrings. Confirmed NOT an owner-chain/hygiene bug (unlike the
// separate, already-tracked `x.addOne`/`LambdaLift` gap) via `SCALINO_INTERP
// _DEBUG`-traced `[spliceOwner]`/`[newVal]` prints showing consistent
// symbol identity across all per-case vals. Confirmed NOT a general
// Scala Native local-implicit-lazy-val-in-a-Block codegen bug via a
// hand-written control that works correctly with the same shape.
object Main:
  def main(args: Array[String]): Unit =
    def tryWrite[T: upickle.default.Writer](name: String, v: T): Unit =
      try println(s"$name = ${upickle.default.write(v)}")
      catch case e: Throwable => println(s"$name FAILED: $e")

    println("--- TipoGenerazione: only ordinal 0 (CaldaiaStandard) should work, but doesn't yet ---")
    TipoGenerazione.values.foreach(v => tryWrite(s"TipoGenerazione.$v (ord=${v.ordinal})", v))

    println("--- ContestoUrbano: same pattern ---")
    ContestoUrbano.values.foreach(v => tryWrite(s"ContestoUrbano.$v (ord=${v.ordinal})", v))
