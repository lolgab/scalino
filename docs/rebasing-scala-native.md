# Moving to a newer Scala Native

Scalino builds Scala Native from source: `vendor/scala-native` is upstream `main` at
`SCALA_NATIVE_REF` (`versions.env`) plus `patches/scala-native-*.patch`. `build/01a-publish-scala-native.sh`
runs Scala Native's own sbt build and publishes every module the toolchain uses to `~/.ivy2/local` under
`SCALA_NATIVE_VERSION`; `build/01-fetch-deps.sh` resolves them from there. No released Scala Native jar is
involved.

## The patch series

The patches are split by area, with disjoint file sets, so they are independent of each other:

| patch | contents |
| --- | --- |
| `selfhost` | the few `nscplugin` hunks that only compile against scalino's self-hosted dotc; `01a` reverts it while sbt runs |
| `nscplugin-tagging` | SMI / pointer tagging, enum-with-explicit-nulls |
| `javalib` | zip file system provider, provider-generic `Files`, SMI boxes, ... |
| `nir-util`, `interflow`, `codegen`, `linker`, `build-tools` | the `tools`/`nir`/`util` modules: escape analysis, direct LLVM codegen, incremental builds, link-time pruning, PGO |
| `runtime-scala` | `nativelib` Scala code: monitors, `SmiBox`, thread slots |
| `gc-immix-commix` | Immix/Commix: evacuation, compact headers, shared recycle pool, release to OS |
| `gc-shared-and-runtime-c` | trampoline (aarch64 resume via sigreturn), stack overflow poll count, compact headers (RTTI space, lock stack) |
| `docs` | `gc-safepoint-trampoline.md` |

## Bumping `SCALA_NATIVE_REF`

Replaying 80-odd stacked patches one by one re-resolves the same conflicts every time upstream moves code, so
do one merge instead:

1. Clone Scala Native (full history). Make a branch `base` at the old upstream commit and apply the series
   to it, one commit; make `new-base` at the new upstream commit.
2. `git checkout -b merged new-base && git merge --no-commit <patched branch>`, resolve the conflicts once.
   Where upstream moved code (it moved Immix/Commix shared code to `gc/immix_commix/` once), port the
   patched behaviour to the new place instead of keeping the old file.
3. Build with `sbt` (revert the `selfhost` patch first, as `01a` does) and fix what 3.x/Scala Native changed.
4. `build/tools/regen-scala-native-patches.py <clone> new-base merged patches` rewrites the series and checks
   that applying it to `new-base` gives exactly `merged`.
5. Bump `SCALA_NATIVE_REF`/`SCALA_NATIVE_VERSION` (`project/ScalaNativeBuildInfo.scala`'s `baseVersion`) in
   `versions.env`, and the values in `build/selfhost/ScalaNativeBuildInfo.scala`.

Check each patched area against upstream before keeping it. Upstream has already absorbed several of our changes
(the x86_64 trampoline red-zone fix, `Path.toUri`, `Arrays.fill`, the growth-deferral constants, the
`Files` probes, interior-pointer conservative marking, the shared yieldpoint trap page, the inline module-load
fast path, the connect `SO_ERROR` check, the `\e` regex escape, ...).

Compact headers keep upstream's RTTI, which depends only on a type and its ancestors; no whole-program class ids.
Codegen lays every RTTI out in one global, the RTTI space (`codegen/RttiSpace.scala`), and each RTTI symbol becomes
an alias of its place there. The object header is then a 32-bit word, the RTTI's offset in that space, with the
lock state in its 2 lowest bits (like HotSpot's compressed class pointers). Locking is lightweight locking as in
HotSpot (`runtime/monitor/CompactMonitor.scala`): a per-thread lock stack in C (`lock_stack.c`) for fast locks,
inflated monitors found by object address in `MonitorTable`. Arrays keep a 16-byte header (upstream's stride
field, padded); byte arrays keep their elements at offset 24 for jsoniter-scala.

## Binary compatibility with libraries built for released Scala Native

The link name of an extern (`@name`) is part of its NIR global, so renaming one that already exists in a
release breaks every library compiled against that release: "Unknown extern symbol
scala.scalanative.posix.sys.socket$.socket". Upstream keeps such renames linkable with `externRedirect`
(#5190) and checks the NIR externs against the previous releases in its build (#5191), so scalino carries no
patch for it. After bumping `SCALA_NATIVE_REF`, run `tests/native-compat/run.sh`.
