# 01: dotc daemon (warm `scalino-dotc` across builds)

## Status (2026-11-20)

Implemented, uncommitted: `patches/scala3-0045-dotc-daemon.patch`, `cli/DotcDaemon.scala`, the
`compileToClasses` changes, `tests/dotc-daemon/run.sh`. Results, the traps hit and the open
position-only difference are in docs/findings.md, "dotc daemon". In short: small edits on ape are 2-3x
faster end to end (0.67s -> 0.22s for one file, 1.84s -> 1.00s for the upickle file). A 22-file closure
is about 10% slower with the memory-saving EVAC default (8.2s vs 7.7s) and 11% faster without it (6.9s).
Measurement 7 below was wrong: the 0.6s CLI overhead came from a debug-mode `dist/scalino`. A
release-size build does the no-op compile in 0.10s, so the "fast CLI scan" column of the payoff table
doesn't apply.

## Summary

Keep `scalino-dotc` running between compiles and reuse one dotc `ContextBase` (symbol table, loaded
library TASTy, erased denotations, macro-interpreter caches) across `Run`s. Use it first under `-w`,
then across separate `scalino` invocations through a per-project background process. On `~/scala/ape`, 60-95%
of the dotc time of a typical small-edit recompile is per-process warm-up, not real work. That is
0.5-1.2s per edit. A second, separate cost turned up while measuring: `scalino` itself spends 0.6s
scanning sources with regexes before it decides whether to run dotc at all.

## Current state

**How dotc is driven today.** `compileToClasses` (`cli/ScalinoCli.scala:2009`) computes an
approximate invalidation closure from source text (`:1924-1937` design comment, `:2057-2066` BFS),
purges stale outputs (`purgeArtifactsFor`, `:1998`), then spawns a fresh `scalino-dotc` per compile
through `runCaptureAll` (`:267`, the command is built at `:2085-2100`). `classesDir` is the first
classpath entry, so files outside the closure resolve from their `.tasty` (written by
`-Xearly-tasty-output`). The binary's entry point is stock `dotty.tools.dotc.Main`
(`build/03-build-scalino-dotc.sh:70`, `vendor/scala3/.../dotc/Main.scala`: `object Main extends Driver`).
Every compile therefore builds a new `ContextBase` (`Driver.initCtx`, `Driver.scala:74`).

By contrast, the linkers already stay alive under `-w`: `LongRunningLinker` / `linkIncremental`
(`cli/ScalinoCli.scala:374-438`) talks to `scalino-linkdriver --long-running` over stdin/stdout with
`SCALINO_LINKING_DONE/FAILED` sentinels (`src/LinkDriver.scala:466-492`), and `linkJsIncremental`
(`:2569`) does the same for `scalino-jslink`. dotc has no equivalent.

**Upstream support for multi-run reuse already exists:**
- `dotty.tools.dotc.Resident` (`vendor/scala3/compiler/src/dotty/tools/dotc/Resident.scala`), a PoC
  REPL-style driver that loops `setup(args, prevCtx)` + `doCompile` on the same base. It is not
  reachable from `Main`, so it isn't linked into `scalino-dotc`.
- `Denotation.current` → `bringForward` → `updateValidity` (`core/Denotations.scala:721-767`)
  moves *the whole `nextInRun` ring* of a still-valid denotation into the new run. Phase-transformed
  versions (erasure etc.) survive, and only base-class caches are invalidated.
- `Namer.checkNoConflictIn` (`typer/Namer.scala:151-160`) treats only `isDefinedInCurrentRun`
  symbols as conflicts, so redefining a top-level class from source in a later run is supported.
- The macro interpreter's javalib overlay is cached per `ContextBase`
  (`quoted/JavalibOverlay.scala:194-204`, "one compiler run / LSP session").
- `JavaPlatform.classPath` is memoized once per platform (`config/JavaPlatform.scala:17-22`), so
  one daemon must have one fixed classpath.
- `scalino-lsp` (PC backend) already runs many runs on one `ContextBase` under Scala Native, but
  without a backend and in interactive (`acceptStale`) mode.

