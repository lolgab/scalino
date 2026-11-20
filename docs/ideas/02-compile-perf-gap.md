# 02: closing the compile-time gap vs JVM dotc

**Status (2026-11-20):** step C1+C2 (track C) is implemented as patches `scala-native-0037` (inline
`loadModule` fast path) and `0038` (stack-overflow check polls a global count). ape full compile
7.58s -> 7.04s (-7%), output identical. See docs/findings.md "Module loads and stack-overflow polls
inlined".

## Summary

The "33s vs 10.5s" gap on `~/scala/ape` is out of date. A clean compile with today's `dist/scalino-dotc`
takes **7.4s**. That beats a cold JVM dotc 3.10.0 (10.3s wall, 45s CPU) and is **1.65x slower than a
warm JVM** (4.4s, which is what scala-cli/bloop users see). The remaining gap is almost entirely
the macro interpreter running upickle's `derives ReadWriter`. Files without derives already compile as fast natively (cold process) as on a warm JVM.
The plan: first make the interpreter profilable, then
cut its cost. Separately, two Scala Native runtime calls (`loadModule`, stack-overflow checks) that tax every thread
should be inlined, and dotc should get a modest default initial heap. Do not build a cross-run splice cache.

## Current state

### Status of the earlier work (patches 0037/0038)

- After the 3.10 rebase there is no separate `scala3-0037`/`0038` patch any more. Both were folded
  into `patches/scala3-0040-rebase-3.10.0-fixups.patch`: stackless PF path (`InterpretedFn1`, patch lines
  ~1782-1958), `quotesApiNames`/`quotesApiOwnerMayBind` prefilter (~5649-5655, 6760-6767), the
  `moduleConstructor` `*Module` gate (~6752-6757) and the `ownerNames` cache (~6749).
- **The native rebuild has happened.** `nm dist/scalino-dotc | grep InterpretedFn1` shows the symbols
  (dist built 2026-11-15), and the released Homebrew `scalino 0.0.31` dotc behaves the same (below).
  The memory note "NOT yet rebuilt natively" is stale.

### Measurements (2026-11-19, macOS arm64, 10 cores, load avg ~1.5)

Setup: `~/scala/ape` copied (without `.scalino-build`/db/backups) to the scratchpad. 30 main
files / 6409 lines, 33 `derives ReadWriter` in 7 files (20 of them in `src/domain/Building.scala`).
`dist/scalino` was used through a shadow dist whose `scalino-dotc` is a wrapper that logs argv/env
and runs `/usr/bin/time -l`. Direct runs replay that exact argv (`@native.args`) with `-d`/
`-Xearly-tasty-output` redirected to a throwaway dir. The JVM runs use
`cs fetch --classpath org.scala-lang:scala3-compiler_3:3.10.0` with the same argv minus
`-javabootclasspath` and `-Xmacro-settings:javalibTasty`, and produce the same 86 `.tasty`/884 `.nir`.
Warm JVM: a 15-line `Warm.java` calls `dotty.tools.dotc.Main$.MODULE$.process(args)` N times in
one JVM with a fresh output dir each iteration.

| what | native `dist/scalino-dotc` | JVM cold (`java ... dotc.Main`) | JVM warm (iter ≥6) |
|---|---|---|---|
| ape full, 30 files | **7.34-7.67s** wall, 8.5-8.9s user | 10.3-10.7s wall, 45s user | **4.4-4.9s** |
| the 23 files without `derives` (others from `.tasty`) | **1.46-1.66s** | | 1.41-1.78s |
| the 7 files with `derives` | 6.7-7.5s | | 3.3-4.2s |
| `scalino compile .` end to end (clean) | 8.77s (dotc 7.57s) | | |
| Homebrew `scalino 0.0.31` dotc (release+PGO), full | 7.5-8.3s | | |

Conclusions:
- **Non-macro compilation is at parity with a warm JVM**, and the native number even includes process
  start and classpath load.
- **The whole gap (~3-3.7s) is in the `derives` files.**

**Per-thread split** (`/usr/bin/sample <pid> 7 1` on a full compile, ~1.17ms/sample, busy = samples
not parked in `__psynch_cvwait`):

| thread | busy samples | ≈ CPU |
|---|---|---|
| main (typer etc.) | 3672 | 4.3s |
| macro thread (Splicer, patch 0033) | 2283 | **2.7s (38% of wall)** |
| 7 GC workers | 71 each | small |

