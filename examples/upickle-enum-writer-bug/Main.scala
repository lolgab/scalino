import domain.*

// Still-open gap, not wired into CI (fails); see docs/findings.md item 10 "still-open gap".
// `domain/*.scala` are `~/scala/ape`'s real files, copied verbatim; no db/http/porcupine/
// cats-effect involved, only `derives ReadWriter` on an `enum` with more than one case.
//
// Every enum here serializes only its ordinal-0 case correctly; every other case throws
// `scala.MatchError: null` in `upickle.core.Types$TaggedWriter.write0`. Ruled out: default
// parameters, nested case classes, field position/count in `BuildingInput`; an owner-chain/
// hygiene bug (SCALINO_INTERP_DEBUG `[spliceOwner]`/`[newVal]` traces show consistent symbol
// identity); a general Scala Native local-implicit-lazy-val-in-a-Block codegen bug (a
// hand-written control with the same shape works).
object Main:
  def main(args: Array[String]): Unit =
    def tryWrite[T: upickle.default.Writer](name: String, v: T): Unit =
      try println(s"$name = ${upickle.default.write(v)}")
      catch case e: Throwable => println(s"$name FAILED: $e")

    println("--- TipoGenerazione: only ordinal 0 (CaldaiaStandard) should work, but doesn't yet ---")
    TipoGenerazione.values.foreach(v => tryWrite(s"TipoGenerazione.$v (ord=${v.ordinal})", v))

    println("--- ContestoUrbano: same pattern ---")
    ContestoUrbano.values.foreach(v => tryWrite(s"ContestoUrbano.$v (ord=${v.ordinal})", v))
