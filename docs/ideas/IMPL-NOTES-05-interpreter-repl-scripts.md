# 05 — interpreter-backed `scalino repl`, scripts and `run --interpret`

Status: implemented and verified end to end with binaries rebuilt from this worktree (2026-11-21).
Branch `impl/05-interpreter-repl-scripts`, not pushed.

## What was done

- `patches/scala3-0051-interpreter-run-and-repl.patch` (new; vendor edits regenerated into it):
  - `dotc/ScalinoInterpret.scala`: `scalino-dotc --interpret [--fallback-exit] <main> <classesDir> <opts> [-- args]`.
    Reads the program's `.tasty` (the normal compile output, so the CLI's incremental cache and
    the dotc daemon are reused unchanged) through a `-from-tasty` run of
    `readTasty, inlining, postInlining` + a phase installing the post-inlining trees as `defTree`s /
    root trees; pre-scan for native-only code (`scala.scalanative.unsafe/runtime/posix/libc/...`,
    `@extern`, `Thread`, `java.util.concurrent`, `Future`/`Promise`/`ExecutionContext`); then
    `Interpreter#runMain`. Exit codes: program's, 1 uncaught exception (message + interpreted
    call stack), 2 usage/compile error, 3 = "link it instead" (only with `--fallback-exit`).
  - `dotc/ScalinoRepl.scala`: `scalino-dotc --repl [--interactive] [--init-script c] [--quit-after-init] <opts>`.
    One `ContextBase` + one `Compiler`, a `Run` per input (`parser, typer, postTyper, inlining,
    postInlining`, install). Inputs wrapped as `import rs$line1.{*, given}; ...; object rs$lineN {..}`,
    expression statements become `val resK = ...`; statements interpreted in order with a shared
    `Interpreter.Session`; prints `val x: T = v`, `def f(...): T`, `// defined class C`, like the stock
    REPL. Commands `:help :quit :type :imports :reset :load`. Multi-line input via parser
    "incomplete at EOF" detection. Prompts only with `--interactive` (the CLI passes it when stdin
    is a tty: this javalib has no `System.console`).
  - `quoted/Interpreter.scala`: `runMain`, `evalStatement`, `Session` (module vals/vars shared
    across interpreters), `programMode` host stand-ins (`Console.out/err/in`, `StdIn.read*`,
    `sys.exit`/`System.exit` -> `ProgramExit`), all parent-constructor calls run in program mode,
    JDK-exception subclasses (`throwableCall`, message/cause captured from the super call),
    interpreted `toString` for host printing (`InterpretedInstance.render`), interpreted call stack
    on errors (`stopStack`, `lastThrowStack`). Gaps closed: `StringBuilder#underlying`,
    `Array#clone`, `f"..."`, `java.lang.Math` (`HostValueCalls.math`), `Math.PI/E`, host
    `copyToArray`, secondary constructors with locals, many more JDK exception classes,
    `Int + String`.
- `cli/ScalinoCli.scala`: `scalino repl [sources] [--dep..] [-O..] [--repl-init-script c]
  [--repl-quit-after-init]` (also `console`); `run --interpret` (compile as usual, then
  `scalino-dotc --interpret`; exit 3 -> "Linking it instead" and the normal link path; under `-w`
  no fallback); `.sc` scripts given as file args, wrapped to
  `.scalino-build/scripts/<name>_sc.scala` (`object <name>_sc { def main(args) = { <script> }}`,
  header on line 1 so line numbers match; diagnostics have the wrapper path mapped back to the
  script; directives are read from the script itself). Help, completions.
- `tests/interpreter/run.sh`, README (`REPL and the interpreter`), `docs/findings.md` section.

## How it was verified

Toolchain built from this worktree: `build/00b-setup-vendor.sh` (own vendor/), `build/02a` (JVM
bootstrap for the dev loop), `SCALINO_LINK_MODE=release-fast build/03-build-scalino-dotc.sh`
(smoke test OK), `build/07-build-scalino.sh`. `dist/` started as an APFS clone of main's dist.

```
$ tests/interpreter/run.sh
ok: --interpret prints what the linked binary prints     (stdout + exit code 4 identical)
ok: uncaught exception
ok: native-only code is linked instead
ok: .sc scripts
ok: repl
ok: repl with sources and an init script
PASS
```

Edit-run latency, `H.scala` one-liner, daemon off, after one warm-up each, editing the source
between runs (`/usr/bin/time -p dist/scalino run H.scala -q [--interpret]`):
linked `real 3.10` / `3.11`, interpreted `real 0.29` / `0.29`; a `.sc` script `real 0.22`.

Dev loop used: compile touched dotc files with JVM dotc on top of
`.build-work/selfhost-bootstrap/classes` (+ the generated `MiniPhaseOverrides.scala`), run
`java ... dotty.tools.dotc.Main --interpret|--repl ...` against test programs compiled by the
native `scalino-dotc`. ~20s per iteration vs ~15 min per native rebuild.

## Decisions taken without Lorenzo (defaults, revisit)

- `--interpret` is opt-in, also for `.sc` scripts (interpreter results can be silently wrong
  where a native run isn't, e.g. the `Range`/`for`/guard `List`-vs-`Vector` case below).
- Automatic fallback only before the program wrote anything (re-running would repeat side
  effects); after output it's an error telling to rerun without `--interpret`.
- `.sc` only as explicit file args, never from directory scans (a stray mill `build.sc`).
- Scripts are wrapped in a method, not an object body: the interpreter initialises object `val`s
  lazily per access and never runs other object-body statements. Consequence: a script can't
  forward-reference a `def` across a `val` (local-block rules).
- No line editing in the REPL (no JLine on Scala Native); `rlwrap` suggested in the docs.

## Found on the way (not fixed here)

- Toolchain bug: on the native `scalino-dotc`, `x match { case a: Array[?] => }` with `x` a small
  boxed `Int` (an SMI-tagged pointer, patch 0022) SIGSEGVs in the type test, while class tests
  (`case i: Integer`) are tag-aware. Worked around in `ScalinoRepl.render` and the new interpreter
  intrinsics (`getClass.isArray` after a name guard). Worth a look in the SN patches: any
  `Array[?]` type test on an `Object` that may be a boxed Int crashes.

## What remains

- `BigInt`/`BigDecimal`: javalib `BigInteger` bodies interpret only partly; host `java.math` objects
  + by-name calls (like `HostValueCalls.string`) would be the robust route.
- Interpreted `for (i <- 1 to 3; j <- ...; if ...) yield` gives a `List` where the native run gives
  a `Vector` (host-intrinsic `withFilter`/`flatMap` shapes): fidelity issue, needs a look.
- Exception stack traces have no line numbers (the interpreter tracks only methods).
- Coverage beyond the test programs is unknown: running a few real scala-cli scripts/projects
  through `--interpret` would surface the next gaps; macro-library-heavy code (circe, upickle
  derivations at runtime) is untested.
- The REPL has no completion/history, no `:paste`, no `:doc`; `res` values of a failed input are
  not printed. Native-only code in the REPL just reports the interpreter error.
- `run --interpret -w` never falls back.
- Windows: untested (the CLI paths are platform-neutral; `isatty` is used as elsewhere).
