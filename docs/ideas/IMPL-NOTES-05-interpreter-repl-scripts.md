# 05 — interpreter-backed `scalino repl`, scripts and `run --interpret`

Status: in progress (plan written 2026-11-21).

## Goal

Reuse the macro tree interpreter (`vendor/scala3/.../quoted/Interpreter.scala`, javalib TASTy
overlay from `scala3-0036`) to

- (a) `scalino repl`: a Scala 3 REPL with no JVM, scala-cli's `repl` flags/UX as reference;
- (b) `scalino run --interpret` (and `.sc` scripts): run a program by interpreting its compiled
  TASTy instead of linking a native binary, for near-zero dev-loop latency;
- coverage gaps (extern/natives, threads, missing javalib bodies) end in a clear error or an
  automatic fallback to the normal link path.

## Design

All execution happens inside `scalino-dotc` (the only binary that has the interpreter linked in),
behind two new entry modes in `dotc.Main` (same hook shape as `--daemon`, patch `scala3-0048`):

- `scalino-dotc --interpret <mainClass> <dotc options> -- <program args>`
  1. sets up a context from the usual options (`-classpath <classesDir>:<deps>`, `-Yretain-trees`,
     javalib TASTy setting),
  2. re-reads the user's own `.tasty` (written by the normal incremental compile with
     `-Xearly-tasty-output`, so the CLI's compile cache and the dotc daemon are reused as-is)
     through a `-from-tasty` run that stops after `postInlining`: inline calls and macros in
     the user's code are expanded exactly as for the native build,
  3. points every user symbol's `defTree` at its post-inlining tree,
  4. pre-scans the reachable user code for constructs the interpreter cannot run (`@extern`,
     `scala.scalanative.unsafe`/`runtime`, `Thread`, `java.util.concurrent`, `Future`): if
     found, exits with a dedicated status (3) so the CLI falls back to linking,
  5. interprets `main(args)`. An interpreter `StopInterpretation` before the program has written
     anything to stdout/stderr also exits 3 (automatic fallback); after output it is a clear
     error ("unsupported by the interpreter: ...; rerun without --interpret").
     Program exceptions print like a native run (stack trace of the exception) with exit 1;
     `sys.exit(n)` exits n.
- `scalino-dotc --repl <dotc options>`: a line-oriented REPL. Each input is wrapped
  (`object rs$lineN { <input> }`, with the previous lines' members imported), compiled in-process
  in one long-lived context (new `Run` per line, symbols of earlier lines stay entered), its
  post-inlining trees are installed as above, then the wrapper object's statements are
  interpreted and new vals/defs printed `val x: Int = 1` style. Interpreter state (object fields,
  module vars) lives in a session object shared by every line's `Interpreter`.
  Commands: `:help`, `:quit`/`:exit`, `:type <expr>`, `:imports`, `:reset`, `:load <file>`;
  multi-line input continues while the parser reports an incomplete statement.

CLI (`cli/ScalinoCli.scala`):

- `scalino repl [inputs] [--dep ...] [-O ...] [--repl-init-script code] [-q]`: resolves deps and
  compiles the given sources (if any) like `compile`, then `exec`s `scalino-dotc --repl` with the
  classes dir + deps on the classpath. Native only (`--js` is rejected).
- `scalino run --interpret`: compile as usual, detect the main class, then
  `scalino-dotc --interpret` instead of `scalino-linkdriver`; exit 3 = fall back to the link path
  (message on stderr unless `-q`). Works with `-w`.
- `.sc` scripts: wrapped into a generated `.scala` (`object <name>_sc { def main(args) = { body } }`,
  body lines kept at their line numbers) under `.scalino-build/scripts/`.

## Steps

1. Dev loop: JVM-compiled patched compiler (`build/02a`, `.build-work/selfhost-bootstrap/classes`),
   recompile only the touched files on top of it.
2. `Interpreter` entry points (`runMain`, session state, REPL statement evaluation).
3. `ScalinoInterpret` (from-tasty post-inlining load, pre-scan, run, exit codes) + `--interpret` in Main.
4. CLI `run --interpret` + fallback; `.sc` wrapping.
5. REPL (`ScalinoRepl` + CLI `repl`).
6. Coverage pass on examples; tests under `tests/interpreter-run/`; README.
7. Native rebuild of `scalino-dotc` + `scalino` from this worktree, end-to-end verification.

## Risks

- The interpreter is macro-oriented: user programs will hit missing intrinsics. Mitigation: fallback.
- Object initialisation semantics: the interpreter evaluates module `val`s lazily per access, not
  in constructor order; side-effecting object bodies may run in a different order.
- Native rebuild of dotc is ~15-50 min and memory-hungry: done under the shared lock, as few
  times as possible.
- No JLine on Scala Native: the REPL reads plain lines (no history/completion).

## Decisions taken without Lorenzo (defaults, revisit)

- `--interpret` is opt-in, also for `.sc` scripts (the interpreter can be silently wrong in ways a
  native run is not). Easy to flip later.
- Fallback on unsupported code is automatic only while the program has produced no output.