**Measurements** (macOS arm64, 10 cores, `dist/` from 2026-11-15, `release-size` dotc).
Setup: `~/scala/ape` (29 files / 6409 lines; http4s, cats-effect, upickle, scalatags, porcupine) was
copied to the scratchpad. `dist/scalino` had no exec bit, so a shadow dist (symlinks + executable
copy) was used, with `scalino-dotc` and `scalino-cs` wrapped by argv/time-logging scripts. Direct dotc
runs use the exact argv `scalino` logged (`-Yretain-trees`, nscplugin, `-Xmacro-settings:javalibTasty`,
`-Xearly-tasty-output`), with output redirected to a throwaway dir and timed best-of-3 by a small
Python harness.

1. End-to-end `scalino compile .` after appending a comment to one file (dotc time from the wrapper,
   so the remainder is CLI time):

   | edited file | closure (files sent to dotc) | total | dotc | CLI (rest) |
   |---|---|---|---|---|
   | `Main.scala` | 1 | 1.25s | 0.60s | 0.65s |
   | `BrowserLauncher.scala` | 2 | 1.39s | 0.72s | 0.67s |
   | `util/Logger.scala` | 4 | 1.28s | 0.63s | 0.65s |
   | `http/Views.scala` | 4 | 2.68s | 2.00s | 0.68s |
   | `domain/DhwCalc.scala` | 23 | 8.47s | 7.70s | 0.77s |
   | `--no-incremental` (all 29) | 29 | 8.3s | 7.6s | |

2. Fixed per-process cost (direct dotc):
   - `-Ystop-after:parser src/Main.scala`: **0.09s** (exec + settings; 104 MB RSS). Hello world
     with `examples/Hello.scala` (no deps) runs in 0.15s end to end.
   - trivial `object T` through all phases on ape's classpath: 0.17s.
   - GC is not the cause: `GC_INITIAL_HEAP_SIZE=1g/2g` changes Routes 1.61 → 1.65 / 1.52s,
     Views 0.43 → 0.44 / 0.42s.

3. **Warm-vs-cold estimate without a rebuild ("duplicate trick").** Compile file set *F*, then *F*
   plus a copy of each file with an extra `package zcopy` clause, in one dotc process. The copy
   repeats all the per-file work but finds every library symbol and the interpreter already warm, so
   (time(F+copies) − time(F)) ≈ F's warm cost and the rest of time(F) is warm-up:

   | set | cold | + copies | warm-work estimate | warm-up share |
   |---|---|---|---|---|
   | `http/Views.scala` | 0.43s | 0.50s | 0.07s | ~84% |
   | `domain/EnergyBalance.scala` | 0.27s | 0.31s | 0.04s | ~86% |
   | `http/Routes.scala` (upickle `derives`) | 1.65s | 2.14s | 0.49s | ~70% |
   | Logger closure (4 files) | 0.52s | 0.54s | 0.02s | ~96% |
   | Views closure (4 files) | 1.69s | 2.34s | 0.65s | ~61% |
   | DhwCalc closure (23 files) | 7.07s | (copy failed to type-check) | ~5.9s est. | ~16% est. |

   The "+copies" run still pays per-run fixed costs once, so this is a lower bound on what a warm
   daemon costs. Per-run fixed costs (Run/phase setup, `invalidateInheritedInfo` re-filling base-type
   caches) cannot be measured without a prototype.

4. Where Routes' ~1.17s of warm-up goes (stop-after bisection, 1 copy vs 2 copies):
   typer ≈ 0.31s (library TASTy unpickling: cats, http4s), `inlining` ≈ 0.29s (macro interpreter
   warming up on upickle's retained trees), post-inlining ≈ 0.45s (refchecks→genNIR: forcing erased
   signatures and members of library classes; the second copy adds ~0 here), plus 0.09s for the
   process. Because `updateValidity` keeps the transformed denotation ring, all three should carry
   over into a warm `ContextBase`.

5. Memory: Routes alone peaks at 610 MB RSS and the full 29-file compile at **1.57 GB**. A daemon
   holds roughly the full-compile heap for its lifetime.

