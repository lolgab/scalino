# 03: linkdriver allocation hotspots -> concrete patches

## Summary

The 2026-10-25 allocation profile of `scalino-linkdriver` linking dotc (4.4B objects, 203GB) predates
patch 0087 (`localNames`/`addMissing`/UseDef/InstructionBuilder fixes, now folded into
`scala-native-0004`/`0005`). The two sources it named first are already fixed. What is left is mostly
**Interflow's per-block state copying**: every block makes two deep copies of the full symbolic
state, and every merge walks every SSA local ever defined. It is O(blocks x locals) per function,
and it is the likely source of the 81GB of `ObjectArray`. Smaller leftovers: virtual-dispatch target
sets rebuilt on every call (`HashMap.get -> Some`), boxed `nir.Local`/`Long` keys, and
`UnrolledBuffer`+`GenericClassTag` per CFG block. This doc maps each hotspot to a code site and a
patch, sorted by payoff/risk.

## Current state

### What is already fixed (verified in `vendor/`, which is regenerated from patches)

| profile line (2026-10-25) | fix | where now |
|---|---|---|
| `OpenHashMap.put` -> `Some`+`OpenEntry` 90M each (`State.localNames`) | `LongMap[String]` | `interflow/State.scala:36-42`, patch 0005 |
| `LongMap.foreach` Tuple2 + boxed Long 68M (`addMissing`) | `LongMapOps.addMissing` via `foreachKey` | `interflow/package.scala`, patch 0005 |
| UseDef `UnrolledBuffer` ~200M | rewrite | `interflow/UseDef.scala:12-14`, patch 0005 |
| `InstructionBuilder` UnrolledBuffer | `ArrayBuffer(4)` | `nir/InstructionBuilder.scala:9-11`, patch 0004 |
| EA `Tuple2$mcII$sp` 66M | union without Stack/tuples | `interflow/EscapeAnalysis.scala:706` |

The memory note for 0087 says it gave allocs -16% / bytes -20% on a self-link sample. **The ranking
below has not been re-measured since then** (see step 0).

The memory note also says the ControlFlow edge change was reverted "for binary-compat". That
constraint is gone: since the rebase, `build/01a-publish-scala-native.sh` builds every SN module from
`vendor/` (`PROJECTS=(... nir3 ... tools3 ...)`), so `nir` can change freely.

### Measurements taken for this doc

Machine: M-series mac, brew `scalino 0.0.31` (Scala Native 0.5.13-SNAPSHOT, release-fast tools).
Workload: `~/scala/ape` (8235 classes / 51943 methods after classloading), copied to the scratchpad.
Each run forces a fresh link by moving `.scalino-build/Main` away first.

```sh
cd $SCRATCH/ape && mv .scalino-build/Main ../ape-link-old
PATH=/opt/homebrew/opt/llvm/bin:$PATH SCALANATIVE_GC_LOG_LEVEL=info GC_LOG_FILE=$SCRATCH/gc4.log \
  /usr/bin/time -l scalino package . -o ../ape-bin --native-mode release-fast
grep linkdriver gc4.log | grep "collection finished"     # per-GC pause
```

| run | link total | Interflow (incl. EA) | EA | codegen | GCs / STW total | RSS |
|---|---|---|---|---|---|---|
| quiet, direct codegen (run 4) | 56.3s | 23.5s | 5.1s | 28.9s (overlaps compile) | 31 / **6.7s** | 6.5GB |
| quiet, `.ll` fallback (run 3) | 65.3s | 30.4s | 5.3s | 21.8s | 32 / 11.0s | 6.6GB |
| `SCALANATIVE_ESCAPE_STATS=1` (run 2) | 53.0s | 24.8s | 6.5s | 16.2s | - | 7.7GB |
| machine loaded by another link (run 1) | **553s** | 458s | 188s | 82s | - | 3.5GB |

- GC split in run 4 (from GC timestamps against phase boundaries): about **3.5s of STW falls inside
  the Interflow phase** (27 collections, growing from 0 to 332ms as the heap grows) and 3.2s inside
  codegen (4 collections, 286-1405ms). The heap grows about 10% per collection, up to 8.5GB.
- EA stats (run 2): `defines=51943 specs=27285 firstPass=67576 analyses in 2357ms, reanalyses=31242
  in 3549ms`, `summarize=704ms exclusive=5813ms`.
