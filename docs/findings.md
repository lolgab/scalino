# Findings from the native-image spike

Background for the comments in `build/*.sh`. Scala 3 only, GraalVM
native-image approach (see decision log below).

## Decisions made

- **Packaging strategy: GraalVM native-image**, not full self-hosting (compiling
  dotc itself down to Scala Native). Self-hosting was ruled out as multi-year/
  high-risk given dotty's deep ties to the JVM (zinc, java.nio, reflection).
  native-image only needs a JVM at *build* time, never at runtime.
- Macros that currently run compiled JVM bytecode at compile time are
  re-routed through **our own from-scratch tree interpreter** instead (not
  the upstream `tasty-interpreter` project, which was incomplete) — see
  "Macro execution" below.

## Blocker 1: dotc needs `jrt:/`, native-image binaries don't have it

dotc initializes its core symbol table (`java.lang.Object`, `AnyRef`, ...) by
reading JDK classfiles off the `jrt:/` modules filesystem, resolved from
`java.home`. A native-image binary has no real JDK install and (in general)
no `jrt:/` filesystem provider, so `Definitions.init` crashes before touching
any user source:

```
java.lang.AssertionError: assertion failed: asTerm called on not-a-Term val <none>
  at dotty.tools.dotc.core.Definitions.ObjectClass(Definitions.scala:325)
```

**Fix**: extract `java.base`'s classfiles into a plain jar
(`jimage extract` + `jar cf`) and point dotc at it with `-javabootclasspath`,
bypassing `jrt:/` entirely. See `build/02-build-java-base.sh`.

## Blocker 2: compiler plugins can't be loaded dynamically under native-image