6. Determinism: two cold compiles of the Views closure into separate dirs are **byte-identical**
   (115 files, `diff -r`), so a daemon-vs-cold byte comparison is a usable correctness oracle.

7. **Side finding, not dotc:** the 0.6-0.77s "CLI (rest)" is spent in `scalino` itself with no
   subprocess (`scalino-cs` is never called; the deps cache hits). It scales with source size:
   appending a 73 KB comment to one file raised the no-op `scalino compile .` from 0.60 to 0.70s,
   ~1.4 µs/byte. `/usr/bin/sample` leaf frames are in `scala.scalanative.regex.Machine.step` and
   `StringCharBuffer.get`: the regex scans in `compileToClasses` (`topLevelDeclRe`, `identifierRe`
   over every file, `:1943-1957`, `:2051-2054`) plus directive parsing. Hello world's no-op is ~0s.
   This is half of every small-edit build on ape and is worth its own idea doc: a hand-written
   scanner, or caching the scan per file hash in the manifest.

## Proposed design

### Process model

One daemon per **(compile fingerprint, classesDir)**. The fingerprint is the existing
`computeFingerprint` (`:1962`: compile classpath, javaBase, plugin, options, dotc mtime). It is the
same `scalino-dotc` binary, entered with a new first argument `--daemon`, so there is one binary and
no build-script change. `Main` becomes:

```scala
object Main extends Driver:
  override def main(args: Array[String]): Unit =
    if args.headOption.contains("--daemon") then ScalinoDaemon.run(args.tail) else super.main(args)
```

`ScalinoDaemon` (new file in a new `patches/scala3-00NN-dotc-daemon.patch`) is `Resident`
grown up:

- It holds `base: ContextBase | Null` and `rootCtx`. Each request calls `setup(requestArgs, rootCtx)`
  (as `Resident` does), then `doCompile(compiler, files)` with a per-request
  `ConsoleReporter` writing to a buffer. Output and exit code go back to the client, which
  re-renders them through `Diagnostics.render` exactly as `runCaptureAll` output is today.
- **Project-symbol invalidation** mirrors on-disk purging: whatever `compileToClasses` purges on
  disk, the daemon unlinks in memory. Each request carries `purge` entries `(pkg, name)`: the
  previous `declaredNames` of every recompiled file and of every removed file, the same data
  `purgeArtifactsFor` receives. Before the run the daemon looks up `pkg`'s package class and
  `unlink`s the class, its module and `name$package` from `unforcedDecls`. Unchanged files outside
  the closure keep their previous-run, source-derived symbols, which have the same content as their
  `.tasty`. This is safe because scalino's closure is transitive over textual mentions
  (`:2057-2066`): anything that mentions a changed name is recompiled, and so re-entered.
- **Reset rules** (drop `base` and lazily recreate it; the process stays, so only exec cost is saved):
  any compile that reports errors or throws (phase 1; revisit in phase 3), a `fullRebuild` request,
  a manifest-generation mismatch (below), and an optional heap ceiling (`SCALINO_DOTC_DAEMON_MAX_RSS`).
- **Generation check:** the daemon remembers the hash of the manifest it produced after its last
  successful compile. The client sends the hash of the manifest it loaded. If they differ, something
  else wrote `classesDir` (a one-shot build, another scalino version, a manual `rm`), and the daemon
  resets instead of trusting in-memory symbols.
- Environment as for the long-running linker: `SCALANATIVE_EVAC_ENABLE=1` (`:401-404`).

### Protocol

Line-based, like LinkDriver's. Arguments are paths and flags with no newlines:

```
client → SCALINO_COMPILE <manifestHash> <nArgs> <nPurge>
         <arg>        × nArgs   (the exact argv compileToClasses builds today, minus the binary)
         <pkg>\t<name> × nPurge
daemon → <diagnostic output lines...>
         SCALINO_COMPILE_DONE <exitCode> | SCALINO_COMPILE_RESET <exitCode>
```

### Phase A: inside `-w` (stdin/stdout child)