- Run 1 is a side finding, not an allocation one. With another agent's linkdriver running at 330%
  CPU, the same link took 10x longer, with **sys 298s > user 218s**. Samples show `swtch_pri` /
  `cthread_yield` (the `thread_yield` -> `sched_yield` spin in `gc/shared/ThreadUtil.c:32`, called
  from `YieldPointTrap.c:58`), and every STW collection pays it once cores are oversubscribed. Fewer
  collections reduce the exposure, but the real fix is in the GC (block instead of spinning). Worth
  its own idea.
- Side finding: the brew install fell back to `.ll` + clang ("no compatible libLLVM (via
  llvm-config)") because `/opt/homebrew/opt/llvm/bin` is not on `PATH`. That made the link 16%
  slower with 64% more GC time.
- `/usr/bin/sample` of run 1 (`/opt/homebrew/bin/sample` is a broken Python shim, so use the full
  path) does not give usable caller attribution, because the release binary's stacks unwind only one
  frame. Leaf-only top-of-stack: `Marker_markEdge` 10.3k, `Block_Sweep` 3.6k, `mutable.HashMap.get`
  1.9k, `mutable.RedBlackTree.g$1` 1.8k (MergeProcessor `todo`), `ConcurrentHashMap$KeySetView.add`
  1.4k (`Interflow.recordAsk`), `Class$$Lambda$6` / `Trait$$Lambda$1` /
  `PolyInline$$Lambda$3` (the `targets`/`polyTargets` closures). Parser scripts:
  `$SCRATCH/tools/parse_sample.py`, `callers.py`.

### Hotspot -> code site map

**H1. Two full state copies per visited block.**
- `MergeProcessor.visit` (`interflow/MergeProcessor.scala:584`) does
  `block.start = newState.fullClone(block.id)`. `block.start` is read in only one place, `advance()`
  (`:606`): `if (newState != block.start)`. `State.equals` (`State.scala` after `fullClone`) compares
  only `heap` and `locals`.
- `fullClone` (`State.scala:297`) copies seven things: `heap.mapValuesNow(_.clone())` (a new LongMap
  plus a copy of every instance), `locals`, `delayed`, `emitted`, `getClassCache`, `virtualNames`
  and `localNames`. `new State` also creates a fresh `InstructionBuilder` and `ArrayBuffer`.
- A single-predecessor merge does a second `fullClone` (`MergeProcessor.scala:79`), and
  `PolyInline.scala:175` does one per poly-inline target.
- `Instance.clone()` (`Instance.scala:21-25`) copies `EscapedInstance` and `DelayedInstance` even
  though both are immutable (`val` fields only; no code mutates them, and `materialize` *replaces*
  the heap entry). Only `VirtualInstance.values` is ever mutated in place, at `Eval.scala:279`
  (fieldstore), `:488` (arraystore) and `:464` / `State.scala:91` (fresh instances).
- `heap` and `locals` only ever grow during a function (addresses and SSA ids are never removed), so
  each clone costs O(size of the function so far). LongMap clones copy two capacity-sized arrays;
  `locals` of a 4k-local function means 8192-slot `long[]` + `Object[]` (~128KB) **per clone**. This
  is the best candidate for the profile's `ObjectArray` 420M / **81GB** (average 193B, a heavy tail),
  most of which the old profiler could not attribute ("Array.clone / copyOf").

**H2. Merges walk every local, including dead block-local temporaries.**
- `computeMerge.mergeLocal` (`MergeProcessor.scala:301-314`) runs for **every key of
  `headState.locals`**. Each call allocates an `ArrayBuffer` + array, then `values.toSeq` (an
  immutable copy: `::` cells), then `localNameOf`. Only then does `mergePhi` check
  `isSingleValue`, which is the common case.
- The heap merge (`:325`): `heap.keysIterator` (generic `Iterator[Long]`, so a boxed `Long` per key;
  Long boxes really allocate, see findings "Escape analysis, round 2"), `.filter(includeAddr)`, and
  per virtual instance `head.values.zipWithIndex.map` (a Tuple2 per field) plus `states.map` (a List
  per field).
- Most SSA locals in NIR are used only inside the block that defines them, but they are still carried
  forward in `locals`, cloned (H1) and merged. When their values differ on two incoming edges,
  `mergePhi` **materializes** them, so a virtual object referenced only by a dead temporary is forced
  to escape at a join. That costs code quality as well as allocations.

