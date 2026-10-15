# Profile guided optimization

Release builds of the performance critical binaries — `scalino-linkdriver`,
`scalino-dotc` and `scalino-lsp` — are profile guided. Measured on the native
`scalino-dotc` compiling the Scala Native tools sources (macOS arm64,
`release-fast`): **about 11–13% faster** than the same build without PGO, also
on a workload the profile was not trained on. About two thirds of that comes
from LLVM's own IR-level PGO, one third from Interflow (the NIR optimizer)
using the same profile.

## What happens at build time

The profiles are committed in `pgo/` (`<name>.interflow.gz`, `<name>.llvm.gz`
for `linkdriver`, `dotc`, `lsp`). `build/04` (linkdriver), `build/03` (dotc) and
`build/08` (lsp) pick them up automatically for the release modes
(`SCALINO_LINK_MODE=release-*`, i.e. tag builds); debug builds and
`SCALINO_PGO=0` ignore them. The linker is told through three env variables
(patch `scala-native-0080`):

| variable | effect |
|---|---|
| `SCALANATIVE_PGO_USE=<indexed .profdata>` | direct codegen runs `pgo-instr-use,pgo-icall-prom` before the O2 pipeline: block layout, inlining, hot/cold splitting and indirect call promotion (a polymorphic inline cache) follow the profile |
| `SCALANATIVE_PGO_PROFILE=<function counts>` | Interflow reads function entry counts (`PgoProfile`), see below |
| `SCALANATIVE_PGO_GEN=1` | direct codegen instruments every function (`pgo-instr-gen,instrprof`, entry-first, with indirect call value profiling); the program must link `libclang_rt.profile` and writes `.profraw` files at exit (`LLVM_PROFILE_FILE`) |

`SCALANATIVE_PGO_STATS=1` prints how many inlining decisions the profile changed.

A PGO link that fails is retried without PGO (build/03, 08) or the plain binary
is kept (build/04): a profile can never break a release.

### What Interflow does with the profile

Functions are ranked by entry count. *Hot* = the functions covering 99% of all
entry counts, *very hot* = 90% (`SCALANATIVE_PGO_HOT_COVERAGE`,
`SCALANATIVE_PGO_VERY_HOT_COVERAGE`). A function that is missing from the
profile is *unknown*, never cold (it may have been inlined away when the
profile was taken); only an entry count of 0 is cold.

* hot call (caller and callee hot): the callee is inlined up to
  `smallFunctionSize * 4` instructions (`SCALANATIVE_PGO_HOT_FACTOR`) instead of
  only when it is small; the allocation-driven inlining limit doubles;
* cold call (caller or callee never ran): only functions that are small anyway
  are inlined, which keeps cold code out of the IR LLVM has to compile;
* very hot method, in its own body (not in an inlined callee): the
  `release-full` machinery runs even in `release-fast`/`release-size` — wide
  polymorphic inlining (up to 16 classes, 2–4 implementations) and call-site
  specialization (`Sig.Duplicate`). Incremental reprocessing is off while a
  profile is loaded, since specialization breaks its reuse argument.

## Why two profiles per binary

LLVM's counters are tied to the CFG of the IR they were collected on, and
Interflow's decisions change that IR. So the pipeline (`build/pgo/pgo-lib.sh`)
runs in two rounds:

1. **gen1** link with `SCALANATIVE_PGO_GEN=1`, **train1** run the workload, giving
   the function entry counts of the unguided build (`prof1`, the Interflow
   profile);
2. **gen2** link again with Interflow guided by `prof1` *and* instrumented,
   **train2** run the workload again → `prof2`, the LLVM profile of the IR that
   will actually ship;
3. **final** link with both profiles, exactly like a release build does.

## JVM links (`SCALINO_JVM_LINK=1`)

CI links scalino-dotc, scalino-lsp and the CLI with `LinkDriver` on a JVM
(`build/link-lib.sh`) instead of the native linkdriver: no direct codegen, so the
IR is textual `.ll` and clang compiles it. PGO works there too, with clang doing
what the direct codegen does itself (patch `scala-native-0082`):
`SCALANATIVE_PGO_GEN=1` adds `-fprofile-generate -mllvm -pgo-instrument-entry=true`
to the clang call of every `.ll` file, `SCALANATIVE_PGO_USE=<.profdata>` adds
`-fprofile-use=`. The Interflow profile (`SCALANATIVE_PGO_PROFILE`) is the same.