A `LongRunningDotc` sits next to `LongRunningLinker` (`:374`): spawn `scalino-dotc --daemon` on
first compile, keep it keyed by fingerprint+classesDir, and respawn when the key changes or the process
died. `compileToClasses` gains a `daemon: Boolean` path that sends the request instead of calling
`runCaptureAll`. Same shutdown path as `terminateLinker`.

### Phase B: across invocations (bloop-like, no JVM)

The patched javalib has TCP `ServerSocket`/`Socket` for Unix and Windows
(`vendor/scala-native/javalib/src/main/scala/java/net/{Unix,Windows}PlainSocketImpl.scala`) but no
`UnixDomainSocketAddress`, so use **loopback TCP plus a secret token**, which works the same on every
platform scalino ships:

- State file `~/.cache/scalino/dotc-daemon/<hash(key)>.json` (mode 0600):
  `{pid, port, token, dotcMtime, key}`. The client checks it, connects to `127.0.0.1:port`, sends the
  token, then the request. On no file, a dead pid, a refused connection or a mismatched `dotcMtime`,
  it spawns the daemon detached (`--daemon --listen`), with stdio to a log file under the same directory,
  and waits for the state file to appear. Use a `FileChannel.lock()` on `<hash>.lock` around spawning so
  that two concurrent `scalino` runs don't start two daemons.
