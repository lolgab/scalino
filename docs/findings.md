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
`agent-config/dotc/`. See `build/03-build-scalino-dotc.sh` and
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

`scalino-dotc` (dotc + nscplugin baked in) compiles `.scala` → `.nir`, and a
second native-image binary (`scalino-linkdriver`, wrapping scala-native's
`tools_3` link API) drives clang to turn `.nir` into a native executable —
both steps using only standalone binaries, no `java` process anywhere. `bin/scalino-bootstrap`
wires the two together into one CLI.

Verified default JVM bytecode output (GenBCode phase, i.e. plain `.class`
files from `scalino-dotc`, unrelated to the scala-native path) has a
`VerifyError` in this setup, likely from a subtlety in the jimage-extracted
`java.base.jar`. Untested/unfixed since scala-native bypasses GenBCode
entirely (NIR is generated straight from typed trees) — only relevant if
someone also wants this binary to emit correct JVM `.class` files.

## Macro execution: own-implementation tree interpreter

The upstream `tasty-interpreter` project (referenced in the original brief)
turned out to be incomplete/unmaintained, so we wrote our own from scratch:
`compiler/src/dotty/tools/dotc/quoted/Interpreter.scala` in `vendor/scala3`,
patched via `patches/0001-own-implementation-tasty-interpreter.patch`
(applied by `build/00b-setup-vendor.sh`, baked into `scalino-dotc` by
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
pass `-Yretain-trees` (`bin/scalino-bootstrap` always does). Verified against
`interpreter/test-fixtures/{i4515,i4515b,inline-varargs-1,tasty-getfile}` —
real upstream tests, originally `Macro_1.scala`/`Test_2.scala`-style separate
compilations.

**Test fixtures**: `interpreter/test-fixtures/` holds real macro tests
harvested from `vendor/scala3/tests/{run-macros,pos-macros}`. Of the curated
set: 7 pass correctly end-to-end (including through the actual `scalino-dotc`
+ `scalino-linkdriver` binaries, not just a JVM test harness — see
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

Asked whether `scalino` could grow scala-cli-like ergonomics (single command,
dependency resolution, caching, watch mode) without reintroducing a JVM.
Conclusion: don't reuse scala-cli itself — it's deeply JVM-coupled
(coursier-as-library, bloop, zinc). Instead reuse only the one genuinely
JVM-free piece already available (coursier ships `cs` itself as a prebuilt
GraalVM native-image launcher, so shelling out to it for dependency
resolution costs no JVM despite coursier being JVM Scala under the hood),
and hand-roll the rest (directives, an incremental-compile cache, watch mode)
purpose-built rather than porting zinc/BSP.

### `scalino`: implemented, self-hosted

`cli/ScalinoCli.scala` is a mini scala-cli-style CLI (`scalino run`/`scalino compile`),
built by `build/07-build-scalino.sh` -- itself compiled by
`dist/scalino-dotc`+`dist/scalino-linkdriver` (bootstrapped once those exist),
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
  cached on disk (`.scalino-build/deps-cache/`) keyed by the sorted dependency
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
  guess. This also fixes `bin/scalino-bootstrap`'s documented "first file's basename"
  limitation -- source file order no longer matters.
- Drives `scalino-dotc`/`scalino-linkdriver` directly (reading the same
  `dist/*.cp` manifests `bin/scalino-bootstrap` uses), with the resolved dependency
  classpath folded in, into the same persistent `.scalino-build/<mainClass>/`
  caching layout `bin/scalino-bootstrap` uses (see the C-object-cache fix above).

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

### `scalino`: closing the gap with scala-cli's CLI surface

Follow-up pass specifically aimed at making the command line itself feel
like scala-cli's, not just the directive parsing underneath:

- `scalino <sources...>` with no subcommand now means `run` (scala-cli's
  signature `scala-cli Foo.scala` UX); `scalino run`/`scalino compile` still work
  explicitly.
- A source argument may be a directory (`scalino run .`): every `.scala` file
  under it is collected recursively, skipping hidden and build-output
  (`.scalino-build`/`target`/`out`) directories.
- New directives: `mainClass` (explicit entry point, alternative to
  `--main-class`) and `options`/`option` (extra `scalino-dotc` flags, e.g.
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
  `BuildFailed` in `ScalinoCli.scala`); a genuinely bad CLI invocation still exits
  immediately, before the loop ever starts.
- `scalino version` and `scalino --help`/`-h`.
- Typing an unimplemented scala-cli command (`test`, `fmt`, `repl`,
  `package`, `publish`, `publish-local`, `clean`, `bsp`, `export`,
  `doctor`, `setup-ide`, `install-completions`, `dependency-update`,
  `shebang`) now gets a clear "not implemented" message naming what *is*
  supported, instead of being misparsed as a source file.

Verified: implicit-run, `run` on a directory (correctly aggregates every
`.scala` file found and still applies entry-point detection/disambiguation
across all of them), `-O` flag passthrough to `scalino-dotc`, the
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

Not implemented: incremental Scala compilation (every `scalino`
build fully recompiles every given source file -- only the native-library
object cache and the dependency-resolution cache are incremental),
multi-module projects, and `--compiler-plugin`/`//> using plugin` (see
below -- not a bounded gap, an architectural one). Watch mode and
`//> using file`/`exclude` are implemented (both below).

**`--compiler-plugin`/`-P`/`//> using plugin`: not implementable as a
CLI-level gap, deliberately skipped.** Real scala-cli loads a user-given
plugin jar into dotc reflectively at runtime. This toolchain's dotc is
itself a native-image binary, and native-image's closed-world AOT
reflection means `Class.forName` only ever succeeds for classes resident
in the image at *build* time (see "Blocker 2" above, which is the same
constraint for the one plugin scalino already ships baked in,
`nscplugin`). There is no code path by which a `-Xplugin:<arbitrary jar
picked at runtime>` could ever load a class native-image didn't see when
`scalino-dotc` itself was built -- so unlike every other item in this
section, this one can't be closed by adding CLI/directive parsing; it
would need a from-scratch alternative to reflective plugin loading
(e.g. a fixed registry of known plugins baked in at image-build time,
`kind-projector` included), which is future work, not a quick parity fix.

### `scalino`: second CLI-parity pass -- directive/flag aliases, jars, repositories (2026-09-06)

Follow-up to the pass above, closing several more scala-cli directive/flag
gaps found by direct comparison against real scala-cli's own CLI surface,
each verified with a real end-to-end build (not just parsed):

- `//> using dependency`/`dependencies` (and `compileOnly.dependency(ies)`/
  `test.dependency(ies)`) as long-form aliases of `dep`/`deps`;
  `//> using scalacOption`/`scalacOptions` as aliases of `options`/`option`;
  `//> using test.scalacOption`/`test.scalacOptions` (test-scope-only extra
  compiler flags, appended after `options` for the test compile only).
- `--compile-dep`/`--compile-only-dependency` flag + `//> using
  compileOnly.dep` directive (already existed) now share one
  `extraCompileOnlyClasspath`, resolved and cached separately from the main
  dependency set (a macro-only/annotation-only dependency needed at compile
  time but not runtime, without polluting the link classpath).
- `//> using jar "./local.jar"` / `jars`: adds a local jar file straight to
  the classpath (main, test, and IDE setup), no `cs fetch` involved.
- `//> using resourceDir "./resources"` / `resourceDirs`: adds a directory
  to the classpath (combine with `--embed-resources`/`nativeEmbedResources`
  to actually bake its files into the binary via scala-native's own
  `NativeConfig#withEmbedResources` resource scan, which runs over the link
  classpath).
- `-r`/`--repo`/`--repository` flag + `//> using repository`/`repositories`
  directive: extra Maven repositories threaded straight through to the
  existing `cs fetch` call as repeated `-r <repo>` flags (`resolveDeps` in
  `ScalinoCli.scala`), on top of the default Central-only resolution.
  `resolveDeps`'s on-disk classpath cache key now folds the repository list
  in alongside the sorted dependency list, so adding/removing a repository
  correctly invalidates a stale cached classpath instead of silently
  reusing one resolved under a different repo set. Verified against a real
  non-Central artifact (`com.github.jitpack:gradle-simple:1.1`, hosted only
  on `https://jitpack.io`): resolution fails with the normal "dependency
  resolution failed" error with no `--repository` given, and succeeds full
  end-to-end (resolve, compile, link, run) with either `--repository
  https://jitpack.io` or the equivalent `//> using repository` directive.
- `//> using file "./Other.scala"` / `files` (extra source, resolved
  relative to the *declaring* source file's own directory, not cwd) and
  `//> using exclude "glob"` (drops matching sources, glob matched against
  each collected source's path relative to cwd) -- folded straight into
  `expandSources` (`ScalinoCli.scala`) so all three of its call sites
  (`run`/`compile`, `test`, `setup-ide`) get both for free. Only one level
  deep: a `file`-referenced source's own `file`/`exclude` directives aren't
  expanded a second time, matching real scala-cli. The glob matcher is a
  small hand-rolled `*`/`**`/`?`-to-regex translator, not
  `java.nio.file.FileSystem#getPathMatcher("glob:...")` -- that method's
  support in this toolchain's from-scratch javalib port is unverified, and
  the translator is ~15 lines. **Gotcha caught by real end-to-end testing,
  not just reading the code back**: the first cut relativized each
  candidate path's `.toAbsolutePath` against cwd directly, but
  `collectScalaFiles` produces paths like `./generated/Broken.scala` (a
  literal `.` path element from walking a directory arg of `.`) --
  `Path#relativize` doesn't strip that element on its own, so the "relative
  to cwd" string came out as `./generated/Broken.scala` instead of
  `generated/Broken.scala` and every exclude glob silently matched nothing.
  Fixed by `.normalize()`-ing both sides before relativizing. Verified with
  a real directory build: a `generated/` subtree containing a file that
  doesn't even parse as Scala is included (and fails the build) with no
  `exclude` directive, and cleanly skipped with `//> using exclude
  "generated/**"` in a sibling source.
- `--args-file <path>`: forwarded to `scalino-dotc` verbatim as `@<path>`
  rather than parsed by `scalino` itself -- dotc already has this exact
  feature built in (`CommandLineParser.expandArg`/`CliCommand.scala:47`,
  any raw arg starting with `@` expands to that file's contents, one
  option per line, `#` starts a line comment, real quoting via
  `CommandLineParser.tokenize`), so re-implementing the parsing here would
  just be a worse copy of a mechanism the compiler already gets right.
  Verified two ways with a real args-file: a `-Xfatal-warnings` line
  (deliberately triggers dotc's own "deprecated alias, use -Werror"
  warning promoted to an error, proving the line really reached dotc's
  argument parser) and, separately, a clean file with just `-explain` and
  a `#`-comment line building successfully.
- `--watching`/`--watching-path <path>` (repeatable; file or directory,
  recursive, any extension -- unlike `expandSources`/`collectScalaFiles`
  this is not `.scala`-filtered, since these are meant to cover arbitrary
  resource files a build depends on but doesn't compile) folds into the
  existing `watchLoop`'s mtime-polled path set alongside the real sources.
  Verified with a real background `scalino run -w --watching-path <dir>`:
  editing a plain (non-`.scala`) file under the watched directory
  triggered "change detected, rebuilding..." and a rerun, exactly as
  editing a source file would.

**Not implemented: `--restart`/`--revolver`** (background run,
auto-kill-and-restart on change). Unlike everything else in this section,
this isn't just directive/flag parsing over the existing synchronous
build pipeline -- it needs real background process lifecycle management
(spawn detached, track the child, kill and respawn on change) layered on
top of `runInherited`'s current spawn-and-block model, and this
toolchain's scala-native javalib port has not been verified to support
`Process#destroy` at all. Left as a documented gap rather than risking a
half-working implementation of process control this session didn't have
budget to verify properly.

### `bin/scalino-bootstrap` created a fresh tmp dir every build — first real bug found

First thing noticed trying to actually use `scalino-bootstrap` repeatedly: every dependency
`.c` file (scala-native's own vendored runtime, ~160 files) recompiled from
scratch on every single build, even with no source changes. Two distinct
causes, one in each layer:

1. **Our bug**: `bin/scalino-bootstrap` used `work="$(mktemp -d)"` + `trap 'rm -rf "$work"'
   EXIT` — a fresh, deleted-on-exit directory every invocation. scala-native's
   own `Build.buildCachedAwait` (used by `LinkDriver.scala`) keeps its
   per-file `.o` cache *inside* that workDir, so deleting it every time threw
   the cache away regardless of whether it worked. Fixed: `bin/scalino-bootstrap` now uses
   a persistent, project-local `.scalino-bootstrap-build/<mainClass>/` (scala-cli's own
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
   mid-`.scalino-bootstrap-build`, got the new output), and a warm rebuild of
   `examples/Hello.scala` went from 162 dependency recompiles / ~4.6s to 0
   recompiles / ~1.7s.

## JVM-free language server (LSP)

Goal: a Metals-equivalent for editors like Zed, without a JVM at runtime.
Metals itself doesn't work here — it's an LSP *client architecture*
(BSP + semanticdb + `mtags-interfaces`) built to bridge Scala into arbitrary
JVM build tools, none of which this toolchain needs (it already owns
compile/link/deps end to end via `scalino`). What's actually needed is much
smaller: dotc's own `interactive`/`InteractiveDriver` machinery, wired to a
stdio LSP server.

That server already existed: `vendor/scala3/language-server/` is dotty's own
pre-Metals LSP implementation (`DottyLanguageServer.scala`, ~1000 lines, on
`org.eclipse.lsp4j`), superseded and unmaintained once Metals took over, but
functionally complete — `didOpen`/`hover`/`definition`/`completion`/
`references`/`rename`/`documentSymbol`/workspace `symbol`/`implementation`/
`signatureHelp`, all thin wrappers over `InteractiveDriver`. `dist/scalino-lsp`
(`build/08-build-scalino-lsp.sh`) native-images a trimmed version of it —
`patches/scala3-0002-trim-language-server.patch` drops `worksheet/` (spawns
a forked JVM REPL subprocess — genuinely JVM-shaped, out of scope) and
`decompiler/` (TASTy-decompile-on-request, not core LSP). Unlike
`scalino-dotc`, this module was never published as a jar (dead since ~2019),
so it's compiled JVM-side from source first (mirroring
`03a-patch-compiler.sh`'s pattern) rather than jar-spliced.

Three real bugs found and fixed, none anticipated by the initial scoping:

1. **lsp4j 0.6.0 (the version `vendor/scala3`'s own `Build.scala` pins) has
   a real bug**: its published jar's `LanguageServer.initialized()` (the
   `@Deprecated` no-arg overload) carries a `@JsonNotification` annotation
   that its own *sources* jar doesn't show — a binary/sources mismatch in
   the 2019-era release — so `Launcher.Builder.create()` throws "Duplicate
   RPC method initialized." for any `-stdio` launch. Fixed in later
   releases (verified against 0.21.1). Since nothing appears to have run
   this module over real `-stdio` since Metals took over, this plausibly
   never worked even upstream. Fix: bumped to lsp4j 0.21.1 in
   `build/01-fetch-deps.sh`, which in turn required three small API-shape
   patches to `DottyLanguageServer.scala` (`TextDocumentPositionParams` split
   into per-method subtypes like `HoverParams`/`DefinitionParams`;
   `definition`/`implementation`/workspace `symbol` now return
   `Either<List<...>, List<...>>` instead of a bare list; and a
   `WorkspaceService.diagnostic` method added in newer lsp4j shadowed our
   own like-named `diagnostic` helper, renamed to `toLspDiagnostic`) — all
   in the same patch file.
2. **A second, more fundamental duplicate-RPC-method bug, unrelated to lsp4j
   version**: dotc's Mixin phase materializes a concrete forwarder on
   `DottyLanguageServer` for *every* default method it inherits from
   `LanguageServer`/`TextDocumentService`/`WorkspaceService` — not just the
   ones the class overrides (`codeAction`, `formatting`, `foldingRange`, ...
   dozens of them) — and each forwarder still carries the original
   `@JsonNotification`/`@JsonRequest` annotation. lsp4j's reflective method
   scanner (`ServiceEndpoints.getSupportedMethods`/`GenericEndpoint`) walks
   both the concrete class *and* its interfaces unconditionally and isn't
   dedup-aware, so it finds the same RPC method twice and throws. Plain Java
   implementers never hit this, since javac doesn't synthesize forwarders
   for un-overridden default methods — this is a genuine Scala/lsp4j interop
   gap, not an lsp4j defect. Fixed by scanning the three service
   *interfaces* directly (clean on their own) instead of the concrete class:
   `DottyLanguageServer` implements `JsonRpcMethodProvider` (which
   `Launcher.Builder` already prefers over its own class scan when present)
   for the wire-protocol method table, and `dotty.tools.languageserver.Main`
   builds its own minimal `Endpoint` for local dispatch, since
   `GenericEndpoint`/`ServiceEndpoints.toEndpoint` has no equivalent
   override hook. Both reuse `DottyLanguageServer.rpcDispatch`/`rpcMethods`
   (a from-scratch, ~50-line reimplementation of lsp4j's `@JsonSegment`
   method-naming logic, since the real one lives behind a package-private
   type not reachable from outside `org.eclipse.lsp4j.jsonrpc.services`).
3. **`InteractiveDriver` needs `-javabootclasspath` explicitly under
   native-image**, exactly like `scalino-dotc`'s own compiles already do
   (`docs/findings.md` "Blocker 1") — under a real JVM, dotc happily
   resolves `java.lang.Object` etc. from the host JVM's own modules with no
   flag needed, so this was invisible testing JVM-mode first; under
   native-image there are no real JDK modules at runtime, so
   `Definitions.init` fails opaquely (`asTerm called on not-a-Term val
   <none>`) without it. Not a code fix — a requirement on whatever
   generates a project's `.dotty-ide.json` (see below).

**Verified working, real native binary, zero JVM at runtime** (checked via
process-tree inspection while running): a hand-written `.dotty-ide.json`
fixture pointing `dist/scalino-lsp -stdio` at `examples/Hello.scala`
gets a correct `initialize` response, correct (empty) diagnostics on
`didOpen`, real Scaladoc-sourced hover text for `println`, and a correct
compiler type-error diagnostic (`Found: String, Required: Int`, right
range) after a `didChange` introducing a real type error. Also confirmed:
the real stdlib jar is `org.scala-lang:scala-library` (no `_3` suffix) —
`scala3-library_3` is, as of the unified 2.13/3.x versioning line (3.8.x+),
a 319-byte relocation stub on Maven Central, not real classfiles (same
"decoy artifact" shape as `scalalib_native0.5_3`, documented above, but for
an unrelated reason).

Done since: `scalino setup-ide <sources...>` (`cli/ScalinoCli.scala`) generates
`.dotty-ide.json` (including `-javabootclasspath`) by reusing
`buildBinary`'s own classpath/directive-parsing plumbing, instead of
hand-writing it; and [`zed-extension/`](../zed-extension/) is a real Zed
extension (modeled on
[metals-zed](https://github.com/scalameta/metals-zed), reusing its
`languages/scala/` tree-sitter files under the same NOTICE) that owns its
own `Scala (scalino)` language/grammar (renamed from a same-named-as-metals-zed
`Scala`, see below) and registers `scalino-lsp` as its *only* language
server — no metals-zed extension needed at all (superseded
the earlier "second server on Zed's `Scala` language" design once it became
clear Zed has no extension-to-extension dependency or override mechanism,
so piggybacking on metals-zed's language would have meant also installing
its bundled JVM-backed `metals` and manually excluding it per-project). Its
own `README.md` has install steps. Compiles clean (`cargo check`) against
`zed_extension_api` 0.7.0.

**Fourth real bug, found 2026-09-05 by actually running it in a real Zed
instance (an isolated `--user-data-dir` profile with *only* the
`scalino-lsp` dev extension installed, no metals-zed) instead of just
`cargo check`ing it:** `extension.toml` declared `[grammars.scala]` but
never set the top-level `languages = ["languages/scala"]` key that tells
Zed to actually load `languages/scala/config.toml` as a language
definition — so despite the whole `languages/scala/` tree sitting right
there in the extension, Zed's own extension index showed
`"languages": []` for this extension. Net effect: the `Scala` language
identity was silently owned entirely by whichever *other* extension
happened to define it (metals-zed's `scala` extension, if installed) — the
opposite of the design intent above. With metals-zed installed alongside,
this was invisible (both `scalino-lsp` and `metals` start together,
looks fine) — matches real usage logs where the two servers always
launched within ~1s of each other. Without metals-zed, `.scala` files get
no language mode at all and `scalino-lsp` never starts, since its
`language_servers.scalino-lsp` entry is bound to a `Scala` language
nothing defines. Fixed by adding the missing `languages = ["languages/scala"]`
line. Needs a real Zed dev-extension reinstall (Zed doesn't rescan
`extension.toml` on its own) to take effect in an already-running Zed.

Also found via direct `-stdio` probing of `dist/scalino-lsp` (not yet
fixed, lower priority — didn't block the language-registration bug above):
a project with no `.dotty-ide.json` yet (i.e. `scalino setup-ide` never run
there) crashes the *entire* native-image process on the first `didOpen` —
`DottyLanguageServer.drivers()` (`DottyLanguageServer.scala:74`) throws
`FileNotFoundException` reading it, uncaught, inside a `CompletableFuture`
on a ForkJoinPool thread, which kills the whole process instead of
surfacing an LSP error to the client.

(A second suspected bug from this same round of probing — a
`NullPointerException` at `RemoteEndpoint.handleRequest:279` that seemed to
fire once per `didOpen`/`didChange`/`initialized` — turned out to be a false
positive: the probe script's own `send()` helper defaulted to treating
every call as a request (attaching a JSON-RPC `id`) unless told otherwise,
and three call sites forgot to pass `is_notif=True`. Sent that way, they
*are* requests as far as lsp4j is concerned, and `SafeLocalEndpoint.request`
(`Main.scala`) reflectively invokes a void/`Unit`-returning method and casts
the resulting `null` to `CompletableFuture` — exactly reproducing that NPE
by construction, no server bug required. Rerunning with the notifications
sent correctly (no `id`) against the same real project produced zero
errors over a full 2-minute idle session. Lesson for next time: a
hand-rolled LSP probe script is just as capable of fabricating a
"reachability"-shaped false bug as a real one — verify by fixing the
script and confirming the symptom disappears before writing it up.)

**Fifth fix, same day: the language-registration fix above wasn't enough
either.** Even with `scalino-lsp` correctly owning a language, giving
it the *same name* (`Scala`) metals-zed uses meant Zed still had to pick a
winner between the two extensions' competing definitions for `.scala`
files — and that pick flipped after a routine dev-extension reinstall
(observed live: `metals` and `scalino-lsp` kept launching together
regardless, since metals-zed's `language_servers.metals` entry is bound to
the *name* `Scala`, not to whichever extension owns the grammar). Fixed by
renaming the extension's language to `Scala (scalino)` — a name metals-zed
can't also claim — and narrowing `path_suffixes` to just `.scala` (was
`["scala", "sbt", "sc", "mill"]`, copied wholesale from metals-zed; dropped
the rest since this LSP has no reason to claim sbt/ammonite-script/mill
files at all). `scalino setup-ide` now also writes a `file_types` override
into `.zed/settings.json` (`"file_types": {"Scala (scalino)": ["scala"]}`) so
`.scala` files resolve to this extension's language deterministically
regardless of extension load order or whether metals-zed stays installed.

**Also added, same day: a real log file, not just editor-captured
stderr.** `Log` (`vendor/scala3/language-server/.../Main.scala`) appends a
timestamped line per dispatched RPC method (`-> method`, then `<- method
ok` or `<- method FAILED: <exception>`) to `.scalino-lsp.log` in the
project root, written by the server itself rather than relying on Zed's
own stderr capture — which, in practice, only ever showed a single
truncated line ("Starting server") on every connection failure seen this
week, useless for seeing what happened *before* things went wrong.
Wraps `SafeLocalEndpoint.invoke` (`Main.scala`), unwrapping
`InvocationTargetException` so a real exception inside e.g. `hover` shows
its actual class/message in the log, not just that reflection was
involved. Best-effort (silently a no-op if the file can't be opened, e.g.
read-only cwd) — never a reason to fail startup.

**`Scala (scalino)` rename confirmed working live, same day:** after a dev-extension
reinstall + fresh Zed relaunch, `metals` genuinely stopped auto-starting —
confirmed via `Zed.log` showing `scalino-lsp` alone launch for a `.scala`
buffer, no accompanying `metals` process, across multiple fresh sessions.

**Sixth bug, the real one behind this whole week's "it doesn't work in
real Zed" saga — found the same day, after the rename fix above stopped
being confused by metals racing alongside it.** `scalino-lsp`, launched
by a real, fully-quiescent, freshly-restarted Zed (no extension-reload
churn, no sleep/wake nearby), reliably failed its *first* `initialize`
request: **the raw request bytes are read successfully and completely
within milliseconds** (confirmed via a temporary byte-logging `InputStream`
wrapper — every header byte and the full JSON body logged) **but our own
RPC dispatch (`SafeLocalEndpoint.invoke`, `Log("-> initialize")`) never
fires** — no exception, no log line, nothing, from any thread. A temporary
watchdog thread (periodic `Thread.getAllStackTraces` dump + a
`Runtime.addShutdownHook`) showed every thread idle within ~3s of the
request being read, and then, at almost *exactly* 60.0 seconds after the
bytes were read, the JVM ran its shutdown hook and exited **cleanly, code
0** — matching `ThreadPoolExecutor`'s/`SynchronousQueue`-backed cached pool's
default 60s keep-alive: whatever pool thread was handling the request
either finished (silently, without dispatching) or was abandoned, and once
it timed out and self-terminated, `DestroyJavaVM`'s `joinAllNonDaemons()`
had nothing left to wait for.

Captured Zed's *exact* raw `initialize` bytes (byte-for-byte, via a `tee`
wrapper substituted for `dist/scalino-lsp` in `.zed/settings.json`'s
`binary.path`) and replayed them directly against the binary, completely
offline — **100% reproducible with zero Zed involvement**, confirming this
was never a Zed transport/spawn issue (the earlier "Zed never writes to
stdin" read from an initial botched byte-capture attempt — a real editor's
richer capabilities payload was the actual variable, not anything
Zed-side). Bisection (replaying trimmed copies of the exact captured
payload) initially isolated `capabilities.general`/`capabilities.experimental`
as sufficient triggers in isolation — both newer `ClientCapabilities`
fields the trace fixture never exercised — and adding them to
`build/lsp-trace-drive.py`'s capabilities + re-running the standalone LSP
retrace step (`build/05-regen-agent-config.sh`'s tracing block) fixed
*those two fields in isolation*. But the **full real payload still hung
identically after that fix** — a systematic one-field-at-a-time removal
sweep across every top-level `workspace.*` and `textDocument.*` key (and
`general`+`experimental` together) found **no single field whose removal
fixes it**, meaning it isn't reducible to one missing reflection
registration.

The decisive test: replaying the *exact same captured bytes* against the
*exact same compiled classes* under a **plain JVM** (`java -cp
<lsp-classes>:<lsp-java-classes>:<compiler-patched.cp>:<lsp.cp>
dotty.tools.languageserver.Main -stdio`, no native-image at all) returned a
real response in under 1 second. **This is conclusively a GraalVM
native-image-specific pathology** — some interaction between Gson's
reflective `TypeAdapter` construction (many distinct nested capability
classes touched for the first time in one large parse) and SVM's
closed-world reflection/threading model, triggered only by a sufficiently
rich real-editor-shaped payload, not by any single field. Matches a broader
known category of GraalVM issues where native-image's non-daemon-thread
lifecycle/JVM-exit timing genuinely differs from a real JVM's (e.g.
oracle/graal#12116, #434) — not something fixable by more
reachability-metadata tweaks alone.

**FIXED, same day: lsp4j (and Gson, and Jackson for `.dotty-ide.json`)
removed entirely.** Rather than chase the exact native-image/Gson
interaction bug, the whole transport was rewritten from scratch:
`Main.scala` now implements JSON-RPC/LSP framing and dispatch by hand
(Content-Length reader, a single worker thread draining a queue so a slow
request never blocks the reader from noticing e.g. `exit`, response/error/
notification encoding), and a new `Lsp.scala` defines every wire type
(`Position`/`Range`/`Location`/`Diagnostic`/`CompletionItem`/... and the
JSON-RPC envelopes) with **hand-written** `jsoniter-scala`
(`com.github.plokhotnyuk.jsoniter-scala:jsoniter-scala-core_3:2.37.3`)
`JsonValueCodec` instances — no `JsonCodecMaker` macro derivation (that's a
real Scala 3 quote/splice macro from a third-party library; expanding it
would depend on this project's own macro interpreter, patches/scala3-0001,
handling code it was never built to handle — sidestepped entirely by
writing every `decodeValue`/`encodeValue` by hand against jsoniter-scala's
low-level `JsonReader`/`JsonWriter` API). Zero reflection anywhere in the
module now, at compile time or runtime. Each params type only models the
handful of fields `DottyLanguageServer` actually reads (unknown JSON keys
are always skipped), so a real editor's much richer `initialize`
capabilities — the exact payload that broke Gson — never gets parsed into
a type at all; there's no reflection path left for it to break.
`DottyLanguageServer.scala` keeps 100% of its actual compiler-facing logic
(`InteractiveDriver`, `Interactive.*`, driver-per-project management) —
only its method signatures/return-value construction changed from lsp4j
types to `Lsp.*` types, and the lsp4j-workaround machinery (`rpcDispatch`/
`rpcMethods`/`CancelChecker`/`CompletableFutures`) is gone along with it.
`.dotty-ide.json` parsing also moved off Jackson onto a hand-written
`Lsp.ProjectConfig` codec, for the same reflection-freedom reason.
Deliberately dropped: `window/showMessageRequest` (a server-initiated
request needing bidirectional id correlation this transport doesn't
implement) — only used by `rename`'s "also rename overridden members?"
prompt, which now always says yes (matching the previous default choice);
and any `$/cancelRequest` support (never actually checked by any handler
even under lsp4j, so nothing observable changed).

Verified two ways: (1) replayed the *exact* raw `initialize` bytes captured
from real Zed (the payload that used to hang ~60s then silently exit)
directly against the new binary — response in under a second, every time;
(2) ran `build/lsp-trace-drive.py`'s full realistic session (real
capabilities+clientInfo+workspaceFolders `initialize`, didOpen x3, hover,
definition, completion, references, rename, documentSymbol,
workspace/symbol, didChange-introduces-a-type-error) against real dotc
compilation of `build/lsp-trace-fixture` — every step OK, diagnostics
correct, zero errors in `.scalino-lsp.log`. Binary also shrank
99MB → 81MB and native-image build time dropped (~2m30s → ~1m55s) with
lsp4j/Gson/Jackson off the classpath. `build/08-build-scalino-lsp.sh` no
longer needs a javac step (deleted `config/ProjectConfig.java`) or
`-H:ConfigurationFileDirectories` at all (deleted the now-dead
`agent-config/lsp/`; `build/05-regen-agent-config.sh`'s LSP tracing step
is gone, since there's no reflection left to trace).

Not yet done (see item 5 below for a follow-up pass closing most of this):
independent re-check against real Zed specifically (only verified via the
offline harness/ad hoc clients so far); whether `rpcDispatch`-era
per-project multi-driver behavior (`references`/`rename`/`implementation`
across *dependent projects*, not just multiple files in one project) still
works correctly now that a single worker thread processes requests
serially.

4. **Fixed (2026-09-06): the missing-`.dotty-ide.json` whole-process crash flagged above.** Root cause, once actually traced (a real, minimal Python LSP client -- send `initialize`/`initialized`/`didOpen` against a project with no config file, watch whether the process survives -- settled this far faster than reasoning about it further): `DottyLanguageServer.initialize`'s "warmup" thread (`new Thread(() => { try { drivers; () } catch { case NonFatal(ex) => ex.printStackTrace; sys.exit(1) } })`) calls `sys.exit(1)` on ANY warmup failure -- faithfully ported from the *original* lsp4j-based code's `CompletableFuture(...).exceptionally { ex => ex.printStackTrace; sys.exit(1) }`, so this isn't a regression the rewrite introduced, it's real upstream dotty behavior. Under GraalVM native-image, an uncaught exception on *any* thread (not just the main one) already terminates the whole process by default -- so even without the explicit `sys.exit(1)`, this thread dying would kill the server; the original code's `sys.exit(1)` just made explicit what native-image already does implicitly. Confirmed via the same test client: with the `sys.exit(1)` removed, the exception is caught, printed to stderr/`.scalino-lsp.log` same as before, but the process stays alive -- and the *next* request that needs `drivers` (`didOpen`, `hover`, ...) hits the exact same exception again, this time inside `Main.scala`'s own per-request `try`/`catch` (already existed, unrelated to this fix), which reports it as a normal JSON-RPC error response for any real *request* (definition/hover/etc, all have an id) instead of a crash -- notifications (`didOpen`) still failed silently from the client's perspective at the time (logged, no response channel exists for a notification) -- closed by item 6 below (`window/showMessage` support).

   Also added a clearer error at the actual source (`drivers`, `DottyLanguageServer.scala`): checks `configFile.exists` explicitly and throws `FileNotFoundException(s"$IDE_CONFIG_FILE not found at $rootUri -- run \`scalino setup-ide <sources...>\` in the project root first")` instead of letting a bare `NoSuchFileException` propagate -- this toolchain's workflow requires a separate, explicit `scalino setup-ide` step before ever opening the editor (unlike Metals, which bootstraps this file itself via BSP), so a real user hitting this is far more likely here than it ever was for the tool this code was originally written for.

   Verified via the same test client both before (process dies, exit code 1, confirmed) and after (process survives `didOpen` against a config-less project, still responds correctly to a subsequent `shutdown` request) the fix. Patch: `patches/scala3-0002-trim-language-server.patch` (already the file that carries every other change to this same module).

5. **Re-verified (2026-09-06): `references`/`rename`/`documentSymbol`/`workspace/symbol` against a real cross-file, multi-source single project** (the `lsp-trace-drive.py` run cited above already covered these against `build/lsp-trace-fixture`, but that fixture's exact shape wasn't re-checked cross-file at the time). Fresh two-file project (`Lib.scala`: `object Lib` with `def greet`/`case class Point`; `Main.scala`: `@main def run` calling both), driven by a small ad hoc Python client (`initialize`/`initialized`/two `didOpen`s/then each request): `references` on `greet`'s definition in `Lib.scala` correctly returned both the definition site *and* the call site in `Main.scala`; `documentSymbol` on `Lib.scala` correctly nested `Lib` → `greet`/`Point` → `Point`'s `x`/`y`/`norm`; `workspace/symbol` for `"Point"` found it; `rename` on `greet` returned a `WorkspaceEdit` with correct edits in *both* files (definition and call site). All four are real cross-file operations working correctly under the native-image binary.

7. **Re-verified (2026-09-06): multi-*project* driver behavior (`references`/`definition`/`rename` across two separate, dependent `.dotty-ide.json` projects), the one gap item 5 above left open.** `.dotty-ide.json` isn't limited to one project -- it's a JSON array (`ProjectConfig`/`projectConfigListCodec`, `Lsp.scala:81-88`), and `DottyLanguageServer.drivers` (`DottyLanguageServer.scala:67-104`) already builds one `InteractiveDriver` per entry, with `projectsSeeing`/`dependentProjects` (`DottyLanguageServer.scala:163-178,560-577`) searching a dependency's *dependent* projects too via each config's `projectDependencies` field -- this machinery was already there, just never exercised end to end by any fixture so far. Hand-built a real two-project fixture (`ProjA`: `object Shared { def util(x: Int) = x + 1 }`, pre-compiled with `scalino-dotc` to get a real `classDirectory`; `ProjB`: imports and calls it, with `ProjB`'s `dependencyClasspath` including `ProjA`'s `classDirectory` and `projectDependencies: ["ProjA"]`) and a hand-written `.dotty-ide.json` wiring both project entries. Iterated fast on plain JVM (`java -cp <lsp-classes>:<compiler-patched.cp>:<lsp.cp> dotty.tools.languageserver.Main -stdio`, no native-image rebuild needed per request-loop cycle -- same JVM-first workflow this project uses for interpreter-bug iteration, works just as well here) before re-confirming on the real native-image `scalino-lsp` binary.

   **First attempt surfaced what looked like a real bug, turned out to be a test-fixture mistake, worth recording since the failure mode is confusing and could easily be mistaken for a real product bug by a future reader hitting the same shape.** A `.dotty-ide.json` with 2 project entries where one project's `dependencyClasspath` doesn't actually include a real Scala library jar (only its own compiled output, or nothing) causes `InteractiveDriver`'s `Definitions.init` to throw `MissingCoreLibraryException: Could not find package scala` while constructing *that* project's driver (`DottyLanguageServer.scala:103`, inside the `for (config <- configs)` loop) -- BUT this exception is thrown from the async `initialize` warmup thread (see item 4's fix above), which only does `ex.printStackTrace()`, not `Log(...)`, so `.scalino-lsp.log` shows *nothing* about it. Worse: `myDrivers` was already assigned `new mutable.HashMap` (empty) *before* the loop, so the loop aborting partway leaves `myDrivers` non-null-but-empty forever (the `if myDrivers == null` cache-init guard never retries). Every subsequent request then fails with a wildly misleading `java.util.NoSuchElementException: next on empty iterator` from `configFor`'s `drivers.keys.head` fallback (`DottyLanguageServer.scala:137`) -- a generic collections error miles away from the real cause. Confirmed via temporary `System.err.println` instrumentation (added, tested, then fully reverted -- confirmed byte-identical to the tracked patch afterward via a diff against `patches/scala3-0002-trim-language-server.patch`) that decode succeeded (2 configs, correct ids) and the *first* driver construction was the one throwing. Root cause was simply that this test's own hand-written fixture forgot to populate `dependencyClasspath` with the real compiler classpath (`cs`-resolved scala3-library et al.) the way real `scalino setup-ide` always does (`cli/ScalinoCli.scala:1776`, `dependencyClasspath = cc.compileCp...`) -- not a product bug. **Worth fixing anyway as a robustness gap** (not done this pass, out of scope for a verification-only pass): `drivers` should either not cache a partially-failed result as if it were complete, or at least `Log(...)` the real exception before the warmup thread swallows it, so a genuinely misconfigured project (this exact shape *could* happen for real, e.g. a hand-edited or generator-produced multi-project config missing a dependency) fails loudly instead of turning every subsequent request into a confusing, unrelated-looking crash.

   With a correctly populated `dependencyClasspath`, all three cross-project operations work correctly, verified identically on both plain JVM and the real native-image binary: `references` on `util`'s definition in `ProjA` found both the definition site *and* the call site in the *separate*, dependent `ProjB` project; `definition` from `ProjB`'s call site correctly jumped back across projects to `ProjA`'s real definition; `rename` from `ProjA`'s definition produced a `WorkspaceEdit` with correct edits in *both* projects' files. The `rpcDispatch`-era multi-project design this code inherited from real dotty genuinely still works correctly under the single-worker-thread rewrite.

6. **`window/showMessage` support added (2026-09-06), closing the gap noted in item 4 above.** A failed *notification* (`didOpen`/`didChange`/`didClose` -- none of which have a JSON-RPC response channel to carry an error back on, unlike a request) used to only reach `.scalino-lsp.log`/stderr, invisible from the editor's side; the missing-config `didOpen` failure from item 4 is exactly this case. Added `ShowMessageParams`/`showMessageParamsCodec` (`Lsp.scala`, same hand-written-codec style as every other type here -- `type: Int` per the LSP spec's `MessageType` enum, `1` = Error) and a `sendShowMessage` helper (`Main.scala`) wired into `handle`'s existing catch-all: a request still gets `sendError` as before, but a notification now also gets a real `window/showMessage` pushed to the client. Verified end to end against the real native-image binary: `didOpen` against a config-less project now produces a `window/showMessage` notification carrying the exact `FileNotFoundException` message (not just a log line) while the server stays alive and keeps answering `shutdown` correctly afterward; a normal `didOpen` against a properly-configured project still produces the ordinary `publishDiagnostics` notification with no spurious `showMessage`. Patch regenerated (`patches/scala3-0002-trim-language-server.patch`) and confirmed to reapply cleanly from a fresh vendor checkout, then rebuilt and re-verified from that clean tree.

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
   dist-relative paths; `bin/scalino-bootstrap` and `scalino` resolve them against their own
   dist root at runtime, and `scalino` locates that root via its own executable
   path (`cli/selfexe/*.scala`, one small OS-specific native binding per
   platform) instead of a build-time-baked-in absolute path. `dist/` is now a
   self-contained, copyable/tarball-able distribution.
4. **`scalino` follow-ups.** See "Toward a build-tool experience without a JVM"
   above for what's implemented (including watch mode, directory/CLI-flag
   parity with scala-cli as of the "closing the gap" pass, and incremental
   compilation -- see item 7). Still missing: multi-module/multi-target
   projects, a real (not heuristic-text-scan) entry-point detector, and
   scala-cli's `fmt`/`repl`/`package`/`publish`/`bsp`/`export` commands --
   none of those have an obvious JVM-free equivalent yet (no scalafmt, no
   bloop), so they currently just print "not implemented" rather than being
   faked.

   `scalino test` (2026-09-04) *is* now implemented, with no JVM anywhere in
   the chain -- see the doc comment above `readAllBytes`/`ClassInfo` in
   `cli/ScalinoCli.scala` for the full design. In short: real (JVM) sbt-scala-native
   drives test execution from the sbt/JVM side over a ComRunner socket
   protocol; `scalino` instead (1) structurally scans the resolved test
   classpath's jars for a class implementing `sbt.testing.Framework` --  no
   hardcoded per-framework list, so any framework with a scala-native port
   is found the same way -- (2) compiles+links+runs a tiny throwaway "probe"
   binary that instantiates it and prints its real `fingerprints()` (cached
   after the first run), since there's no JVM to query that cheaply, (3)
   scans the user's *compiled* test classes structurally against those
   fingerprints (ancestry walk across both compiled output and classpath
   jars -- a from-scratch substitute for `Class#isAssignableFrom`), and (4)
   generates a small driver source that instantiates the framework(s) by
   literal name (no reflection) and drains `Task.execute` synchronously.
   Test-object instantiation inside a discovered `Task` turned out to need
   no bridging work at all on `scalino`'s end: scala-native's native test-port
   authors already rely on `scala.scalanative.reflect.Reflect`/
   `@EnableReflectiveInstantiation` for it (confirmed both via source
   research and by a from-scratch hand-rolled `sbt.testing.Framework` smoke
   test using that exact mechanism, exercised end to end -- discovery,
   probe, driver codegen, link, run, pass/fail/exit-code all verified
   correct).

   **munit: fixed (2026-09-04), verified end to end.** The blocker above
   (`PositionMethods`/`TermMethods` position-capture macros) is closed --
   `Interpreter.scala` now implements `TreeMethods#pos`, `SymbolMethods#pos`,
   `TermMethods#underlyingArgument`/`underlying`, and the full
   `PositionMethods` surface (`start`/`end`/`sourceFile`/`startLine`/
   `endLine`/`startColumn`/`endColumn`/`sourceCode`) as direct real-dotc
   calls, the same pattern as every other `XMethods` extension bag. A real
   `munit.FunSuite` test (`assertEquals`, real `Location`/`Clue` macros)
   now compiles, links, and passes via `scalino test` end to end -- see
   `patches/scala3-0001-*.patch` for the diff, applied to
   `vendor/scala3/compiler/src/dotty/tools/dotc/quoted/Interpreter.scala`.

   **utest: interpreter-side gaps closed, blocked on a separate, later
   dotc bug (2026-09-04).** Getting `assert(...)`/`Tests { ... }` to
   interpret correctly needed a long tail of real, narrow gaps closed in
   `Interpreter.scala`, in the order they surfaced:
   - **General user-defined `unapply`/`unapplySeq` fallback** (this is
     the real fix for remaining-work item 1's "general quote-pattern
     matching" gap, as far as CUSTOM extractor objects with a retained
     tree go): previously `UnApply` matching only worked for a curated
     list of well-known extractors. Now, when none of those match, it
     falls back to actually INTERPRETING the extractor's own retained
     `unapply` body via `callUserDefDef` -- the same machinery any other
     macro helper method already goes through -- instead of requiring a
     hardcoded case per extractor. Needed to handle curried `unapply`s
     (`def unapply(using Quotes)(tree): Option[...]`, unwrapping the
     `Apply(Select(qual,"unapply"), givenArgs)` shape down to the base
     `Select` and threading the leading args through).
   - `quotes.reflect.Term`'s `TermTypeTest`, `Inferred`/`InferredTypeTest`,
     `Closure`/`ClosureTypeTest`, `Return`/`Try`/`While`/`SummonFrom` (+
     their TypeTests) -- the remaining `quotes.reflect` tree-shape
     extractors the real default `TreeMap#transformTerm`/`transformTypeTree`
     (`Quotes.scala`'s own library source, itself interpreted like any
     other macro-adjacent code) needs when a macro's own `new TreeMap {
     override def transformTerm = ... }` calls `super.transformTerm` for
     shapes it doesn't override.
   - The matching `XxxModule#copy(original)(...)` for every one of those
     shapes (`Select`/`Ident`/`Super`/`Apply`/`TypeApply`/`Literal`/`New`/
     `Typed`/`NamedArg`/`Assign`/`Block`/`If`/`Match`/`Closure`/`Repeated`/
     `CaseDef`/`ValDef`/`DefDef`/`Return`/`Try`/`While`/`SummonFrom`) --
     real QuotesImpl implements essentially all of these as a thin
     `tpd.cpy.Xxx(original)(...)`, called the same direct way.
   - `reflectModule.this` (the `quotes.reflect` bundle's own self-type,
     referenced by its default trait methods): falls back to a freshly
     built `QuotesImpl()` (stateless beyond the ambient `Context`, so any
     instance is a valid stand-in), instead of crashing as an unbound
     `This`.
   - `SymbolMethods#isDefDef`/`isClassConstructor`, `DefinitionMethods#name`,
     `ValDefMethods#tpt`/`rhs`, `DefDefMethods#returnTpt`/`rhs`/`paramss`.
   - `Expr#show` (declared directly on `trait Quotes`, not a separate
     `XMethods` bag, so dispatched by an `ExprImpl` showing up in the args
     rather than by owner name) and `TermModule#betaReduce` (a direct call
     into dotc's own real `dotty.tools.dotc.transform.BetaReduce`
     compiler-internal utility, exactly like calling any other dotc-internal
     API this file already does).
   - **A real, general bug in secondary/auxiliary-constructor
     interpretation**, found via `scala.util.matching.Regex`'s own `def
     this(regex: String, ...) = this(Pattern.compile(regex), ...)`:
     `interpretNewFromTree` used to always jump straight to
     `fn.owner.primaryConstructor`'s body regardless of which overload was
     actually called, binding the call's real arguments against the
     PRIMARY constructor's differently-shaped params -- e.g. leaving
     `Regex#pattern` holding the raw source STRING, not a compiled
     `Pattern`. Fixed to interpret the actual called constructor's own
     body when it isn't the primary one (almost always a single delegating
     `this(...)` call, handled by interpreting that call and keeping ITS
     result as the real instance, since a constructor body is `Unit`-typed
     at the language level and would otherwise discard it).
   - `java.util.regex.Pattern`/`Matcher` and `java.lang.StringBuilder`
     (real JDK classes, no retained source) as direct-call intrinsics --
     needed by `Regex#replaceAllIn`'s own real (interpreted) source.
   - A quote-pattern structural-matching gap: the SAME real symbol can be
     referenced through a different number of `Select` hops depending on
     unrelated context (a package vs. its synthetic package-OBJECT
     accessor, e.g. `utest.package.test` vs. plain `utest.test` for the
     identical `test` method) -- `matchQuoteBodyTree`'s `Select` case now
     falls back to whole-reference symbol identity when the scrutinee
     isn't ALSO `Select`-shaped, instead of failing outright on a
     structural difference that isn't a real one.

   With all of that, utest's `Tests { ... }`/`assert(...)` macros now
   interpret and expand SUCCESSFULLY (verified via the JVM-based
   `dotty.tools.dotc.Main` fast-iteration path, bypassing the ~2min
   native-image rebuild per change) -- but the EXPANDED code then crashes
   real dotc's `LambdaLift` phase (`Dependencies$NoPath` in
   `Dependencies.markFree`, i.e. this is *after* our own-implementation
   interpreter's job is done and normal dotc compilation of the
   macro-expanded tree has taken over). This is a **separate, deeper dotc
   compiler-internals issue** -- not a macro-interpretation gap -- and
   needs its own investigation (likely an owner-chain/`-Yretain-trees`
   interaction affecting how a macro-expanded closure's free variables get
   resolved). Confirmed via both the JVM fast-loop and the real native
   `scalino test` pipeline (`scalino-dotc`), so not an artifact of either path.
   Repro: `//> using dep "com.lihaoyi::utest::0.8.4"` +
   `object T extends utest.TestSuite { val tests = utest.Tests { test("x")
   { assert(1 == 1) } } }`, `scalino test .`.

   scalatest not retested this session (untouched since the original
   blocker note) -- likely has its own similar tail of gaps given it also
   uses position-capture macros, not yet investigated.

   **Serious general bug found and fixed via a real third-party project
   (2026-09-04): the interpreter's own `StopInterpretation` "give up,
   unsupported" signal could be SILENTLY SWALLOWED by an ordinary `try`/
   `catch` inside interpreted macro code.** `matchesRuntimeType` (used for
   a catch clause's `case _: SomeType =>` typed-wildcard pattern) has an
   explicit `else true // best effort; a wrong positive only widens
   matching, doesn't crash` fallback for any type it doesn't specifically
   recognize (only `Int`/`Long`/`Boolean`/`String` are checked precisely).
   Combined with `NonFatalInterpretedException.unapply` being a bare
   `scala.util.control.NonFatal.unapply` (which doesn't and can't
   distinguish "a real exception from the program being interpreted" from
   "the interpreter's own internal abort signal", since `StopInterpretation
   extends Exception` is unremarkably non-fatal), this meant: whenever
   interpreted macro code caught an exception with `case _: X => ...` for
   ANY `X` (however narrow or unrelated), a `StopInterpretation` raised
   *inside* that `try` was wrongly treated as "matches" and swallowed --
   turning a clean "unsupported feature" error into silently wrong/garbage
   output instead. Found via `scala.sys.SystemProperties#wrapAccess` (`try
   Some(body) catch case _: AccessControlException => None`, used by
   munit's own `MacroCompat`'s working-directory computation): calling the
   then-unsupported `System.getProperty` inside `body` didn't fail loudly,
   it silently returned `None` -> `null`, corrupting `MacroCompat`'s
   computed path and causing a CASCADE of unrelated-looking downstream
   failures (`endsWith`/`getJPath` errors) across every test file in the
   project, not just the one that actually needed `System.getProperty`.
   **Fixed** by making `StopInterpretation extends scala.util.control.
   ControlThrowable` instead of plain `Exception` -- `ControlThrowable` is
   Scala's own established mechanism for exactly this ("a parent class for
   throwable objects intended for flow control ... instances should not
   normally be caught", its own doc says outright that `NonFatal` won't
   match it), so `NonFatalInterpretedException` (line above) now correctly
   never matches a `StopInterpretation` at ANY catch site, current or
   future, without needing a manual rethrow guard at each one (the first
   fix attempt added exactly such a guard to the one `Try`-node site found;
   the `ControlThrowable` fix subsumes and replaces it, verified to
   reproduce the identical error reduction). This is a correctness fix
   independent of any specific macro library -- any macro whose real
   source wraps a not-yet-supported call in a `try`/`catch` was previously
   at risk of silently wrong (not just incomplete) macro expansion.

   **Other concrete gaps closed the same session** (found via a real,
   large third-party project -- http4s/upickle/porcupine/cats-effect, not
   just munit/utest): `SourceFileMethods#getJPath`/`name`/`path` (munit
   1.3.4's `MacroCompat` uses `getJPath`, not the deprecated `jpath` 1.0.0
   used); `java.io.File.separator`/`separatorChar`/`pathSeparator`/
   `pathSeparatorChar` and `java.lang.System.getProperty`/`getenv` (real
   JDK statics, no retained source); a `New(...)` quote-pattern case in
   `matchQuoteBodyTree` (`case '{ new StringContext(...) } =>`, real
   scala-library `FromExpr[StringContext]`'s own pattern -- `New`'s own
   `.symbol` is always `NoSymbol`, so matching needs to recurse into the
   `tpt` child instead, whose class symbol is meaningful).

   **Known still-open gaps found via the same project** (not yet fixed,
   third-party-library-specific, lower priority than the general bug
   above): upickle's `derives ReadWriter` for `enum`/case-object types
   needs `java.lang.Class#getClassLoader` somewhere in its `isSingleton`/
   `Mirror`-adjacent macro machinery (`upickle.implicits.macros`) -- not
   yet traced to the exact call site. A separate `self` field-accessor
   failure surfaces deep inside `scala.collection.IterableOps.WithFilter`
   interpretation (real stdlib `for`-comprehension-with-guard desugaring),
   encountered via http4s's `uri"..."` literal macro
   (`org.typelevel.literally.Literally`) but NOT actually part of that
   macro's own quote-pattern matching (a red herring from the error's
   `fn.owner` name, "Typed", which is coincidental/misleading here) --
   needs its own trace, not yet done.

   Auto-detection was independently confirmed working against a real
   published framework regardless of any of the above (a project
   depending on `munit` with no test classes correctly reports "no tests
   found" rather than "no test framework found" -- i.e. `munit.Framework`
   really was found and probed successfully).
   Known scope gaps vs real scala-cli, by design (not blocked on anything):
   only `SubclassFingerprint`-based frameworks (covers
   munit/utest/scalatest/zio-test-sbt), not JUnit4-style
   `AnnotatedFingerprint`; whole-test-class selection only, no per-test-
   method filtering (`-- <pattern>` is forwarded to the framework's own
   runner, matching real scala-cli's own convention there).
5. Now covered by CI (`.github/workflows/ci.yml`) on Linux and macOS,
   x86_64 and arm64 -- all four build, link, and run the plain-program and
   real-macro smoke tests, plus a relocatability check that mirrors the
   release tarball's flat layout. Windows (x86_64 and arm64) is still
   experimental: CI found and fixed three real bugs so far (GraalVM tool
   paths need `.exe`/`.cmd` resolved explicitly for a literal path, `git
   apply` needs `-c core.autocrlf=false` on the vendor clone plus
   `.gitattributes` pinning `patches/*.patch` to LF, and `require()` needs a
   plain existence check rather than `command -v` for literal paths -- see
   the "resolve GraalVM tool paths"/"force LF for vendor clone" commits) and
   is now getting as far as `02-build-java-base.sh` before failing silently
   in `03a-patch-compiler.sh` with no error output. Prime suspect: `cs
   fetch --classpath`'s output separator. Every build script (`01-fetch-
   deps.sh` onward) joins/splits classpaths on a hardcoded `:`, matching
   Unix's `java.io.File.pathSeparator` -- Windows' is `;`, and even if that
   weren't a factor, naively splitting a Windows path with a drive letter
   (`C:\...`) on `:` would mis-parse it regardless of the real separator.
   Confirming and fixing this needs actually reading a failed Windows run's
   intermediate `.build-work/*.cp` files (not currently uploaded anywhere),
   or a real Windows box -- deliberately not guessed at blind here.
6. `bin/scalino-bootstrap`'s "first source file's basename is the main class" convention is
   still naive (unlike `scalino`, which auto-detects) — for multi-file macro
   examples via `bin/scalino-bootstrap` directly, the entry-point file must be listed
   first (see `examples/macro-hello/` usage in the README).
7. ~~Only compiling Scala source is incremental-cache-free.~~ **Fixed
   (2026-09-04): own zinc-style incremental compilation, no real Zinc/JVM
   involved.** Real sbt Zinc gets its invalidation graph from a "compiler
   bridge" hooked deep into the compiler's own symbol table -- exactly the
   kind of JVM/dotty-internals tie this project's vendor+patch+splice
   approach exists to avoid taking on wholesale (and the same reason full
   self-hosting was ruled out in the first place -- see the top of this
   doc). Actual Zinc is also a poor native-image candidate on its own
   terms: it dynamically classloads the compiler bridge by reflection, the
   same closed-world-hostile shape that's caused problems elsewhere in
   this codebase.

   Instead, `compileToClasses`/`buildBinary` (`cli/ScalinoCli.scala`) reconstruct
   an *approximate* dependency graph purely from source text: which
   top-level names each file declares (a regex scan, same spirit as this
   file's existing directive/entry-point heuristics), and which of those
   names each *other* file's text mentions. On a rebuild: hash every given
   source file's content (FNV-1a, same as the existing dependency-classpath
   cache keys), diff against the last successful build's manifest
   (`.incr-manifest`, a plain tab/newline file next to the classes dir --
   this project's usual style for on-disk caches, no JSON parser needed)
   to get the changed set, then widen it by walking the "who textually
   mentions one of this file's declared names" graph to a fixed point.
   Only that closure is handed to `scalino-dotc`; the classes directory is no
   longer wiped before every build and is added to the compile classpath,
   so everything outside the closure resolves from its already-compiled
   `.tasty`/`.class` instead of needing its source recompiled -- the same
   trick real Zinc uses to avoid recompiling unaffected compilation units,
   just driven by a text-scan graph instead of a real symbol table. Stale
   artifacts (a class renamed or removed from a file since the last build,
   or a whole source file dropped from the set) are purged by filename
   match before recompiling.

   Deliberately more conservative than real Zinc's name-hashing: it
   invalidates a dependent on ANY change to a file it mentions, not just
   an API-visible one (no attempt to tell a body-only edit from a
   signature change), so it recompiles strictly more than a real bridge
   would -- but it can never *under*-invalidate, so a stale binary isn't a
   failure mode this approach can produce. A whole-project fingerprint
   (compile classpath, scalac options, `scalino-dotc`'s own mtime) forces a
   full rebuild whenever any of those change, since none of them are
   tracked per-file. `--no-incremental` forces a full rebuild on demand.
   Known gap: the declaration scan is top-level-only (by regex, not real
   parsing), so a name declared only inside a nested object has no edges
   in the graph -- the file containing it still recompiles correctly on
   its own changes, the gap is only in rippling to files that reference
   that nested name specifically.
8. **Four more general interpreter bugs found + fixed (2026-09-04/05), via
   `scalino test .` against a real large third-party project (`~/scala/ape`:
   http4s/ip4s/cats-effect/upickle) instead of a curated fixture.** Fresh
   regression coverage for all four lives in `examples/interpreter-
   regressions/` (self-contained, no external deps) and runs in CI
   alongside `examples/macro-hello/`.

   - **`Regex#unapplySeq`'s own varargs/`_*` pattern slot** (`case
     SomeRegex(_*) => ...`) was being matched through the interpreter's
     GENERAL `Typed(Ident(WILDCARD), tpt)` case, which type-tests the
     extracted value's runtime class against `tpt.tpe`'s class -- but that
     slot's own declared type is the synthetic `scala.<repeated>[...]`
     marker, not `Seq[...]` itself, and has no real ancestry relationship
     to the extracted `List`/`Nil` at all, so a genuinely-matching regex
     (real `unapplySeq` returning `Some(Nil)`) still fell through to
     `case _ => None`. Found via ip4s's own `Hostname.fromString` (`case
     Pattern(_*) => ...`) silently reporting "Invalid hostname" for a
     hostname that plainly is valid. Fixed by special-casing this ONE
     pattern slot (`Interpreter.scala`'s `matchPattern`, the varargs-size-
     mismatch branch) to unwrap `Typed`/`Bind` directly instead of going
     through the general type-test path: `_*` always matches, `xs @ _*`
     binds the whole list.
   - **`String#split`'s own JDK-native intrinsic returned `.toSeq`**
     instead of the real `Array[String]` real Java/Scala semantics
     produce. Looked harmless (our own `Seq`-based `map`/`filter`
     intrinsics then applied directly, no real `ArrayOps` interpretation
     needed) but is a genuine representation mismatch: the SOURCE tree
     already has an implicit `scala.Predef.refArrayOps`/`genericArrayOps`
     conversion node wrapping the split result wherever it needs
     `.iterator`/etc (inserted by dotc's typer, which only ever saw the
     static `Array[String]` return type) -- interpreting that conversion
     with an `ArraySeq` substituted for the real array it expects crashes
     deep inside real `ArrayOps` source, which pattern-matches on its own
     `xs` field's runtime type (`Array[AnyRef]`/`Array[Int]`/etc) to pick a
     primitive-vs-reference iteration strategy and has no case for a boxed
     `ArraySeq`. Found via the same `Hostname.fromString` (`value.split
     ('.').iterator.map(...)`), one call after the varargs-slot fix above.
     Fixed by simply returning the real array from both `split` overloads.
   - **`new Base(args) {}` (anonymous-subclass instantiation) lost its own
     constructor args entirely** whenever the real arg expression needed
     evaluating (e.g. a closure literal for a `Function0`-typed field): the
     synthetic `$anon` class's OWN primary constructor takes no value
     params at all, since the real args live in the SUPERCLASS constructor
     call, in `$anon`'s `Template.parents` -- which `-Yretain-trees` never
     puts in `$anon`'s own trivial ctor body. A previous session already
     built `moduleSuperCtorCall`/`findTemplate` to work around the same gap
     for `object Foo extends Bar(args)`, but two more gaps stood between
     that and covering anonymous classes too: `ClassSymbol#rootTree` is
     unconditionally `EmptyTree` for anything that isn't a TOP-LEVEL class
     (a `$anon` never is, confirmed via `SCALINO_INTERP_DEBUG` -- every lookup
     came back empty), and `findTemplate` itself only recursed into nested
     class-body members, never into a method/`val` body where a LOCAL
     anonymous class actually lives. Fixed both: `moduleSuperCtorCall` now
     starts from `topLevelClass` (walking up to the nearest real top-level
     symbol, a no-op for the original module case) instead of the target
     class itself, and `findTemplate` now also recurses into `Block`s and
     `ValOrDefDef` bodies. `interpretNewFromTree`'s own primary-ctor branch
     then binds the found superclass call's args onto the anon instance's
     fields, gated strictly by `isAnonymousClass` so it can't touch the
     already-working named-class path (a broader, ungated version of this
     same idea was tried and reverted in an earlier session -- see the
     comment left in place above it). Found via cats' own `Eval.defer`
     (`new Eval.Defer[A](() => a) {}`), manifesting as "Interpreter (own
     implementation) does not support calling method apply in trait
     Function0" (a real host closure the interpreter already knows how to
     call, `Defer`'s `thunk` field just never actually held it) -- reached
     through http4s's `uri"..."` literal macro.
   - **`scala.runtime.ClassValueCompat`'s abstract `computeValue` was
     unreachable from ANY module extending it**, because `interpretModule
     Access`'s existing (already-known, already-documented) workaround for
     `object Foo extends Bar(args)` deliberately builds a throwaway,
     separate instance of the superclass rather than one identified as the
     module itself -- so a module like `scala.reflect.ClassTag`'s own
     private `cache` object, which overrides `computeValue` concretely,
     never got virtual dispatch to find that override; the abstract
     declaration is all `notInterpretable` ever saw. The obvious general
     fix (bind the instance as the module class) was already tried
     BROADLY in an earlier session and reverted -- it regressed unrelated
     modules (upickle's `ReadWriter` derivation, `StackOverflowError`).
     Fixed narrowly instead: `interpretModuleAccess` now builds the
     module-identified instance ONLY when the super-called class is
     specifically `scala.runtime.ClassValueCompat` (checked by the actual
     class being extended, not by the module's own name/shape), so no
     unrelated module can be affected. Found via `ClassTag`'s own internal
     caching, reached through http4s's `uri"..."` literal macro.
   - **A curated, by-bare-name extractor ("Ident", meant for `quotes
     .reflect.Ident`) silently shadowed an unrelated same-named user case
     class** (cats' own private `Eval.Ident[A, B](ev: A <:< B)`, its
     `Eval` trampoline's continuation-stack ADT) -- the curated handler
     correctly fails closed (returns "no match") for a value that isn't
     really a `quotes.reflect` tree, but since that `None` came from
     INVOKING the found handler rather than failing to find one at all,
     the generic case-class-unapply fallback never got a turn, and `case
     Ident(ev) => ev(a)` wrongly reported "doesn't match" for a value that
     structurally is one -- a `MatchError` several frames later. A BROAD
     fix (compose every curated handler with the generic fallback on its
     own `None`) was tried first and regressed several other,
     previously-working macros (ip4s's `port"..."`, upickle enum
     derivation, several `derives ReadWriter` sites) -- `genericUserUnapply`'s
     own "try this candidate speculatively regardless of the
     scrutinee's actual shape" strategy isn't safe as a blanket fallback.
     Reverted to composing ONLY the one curated name actually known to
     collide (`"Ident"`), leaving every other curated extractor's original
     behavior untouched. Found via cats' `Eval#value` trampoline, reached
     through http4s's `uri"..."` literal macro.

   **Known still-open gap, found but NOT fixed this session:** http4s's own
   `uri"..."` literal macro still doesn't fully interpret end-to-end --
   after the four fixes above, it progresses much further (`Main.scala`'s
   `host"..."`/`port"..."` literals and every `derives ReadWriter` site in
   `~/scala/ape` now compile clean) but still fails inside cats-parse's own
   parser-simplification machinery: `Parser.Impl.unmap(pa: Parser[Any])`
   (a real, hand-written exhaustive match with no case for `Pure`) somehow
   receives a genuine `Pure` instance as `pa`, which real, correctly-typed
   compiled code could never do (`Pure` is a `Parser0`, not the stricter
   `Parser` `unmap` demands) -- a `MatchError` deep inside `cats.parse
   .Parser.Impl.unmap`. Confirmed this is NOT `matchesRuntimeType` being
   wrong again (every real `Parser`-vs-`Parser0` runtime type test observed
   via `SCALINO_INTERP_DEBUG` across a full test run, including several
   involving `Pure` itself, returned the CORRECT answer) -- the `Pure`
   value must be reaching `unmap` through some OTHER path (likely
   `Parser.Impl.expect1`'s own `case p1: Parser[A] => p1` narrowing, or a
   `OneOf0`-simplification step upstream returning an unexpected shape),
   not yet traced to its exact origin. `scalino test .` on `~/scala/ape`
   still fails on this one file (`test/http/RoutesTest.scala`, every test
   method using the `uri"/"` literal) as a result.
9. **Three more general interpreter bugs found + fixed (2026-09-05), via
   `examples/macro-hello` -- switched from a plain `println` macro to a real
   `jsoniter-scala` `JsonCodecMaker.make` derivation (a much harder, real
   third-party macro) to give this example ongoing regression value; see
   `examples/macro-hello/Test.scala`. Not yet passing end-to-end (see the
   still-open gap below), so NOT wired into CI yet -- do that once it's
   fully green.**
   - **`mutable.Map#getOrElse`/`#getOrElseUpdate` didn't force their own
     by-name default/`op` parameter** -- both real signatures take it
     `=> V1` (same as immutable `Map#getOrElse`, already handled correctly
     a few cases above), but the `mutable.Map` cases in
     `interpretStdlibIntrinsic` matched it as a plain `Object` and passed
     the still-wrapped `ByNameArg` straight through as if it were the
     value, instead of calling `.force()` in the miss case. Found via
     jsoniter's own `refs.getOrElse(refKey, { ...builds a new ref... })`
     pattern (shared-method memoization for recursive derivations) --
     forcing the default surfaced fine there, but the raw `ByNameArg`
     object itself, unwrapped, then leaked into a REAL `ApplyModule.apply`
     call downstream as its `fun: Term` argument, which doesn't type-test
     as `Tree` and so silently fails closed with "no built-in intrinsic
     exists" several frames away from the actual bug.
   - **A local `val`/parameter bound to an already-interpreted function
     VALUE (not a real method symbol), called WITH real arguments via a
     bare symbol reference, silently dropped every argument.** The
     `Call(fn, args)` dispatch's own generic fallback (`else if
     (env.contains(fn.symbol)) env(fn.symbol)`, meant for a BARE value
     reference with zero args -- `Call`'s extractor also produces this
     shape for e.g. plain `Ident(f)`) never checked `rawArgs.isEmpty`, so
     a genuine call on such a value (e.g. jsoniter's own
     `genReadCollection`, whose `readVal`/`result` parameters are
     `Quotes ?=> Expr[B] => Expr[C]` callbacks invoked later as
     `readVal('x)`/`result('x)`) just returned the raw, unapplied closure,
     discarding the real argument entirely. This alone produced no visible
     error -- `resolveNestedSplices`'s own "keep feeding a fresh
     `QuotesImpl` while the result is still an unapplied function" fuel
     loop (see item 8's sibling fix, or the doc comment on
     `resolveNestedSplices` in `Splicer.scala`) "helpfully" kept reducing
     whatever unapplied closure came back by feeding it MORE dummy
     `QuotesImpl`s -- silently binding a real value-typed parameter (e.g.
     `genReadValForGrowable`'s own `x: Expr[G]`) to a bogus `QuotesImpl`
     instead of the real argument, several calls downstream of where this
     actually went wrong, surfacing as "Unexpected result interpreting a
     nested splice: QuotesImpl@...". Traced via three rounds of targeted
     `SCALINO_INTERP_DEBUG`-gated prints (now left in place, gated the same
     way): one at `resolveNestedSplices`'s own entry/failure points, one
     logging `Ident` lookups for symbol name `"x"` specifically, one
     logging every `Apply` node that falls through the `SpliceInterpreter`
     override's special cases -- confirmed the leaked value's OWN symbol
     (not just its runtime class) really was `genReadValForGrowable`'s `x`
     parameter, bound to a `QuotesImpl`, several calls before the crash
     site. Fixed by applying `rawArgs` (via the existing `applyFn1`
     single-arg-apply helper, folded left-to-right for however many
     curried argument groups `Call` flattened) whenever `rawArgs.nonEmpty`,
     instead of returning the closure unapplied.
   - **`BlockModule.apply(stats, expr)` didn't convert its own `stats`
     argument through `asObjectList`** the way the neighboring
     `ApplyModule.apply` case already does, so a real `List[Statement]`
     built via a `ListBuffer#toList` call (jsoniter's own
     `Block(defs.toList, codecDef)`, `defs` a real interpreted
     `ListBuffer[Statement]` -- see `asObjectList`'s own doc comment on why
     `::`/`Nil` built this way stay `InterpretedInstance`s rather than
     real host `List`s) failed to match `stats: List[Tree @unchecked]` and
     fell through to "no built-in intrinsic exists" the same way as the
     first two bugs above.

   **Known still-open gap, found but NOT fixed this session:** with all
   three bugs above fixed, `JsonCodecMaker.make`'s own macro expansion now
   runs to completion (no more interpreter errors) -- but the SPLICED
   RESULT then fails a few phases later, during inlining:
   `undefined: x.addOne # -1: TermRef(TermRef(NoPrefix,val x),addOne) at
   inlining`. This looks like an owner-chain/hygiene gap in the final
   spliced tree (a reference to a generated-code local `val x` that isn't
   reachable from its own use site's owner chain by the time a later
   phase looks it up) rather than an interpretation gap -- in the same
   family as the `moduleSuperCtorCall`/anonymous-class-ctor-arg owner-chain
   fixes from an earlier session (item 8), but for `ListBuffer`-accumulated
   `defs`/local-`val`-declaring code spliced in via `Block(defs.toList,
   ...)` specifically. Not yet traced to its exact origin -- next session
   should start with `SCALINO_INTERP_DEBUG=1` and grep for `changeOwner`/
   `changeNonLocalOwners` call sites reachable from `BlockModule.apply`'s
   own construction path, and check whether `defs`' individual `DefDef`
   trees (each built via a separate `Symbol.newMethod`/`DefDef.apply` call,
   likely at a different point in the recursive derivation than where
   they're finally spliced into the top-level `Block`) have their owners
   correctly rechained to the enclosing class before/during that final
   `Block.apply` call.
10. **`scalino test .` against `~/scala/ape` fixed end-to-end (2026-09-05, real project, not a curated fixture) -- from the http4s `uri"..."` literal macro failing to compile at all, to the full real test suite actually compiling, linking, and RUNNING natively.** User asked to fix all bugs blocking this. Eight separate real bugs, spanning the interpreter, native-image reflection reachability, and dependency resolution:
    - **`isInstanceOf` was unconditionally `true`** (`"best effort: we don't retain the type argument through the Call extractor"`) -- stale: `callTypeArgs(tree)` (recovered from the ORIGINAL tree for exactly this reason, already threaded through for `classOf[T]`/callee type-param binding) was sitting right there unused. Silently broke any real runtime type dispatch built on it -- cats' own `AndThen.apply`/`#andThen`, `case ref: AndThen[A, B] @unchecked => ref`, always took that branch even for a bare (never-wrapped) closure, skipping the real `Single(f)` construction a fresh `AndThen` needs. The unwrapped closure then flowed wherever a real `Single`/`Concat` was expected, and `AndThen#runLoop`'s exhaustive match over those two cases threw a spurious `MatchError`. Fixed by wiring `typeArgs.headOption` through to `matchesRuntimeType` (already used for typed *pattern* tests, just never for plain `.isInstanceOf` calls) -- with one refinement: `matchesRuntimeType`'s own lenient "unrecognized real host object -> assume true" fallback needed a matching carve-out for a BARE closure specifically (a raw `FunctionN`, never itself constructed via `interpretNew` -- any real wrapped instance would already have taken the ancestry-checked branch above), since that idiom is exactly "is this plain function value ALREADY the wrapped type."
    - **`var x: T = compiletime.uninitialized` (the 3.x replacement for `_`) wasn't recognized as an uninitialized placeholder.** The real `UninitializedDefs` MiniPhase normally rewrites this to the same `Ident(WILDCARD)` sentinel `_` already got -- but `-Yretain-trees` captures trees from before that phase runs, so this interpreter's own uninitialized-check (`vdef.rhs.isEmpty || Ident(WILDCARD)`) never saw the rewritten form, genuinely interpreting the original `compiletime.uninitialized` call instead (real body: `throw new NotImplementedError(...)`, a `@compileTimeOnly` marker never meant to run). Fixed by also recognizing a bare reference to `defn.Compiletime_uninitialized` -- the same symbol `UninitializedDefs` itself checks for.
    - **The "no initializer" fallback used a blanket `null`**, correct only for reference types -- `compiletime.uninitialized`'s whole point is covering value types `_` alone couldn't (a bare `_` initializer is invalid there), so a primitive-typed var using it (e.g. `Int`) needs a real scalar zero, not `null`. Added `defaultValueOf(tpe)`, mirroring `matchesRuntimeType`'s own `cls ==` dispatch over `defn.{Int,Long,Boolean,Double,Float,Char,Byte,Short,Unit}Class`.
    - **No curated intrinsic existed for `new IndexOutOfBoundsException(msg)`** (only `UnsupportedOperationException`/`IllegalArgumentException`/`IllegalStateException`/`NoSuchElementException`/`RuntimeException` were curated) -- genuinely thrown as part of NORMAL control flow by real stdlib code (`ArrayBuffer#checkWithinBounds`), not just an "unreachable" fallback. Same for `Throwable#getMessage`/`#getCause` (real JDK methods, no retained source, needed by any interpreted `catch` handler inspecting what it caught).
    - **A REAL host object's own uncurated method reading/writing ITS OWN private field silently went stale or crashed.** `ArrayBuffer#update` has no curated intrinsic, so its real body (`checkWithinBounds` reading `size0`, then `mutationCount = mutationCount + 1`) is genuinely tree-interpreted with `this` bound to the real host `ArrayBuffer` -- but a real object has no `.fields` map. The READ side silently replayed the field's ORIGINAL initializer forever, hiding real mutation already applied through a curated intrinsic (`ArrayBuffer#addOne`, which calls the genuine host `+=`) -- `size0` looked permanently `0` no matter how many real elements were added, so a later in-bounds `.update(0, ...)` threw a spurious `IndexOutOfBoundsException`. The WRITE side (`Assign` on a `Select` whose qualifier isn't an `InterpretedInstance`) just crashed outright (`unexpectedTree`) -- there was no case for it at all. Fixed by adding `reflectiveFieldRead`/`reflectiveFieldWrite`, reading/writing the REAL field via reflection (walking real superclasses for an inherited field) when the receiver is a genuine host object; both fall back to the old behavior on any failure. Traced to this exact root cause via cats-parse's own `DropRightIterator` (`scala.collection.View`'s `dropRight` combinator), reached through http4s's `uri"..."` literal macro validating the literal at macro-expansion time.
    - **`java.lang.String#regionMatches`** (both overloads) had no curated intrinsic -- a real JDK method, no retained source, needed by cats-parse's own literal-matching machinery.
    - **A real top-level `val`'s own JVM semantics (computed once, every read returns the SAME instance) weren't honored** -- `interpretStaticFieldAccess` re-interpreted the initializer tree on EVERY access, building a fresh value each time. Third-party library internals routinely rely on `eq` (physical identity) against a cached top-level `val` as a fast-path sentinel (cats-parse's own `Parser.unit`); a fresh instance every read always failed those checks. Fixed with a per-`Interpreter`-instance (i.e. per-macro-expansion, matching real once-per-classloading semantics closely enough) memoization cache, `staticFieldCache`.
    - **Under native-image specifically (NOT reproducible via the unrestricted-reflection JVM iteration loop this session otherwise used), `reflectiveFieldRead`/`reflectiveFieldWrite`'s own reflective calls needed the touched classes/fields traced into `agent-config/dotc/reachability-metadata.json`, and even after retracing, an UNCAUGHT crash was still possible for the next untraced class.** `build/05-regen-agent-config.sh`'s existing dotc-tracing fixture (`interpreter/test-fixtures/macros-in-same-project1`) never exercised this new reflective-field code path at all, so `scalino-dotc` (unlike the plain-JVM fast loop) hit `org.graalvm.nativeimage.MissingReflectionRegistrationError` -- and since that's a bare `Error`, not a `ReflectiveOperationException`, it wasn't caught by either helper's existing `catch`, crashing the WHOLE COMPILER outright instead of falling back gracefully per-macro. Fixed two ways, both needed: (1) a second tracing pass in `05-regen-agent-config.sh`, MERGED via `config-merge-dir` (not `config-output-dir`, which would overwrite instead of add) against `examples/interpreter-regressions/{Foo,Test}.scala` (can't just add these files to the FIRST pass's invocation -- both fixtures independently declare top-level `object Foo`/`object Test` with no package, a duplicate-definition error if compiled together) -- this covers the specific classes/fields this session's own regression tests touch (confirmed: registered `scala.collection.mutable.ArrayBuffer`'s `array`/`mutationCount`/`size0`); (2) `isMissingReflectionRegistration`, checking the exception's class NAME (not a real `case _: MissingReflectionRegistrationError`, which would need `org.graalvm.nativeimage` on this file's own plain-Maven compile classpath -- absent, since it's a native-image-only runtime type) added alongside `ReflectiveOperationException` in both helpers' catches. This second fix is the actually load-bearing one long-term: no amount of pre-emptive tracing can cover the truly unbounded set of real host classes/fields a third-party macro might reach through this generic path, so a graceful fallback -- not a hard crash -- is the only sound general answer. Confirmed via a real regression: retracing alone fixed the originally-found `ArrayBuffer` gap, but the very next real class reached the same way (`scala.collection.immutable.NumericRange`'s own `step` field) crashed the whole compiler outright until the catch-widening fix landed too.
    - **`cs fetch` (unlike a real build tool's own dependency management) resolves Maven `provided`-scope dependencies as excluded by default, with no CLI flag to change that** (confirmed against real `cs fetch --help`/`cs resolve --help`: neither exposes a scope/configuration override) -- NOT an interpreter bug, a `cli/ScalinoCli.scala` dependency-resolution gap. `org.typelevel:scalac-compat-annotation_3` is a `provided`-scope dependency of `org.typelevel::literally` (itself pulled in by `http4s-core` and others), needed at MACRO-EXPANSION time (an inlined reference to one of its annotation classes shows up in the retained tree literally's own macro splices), not at runtime -- so it was simply missing from the resolved classpath, and http4s's `uri"..."` literal macro failed to even TYPE (a generic dotc `"undefined: ..."` error, unrelated to macro interpretation at all) as a result. Fixed by always adding it as a ROOT `cs fetch` coordinate (`alwaysIncludedArtifacts`) -- a dependency listed explicitly as a root coordinate always resolves at ITS OWN default scope (compile) regardless of what scope it'd have as someone else's transitive dependency, so re-requesting it directly is enough to pull it in. Same "always supply this ourselves" category as the scala-native runtime libs `excludedArtifacts` already manages, just for a compile-time gap instead of a runtime duplicate-symbol one.

    Regression coverage for the interpreter-side fixes (the native-image-specific one and the `ScalinoCli.scala` dependency-resolution fix have no unit-test equivalent -- covered by the `scalino test .`-against-`~/scala/ape` verification itself) added to `examples/interpreter-regressions/{Foo,Test}.scala`, alongside the existing ones. Verified: the full real `ape` project (all `src/`+`test/` files, including every `uri"..."` literal in `test/http/RoutesTest.scala`) now compiles, links, and RUNS -- 42 tests passed. `examples/interpreter-regressions` and `examples/Hello.scala` still pass; `examples/macro-hello` still fails on the SAME pre-existing, unrelated, already-documented gap (item 9's own "still-open gap" above, `undefined: x.addOne ... at inlining`) -- confirmed unrelated to this session's changes (untouched code path) and already known-broken before this session started.

    **New still-open gap found this session (NOT a macro-interpretation bug in the sense of "interpreter gives wrong runtime value" -- happens at real, compiled, LINKED native runtime, after `scalino test .` successfully compiles and links everything), root-caused MUCH further in a same-day follow-up:** 12 of `~/scala/ape`'s 54 real tests fail with `scala.MatchError: null` inside `upickle.core.Types$TaggedWriter.write0`, reached through `db.BuildingRepo#save`'s real `upickle.default.write` call on a `domain.BuildingInput`.

    **Precise root cause, isolated via a minimal, fast (~3s) standalone repro (copying `~/scala/ape`'s real `domain/{Building,WindowCatalog,AmbienteNonRiscaldato}.scala` verbatim, no `db`/`http`/porcupine/cats-effect involved at all):** every `enum ... derives ReadWriter` in the project -- NOT specific to `BuildingInput`, NOT specific to defaulted params, NOT specific to nested case classes; the earlier hypothesis in this doc's previous revision was wrong on all three counts -- serializes ONLY its ordinal-0 case (the first one written in source) correctly; every OTHER case throws this exact `MatchError: null`. Confirmed via `TipoGenerazione.values.foreach(v => upickle.default.write(v))`: `CaldaiaStandard` (ord 0) -> `"CaldaiaStandard"`; `CaldaiaCondensazione`/`PompaDiCalore`/`Biomassa` (ord 1-3) -> `MatchError: null`, for EVERY enum tried (`ContestoUrbano`, `MetodoCalcolo`, same pattern). The earlier full-`BuildingInput` failure was really just "this object happens to reference several non-ordinal-0 enum values, and a couple of ordinal-0 ones (`raffrescamento`'s default `TipoGenerazione.PompaDiCalore`, ord 2) coincidentally never got serialized at all because upickle omits fields that equal their default value" -- a total red herring that made the failure look field-position/default-arg-related when it never was.

    **Mechanism, traced into upickle's own real source (`upickle-core`'s `Types.scala`, `upickle-implicits`' `macros.scala`):** `TaggedWriter.write0` does `val (tagKey, tagValue, w) = findWriterWithKey(v)` -- a tuple-pattern `val` binding, which throws exactly `MatchError: null` if `findWriterWithKey` returns `null` (`TaggedWriter.Node`'s own `findWriterWithKey` scans its per-case child `Leaf` writers and returns `null` if none match; `Leaf`'s own `findWriterWithKey` returns `null` on a checker miss). So: for every case beyond ordinal 0, NONE of the per-case Leaf writers' `Annotator.Checker` recognizes the real runtime value as a match. The per-case Leaf writers themselves are built by `defineEnumWriters[T0, T <: Tuple](prefix): T0 = ${ defineEnumVisitorsImpl[T0, T](...) }` (`upickle-implicits/macros.scala:555-602`) -- a REAL macro (interpreted by this project's own `Interpreter.scala`) that walks `T` (`Mirror.MirroredElemTypes`, a tuple of the enum's per-case singleton types) and, for each case, synthesizes a fresh `implicit lazy val xN: Reader/Writer[CaseType] = prefix.macroR/macroW[CaseType]` via `Symbol.newVal(Symbol.spliceOwner, ...)` + `ValDef.apply`, bundling ALL of them (`x0..xN-1` per case, plus one more, `xN`, for the whole sum type `T0`) into one `Block` whose OWN result expression is only `xN` -- the per-case `x0..xN-1` vals are meant to be picked up later via ordinary Scala 3 IMPLICIT SEARCH (from within `xN`'s own nested macro expansion, which needs `Writer[EachCase]` instances to build the `TaggedWriter.Node`), not referenced directly.

    **Ruled out, with real evidence (not just once file re-read), before landing on "not yet traced further":**
    - **Owner-chain/hygiene mismatch across the per-case `Symbol.newVal` calls** (the `[spliceOwner]`/`[newVal]` combo this session added, gated by `SCALINO_INTERP_DEBUG`, at the `"spliceOwner"`/`"newVal"` cases in `Interpreter.scala`'s `interpretStaticCall`-adjacent dispatch) -- traced against the minimal repro: `x0`, `x1`, `x2`'s owner symbols are the exact SAME identity (`System.identityHashCode` equal) within one `defineEnumVisitorsImpl` expansion, both for the Reader pass and the Writer pass. This is NOT a repeat of item 9's `x.addOne`/`LambdaLift` owner-chain bug -- that one is a hard `"undefined: ..."` compile-time crash from a genuinely wrong owner; this one compiles and links clean, and only misbehaves at runtime.
    - **A general Scala Native codegen bug with local `implicit lazy val`s in a `Block`, unrelated to macros** -- ruled out by a hand-written control (`Repro5.scala`: a `Block` defining `implicit lazy val x0`/`x1`/`x2` by hand, `x2` built via an explicit varargs call picking up `x0`/`x1`, matching `defineEnumVisitorsImpl`'s own shape) compiling and running CORRECTLY (`result.tag=MERGED:x0,x1`). So plain local-implicit-lazy-val-in-a-block genuinely works under this toolchain; something specific to the MACRO-SPLICED version is different.

    **Same-day follow-up session, MUCH deeper trace -- corrects a wrong mid-investigation guess from earlier the same day, don't trust that guess (removed above, was: "the nested `macroR[CaseType]`/`macroW[CaseType]` call itself is the divergence point").** Got upickle's REAL, actual `ReadersVersionSpecific`/`WritersVersionSpecific`/`macros.scala` source (`src-3` variants -- NOT bundled in either published sources jar, which only ship the SHARED `src/` tree; fetched instead via `gh api repos/com-lihaoyi/upickle/contents/...?ref=4.4.3`, tree listed via `gh api repos/com-lihaoyi/upickle/git/trees/4.4.3?recursive=true`). Also got DEFINITIVE ground truth by running the identical minimal repro through real, unmodified `scala-cli` (JVM Scala 3.8.4 + real upickle 4.4.3, this project's toolchain not involved at all): **all ordinals serialize correctly** -- conclusively confirms this is a genuine bug in THIS project's toolchain, not upickle itself and not a real-dotc semantic this investigation had simply misunderstood.

    Traced `getSingletonImpl` (`WritersVersionSpecific.scala`'s `macroW[T]`, singleton branch: `Annotator.Checker.Val(macros.getSingleton[T])`) down to its real body:
    ```scala
    TypeRepr.of[T] match
      case tref: TypeRef => Ref(tref.classSymbol.get.companionModule).asExpr.asInstanceOf[Expr[T]]
      case v => '{valueOf[T]}
    ```
    Added `SCALINO_INTERP_DEBUG`-gated tracing at THREE points to follow this precisely (all left in place): `[classSymbol]`/`[companionModule]` (with the scrutinee's real `getClass`, e.g. `dotty.tools.dotc.core.Types$CachedTermRef`) at the `TypeReprMethods#classSymbol`/`SymbolMethods#companionModule` interpretation sites, and `[genericTypeTest]`/`[genericTypeTest.check]` at the generic `TypeTest[TypeRepr, X]` dispatch (`typeTestClass`-based, real `cls.isInstance(v)`) used for both named-extractor patterns (`case TypeRef(prefix, name) => ...`) AND plain type-ascription binds (`case tref: TypeRef => ...`) alike.

    **Result: `getSingletonImpl` is NOT the bug.** For `T = Motivo.A.type`, the real scrutinee is confirmed a genuine `CachedTermRef` (`TermRef`/`TypeRef` are real, disjoint siblings under `NamedType` in dotc's own `Types.scala` -- confirmed by reading it directly, `TermRef` does NOT extend `TypeRef`). The `TypeRefTypeTest` check on it correctly returns `false` (`[genericTypeTest.check] ... result=false`), so the interpreter correctly falls through to `case v => '{valueOf[T]}` -- exactly matching real dotc semantics, for the FIRST enum case tried in this trace. (The earlier session's `[classSymbol]`/`[companionModule]` hits that looked like they came from THIS function were a red herring from a DIFFERENT, unrelated macro helper that happens to use the identical `Ref(...companionModule(classSymbol(...)))` shape for a legitimately different reason -- distinguishable only by its bound variable's real name, `t` there vs `tref` here, once the trace was re-read carefully.)

    **New prime suspect, not yet confirmed:** `valueOf[T]` (`scala.runtime.stdLibPatches.Predef.valueOf` / `Predef.valueOf`, real source: `inline def valueOf[T]: T = summonFrom { case given vt: ValueOf[T] => vt.value; ... }`) -- an `inline` construct depending on `summonFrom` (compile-time implicit search) resolving a compiler-SYNTHESIZED `ValueOf[T]` given instance for the singleton type `T`. Neither `summonFrom` nor `ValueOf` synthesis has any dedicated handling in `Interpreter.scala` (confirmed by grep -- zero hits for either name outside of unrelated identifiers). Two live possibilities, NEITHER checked yet: (a) real dotc's OWN typer/inliner resolves `summonFrom`+`ValueOf` synthesis BEFORE this interpreter ever sees the tree (in which case the retained tree should already be reduced to something else entirely by the time it's interpreted, and the bug is in how THAT reduced form gets handled), or (b) `-Yretain-trees` preserves the PRE-`summonFrom`-resolution form, and this interpreter's generic method-call interpretation genuinely walks into `summonFrom`'s own real body / `ValueOf`'s synthesis path, with a bug somewhere in there specific to non-first enum cases.

    **Separately, a real (but so far NON-fixing) improvement landed this session and was kept:** `matchesRuntimeType`'s lenient "unrecognized real host object -> assume `true`" fallback now also checks whether the scrutinee is a genuine dotc-internal `Type`/`Tree` (never one of this interpreter's own `InterpretedInstance`/`ModuleValue`/collection representations) and, if so, reuses the SAME real `typeTestClass`-based `cls.isInstance(v)` lookup already trusted for the named-extractor-call pattern shape, instead of blindly returning `true`. Verified SAFE (the full real `ape` project, all 38 `src/`+`test/` files, still compiles clean with it) but does NOT fix this bug -- confirmed empirically by rerunning `examples/upickle-enum-writer-bug/Main.scala` after the change, same `MatchError`s. Kept anyway since it's a real, independently-defensible correctness fix (was previously ALWAYS true for e.g. any `case tref: TypeRef => ...` against ANY unrecognized real object, not just this specific scenario) -- just not the fix for the enum-writer bug specifically. Do not assume it's related; the actual divergence is still downstream of the correctly-taken `case v => '{valueOf[T]}` branch.

    **`summonFrom`/`ValueOf[T]` suspect ALSO ruled out, same session, immediately after writing the paragraph above -- don't re-chase it either.** Checked `summonFrom`'s real vendored definition (`library/src/scala/compiletime/package.scala`): its body is a deliberate dead stub, `error("Compiler bug: summonFrom was not evaluated by the compiler")` -- real semantics come entirely from a SPECIAL CASE in `typer/Applications.scala` (`fun1.symbol == defn.Compiletime_summonFrom`), i.e. real compile-time implicit search, not a real function body ever meant to run. Wrote a direct, non-macro, non-derives control (`getIt[T](using vt: ValueOf[T]): T = vt.value`, called for `Metodo.A/B/C.type` explicitly) and ran it through the REAL `scalino run` pipeline (compiled and linked normally, no macro interpretation involved at all): **all three cases resolve correctly** (`A: A`, `B: B`, `C: C`, all three `== ` checks `true`). So real, compiled `ValueOf[EnumCase.type]`/`summonFrom` resolution is completely correct in this toolchain, for every ordinal -- the bug cannot be there. Also traced `getSingletonImpl`'s own `quoteTypeBindings` for its `'{valueOf[T]}` result across both the Reader and Writer macro passes: `type T` is bound to the CORRECT, per-case singleton type (`(Motivo.A : Motivo)` vs `(Motivo.B : Motivo)`) both times -- so the type substitution INSIDE this interpreter's own quote-evaluation environment is also correct.

    **Where this leaves it:** every layer actually checked this session -- `getSingletonImpl`'s `TypeRef`/`TermRef` runtime type test, its `valueOf[T]` type binding, real compiled `ValueOf[T]`/`summonFrom` resolution, owner-chain consistency across `defineEnumVisitorsImpl`'s per-case vals, and plain local-implicit-lazy-val-in-a-Block codegen -- is CORRECT. The bug must be in something not yet directly observed: most likely candidate now is the SPLICER's own handling of a quote's TYPE ARGUMENT when a `Expr[T]` (built by nested-macro-call interpretation, e.g. `getSingleton[T]`'s result) gets REIFIED into a real tree and spliced back into the enclosing program as literal source -- i.e. whether the tree that actually lands in the compiled program says `valueOf[Motivo.B.type]` (correct) or ends up with an unsubstituted/widened/wrong type argument that only happens to still resolve correctly for the FIRST case tried (e.g. if implicit search for a too-widely-typed argument still coincidentally finds `Motivo.A`'s own `ValueOf` first via search order, but fails or finds the wrong thing for any other case). This is a DIFFERENT area than anything instrumented so far (`Splicer.scala`'s own tree-reification path for a returned `Expr[T]`'s type argument specifically, not `Interpreter.scala`'s environment-level type bindings, which are confirmed fine). Getting the ACTUAL FINAL SPLICED SOURCE TEXT for one of the per-case vals (not just individual sub-expression traces) -- e.g. by dumping the fully-expanded tree right before it's handed to the next real compiler phase -- would settle this directly instead of continuing to infer it from fragments.

    **The `Splicer.scala` type-argument-reification suspect from the paragraph above was ALSO checked and is ALSO fine -- and checking it produced the most important correction yet, a wrong mental model of WHICH CODE ACTUALLY GETS INTERPRETED BY THIS PROJECT'S OWN INTERPRETER at all.** Added one more `SCALINO_INTERP_DEBUG` print, right where a quote `'{...}`'s type bindings get substituted into its body before wrapping as an `ExprImpl` (`Splicer.scala`, the `Apply(Select(Quote(body, _), nme.apply), _)` case, after `body3` is built): confirms `getSingletonImpl`'s returned `'{valueOf[T]}` DOES reify correctly per case -- `body3.show=valueOf[Motivo.A]` / `valueOf[Motivo.B]`, `body3.tpe` the correct singleton type, both times. So the ACTUAL SPLICED TREE going out of `getSingletonImpl` is right, for every case checked.

    Given every one of `getSingletonImpl`'s own layers is now confirmed correct, went looking for where its CALLER (`macroW[T]`'s `isSingleton` branch, building `Checker.Val(macros.getSingleton[T])`) actually gets interpreted -- and found something that reframes the whole investigation: **`defineEnumVisitorsImpl` (`upickle-implicits/macros.scala:555-602`, this project's `Interpreter.scala` walking it) never itself interprets the `macroW[CaseType]`/`macroW[T0]` calls it builds.** Its own `handleType` constructs each one via raw `quotes.reflect` tree-builder calls (`Symbol.newVal` + `TypeApply(Select(prefix.asTerm, ...methodMember("macroW")...), ...)`), and the WHOLE THING is returned as an inert `Tree` value (`Block(allDefs, Ident(allDefs.head._2.termRef))`) -- at no point does `defineEnumVisitorsImpl` itself call `interpretTree` on any of those constructed calls. Confirmed empirically: grepping the ENTIRE debug trace for the literal text `macroW` finds exactly 3 hits, all the STRING literal `"macroW"` passed as `defineEnumWriters`'s own `macroX` argument -- zero actual `macroW[...]` Apply/Block interpretation trace lines anywhere, for EITHER the per-case calls or the whole-sum-type one.

    So where did the extensively-traced `getSingletonImpl` interpretation actually come from, if not from this project's interpreter walking into `macroW[T]`'s body directly? The likely answer: once `defineEnumVisitorsImpl`'s returned Block is spliced back into the real program, `macroW[CaseType]` (still `inline`) gets expanded by REAL DOTC'S OWN INLINER as a completely ordinary part of normal compilation of the now-real source -- NOT by this project's `SpliceInterpreter` -- and real dotc's Inliner only hands control BACK to this project's interpreter at the next genuine `${...}` splice boundary it encounters while expanding (`macros.getSingleton[T]`, `macros.tagKey[T]`, etc. -- each its own fresh top-level splice). Under this model, `inline m match { case _: ProductOf[T] => ...; case _: SumOf[T] => ... }`'s branch selection (correctly landing on `ProductOf` for a payload-less singleton case like `Motivo.A.type`, and presumably `SumOf` for the whole enum `Motivo`) is real dotc's own, well-tested inline-match reduction -- not this project's interpreter at all, and `compiletime.summonAll[Tuple.Map[MirroredElemTypes, Writer]]` (the whole-enum branch's own mechanism for collecting all the per-case writers into `TaggedWriter.Node`) is ALSO real dotc's own special-cased Inliner logic (`Inlines.scala:575`, confirmed by reading it directly), needing REAL, ordinary Scala 3 implicit search to find `x0`/`x1`/... in scope -- not anything this interpreter reimplements.

    **This relocates the entire remaining mystery outside `Interpreter.scala`/`Splicer.scala` and this session's own debugging tools.** If real dotc's implicit search, run against the SPLICED-IN local `implicit lazy val x0`/`x1`/... (each one's `Symbol` built via this interpreter's own `Symbol.newVal`, not written by a human), fails to find all of them -- finding only `x0` (ordinal 0) and silently treating the rest as absent, rather than erroring outright -- `TaggedWriter.Node(writers: _*)` would end up built from an INCOMPLETE writers list, `findWriterWithKey` would have nothing to try beyond the first case's `Leaf`, and `write0` would throw exactly the observed `MatchError: null` for every value beyond the first -- matching the symptom precisely. This is a genuine implicit-search/symbol-visibility question about SPLICED, interpreter-constructed `Symbol`s specifically, not a macro-interpretation-correctness question -- outside the scope of `SCALINO_INTERP_DEBUG` tracing (which only instruments interpretation, and this code path was JUST shown to bypass interpretation almost entirely). NOT verified directly this session -- ran out of a clean way to observe real dotc's own implicit search from outside.

    **The implicit-search-sibling-visibility hypothesis from the paragraph above was directly tested (new session, same investigation) via a from-scratch, fully faithful ISOLATED REPRO -- and is DEFINITIVELY REFUTED. Every layer works correctly; the bug remains unreproduced outside the real project.** Built (`<scratchpad>/implicit-visibility-repro/Repro8..12*.scala`, not committed -- scratch-only) an INDEPENDENT toy typeclass (`W[T]`/`Helper.macroW[T]`/`Checker`/`Leaf`/`Node`) that mirrors upickle's REAL mechanism line-for-line, confirmed against freshly-fetched real upickle 4.4.3 source (`Writers.scala:16-76`, `MacroImplicits.scala`, `Types.scala:194-300`) rather than from memory:
    - `Repro10`: exactly replicates `defineEnumVisitorsImpl`'s own `handleType`/`getDefs` `AppliedType`-destructuring + `Symbol.newVal`-built `Block` of per-case `implicit lazy val x0..xN`, PLUS a real `inline m match { case _: Mirror.ProductOf[T] => ...; case _: Mirror.SumOf[T] => compiletime.summonAll[Tuple.Map[m.MirroredElemTypes, W]] }` for a REAL 3-case enum (`enum Color { case Red, Green, Blue }`) -- i.e. the exact mechanism the previous paragraph flagged as unverified. Compiled clean and printed `sum:Red,Green,Blue len=3` -- summonAll finds ALL THREE locally-spliced siblings correctly, refuting the "implicit search doesn't see interpreter-constructed local symbols" hypothesis outright.
    - `Repro11`: extends this with upickle's REAL matching mechanism too (`Checker.Val(v0) if v0 == v`, `Node` throwing `MatchError(null)` when no child leaf matches -- the literal shape of `TaggedWriter.Leaf`/`Node`/`findWriterWithKey`/`write0`, `Types.scala:194-221`). Writing all three real `Color` values (`Red`/`Green`/`Blue`) through the derived writer succeeds for EVERY case, no `MatchError` -- the checker-matching mechanism itself is also clean.
    - `Repro12`: tests one more previously-unconsidered angle -- upickle's REAL `derives ReadWriter` actually calls `ReadWriter.join(macroRAll[T], macroWAll[T])` (`MacroImplicits.scala:11-14`), i.e. TWO SEPARATE `defineEnumVisitorsImpl`-shaped macro expansions (one per Reader, one per Writer) run within ONE overall inline expansion, each independently building a Block of `x0..xN`-NAMED local implicits (same names, reused). Ran two independent `defineEnumWriters` calls back-to-back in one inline method (`derivedRW[T]`) -- both come back correct for all three cases, no cross-contamination between the two Blocks' same-named symbols.
    - Also tested `Repro11` compiled through the REAL scala-native pipeline end to end (`-Xplugin:nscplugin` -> real NIR -> `LinkDriver` -> a real native executable, not just the JVM fast loop) -- identical correct result (`Red -> ...`, `Green -> ...`, `Blue -> ...`, no `MatchError`), ruling out an NIR-codegen-specific angle too.

    **Net result: the ENTIRE general derivation mechanism -- Block/Symbol.newVal splicing, real-dotc-Inliner-driven `macroW[T]` re-entry post-splice, `Mirror.SumOf`+`compiletime.summonAll` finding locally-spliced same-block siblings, `getSingleton[T]`'s `valueOf[T]` fallback, `Checker.Val` runtime matching, dual Reader+Writer derivation in one expansion, and real NIR codegen -- is now independently verified correct, isolated from upickle entirely.** Combined with everything ruled out in earlier sessions (owner-chain/hygiene, `TypeRef`/`TermRef` runtime type test, quote type-binding substitution, `Splicer.scala`'s own tree reification, plain local-lazy-val-in-Block codegen, `defineEnumVisitorsImpl` never itself interpreting the calls it builds), there is no remaining layer of the GENERAL mechanism left to blame -- every piece that can be isolated from upickle's own specific code has been isolated and shown correct.

    **How to continue:** stop building further isolated toy repros of the general mechanism -- that avenue is exhausted. The bug must be triggered by something specific to REAL upickle's own code that these repros deliberately simplified away: most likely candidates, in priority order: (1) the REAL `SingletonWriter[T]`/`CaseClassWriter[T]`/`annotate[T]`/`isMemberOfSealedHierarchy[T]`/`tagKey`/`tagName`/`shortTagName` machinery (`Writers.scala:19-61`, `CaseClassReadWriters.scala`) -- none of which this session's repros replicated at all (they used a trivial `W.leaf`/`W.node` stand-in instead); (2) the REAL `ObjVisitor`/`Visitor` JSON-writing layer that `write0` actually drives, vs. this session's simplified `def write(v: Any): String`; (3) something specific to the REAL `Building`/`WindowCatalog`/`AmbienteNonRiscaldato` enums or their surrounding file structure that isn't just "a plain multi-case enum" (worth re-diffing against a fresh minimal enum one more time to be sure). The most direct next step is almost certainly to STOP guessing/replicating and instead add fresh, targeted `SCALINO_INTERP_DEBUG` instrumentation directly to a real run of `examples/upickle-enum-writer-bug/Main.scala` (via the JVM fast loop against the real resolved upickle classpath, `.build-work/deps-cache/deps-com_lihaoyi__upickle__4_4_3.cp`), tracing the REAL `Checker.Val`'s captured value's identity/toString at CONSTRUCTION time for each of the real writers, to see directly whether the real run shows the same clean behavior this session's repros did (in which case the bug is even further downstream, e.g. in `ObjVisitor`) or actually diverges somewhere this session's simplified stand-ins didn't cover. All `SCALINO_INTERP_DEBUG`-gated tracing added across this investigation (`[spliceOwner]`, `[newVal]`, `[classSymbol]`, `[companionModule]`, `[genericTypeTest]`/`[genericTypeTest.check]`, `[quoteBody3]`) remains in place in `Interpreter.scala`/`Splicer.scala` as useful context.

    **Small, genuine, independently-useful interpreter gap found+fixed as a side effect of building these repros (commit pending):** `quotes.reflect.Symbol.requiredMethod(path: String): Symbol` (the real API, `Quotes.scala:3844`, distinct from the per-symbol `Symbol#requiredMethod(name)` used internally elsewhere in this file) had no intrinsic at all -- any macro calling it directly (not just via `.methodMember(name).head`) hit "no built-in intrinsic exists for it". Fixed with a one-line addition mirroring the adjacent `requiredClass`/`requiredModule` cases (`Interpreter.scala`, `SymbolModule` dispatch), delegating to the same real `dotty.tools.dotc.core.Symbols.requiredMethod` used by `requiredClass`/`requiredModule`. Not the enum-writer bug, but real and worth keeping -- upickle's own code doesn't happen to call this particular API shape, but other macros might.

11. **`scalino test .` against `~/scala/ape` still fails (2026-09-06): porcupine's `sql"..."` string-interpolator macro (`com.armanbilge::porcupine`, used by `db/Schema.scala`/`DraftRepo.scala`/`BuildingRepo.scala`) hits `resolveExternalDefTree`'s `notInterpretable` fallback -- "no built-in intrinsic exists for it (... this own-implementation interpreter does not yet unpickle cross-module TASTy bodies ...)" for `method inline$sqlImpl in package porcupine`. One real bug found+fixed as part of chasing this; a second, deeper one found but NOT fixed.**

    **Bug 1 (fixed): `resolveExternalDefTree` searched the TASTy name table by the symbol's *displayed* name, which is wrong for any derived name.** `sym.name.toString` for `inline$sqlImpl` renders dotc's own `NameKinds.InlineAccessorName` (a `PrefixNameKind` wrapping the real underlying name `sqlImpl` with an `"inline$"` prefix, `NameKinds.scala:389`) -- but `DottyUnpickler#mightContain` (`core/tasty/DottyUnpickler.scala:99-106`) only indexes plain `SimpleName`s from the pickled name table (`unpickler.nameAtRef.contents.toArray.collect { case name: SimpleName => name.toString }`), so a derived name's flattened `toString` was never one of the entries it binary-searches -- confirmed directly by instrumenting `resolveExternalDefTree` (temporary `SCALINO_TRACE_RESOLVE`-gated `System.err.println`, removed before landing) and seeing `root.isEmpty=true` for `rootTreeContaining("inline$sqlImpl")` even though the class (`sql$package$`) and its containing TASTy both genuinely exist. Same root cause would silently break resolution of ANY compiler-synthesized derived name reached this way: `foo$default$1` default getters, `super$foo` superaccessors, etc. Fixed generically with `rootTreeSearchName`, which unwraps a `DerivedName` down to its innermost real `SimpleName` before calling `rootTreeContaining` (note: `Name#toSimpleName` looked like the obvious existing helper for this but is a trap -- for a `DerivedName` it's defined as `termName(toString)`, i.e. it re-flattens the DISPLAYED name into a new `SimpleName`, the exact wrong thing; had to walk `DerivedName#underlying` by hand instead). This only fixes the fast-path *hint* -- `findDefTree`'s actual lookup already matched by exact symbol identity, so correctness was never in question, only whether the tree got loaded at all.

    **Bug 2 (found, NOT fixed -- root cause identified precisely, fix would need to patch `core/Symbols.scala`, outside this project's current single-file-overlay patch mechanism):** even with bug 1 fixed, `top.asClass.rootTreeContaining("sqlImpl")` (the correct, unwrapped name) *still* returns `EmptyTree` for `sql$package$`. Traced (same temporary-tracing approach, added to `core/Symbols.scala` and `core/tasty/DottyUnpickler.scala`, both reverted before landing -- see `03a-patch-compiler.sh`'s own file list if reopening this) to: `ClassSymbol#rootTreeContaining` (`core/Symbols.scala:470-486`) has TWO separate branches depending on whether `myTree` is still a lazy `tpd.TreeProvider` or has already been forced into a concrete `Tree` by some EARLIER caller. For `sql$package$`, by the time our interpreter asks, `myTree` is *already* a concrete, in-memory `EmptyTree` (confirmed: `tree.getClass=Trees$EmptyTree`, `tree.isEmpty=true`) -- something else, earlier in the same compilation, already called `rootTreeContaining` (possibly with `id=""`, i.e. a plain `.rootTree` call, which unconditionally forces materialization) and got back nothing, permanently caching that empty result on the `ClassSymbol` (`myTree = tree` at `Symbols.scala:479`, unconditional, no re-check later). Two things not yet determined: (a) *who* forces this first, premature `rootTree` call on `sql$package$` before our own macro-expansion query ever runs (candidates: the scala-native backend/nscplugin's own whole-classpath NIR reachability pass, since it's the one other consumer that would touch a dependency's module class this early and this broadly; not yet confirmed), and (b) *why* `TreeUnpickler#unpickle(UnpickleMode.TopLevel)` (`core/tasty/TreeUnpickler.scala:140-148`, `rdr.readTopLevel()`) genuinely returns `Nil` for this specific TASTy file/mode/phase combination rather than the real tree -- not yet traced into `readTopLevel()` itself. Whoever picks this up next should start there: instrument `TreeReader#readTopLevel()` directly (not `resolveExternalDefTree`/`Symbols.scala`, both already fully diagnosed) to see whether it throws-and-swallows, or genuinely reads zero top-level entries, and get a real stack trace for the FIRST (poisoning) call to `sql$package$.rootTree` to identify the actual caller.

    Verified the bug-1 fix alone doesn't regress anything: full `scalino-dotc` rebuild (native-image, not just the JVM fast loop), `dist/scalino run` on a plain Hello World still compiles/links/runs correctly. Not yet re-verified against the full existing `examples/interpreter-regressions`/`examples/upickle-enum-writer-bug` suite from item 9/10 above -- should be done before further interpreter work, in case the derived-name fix interacts with anything there.

12. **The upickle enum-writer `MatchError: null` bug (items 9/10, `~/scala/ape`'s live `/calcola` endpoint hits this in production) -- major new diagnostic progress (2026-09-06), still NOT fixed. This is the single highest-value thing to pick up next; read this whole entry before touching it again, it corrects several assumptions items 9/10 made in good faith.**

    **New technique that should have been used from the start: patch the REAL upickle jars with actual runtime instrumentation, not just interpreter-side `SCALINO_INTERP_DEBUG` tracing.** Fetched upickle 4.4.3's real source for the relevant files via `gh api repos/com-lihaoyi/upickle/contents/<path>?ref=4.4.3` (paths: `upickle/core/src/upickle/core/Types.scala`, `upickle/implicits/src-3/upickle/implicits/{Writers,macros}.scala`, `upickle/implicits/src-3/upickle/implicits/MacroImplicits.scala`), added `System.err.println`/stack-trace instrumentation gated by `SCALINO_TRACE_UPICKLE=1`, recompiled each file standalone with `dist/scalino-dotc` (plugin+`-Yretain-trees`, matching real build flags exactly -- a plain JVM-mode `dotty.tools.dotc.Main` invocation WITHOUT those flags gives materially different results, see below), and spliced the resulting `.class`/`.nir` back into a **copy** of the real published jar (`jar uf`) -- crucially, the temp source file must be named to match the ORIGINAL source file (`macros.scala`, not e.g. `upickle-macros-patched.scala`), because dotc derives package-object synthetic class names (`<filename>$package`) from the source filename, and a mismatched name produces classes that don't override anything. Point `scalino`'s dependency classpath at the patched jar (excluding the original) instead of the real one for the one-off diagnostic run. This is a generally reusable technique for debugging "the bug is in a real published dependency's own internals, not obviously in our interpreter" cases -- much more direct than guessing from toy repros.

    **First real finding: running the same `-Xprint:inlining` dump used in items 9/10, but via `dotty.tools.dotc.Main` WITHOUT `-Xplugin:.../-Xplugin-require:scalanative -Yretain-trees` (the flags `scalino run` always passes), gives a MATERIALLY DIFFERENT and WRONG picture** -- it shows a hard compile error, `Interpreter (own implementation) does not support calling method inline$defineEnumVisitorsImpl in package upickle.implicits.macros: ... does not yet unpickle cross-module TASTy bodies` (the SAME derived-name/`inline$` class of gap as item 11's porcupine bug -- unsurprising, `defineEnumWriters`/`defineEnumReaders` are themselves `inline` package-object members). Passing the exact real flags makes this error disappear entirely (0 errors), matching `scalino run`'s own already-observed successful compile. **Takeaway: never draw conclusions about this toolchain's behavior from an ad hoc JVM-mode dotc invocation unless it passes the exact same flags `cli/ScalinoCli.scala`'s `buildBinary`/`compileToClasses` actually use** (`-Xplugin:<plugin>`, `-Xplugin-require:scalanative`, `-Yretain-trees`) -- items 9/10's various JVM-fast-loop reproductions may have been silently exercising a different code path than the real `scalino run`/`scalino test` pipeline for this exact reason, without anyone (including this session, initially) checking.

    **Second, and the actually new, load-bearing finding, obtained with the real flags:** confirmed via direct runtime tracing (`Leaf.findWriterWithKey`, patched into the real `upickle.core.Types` and spliced into a copy of `upickle-core_native0.5_3-4.4.3.jar`) that for every enum tested (`TipoGenerazione`, `ContestoUrbano`), **only ONE `TaggedWriter.Leaf` -- always the one for the ordinal-0 case -- is ever queried, for every write attempt regardless of the real value's actual ordinal.** `Leaf#findWriterWithKey`'s own `checker match { case Annotator.Checker.Val(v0) if v0 == v => ...}` logic is completely correct and was directly proven so at runtime (`v0==v=true` for the matching case, `v0==v=false` with CORRECT, DISTINCT `hashCode`s for every non-matching one) -- **this DEFINITIVELY REFUTES any remaining suspicion of an equals/hashCode/object-identity bug for enum singletons under this toolchain** (also independently reconfirmed via a bespoke Scala Native `enum Color` test: `direct.eq(viaValues)`/`==`/`ValueOf`-derived identity all correct for a non-zero ordinal, `/tmp/enum-identity-check` if useful as a future regression -- not committed, scratch only).

    **The single leaf is never wrapped in a multi-case `TaggedWriter.Node` at all -- confirmed by ALSO patching `TaggedWriter.Node`'s own constructor to unconditionally print/stack-trace on construction, and by patching `Writer.merge` (`Types.scala`'s `object Writer { def merge[T](writers: Writer[_ <: T]*) = ... }`, the ONLY call site that constructs a `TaggedWriter.Node` for the write side) the same way: NEITHER ever fires, for either enum, across every ordinal tried.** Yet `-Xprint:inlining` (with the correct real flags) shows the compiled program's actual source DOES contain a `Writer.merge[domain.TipoGenerazione](writers*)` call, fed by a `Tuple4.apply(x0, x1, x2, x3).productIterator.toList` built from four distinct, individually-correct per-case `implicit lazy val`s (each one's own `SingletonWriter`/`annotate`/`Checker.Val(...)` construction independently verified correct and case-specific by direct tree inspection, exactly as items 9/10 already established for the toy repros -- this is now confirmed for the REAL project's REAL enums too, not just repros). **This is a genuine contradiction that hasn't been resolved: code that's demonstrably present in the compiled, linked binary's own source text is never executed at runtime.** The most likely explanation not yet ruled out: `defineEnumVisitorsImpl`'s constructed `macroW[TipoGenerazione]` call (`macros.scala:579-582`, built via raw `quotes.reflect` `Symbol`/`TypeApply` construction rather than ordinary source text -- `TypeApply(Select(prefix.asTerm, prefix.asTerm.tpe.typeSymbol.methodMember("macroW").head), ...)`) resolves to a DIFFERENT overload/instance of `macroW` than the "real" one whose SumOf-branch source is what actually shows up in the `-Xprint:inlining` dump -- i.e. the dump may be showing macroW's SumOf branch as WRITTEN, while a DIFFERENT invocation (with a mis-resolved `Mirror.Of[TipoGenerazione]`, or a different overload picked up via `methodMember(...).head`'s ordering) is what ACTUALLY executes and produces the single-Leaf writer that gets used. This has NOT been directly observed yet, only inferred by elimination.

    **Ruled out this session, don't re-test:** (1) `defineEnumVisitorsImpl`'s own statement-ordering bug (`macros.scala:600`, `val allDefs = topTraitDefs.toList ::: subTypeDefs` -- puts the whole-type `xN` val textually BEFORE the per-case `x0..x(N-1)` ones it depends on, due to `getDefs`'s `:::`-prepending recursion reversing order) -- looked like a highly plausible root cause (a genuine, real, un-obfuscated bug in upickle's own source, independent of anything this project changed), but patching `macros.scala` to reverse the statement order back to natural (`Block(allDefs.reverse.map(_._1), ...)`, spliced the same way) made **zero observable difference** -- same single-leaf, same failure, confirming `Writer.merge`'s block isn't even where the actually-used writer comes from, per the finding above. (2) Enum singleton `equals`/`hashCode`/reference-identity under scala-native, including through `ValueOf[T]` -- directly tested, all correct (see above). (3) The interpreter's own tree/quote reification for `getSingletonImpl`/`Checker.Val`'s per-case captured value -- already exhaustively verified correct in items 9/10 AND reconfirmed via direct `-Xprint:inlining` inspection of the REAL project's REAL enums this session (every per-case `SingletonWriter(..., Checker.Val(ev.value))` construction is textually distinct and correct per case).

    **How to continue:** the next concrete step is to trace, at the POINT `ReadWriter.join`'s `case (r1: TaggedReader[T], w1: TaggedWriter[T]) => ...` pattern match runs (`Types.scala:52-58`), exactly what `w1`'s runtime class is (`w1.getClass`) -- if it's `Types$TaggedWriter$Leaf` directly (not `$Node`), that confirms `macroWAll[T]`'s result really is a bare Leaf, and the investigation should move to instrumenting `methodMember("macroW")` resolution (or adding a print inside `macroW`/`macroWAll` themselves, which requires patching `Writers.scala` the same way this session patched `Types.scala`/`macros.scala` -- same technique, not yet applied to this specific file) to see which branch/overload actually runs when called from within `defineEnumVisitorsImpl`'s constructed tree specifically, versus when called for an ordinary per-case type. All three patched-jar diagnostic files from this session (`Types.scala`, `macros.scala`, plus the unpatched fetched `Writers.scala`/`MacroImplicits.scala`/`CaseClassReadWriters.scala`) are NOT committed anywhere (pure `/tmp` scratch, gone after reboot) -- re-fetch via the `gh api` commands above if resuming this.

    **Same-day continuation (2026-09-06, round 2). The "how to continue" step above was executed and confirmed; a real, adjacent interpreter bug was found+fixed as a side effect; the core mystery is still open, but is now narrowed further and the wrong diagnostic tool for it has been identified.**

    **`w0.getClass` (renamed from `w1` in the join match after re-reading -- it's `w0`, the method's own `implicit w0: Writer[T]` parameter, not a pattern-bound `w1`) confirmed to be `class upickle.core.Types$TaggedWriter$Leaf`, exactly as suspected.** `macroWAll[TipoGenerazione]`'s result genuinely is a bare `Leaf`, not a `Node` -- the prior entry's central contradiction (compiled-and-present `Writer.merge` code that never runs) stands confirmed, not just inferred.

    **Attempted the natural next step -- add `System.err.println`-based compile-time tracing directly to upickle's real `isSingletonImpl` (`macros.scala:541-543`, gated by an env var, to see whether `isSingleton[TipoGenerazione]` itself -- the WHOLE enum type, not a per-case type -- ever gets checked, which would prove `macroW[TipoGenerazione]` mistakenly took its `ProductOf`/singleton branch instead of `SumOf`) -- and immediately hit a second, real, previously-unknown interpreter bug that had nothing to do with upickle:** `System.err`/`System.out` field access had NO intrinsic at all (`Interpreter (own implementation) does not support calling value err in object System`), a hard `StopInterpretation`. Worse, when this failure happens while producing an `Expr[Boolean]` that feeds an `inline if` (exactly `isSingletonImpl`'s own shape, `Writers.scala:45`'s `inline if macros.isSingleton[T] then ... else ...`), the caller doesn't see the real underlying error at all -- it sees a much more confusing, seemingly-unrelated cascading failure: `Cannot reduce 'inline if' because its condition is not a constant value: ??? :Boolean`. **Reproduced cleanly in isolation** (`/tmp/expr-fold-repro/{Macros,Repro}.scala`, two real files -- a macro can't be defined and called from the same source file -- not committed, scratch only, small enough to recreate by hand if needed: an `inline def check(inline flag: Boolean): Boolean = ${ checkImpl('flag) }` whose impl does a plain `Expr(result)` works fine; the same shape with a `System.err.println` statement first hits exactly this two-layer failure). **Fixed the field-access half**: added a `case "System" => case "out"/"err" => Some(System.out)/Some(System.err)` arm to `javaStaticFieldConstant` (`Interpreter.scala`, alongside the existing `StandardCharsets`/`Character`/`File` cases), verified via the isolated repro (the "value err" error is gone) and via the full existing regression suite (`examples/{Hello,interpreter-regressions,macro-hello,upickle-enum-writer-bug}` all behave identically to before -- no regressions). **NOT fully fixed**: calling any METHOD on the returned real `PrintStream` (`println`, `print`, etc.) still hits its own, separate "no built-in intrinsic exists for it" -- unlike most other real host objects handled elsewhere in this file, `PrintStream` methods are NOT automatically covered by the generic real-host-object reflective-call fallback (why not, isn't yet known -- worth a look, since making macro-time `System.err.println` debugging actually work would make every future session's diagnostic work here much faster). Landed the field-access fix alone since it's real and independently useful (e.g. passing `System.err`/`System.out` on to something else without calling a method on it directly); the method-call gap remains open.

    **Given `System.err.println` doesn't actually work for compile-time tracing yet, switched to `quotes.reflect.report.warning(msg)` instead (already had a working intrinsic, `Interpreter.scala`'s `("error"|"errorAndAbort"|"warning") if fn.owner.name.toString == "reportModule"` case) -- and got a result that's probably a dead end, not a new lead: instrumenting the real `isSingletonImpl` this way, across compiling the ENTIRE `examples/upickle-enum-writer-bug/domain/*.scala` (a dozen-plus `derives ReadWriter` enums, each with 2-5 cases -- should be 40+ `isSingleton` checks total), only ONE `COMPILE-TRACE isSingleton[...]` warning was ever emitted for the whole compilation, and it wasn't even for `TipoGenerazione`/`ContestoUrbano` (the two enums this investigation has been focused on) -- it was for an unrelated case in a completely different enum (`AmbienteAdiacentePavimento`).** Most likely explanation: dotc's own reporter suppresses/deduplicates repeated `report.warning` calls from what it considers "the same" macro-expansion site in some way this investigation didn't control for (not yet confirmed) -- rather than anything about `isSingleton` itself only running once. **Don't waste time re-deriving this "it barely reports anything" observation as if it were meaningful -- `report.warning` is not a reliable compile-time tracing channel for a macro invoked this many times; treat this whole paragraph as a dead end, not a lead.**

    **How to continue (supersedes the "how to continue" paragraph above, which is now done):** the `methodMember("macroW")`-resolves-to-a-different-overload hypothesis from the previous paragraph is still the leading suspect and still hasn't been directly tested. Do NOT use `report.warning` for this (see immediately above) -- either (a) fix the `PrintStream` method-call gap first (find why real-host-object reflective fallback doesn't cover it, likely a quick look at whatever gate decides "attempt reflection here" in `Interpreter.scala`), which then makes `System.err.println`-based tracing Just Work everywhere and is probably worth doing regardless of this specific bug, or (b) skip real stdout/stderr entirely and have the diagnostic macro write to a fixed file path via `java.nio.file.Files.write`/`java.io.FileWriter` (check whether those already have intrinsics before assuming (b) is easier than (a)). Once real tracing works, instrument `methodMember`'s resolution itself (`Interpreter.scala:3385`, the `"methodMember" if fn.owner.name.toString == "SymbolMethods"` case -- inspected this session and its implementation looks faithful to real dotc, reusing the real `Type#allMembers` API directly rather than reimplementing it, so a genuine ordering mismatch there seems unlikely on inspection alone, but hasn't been RULED OUT by direct observation) to see, specifically for the `prefix.asTerm.tpe.typeSymbol.methodMember("macroW")` call `defineEnumVisitorsImpl` makes (`macros.scala:580`), how many candidates come back and which one `.head` actually picks, when `arg` is the WHOLE enum type (`TipoGenerazione`) versus a per-case type (`CaldaiaStandard.type`) -- the per-case ones are independently confirmed correct (their spliced trees are right), so the divergence, if it's here at all, should show up specifically for the whole-enum-type call.

    **Same-day continuation, round 3 (2026-09-06). Fixed the `PrintStream` method-call gap from round 2's "how to continue" (option (a)); used it to directly test the `methodMember` hypothesis, which is now DEFINITIVELY REFUTED; and, while re-examining the `-Xprint:inlining` dump to look for a replacement hypothesis, found and corrected a real methodological error in round 2's own "statement-ordering" analysis -- it was based on a misreading, not a real finding. Root cause STILL not found. Do not re-attempt the two things ruled out here.**

    **`PrintStream` method-call gap fixed**: added curated `java.io.PrintStream` cases to `interpretStdlibIntrinsic` (`Interpreter.scala` -- `println()`, `println(String)`, `println(Any)`, `print(String)`, `print(Any)`, `flush()`), same hand-curated-per-real-host-object pattern as the adjacent `BitSet`/`Pattern`/`Matcher` cases (confirmed while adding this: there is no generic reflective-call fallback for arbitrary real-host-object methods anywhere in this file -- `java.lang.reflect.Method`/`.invoke` isn't used at all outside the unused import at the top; genuinely every real-host-object method needs its own hand-written case). Verified via the same isolated repro from round 2 and the full regression suite (no change). `System.err.println` inside a macro body now genuinely works end to end.

    **Used the now-working tracing to instrument `methodMember`'s actual call site directly** (`macros.scala:580`'s `prefix.asTerm.tpe.typeSymbol.methodMember(macroX)`, printed `.length` and the candidate list) across the full real `examples/upickle-enum-writer-bug` compile. **Result: `candidates.length=1` for every single one of the 166 `handleType` calls captured, including specifically the whole-enum-type ones** (`name=x4 macroX=macroW arg=domain.TipoGenerazione skipTrait=false candidates.length=1 candidates=method macroW` -- and the equivalent for `macroR`/`ContestoUrbano`/every other enum in the file). **This conclusively refutes the "wrong overload picked via `methodMember(...).head`" hypothesis -- there is never more than one candidate to pick from, so `.head` can't be picking the wrong one.** Don't re-test this.

    **While re-reading the `-Xprint:inlining` dump to look for the next hypothesis, discovered round 2's own "statement-ordering bug" analysis (the `allDefs = topTraitDefs.toList ::: subTypeDefs` / `getDefs`'s `:::`-prepending-reverses-order finding, and the "patched it, zero observable difference" conclusion) rests on a misreading of the dump, not a real observation -- though the PATCH TEST's conclusion (this isn't the bug) still stands, just not for the reason originally given.** Built a minimal, fully-legible isolated repro (`/tmp/tiny-enum/Tiny.scala`, a bare 2-case `enum Color derives ReadWriter`, ~650-line `-Xprint:inlining` dump, small enough to read in full without getting lost in a 33,000-line file) specifically to settle this. **The actual printed/executed statement order for `defineEnumVisitorsImpl`'s spliced block is `x0, x1, ..., x(N-1), xN` -- per-case vals FIRST, the whole-type val (containing `Writer.merge`) LAST** -- the OPPOSITE of what round 2 reported ("xN textually before x0..x(N-1)"). Real dotc evidently reorders/emits these in dependency-safe order regardless of `allDefs`'s raw list order (topTraitDefs is prepended in the source, but that's not what ends up in the executed position) -- so round 2's patch (reversing `allDefs`) correctly produced "zero observable difference" because the *effective* order was never wrong to begin with, not because the merge-block "isn't where the used writer comes from" (that part of round 2's reasoning doesn't follow either -- it *is* where it comes from, confirmed again this round: for the 2-case repro, `x2`'s block, printed and executed LAST, is exactly the one containing `WritersVersionSpecific_this.Writer.merge[Color](writers*)`). Round 2's confusion was almost certainly from visually mis-navigating the 33,805-line full-domain dump by eye -- one enum's trailing `x0` was mistaken for a sibling of the PRECEDING enum's `x4`, when it was actually the START of the NEXT enum's OWN block. **Practical lesson for next time: use a minimal isolated repro to read an `-Xprint` dump by eye, never the full real-project dump -- it's too easy to lose track of block boundaries in 30,000+ lines of nested, similarly-shaped output.**

    **FOUND AND FIXED, same day, immediately after the paragraph above (round 4) -- this is the actual root cause. `List#:::` was implemented identically to `++` (`case (a, "++" | ":::", b) => Some(a ++ b)`, `Interpreter.scala`), but `:::`'s real contract is the OPPOSITE operand order: `a ::: b` ("adds the elements of a given list in front of this list") means `b`'s elements come FIRST, `a`'s come after -- i.e. the correct result is `b ++ a`, not `a ++ b`. This one-line conflation is the entire bug.**

    Found by switching to the workflow the user explicitly asked for -- **test via plain JVM bytecode (`dotty.tools.dotc.Main` + `-Yretain-trees`, no `-Xplugin`/no scala-native at all) instead of rebuilding the native `scalino-dotc` binary for every iteration.** Confirmed first that the bug reproduces identically on plain JVM bytecode (compiled our patched dotc against the *plain, non-`_native` published* `com.lihaoyi::upickle:4.4.3` and ran the result with plain `java` -- `cs fetch --classpath "com.lihaoyi::upickle:4.4.3"`, excluding its own `scala3-library_3`/`scala-library` in favor of this toolchain's pinned 3.8.4 ones): identical `Green FAILED: scala.MatchError: null`. This cut the edit-compile-run round trip from ~100s (native-image) to ~15s (`03a-patch-compiler.sh`'s JVM-only recompile + a `dotty.tools.dotc.Main` compile + a `java` run), and, critically, meant `javap -c` could decompile and directly read the ACTUAL bytecode dotc/our interpreter produced -- far more reliable than eyeballing a 30,000-line `-Xprint` text dump (see round 3's own misreading, above).

    `javap -c` on the tiny 2-case repro's compiled `Color$.class` showed `derived$ReadWriter$lzyINIT1` calling `x0$1`/`x0$2` (case-0's OWN per-case reader/writer) directly as the arguments to `ReadWriter$.join`, allocating-but-never-using two more `LazyRef`s that should have been `x1`'s and `x2`'s -- i.e. the compiled `derived$ReadWriter` never touches the merged `x2` (whole-type) writer/reader AT ALL; it silently substitutes the ordinal-0 case's OWN writer/reader as if it were the whole type's. Separately confirmed `x2`'s own compiled body (`x2$lzyINIT2$1`) is 100% correct in isolation -- it genuinely calls `x0$2`/`x1$2`, builds a `Tuple2`, and `Writer$.merge`s them into a real 2-leaf `Node` -- so the bug isn't in `x2`'s own construction, it's in what `derived$ReadWriter` actually references as "the merged instance": `Block(allDefs, Ident(allDefs.head._2.termRef))`'s `allDefs.head` was resolving to `x0` instead of `x2`.

    Added a plain `System.err.println` (now that round 3 made this actually work) right at `macros.scala`'s `val allDefs = topTraitDefs.toList ::: subTypeDefs` to print `allDefs.map(_._2.name)` directly, patched into a copy of the *plain JVM* `upickle-implicits_3-4.4.3.jar` (same jar-splicing technique as before, just targeting the non-`_native` artifact this time since we're testing via plain `java`) -- **`allDefs.head` was `x0`, not `x2`; `subTypeDefs` itself came back as `[x0, x1]` (natural order) instead of the `[x1, x0]` `getDefs`'s own `:::`-based recursion should produce.** Both observations are exactly what "`:::` computed as `a ++ b` instead of `b ++ a`" predicts: `getDefs`'s own recursive step (`defAndSymbol.toList ::: defs`) builds up `subTypeDefs` by *repeatedly appending* instead of *prepending*, ending in natural order; and the final `topTraitDefs.toList ::: subTypeDefs` puts `topTraitDefs` (the whole-type definition, meant to end up FIRST so it's `allDefs.head` -- the value spliced back as the derived instance) at the END instead, so `allDefs.head` becomes whichever per-case definition happened to be built first (always the ordinal-0 case, `x0`) -- silently, generically, for every multi-case enum in the codebase, matching the exact observed symptom (only ordinal 0 ever serializes) precisely.

    **Fix**: split the `"++" | ":::"` case into two, keeping `++` as `a ++ b` and adding `:::` as `Some(b ++ a)` (`Interpreter.scala`, `interpretStdlibIntrinsic`). Verified via the JVM fast loop (tiny 2-case repro: both `Red`/`Green` now serialize correctly) and via the full real `examples/upickle-enum-writer-bug` (all of `TipoGenerazione`'s 4 cases and `ContestoUrbano`'s 3 cases now serialize correctly, through plain JVM bytecode AND after rebuilding the real native `scalino-dotc`/running through the full `scalino run`/scala-native pipeline). Full regression suite (`examples/{Hello,interpreter-regressions,macro-hello}`) unchanged (`macro-hello` still fails on its own pre-existing, unrelated `undefined: x.addOne` gap, item 9). This closes out items 9/10/11(partially, unrelated)/12 -- **the upickle enum-writer `MatchError: null` bug that blocked `~/scala/ape`'s `/calcola` endpoint (and every other multi-case-enum JSON round-trip in this toolchain) is fixed.**

    **Why this took 4 rounds across multiple sessions to find, despite being a single line**: `:::` is one of the least-exercised list operations in typical macro code (`++`/`::`/`:+` are far more common), so this specific conflation had never been hit before upickle's own enum derivation macro happened to rely on `:::`'s specific "prepend, not append" contract to control ordering of a spliced `Block`'s result expression. Every other layer this investigation checked (equals/hashCode, `methodMember`, quote/tree construction, `Symbol.newVal`, TASTy name-table lookups from items 11/12) was, in fact, correct -- the bug was exactly one operator away from all of them, in a place none of those checks would ever exercise.

    **Where this actually leaves the investigation:** every hypothesis generated so far by reading the *compiled tree* (`-Xprint:inlining`) has checked out correct, for both a minimal 2-case repro and the real 4-case enums. The runtime fact from round 2 stands unchallenged and unexplained: `ReadWriter.join`'s `w0.getClass` is a bare `TaggedWriter$Leaf`, not `$Node`, and `Writer.merge`/`TaggedWriter.Node`'s constructor provably never run. Since the tree is right and `methodMember` is right, the remaining candidates are narrower than before: (1) something in how *this project's interpreter* reifies/splices a multi-statement `Block` with cross-referencing local `implicit lazy val`s back into real TASTy specifically differs from what real dotc's own quote-reification does for the identical shape -- not yet tested with a repro that ISOLATES splicing behavior from macro-content correctness (e.g. compare the ACTUAL TASTy bytes/symbol identities produced for `x0`'s declaration vs. `x0`'s use-site-in-`x4` reference, under this toolchain vs a real, unmodified dotc build, for the same tiny 2-case repro -- not done yet); or (2) something specific to how *this toolchain's own scala-native NIR codegen* (not the interpreter, not the typer) compiles a `lazy val` whose initializer is itself defined via a forward/self-referencing local block of sibling lazy vals -- also not yet tested directly (the general "multiple sibling lazy vals in a Block" case was tested and found fine in item 10's `Repro5`, but NOT the specific "one of them, forced first, transitively forces N siblings via `compiletime.summonAll`-resolved direct references" shape). Whoever continues this should pick ONE of these two and design a repro that isolates it from the other, rather than adding more tracing to the already-thoroughly-observed macro-expansion layer -- that layer is now exhausted of leads.

## Toward self-hosting scalino-dotc/scalino-lsp on scala-native (research only, not started)

**Nothing implemented here -- this is a scoping pass only**, for the lowest-priority standing goal ("make migrating off GraalVM native-image easier"). Today's split: `cli/ScalinoCli.scala` (~1700 lines) already self-hosts -- compiled BY `scalino-dotc`/`scalino-linkdriver` into a real scala-native binary, no GraalVM involved. `scalino-dotc` and `scalino-lsp` themselves are the opposite: unmodified dotty JVM bytecode, AOT-compiled by GraalVM native-image. "Migrating off native-image" ultimately means getting THOSE two self-hosted the same way `scalino` already is -- dotc compiling its own (and the language-server's) source down to native code directly, no GraalVM anywhere. Scale, first: the compiler is 591 `.scala` files / 222,003 lines (`vendor/scala3/compiler/src`); the language-server driver is only 13 files / 2,510 lines (`vendor/scala3/language-server/src`) -- but see below, that size difference does *not* translate into a meaningfully smaller self-hosting target, since the LSP links the identical compiler engine.

**Blocker A (most load-bearing): `MegaPhase.defines` reflects on dotc's own `MiniPhase` classes on every single compilation, unconditionally.** `transform/MegaPhase.scala:498-513` caches `cls.getDeclaredMethods` (`java.lang.reflect.Method`) per `MiniPhase` subclass to detect which mini-phases override a given callback method, walking the superclass chain (`hasRedefinedMethod`) -- this runs during pipeline construction on *every* dotc invocation, not behind any flag. Confirmed via direct read of the source (not speculation). This matters specifically for scala-native, more than it ever did for GraalVM: native-image at least lets you register known classes for reflection at build time and then `Class.forName`/`getDeclaredMethods` work normally at runtime (that's exactly how Blocker 2's `nscplugin` fix above works) -- scala-native has no such general mechanism at all, only the narrow `@EnableReflectiveInstantiation` opt-in used elsewhere in this project for test-framework discovery (see "Remaining work" item 4's `scalino test` design). `MegaPhase.defines` reflects over an open-ended, unregistered set of classes (every `MiniPhase` subtype, including any this project might add later) using the fully general `getDeclaredMethods`/`getSuperclass` API, which has no scala-native equivalent at all. This would need a real source change (e.g. each `MiniPhase` declaring which callbacks it overrides via an explicit field/annotation, checked at compile time instead of discovered via runtime reflection) before dotc's own pipeline-construction code could even run under scala-native.

**Blocker B: two more, narrower reflection call sites in dotc's own source**, both flag-gated and low-risk by comparison: `Driver.scala:93` (`-Yreporter <class>`, a rarely-used debug flag that loads a custom `Reporter` by name via `Class.forName`, only reached when that flag is explicitly passed) and `transform/CheckUnused.scala:1005` (`-Wunused`'s check for `quotes.reflect` tree-extractor parameter names, `Class.forName(s"dotty.tools.dotc.ast.Trees$$...")`, reached only under that lint flag against code using `quotes.reflect` unapply patterns). Unlike Blocker A, both could simply be stubbed out (fail loudly if the gating flag is passed, same posture this project already takes for `--compiler-plugin`) without losing any normal-compile functionality -- low effort, not architecturally hard, just noted for completeness.

**Blocker C (same category as already-solved Blocker 2, not new): `transform/MacroAnnotations.scala` reflectively invokes a macro *annotation's* compiled bytecode** (`java.lang.reflect.InvocationTargetException` imported, a real reflective `invoke` call for macro-annotation transforms) -- a different, less-common Scala 3 feature from the inline/quote macros this project's own `Interpreter.scala` already replaces. Same fundamental "closed-world AOT can't reflectively invoke arbitrary user bytecode" problem as the original Splicer bottleneck (see "Macro execution" above), just for a feature nobody has needed to unblock yet (no known real project this session used macro annotations). Would need the same own-interpreter treatment extended to this code path, or could stay unsupported (macro annotations are still an experimental/niche Scala 3 feature).

**Mitigating finding, reduces risk on one front**: scala-native's javalib already has real, tested `java.util.zip`/`java.util.jar` support (`vendor/scala-native/javalib/src/main/scala/java/util/{zip,jar}/`, including `ZipFile`/`JarFile`/`Manifest`, with real unit tests under `unit-tests/.../javalib/util/jar/`). This is exactly what dotc's own `dotty.tools.io.ZipArchive`/`Jar.scala` need to read dependency jars off the classpath -- unlike the `jrt:/` gap (Blocker 1, above), which is backend-agnostic in its *fix* (extracting `java.base` into a plain jar and using `-javabootclasspath` doesn't depend on GraalVM at all, so that fix carries over to a scala-native build unchanged), classpath-jar-reading looked like a candidate for a *second* JDK-completeness gap but turns out already covered.

**Open, unverified risk (not confirmed either way): dotc's own TASTy pickling phase uses real concurrency.** `transform/Pickler.scala` imports `scala.concurrent.Future`/`Await` (aliased `StdFuture`) plus a dotc-internal `util.concurrent.Executor`/`Future` (`util/concurrent.scala`) to overlap final TASTy/bytecode assembly with downstream phases (`useExecutor`, `backendFuture`) -- real, load-bearing parallelism in a core phase, not test-only code. scala-native does have genuine OS-thread multithreading (this project's own build logs already show its "multithreadingEnabled" auto-detection working), so this is *plausibly* portable, but nobody has verified `scala.concurrent.Future`/`ExecutionContext`'s behavior under scala-native for this specific use -- flagged as a risk requiring direct testing, not a confirmed blocker.

**No upstream precedent exists.** `vendor/scala3/project/Build.scala` has zero scala-native cross-build targets for the compiler or language-server modules -- every "native" hit in that file is unrelated (native OS distribution packaging/`libexec-native-overrides`). Unlike, e.g., scala-native's own build tool (`tools_native<v>_3`, a real self-hosting artifact documented above), dotty's own team has never attempted or published a scala-native build of dotc itself.

**Why `cli/ScalinoCli.scala` already self-hosting doesn't derisk this much.** It proves the mechanical pipeline works (`scalino-dotc` really can compile ordinary Scala 3 source to a working scala-native binary) but says nothing about the harder case: `ScalinoCli.scala` is ~1700 lines with no dependency on dotc's own reflection/concurrency-using internals above, while dotc-compiling-itself would need to compile 222,003 lines that *do* hit all three. The project's own established patching approach -- recompile one changed file, splice the result into the published jar (`docs/findings.md`, "Patching mechanism" above) -- is explicitly documented as *not* scaling past single-file, dependency-only changes; "Remaining work" item 2 already flags that anything bigger needs the real dotty/scala-native sbt bootstrap build, which doesn't exist yet for this purpose. Fixing Blocker A alone is a multi-file, non-public-API-adjacent change of exactly the kind that approach can't handle.

**Why the language-server doesn't meaningfully shrink the target.** Its own driver code (`Main.scala`/`Lsp.scala`/`DottyLanguageServer.scala`, 2,510 lines) is small and would self-host about as easily as `cli/ScalinoCli.scala` does today -- but `InteractiveDriver` underneath it still chains through `Run`/`Phases`/`MegaPhase` to do real typechecking, i.e. it depends on the exact same 222,003-line compiler engine and hits Blocker A identically. There's no "self-host just the LSP" shortcut that avoids fixing the compiler-internals reflection issue.

**Scope assessment: many months at minimum, not a quick win, correctly the lowest-priority item.** Concretely: (1) fix Blocker A (real, if bounded, source surgery to dotc's own phase-pipeline machinery -- removing a reflection dependency from code every compilation exercises), (2) fix or stub Blockers B/C, (3) verify the `scala.concurrent`-based pickling concurrency under scala-native for real, (4) get the real dotty/scala-native sbt bootstrap build (already an open, separate "Remaining work" item) building the *entire* compiler module rather than one patched file at a time, (5) solve a two-stage bootstrap this project has never needed before (`scalino-dotc` today only ever self-hosts small downstream tools, never itself -- self-hosting dotc means compiling dotc-native with dotc-JVM first, verifying it behaves correctly, then recompiling dotc-native with *itself*), and (6) absorb whatever unknown scala-native-maturity risk shows up at a codebase size (222K lines, large binary, long-running process) nobody -- not this project, not scala-native's own team -- has exercised before. No single step here is exotic, but the combination, with zero upstream precedent to derisk it, is a genuinely large, multi-month-plus undertaking with real risk of stalling on scala-native's own maturity ceiling partway through.

**Recommended smaller first step, if this is ever picked up**: don't start with the full compiler. First fix (or stub) Blockers A/B/C in isolation and confirm `scalino-dotc` (still GraalVM-built) still behaves identically after -- a correctness check that's cheap and independent of any scala-native attempt. Only after that, as a bounded feasibility smoke test, try getting `scalino-dotc` to compile a deliberately reduced subset of its own source (e.g. just the frontend -- parser/typer, no backend phases at all) into a scala-native binary, purely to answer "does dotc's own source, at meaningfully-large scale, even compile and link correctly under this toolchain's current scala-native support" before ever committing to the full 222K-line compiler.