The IR differs from the direct codegen's, so the JVM link has its own profiles,
`pgo/<name>.jvm.{llvm,interflow}.gz` (`pgo_key` in `build/pgo/pgo-lib.sh`), taken
by the same `build/pgo/pgo-<name>.sh` scripts with `SCALINO_JVM_LINK=1`.
The `Refresh PGO profiles` workflow (`workflow_dispatch`) does that on a CI runner
-- with the toolchain, clang and `SCALANATIVE_LINK_THREADS` of the release build,
so the CFG hashes match -- and opens a PR with the new files. Until they are
committed a JVM release link simply has no PGO. scalino-linkdriver is always
linked natively and keeps its direct-codegen profile.

## Refreshing the profiles

```
build/all.sh                                  # a finished build in dist/
build/pgo/refresh-profiles.sh                 # linkdriver dotc lsp, a couple of hours
build/pgo/refresh-profiles.sh dotc            # or just one
git add pgo && git commit
```

Needs `llvm-profdata` on `PATH` (Homebrew/apt LLVM) and the instrumented
binaries are linked with the compiler-rt profile runtime of `$CLANG`. Stale
profiles are harmless: a function whose CFG changed just loses its profile
(LLVM) and an unknown function is treated as unknown (Interflow). Refresh after
changes that move a lot of code, or when a release's PGO speedup regresses
(`build/pgo/bench-dotc.sh <dotc A> <dotc B>`).

Workloads (what the instrumented binaries run):

* **linkdriver** — relinking `LinkDriver` itself;
* **dotc** — compiling the Scala Native tools sources with the Scala Native
  plugin (`build/pgo/dotc-workload.sh`);
* **lsp** — `build/lsp-trace-drive.py`: a real-editor-shaped LSP session
  (initialize, didOpen/didChange, hover, definition, completion, references,
  rename, document/workspace symbols) against real compilation of
  `build/lsp-trace-fixture` (`LSP_RUNS` times, default 1). The language server embeds the
  presentation compiler, so this exercises the compiler paths that matter for
  interactive use. Needs `build/08` to have run (it leaves the link classpath in
  `selfhost/lsp-link.cp`) and `dist/scalino`.

## Portability

The LLVM profile is committed in LLVM's *text* format, which every LLVM version
reads (the raw/indexed formats are not forward compatible). LLVM's
`pgo-instr-use` itself only reads the *indexed* format (and fails silently on
anything else, which the linker now reports), so `pgo_prepare` converts the text
profile with the `llvm-profdata` of the LLVM the build links against; it must be
on `PATH` (the Homebrew/apt LLVM the build already requires ships it). Without it
the LLVM part is skipped with a warning. Interflow's profile is keyed by the
mangled symbol names, so one profile serves all targets. It is collected on
macOS arm64; on other targets the functions whose IR differs (platform specific
code) simply do not match and are optimized as before.

## Gotchas found on the way

* `SCALANATIVE_INLINE_STATS=1` builds a string per inlining decision — far too
  slow for dotc; use `SCALANATIVE_PGO_STATS`.
* Native link pool threads now get 64MB stacks (patch `scala-native-0081`,
  `SCALANATIVE_LINK_STACK_MB`), like the JVM driver's `-Xss64m`; with the OS
  default (512KB on macOS) the escape analysis recursion overflowed on dotc-sized
  inputs.
* Linking dotc/lsp needs `SCALANATIVE_EVAC_ENABLE=1`. The long LLVM-C calls
  (`LLVMRunPasses`, `LLVMTargetMachineEmitToFile`, `LLVMVerifyModule`,
  `LLVMDIBuilderFinalize`, module/context disposal) are `@blocking` externs
  (patch `scala-native-0080`), so the GC can collect while a codegen thread is
  inside LLVM; unannotated they looked like a stuck thread, and one huge function
  exceeded the GC's 60s safepoint timeout and aborted the link. The instrumented
  pipeline links still set `SCALANATIVE_GC_SYNC_TIMEOUT_MS=0` as a precaution.
* Don't run several dotc links at once on a 16GB machine.