**H3. Per-MergeProcessor setup and bookkeeping** (one MergeProcessor per optimized function *and per
inlined callee*):
- `offsets: Map[nir.Local, Int]` (`:24-28`): `insts.zipWithIndex.collect{...}.toMap`, a Tuple2 per
  instruction and an immutable HashMap with boxed `nir.Local` keys (`Local` is an `AnyVal` over
  `Long`: `nir/Local.scala:4`).
- `todo = mutable.SortedSet.empty[nir.Local](using Ordering.by(offsets))` (`:30`): a red-black tree
  whose every comparison does two boxed-key HashMap lookups (`RedBlackTree.g$1` is #6 by self time).
- `MergeBlock.incoming/outgoing: mutable.Map[nir.Local, ...]` (`MergeBlock.scala:12-13`): boxed keys,
  and `merge(block)` does `block.incoming.toSeq.sortBy(_._1.id)` (`:64`).
- `currentSize()` (`:32-46`), called from `Inline.scala:55` for every inline decision: it evaluates
  `blocks.##` **twice**, and each hash goes through `MurmurHash3.mapHash -> LongMap.foreachEntry`
  with a `Function2[Any,Any,Unit]`, boxing every block id. The cache key is also wrong: the hash only
  changes when blocks are *added*, but the cached value is the sum of `b.end.emit.size`, which grows
  inside existing blocks. So `callerTooBig` can read a stale, smaller size.
- `State.heapClosure` (`State.scala:228`), `newEscapes` (`MergeProcessor.scala:106`) and
  `VirtualCollector.addrs` (`:124`) are `mutable.Set[Addr]` with `Addr = Long`, so each holds a
  boxed `Long` and a `HashSet$Node` per element. `inherit` (`State.scala:204`) builds two of these
  and a third with `++`. This matches the profile's `Long` 98M and `HashSet$Node` 103M.
  `materialize`'s `mutable.Map[Addr, nir.Val]` (`:320`) has the same problem.

**H4. Virtual-dispatch targets rebuilt on every query** (`HashMap.get -> Some` 108M):
- `Class.targets` / `Trait.targets` (`linker/Infos.scala:102-113`, `:189-201`) allocate a new
  `HashSet` and a closure. Then, for every allocated subclass/implementor, `resolve(sig)` =
  `responds.get(sig).orElse(defaultResponds.get(sig))` (`:186-188`) allocates a `Some` (sometimes
  two). Callers with no cache: `Eval.scala:325` (every `Op.Method` evaluated by Interflow),
  `PolyInline.polyTargets` (`PolyInline.scala:55-82`: an UnrolledBuffer, a Tuple2 per target and
  `toIndexedSeq`, for every method op), `NoOpt.scala:96`, `Reach.scala:217/1107`,
  `LowerKnowledge.scala:95`, and `VirtualTable.scala:35`. VirtualTable builds the whole set for
  every `(class, sig in calls)` just to test `.size > 1`.
- EA already caches (`EscapeAnalysis.scala:623-641`, `scopeTargets`), but each lookup allocates a
  `(Global, Sig)` tuple key plus an `Option`.

**H5. Extractor allocations in Eval's hot match arms.** `VirtualRef.unapply` (`InstanceRef.scala:30`)
returns `Some((kind, cls, values))`, i.e. a Some and a Tuple3 for every fieldload/fieldstore/
arrayload/arraystore on a virtual object. The `(addr)` overloads also allocate a `Val.Virtual`.
`InstanceRef.unapply` returns `Some(state.deref(addr).ty)`, and `Instance.ty` builds a new
`nir.Type.Ref(cls.name, exact = true, nullable = false)` on every call (`Instance.scala:12-19`).
`DelayedRef` and the others are the same.

**H6. `UnrolledBuffer` + `GenericClassTag` per CFG block** (UnrolledBuffer family ~310M,
`GenericClassTag` 83M):
- `nir/ControlFlow.scala:23-24`: two `UnrolledBuffer.empty[Edge]` per `Block`. Each one is a fresh
  `GenericClassTag` (Scala Native's `ClassTag.apply` -> `cache.computeTag` allocates;
  `scalalib/overrides-3/scala/reflect/ClassTag.scala.patch`), the buffer, an `Unrolled` node and a
  **32-slot array** created through `reflect.Array.newInstance`. That is about 330B per buffer and
  ~660B per block, for 1-2 edges. Graphs are built per function in `UseDef.scala:175`,
  `Opt.scala:33` (`removeDeadBlocks`) and `LinktimeValueResolver.scala:247`.
- The same pattern appears once per merge (`MergeProcessor.scala:103` `mergePhis`), once per method op
  (`PolyInline.scala:73`, `:148`), and once per `Lower.onDefns` batch (`codegen/Lower.scala:151`).
- `State.allocClass` (`State.scala:58`): `fields.toArray[nir.Val]` means a GenericClassTag and a
  reflective array for every virtual allocation.
- The same `ClassTag.apply` allocation also costs dotc 2.3M allocations (3%) on its typecheck
  workload (findings, "Escape analysis, round 2").

**H7. Remaining items from the old ranking that are not Interflow:** `CharArray` 707M / 15.7GB. No
string building on a hot path survived review. EA builds reason strings only behind `recordReasons`
or by-name parameters. `inHashRange(d.name.show, ...)` (`EscapeAnalysis.scala:143,166`) evaluates
`show` eagerly, but only per stack candidate (a few thousand). The profile ran with the
site-profiling build, whose own instrumentation may account for part of this. The old profile cannot
tell; step 0 decides.

## Proposed design

The fixes are grouped by whether they change optimizer output. **"Identical" fixes must produce a
byte-identical binary** (the link is deterministic: `tests/jar-stamps/run.sh` already relies on
identical binaries). That gives them a cheap, strong check.

**P1 (identical) - lighter state snapshot + immutable instances shared.**
- `Instance.clone()` returns `this` for `EscapedInstance` / `DelayedInstance`.
- Add `State.snapshot(): State` (or a small `Snapshot(heap, locals)` class) that copies only `heap`
  (the instance copy above) and `locals`, and use it at `MergeProcessor.scala:584`. `advance()`
  compares `newState` against the snapshot with the same `heap == && locals ==` semantics.
- Single-incoming merge (`:79`): keep `fullClone`. The predecessor's `end` state is still referenced
  through `incoming` for later merges and invalidation, so it must not be mutated.

**P2 (identical) - copy-on-write virtual instances (follow-up to P1, only if P1 + P3 leave clones
hot).** `fullClone` / `snapshot` would copy `heap` as a plain `LongMap.clone()` (array memcpy, no
per-instance copy). The two in-place writers (`Eval.scala:279`, `:488`) would go through
`state.writableValues(addr)`, which copies the instance on the first write in a state that does not
own it (an owner tag in `VirtualInstance` set by `alloc`/clone-on-write). The writer list must be
re-audited (grep `values(` over `interflow/`); a missed writer is a silent miscompile.

**P3 (output changes) - drop block-local temporaries from `State.locals`.**
Per MergeProcessor, scan the original `insts` once (it already walks them for `offsets`). Find the
locals **used outside their defining block** (including block params used in successors and values
passed in `Next.Label` args) and collect them in a `LongMap`-backed set. When a block finishes
(`visit`, after `eval.run`), rebuild `newState.locals` keeping only those keys plus `newVar` locals
(negative ids, always kept). Dropping a value that a later block reads would crash `locals(id)`
loudly, not miscompile, which makes this safe to try. Effects: smaller `locals` for every later
clone and merge; fewer phis; fewer forced materializations at joins (better code, so the output
changes). Validation needs the differential tools, not byte equality.

**P4 (identical) - allocation-free merge loops.** In `mergeLocal`, loop over `states` with
`getOrNull` and compare with the head value first. Allocate the `Seq` only when the values differ
(that is when `mergePhi` needs it). In the heap merge, use `heap.foreachKey` with an explicit
`includeAddr` check instead of `keysIterator.filter`, and a `while` loop over field indices instead
of `zipWithIndex.map`. Use a LongMap-backed set in `heapClosure`, `newEscapes`, `VirtualCollector`
and `materialize` (a tiny `LongSet` in `interflow/package.scala`, or `LongMap[Unit]`).

**P5 (identical) - MergeProcessor indices.** Make `offsets` a `LongMap[Int]` filled by a `while` loop
(the public `offsets: Map[Local,Int]` is passed to `Eval.run`; check its uses and switch those too).
Replace `todo` with a `java.util.BitSet` over label offsets plus an `Array[nir.Local]`
offset->label. `head` is `nextSetBit(0)`; `retain` iterates the set bits. Iteration order is the
same as the current offset-ordered SortedSet. Key `MergeBlock.incoming/outgoing` by `Long` and keep
the sorted `incoming` order explicit.

**P6 (output may change) - `currentSize`.** Track the size directly: sum `b.end.emit.size` over
`blocks.valuesIterator` with no hashing (O(blocks), no allocations, at least as cheap as the current
double hash). This also fixes the stale-cache bug, so inlining decisions may change (callers that
grow inside a block now hit `maxCallerSize` correctly). Ship separately from P4/P5 so the identical
fixes keep their byte-equality check.

**P7 (identical) - memoized dispatch targets.** Add a per-`ReachabilityAnalysis.Result` cache
(`ConcurrentHashMap[TargetsKey, Targets]`, because Interflow runs on several threads; the key is a
small final class or packed ids). `Targets` holds `classes: Array[Class]` and
`impls: Array[nir.Global.Member]`, both in implementor order, plus a distinct-impl set. Add
`Class.resolveOrNull(sig)` (`responds.getOrElse(sig, null)`, then `defaultResponds`) so
`Some`/`orElse` go away. Use the cache from `Eval.scala:325`, `PolyInline.polyTargets` (no
UnrolledBuffer, no tuples), `EscapeAnalysis.targetsOf` and `LowerKnowledge.targets`. Have
`VirtualTable` use an early-exit `hasMultipleTargets(cls, sig)`. Keep `ScopeInfo.targets` for
Reach, because its sets change during Reach. The cache must live in the *Result*, never in the
infos: the long-running linker reuses `Reach` and its infos across links (patch 0048/0020), and its
`allocated`/`subclasses` keep growing.

**P8 (identical) - extractors and `Instance.ty`.** Rewrite `VirtualRef` and friends as Scala 3
name-based extractors that return the `VirtualInstance` itself (`isEmpty = (this eq null)`), and
cache `ty` in `VirtualInstance` (a `val`, since `cls` is fixed). Callers that destructure
`(kind, cls, values)` change to field access. The change is local to `interflow/`.

**P9 (identical) - UnrolledBuffer/ClassTag.** `ControlFlow.Block.inEdges/outEdges` ->
`new ArrayBuffer[Edge](2)` (no ClassTag, 2-slot array). `mergePhis` and `PolyInline` -> `ArrayBuffer`.
`allocClass` -> fill an `Array[nir.Val]` directly. Optionally (decide separately, since it affects
every user program): cache `ClassTag.apply` in the scalalib override with a `ConcurrentHashMap[Class,
ClassTag]`. On SN, a GenericClassTag allocation is a bump-pointer allocation, cheaper than a CHM
lookup. The win is only that `newArray` stops re-allocating the tag, so call-site fixes come first.

## Implementation steps

0. **Re-profile (half a day, needs one ~16 min build).** Follow the memory-note recipe
   (`build/escape-analysis/make-prof-nativelib.sh`, then `build/04-build-scalino-linkdriver.sh` with
   `SCALINO_NATIVELIB_JAR=...nativelib-prof.jar`; note `04a-patch-tools.sh` no longer exists, the
   tools come from `01a`). Profile the current tree linking **ape** (55s, see above) rather than
   dotc (35 min profiled). Also add temporary counters, gated by a diagnostic env var outside the
   link cache key (e.g. `SCALANATIVE_INTERFLOW_STATS=1`): number of `fullClone`/`snapshot` calls,
   Σ `locals.size`, Σ `heap.size`, Σ `emitted.size` per clone, merges, `mergeLocal` calls, and how
   many locals were single-valued. That confirms or refutes H1/H2 as the `ObjectArray` source before
   writing P2/P3.
1. **P1 + P9 (ControlFlow, allocClass)**: `interflow/Instance.scala`, `interflow/State.scala`,
   `interflow/MergeProcessor.scala`, `nir/ControlFlow.scala` -> patches `scala-native-0005-interflow`
   and `0004-nir-util`. Check: byte-identical ape binary and dotc link, `tests/escape-analysis/run.sh`.
2. **P4 + P5**: `MergeProcessor.scala`, `MergeBlock.scala`, `State.scala`, `Eval.scala` (offsets
   type), `interflow/package.scala` (LongSet). Byte-identical check.
3. **P7 + P8**: `linker/Infos.scala`, `linker/ReachabilityAnalysis.scala` (cache holder),
   `interflow/Eval.scala`, `PolyInline.scala`, `EscapeAnalysis.scala`, `codegen/LowerKnowledge.scala`,
   `codegen/VirtualTable.scala`, `interflow/InstanceRef.scala` -> patches 0005/0006/0007. Check that
   `LowerCache` subjects still record what `targets` touches (`LowerKnowledge.targets` records every
   implementor as a subject; keep that recording, and memoize only the computation).
   Byte-identical check, plus `SCALINO_LOWER_CACHE=1 verify` over the watch edit script.
4. **P6** alone: measure the ape/dotc link and binary size, then run `pos-differential.sh` and the
   wes harness (`cmp` against `jvm.raw`, as in the stack-object fix validation).
5. **P3** alone: same validation as 4, plus `SCALANATIVE_STACK_POISON=1` on
   `examples/escape-analysis` (sum=1412) and `mutate-deepfix` still caught. Compare
   `Allocated N objects on the stack` and binary size (expected to go up / down).
6. P2 only if step 0's counters still show per-instance clone volume after 1-5.
7. Re-profile (step 0 again), write the findings entry with before/after numbers, and regenerate the
   patches after each batch (vendor resets have wiped work before).

## Effort estimate and risks

| step | effort | risk |
|---|---|---|
| 0 profile + counters | 0.5d | none (diagnostic) |
| 1 P1 + P9 | 0.5d | low; byte equality catches mistakes |
| 2 P4 + P5 | 1d | low-medium: `todo` order must stay offset order, `incoming` order must stay sorted by id |
| 3 P7 + P8 | 1d | medium: the cache must be per Result (long-running linker); LowerCache dependency recording |
| 4 P6 | 0.5d + validation | output changes (inlining); a size/perf regression is possible either way |
| 5 P3 | 1-1.5d + validation | output changes; a wrongly dropped local fails loudly (`locals(id)` NoSuchElement -> BailOut?) - **check that BailOut does not swallow it silently**, otherwise the function silently goes unoptimized |
| 6 P2 | 1d | highest: a missed in-place writer is a silent miscompile |

Total ~5 days without P2. Rebuilding dist for timing comes on top (one 04 build per batch).

## Expected payoff

Real numbers need step 0. Estimates from today's measurements and the old profile:

- **Interflow allocation volume**: P1 removes 5 of the 7 copied structures from one of the two
  per-block clones, and P3 shrinks `locals` (the largest map) by the fraction of block-local
  temporaries. In typical NIR most `let`s are consumed in their own block, so plausibly 50-80% of
  `locals`. Combined, the expected drop is **40-60% of Interflow bytes**. The `ObjectArray` 81GB line
  is the one that should move; P4/P5/P7/P8/P9 together target the object-count lines (`Some`,
  `Tuple2`, `::`, `Long`, `HashSet$Node`, `nir.Local`, UnrolledBuffer, ClassTag: ~1.6B of the 4.4B
  objects in the old profile). Assume a third to half of those go.
- **Time on ape (56s link, direct codegen)**: Interflow GC is 3.5s, so roughly -1.2 to -2s of STW
  from fewer collections. Mutator: no clone memcpy, no target-set rebuilds, no red-black tree
  compares, plausibly -10-20% of the 18.4s non-EA Interflow CPU wall, i.e. -2 to -3.5s. **Total
  estimate -3 to -5s (6-9%) per cold ape link**, and proportionally more on dotc, which has larger
  functions (quadratic term). RSS: the heap grows ~10% per collection, so fewer collections in the
  Interflow phase also lower the peak heap somewhat. The codegen-phase collections (3.2s, largest
  pauses) are untouched by this doc.
- Watch relinks: the gain there is small, because Lower dominates relink allocation (84%, findings
  "Where a relink allocates"); not measured here. P9's ControlFlow change helps
  `LinktimeValueResolver`, which does run per link.
- P3 may also **improve generated code** (fewer forced materializations at joins), measurable as
  more stack/scalar-replaced objects. That is unquantified.

## Open questions for Lorenzo

1. P3 and P6 change optimizer output. Are they OK to ship behind the same validation bar as the
   escape-analysis work (pos-differential + wes + poison), or should they be env-gated first?
2. P7's per-Result targets cache: is `ReachabilityAnalysis.Result` re-created on every link in
   `--long-running` mode (it appears to be, but patch 0048 keeps `Reach`)? If not, where should the
   cache be invalidated?
3. The `ClassTag.apply` cache in scalalib changes every user program's runtime. Do it, or keep the
   fix to tool call sites only?
4. Profile target for step 0: ape (55s, representative user program) or the dotc self-link (the
   historical baseline, ~35 min profiled)?
5. Two side findings outside this idea's scope: (a) GC STW spins on `sched_yield` and degrades 10x
   with sys > user under CPU oversubscription (553s vs 56s); (b) the brew install silently falls back
   to `.ll` + clang when `llvm-config` is not on `PATH` (`/opt/homebrew/opt/llvm/bin` is
   keg-only). Should these become separate ideas?

## Implementation status (2026-11-20)

`patches/scala-native-0040-interflow-alloc-reductions.patch` (uncommitted) has P1, P4 (mergeLocal
fast path, heap merge without `keysIterator`/`zipWithIndex`), P5 (bitset worklist instead of the
`SortedSet`, `offsets` without `zipWithIndex`, `currentSize` hashes once), P7 (per-`Result` caches
`targetsOf`/`polyTargetsOf`, `Class.resolveOrNull`, `hasMultipleTargets`) and P9 (ControlFlow edges
as `ArrayBuffer(2)`). P8, P2 and P6 have not been started.

Validation on the JVM link driver, comparing an overlay of the touched sources built from the
baseline against one built from the patched tree (same file set; scripts in the session
scratchpad: `tools/overlay.sh`, `link-ape.sh`, `link-dotc.sh`):
- **Byte-identical binaries**: ape `57596cba...`, dotc `5f683c82...` for both overlays.
- Interflow time without EA (Optimizing minus "Analyzing object escapes"), machine load ~7-8:
  dotc 34.5s / 31.6s before -> 19.6s / 21.9s after (two runs each). Ape: 9.9s / 9.3s -> 7.8s /
  7.1s.
- **Not yet measured natively.** That needs `01a-publish-scala-native.sh` + `04-build-scalino-linkdriver.sh`,
  then `tests/escape-analysis/run.sh`, the POISON example, and the ape GC-log run from
  "Measurements" above.

P3 (dropping block-local temporaries) was implemented and then reverted. On dotc the Interflow time
did not change (22.4s), the binary grew by 16KB and 2 fewer objects were stack allocated, so it
changed output for no gain. Clone/merge volume is evidently not dominated by block temporaries.

### Native validation (2026-11-20)

- `build/01a-publish-scala-native.sh` with 0040 applied, then `build/04-build-scalino-linkdriver.sh`
  with `SCALINO_LINK_NO_DIST=1` (dist/ untouched).
- The new linkdriver was tested from a scratch copy of dist/ with `lib/` pointing at the fresh ivy
  nativelib jars. dist/'s own jars predate patch 0038, and linking against them fails with
  `Undefined symbols: _scalanative_StackOverflowGuards_pendingCount`, which is unrelated to 0040.
- `tests/escape-analysis/run.sh`: PASS (off, on, poisoned, mutation caught).
- `examples/escape-analysis` with `SCALANATIVE_STACK_POISON=1`: `sum=1412`.
- Native ape A/B (2026-11-21), linkdrivers built from the same vendor tree with and without 0040,
  runs interleaved, release-fast, fresh link each time:

  | run | Optimizing | Generating | link total | GCs / STW | user | sys |
  |---|---|---|---|---|---|---|
  | base1 | 102.1s | 53.5s | 166.5s | 31 / 41.5s | 396s | 142s |
  | base2 | 79.1s | 48.7s | 138.1s | 31 / 29.5s | 374s | 85s |
  | 0040 run 1 | 70.2s | 47.1s | 130.7s | 30 / 32.2s | 364s | 101s |
  | 0040 run 2 | 67.7s | 44.3s | 124.5s | 30 / 29.2s | 352s | 82s |
  | 0040 run 3 | 62.0s | 41.9s | 116.0s | 30 / 26.0s | 357s | 70s |

  All five binaries are byte-identical (sha1 `4cf60e345beb...`). The machine was memory-constrained
  (about 8.5GB of swap in use, about 4.7GB in the compressor), so GC pauses were 4-5x the 6.7s
  measured on a quiet machine and the absolute numbers are inflated. The comparison is still fair:
  Optimizing went from 79-102s to 62-70s, and the link total from 138-167s to 116-131s.