- One connection is served at a time (dotc isn't reentrant). A second client queues on the lock and
  never gets a parallel run on the same base.
- Idle exit after `SCALINO_DOTC_DAEMON_IDLE` (default 15 min), and a cap on live daemons per user
  (default 3; the oldest-idle one exits). With a 1.5 GB heap each, the cap matters.
- CLI: `--no-daemon` / `SCALINO_DOTC_DAEMON=0` opt-out, and `scalino daemon status|stop` to list and kill
  daemons. One-shot `scalino` in CI should default to no daemon (detect `CI=true`), so nothing lingers.
- `-w` can use the Phase A child or attach to the Phase B daemon. Attaching is simpler: one code path.

### Verification mode

`SCALINO_DOTC_DAEMON=verify`, modeled on `SCALINO_LOWER_CACHE=verify`: compile through the daemon
into `classesDir`, then cold-compile the same closure with the same `classesDir` snapshot into a temp
dir, and fail naming the first differing `.nir`/`.tasty`. This works because dotc output is
deterministic (measurement 6).

## Implementation steps

1. **Prototype measurement, no CLI changes.** New patch `patches/scala3-00NN-dotc-daemon.patch`
   with `ScalinoDaemon.scala` (stdin protocol only) and the `Main` hook. Rebuild `scalino-dotc` via
   `build/03-build-scalino-dotc.sh` (long build, done once). Drive it with a Python script, like the
   linkdriver benchmarks in `docs/findings.md`, through the ape edit sequence from measurement 1. This
   gives the real warm numbers, including per-run fixed cost.
2. **Purge/unlink plus generation check** in `ScalinoDaemon`, then an edit-sequence test
   `tests/dotc-daemon/run.sh` that compares against a cold compile after each step: body edit,
   signature change, add class, remove class, rename class within a file, delete a file, edit a
   `derives` case class (macro path), introduce and then fix a type error, and a top-level
   `def` in `<File>$package`.
3. **CLI Phase A:** `LongRunningDotc` + `compileToClasses(daemon = watch)` in
   `cli/ScalinoCli.scala` (near `:374` and `:2085`), plus `--no-daemon`. Rebuild via
   `build/07-build-scalino.sh`.
4. **Verify mode** (`SCALINO_DOTC_DAEMON=verify`) in the CLI. Run the step-2 test through the CLI,
   then do a manual `-w` session on ape and on `~/scala/test-native`.
5. **CLI Phase B:** state file, lock, TCP listener (`--listen` in `ScalinoDaemon`), idle timeout,
   `scalino daemon status|stop`, CI default off. Windows: check that `ServerSocket` binds loopback
   under the Windows impl, and run the CI matrix that already covers Windows run/test.
6. **Keep the base on failed compiles** (phase 3), only if step 2's test stays green with error/fix
   cycles. Otherwise keep reset-on-error.
7. Document in `docs/findings.md` and the README (`scalino daemon`, env vars).

## Effort estimate and risks

- Steps 1-2: ~2-3 days (most of it in invalidation edge cases and the dotc rebuild). Steps 3-4: ~1
  day. Step 5: ~2-3 days (spawn races, Windows, stale daemons). Total ~1-1.5 weeks.
- **Stale symbols (main risk).** In-memory symbols that the on-disk state no longer has, for example a
  class renamed inside a changed file. The unlink-what-you-purge rule plus verify mode covers this.
  One known pre-existing hole: the CLI closure misses files that mention a name that *disappeared from
  a still-existing file*, because `allKnownNames` only adds names of removed *files* (`:2046-2047`).
  That breaks cold incremental builds today too, so it should be fixed in the closure first: add prior
  `declaredNames` of changed files to `removedNames`.
- **Retained trees and positions.** With `-Yretain-trees`, unchanged files' trees come from the
  previous run's typed source trees instead of TASTy. Inlining from those may give different spans
  than inlining from TASTy, so verify mode may flag benign byte diffs in positions. If that happens,
  compare with positions stripped, or accept TASTy-only reuse (see open question 3).
- **Patched macro interpreter global state.** Most caches are per `Interpreter` instance or keyed
  on `ctx.base`. `Interpreter`'s companion (`quoted/Interpreter.scala:7229+`) and the Splicer macro
  thread (patch scala3-0033) need an audit for cross-run leaks.
- **Memory:** ~1.6 GB resident per ape-sized daemon (measurement 5), possibly growing over runs
  (compare the linkdriver OOM history in `docs/findings.md`). Mitigations: EVAC, an RSS ceiling →
  reset, the daemon cap, idle exit.
- **Big-closure edits gain little** (~1.2s of ~7s for the 23-file closure), because the work is
  real compilation.

## Expected payoff

From measurements 1 and 3, assuming the per-run fixed cost is small (≤50 ms, unverified; step 1
measures it):

| ape edit | today total | dotc today | dotc warm (est.) | total with daemon | with daemon + fast CLI scan* |
|---|---|---|---|---|---|
| `Main.scala` (1 file) | 1.25s | 0.60s | ~0.1s | ~0.75s | ~0.2s |
| `util/Logger.scala` (4 files) | 1.28s | 0.63s | ~0.1s | ~0.75s | ~0.2s |
| `http/Views.scala` (4 files, macros) | 2.68s | 2.00s | ~0.7s | ~1.4s | ~0.8s |
| `domain/DhwCalc.scala` (23 files) | 8.47s | 7.70s | ~6.5s | ~7.3s | ~6.6s |

\* the separate 0.6s regex-scan finding (measurement 7), if fixed.

So: about 2-5x faster small-edit compiles in `-w` and across invocations, provided the CLI scan is
fixed too. Without that fix, the daemon alone caps at ~1.6-1.9x on small edits. Projects with heavier
dependencies or macros (Routes-like files) gain the most in absolute terms (~1s). For hello-world-sized
projects the gain is nil (dotc runs in 0.15s cold).

## Open questions for Lorenzo

1. **Is cross-invocation (Phase B) wanted, or is `-w` (Phase A) enough?** Phase B adds a background
   process holding ~1.5 GB per project, plus `scalino daemon stop`. bloop users accept that, but
   scalino's pitch is "no JVM, no daemon"?
2. **Should the 0.6s CLI regex-scan cost be fixed first?** It is cheaper, has no state, helps every build,
   and doubles the daemon's relative payoff. I'd do it first as its own idea doc.
3. **Is TASTy-only reuse an acceptable fallback?** That means always loading project classes from
   `classesDir` `.tasty` (never reusing source-entered project symbols across runs): unlink *all*
   project symbols each run, keep only library ones. It is simpler to prove correct, gives up a little
   speed, and removes the retained-tree position risk.
4. **Reset on compile errors (simple and safe) or keep the base (faster edit-error-fix loops)?**
5. **Should the LSP and the compile daemon share a process later?** Both hold the same library
   symbols for the same classpath (2x memory today when both run), but the PC runs in interactive
   mode and without a backend, so sharing is a larger change than this doc proposes.