Main and macro busy sum to the total sample count, so they hand off strictly (no overlap). The macro
thread therefore is ~2.7s of the gap. The other ~0.5-1s is probably post-expansion typing of the large
expanded trees plus whatever the JVM's own (reflective, JIT-compiled) macro calls cost. That remainder is an estimate, not a measurement.

**Self-time hot spots** (share of each thread's *busy* samples):

| symbol | main | macro |
|---|---|---|
| `__scalanative_loadModule` | 8.8% | 8.0% |
| `java.lang.Object.getClass` | 6.6% | 6.2% |
| `scalanative_StackOverflowGuards_check` | 5.9% | 6.1% |
| `_tlv_get_addr` (macOS TLS) | 3.2% | 3.4% |
| `memset`/`bzero`/`GC_alloc_small` | 5.4% | 12.6% |
| CAS atomics | 3.9% | <1% |
| `stat`/`open`/`access`/`read` (classpath I/O) | ~7% | ~1% |
| `String.equals` | <1% | 4.2% |
| libunwind (exceptions still thrown) | - | ~3% |
| `interpretTree0` / `interpretQuotesApi` / `Call0.unapply` self | | 1.5% / 0.6% / 0.9% |

The interpreter's own profile is now flat. **Inclusive stacks are unusable:** the release binary has no
frame pointers, so `sample` produces nonsense parents (e.g. `getClass` "called from"
`_pthread_cond_wait`). Only self-time by address is trustworthy. Which interpreter paths cost the 2.7s
is therefore still unknown in native terms.

**Runtime-tax sources (verified in vendor):**
- Every module access under `--multithreading` calls the C function `__scalanative_loadModule`
  (`vendor/scala-native/tools/src/main/scala/scala/scalanative/codegen/Generate.scala:233-245, 330-345`).
  It is declared `INLINE` in `nativelib/.../module_load.c:127`, but it lives in a separate C TU and
  dotc links with `SCALINO_LINK_LTO=none` on macOS/arm64 (`build/00-env.sh:114-128`). So each access is
  an out-of-line call plus an acquire load plus a header class-id compare (`MODULE_IS_CLASS`, `:64`).
- Every self-recursive method calls `scalanative_StackOverflowGuards_check`
  (`codegen/Lower.scala:295-299`, selection `:1150-1168`; C at `stackOverflowGuards.c:350`): an
  out-of-line call plus a TLS read (`_tlv_get_addr` on Darwin). dotc is highly recursive.
- `Object.getClass` is a 1.3KB function (`otool`: null/tag checks, class-id table lookup). Its
  callers are unknown because the stacks are broken.

**GC** (`GC_STATS_FILE`): default heap 12 collections, total mark 0.21s, sweep 0.01s wall. Parallel
STW (patch 0030/0034) removed the old "3.2s GC". The initial heap trades CPU for RSS:

| `GC_INITIAL_HEAP_SIZE` | full (22-file argv) wall / user / RSS | small (2 files) wall / user / RSS |
|---|---|---|
| default | 7.32-7.61 / 8.5-8.8 / 1.58GB | 0.51-0.55 / 0.61 / 380MB |
| 1g | 7.35-7.55 / 7.2 / 2.1-2.3GB | 0.46-0.51 / 0.39 / 546MB |
| 2g | 7.21-7.30 / 7.2-7.3 / 2.0-2.2GB | 0.47-0.52 / 0.40 / 546MB |
| 4g (full ape argv) | 7.94-8.22 / 7.2-7.5, sys 1.2-1.4 | |

So: CPU -15% (full) / -35% (small), wall -0-3% on 10 cores, RSS +0.5GB. 4g is worse (sys time).
The dotc spawn has no GC env today (`cli/ScalinoCli.scala:267` `runCaptureAll`, command built at
`:2085-2101`); only the linker gets `SCALANATIVE_EVAC_ENABLE` (`:405`).

**Incremental runs and splice caching:** after a full build, appending a comment
- to `src/http/Routes.scala` (4 derives): closure = 2 files, dotc 1.75s, macro thread ~0.3s.
- to `src/domain/Building.scala` (20 derives): closure = **22 of 30 files**, dotc 7.3s (≈ a full
  build), macro thread ~37% of busy.

The cost of a Building edit comes from the approximate invalidation closure as much as from
macros.

## Proposed design

Four independent tracks, in payoff order.

**A. Make the interpreter profilable (prerequisite).** Add an opt-in frame-pointer mode to direct
codegen: an env var such as `SCALANATIVE_FRAME_POINTERS=1` sets the `"frame-pointer"="all"` function
attribute in `DirectCodeGen.scala`. Link a profiling `scalino-dotc` with it, so `sample`/samply give
real inclusive trees. Commit an `ape`-like bench (a pinned upickle-derives fixture, not `~/scala/ape`)
plus the warm-JVM harness, so "gap vs warm JVM" is one command.

**B. Interpreter (target: macro thread 2.7s → ≤1.0s).** Extend 0038's prefilter idea from a name
filter to a **per-callee-symbol dispatch cache**: an `IdentityHashMap[Symbol, Dispatch]` per run, where
`Dispatch = QuotesApi(handler) | StdlibIntrinsic(handler) | ModuleCtor | UserBody(DefDef) | ...`.
It would be resolved once per symbol, and later calls would skip the string-guard chains (`String.equals`
4%) and `resolveExternalDefTree` (`Interpreter.scala:4073`). Then use the A-profile to choose among:
- memoizing the `Call` classification per tree (the ~15-branch chain in `Call0.unapply`);
- replacing `Env = Map[Symbol, Object]` (`Interpreter.scala:120`) with slot-indexed frames, to cut the
  macro thread's 12.6% alloc/zeroing;
- finding the remaining thrown exceptions (libunwind ~3%) with a per-class throw counter under
  `SCALINO_INTERP_DEBUG`;
- moving the `System.getenv("SCALINO_OUTER_DIAG")` checks on hot paths (e.g. every `unpickleTypeV2`,
  `Interpreter.scala:~4505`) to a cached `val`, as was already done for `SCALINO_INTERP_DEBUG` (`:7230`).

Iterate on the JVM loop recipe (memory: interpreter-prefilter-scivolo) and confirm natively.

**C. Runtime tax (helps typer, interpreter, linkdriver and lsp alike).** A Scala Native patch:
1. *Inline the `loadModule` fast path in NIR.* In `genModuleAccessors`' multithreaded branch,
   emit: atomic acquire load of the slot; if non-null, untagged and the class id matches, return it;
   else call the C slow path. The single-threaded variant already does the load inline.
2. *Cheaper stack-overflow polling.* Either emit the pending-flag check inline (an LLVM `thread_local`
   load, which is still a TLS thunk on Darwin), or poll only at loop back-edges/GC yield points that
   already exist. A third option is a link option to drop the checks for the toolchain binaries and
   rely on the big `ulimit -s`, accepting a segfault instead of `StackOverflowError`, after checking dotc's
   `handleRecursive`/SOE catch sites.
3. Find `getClass`'s callers with the A-profile. It may be `ClassTag`/`isInstanceOf` lowering or the
   `Array.copy` component-type checks, and is likely fixable in Lower.

**D. Default initial heap for dotc.** In `runCaptureAll`, set
`pb.environment().putIfAbsent("GC_INITIAL_HEAP_SIZE", "1g")` when the user has not set it (the
same pattern as `:405`). That buys -15-35% CPU and +0.5GB RSS. Wall time hardly changes on a 10-core
machine, but CI and 2-4-core laptops, where GC workers compete with the mutator, should see more.
Optionally clamp it to 1/8 of physical RAM.

**Not recommended now: cross-run caching of splice results.** Correctness would need a key covering the macro
(library TASTy UUID), its pickled arguments including types, and *every symbol/type the macro
inspected* through `Quotes` (field lists, companions, annotations, other files' definitions),
plus compiler settings and any reports or side effects. That requires instrumenting the whole
`QuotesImpl` surface for dependency tracking. Payoff is capped at the macro share of the files in the
closure. For a Building edit that is ~2.7s, but only if the derived classes did not change, which is
exactly the case a real edit usually breaks. Tracks B and 01 (daemon) shrink it further. Better
levers for that scenario are a precise (API-hash) invalidation closure, so a comment edit does not
recompile 22 files, and idea 01. Revisit splice caching only if B stalls above ~1.5s.

## Implementation steps

1. **Bench + baseline** (`build/bench/compile-gap.sh`, `tools/WarmDotc.java`, fixture under
   `tests/perf/upickle-derives/`): native full/derives-only/no-derives runs, warm-JVM N=15, and
   reporting median and per-thread busy via `sample`.
2. **Frame-pointer opt-in** (new `patches/scala-native-0037-frame-pointers-opt-in.patch`, touching
   `tools/native/.../direct/DirectCodeGen.scala`). Link a profiling dotc in the same mode as the shipped one
   (`SCALINO_LINK_MODE=release-fast SCALANATIVE_FRAME_POINTERS=1 build/03-build-scalino-dotc.sh`) and
   record inclusive profiles of ape. Write them up in `docs/findings.md`.
3. **GC default** (`cli/ScalinoCli.scala:267-273`): `putIfAbsent("GC_INITIAL_HEAP_SIZE","1g")`; also
   for the LSP spawn if it uses a different path. Re-measure on a 2-core CI runner.
4. **loadModule fast path inline** (`patches/scala-native-0038-inline-loadmodule-fastpath.patch`:
   `tools/.../codegen/Generate.scala` `genModuleAccessors`; the compact-header class-id compare must
   match `MODULE_IS_CLASS`). Validate with the tests/escape-analysis + module-init concurrency tests,
   then rebuild dotc/linkdriver/lsp and measure the dotc compile and a linkdriver link.
5. **Stack-guard check** (`patches/scala-native-0039-...`: `codegen/Lower.scala:295-299, 1150-1168`,
   `nativelib/.../stackOverflowGuards.c`). Keep Windows' VEH semantics intact (the pending flag is
   required there).
6. **Interpreter dispatch cache** (new `patches/scala3-0045-interpreter-dispatch-cache.patch`,
   `compiler/src/dotty/tools/dotc/quoted/Interpreter.scala`). Iterate on the JVM loop, gate on
   identical `.tasty` output for ape, `examples/interpreter-regressions`, and the chimney2/jsoniter
   fixtures.
7. Follow-ups from step 2's profile: the `Call` memo, `Env` frames, exception sites, the getenv hygiene.
8. Regenerate patches after every batch (vendor is disposable) and update `docs/findings.md`.

## Effort estimate and risks

| step | effort | risk |
|---|---|---|
| 1 bench | 0.5 day | low |
| 2 frame pointers + profile | 0.5-1 day incl. one dotc link | low (opt-in) |
| 3 GC default | 1 hour + CI measure | RSS +0.5GB per dotc process; parallel `scalino` builds on small machines |
| 4 loadModule inline | 1-2 days | medium: module-init races/tagged init contexts (memory: null-is-aligned trap); must be stress-tested multithreaded |
| 5 SO check | 1-2 days | medium: Windows VEH path, and losing `StackOverflowError` recovery if checks are dropped |
| 6-7 interpreter | 3-6 days | medium: interpreter regressions (many fixtures). The dispatch cache must respect overrides and receiver-dependent intrinsics; key by `(Symbol, receiver kind)` where needed |
| splice cache (not recommended) | 2+ weeks | high: silent stale code |

Each dotc relink costs tens of minutes (`docs/pgo.md`: never run several dotc links at once on 16GB).
Batch steps 4-5 into one relink.

## Expected payoff

Baseline is the ape full compile: native 7.35s vs warm JVM 4.4s.
- D: CPU -1.3s (-15%), wall ≈ -0.1-0.2s here. More on few-core machines (not measured).
- C1+C2: `loadModule` + SO check + TLS make up ~17% of busy samples on both threads. Making them
  cheap rather than free should give **-8-12% wall (~0.6-0.9s)**. The same applies to linkdriver/lsp
  CPU (not measured).
- B: if the macro thread drops from 2.7s to ~1.0s, that is **-1.7s**.
- Combined: ~4.8-5.0s, i.e. **~1.1x of a warm JVM, down from 1.65x**. Native already wins against a
  cold JVM, and in startup-bound small incremental compiles (0.5s for a 2-file closure).

## Open questions for Lorenzo

1. Which baseline counts? Warm JVM (bloop, 4.4s) is the honest scala-cli comparison. Cold JVM is
   already beaten. Agree to target "≤1.2x warm JVM on ape"?
2. Is +0.5GB RSS per dotc acceptable as a default (step 3), or should it scale with RAM / be capped at
   512m?
3. For the stack-overflow checks, is "segfault instead of `StackOverflowError`" acceptable for the
   toolchain binaries, or must the checks stay (inline only)?
4. Upstream the inline `loadModule` fast path to Scala Native rather than keep it as a scalino patch?
5. Is a precise (API-hash) invalidation closure worth its own idea? The Building edit recompiling
   22/30 files is a bigger incremental cost than macro re-execution.
6. Is the derives-heavy fixture fine to commit under `tests/perf/` (pulls upickle from Maven), or keep
   the bench pointed at a local `~/scala/ape` checkout?