dotc loads `-Xplugin:<jar>` reflectively (`Class.forName` on a class named in
the jar's `plugin.properties`). native-image is closed-world AOT: it can only
reflect on classes it saw at build time. Passing `-Xplugin` for a jar that
wasn't on native-image's own build classpath silently fails ("Missing
required plugin", no other diagnostic).

**Fix**: bake the plugin (`nscplugin`) into the *same* native-image build as
dotc (same `-cp`). At runtime, `-Xplugin:<jar>` still needs to point at a real
jar (dotc reads `plugin.properties` from it), but the classes it names are
already resident in the image, so `Class.forName` succeeds. Reflection
metadata for the plugin's classes is captured via the native-image tracing
agent (`-agentlib:native-image-agent=...`) and checked into
`agent-config/dotc/`. See `build/03-build-dotc-native.sh` and
`build/05-regen-agent-config.sh`.

This generalizes: any dotc plugin we want to support has to be baked in at
image-build time. There is no dynamic plugin loading in the shipped binary.

## Gotcha: scala-native's Maven artifact names are landmines

- `scalalib_native0.5_3` **looks** like "the Scala stdlib for scala-native"
  but for the 3.7.1 version line it is an empty jar (manifest only). The real
  cross-compiled Scala 3 stdlib is `scala3lib_native0.5_3`, versioned as
  `<scala-version>+<scala-native-version>` (e.g. `3.7.1+0.5.12`), not a plain
  version number.
- `tools_native0.5_3` is **not** a JVM-runnable build tool — it's the same
  `scala.scalanative.build`/`scala.scalanative.linker` source cross-compiled
  *for* scala-native itself (self-hosting artifact). Running it on a plain
  JVM crashes immediately:
  ```
  scala.scalanative.runtime.UndefinedBehaviorError
    at scala.scalanative.meta.LinktimeInfo$target$.os(LinktimeInfo.scala:92)
  ```
  because it uses link-time intrinsics that only resolve during an actual NIR
  link. The JVM-side build tool is **`tools_3`** (no `_native` in the name).

Get either of these wrong and you get confusing failures far from the actual
mistake (missing symbols during linking, or `UndefinedBehaviorError` crashes).
`build/01-fetch-deps.sh` documents the correct coordinates inline.

## Confirmed working: full pipeline, zero JVM at runtime

`dotc-native` (dotc + nscplugin baked in) compiles `.scala` → `.nir`, and a
second native-image binary (`linkdriver-native`, wrapping scala-native's
`tools_3` link API) drives clang to turn `.nir` into a native executable —
both steps using only standalone binaries, no `java` process anywhere. `bin/snc`
wires the two together into one CLI.

Verified default JVM bytecode output (GenBCode phase, i.e. plain `.class`
files from `dotc-native`, unrelated to the scala-native path) has a
`VerifyError` in this setup, likely from a subtlety in the jimage-extracted
`java.base.jar`. Untested/unfixed since scala-native bypasses GenBCode
entirely (NIR is generated straight from typed trees) — only relevant if
someone also wants this binary to emit correct JVM `.class` files.

## Macro execution: own-implementation tree interpreter

The upstream `tasty-interpreter` project (referenced in the original brief)
turned out to be incomplete/unmaintained, so we wrote our own from scratch:
`compiler/src/dotty/tools/dotc/quoted/Interpreter.scala` in `vendor/scala3`,
patched via `patches/0001-own-implementation-tasty-interpreter.patch`
(applied by `build/00b-setup-vendor.sh`, baked into `dotc-native` by
`build/03a-patch-compiler.sh`).

**How upstream does it, and why that can't survive native-image:** dotc
expands `inline def foo = ${ fooImpl }` by reflectively loading `fooImpl`'s
already-compiled classfile and having the real JVM execute the real bytecode
(`java.lang.reflect.Method.invoke` off a `URLClassLoader` built from
`-classpath`). Tree-walking was only ever used to evaluate the small
expressions that become that reflective call's *arguments*. native-image is
closed-world AOT — it cannot load a class that wasn't part of the build, and
a user's macro implementation is by definition unknown until this
compilation run. There is no reflection config that fixes this; the
reflective bottom-out has to go away entirely.

**Design:** recursively interpret the callee's own body from its typed tree
(`Symbol.defTree`), walking arbitrarily deep into the macro's call graph,
with two reflection-free escape hatches:
- a curated set of primitive/stdlib intrinsics (arithmetic, `String`,
  `Seq`/collections basics) implemented natively in Scala, and
- the `scala.quoted`/`Quotes` API surface, called as ordinary direct Scala
  method calls into `QuotesImpl`/`ExprImpl` — not reflection, since those
  classes are already statically linked into this compiler binary and
  native-image sees every such call site at build time like any other code.

**Cross-module macros work, better than expected.** `Symbol.defTree` is only
populated for symbols whose tree was "retained" (`-Yretain-trees`). It turns
out that flag retains trees for symbols unpickled from a *dependency's*
`.tasty` this run just as much as for current-run definitions — TASTy always
pickles full method bodies, not just signatures. So a macro compiled in one
`dotc` invocation and used from a separate one (the normal real-world shape)
interprets exactly like a same-run macro, as long as **both** invocations
pass `-Yretain-trees` (`bin/snc` always does). Verified against
`interpreter/test-fixtures/{i4515,i4515b,inline-varargs-1,tasty-getfile}` —
real upstream tests, originally `Macro_1.scala`/`Test_2.scala`-style separate
compilations.

**Test fixtures**: `interpreter/test-fixtures/` holds real macro tests
harvested from `vendor/scala3/tests/{run-macros,pos-macros}`. Of the curated
set: 7 pass correctly end-to-end (including through the actual `dotc-native`
+ `linkdriver-native` binaries, not just a JVM test harness — see
`examples/macro-hello/`), 1 (`i10863`) partially passes (resolves the right
value but `.show` renders through dotc's own pretty-printer rather than the
exact printer real macros get, so output differs in detail), and 2
(`i7715`, `i8746`) fail cleanly with a named `StopInterpretation` rather than
a crash or wrong answer, because they need general quote-pattern matching.

**Known gaps** (see the `Interpreter` class doc comment for specifics):
- General pattern matching (`UnApply` — case-class deconstruction, quote
  patterns like `case '{ $body } => ...`) isn't implemented. `matchPattern`
  only covers wildcards, binds, literals, typed-wildcard type tests.
- `Tree#show` uses dotc's default printer, not the exact printer real macros
  get by default (`reflect.Printer.TreeCode`) — correct interpretation, but
  detail-level string differences are possible.
- The `Quotes` API surface is covered by hand as shapes get exercised, not
  exhaustively.
- Two upstream test fixtures (`quote-simple-macro`, `quote-elide-prefix`)
  crash inside dotc's own `Inliner` before the interpreter is even reached —
  a pre-existing dotc issue, not caused by or fixed by this patch.

**Patching mechanism**: this is a real source-level patch to `scala/scala3`
(the diff lives in `patches/`), but *not* a full dotty sbt bootstrap rebuild
— `build/03a-patch-compiler.sh` recompiles only the one changed file against
the published, unmodified `scala3-compiler_3` jar and splices the result
into a copy of that jar. This works because the change is confined to a
single file with no new dependencies on anything it wasn't already a normal
dependent of. A patch spanning multiple compiler files, or one needing an
API not exposed by the published jar's public surface, would need the full
sbt build instead (see "Remaining work").

## Toward a build-tool experience without a JVM

Asked whether `snc` could grow scala-cli-like ergonomics (single command,
dependency resolution, caching, watch mode) without reintroducing a JVM.
Conclusion: don't reuse scala-cli itself — it's deeply JVM-coupled
(coursier-as-library, bloop, zinc). Instead reuse only the one genuinely
JVM-free piece already available (coursier ships `cs` itself as a prebuilt
GraalVM native-image launcher, so shelling out to it for dependency
resolution costs no JVM despite coursier being JVM Scala under the hood),
and hand-roll the rest (directives, an incremental-compile cache, watch mode)
purpose-built rather than porting zinc/BSP.

### `scli`: implemented, self-hosted

`cli/Scli.scala` is a mini scala-cli-style CLI (`scli run`/`scli compile`),
built by `build/07-build-scli.sh` -- itself compiled by
`dist/dotc-native`+`dist/linkdriver-native` (bootstrapped once those exist),
**not** by any JVM. This closes the loop: the build tool driving the
compiler is itself a product of that same compiler.

What it does:
- Parses `//> using dep "..."` in all three scala-cli coordinate formats
  (one per line, anywhere in any given source file):
  - `org:name:version` (sbt `%`, exact artifact) — unchanged
  - `org::name:version` (sbt `%%`, Scala-version cross) — `org:name_3:version`
  - `org::name::version` (sbt `%%%`, platform+Scala cross) —
    `org:name_native<binVer>_3:version` (`<binVer>` = scala-native's
    major.minor, e.g. `0.5`, baked into `BuildInfo` from `versions.env`)

  `//> using scala "x"` is parsed and produces a warning (not an error) if
  it disagrees with the toolchain's pinned version -- there's no
  multi-version support, this binary only ever targets the Scala/scala-native
  version it was built for.
- Resolves dependencies via a native `cs fetch --classpath` subprocess,
  cached on disk (`.scli-build/deps-cache/`) keyed by the sorted dependency
  list, so repeat runs skip the resolution step (and its network
  round-trip) entirely, not just the artifact download `cs` already caches
  itself. Excludes scala-native's own runtime artifacts
  (`nativelib_native<v>_3`, `javalib_native<v>_3`, etc., see
  `excludedArtifacts`) and plain `scala3-library_3`/`scala-library` from
  that resolution: a `::name::version` dependency's own published build was
  cross-compiled against *some* scala-native release (rarely the exact same
  patch version as ours), and without excluding them its transitively-pulled
  copies collide with the ones we always supply ourselves
  (`dist/nativelibs.cp`) -- hundreds of `clang` duplicate-symbol errors at
  link time otherwise (two copies of the GC, of libc shims, ...).
- Detects the entry point via a **heuristic text scan** (`@main def`,
  `object X extends App`, or a `def main(args: Array[String])` inside an
  `object` block, tracked via naive brace-depth counting) -- not
  compiler-driven, since this binary has no reflection/introspection
  machinery to ask the compiler "what did you just produce." Works for
  normal formatting; can misfire on deliberately adversarial code (e.g. a
  `{`/`}` inside a string or comment throws off the depth counter). Ambiguous
  or missing entry points are a clear error asking for `--main-class`, not a
  guess. This also fixes `bin/snc`'s documented "first file's basename"
  limitation -- source file order no longer matters.
- Drives `dotc-native`/`linkdriver-native` directly (reading the same
  `dist/*.cp` manifests `bin/snc` uses), with the resolved dependency
  classpath folded in, into the same persistent `.scli-build/<mainClass>/`
  caching layout `bin/snc` uses (see the C-object-cache fix above).

Verified against `examples/Hello.scala` (plain), `examples/macro-hello/`
(real macro, **and** confirmed order-independent -- `Foo.scala Test.scala`
and `Test.scala Foo.scala` both correctly detect `Test` as the entry point),
the ambiguous/missing-entry-point error paths, and all three dependency
formats together in one resolution (`scala3-interfaces` exact,
`com.lihaoyi::pprint` cross-Scala, `com.lihaoyi::os-lib::0.11.7`
cross-platform). The last one is the strongest proof: not just resolved but
**actually linked and called into** --
`println(os.pwd)` (real POSIX `getcwd` underneath, via a real third-party
scala-native library) compiled, linked, and printed the correct working
directory end to end.

### `scli`: closing the gap with scala-cli's CLI surface

Follow-up pass specifically aimed at making the command line itself feel
like scala-cli's, not just the directive parsing underneath:

- `scli <sources...>` with no subcommand now means `run` (scala-cli's
  signature `scala-cli Foo.scala` UX); `scli run`/`scli compile` still work
  explicitly.
- A source argument may be a directory (`scli run .`): every `.scala` file
  under it is collected recursively, skipping hidden and build-output
  (`.scli-build`/`target`/`out`) directories.
- New directives: `mainClass` (explicit entry point, alternative to
  `--main-class`) and `options`/`option` (extra `dotc-native` flags, e.g.
  `//> using options "-explain"`).
- New flags mirroring scala-cli's: `-d`/`--dep`/`--dependency` (dependency,
  repeatable, same as the directive), `-S`/`--scala`/`--scala-version`
  (same warn-if-mismatched behavior as the directive), `-O`/
  `--scalac-option` (extra compiler flag, repeatable).
- `-w`/`--watch`: rebuilds (and, for `run`, reruns) on source change. Polls
  file mtimes every 500ms rather than using `java.nio.file.WatchService` --
  simpler and doesn't depend on that API's support in this toolchain's
  javalib port, which is unverified. Build/link/resolve failures during a
  watch iteration are caught and reported without killing the loop (see
  `BuildFailed` in `Scli.scala`); a genuinely bad CLI invocation still exits
  immediately, before the loop ever starts.
- `scli version` and `scli --help`/`-h`.
- Typing an unimplemented scala-cli command (`test`, `fmt`, `repl`,
  `package`, `publish`, `publish-local`, `clean`, `bsp`, `export`,
  `doctor`, `setup-ide`, `install-completions`, `dependency-update`,
  `shebang`) now gets a clear "not implemented" message naming what *is*
  supported, instead of being misparsed as a source file.

Verified: implicit-run, `run` on a directory (correctly aggregates every
`.scala` file found and still applies entry-point detection/disambiguation
across all of them), `-O` flag passthrough to `dotc-native`, the
`mainClass` directive, the `-d` flag (resolution failure surfaces the same
clean error as a directive-declared dependency), and a full watch-mode
cycle (initial build/run, edit, detected, rebuild, rerun, all without
restarting the process).

**Known limitation, inherent to scala-native itself, not this tool**: a
resolved dependency jar typechecks fine but only *links* if it's actually
cross-published for scala-native (an `org::name::version`-resolvable
artifact). A plain `org::name:version` JVM-only dependency (e.g.
`org.typelevel::cats-core`) resolves and typechecks but has no `.nir`
bodies, so code that actually *called into* it would fail at the link step
with unreachable symbols, same as it would under real scala-cli targeting
`--native` with a JVM-only dependency.

Not implemented: watch mode, incremental Scala compilation (every `scli`
build fully recompiles every given source file -- only the native-library
object cache and the dependency-resolution cache are incremental),
multi-module projects, and anything past the one directive kind above
(no `//> using options`, `//> using resourceDir`, toolkit shortcuts, etc.).

### `bin/snc` created a fresh tmp dir every build — first real bug found

First thing noticed trying to actually use `snc` repeatedly: every dependency
`.c` file (scala-native's own vendored runtime, ~160 files) recompiled from
scratch on every single build, even with no source changes. Two distinct
causes, one in each layer:

1. **Our bug**: `bin/snc` used `work="$(mktemp -d)"` + `trap 'rm -rf "$work"'
   EXIT` — a fresh, deleted-on-exit directory every invocation. scala-native's
   own `Build.buildCachedAwait` (used by `LinkDriver.scala`) keeps its
   per-file `.o` cache *inside* that workDir, so deleting it every time threw
   the cache away regardless of whether it worked. Fixed: `bin/snc` now uses
   a persistent, project-local `.snc-build/<mainClass>/` (scala-cli's own
   `.scala-build` convention), never deleted.

2. **scala-native's bug** (0.5.12): even with a persistent workDir, dependency
   `.c` files still recompiled every time. Root cause, traced into
   `vendor/scala-native`: `LLVM.compile`'s freshness check
   (`needsCompiling`, `LLVM.scala`) is `mtime(.c) > mtime(.o) ||
   Build.userConfigHasChanged(config)` — a real per-file cache. But for
   vendored dependency libraries, `NativeLib.compileNativeLibrary` calls this
   with a **per-library** `projConfig` (`configureNativeLibrary` appends
   reachability-analysis-derived preprocessor flags per library), while the
   hash file it's compared against is written once, at the very end of the
   whole build, from the **top-level, un-augmented** config
   (`Build.scala:159`, `dumpUserConfigHash`). Comparing two structurally
   different `NativeConfig`s meant `userConfigHasChanged` was true almost
   unconditionally for every dependency file — the mtime check never even
   got a chance to matter. Our own generated LLVM IR (compiled with the
   *unmodified* top-level config) was never affected — only the ~160 vendored
   runtime C/S files were.

   **Fix** (`patches/scala-native-0001-fix-native-lib-object-cache.patch`,
   `build/04a-patch-tools.sh`, same splice-not-full-rebuild approach as the
   dotc patch): made the hash-file path an explicit parameter through
   `LLVM.compile`/`needsCompiling`/`Build.userConfigHasChanged`/
   `dumpUserConfigHash`, and gave `compileNativeLibrary` its own hash file
   scoped to that library's `destPath`, computed from and compared against
   its own `projConfig`. Every other call site keeps the original top-level
   file (`Build.defaultUserConfigHashPath`), so this is strictly additive —
   it can only make the cache *more* accurate (skip when a library's
   effective config really is unchanged), never less safe, since the
   pre-patch behavior was "almost always recompile" (safe, just wasteful),
   not "sometimes wrongly skip." Verified: cache invalidates correctly when
   source actually changes (rebuilt `examples/Hello.scala` after editing it
   mid-`.snc-build`, got the new output), and a warm rebuild of
   `examples/Hello.scala` went from 162 dependency recompiles / ~4.6s to 0
   recompiles / ~1.7s.

## Remaining work

1. **General quote-pattern matching.** The biggest real gap — see
   `i7715`/`i8746` above. Needed for a large class of real-world macros
   (typeclass derivation, anything using `case '{ ... } => ` matching).
2. **Full sbt source build.** Current patching approach (recompile one file,
   splice into the published jar) only works for single-file, dependency-only
   patches. Anything touching multiple compiler files, or needing non-public
   API, needs the real dotty/scala-native sbt bootstrap build wired up
   instead.
3. ~~**Packaging/relocatability.**~~ Fixed: `build/06-package.sh` now vendors
   every jar into `dist/lib/` and rewrites `dist/*.cp` manifests to
   dist-relative paths; `bin/snc` and `scli` resolve them against their own
   dist root at runtime, and `scli` locates that root via its own executable
   path (`cli/selfexe/*.scala`, one small OS-specific native binding per
   platform) instead of a build-time-baked-in absolute path. `dist/` is now a
   self-contained, copyable/tarball-able distribution.
4. **`scli` follow-ups.** See "Toward a build-tool experience without a JVM"
   above for what's implemented (including watch mode and directory/CLI-flag
   parity with scala-cli as of the "closing the gap" pass). Still missing:
   incremental Scala compilation (see item 7), multi-module/multi-target
   projects, a real (not heuristic-text-scan) entry-point detector, and
   scala-cli's `test`/`fmt`/`repl`/`package`/`publish`/`bsp`/`export`
   commands -- none of those have an obvious JVM-free equivalent yet (no
   vendored test framework, no scalafmt, no bloop), so they currently just
   print "not implemented" rather than being faked.
5. Only tested on macOS/arm64. Linux/other-arch is unverified.
6. `bin/snc`'s "first source file's basename is the main class" convention is
   still naive (unlike `scli`, which auto-detects) — for multi-file macro
   examples via `bin/snc` directly, the entry-point file must be listed
   first (see `examples/macro-hello/` usage in the README).
7. Only compiling Scala source is incremental-cache-free right now — `dotc`
   always fully recompiles every given source file. The native-library object
   cache fixed above, and `scli`'s dependency-resolution cache, are the only
   incremental pieces so far.
