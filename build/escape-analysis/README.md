# Escape analysis tooling

Development and validation tools for patches 0061 / 0064 / 0068 (the escape
analysis, stack-allocated objects and allocation-oriented inlining in
`vendor/scala-native/tools/.../interflow/`). The design notes and the
measurements behind them are in `docs/findings.md`, "Escape analysis, round 2".

## Dev loop (no sbt, no full rebuild)

```sh
build/escape-analysis/dev-link.sh <tag>     # compile the analysis sources, link dotc on the JVM (~4 min)
build/escape-analysis/bench-dotc.sh $EA_WORK/<tag>/dotty.tools.dotc.Main
```

`EA_WORK` defaults to `.build-work/ea-dev`. Do **not** use `vendor/sn-sbt`: it is
out of sync with `vendor/scala-native`.

Link-time environment variables read by the analysis (all optional):

| variable | effect |
| --- | --- |
| `SCALANATIVE_STACK_OBJECTS=0/1` | force the stack allocation transform off/on (on by default in release modes) |
| `SCALANATIVE_INLINE_ALLOC=0/1/<n>` | allocation-oriented inlining off/on, `<n>` = maximal callee size |
| `SCALANATIVE_STACK_POISON=1` | **overwrite the stack objects of a frame when it is left** (see below) |
| `SCALANATIVE_STACK_INCLUDE` / `_EXCLUDE=<regex>` | restrict the transform to classes matching / not matching |
| `SCALANATIVE_STACK_HASH=lo:hi:mod` | keep only the sites whose (function, id) hash mod `mod` is in `[lo,hi)`: bisecting a miscompile |
| `SCALANATIVE_STACK_VERBOSE=1` | print every converted site (`kind in function #id`) |
| `SCALANATIVE_STACK_EXIT=1` | exit right after the transform (skips codegen) |
| `SCALANATIVE_ESCAPE_REPORT=1` | static report: eligible/escaping sites and the top reasons; `SCALANATIVE_ESCAPE_EXIT=1` exits right after |
| `SCALANATIVE_ESCAPE_KIND_REASONS='kind;kind'` | per class: top escape reasons, with the chain of callees that explains each one |
| `SCALANATIVE_ESCAPE_WHY=<fn substrings>` | why does each parameter of the matching functions escape |
| `SCALANATIVE_ESCAPE_DUMP` / `_TRACE=<fn substring>` | print the NIR / the per call analysis of matching functions |
| `SCALANATIVE_ESCAPE_STATS=1` | analysis time breakdown and counters |
| `SCALANATIVE_INLINE_STATS=1` | outcome of the allocation-oriented inlining decisions |
| `SCALANATIVE_SITE_PROFILE=<file>` | instrument every allocation site with a hit counter, write the site table to `<file>` |
| `SCALANATIVE_EA_X=a,b` | experiments: `nospec` (no specialization), `monitor`, `matcherror`, `throwcap` (the last three are **unsound**, measurement only), `mutate-deepfix` (re-introduces a fixed soundness bug, see below) |

## Measuring allocations

```sh
build/escape-analysis/make-prof-nativelib.sh                 # nativelib whose GC counts allocations
PROF=1 build/escape-analysis/dev-link.sh prof                # dotc linked against it
build/escape-analysis/bench-dotc.sh $EA_WORK/prof/dotty.tools.dotc.Main /tmp/alloc.prof   # top allocated classes
```

Per allocation site (which sites still allocate, and why they escape):

```sh
SCALANATIVE_SITE_PROFILE=/tmp/sites.tsv PROF=1 build/escape-analysis/dev-link.sh sites
SCALINO_SITE_FILE=/tmp/hits.txt build/escape-analysis/bench-dotc.sh $EA_WORK/sites/dotty.tools.dotc.Main
python3 build/escape-analysis/site-report.py /tmp/sites.tsv /tmp/hits.txt 40
```

## Validating soundness

A stack object that outlives its frame is silent: the dead stack memory is
usually intact for a while. **Always validate with `SCALANATIVE_STACK_POISON=1`**,
which makes every function overwrite its stack objects with `0xA5` before it
returns or throws, so a dangling reference crashes right away:

```sh
SCALANATIVE_STACK_POISON=1 ./dist/scalino run examples/escape-analysis/Main.scala --native-mode release-fast   # prints sum=1412
```

`examples/escape-analysis` must also *fail* when the analysis is made unsound:
`SCALANATIVE_EA_X=mutate-deepfix` re-introduces a real bug found in review (a
callee that leaks the *contents* of a parameter was not reflected in the
caller's summary when the caller never loaded those fields itself) and the
program then prints a different sum. When adding an analysis feature, add a
mutant like that and make sure the example catches it.

Differential test of a compiler binary built with the analysis against a known
good one, over Scala 3's own `tests/pos` programs:

```sh
build/escape-analysis/pos-differential.sh dist/scalino-dotc /tmp/new.txt
build/escape-analysis/pos-differential.sh /path/to/known-good/scalino-dotc /tmp/old.txt
diff <(sed 's/ sz=[0-9]*//' /tmp/old.txt) <(sed 's/ sz=[0-9]*//' /tmp/new.txt)
```

(`parent-refinement.scala` prints its diagnostics in a different order from run
to run, in both binaries.)

## Bisecting a miscompile

If a binary linked with the analysis misbehaves, find the stack-allocated site
responsible: relink with `SCALANATIVE_STACK_HASH=0:1:2`, then `1:2:2`, and so on
(`0:1:4`, `2:3:4`, ...), keeping the half that still fails; list the remaining
sites with `SCALANATIVE_STACK_VERBOSE=1 SCALANATIVE_STACK_EXIT=1`, and use
`SCALANATIVE_ESCAPE_DUMP` / `SCALANATIVE_ESCAPE_TRACE` on the function to see
what the analysis concluded.
