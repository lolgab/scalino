# Findings from the native-image spike

Background for the comments in `build/*.sh`. Scala 3 only, GraalVM
native-image approach (see decision log below).

## Decisions made

- **Packaging strategy: GraalVM native-image for now**, not (yet) full
  self-hosting (compiling dotc itself down to Scala Native). Originally
  ruled out here as "multi-year/high-risk given dotty's deep ties to the
  JVM (zinc, java.nio, reflection)" -- that one-line framing is now stale;
  see "Toward self-hosting scalino-dotc/scalino-lsp on scala-native" below,
  which walks the actual reasoning back to "many months, not multi-year"
  after real, file:line-level investigation, and "Self-hosting, in
  progress" for real fixes landed against that scoping. native-image only
  needs a JVM at *build* time, never at runtime, which remains true of the
  self-hosting approach too (a real JVM is still used at scalino's own
  build time to run dotc-on-the-JVM and code-generation tools; only the
  *shipped* `scalino-dotc`/`scalino-lsp` binaries stop needing GraalVM).
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

   **First attempt surfaced what looked like a real bug, turned out to be a test-fixture mistake, worth recording since the failure mode is confusing and could easily be mistaken for a real product bug by a future reader hitting the same shape.** A `.dotty-ide.json` with 2 project entries where one project's `dependencyClasspath` doesn't actually include a real Scala library jar (only its own compiled output, or nothing) causes `InteractiveDriver`'s `Definitions.init` to throw `MissingCoreLibraryException: Could not find package scala` while constructing *that* project's driver (`DottyLanguageServer.scala:103`, inside the `for (config <- configs)` loop) -- BUT this exception is thrown from the async `initialize` warmup thread (see item 4's fix above), which only does `ex.printStackTrace()`, not `Log(...)`, so `.scalino-lsp.log` shows *nothing* about it. Worse: `myDrivers` was already assigned `new mutable.HashMap` (empty) *before* the loop, so the loop aborting partway leaves `myDrivers` non-null-but-empty forever (the `if myDrivers == null` cache-init guard never retries). Every subsequent request then fails with a wildly misleading `java.util.NoSuchElementException: next on empty iterator` from `configFor`'s `drivers.keys.head` fallback (`DottyLanguageServer.scala:137`) -- a generic collections error miles away from the real cause. Confirmed via temporary `System.err.println` instrumentation (added, tested, then fully reverted -- confirmed byte-identical to the tracked patch afterward via a diff against `patches/scala3-0002-trim-language-server.patch`) that decode succeeded (2 configs, correct ids) and the *first* driver construction was the one throwing. Root cause was simply that this test's own hand-written fixture forgot to populate `dependencyClasspath` with the real compiler classpath (`cs`-resolved scala3-library et al.) the way real `scalino setup-ide` always does (`cli/ScalinoCli.scala:1776`, `dependencyClasspath = cc.compileCp...`) -- not a product bug.

   **Fixed anyway (2026-09-06), same session: the robustness gap this exposed is real even though this specific trigger was a test-fixture mistake** (a genuinely misconfigured project -- e.g. a hand-edited or generator-produced multi-project config missing a dependency -- could hit the identical shape for real). `drivers`' per-project `myDrivers(config) = new InteractiveDriver(settings)` (`DottyLanguageServer.scala`, inside the `for (config <- configs)` loop) is now wrapped in a `try`/`catch NonFatal`: a failing project is `Log(...)`'d with a clear "drivers: failed to construct driver for project '<id>': <exception>" message and simply excluded from `myDrivers`, instead of aborting the whole loop and leaving every project -- including correctly-configured ones -- permanently unusable. Verified with the same broken-A/healthy-B two-project fixture, on both plain JVM and the rebuilt native-image binary: `.scalino-lsp.log` now shows the real exception for project A immediately, while `documentSymbol` against project B's file succeeds normally in the same session -- one bad project no longer takes the whole server down. Patch regenerated (`patches/scala3-0002-trim-language-server.patch`) and reapplies cleanly from a fresh vendor checkout.

   **Process note, worth recording since it bit twice in one session:** regenerating this patch requires `git add -N` on `Lsp.scala` *every time* before diffing (it's a new-file addition this patch carries, so it's untracked in the vendor checkout, not just modified) -- forgetting it doesn't error, it just silently produces a diff with zero hunks for that file, and copying that incomplete diff over the tracked patch destroys the file's entire contents the next time the patch is reapplied (caught immediately here via a failed rebuild -- `source file not found: Lsp.scala` -- and recovered from the last commit's tracked copy before redoing the fix correctly). The `build/00b-setup-vendor.sh` fast-follow of `git reset --hard && git clean -fd` between attempts is exactly what makes the file untracked again each time, so this is a recurring trap for any future edit to this same patch, not a one-off mistake -- always re-run `git add -N` on `Lsp.scala` immediately before every `git diff` used to regenerate `patches/scala3-0002-trim-language-server.patch`.

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

## Self-hosting, in progress: Blocker A fixed (2026-09-06)

Picked up the "recommended smaller first step" above. Findings, in order:

**Sharper evidence on why Blocker A is real, beyond "no general reflection mechanism": `getDeclaredMethods` is not merely unsupported, it doesn't exist on scala-native's `java.lang.Class`.** `vendor/scala-native/nativelib/src/main/scala/scala/scalanative/runtime/Class.scala` (the class backing `java.lang.Class` on this target) has `getDeclaredMethods` commented out entirely (`// def getMethods(): Array[Method] = ???`) -- so `MegaPhase.defines`'s one call to it wouldn't just misbehave at runtime, it would fail to **compile** against scala-native's own javalib. `cls.getName()` and `cls.getSuperclass()` (the rest of the original `hasRedefinedMethod` recursion), by contrast, are both real, implemented methods on scala-native's `_Class` -- confirmed by reading the same file. This mattered for scoping the fix: only the one unsupported call needed replacing, not the surrounding recursion.

**Blocker B downgraded: not actually a blocker, confirmed by reading scala-native's real `Class.forName` implementation.** `vendor/scala-native/nativelib/.../runtime/Class.scala`'s companion object implements `forName` via `LinkedClassesRepository.byName.get(name).getOrElse(throw new ClassNotFoundException(name))` -- a real, clean `ClassNotFoundException` for any name the linker didn't keep, exactly matching the JVM's own contract for an unknown class. Both of `Driver.scala:93` (`-Yreporter`) and `CheckUnused.scala:1005` (`-Wunused`'s `Trees$X` lookup) already wrap their `Class.forName` calls in `catch case _: ReflectiveOperationException`/`ClassNotFoundException` respectively -- so under scala-native these two flag-gated features would simply degrade to "reporter/lint enhancement unavailable," exactly as already coded, no source change needed. Correcting the "two more... low-risk" framing above: these aren't even low-risk, they're already handled.

**Blocker C: left deliberately unsupported, not attempted.** Confirmed via full read of `MacroAnnotations.scala` that `callMacro` needs a real, dynamically-instantiated host object (`annotInstance.getClass.getClassLoader.loadClass(...)`, then a structurally-typed reflective `.transform(...)` call) for the macro annotation's own compiled bytecode -- genuinely the same class of problem the interpreter already solved for ordinary macros, but not extended to this path, and non-trivial to extend (would need `Interpreter`/`callMacro` reworked to invoke real compiled bytecode without reflection, or interpret the annotation's `transform` body the same tree-walking way ordinary macros are interpreted now). Left unsupported, matching the "could simply stay unsupported" option already on record -- macro annotations remain an experimental, rarely-used Scala 3 feature.

**Blocker A fix, implemented and verified.** Rather than a same-file "sentinel invocation" trick (invoke each phase's callback with a dummy tree and check identity of the result to infer whether it was overridden) -- considered and rejected as **unsound**: a phase's `transformIdent` might only actually transform trees matching some runtime condition a synthetic dummy `Ident` would never satisfy, so it would misreport a genuinely-overridden, side-effecting, or conditionally-transforming method as absent, silently corrupting pipeline fusion for real code paths the dummy doesn't exercise. Went with a provably-equivalent alternative instead: move the exact same computation dotc always did (`cls.getDeclaredMethods` on a `MiniPhase` subclass) from **runtime** to **build time**, using a real JVM (already required at scalino's own build time regardless, e.g. for the `jimage`/`-javabootclasspath` step) to compute it once and bake the result in as a plain, reflection-free `Map`.

- `build/tools/GenMiniPhaseOverrides.java` (new, plain Java, build-time-only): loads every class off the exact classpath `scalino-dotc`'s native-image build bakes in (`compiler.cp` + `nscplugin.cp`), finds every one assignable from `dotty.tools.dotc.transform.MegaPhase$MiniPhase`, and for each records which of MiniPhase's ~70 overridable `prepareFor*`/`transform*` methods it declares (`cls.getDeclaredMethods()` -- real JVM reflection, fine at build time) -- via `Class.forName(name, false, loader)` (no static-init side effects) over every classfile entry in those jars, best-effort (a class that fails to link off this closed classpath is skipped, not fatal -- any real MiniPhase subclass this misses fails loudly at its own lookup site later, not silently). Emits a generated `MiniPhaseOverrides.scala`.
- `build/02b-gen-megaphase-overrides.sh` (new build step, runs after `01-fetch-deps.sh`, before `03a-patch-compiler.sh`): compiles and runs the tool above, writing `.build-work/generated/MiniPhaseOverrides.scala`. Wired into `build/all.sh`.
- `patches/scala3-0003-fix-megaphase-reflection.patch` (new): `MegaPhase.scala`'s `defines`/`hasRedefinedMethod` now looks up `MiniPhaseOverrides.declaredMethodNames.getOrElse(cls.getName, /* throw */)` instead of calling `cls.getDeclaredMethods` -- `cls.getName`/`cls.getSuperclass`/`cls.eq` (all scala-native-supported) are otherwise unchanged, so this is a small, single-method diff. A missing map entry throws immediately with a clear message (rebuild-the-table) rather than defaulting to "no overrides" -- correctness-critical code fails loud, not silently wrong.
- `build/03a-patch-compiler.sh` extended to compile `MegaPhase.scala` + the generated `MiniPhaseOverrides.scala` together (alongside the existing `Interpreter.scala`/`Splicer.scala` splice) and overlay both into the patched jar -- same "single extra file, no other dependents" splice pattern already used for the macro interpreter, still no full sbt bootstrap needed. This works *specifically* because the fix only touches `MegaPhase.scala`'s own body -- none of the 83 actual `MiniPhase` subclass files (`RefChecks.scala`, `EtaReduce.scala`, etc.) needed to change at all, sidestepping the "would need the full sbt bootstrap" problem the original per-subclass-annotation designs (considered and rejected above) would have hit.

**Verified for real, still on GraalVM native-image (the "cheap correctness check" step, not a scala-native attempt yet)**: rebuilt `scalino-dotc` and `scalino-lsp` from scratch with the patch applied (`GenMiniPhaseOverrides` found exactly 83 `MiniPhase` subclasses, matching a direct `grep -rl "extends MiniPhase"` count across `compiler/src` -- a strong completeness signal) and ran real end-to-end regressions against the rebuilt binaries: `examples/Hello.scala` (plain compile+link+run, correct output), `examples/interpreter-regressions/{Test,Foo}.scala` (correct output, unchanged), and a real two-stage separate compilation of `interpreter/test-fixtures/i4515` (`Macro_1.scala` compiled first, `Test_2.scala` compiled separately against its `.tasty`, then linked and run -- clean exit, matching expected no-op success). `examples/macro-hello` still fails on its own pre-existing, unrelated `undefined: x.addOne` gap (documented above, item 9) -- confirms this is not a regression, same failure as before the patch.

**A real regression *was* caught, though -- by `build/lsp-trace-drive.py`'s full LSP session, not by the three tests above.** All three passed clean, which made the actual bug more interesting: rebuilt `scalino-lsp` with the patch and got `initialize` itself timing out/failing to parse on *every* message, immediately, with `.scalino-lsp.log` showing `java.lang.NullPointerException: null` on every incoming byte -- a regression not present on a from-scratch rebuild of the pre-patch baseline (confirmed via `git stash` on just `MegaPhase.scala`, rebuilding, and re-running the identical trace: baseline's `initialize` succeeded in 670ms). Root-caused by temporarily instrumenting `Main.scala`'s catch block with `t.printStackTrace()`: the NPE was inside `Lsp$.incomingEnvelopeCodec$lzyINIT1` -- a `VarHandle`-based lazy-val CAS guard failing on a null field, i.e. **`Lsp.scala`'s own compiled bytecode was subtly wrong**, produced by the patched dotc at LSP-build time, not a runtime protocol bug. Traced to `transform/LazyVals.scala` (itself a `MiniPhase`, responsible for lowering `lazy val` into the real bitmap+CAS-based implementation): `javap` on the real, unmodified `scala3-compiler_3.jar` confirmed `LazyVals` genuinely declares `transformStats`, but `GenMiniPhaseOverrides`'s generated table only recorded `Set("prepareForUnit", "transformDefDef", "transformTemplate", "transformValDef")` for it -- **missing `transformStats` entirely**.

**Root cause: a transcription bug in `GenMiniPhaseOverrides.java`'s own `OVERRIDABLE_NAMES` literal**, not a flaw in the build-time-computation approach itself -- the `prepareFor*` half of the list correctly included `prepareForStats`/`prepareForUnit`, but the parallel `transform*` half was missing both `transformStats` and `transformUnit`, so *every one of the 83 MiniPhase subclasses* silently lost those two callbacks from its recorded override set, system-wide -- not just `LazyVals`. `LazyVals`'s `transformStats` override (the pass that actually rewrites a statement list to install the CAS-based lazy-val machinery) was the one that happened to matter for a real, observable symptom, because `Lsp.scala` is a large `object` with many lazy vals; `Hello.scala`/`interpreter-regressions`/`i4515` apparently don't exercise a `transformStats`/`transformUnit` override strongly enough to produce a *visible* difference, which is exactly why the earlier three-test pass looked clean -- a reminder that "the tests I happened to run passed" is a weaker signal than "the generated data was checked against the real bytecode directly." **Fixed** by adding `"transformStats"` and `"transformUnit"` to the Java tool's list, regenerating `MiniPhaseOverrides.scala` (`LazyVals`'s entry now correctly includes `transformStats`), and rebuilding `scalino-dotc`+`scalino-lsp` from scratch. Full re-verification after the fix: all three non-LSP tests above still pass, and the LSP trace now matches the pre-patch baseline exactly -- `initialize` OK in 692ms, `workspace/symbol` OK, and the *same* pre-existing, unrelated failure on `hover`/`definition`/`completion`/`references`/`rename`/`documentSymbol` (a stale absolute path baked into `build/lsp-trace-fixture/.dotty-ide.json` from before this repo was renamed/moved -- `/Users/lorenzo/scala/scala-native-compiler/...` doesn't exist -- `NoSuchElementException: next on empty iterator` in `DottyLanguageServer.configFor`, nothing to do with this patch, not fixed here).

**Takeaway for anyone extending `GenMiniPhaseOverrides.java`'s method-name list in the future**: cross-check the two halves (`prepareFor*`/`transform*`) against each other for the same count, and don't trust "the tests I ran passed" as a substitute for "the generated table is complete" -- verify a sample of the generated table's entries against `javap` on the real jar directly, the way this bug was actually caught. Blocker A is fixed, verified against real compiles including the LSP, still shipping via GraalVM native-image today (the scala-native attempt itself -- actually cross-compiling `scalino-dotc` to a scala-native target -- is still the separate, not-yet-attempted next step below).

**Next step, unchanged from the recommendation above**: the bounded feasibility smoke test -- attempt compiling a deliberately reduced subset of dotc's own source (frontend only, no `backend/jvm`, no `backend/sjs`, `dotc/sbt` stubbed/excluded) directly as a scala-native target, to get real signal on the remaining open risks (the `Pickler.scala` `scala.concurrent.Future`/`Await` concurrency risk, and the general "has anyone ever compiled a codebase this size to scala-native" unknown) before committing to the full compiler. Blockers A/B/C no longer stand in the way of that attempt.

## Frontend-only smoke test: reduced dotc source set compiles clean under plain JVM dotc (2026-09-06)

Picked up the smoke test above. Real, concrete progress, done the same way as Blocker A: patch, then actually run it and check.

**Empirical confirmation the exclusion is mandatory, not optional**: `cs fetch org.scala-lang.modules:scala-asm_native0.5_3:9.9.0-scala-1` and `cs fetch org.scala-sbt:compiler-interface_native0.5_3:1.10.5` both fail with "not found" on Maven Central -- neither `backend/jvm` (needs scala-asm) nor a real zinc integration (needs `compiler-interface`/`xsbti`) can ever be resolved for a scala-native target. `backend/jvm`/`backend/sjs` (Scala.js, irrelevant here, and it transitively needs `backend/jvm`'s ASM-based types too) are therefore hard exclusions, not a smoke-test convenience.

**Every `.java` file is *also* a hard blocker, independent of any external dependency, for a reason not documented above**: this toolchain's own dotc can compile `.scala` sources to NIR (via `nscplugin`), but there is no Java-to-NIR path at all -- `javac` isn't part of this pipeline for *dotc's own* self-compile the way it is for `-javabootclasspath`'s pre-extracted `java.base.jar` (real JDK classfiles, not compiled by this toolchain). Found two real `.java` files that needed porting to Scala *only for this reason* (neither had any `xsbti` dependency): `sbt/interfaces/{IncrementalCallback,ProgressCallback}.java` -> `.scala` (kept the same package/class shape, so no call-site changes needed beyond the two new files). `backend/jvm`'s own three `.java` files (`MethodNode1`/`ClassNode1`/`LabelNode1`) and `profile/{ExternalToolHook,ExtendedThreadMxBean}.java` didn't need porting -- see below, both were excluded (JMX-based profiling has no meaning without a JVM to introspect, so `RealProfiler` was dropped outright, not ported) or already inside excluded `backend/jvm`.

**The reduced source set, arrived at empirically (found by compiling and fixing forward, not by hand-deriving a minimal file list first -- faster and more reliable than guessing)**: took *every* `.scala` file under `compiler/src`, minus `backend/jvm`, `backend/sjs`, `debug` (a self-contained expression-evaluator subsystem for debugger support, confirmed via grep to have zero references from anywhere in `dotc/`), `dotc/decompiler`, `dotc/semanticdb`, `scripting`, `transform/sjs`, `MainGenericCompiler.scala` (a separate multi-tool entry point, not part of the `Compiler`/phase pipeline), `dotc/util/ClasspathFromClassloader.scala` (dead code, confirmed unreferenced), and `dotc/sbt/{ExtractAPI,ShowAPI,APIUtils,ThunkHolder}.scala` (the part of zinc integration needing the *rest* of the real `xsbti.api.*` surface -- `sbt/ExtractDependencies.scala`/`sbt/package.scala` don't, and were kept, patched). **468 files.** Larger than the ~136K-line/~61%-of-222K estimate from the earlier cut-list research pass (that estimate hand-derived a minimal `transform/` subset via import-following; this pass just kept everything not on the hard-exclusion list, which turned out simpler and safer than trying to prune further by hand).

**Patches, beyond Blocker A/B/C**:
- `core/NameOps.scala`: `compactify`'s name-shortening hash moved off `java.security.MessageDigest` (no Scala Native port -- confirmed by direct filesystem search, zero hits anywhere in `vendor/scala-native`) onto `scala.util.hashing.MurmurHash3` (plain Scala, cross-builds fine) -- four 32-bit hashes rendered as hex via `Integer.toHexString` (not `java.util.Formatter`, also unported), giving the same 32-hex-char length MD5 produced. Gotcha hit immediately: `scala.util.hashing...` written unqualified resolved to `dotty.tools.dotc.core.StdNames.nme.scala` (a `Name` constant literally named `"scala"`, pulled in via this file's own `import nme.*`) instead of the real top-level package -- same shadowing idiom already used elsewhere in this codebase (`_root_.scala.List`), applied here too.
- `transform/MacroAnnotations.scala`: `callMacro`'s real reflective invoke (`ClassLoader#loadClass` -- confirmed absent from scala-native's `java.lang.ClassLoader` by reading the file directly, zero methods beyond `getResourceAsStream`/`getUnnamedModule`) replaced with a clean compile-time error ("macro annotations are not supported by this toolchain"), matching the "left deliberately unsupported" call already made for Blocker C. Everything else in the file (`hasMacroAnnotation`, needed unconditionally by `PostTyper`/`Inlining` even when no macro annotation is ever used) is reflection-free already and untouched.
- `core/Phases.scala`, `Compiler.scala`, `transform/CollectEntryPoints.scala`, `config/ScalaSettingsProperties.scala`, `core/SymUtils.scala`/new `core/SymExtensions.scala`: the actual `backend.jvm`/`backend.sjs` exclusion mechanics -- phase-list entries removed (`Compiler.scala`'s `backendPhases` is now `Nil`; nothing in this build emits JVM bytecode or Scala.js IR, matching what scala-native already does at runtime for user code, per the earlier-documented `GenBCode`/`VerifyError` finding), `phaseOfClass(classOf[GenBCode])`-style lookups replaced with a direct `NoPhase` (the class doesn't exist in this source set, but the answer -- "this phase never runs" -- is unconditionally correct), and `DottyBackendInterface.symExtensions` (a self-contained `Symbol` extension bag with no other `backend.jvm` dependency, used from three otherwise-unrelated core/transform files) extracted verbatim into its own file so those three callers don't need `backend.jvm` at all.
- **A second, broader Scala.js leak than the earlier cut-list research found**: `backend.sjs.JSDefinitions`/`transform.sjs.JSSymUtils` turned out to be imported not just from the Scala.js-specific transform phases already known about, but from six *ordinary* typer/core files too (`typer/{Typer,Implicits,ImportSuggestions}.scala`, `core/{TypeErasure,SymUtils,Contexts}.scala`, `core/unpickleScala2/Scala2Unpickler.scala`, `interactive/InteractiveDriver.scala`) plus two more transform files (`Mixin.scala`, `Constructors.scala`, `Memoize.scala`) -- all real special-casing for Scala.js pseudo-unions/JS types, and all but one (`SymUtils.scala`'s `isConstExprFinalVal`, called unconditionally) already gated behind `ctx.settings.scalajs.value`, which can never be true once `SJSPlatform`/`config.SJSPlatform` is excluded and `core/Contexts.scala`'s/`interactive/InteractiveDriver.scala`'s platform selection always builds a plain `JavaPlatform`. Each site's dead branch replaced with its statically-always-taken alternative (a one-line comment explaining why at each), not stubbed with a runtime check -- found by compiling and reading the real "Not Found"/"value X is not a member" errors one at a time, not by grepping for `sjs` in advance (a grep for `sjs`/`JSDefinitions`/`isJSType` *after* the fact, across the whole reduced file set, confirmed no more real references remained, only explanatory comments and one harmless block of inert generated-table data for the now-excluded classes).
- **One real near-miss, worth flagging for future patchers of this file**: `sbt/interfaces/IncrementalCallback`'s `enabled` method was initially dropped as apparently-unused (grepping for `\.enabled(` -- with parens -- found nothing outside the excluded `backend/jvm`), but `core/Contexts.scala:182`'s real call site was `local.enabled` with **no parens** (a Scala no-arg method call), which that grep pattern silently missed. Caught by the compile itself ("value enabled is not a member of ..."), not by re-reviewing the grep -- restored as a real trait method (`def enabled: Boolean = false`). Lesson: a "confirm nothing else calls this" grep for a no-arg method needs to check both call styles, or just trust the compiler's own errors over the grep.

**Verified**: `find compiler/src -name '*.scala'` filtered per the exclusion list above, plus the Blocker-A-era generated `MiniPhaseOverrides.scala`, compiled together (**one single `dotty.tools.dotc.Main` invocation, 468 files**) against a classpath with the *published* `scala3-compiler_3` jar removed (to avoid shadowing the exact classes being recompiled from source) but every other real dependency (`scala-library`, `scala3-library`, `scala3-interfaces`, `tasty-core_3`) present -- **zero errors**, only pre-existing upstream style warnings (`eq` used infix, `-feature` migration notices) unrelated to any patch here. This is still a plain-JVM-dotc compile (`java dotty.tools.dotc.Main`, not `scalino-dotc`/native-image, and not yet scala-native/NIR at all) -- it answers "does this reduced 468-file slice of dotc's own source, at real scale, typecheck and pickle correctly on an ordinary JVM" (yes), not yet "does it compile all the way to a scala-native binary" (the actual target test, next).

## Self-hosting milestone: `dotty.tools.dotc.Main` compiles, links, and RUNS as a real Scala Native binary (2026-09-06)

**This is no longer a smoke test result -- it is a working, if incomplete, self-hosted dotc.** Picked up immediately after the JVM-mode 468-file compile above, same session. Net result: `dotty.tools.dotc.Main` -- the actual, real dotc entry point, unmodified in spirit (only the fixes below) -- compiles, links with zero unreachable symbols, and **runs**, on Scala Native, no JVM anywhere. It correctly prints real dotc's own usage/help text when run with no args, and **correctly parses and typechecks a real two-line Scala source file end-to-end with exit code 0 and zero errors** (`object Hello { def main(args: Array[String]): Unit = println("hi") }`, via `-javabootclasspath dist/java.base.jar -classpath <nativelibs.cp>`). The full frontend + typer + inliner + the entire `transformPhases` list (unchanged, un-trimmed -- only `backendPhases` was ever touched) ran correctly on real code, compiled by a binary that was itself produced entirely by compiling dotc's own source through the exact same toolchain.

**What was added on top of the 468-file JVM-mode pass, in order, each verified by re-running the actual `-Xplugin:<nscplugin> -Xplugin-require:scalanative -Yretain-trees` compile + `scalino-linkdriver` link, not by inspection alone:**

1. **Two more real external-dependency gaps found, both via the same `cs fetch ..._native0.5_3` empirical check already used for scala-asm/compiler-interface**: `org.scala-lang:tasty-core_3` and `org.scala-lang:scala3-interfaces` **also have no Scala Native cross-build** (confirmed: `cs fetch org.scala-lang:tasty-core_native0.5_3:3.8.4+0.5.12` and `cs fetch org.scala-lang:scala3-interfaces_native0.5_3:3.8.4` both fail "not found"). Unlike scala-asm/compiler-interface (genuinely external, no alternative), **both of these are dotty's own modules with real source available elsewhere in the same `vendor/scala3` checkout** -- `tasty-core`'s real source lives at `vendor/scala3/tasty/src` (1,608 lines, all `.scala`, not physically inside `compiler/src` as the stray `compiler/src/dotty/tools/tasty/besteffort/` two-file subtree might suggest), and `scala3-interfaces`'s at `vendor/scala3/interfaces/src` (177 lines across 8 `.java` files). Adding `tasty-core`'s source directly to the compile (no patch needed, it's plain Scala with no problematic dependency) closed the ~30 tasty-related unreachable symbols in one step, dropping the original Blocker-A-fix-era smoke test's 77 unreachable link symbols to 24.
2. **A new, general finding, independent of any specific external dependency**: **this toolchain's own dotc has no Java-to-NIR path at all** -- `scalino-dotc` can compile `.scala` sources to NIR via `nscplugin`, but never compiles `.java` sources (there is no such phase, ever). Any `.java` file that's part of a self-hosted build -- regardless of whether it needs `xsbti` or any other external artifact -- is a hard blocker purely because it's Java, and needs porting to Scala. Found and ported **4 such files this session**: `sbt/interfaces/{IncrementalCallback,ProgressCallback}.java` (already known, Blocker-A-era) and, newly, `interfaces/src/dotty/tools/dotc/interfaces/*.java` (8 files: `AbstractFile`, `SourceFile`, `SourcePosition`, `Diagnostic`, `DiagnosticRelatedInformation`, `ReporterResult`, `SimpleReporter`, `CompilerCallback` -- the real `scala3-interfaces` embedding-API types). All ported as straight 1:1 Scala traits (same package/class names, zero call-site changes needed for imports).
3. **A real, subtle consequence of porting a Java interface to a Scala trait, found only by compiling, not foreseeable by inspection**: Scala's Java-interop leniency lets *any* override of a Java-declared abstract method use either call convention (`foo.bar` or `foo.bar()`) freely, regardless of how the Java method itself is shaped, because Java has no such distinction at the bytecode level. Once `interfaces.SourceFile.content()` etc. became **real Scala trait methods**, Scala 3's strict override/call-site parens-matching rules applied for the first time, and the *existing, already-committed* dotc codebase turned out to call these methods with **both conventions inconsistently** across different files (`source.content` in some, `source.content()` in others; `override def name: String` in some overrides, needing to match whichever convention the trait declares). Net effect: **34 real compile errors across two full iterations** (11 "must be called with () argument" sites needing parens added, then 24 "does not take parameters"/override-incompatible sites needing parens *removed* from the trait declarations themselves, then 3 more stragglers each iteration) before the whole reduced source set was internally consistent again. Resolved by matching each of the 8 ported interfaces' methods to whichever convention its *real* override sites already used (`content()` needed parens; every other method -- `name`, `path`, `jfile`, all of `SourcePosition`'s accessors, all of `Diagnostic`/`ReporterResult`'s -- needed to be nullary, no parens). **Lesson for anyone porting another Java interface in this codebase**: budget for this as a real, iterative, compile-driven fixup pass, not a one-shot mechanical translation -- there is no way to know which convention existing call sites assume without asking the compiler.
4. **A cluster of independent, narrow reflection/dynamic-loading gaps, all fixed the same way as `-Yreporter` (Blocker B) and macro annotations (Blocker C) -- graceful, compile-time "not supported" instead of a runtime reflective call**, found one at a time from real linker "Unknown method/type/constructor" output, not pre-emptively:
   - `Driver.scala`'s `-Yreporter <class>` (`Class#getDeclaredConstructor`) -- now a clean error, matching the already-established pattern (this is the *exact* Blocker B site that the earlier "already gracefully degrades via catch" analysis called safe to leave alone for a GraalVM-targeted build; **that analysis was correct for "does it crash at runtime" but wrong for "does it link at all"** -- the method doesn't exist on Scala Native's `Class` at all, so even code that would only run this path conditionally still fails to *link* unconditionally, since Scala Native's reachability analysis is static, not gated by the runtime branch. Corrected here, with the same correction applied to `transform/CheckUnused.scala`'s `-Wunused` parameter-name lookup, the other Blocker B site).
   - `plugins/Plugin.scala`'s dynamic plugin `instantiate`/`load`/`loaderFor` (`Class#getConstructor`, `ClassLoader#loadClass`, `new URLClassLoader(...)`) -- now clean errors, consistent with this project's own already-documented stance that dynamic `-Xplugin` loading needs a plugin baked into the same build as dotc (see "Blocker 2" above), not loaded reflectively at runtime.
   - `core/MacroClassLoader.scala`'s `makeMacroClassLoader` (`new URLClassLoader(...)`) -- confirmed via a full-codebase grep that its result is *only* ever passed as a constructor parameter to `Interpreter`/`Splicer.splice`, neither of which ever calls `.loadClass` on it (both are this project's own reflection-free tree-based macro execution) -- replaced with a real, already-existing classloader reference (`getClass.getClassLoader`) instead of constructing a new one.
   - `quoted/Interpreter.scala`'s `findRealField`/`reflectiveFieldRead`/`reflectiveFieldWrite` (`Class#getDeclaredField`, `java.lang.reflect.Field`) -- the interpreter's own documented real-host-object reflective escape hatch (used e.g. for `ArrayBuffer#size0` when a curated intrinsic doesn't cover a field mutation) -- now always fails gracefully, exactly as its own doc comments already described for the "field not found" case; strictly more conservative than the GraalVM build (which could actually find *registered* fields at runtime) but the correct answer given Scala Native has no registration mechanism to even attempt this against. Also removed `quoted/Interpreter.scala`'s dead `AbstractFileClassLoader` REPL-session branch (the REPL module isn't part of this reduced source set at all, and the branch's own condition, `ctx.owner.topLevelClass.name.startsWith(REPL_SESSION_LINE)`, could never be reached from a REPL-less build anyway).
   - `config/Settings.scala`'s generic `Setting[Option[T]]` reflective instantiation (`propertyClass.get.getConstructor().newInstance()`) -- its only real user across the whole codebase, `-rewrite` (`OptionSetting[Rewrites]`), becomes an unsupported flag rather than reflectively instantiated.
5. **A cluster of independent JDK-API gaps unrelated to reflection, same fix pattern (drop the branch that needs it, since each was already effectively dead code for this toolchain or gracefully degradable)**:
   - `java.lang.Runtime$Version`/`Runtime.version()` -- no Scala Native port (confirmed absent from `vendor/scala-native`). Three sites: `config/ScalaSettingsProperties.scala`'s `supportedReleaseVersions` (capped the reported release-version range at the *running* JDK's own feature version -- moot with no running JDK at all, now just reports the full known range); `classpath/DirectoryClassPath.scala`'s `JrtClassPath.apply` (used to decide between `ct.sym`-based and live `jrt:/`-based JDK classpath resolution -- moot regardless, since `jrt:/` itself doesn't exist without a real JDK install, Blocker 1 above; now always `None`, the same fallback its own caller comment already documented as "if available"); `io/ZipArchive.scala`'s `FileZipArchive.openZipFile` (the multi-release-jar-aware `JarFile` constructor overload -- now always the plain `ZipFile` constructor; a multi-release jar's `release`-specific classfile variants won't be selected, but the jar still reads).
   - `java.util.Timer`/`TimerTask` -- two independent best-effort *timeout* features, neither correctness-critical: `typer/ImportSuggestions.scala`'s per-candidate implicit-search cancellation (the outer `deadLine`/`System.currentTimeMillis` check, unaffected, remains the real timeout defense; this only drops a second, finer-grained safety net for one pathological candidate) and `Run.scala`'s periodic progress printer (gated behind `debugPrintProgress`, a hardcoded-`false` `inline val` -- this branch was already dead code, never enabled by any real build, before this session touched it at all).
   - `java.nio.file.FileStore` -- `io/PlainFile.scala`'s `underlyingSource`'s `"jar"` case, used to resolve a NIO jar-filesystem entry back to its containing jar's real path -- moot in practice, since this toolchain reads jars via its own `ZipArchive`/`java.util.zip`, never through NIO's `jar:` filesystem provider (matches the earlier JVM-coupling survey's finding that `dotty.tools.io` has zero NIO `FileSystemProvider` usage) -- was already effectively dead code, just needed to link; now `None`.
6. **The final, largest-footprint (but each-site-shallow) fix: `java.net.URL` does not exist at all on Scala Native** -- confirmed by direct filesystem search, no `URL.scala` anywhere in `vendor/scala-native`'s javalib, unlike `java.net.URI` (real, working). Unlike every other gap in this list, this isn't one or two call sites -- `dotty.tools.io`'s `ClassPath`/`Path`/`AbstractFile` abstractions bake `Seq[URL]`/`URL` into their **public API signatures** across 13 files (`ClassPath.asURLs`, `Path.toURL`, `AbstractFile.toURL`, and 9 concrete `asURLs` overrides in `classpath/*.scala`/`interactive/LogicalSourcePath.scala`). Confirmed first that nothing in this reduced source set actually *reads* an `asURLs`/`toURL` value for its real URL semantics (`core/MacroClassLoader.scala`'s `URLClassLoader` construction was the one real consumer, already removed in fix #4 above) -- every implementation already builds a `URI` internally before calling the now-missing `.toURL()` on it, so the fix was mechanical: change all 13 signatures from `URL`/`Seq[URL]` to `URI`/`Seq[URI]` and drop the trailing `.toURL()` call at each construction site. One knock-on fix needed after the signature change: `classpath/ClassPathFactory.scala`'s `classesInManifest`-adjacent code called `.toURI()` on what was (pre-fix) a `URL` to convert it *to* a `URI` for `java.nio.file.Paths.get(...)` -- now that the value already *is* a `URI`, that call became invalid (`URI` has no `.toURI()`) and was simplified to use the value directly. (A user-suggested alternative -- pulling in a third-party `java.net.URL`-providing library for Scala Native -- was considered and set aside: nothing in this reduced build needs real URL semantics for anything, so the existing-`URI` fix is strictly simpler and adds no new dependency.)

**End-to-end verification, same rigor as every fix above -- real compile, real link, real run, not inspection**: after all of the above, `-Xplugin:<nscplugin_3.8.4-0.5.12.jar> -Xplugin-require:scalanative -Yretain-trees` compiling all 484 files (468 + `tasty/src`'s 8 + `interfaces/src`'s 8) against `nativelibs.cp` (the real Scala-Native-cross-compiled stdlib, not the plain JVM one) produced **11,329 real `.nir` files, zero compile errors**; `scalino-linkdriver` against that output, entry point `dotty.tools.dotc.Main`, produced **a real, standalone 46MB native executable with zero unreachable symbols** (down from the original Blocker-A-fix-era smoke test's 77, through 24, to 2, to 0, across this session's fix passes); running it with no arguments printed dotc's real usage/help text; running it against a real two-line `Hello.scala` (with `-javabootclasspath`/`-classpath` pointed at `dist/java.base.jar`/`nativelibs.cp`) exited 0 with no errors, having genuinely parsed and typechecked the file through the complete, un-trimmed `transformPhases` pipeline.

**What this is not, to be precise about scope**: `backendPhases` is still `Nil` (see the Blocker-A-fix-era patch to `Compiler.scala` above) -- this binary parses, typechecks, inlines, and runs every transform phase through `Constructors`/`LambdaLift`/`Flatten`/etc., but **emits no output at all** (no `.class` files, no NIR, no anything) for the code it compiles, since there is no backend wired in. It is not yet a replacement for `scalino-dotc` (which, via GraalVM native-image, still does real work end-to-end: compile user Scala to NIR via the baked-in `nscplugin`). Getting from here to an actual self-hosted `scalino-dotc` replacement means wiring `nscplugin`'s own NIR-generation phases into `backendPhases` instead of leaving it empty -- a real, scoped, follow-up task, now dramatically de-risked (the entire rest of the compiler is proven to compile, link, and run) but not attempted this session. Also unsupported/degraded in this build, matching the fixes above: macro annotations (Blocker C, pre-existing), `-Yreporter`, `--compiler-plugin`/dynamic `-Xplugin` loading, `-rewrite`, `-scalajs`, the macro interpreter's real-host-object reflective field-access fallback (falls back to its own already-documented "field not found" path unconditionally now), and multi-release-jar-aware classpath resolution. None of these affect ordinary compilation of ordinary Scala code -- they were all either already-rare flags/features or graceful-degradation paths whose "doesn't work" case was already a documented, handled outcome before this session touched them.

## Wiring in the real backend: nscplugin baked in, real end-to-end pipeline works -- but a serious, confirmed non-determinism bug blocks completion (2026-09-06, same day)

Picked up exactly where the milestone above left off: get `backendPhases` (or rather, the plugin-phase mechanism `nscplugin` actually uses -- it's not `backendPhases` at all, see below) to do real work, using scala-native's own `nscplugin`, so this self-hosted binary can actually emit NIR for user code, not just typecheck it.

**How `nscplugin` actually gets its phases into the pipeline (this doesn't go through `Compiler.backendPhases` at all)**: `-Xplugin:<jar>`'s dynamic loading (`Plugin.load`/`instantiate`/`loaderFor`, all three already stubbed out earlier this session for linking, see the milestone above) is only half the story -- the actual splice point is `plugins/Plugins.scala`'s `loadRoughPluginsList` (returns `List[Plugin]`) feeding `addPluginPhases` (schedules each `StandardPlugin`'s `initialize(options)`-returned `List[PluginPhase]` into the real phase plan via `Plugins.schedule`, ordering by each phase's own `runsAfter`/`runsBefore`). `-Xplugin-require:scalanative` is *just* a presence assertion (`plugs exists (_.name == req)`) -- no other activation-behavior difference. This meant the fix wasn't "populate `backendPhases`" at all -- it was replacing `loadRoughPluginsList`'s dynamic jar-scanning body with a single hardcoded line: `List(new scala.scalanative.nscplugin.ScalaNativePlugin())` (`patches/scala3-0007-bake-in-nscplugin.patch`). No jar scanning, no `plugin.properties` parsing, no classloader -- exactly the "baked in, not dynamically loaded" posture already established for Blocker 2, just going one step further than GraalVM's version of that idea (which still uses `Class.forName` for a build-time-*registered* class; Scala Native has no registration mechanism to register it *with*, so this skips reflection entirely rather than working around its absence).

**`nscplugin`'s own real source, not a published jar, needed for the same reason `tasty-core`/`scala3-interfaces` did**: `org.scala-lang:scala3-compiler_3` is `nscplugin`'s only real dependency (`provided` scope, already satisfied since we're compiling dotc's own source in the same command) -- but the *published `nscplugin_3.8.4-0.5.12.jar` itself* has no Scala Native cross-build any more than `tasty-core`/`scala3-interfaces` did (same already-established reasoning: we need to compile it *as part of* this self-hosted dotc, not link against a prebuilt artifact of it). Its real source lives in the already-cloned `vendor/scala-native` (`build/00b-setup-vendor.sh` clones it for the *user-code* scala-native pipeline already) at `nscplugin/src/main/scala-3` (18 files, 7,141 lines) plus two more modules its own `project/Build.scala` compiles directly into it via `dependsOnSource` (not separate published artifacts): `nir/src/main/scala` (34 files, 5,821 lines) and `util/src/main/scala` (12 files, 631 lines). All plain `.scala`, no Java files, no further external dependencies -- 64 files, ~13,600 lines total, added to the source-file list alongside dotc's own 486.

**Four small compile-time fixups needed in `nscplugin`'s own source** (`patches/scala-native-0002-nscplugin-selfhost.patch`), each the same class of issue already handled elsewhere this session:
- `NirGenExpr.scala`/`NirGenName.scala` imported `dotty.tools.backend.jvm.DottyBackendInterface.symExtensions` -- the old location, before this session's Blocker-A-era extraction to `dotty.tools.dotc.core.SymExtensions`. Repointed to the new location.
- `NirPrimitives.scala` imports `dotty.tools.backend.jvm.DottyPrimitives` -- genuinely self-contained (just a big `Symbol`-to-`Int` primitive-op lookup table, confirmed by reading it: only depends on core `dotc.*` types plus one sibling, `backend.ScalaPrimitivesOps` -- itself *already* outside `backend/jvm` and already in-scope, self-contained too). Rather than patch `nscplugin`, just added this one file from `backend/jvm` to the source list directly (an explicit file list, not directory-based inclusion, so this doesn't drag in the rest of `backend/jvm`/its ASM dependency).
- `GenNIR.scala`'s `runsBefore = Set(backend.jvm.GenBCode.name)` -- `GenBCode` doesn't exist in this source set at all (same reason as always); changed to `Set.empty`, a real, correct constraint now that there's no such phase to ever run before.
- `NirCodeGen.scala`'s `caseInsensitiveNameOf` used `.toLowerCase(java.util.Locale.ENGLISH)` (a determinism-motivated choice, per its own doc comment, to avoid the OS default locale) for a case-insensitive-filesystem name-collision check -- `java.util.Locale` doesn't exist on Scala Native at all (confirmed, no `Locale.scala` anywhere in `vendor/scala-native`'s javalib). Switched to the argument-less `toLowerCase()`, itself deterministic on this toolchain since there's no real per-locale case-conversion table to vary by.
- **One new stub needed**: `nir/src/main/scala`'s own `Versions.scala` references `ScalaNativeBuildInfo.version` -- normally an sbt-buildinfo-*generated* object (`buildInfoPackage = "scala.scalanative.nir"`, overriding the generic default, confirmed in scala-native's own `project/Build.scala`), never checked into the real source tree at all. Hand-wrote a matching stub (`build/selfhost/ScalaNativeBuildInfo.scala`, package `scala.scalanative.nir`, `version`/`scalaVersion` hardcoded to match this project's own `versions.env`).

**Verified, same rigor as before**: compiling dotc's own source (550 files total: the 486 from the milestone above, plus `nscplugin`+`nir`+`util`'s 64, plus the one extra `DottyPrimitives.scala`, plus the `ScalaNativeBuildInfo` stub -- reproducible via the new `build/selfhost/gen-file-list.sh`) with `-Xplugin:<the real, unpatched, published nscplugin jar> -Xplugin-require:scalanative` (activating the plugin in the *compiling* JVM dotc, unaffected by any patch to the *source being compiled* -- these are two different processes, easy to conflate) produced **12,622 real `.nir` files, zero errors**; `scalino-linkdriver` against that output, entry point `dotty.tools.dotc.Main`, produced **a real 49MB standalone executable with zero unreachable symbols** (the only new link-time gap beyond the milestone above was `java.util.Locale`/`Locale.ENGLISH`, fixed above -- everything else linked clean on the first real attempt). **This binary then actually compiled real user code**: given `/tmp/HelloTest.scala` (`object Hello { def main(args) = println("hi") }`), it produced real `Hello.nir`/`Hello$.nir` files (confirmed via the same real dotc compile pipeline this binary itself was built by), and `scalino-linkdriver` linked *those* into a runnable executable -- the first time in this project's history that dotc-compiling-dotc-compiling-user-code has worked end to end with zero JVM/GraalVM anywhere in the chain.

**A real, confirmed non-deterministic correctness bug in this binary's own output -- root-caused and fixed (2026-09-06, later same day).** Compiling the *exact same* trivial input file (`object PrivTest { private def secret(): Int = 42; def main(args) = println(secret()) }`) repeatedly, with no changes to anything, produced **different NIR content on different runs** -- confirmed directly by `strings`-inspecting the output across 5 consecutive runs: sometimes `writeReplace`/`ModuleSerializationProxy`/`Class` (the compiler-synthesized Java-serialization support method every `object` gets) was present and `main`/`secret` were missing; sometimes the reverse; sometimes different subsets again.

Ruled out first, each by direct real test, not by inspection:
- **Not `Pickler.scala`'s `ParallelPickling`/`scala.concurrent.Future`-based concurrent TASTy assembly.** Temporarily forced `Pickler.ParallelPickling` from `true` to `false`, rebuilt the whole self-hosted binary from scratch, and the exact same non-determinism reproduced.
- **Not threading in general.** Bisected via a real JVM + the original, unpatched, published `nscplugin` jar first: compiling `PrivTest.scala` 5/5 times on plain JVM bytecode was **byte-identical every time** -- the bug is Scala-Native-specific, not a pre-existing logic bug in nscplugin/dotc. Then, on the Scala Native side, patched out the one real `Thread.start()` call site (`Pickler.scala:396`'s `executor.start()`, the only genuine `java.lang.Thread` usage in the whole pipeline, confirmed via `util/concurrent.scala`'s `Executor extends Thread`) and relinked with `withMultithreading(Some(false))` forced at the scala-native `Config` level (not just link-time auto-detection -- `src/LinkDriver.scala`'s own `--multithreading` flag only ever forces it *on*, so this needed a one-off standalone driver bypassing `Opts` entirely). Confirmed via the link log (`Linking (multithreadingEnabled=false)`, and the linker's own `SystemThreads`-unreachability check passing) that the resulting binary is genuinely, verifiably single-threaded -- and the exact same non-determinism still reproduced. This conclusively excludes threading/concurrency as a cause: the bug requires zero concurrent execution.
- **Not the typed AST or dotc's frontend/typer/transform pipeline.** Repeated `-Vprint:erasure` dumps of the same input were byte-identical across every run -- the tree entering the backend is always complete and correct. The bug is specifically inside nscplugin's own NIR-emission/file-writing logic.

**Root cause, pinpointed via targeted tracing**: added temporary `System.err.println` instrumentation to `NirGenStat.scala`'s `genMethods` and `NirCodeGen.scala`'s `genCompilationUnit` (both reverted after use, not part of the real fix) and observed that `generatedDefns` -- the `mutable.UnrolledBuffer[nir.Defn]` accumulating every member of a compilation unit -- was **always identical and correctly insertion-ordered across every run** (same 5 defns, same order, every time: the module's class header, `<init>`, `writeReplace`, `secret`, `main`). The divergence appeared immediately after, in `NirCodeGen.scala`'s own:
```scala
generatedDefns.toSeq
  .groupBy(defn => getFileFor(defn.name.top))
  .foreach(genIRFile(_, _))
```
Since all 5 defns belong to the same top-level module, `getFileFor` should map every one of them to the *same* file key, producing one group of 5 -- `groupBy` should merge them. Instead, the trace showed **up to 5 separate one-element groups**, each independently calling `genIRFile` (which opens/truncates the target file and writes just that group's defns) against the *same* physical output path -- so only whichever singleton group a `Map`'s iteration order happened to visit *last* survived on disk; every earlier group's write was silently clobbered. Which group won was different every run.

The reason `groupBy` failed to merge equal keys: `getFileFor` (`NirCodeGen.scala:171-178`) returns a `dotty.tools.io.AbstractFile`, concretely a fresh `PlainFile` built on every call (`dir.fileNamed(...)`) -- never cached/interned. `PlainFile`'s `equals`/`hashCode` (`vendor/scala3/compiler/src/dotty/tools/io/PlainFile.scala`) were implemented as:
```scala
override def hashCode(): Int = System.identityHashCode(absolutePath)
override def equals(that: Any): Boolean = that match {
  case x: PlainFile => absolutePath `eq` x.absolutePath
  case _            => false
}
```
This relies on `absolutePath` (`givenPath.toAbsolute.toString.intern`) being reference-identical (`eq`) across separately-constructed `PlainFile`s for the same path -- which only holds if `String#intern()` genuinely canonicalizes equal-content strings to one shared object, as real JVMs do. **Scala Native's `String.intern()` (`vendor/scala-native/javalib/src/main/scala/java/lang/String.scala:559`) is a no-op stub: `def intern(): _String = this`.** It never deduplicates against any pool. So two `PlainFile`s built from the identical path string end up with two distinct, non-`eq` "interned" strings -- `equals` wrongly returns `false`, and `hashCode` (`System.identityHashCode`, itself the raw heap address per Scala Native's default `Object.__hashCode()`, `nativelib/.../runtime/Object.scala`) differs between them, varying run to run with ASLR/heap-layout. This is exactly why the bug is Scala-Native-only (real JVM `intern()` makes the same code correct there) and exactly why it's independent of threading (it's a pure per-process hash/identity artifact, not a race).

**Fix, applied and verified** (`patches/scala3-0008-fix-plainfile-nondeterministic-hashcode.patch`): changed `PlainFile.hashCode`/`equals` to plain value equality on `absolutePath` (`absolutePath.hashCode()` / `absolutePath == x.absolutePath`) instead of identity/`eq`-based comparison -- correct on every backend and no longer hostage to `intern()` semantics at all. Rebuilt the full 550-file self-hosted binary from scratch with the fix, relinked with `scalino-linkdriver` under the exact original repro conditions (`multithreadingEnabled=detect`, threads allowed, matching how `full-link2`/`full-link3` were originally built), and ran `PrivTest.scala` through it **10 consecutive times: byte-identical MD5 output every time**, `secret`/`main`/`writeReplace` all consistently present. Also linked the resulting `PrivTest$.nir`/`PrivTest.nir` into a real standalone executable and ran it: prints `42`, exit 0.

**A second, separate, real bug found while broadening verification past `Hello`/`PrivTest` -- root-caused and fixed the same day.** Ran the fixed binary against `examples/interpreter-regressions/{Test,Foo}.scala` (a real macro case: `inline def myMacro(): String = ${ aMacroImplementation }`, needs `-Yretain-trees`) as a further deterministic-output sanity check. It failed, deterministically (3/3 identical), with `Caused by class java.lang.IndexOutOfBoundsException: 0 is out of bounds (min 0, max -1)` during macro interpretation. Confirmed real and self-hosting-specific: the exact same two files, same flags, compile clean (exit 0) on the currently-shipped GraalVM-built `dist/scalino-dotc`.

**Root cause, found via `SCALINO_INTERP_DEBUG=1` (the interpreter's own existing tracing env var, no new instrumentation needed)**: the crash traced to `Regressions.hostObjectMutationVisible`'s real `buf.update(0, 99)` on a genuine host `scala.collection.mutable.ArrayBuffer`. `ArrayBuffer#update` has no curated intrinsic, so its real retained-source body is genuinely tree-interpreted: `checkWithinBounds` reads the private `size0` field, `mutationCount += 1` writes another private field, then `array(index) = elem` touches a third. Reading/writing a real host object's own private field this way needs `reflectiveFieldRead`/`reflectiveFieldWrite` (`Interpreter.scala`) -- which the *previous* self-hosting pass (see the "Frontend-only smoke test" section above) already found and deliberately stubbed to always return `None`/`false`, because Scala Native's javalib has **no `java.lang.reflect.Field`/`Class#getDeclaredField` at all** (confirmed: no `Field.scala` anywhere under `vendor/scala-native/javalib`), unlike JVM/native-image where the real `java.lang.reflect`-based implementation works (and is separately traced/registered). With the read always failing, `size0` silently falls back to tree-replaying its stale initial value (0) instead of the real current size (2) -- exactly the ORIGINAL pre-fix symptom this same interpreter code's own doc comments describe, now resurfacing because the one mechanism that used to prevent it (real reflection) is architecturally unavailable on this backend. `checkWithinBounds` then computes `max = size0 - 1 = -1`, and the in-bounds `update(0, ...)` wrongly throws.

**Fix** (`patches/scala3-0009-curate-arraybuffer-update.patch`): rather than trying to resurrect field-level reflection (impossible here -- there is no general mechanism to add), curate `mutable.IndexedSeq#update` as a direct intrinsic in `Interpreter.scala`'s existing real-host-object dispatch table (`interpretStdlibIntrinsic`), calling the real public `b(i) = v` directly instead of tree-interpreting the retained source body at all -- exactly the same pattern already used for `Array#update`/`Map#update` immediately above it, and consistent with how `.apply`/`.size` on `ArrayBuffer` were already curated this same way (which is why *those* never hit this bug). This sidesteps `size0`/`mutationCount`/`array` entirely rather than working around their unreadability, and is correct on every backend (not a Scala-Native-only patch) -- it also fixes any *other* `mutable.IndexedSeq` implementor's `update`, not just `ArrayBuffer`'s.

**Verified**: rebuilt the full 550-file self-hosted binary from scratch with the fix (`$JAVA -cp compiler.cp dotty.tools.dotc.Main -Xplugin:<nscplugin jar> -Xplugin-require:scalanative -Yretain-trees -classpath nativelibs.cp @reduced-file-list.txt`, then `scalino-linkdriver`) -- zero compile errors, zero unreachable link symbols. `examples/interpreter-regressions/{Test,Foo}.scala` now compiles clean, 3/3 runs, exit 0 (matching the GraalVM binary). Re-ran the non-determinism regression check to confirm no interaction with the earlier fix: `PrivTest.scala` 10/10 runs byte-identical (MD5), and a full `Hello.scala` compile -> link -> run cycle still prints `hi`, exit 0.

**Bottom line (superseded by the broader sweep just below)**: the *architecture* of self-hosting -- dotc's own source, plus nscplugin's own source, compiled and linked into one binary that then compiles real user code to real NIR that then links into a real executable -- works, with GraalVM/native-image nowhere in the chain, and now produces **deterministic, verified-correct output** for every real case tested so far (`Hello.scala`, `PrivTest.scala`, `examples/interpreter-regressions`). All patches from this phase (`patches/scala3-0007-bake-in-nscplugin.patch`, `patches/scala3-0008-fix-plainfile-nondeterministic-hashcode.patch`, `patches/scala3-0009-curate-arraybuffer-update.patch`, `patches/scala-native-0002-nscplugin-selfhost.patch`, `build/selfhost/{gen-file-list.sh,ScalaNativeBuildInfo.scala}`) are saved and reproducible, but deliberately **not yet** wired into `build/all.sh`/the shipped pipeline. Both confirmed correctness blockers (non-determinism, this ArrayBuffer bug) are resolved; what remains before this could replace the GraalVM-built `scalino-dotc` is broader coverage (`examples/macro-hello` needs an external jsoniter dependency on the compile classpath to even test, not yet wired up here; `examples/upickle-enum-writer-bug` is a multi-file project not yet exercised through this binary; and, more fundamentally, the underlying platform gap this bug exposed -- Scala Native has no general field-reflection mechanism at all -- means any *other*, not-yet-exercised real host object whose only-`reflectiveFieldRead`/`Write`-reachable private state a future macro touches will hit the same class of bug until it, too, gets a curated intrinsic; there is no way to close this preemptively, only as each case is found). Promoting this to the default pipeline remains a separate, not-yet-taken decision.

### Broader regression sweep: a third real bug found+fixed (missing `compiler.properties` breaks lazy-val codegen), and a suspected architectural gap that turned out to be a one-flag fix (`.tasty` output via the existing early-tasty mechanism) (2026-09-06, later same day)

Before starting on scalino-lsp (a separate, large self-hosting effort), ran the fixed self-hosted `dotc` binary (patches 0001-0009) against the rest of this project's existing regression fixtures, to shake out any remaining interpreter/javalib gaps cheaply rather than discover them mid-LSP-work. Rebuilt from scratch first (`$JAVA -cp compiler.cp dotty.tools.dotc.Main -Xplugin:<nscplugin jar> -Xplugin-require:scalanative -Yretain-trees -classpath nativelibs.cp @reduced-file-list.txt`, then `scalino-linkdriver`) -- clean compile, 12,622 `.nir` files, clean link.

**`examples/upickle-enum-writer-bug` (a real multi-case-enum JSON round-trip via a real macro derivation): found and fixed a third real, self-hosting-specific bug.** Compiling+linking this fixture (classpath built from the real resolved `upickle_native0.5_3` jars, cached under the fixture's own `.scalino-build/deps-cache/`) failed at LINK time: `Unknown type java.lang.invoke.MethodHandles$Lookup` / `Unknown static method java.lang.invoke.MethodHandles.lookup()`, referenced from `AmbienteAdiacenteParete$`'s static constructor -- confirmed absent from the currently-shipped GraalVM `dist/scalino run` on the identical fixture (its cached `.nir` output has zero `MethodHandles`/`VarHandle` references for the same class).

Root cause, traced by diffing the two builds' actual NIR byte-for-byte (not guessing): dotc's own Scala-3.8+ lazy-val codegen (`transform/LazyVals.scala`) synthesizes a real `java.lang.invoke.MethodHandles.lookup().findVarHandle(...)` call to obtain a `VarHandle` for CAS-based lazy-val initialization, for *any* top-level lazy val (here, a `derives ReadWriter`-synthesized recursive typeclass instance) -- this is real, universal dotc behavior, reproduced identically by a plain published JVM dotc on the same source. Under a normal build, scala-native's own `nscplugin` immediately rewrites this away: `AdaptLazyVals.scala` (`nscplugin/src/main/scala-3/.../AdaptLazyVals.scala`) detects `MethodHandles`/`VarHandle`-based lazy vals (Scala 3.8+) via `compilerUsesVarHandles`, and rewrites the field access/CAS calls into `scala.scalanative.runtime.LazyVals$`'s own `objCAS`/`get`/`setFlag` -- a real, Scala-Native-native implementation, no `java.lang.invoke` involved at runtime at all. That detection is where this broke: `compilerUsesVarHandles` decides "is this dotc 3.8+?" by reading `dotty.tools.dotc.config.Properties.versionNumberString`, which loads `/compiler.properties` via `pickJarBasedOn.getResourceAsStream(propFilename)` -- a real classpath *resource* (not a class), normally bundled at the root of the published `scala3-compiler_3-*.jar` (confirmed: `unzip -l scala3-compiler_3-3.8.4.jar` lists a 113-byte `compiler.properties` with `version.number=3.8.4`), and reachable at runtime under the GraalVM build because it's traced/registered like any other resource (confirmed: `agent-config/scalino-dotc/reachability-metadata.json` has a `"glob": "compiler.properties"` entry). Our self-hosted binary compiles dotc **from source**, never from that jar, so this resource is never available on its own classpath at build *or* run time -- `getResourceAsStream` returns `null`, `scalaProps` stays empty, `versionNumberString` returns `""`. Critically, this doesn't fail loudly: `ScalaVersion.parse("")` matches its own `case "" | "any" => Success(AnyScalaVersion)` branch -- **not a parse failure** -- so `.toOption` is `Some(AnyScalaVersion)`, and the `.orElse(Some(ScalaVersion.current))` fallback `compilerUsesVarHandles` relies on for exactly this situation is **never even reached** (`orElse` only fires on `None`). `AnyScalaVersion` then falls through every `SpecificScalaVersion` case to `case _ => false`, so `compilerUsesVarHandles` silently reports **false** -- causing `AdaptLazyVals` to apply the *wrong*, pre-3.8, field-offset-based lazy-val heuristic to a compiler that actually emits the newer VarHandle-based form, so it never finds anything to rewrite, and the raw, JVM-only `MethodHandles`/`VarHandle` calls survive straight through to the emitted NIR -- unreachable at link time, since Scala Native's javalib has no `java.lang.invoke` support at all (confirmed, zero hits for `MethodHandle`/`VarHandle` anywhere in `vendor/scala-native/javalib`).

**Fix**: not a source patch -- a missing build-time *resource*. Added `build/selfhost/compiler.properties` (the same four keys the real published jar's copy has, `version.number`/`maven.version.number` pinned to this project's `versions.env` `SCALA_VERSION`). Whoever links the self-hosted binary must copy this file into the NIR output directory's root (i.e. `<out>/compiler.properties`, alongside the compiled `.nir` files -- `scalino-linkdriver`'s own resource embedder scans the link classpath directly, jars and plain directories alike) and pass `--embed-resources` to `scalino-linkdriver` (this flag already existed, wired to scala-native's own real `NativeConfig.withEmbedResources`/`ResourceEmbedder` -- just never previously needed for the self-hosted *compiler itself*, only for embedding resources in *user* programs it compiles). Documented directly in `build/selfhost/gen-file-list.sh`'s header (not itself a `.scala` file, so it can't be added to that script's own list output).

**Verified**: relinked with the resource + `--embed-resources` (log confirms `Embedded resource: /compiler.properties`) -- the previously-unreachable `MethodHandles`/`VarHandle` references are now **completely absent** from the recompiled `AmbienteAdiacenteParete$.nir` (confirmed via `strings`, zero hits, matching the GraalVM binary exactly), link succeeds, and running the linked binary reproduces the GraalVM build's exact output byte-for-byte (`TipoGenerazione`/`ContestoUrbano`, all non-ordinal-0 cases correctly serialized -- this fixture's own long-standing, already-fixed-per-item-12 upickle bug, unrelated to self-hosting). Re-ran both already-fixed regressions against this same (resource-embedding) binary to confirm no interaction: `PrivTest.scala` 10/10 byte-identical MD5s, `examples/interpreter-regressions` 3/3 clean exits.

**`interpreter/test-fixtures/macros-in-same-project1`**: compiles clean, single invocation, matching expectations (already covered by `build/05-regen-agent-config.sh`'s own tracing use of this exact fixture).

**`interpreter/test-fixtures/i4515` (two-stage separate compilation): initially looked like a real, large architectural gap -- turned out to be a missing FLAG, not a missing capability, and needed zero source patch (2026-09-06, later still).** Stage 1 (`Macro_1.scala` alone) compiled clean but produced zero `.tasty` output, only `.nir`; stage 2 (classpath pointed at stage 1's output) then failed with `Not found: Macro`. The initial read of this (recorded just above, now superseded) was that real dotc's TASTy-file-writing lives in `backend/jvm/ClassfileWriters.scala`/`PostProcessor.scala`, both deliberately excluded from the reduced source list (needs `scala-asm`, no Scala Native cross-build) -- implying a nontrivial port was needed to get `.tasty` output at all.

That framing was wrong, caught by actually reading `ClassfileWriters.scala` before assuming the port was necessary: `TastyWriter#writeTasty` there is *already* just a plain `underlying.writeFile(path, bytes)` call -- no ASM anywhere in it; ASM is only used for the separate `.class`-writing half of that file. More importantly, `ClassfileWriters.scala`'s own top-of-file comment says it flat-out: *"This file is now copied in `dotty.tools.io.FileWriters` in a more general way that does not rely upon `PostProcessorFrontendAccess`."* `dotty.tools.io.FileWriters` (package `dotty.tools.io`, **not** `backend.jvm` -- already included in the reduced source list wholesale, no exclusion touches it) is a complete, independent, ASM-free reimplementation of the same `TastyWriter`, and `transform/Pickler.scala` (a core phase, obviously already in the reduced set) already imports and uses it directly (`Pickler.EarlyFileWriter`, `Pickler.writeSigFilesAsync`) -- this is dotc's existing **early/pipelined TASTy output** mechanism (built for Zinc pipelining: write `.tasty` as soon as the `Pickler` phase finishes, without waiting for the JVM backend to run at all), gated purely by the `-Xearly-tasty-output <dir>` flag (aliases `-Ypickle-write`/`-Yearly-tasty-output`/`-Xpickle-write`; `Run.scala:415`'s trigger condition is just `Phases.picklerPhase.exists && !ctx.settings.XearlyTastyOutput.isDefault` -- no dependency on any backend phase being scheduled). Since this reduced source set schedules nscplugin's `GenNIR` instead of `GenBCode`, this early/pipelined path is in fact the *only* possible way to get `.tasty` output from this binary -- and it was already sitting there, fully compiled in, unused only because nothing had ever passed the flag.

**Verified end-to-end, zero source changes**: rebuilt the self-hosted binary from the current tree (patches 0001-0009 + `compiler.properties`, unchanged), relinked with `--embed-resources` -- clean compile (12,622 `.nir`), clean link, zero unreachable symbols. Ran stage 1 with `-Xearly-tasty-output /tmp/i4515-stage1` pointed at the same dir as `-d`: produced `Macro.nir`/`Macro$.nir`/`Macro.tasty` (real, non-empty `.tasty`). Ran stage 2 with `-classpath` including stage 1's output dir: exit 0, `Test.nir`/`Test$.nir` produced, correctly resolving `Macro`'s signature from the `.tasty` alone -- genuine cross-invocation separate compilation, working. Linked stage 1 + stage 2's NIR together via `scalino-linkdriver`: clean link, and running the result exits 0 -- matching the GraalVM `dist/scalino-dotc` binary's behavior on the identical two-stage fixture exactly (both stages exit 0, `dist/scalino-dotc` just uses `.class`+`.tasty` instead of `.nir`+`.tasty` for the same early-output mechanism). Re-ran both already-fixed regressions against this exact binary to confirm nothing regressed: `PrivTest.scala` 5/5 byte-identical MD5s, `examples/interpreter-regressions` 3/3 clean exits.

**Consequence for `scalino`'s own build tooling** (not yet done, flagged for whoever wires self-hosting into the real pipeline): to actually rely on this for `scalino`'s own incremental/multi-module compilation model, whatever driver invokes the self-hosted binary needs to always pass `-Xearly-tasty-output <dir>` (pointed at the same output dir as `-d` is the simplest choice) -- it isn't automatic. This is a one-line CLI-argument change to the driver, not a compiler patch.

**`~/scala/ape` (the real third-party-ish project used earlier this session as the toughest end-to-end check): not attempted this pass.** Given the `.tasty`-writing gap just found, and that `scalino`'s own build model is incremental/multi-invocation by default (`ScalinoCli.scala`'s own doc comment: "compilation is incremental by default... unchanged sources... reused from the last build"), it's genuinely unclear whether `ape` -- a real multi-file, dependency-heavy project -- would need cross-invocation TASTy reads at all even under `--no-incremental` (a single dotc invocation over every source file at once sidesteps the gap entirely, same as every fixture verified above), or would hit it immediately. Answering that with confidence needs either reading `ape`'s own macro-usage shape file-by-file, or just trying it end-to-end -- both more work than this sweep's remaining budget allowed for one already-large session. Left as the natural next verification step for whoever continues this.

**Sweep bottom line**: three real, self-hosting-specific correctness bugs found this session, all fixed and verified (non-deterministic NIR output, `ArrayBuffer#update` reflective-field gap, missing `compiler.properties` breaking lazy-val codegen), plus one apparent architectural gap (no `.tasty` output) that on closer investigation needed no fix at all -- just the pre-existing `-Xearly-tasty-output` flag, already fully wired and ASM-free in the reduced source set. Genuine cross-invocation separate compilation now works end to end. One real-world check remains untried and flagged (`~/scala/ape`). Patches/artifacts from this sweep: `build/selfhost/compiler.properties` (new), no source patches for the `.tasty` finding. Still not wired into `build/all.sh`/the shipped pipeline.

## `~/scala/ape` real-world validation: a severe, self-hosting-specific performance bug found and fixed (2026-09-06/07, same session)

Picked up the one flagged untried check from the sweep above: ran the self-hosted binary (patches 0001-0009, `compiler.properties`) against `~/scala/ape`'s full real `src/`+`test/` (38 files, real http4s/upickle/porcupine/cats-effect/cats-parse dependencies, real `uri"..."` literal macros and multi-case-enum `derives ReadWriter`), the same real classpath (`.scalino-build/deps-cache/*.cp`) the shipped `scalino test .` resolves for this project.

**Baseline established first, correctly this time (the earlier "42 tests passed" figure predates `-javabootclasspath` being required and used a different flag set -- re-confirmed clean rather than assumed)**: `dist/scalino-dotc` (GraalVM native-image), same classpath, same 38 files, same `-Xplugin`/`-Yretain-trees`/`-javabootclasspath` flags: **exit 0 in 2:14 (134s)**.

**The self-hosted binary, same input: did not finish within 20+ minutes.** Not an immediate crash or hang at a single instruction -- confirmed via two `lldb -p <pid> -o "thread backtrace" -o detach` samples ~20s apart, real, live, 100%-CPU work, and via a third sample later. All three samples' top-of-stack, independent of exact timing, landed inside `Interpreter.interpretTree0`'s real recursive descent, deep in a call chain repeatedly evaluating `scala.sys.package.env` (i.e. `sys.env`) -- confirmed by full backtrace, not inferred.

**Root cause, confirmed by microbenchmark, not just by reading the stack**: this project's own `Interpreter.scala`/`Splicer.scala` (added by `patches/scala3-0001-own-implementation-tasty-interpreter.patch`, this session's own tree-interpreter, not upstream dotc) gate ~35 separate `System.err.println` debug-tracing call sites -- hit on every node of `interpretTree0`'s recursive descent, every `interpretStaticCall`/`interpretInstanceCall`/`matchCase` -- behind `if sys.env.contains("SCALINO_INTERP_DEBUG") then ...`, re-querying `sys.env` **fresh, on every single recursive interpretation step**, rather than checking it once. On a real JVM this is essentially free: `scala.sys.package.env` calls `System.getenv()`, which returns an already-parsed, JVM-internal cached `java.util.Map` built once at JVM startup, so even calling `.asScala.toMap` millions of times is cheap (measured: a 2,000,000-iteration microbenchmark of `sys.env.contains(...)` alone, `/tmp/ape-check/EnvBenchJVM.scala`, took **4.8s** under plain JVM dotc). The identical microbenchmark (`/tmp/ape-check/EnvBench.scala`) compiled+linked through the self-hosted toolchain and run as a real Scala Native executable took **34.8s** for the same 2,000,000 calls -- **~7.2x more expensive per call** (`~17.4µs` vs `~2.4µs`). Scala Native's `System.getenv()` (`vendor/scala-native/javalib/.../System.scala`) does cache the parsed env in a module-level `val` (`EnvVars.envVars`, not itself the bottleneck), so the per-call multiplier is smaller than the non-deterministic-output bug's root cause but still real and, multiplied across the many hundreds of thousands of recursive interpretation steps a macro-derivation-heavy real project like `ape` genuinely produces, compounds into the observed multi-minute-and-counting overrun where GraalVM finishes in 134s. This is the same *class* of finding as the rest of this sweep (a JVM-vs-Scala-Native javalib cost/behavior difference that's invisible at small scale and severe at real-project scale) but a performance bug, not a correctness one -- the compile was making genuine forward progress the whole time, not stuck.

**Fix** (`patches/scala3-0010-cache-interp-debug-env-check.patch`): added two `private[dotc]` vals (`Interpreter.debugEnabled`, `Interpreter.debugMatchEnabled`) to `Interpreter`'s companion object, computed **once** from `sys.env.contains(...)` at first reference (module-level vals initialize exactly once, on both JVM and Scala Native), and replaced all ~35 call-site `sys.env.contains("SCALINO_INTERP_DEBUG"[_MATCH])` checks in `Interpreter.scala`/`Splicer.scala` with references to these cached vals. Correct on every backend regardless of `sys.env`'s per-call cost (the env var can't change mid-compile), not a Scala-Native-specific workaround.

**Resolved, confirmed with a real number, not just a stack sample: `~/scala/ape`'s full `src/`+`test/` now compiles in 3:22 (202s), exit 0** -- still ~1.5x slower than GraalVM's 134s (expected and acceptable: a tree-walking interpreter doing real recursive macro evaluation is not going to match an AOT-optimized native-image binary's raw throughput, and closing *that* gap is a different, much larger undertaking than removing an accidental O(n) redundant re-computation), but down from "did not finish within 20+ minutes" to a real, practical, everyday-usable compile time. `lldb` sampling of the running (fixed) process no longer shows `sys.env`/`Map.from` anywhere in the stack at any point -- samples land in ordinary compiler-internal work (`TypeOps.asSeenFrom`, `SymDenotation.overridingSymbol`, GC allocation) that varies between samples, confirming both that the specific bottleneck is gone and that the earlier multi-minute run was never actually stuck, just paying this real per-step tax repeatedly.

**A second bug, self-inflicted by this fix's own first draft, caught before finalizing rather than shipped**: the very first attempt at this patch used `replace_all` to swap every `sys.env.contains("SCALINO_INTERP_DEBUG"[_MATCH])` call site for a `debugEnabled`/`debugMatchEnabled` reference -- which, applied blindly, *also* rewrote the two new companion-object declarations themselves (`private[dotc] val debugEnabled: Boolean = sys.env.contains(...)`) into `private[dotc] val debugEnabled: Boolean = debugEnabled` -- a self-referential module-level `val`. This is not a compile error in Scala (object members may reference each other, including their own not-yet-initialized default), but a silent correctness bug: it reads the field's own zero-value default (`false`) at the point of its own initialization, so `debugEnabled` would have permanently evaluated to `false` regardless of the real environment variable -- the debug-tracing feature this patch was supposed to leave intact would have been silently, permanently disabled. Caught while reconstructing the patch file for saving (noticed the "before" reconstruction wouldn't round-trip cleanly), fixed by writing the two companion `val`s' right-hand sides explicitly rather than via the same blanket substitution used for the call sites. **Re-verified after the real fix**: rebuilt again from scratch (clean compile, 12,622 `.nir`; clean link), re-ran `PrivTest.scala` 10/10 byte-identical MD5s and `examples/interpreter-regressions` 3/3 clean exits (no interaction with the earlier fixes), and explicitly smoke-tested that `SCALINO_INTERP_DEBUG=1` still produces real trace output (`[interp] Block: ...`, `[spliceFallback] ...`) -- confirming the debug feature genuinely still works, not just that the perf fix "looked right." Re-ran `~/scala/ape` once more against this corrected binary as the final confirmation: exit 0, matching the timing above. Saved as `patches/scala3-0010-cache-interp-debug-env-check.patch`, verified to apply cleanly in sequence on a fresh pristine clone (0001 through 0010, byte-identical resulting files vs. the live working tree).

**Compiling was the first half of the ask, not the whole thing -- also linked and RAN the real test suite, matching the original request's own bar ("42 tests passed" under GraalVM).** Compiled `~/scala/ape`'s real `src/`+`test/` plus its own generated `.scalino-build/_scratch/ScalinoCliTestMain.scala` (the real munit test-runner entry point the shipped `scalino test .` itself generates and uses -- `sbt.testing.Framework`/`EventHandler`-based, discovers and runs all 9 real test suites, prints a final `passed`/`failed`/`errored`/`skipped` tally) together through the fixed self-hosted binary, then linked the result with `scalino-linkdriver`.

First link attempt failed (`LinkingException: Unreachable symbols... java.time.OffsetDateTime, cats.FlatMap$Ops`) -- not a real gap, a mistake in this verification's own command: the link classpath only had this run's own NIR output plus `nativelibs.cp`, dropping the actual dependency jars (http4s/cats-effect/scala-java-time/etc.) that need to be on the LINK classpath too, not just the compile classpath (`scalino-linkdriver` needs every jar a `.nir` file's symbols can reference, same as the compile step). Relinked with the full classpath (own output + `maindeps.cp` + `testdeps.cp` + `nativelibs.cp`): clean link, zero unreachable symbols, 9.7s.

**Re-established today's real GraalVM baseline first** (the docs' earlier "42 tests passed" figure is stale -- the project has grown since that entry was written): `dist/scalino test .` today: **54 passed, 0 failed, 0 errored, 0 skipped**, all 9 suites (`db.{Backup,BuildingRepo,Schema}Test`, `domain.{AmbienteNonRiscaldato,ClimateCatalog,DhwCalc,PrimaryEnergy,Raccomandazioni}Test`, `http.RoutesTest`).

**Ran the self-hosted binary's linked test executable directly: `54 passed, 0 failed, 0 errored, 0 skipped` -- identical to the GraalVM baseline, suite-for-suite and count-for-count**, in 0.56s wall-clock for the test run itself (compile+link time is separate, covered above). This is the first time in this project's history that a real, non-trivial, dependency-heavy third-party test suite has been compiled, linked, AND executed correctly by a fully self-hosted (zero-JVM, zero-GraalVM) `dotc`, with results matching the production GraalVM-built compiler exactly.

**Lesson for future patches in this codebase, worth keeping**: a blanket find-and-replace across a large diff is exactly the kind of change that's easy to eyeball as "obviously correct" and skip re-verifying -- this one silently broke the very thing it introduced, in a way `git diff` alone doesn't visually flag (the diff *looks* like a clean, uniform substitution) and initial compile+link+the standard regression suite didn't catch either (`SCALINO_INTERP_DEBUG` isn't set by any existing regression check, so `debugEnabled` being permanently `false` was indistinguishable from correct for every test that mattered until this session specifically thought to smoke-test the debug flag itself). Verify the actual new declarations a refactor introduces, not just the call sites it rewrites.


## Self-hosting scalino-lsp: Phase 2 starts, further than scoped (2026-09-07)

Phase 1 (scalino-dotc self-hosting) is done and validated against a real project. This section starts Phase 2: self-hosting `scalino-lsp`, dotty's own pre-Metals language server. Going in, the expected biggest risk was JSON-RPC/`lsp4j` -- a Java reflection-heavy RPC library with no obvious Scala Native story. That risk turned out to already be retired: this project's own earlier GraalVM-era work (see "JVM-free language server (LSP)" above) already ripped `lsp4j`/Gson/Jackson out entirely and replaced them with a hand-rolled `Main.scala` (Content-Length JSON-RPC framing, single-worker-thread dispatch) plus hand-written `jsoniter-scala` codecs in `Lsp.scala` -- done for a GraalVM native-image pathology, with zero reflection anywhere, which happens to make it trivially Scala-Native-friendly too. `build/08-build-scalino-lsp.sh` also already trims the shipped `scalino-lsp` down to exactly 4 real source files (`Lsp.scala`, `Main.scala`, `DottyLanguageServer.scala`, `Memory.scala` -- the worksheet/JVM-subprocess-REPL and TASTy-decompiler endpoints were already dropped by `patches/scala3-0002-trim-language-server.patch`, and both of those dropped features still reference real `lsp4j` types, so they're correctly out of scope here too). `DottyLanguageServer.scala`'s only compiler-facing dependency, `dotty.tools.dotc.interactive.*`, is already part of the existing self-hosted 550-file dotc source list (`compiler/src` minus a short exclusion list, not restricted to the batch-compile-only phases) -- so almost the entire dependency surface was already solved by Phase 1 without anyone intending it.

The one real remaining external dependency, `jsoniter-scala-core`, already ships a genuine, version-matched Scala Native cross-build on Maven Central (`jsoniter-scala-core_native0.5_3:2.37.3`, the *exact* version this project's GraalVM build already pins) -- unlike `tasty-core`/`scala3-interfaces`/`nscplugin` in Phase 1, this needs no source-porting at all, just adding the right jar to both the compile and link classpaths.

**What was actually done, not just scoped**: added the 4 LSP source files to a self-hosted JVM-mode compile alongside the existing 550-file dotc list (`nscplugin.cp` + `nativelibs.cp` + the JVM `jsoniter-scala-core_3` jar on `-classpath`, same `-Xplugin:<real nscplugin jar> -Xplugin-require:scalanative` recipe as Phase 1) -- compiled clean on the first real attempt (12,863 `.nir`/`.class`/`.tasty` files, zero errors) once `nativelibs.cp` was added to the compile classpath (needed regardless of LSP -- `scala.scalanative.unsafe.extern` and friends, referenced deep in dotc's own already-existing source, aren't resolvable from `nscplugin.cp` alone; this was a pre-existing latent gap in how Phase 1's own classpath was assembled, just never hit until a new file pulled in that exact path).

Linking (`scalino-linkdriver`, entry point `dotty.tools.languageserver.Main`) surfaced three real, sequential unreachable-symbol gaps, each fixed the same session:
1. **`java.util.logging.Logger`, used nowhere except two best-effort `logger.warning(...)` calls on an already-caught, already-swallowed exception in `dotc/interactive/Completion.scala`** (dead code for a batch compile -- this is the first self-hosting milestone to actually reach LSP's `textDocument/completion`, which is the only caller). Scala Native's javalib has no `java.util.logging` port at all (confirmed: zero files under `vendor/scala-native/javalib/.../util/logging`), and unlike `java.time`, there's no external polyfill artifact either. Fixed with a minimal patch (`patches/scala3-0011-lsp-selfhost-no-java-util-logging.patch`): replaced the `java.util.logging.Logger`-backed `logger` val with a two-line local object whose `warning(msg)` just does `System.err.println` -- correct on every backend (these are diagnostics on an already-swallowed exception, not behavior), not a Scala-Native-only workaround.
2. **`java.time.Instant`/`ZoneOffset` (used by `Main.scala`'s own `.scalino-lsp.log` timestamping) and, transitively, `java.util.Locale`** (needed by `java.time`'s formatter machinery). Neither is in scala-native's own javalib (confirmed for `Locale` earlier this session too, see the `nscplugin` `caseInsensitiveNameOf` fix) -- both are covered by real, already-cached, version-compatible external polyfill jars instead: `scala-java-time_native0.5_3:2.6.0` and, one level further down, `scala-java-locales_native0.5_3:1.5.4` + `cldr-api_native0.5_3:4.5.0` + `portable-scala-reflect_native0.5_2.13:1.1.3` (the locales polyfill's own runtime service-loader dependency). All four added to the link classpath; no source patch needed.

With those three additions, **the link succeeded clean on the first attempt after they were all present: zero unreachable symbols, a real ~51MB standalone `dotty.tools.languageserver.Main` executable.**

**Went further than "typecheck only" and actually ran it as a real server**: framed a real `initialize` JSON-RPC request by hand (`Content-Length: N\r\n\r\n{...}`), piped it into the binary's stdin with `-stdio`, and got back a real, correctly-framed JSON-RPC response on stdout -- the entire hand-rolled transport (Content-Length reader, envelope decode via the real `jsoniter-scala` codecs, worker-thread dispatch, response encode) works end to end on Scala Native, having compiled and linked through this project's own self-hosted toolchain with zero JVM/GraalVM involved at any stage. This is a substantially stronger result than "Phase 2 milestone 1" was scoped to ask for.

**A real bug surfaced by that very test -- root-caused and fixed (2026-09-07)**: the `initialize` response was a JSON-RPC error, not success -- `scala.scalanative.runtime.UndefinedBehaviorError` inside `Memory.isCritical()` (`DottyLanguageServer.initialize` calls `checkMemory` unconditionally on startup), tracing to `java.lang.Runtime.getRuntime().{maxMemory,totalMemory,freeMemory}`. The interesting part is *why* this compiled cleanly (JVM-mode typecheck, no error) yet only failed at native link/runtime: scala-native's own `java.lang.Runtime` (`vendor/scala-native/javalib/src/main/scala/java/lang/Runtime.scala`, read in full) never declares `maxMemory`/`totalMemory`/`freeMemory` at all -- only `gc()`. But the JVM-mode compile step runs on a real JVM (GraalVM's own `java`), whose bootstrap classloader supplies the *real* JDK `java.lang.Runtime` (with all three methods) ahead of anything on `-classpath`, including `nativelibs.cp`'s own scala-native `javalib` jar -- so dotc's typer silently resolves these calls against the *real* JDK class, not the scala-native stub the binary will actually link against. nscplugin still emits NIR calls to the (nonexistent-in-scala-native) methods; the linker's own "can't call ... on Runtime$" warnings during the "checking intermediate code" step were the only signal, non-fatal by default, easy to miss (as they were, until the JSON-RPC smoke test surfaced the resulting runtime trap). **This is a previously-undocumented, genuinely distinct risk category for this whole self-hosting effort**, worse than a simple "missing class" gap (which fails loudly at either typecheck or link): a method that exists on the real JDK class but not on scala-native's same-named stub can silently typecheck against the wrong one during a JVM-mode self-hosted compile, and only announce itself as a non-fatal link warning followed by a runtime crash -- any other call in dotc's or nscplugin's own source with this exact shape could be lurking undetected the same way.

**Fix, found without needing any platform-conditional split**: `Memory.scala` doesn't need to touch `Runtime` at all -- `java.lang.management.ManagementFactory`/`MemoryMXBean`/`MemoryUsage` (read in full, `vendor/scala-native/javalib/src/main/scala/java/lang/management/{ManagementFactory,MemoryMXBean,MemoryUsage}.scala`) is a real, already-implemented standard-JDK API on Scala Native's javalib (`MemoryMXBean`'s `getHeapMemoryUsage()` is backed by the exact `GC.get{Used,Max,Init}HeapSize()` calls flagged as the natural fix above) -- and it's the *same* standard JDK class on the GraalVM/JVM side too (`java.management` is a real JDK module, fully supported by native-image). So rewriting `isCritical()`/`stats()` to call `ManagementFactory.getMemoryMXBean().getHeapMemoryUsage()` instead of `Runtime.getRuntime()` is a single, unconditional, correct-on-every-backend change to shared source -- no design decision, no per-platform split, no separate file needed. Saved as `patches/scala3-0012-memory-use-management-api.patch`, verified to apply cleanly in sequence (0001-0012) on a fresh pristine clone (confirmed via a real `git reset --hard && git clean -fd` + full reapply on this session's own vendor checkout, not assumed).

**Verified on both sides, not just the self-hosted one**: rebuilt the self-hosted LSP binary (554-file compile, clean; link, zero unreachable symbols) and re-sent the identical hand-framed `initialize` request -- now returns a real success result (`"result":{"capabilities":{...}}`, full capability set), not an error. Also rebuilt the still-shipping GraalVM `dist/scalino-lsp` with the same patch (native-image succeeds clean) and ran `build/lsp-trace-drive.py` (the project's existing full-endpoint LSP functional test) against both binaries with a freshly-generated `.dotty-ide.json` (`dist/scalino setup-ide` against `build/lsp-trace-fixture`, sidestepping the separately-documented stale-path issue the older LazyVals-bug trace hit): **GraalVM binary: 8/8 endpoints pass** (`initialize`/`hover`/`definition`/`completion`/`references`/`rename`/`documentSymbol`/`workspace/symbol`, all `OK`) -- confirms the fix is neutral-to-positive for the real shipping build, not just safe. **Self-hosted binary: 2/8 pass** (`initialize`, `workspace/symbol`) -- `hover`/`definition`/`completion`/`references`/`rename`/`documentSymbol` all hit a *different*, separate `java.util.NoSuchElementException: next on empty iterator` in `DottyLanguageServer.configFor` (`.head` on an empty `Iterable`), unrelated to `Memory.scala`/this fix (distinct stack trace, distinct root cause, not yet investigated) -- a new, real, open item for whoever continues Phase 2, out of scope for this fix.

**The `configFor`/`NoSuchElementException` gap above -- root-caused and fixed (2026-09-07)**: reproduced directly (rebuilt the self-hosted LSP binary, regenerated a genuinely fresh `.dotty-ide.json` via `dist/scalino setup-ide` run from inside `build/lsp-trace-fixture` -- ruling the OLD stale-path fixture issue in/out first, cleanly, since a stale `.dotty-ide.json` with paths under a since-renamed directory *was* found sitting in the fixture at the time, but regenerating it did NOT fix the failure, so this is confirmed a *different*, new, self-hosting-specific bug, not the old one resurfacing). `.scalino-lsp.log` had the real cause the whole time, one line up from the misleading `configFor` crash: `drivers: failed to construct driver for project 'lsp-trace-fixture': java.util.NoSuchElementException: key not found: posttyper` -- thrown inside `new InteractiveDriver(settings)` (caught by the already-existing per-project `NonFatal` guard, see the "test-fixture mistake" item earlier in this document for why that guard exists -- it does its job correctly here, logging and continuing, but with only one project configured, "continuing" means `myDrivers` ends up empty, so every later request still dies in `configFor`'s `drivers.keys.head` fallback).

Root cause: `InteractiveCompiler.phases` (`compiler/src/dotty/tools/dotc/interactive/InteractiveCompiler.scala`) deliberately overrides the phase list to a minimal frontend-only pipeline for IDE latency -- `Parser`, `TyperPhase`, `SetRootTree`, `CookComments` -- with no `PostTyper` phase at all. Every real compile (batch or interactive) runs `Run.compileUnits`'s `ctx.base.addPluginPhases(ctx.base.phasePlan)` (`Run.scala:355`), which schedules any active plugin's phases into the real phase plan by resolving their `runsAfter`/`runsBefore` name sets (`Plugins.schedule`). nscplugin's `PrepNativeInterop.runsAfter = Set(transform.PostTyper.name)` ("posttyper") -- fine against a real batch compile's full phase list, but unresolvable against `InteractiveCompiler`'s reduced one. Patch 0007 (`loadRoughPluginsList`, "Wiring in the real backend" above) made the plugin activate *unconditionally*, with no equivalent to real dotc's actual gating (real, unpatched dotc only activates a plugin when `-Xplugin:<jar>` is actually passed on the command line -- something `InteractiveDriver` never does, since its settings come straight from a project's `.dotty-ide.json` `compilerArguments`, which have no reason to carry a plugin flag). So every `InteractiveDriver` construction on the self-hosted binary now tried to schedule nscplugin's phases into a phase plan that can't satisfy them, crashing before any real compilation happened.

Checked why this doesn't also affect the still-shipping GraalVM `dist/scalino-lsp`/`dist/scalino-dotc`, since `Plugins.scala` is shared source and the crash site has no platform-specific code at all: it can't, structurally. `build/08-build-scalino-lsp.sh` (and `03a-patch-compiler.sh`, which produces the `compiler-patched.cp` jar it compiles against) build GraalVM's binaries from the **published, essentially-unmodified `scala3-compiler_3` jar**, with only a narrow, explicit set of classes overlaid from patched source (`quoted/Interpreter*`, `transform/Splicer*`, `transform/MegaPhase*` -- see `03a-patch-compiler.sh`'s own header comment) -- `plugins/Plugins.scala` is not one of them. Patch 0007/0013 only ever apply to the full `vendor/scala3` source tree that this self-hosting effort compiles end-to-end; GraalVM's pipeline never sees them. Confirmed directly rather than assumed: ran `build/lsp-trace-drive.py` against the already-built, already-installed `dist/scalino-lsp` with the same freshly-regenerated `.dotty-ide.json` -- 8/8 endpoints pass, unaffected either way.

**Fix** (`patches/scala3-0013-gate-baked-in-plugin-on-require-flag.patch`): restored real dotc's opt-in gating on top of the no-jar-scanning simplification -- `loadRoughPluginsList` now returns `List(new ScalaNativePlugin())` only when `ctx.settings.require.value.contains("scalanative")` (i.e. `-Xplugin-require:scalanative` was actually passed, exactly as every real batch self-hosted compile already does), `Nil` otherwise. Verified to apply cleanly in sequence as 0001-0013 on a fresh pristine clone.

**Verified**: rebuilt the self-hosted LSP binary (557-file compile -- the 550 dotc files, the 4 LSP files, plus 3 more decompiler-support files `IDEDecompilerDriver.scala`/`PartialTASTYDecompiler.scala`/`TASTYDecompiler.scala`/`DecompilationPrinter.scala` needed to satisfy `DottyLanguageServer.decompilerDriverFor`'s import, none of them ASM-dependent -- clean compile, zero errors; link, zero unreachable symbols using the compile-time `jsoniter-scala-core_3` jar plus the link-time native cross-build jars: `jsoniter-scala-core_native0.5_3-2.37.3`, `scala-java-time_native0.5_3-2.6.0`, `scala-java-locales_native0.5_3-1.5.4`, `cldr-api_native0.5_3-4.5.0`, `portable-scala-reflect_native0.5_2.13-1.1.3`). Regenerated a fresh `.dotty-ide.json` and re-ran `build/lsp-trace-drive.py`: **7 of 8 endpoints now pass** (`initialize`/`hover`/`completion`/`references`/`rename`/`documentSymbol`/`workspace/symbol`) -- the `configFor` crash is gone, `.scalino-lsp.log` shows every request succeeding server-side. The 8th, `definition`, is logged server-side as `ok` too (`.scalino-lsp.log` timestamps show it completed in ~40s) but the driver script's 25s client-side timeout drops it before the response arrives -- a cold-first-typecheck latency issue (consistent with this session's other findings about the tree-walking interpreter being ~1.5-7x slower than JVM per operation), not a correctness bug; a new, minor, open item for whoever next tunes self-hosted LSP latency (a `--mode:release-fast` link, per this session's own earlier finding on `~/scala/ape`'s compile time, is the obvious first thing to try). Re-ran both standing regression checks with the fix in place to confirm no interaction: `PrivTest.scala` 5/5 byte-identical MD5s, `examples/interpreter-regressions` 3/3 clean exits.

**State**: `patches/scala3-0011-lsp-selfhost-no-java-util-logging.patch`, `patches/scala3-0012-memory-use-management-api.patch`, and `patches/scala3-0013-gate-baked-in-plugin-on-require-flag.patch` all saved and verified to apply cleanly in sequence after 0001-0010 on a fresh pristine clone. No `build/selfhost/`-equivalent script yet for the LSP file list/link classpath (7 extra source files + 5 extra link-only jars, small enough that hand-reproducing the commands above is straightforward, but worth scripting properly -- e.g. `build/selfhost/gen-lsp-file-list.sh` and a documented link-classpath jar list -- before this goes further, so it's reproducible without re-deriving from this section by hand). Not yet done: the `definition` cold-start latency item above; wiring any of this into `build/all.sh`. This phase is genuinely promising -- most of the feared risk (JSON-RPC, `lsp4j`, JVM-only threading/streaming) turned out to already be non-issues, every one of the 4 real bugs found along the way has been fixed, and 7 of 8 real LSP endpoints now work correctly end to end on a fully self-hosted, zero-JVM binary -- earlier-stage than Phase 1's own "full real-world validation" milestone, but no longer far behind it.

## A real, reproducible build script for Phase 1: `build/selfhost/build-dotc.sh` (2026-09-07)

Phase 1's recipe (patches 0001-0010, `build/selfhost/{gen-file-list.sh,ScalaNativeBuildInfo.scala,compiler.properties}`) was, until now, only reproducible by hand-following prose scattered across this document -- a real liability, since none of it was a runnable artifact. Wrote `build/selfhost/build-dotc.sh`: a single, real, idempotent script that (1) applies all patches via the existing `build/00b-setup-vendor.sh`, (2) generates the ~550-file source list via the existing `build/selfhost/gen-file-list.sh`, (3) compiles it with a plain JVM dotc (`$JAVA -cp compiler.cp dotty.tools.dotc.Main -Xplugin:<nscplugin jar> -Xplugin-require:scalanative -Yretain-trees -classpath nativelibs.cp -d <out> @file-list`), copies in `compiler.properties`, (4) links via the already-built `dist/scalino-linkdriver` with `--embed-resources` (classpath `<out>:nativelibs.cp` -- the NIR output alone has no `java.lang.Object` etc, needs the Scala-Native-cross-compiled stdlib too), producing `dist/scalino-dotc-selfhosted` (deliberately never touching the real GraalVM-built `dist/scalino-dotc`), and (5) runs a real smoke test (compiles+links+runs `examples/Hello.scala` through the binary it just built, asserting the real output). Not wired into `build/all.sh` -- still opt-in, same as everything else in this arc.

Two gaps found while writing it, both now fixed in the script itself (not previously written down as exact commands anywhere, only implied): the link classpath needs `nativelibs.cp` appended alongside the NIR output dir (a bare NIR-output-only classpath fails to link with `LinkingException: Class info not available for Top(java.lang.Object)`), and the self-hosted binary itself needs `-javabootclasspath dist/java.base.jar` to compile any real code at all (without it: `AssertionError: asTerm called on not-a-Term val <none>` inside `Definitions.init`, since `-classpath` alone has no real JDK classes for dotc's own bootstrap `Definitions` to resolve against).

**Verified end-to-end from a real run of the script itself** (not just inspection): clean compile (zero errors), clean link (zero unreachable symbols), smoke test passed (`hello from scalino` printed correctly). Additionally re-ran the non-determinism regression check against the binary the script itself produced: `PrivTest.scala` compiled 10 times, byte-identical MD5s on both `.nir` outputs every time -- confirms the script reproduces the actual validated, bug-fixed state, not a stale pre-fix one.

## Closing out the `definition`-endpoint latency item with `--mode release-fast` -- and a real, separate scala-native linker bug found along the way (2026-09-07)

Phase 2's last open item was the self-hosted LSP binary's `definition` endpoint completing correctly but past `build/lsp-trace-drive.py`'s 25s client timeout (~40s server-side, debug-mode link). Relinking the same NIR output with `--mode release-fast` (`scalino-linkdriver ... --mode release-fast --embed-resources`, `scala.scalanative.build.Mode.releaseFast` under the hood) hit a new, unrelated problem first: **`scalino-linkdriver` itself -- a GraalVM-native-image tool, not the self-hosted binary being produced -- crashed with a `StackOverflowError`** during its own release-fast optimization pass, in `scala.scalanative.codegen.Lower$Impl.findNonRecursive` (`vendor/scala-native/tools/.../codegen/Lower.scala:66-72`), a plain recursive (non-tail-recursive, one stack frame per predecessor block) CFG-predecessor walk used by the null-guard-elimination optimization. This is a genuine, pre-existing scala-native linker limitation, unrelated to any patch from this session: `findNonRecursive` recurses once per predecessor block in a method's control-flow graph, and `release-fast`'s more aggressive inlining/lowering produces deeper, larger CFGs than `debug` mode does for the same source -- dotc's own methods are large and heavily branching (a whole compiler frontend+backend self-hosting itself), so this is exactly the kind of input likely to exceed the link driver's default thread stack size for the first time. Root-caused, not assumed: reproduced twice identically, read `Lower.scala` to confirm the shape of the recursion, and confirmed the crash is in the link driver's own worker thread (`pool-5-thread-1`), not in the binary it's producing.

**Fix**: no source patch needed. `scalino-linkdriver` is itself a GraalVM native-image executable, and native-image binaries honor the standard `-Xss<size>` JVM runtime flag for worker-thread stack size (confirmed empirically: `dist/scalino-linkdriver -Xss64m <cp> <workdir> <mainClass> <clang> <clang++> info --mode release-fast --embed-resources` -- passed as the *first* argv token, consumed by the native-image runtime before `LinkDriver.main` ever sees `args`, exactly like a real JVM's `-Xss` would be). `-Xss64m` (vs. the platform default, well under that on macOS/arm64 for a thread pool worker) was sufficient; link completed clean, zero unreachable symbols, ~38.5MB binary (vs. 51MB debug). This is worth remembering as a general rule for this whole self-hosting effort, not just LSP: **any time `scalino-linkdriver` itself crashes with a `StackOverflowError` during optimization (as opposed to the produced binary crashing at its own runtime), try `-Xss<size>` on the driver invocation before assuming a real linker bug needs a source fix** -- the driver's own default thread stack size was simply never sized for a call graph this large before this project started self-hosting its own compiler.

**Verified**: relinked the LSP binary in release-fast mode (patches 0001-0013, i.e. including the `configFor` gating fix, confirmed via a fresh recompile of the 558-file LSP+dotc source list against the current patched tree -- the first release-fast link attempt reused a stale pre-0013 NIR output by mistake and correctly reproduced the *old* `configFor`/`NoSuchElementException` failure, which is a useful confirmation that that bug really is patch-0013-specific and not mode-specific). Re-ran `build/lsp-trace-drive.py` against the release-fast binary: **8/8 endpoints pass** -- `initialize` 401ms, `hover` 88ms, **`definition` 9805ms** (down from ~40s in debug mode, comfortably under the 25s client timeout), `completion` 2065ms, `references` 26ms, `rename` 3ms, `documentSymbol`/`workspace/symbol` both near-instant. Full trace-drive run completed in 15.9s wall-clock.

**Phase 2 status**: all currently-known open items are closed. The self-hosted `scalino-lsp` binary passes every endpoint `build/lsp-trace-drive.py` exercises, at latencies well inside a real editor's expectations, fully self-hosted with zero JVM/GraalVM in the resulting binary. Remaining before this could be considered production-ready: eventually, a decision on wiring either self-hosted binary into `build/all.sh` for real.

## A real, reproducible build script for Phase 2: `build/selfhost/build-lsp.sh` (2026-09-07)

Wrote the LSP equivalent of `build/selfhost/build-dotc.sh`, closing the "no script yet" gap flagged above. `build/selfhost/gen-lsp-file-list.sh` extends `gen-file-list.sh`'s dotc list with the 4 real LSP files plus the 4 decompiler-support files (558 total, confirmed matching the count already validated by hand). `build/selfhost/build-lsp.sh` applies patches, generates that list, compiles it (plain JVM dotc + real nscplugin jar + `lsp.cp`'s JVM `jsoniter-scala-core_3` jar), links via `scalino-linkdriver -Xss64m ... --mode release-fast --embed-resources` against `nativelibs.cp` plus the 5 link-only Scala-Native cross-build jars, writes `dist/scalino-lsp-selfhosted` (never touching the real `dist/scalino-lsp`), then smoke-tests it for real via `build/lsp-trace-drive.py` against `build/lsp-trace-fixture` (regenerating a fresh `.dotty-ide.json` first via the real `dist/scalino setup-ide`).

One new wrinkle found while scripting the jar-fetch step: `cs fetch --intransitive` with all 5 native cross-build coordinates in a single invocation still pulled in `jsoniter-scala-core_native0.5_3`'s own transitive scala-native-runtime deps (a second, conflicting copy of javalib/posixlib/nativelib/clib pinned to an older scala-native version than this project's own `nativelibs.cp`, at 0.5.12) -- 1045 duplicate-symbol linker errors, then (once narrowed to just that one jar) a `cannot use 'throw' with exceptions disabled` compile error in that older nativelib's own `eh.cpp`. `--intransitive` behaves correctly given exactly ONE coordinate per invocation (verified directly, both alone and in combination) -- so the fix was simply to fetch each of the 5 jars with its own separate `cs fetch --intransitive <one-coordinate> --classpath` call and concatenate the results, rather than one call with all 5.

**Verified end-to-end from a real run of the script**: clean 558-file compile, clean release-fast link (zero unreachable symbols, `-Xss64m` needed as expected per the release-fast section above), and the real `lsp-trace-drive.py` smoke test **passes 8/8** (`initialize` 415ms, `hover` 76ms, `definition` 9804ms, `completion` 2050ms, `references` 26ms, `rename` 3ms, `documentSymbol`/`workspace/symbol` near-instant) -- matching the manually-verified result above exactly, confirming the script reproduces the real, validated state.

## Stress-testing the self-hosted LSP against a real multi-file project (`~/scala/ape`) -- new coverage, one real bug found (2026-09-07)

`build/lsp-trace-drive.py`'s fixture is a small, fixed, 3-file, single-directory project -- it already covers incremental `didChange` (edit + re-diagnose), but never exercises real cross-package resolution across multiple `sourceDirectories`, nor whether editing a symbol's *definition* file correctly invalidates a *different*, unedited *usage* file's view of it. Wrote `build/lsp-stress-ape.py` (kept as a real, reusable fixture, sibling to `lsp-trace-drive.py`) to test exactly that against `~/scala/ape`'s real 19-file, 6-directory source tree: `initialize` against a real multi-dir project, `didOpen` on two files in different packages (`domain/Building.scala` defining `BuildingInput`, `db/BuildingRepo.scala` importing and using it), `hover`/`definition`/`references` at the cross-package use site, then a `didChange` injecting a real type error into the *definition* file followed by a re-`hover` on the (unedited) *usage* file.

First run needed a fixture-setup fix, not a bug: `dist/scalino setup-ide src` alone (without also passing `project.scala`) silently produces a `.dotty-ide.json` missing all of ape's real declared dependencies (http4s/upickle/cats/porcupine) -- `scalino setup-ide` only reads `// > using dep` directives from files it's explicitly given, and `project.scala` (where ape declares them) isn't itself under `src/`. Passing `scalino setup-ide src project.scala` resolves and includes the real dependency jars correctly. Not a self-hosting bug, just a real fixture-setup detail worth documenting for whoever reuses this script.

**Functionally, cross-package resolution is entirely correct on the self-hosted binary**: `hover`/`definition`/`references` at the `BuildingInput` use site all resolve correctly across the `domain`→`db` package boundary, and the cross-file invalidation case works too -- editing `domain/Building.scala` to introduce a type error produces real diagnostics there, and a subsequent `hover` on the unedited `db/BuildingRepo.scala` use site still returns a correct, live result (the interactive driver's cross-file dependency tracking is real, not single-file-only).

**One real, genuinely self-hosting-specific bug found**: every `definition`/`references` response's `uri` field is malformed -- `file:/Users/.../Building.scala` (single slash after the scheme) instead of the RFC-8089-correct `file:///Users/.../Building.scala` (triple slash) that both the LSP spec and every real editor expect, and that the request's own `didOpen`-supplied URIs already use. Confirmed self-hosting-specific by running the identical stress script against the still-shipping GraalVM `dist/scalino-lsp`: correct `file:///...` URIs throughout. A non-compliant URI here isn't cosmetic -- a real editor that string-matches a `definition` response's URI against its own already-open-buffer URI list (the normal way to decide "jump in this buffer" vs "open a new one") would treat every self-hosted-binary jump-to-definition as pointing at a *different*, unopened document.

**Root cause, confirmed by reading source, not guessed**: `InteractiveDriver.toUriOption` (`compiler/src/dotty/tools/dotc/interactive/InteractiveDriver.scala:334`) builds these URIs via plain `Paths.get(file.path).toUri`. On JVM, `sun.nio.fs.UnixUriUtils.toUri` constructs the URI by directly building the string `"file:" + "//" + <path>` (path already starts with `/`, giving the correct triple slash) and parsing that as a whole. Scala Native's own `java.nio.file.Path` implementation (`vendor/scala-native/javalib/.../nio/fs/unix/UnixPath.scala:63-72`, and identically in `.../windows/WindowsPath.scala:215-224`) instead builds the URI via the generic 7-argument `java.net.URI(scheme, userInfo, host, port, path, query, fragment)` constructor, passing `host = null`. Per `java.net.URI`'s own semantics (confirmed by reading `vendor/scala-native/javalib/src/main/scala/java/net/URI.scala:103-105`: the authority-and-`"//"` prefix is only emitted `if (userinfo != null || host != null || port != -1)`), `host = null` (combined with `userInfo = null`, `port = -1`) means "no authority at all" -- so the constructor never emits `"//"`, producing `file:/path`. This is a genuine bug in scala-native's own `UnixPath`/`WindowsPath` `toUri()` -- not a dotc/nscplugin issue, not caused by any patch from this self-hosting effort, and not specific to the LSP (any Scala Native program calling `Path#toUri()` on an absolute local path hits the identical malformed result).

**Fix, correct and verified logically, but NOT effective in this project's current pipeline**: changed both `UnixPath.scala` and `WindowsPath.scala` to pass `host = ""` (empty string, not `null`) -- an empty-but-*defined* host, which correctly signals "authority present, but empty" and makes the constructor emit the `"//"`, producing the correct `file:///path`. Saved as `patches/scala-native-0003-fix-path-touri-missing-authority-slash.patch`, verified to apply cleanly in sequence after `scala-native-0001`/`0002` on a fresh pristine clone. **However, rebuilding the self-hosted LSP binary with this patch applied did NOT change its behavior** -- still `file:/...`. Root cause of *that*: this whole self-hosting effort's link classpath (`nativelibs.cp`, populated by `build/01-fetch-deps.sh`) uses the **published, prebuilt `javalib_native0.5_3-0.5.12.jar` from Maven Central**, not anything compiled from this project's own patched `vendor/scala-native/javalib` source -- self-hosting so far has only ever meant self-hosting the *compiler* (dotc+nscplugin); the Scala Native *runtime library* that produced binaries link against has always been the normal published distribution, completely untouched by any `vendor/scala-native` source edit. So this patch, while a real and correct fix, is currently inert: nothing in this project's build pipeline compiles `vendor/scala-native/javalib` from source into anything that gets linked against.

**Left open, deliberately, as a new item**: making this fix actually take effect would need either (a) reporting it upstream to scala-native (it's a genuine bug in the published artifact, unrelated to this project), or (b) extending the self-hosting pipeline to build `javalib` from this project's patched source (its own build, separate from dotc/nscplugin's sbt/mill setup -- not investigated) and substituting that jar ahead of the published one on every link classpath that matters, which is a materially bigger scope change (affects every scala-native build in this project, not just the self-hosted ones) than this stress-testing pass was scoped for. The patch file is kept as the correct, ready-to-use fix for whichever path is taken later.

**Also confirmed, not a new finding**: `definition`'s ~9-10s latency (release-fast mode, matching the earlier-documented cold-start figure) reproduced identically against ape's larger project -- consistent, not a regression.

## Investigated making the `javalib` patch take effect (option (b) above) -- real, non-trivial scope, not done this pass (2026-09-07)

Investigated whether building `vendor/scala-native/javalib` from source (so `patches/scala-native-0003`'s already-correct fix actually reaches a linked binary) is a thin, tractable addition or a real undertaking. It's the latter -- three compounding blockers found empirically, no source changed, nothing in the repo touched (all work done in `/tmp` scratch, cleaned up):

1. **The published `javalib_native0.5_3.jar` cannot be used as a partial-recompile base.** The natural first attempt -- compile only the two patched files (`UnixPath.scala`/`WindowsPath.scala`, plus `java/net/URI.scala` for its named-constructor-arg dependency) against the published jar on the classpath, mirroring `build/03a-patch-compiler.sh`'s proven "patch a few files against the published jar" pattern for the *compiler* -- doesn't work here, because `commonJavalibSettings`'s `NIROnlySettings` (`vendor/scala-native/project/Settings.scala:694`) strips `.class`/`.tasty` from javalib's own packaged jar entirely (confirmed: the real published jar is 3421 `.nir` files, 0 `.class`, 0 `.tasty`). A `.nir` file carries zero type information usable by dotc's frontend -- so anything outside the file(s) you pass as source (`URIEncoderDecoder`, `UnixFileSystem`, `Files`, etc, all real javalib classes) is simply invisible to the compiler, unlike `scala3-compiler_3`'s jar (real `.class` files, why 03a's approach works there). **Consequence: there is no partial-recompile path for javalib at all -- compiling any of it from source means compiling all (or nearly all) of it together as one unit**, same as this session's dotc/nscplugin self-hosting itself required.

2. **Compiling javalib's ~601 files together (`src/main/scala` + the `scala-3` version overlay -- `commonJavalibSettings` doesn't reveal this ahead of time, discovered by checking `find .../javalib/src/main -maxdepth 1 -type d`) hits a real, pre-existing (not self-hosting-related, not caused by the Path patch) interpreter gap**: several `java.net.*` files (`InetAddress.scala`, `Socket.scala`, `ServerSocket.scala`, and the platform socket-impl variants) call an `inline def` in `scala.scalanative.unsafe.UnsafePackageCompat` (`validatedSize`, a pointer/buffer-size-validation helper -- part of `nativelib`, not `javalib`), and dotc's compile-time macro/inline interpreter can't expand it: `"Interpreter (own implementation) does not support calling method validatedSize in object UnsafePackageCompat: ... this own-implementation interpreter does not yet unpickle cross-module TASTy bodies"` -- the exact same general, already-documented interpreter limitation this whole project has hit repeatedly (see the `scalalib-retained.jar`/`build/03b-build-scalalib-retained.sh` precedent earlier in this document), not anything new.

3. **Pulling `nativelib`'s own ~103 files into the same compile (so `UnsafePackageCompat` is a local symbol, sidestepping the cross-module interpreter gap entirely -- confirmed this approach DOES eliminate that specific error class) uncovers a second, different layer of real compile errors specific to `nativelib` itself**: a cyclic reference in `runtime/Continuations.scala`, syntax errors in `runtime/NativeThread.scala`/`reflect/Reflect.scala` (Java-style no-parens calls -- e.g. `_aliveThreads.size` -- on methods that resolve, in this ad hoc invocation, to strict-parens Scala definitions rather than lenient-parens real JDK classes; likely the same "Java interop leniency disappears once ported to a real Scala class" pitfall this document's own dotc/nscplugin self-hosting section already ran into once, but a different, not-yet-diagnosed instance of it here), and type mismatches in `runtime/Class.scala`/`unsafe/package.scala`. None of these appear when `nativelib` is compiled the *normal* way (as its own isolated sbt module, which is how the currently-published, working `nativelib_native0.5_3.jar` was actually produced) -- meaning a correct from-source build needs to replicate whatever compiler flags/classpath/compile-order `nativelib`'s own sbt project graph (`project/Build.scala`'s `MultiScalaProject("nativelib")`, `.dependsOn(javalibintf % Provided)`, plus scala-version-specific overlay directories like `scala-3`/`scala-2`/`scala-next`) actually uses, which isn't fully reconstructable by inspection alone in one pass.

**Conclusion**: extending this pipeline to build a patched `javalib` (and transitively enough of `nativelib` to satisfy it) is a real, multi-step undertaking -- likely either (a) getting scala-native's own sbt build to run for real in this environment (untested this pass: `sbt` is installed locally, and a stale, unrelated local `~/.ivy2/local` SNAPSHOT publish of these same artifacts exists on this machine from prior, unrelated work, suggesting sbt itself is not the blocker -- but a full sbt build was not attempted, since it's potentially slow/network-dependent and a materially different approach from every other patch in this project, which all avoid sbt in favor of direct classpath-based dotc invocations), or (b) painstakingly reconstructing `nativelib`+`javalib`'s exact real compile flags/order/overlay-selection by hand to keep using the direct-invocation approach. Either is real, scoped work for a future session, not a quick follow-on. `patches/scala-native-0003-fix-path-touri-missing-authority-slash.patch` remains saved, correct, and inert exactly as before -- reporting it upstream to scala-native (option (a) from the previous section) remains the lowest-effort way to get it live short of this.

## The `javalib` patch takes effect: sbt's own build works here (option (a), done) (2026-09-07)

Attempted option (a) from above -- get scala-native's own sbt build running in this environment for real, rather than hand-reconstructing its compile settings. It worked, on the second try, and the reason the first try failed is itself a useful finding.

**sbt was never the blocker.** `sbt` (1.12.9, via the `sbt` launcher) is installed and has real network access (confirmed against Maven Central directly). `MultiScalaProject("javalib")` (`vendor/scala-native/project/Build.scala`) publishes its Scala-3 variant under sbt project id `javalib3` (derived by `MultiScalaProject.projectID`: `<name><major>.replace('.', '_')`, so `"javalib" + "3"`) -- so the real, targeted invocation is just `sbt javalib3/publishLocal`, no full-project build needed.

**First attempt failed for a real, structural reason, not a build-environment problem**: `javalib3/publishLocal` failed compiling `nscplugin` (a dependency of `javalib` via `.withNativeCompilerPlugin`, needed to cross-compile javalib's source to NIR) with 7 `E008 Not Found` errors, all `value javaClassName/javaSimpleName is not a member of ... Symbol`, suggesting the fix `import dotty.tools.backend.jvm.DottyBackendInterface.symExtensions`. This is exactly `patches/scala-native-0002-nscplugin-selfhost.patch`'s own change, working as intended -- but working against the *wrong compiler*: that patch repoints `nscplugin`'s import to the new post-extraction location (`dotty.tools.dotc.core.SymExtensions`) needed by *this session's self-hosted dotc* (which has that extraction applied via `patches/scala3-...`). sbt's own build of `nscplugin`, though, compiles against the **published, unmodified `scala3-compiler_3` jar**, where `symExtensions` still lives at the *old* location -- so `patches/scala-native-0001`/`0002` (both of which touch `nscplugin`/`tools` sources, needed only for this project's self-hosted dotc path) make the same source tree simultaneously incompatible with sbt's normal build. This isn't a bug in either patch; it's a real, previously-undocumented fact about this whole effort: **`vendor/scala-native`'s patched checkout now serves two different, incompatible compiler targets from the same source tree, and building `javalib` (which pulls in `nscplugin` as a build dependency) needs the *unpatched* variant, while every other self-hosting step in this project needs the patched one.**

**Fix**: no source patch -- reverted `scala-native-0001`/`0002` (`git apply -R`, both applied cleanly in reverse) immediately before running `javalib3/publishLocal`, then reapplied both immediately after (`git apply`, confirmed `git diff --stat` matches the pre-build patched state exactly, all 11 files, before proceeding). With those two patches out of the way, `sbt javalib3/publishLocal` succeeded cleanly in 41s: `nscplugin` (65 files), `nativelib` (103 files + 1 Java), `clib` (21), `windowslib` (34), `posixlib` (67), then `javalib` itself (601 files) -- all against the crossScala3 build's own pinned Scala version (3.8.3, not this project's 3.8.4; irrelevant, since the `_3` artifact suffix denotes ordinary Scala-3 binary compatibility, not a specific minor version, exactly like the already-published jar this replaces). Output: `javalib_native0.5_3-0.5.12-SNAPSHOT.jar` under `~/.ivy2/local/org.scala-native/javalib_native0.5_3/0.5.12-SNAPSHOT/jars/`, confirmed via `unzip -l` to contain real, patched `UnixPath`/`WindowsPath` `.nir` output (3421 `.nir` entries total, 0 `.class`/`.tasty`, same shape as the published jar -- expected, `NIROnlySettings` strips those from every javalib build, not just this one).

**Wired into the pipeline, scoped to self-hosting only**: both `build/selfhost/build-dotc.sh` and `build/selfhost/build-lsp.sh` now generate a copy of `nativelibs.cp` under `$WORK/selfhost/` (`NATIVELIBS_CP`) with the published `javalib_native0.5_3` entry swapped for `$HOME/.ivy2/local/.../javalib_native0.5_3.jar` when that local jar exists (falling back to the published jar with a loud warning otherwise) -- every compile/link invocation in both scripts now reads from this copy instead of the shared `$WORK/nativelibs.cp`. This deliberately does **not** touch `$WORK/nativelibs.cp` itself, so the real GraalVM pipeline (`build/03..09-*.sh`), which reads that file directly, is completely unaffected -- confirmed by inspection (`build/07-build-scalino.sh`, `build/06-package.sh` etc. only ever reference `$WORK/nativelibs.cp` / `$DIST/nativelibs.cp`, never the new selfhost-scoped copy).

**Verified end to end**: reran both scripts for real. `build-dotc.sh`: clean compile+link+smoke test against the patched javalib. `build-lsp.sh`: clean compile+link, `lsp-trace-drive.py` **8/8**. Then, specifically to confirm the URI fix: reran `build/lsp-stress-ape.py` against the freshly-built `dist/scalino-lsp-selfhosted` -- `definition(BuildingInput)` now resolves to `file:///Users/lorenzo/scala/ape/src/domain/Building.scala` (correct triple-slash form, matching `building_uri`'s own construction exactly -- previously this comparison silently failed, since the malformed `file:/...` form never equals the correctly-formed URI the test builds for comparison), `references` similarly returns two correctly-formed `file:///...` URIs, and the script's own `OVERALL: PASS` gate (which depends on that exact equality) now passes. Re-ran the dotc determinism regression too (`PrivTest.scala`, 5 runs) against a build using the swapped javalib -- still byte-identical MD5s, confirming the javalib swap doesn't reintroduce or interact with the earlier `PlainFile`/`intern()` bug.

This closes out the last open item from the ape stress-test: **all three self-hosting-specific bugs found so far in Phase 2 (missing `java.util.logging`, `Memory.isCritical()`'s `Runtime` gap, and this URI bug) are now fixed and actually taking effect in the built binaries**, not just patched-but-inert. `patches/scala-native-0003` is no longer a special case -- it's live, verified, and reproducible via `build-dotc.sh`/`build-lsp.sh` like every other patch in this effort, contingent only on having run `sbt javalib3/publishLocal` once (with `scala-native-0001`/`0002` temporarily reverted, as above) to populate `~/.ivy2/local`.

## Full cutover to self-hosted binaries: `ScalinoCli.scala`'s own entry-point/test discovery was the real blocker, not the compiler or the LSP -- fixed via NIR (2026-09-07)

With the user's explicit authorization ("continue on self-hosting the missing pieces and then delete graalvm native-image altogether when finished"), a real end-to-end cutover attempt -- wiring `build/all.sh` to produce the self-hosted binaries as the real, shipped `dist/scalino-dotc`/`dist/scalino-lsp` -- correctly stopped itself before touching anything destructive when it hit a genuine, serious functionality gap, rather than forcing forward. This section documents that gap and its resolution.

**The blocker**: `cli/ScalinoCli.scala` (the `scalino` CLI tool itself -- already self-hosted since before this session, see the "Toward a build-tool experience without a JVM" arc earlier in this document) detects `scalino run`'s entry point and `scalino test`'s test classes by hand-rolling a JVM classfile reader (`scanClassFile`/`analyzeClassFile`) over the `.class` files `scalino-dotc` writes into `classesDir` alongside `.nir` -- **on a real JVM/GraalVM-built `scalino-dotc`, `-Xplugin:nscplugin`-activated compiles still run the normal JVM backend (GenBCode) in parallel with nscplugin's own NIR-generation phases, so `.class` files always exist there.** A self-hosted `scalino-dotc` (backend/jvm/ASM deliberately excluded from its whole source set, established at the very start of Phase 1) can **never** produce a `.class` file, under any circumstance -- so `detectMainClass`/`discoverTestClasses` would find nothing, ever, for any project, under a full cutover. This is not an edge case: it's the default, unavoidable way `scalino run`/`scalino test` are invoked (without an explicit `--main-class`), so it would have broken the tool for real, everyday use.

**Why TASTy was tried first, then abandoned for NIR**: the first fix attempt used dotc's own `-from-tasty`/`ReadTasty` machinery (the same real, already-proven infrastructure `scala.tasty.inspector.TastyInspector` and this project's own `-Xearly-tasty-output` separate-compilation support are built on) to query real `Symbol`-level info -- superclass, interfaces, module-ness, method signatures -- directly, reasoning that reusing dotc's own already-tested unpickler beat hand-rolling a new binary-format parser. A small `ScalinoDiscoverCompiler`/`ScalinoDiscoverPhase` (a `TASTYCompiler` subclass driven by a new `-Yscalino-discover` flag) was written and got as far as compiling. **Redirected mid-implementation, before finishing, to NIR instead**, for three concrete reasons that hold up on inspection: (1) NIR is produced *unconditionally* by every self-hosted compile already (no need to opt every real invocation into `-Xearly-tasty-output`, which today is a deliberately separate, narrower code path); (2) NIR is flat and post-erasure -- structurally close to a JVM classfile -- so "does this top-level definition have a runnable `main`" is a direct scan over `Defn.Define`/`Sig`, not a tree walk through TASTy's pickled AST shape; (3) it ports the *exact same* heuristic `ScalinoCli.scala`'s existing classfile scanner already uses (same field names, same semantics), which is a smaller, more mechanical, more obviously-correct change than introducing a materially different (tree-shaped, Symbol-table-based) representation. The TASTy-based `ScalinoDiscoverCompiler`/`-Yscalino-discover` work was fully reverted (`Driver.scala`, `ScalaSettings.scala`, the new `fromtasty/ScalinoDiscover.scala` file all restored to their pre-attempt state, confirmed via `git diff` matching exactly the already-committed patch set) before starting the NIR version -- nothing from that path was kept.

**The fix, entirely inside `cli/ScalinoCli.scala` (no dotc/nscplugin source patch needed at all)**: `nir_native0.5_3`/`util_native0.5_3` -- scala-native's own NIR data model and binary (de)serializer, already used internally by nscplugin -- are published as real Scala Native cross-build artifacts on Maven Central (confirmed via `cs fetch`; fetched `--intransitive`, one artifact per `cs fetch` call, same convention `build/selfhost/build-lsp.sh`'s `LSP_NATIVE_JARS` already established, to avoid pulling in a conflicting second copy of javalib/nativelib/clib). Added as a new compile+link dependency of `ScalinoCli.scala`'s own build (`build/07-build-scalino.sh`'s `COMPILE_CP`/`LINK_CP`) -- this is the real, currently-shipping build script for the `scalino` CLI tool itself (which has self-hosted its *own* build since before this session, independent of whether `scalino-dotc` is GraalVM- or Scala-Native-built), so this is a real, permanent addition, not scoped to `build/selfhost/`.

Ported the classfile scanner's exact heuristics to NIR, verified empirically against real compiled output (a throwaway JVM-side inspector using the published `nir_3`/`util_3` JVM cross-builds was used to dump real `.nir` files from small hand-written examples and confirm the exact shape of each case, rather than guessing at NIR's structure from source alone):
- **Main-class detection** (`findMainClassesNir`, used by `detectMainClass` as a fallback -- see below): an object's `def main(args: Array[String]): Unit` (however it's spelled -- `@main def`, `extends App`, or written by hand, all three already lower to the identical shape before nscplugin ever sees them) compiles to a synthetic top-level `Defn.Class` (**not** the module itself -- confirmed via a real dump: `object Hello`'s module (`Hello$.nir`) has its own two-arg instance `main` with `this` as an explicit first parameter, exactly mirroring the *module's* JVM-classfile shape; the separate forwarder class (`Hello.nir`, no `$`) has a second, one-arg `main` with no receiver at all -- structurally identical to a JVM classfile's `public static void main(String[])`, since NIR method signatures always include the receiver explicitly as the first `Type.Function` argument, so "no receiver" is exactly "static"). `isNirMainDefine` checks for that one-arg `(Array[String]) => Unit` shape with sig id `"main"`.
- **Test discovery / ancestry walk** (`nirClassInfoOf`, `findClassInfo`, wired into `discoverTestClasses` and `isAssignable`): `Defn.Class`/`Defn.Module` already carry `parent: Option[Global.Top]`/`traits: Seq[Global.Top]` as real fields -- no ancestry reconstruction needed at all (simpler than the classfile version, which has to re-derive this from a constant pool). A public no-arg constructor is a same-file `Defn.Define` with `isCtor`, `!isPrivate`, and exactly one NIR-level argument (the implicit receiver -- confirmed via a real dump: a zero-arg class's own ctor has `ty.args.size == 1`, an explicit one-`Int`-arg ctor has `size == 2`).
- `findClassInfo` (replacing the old two-step `findClassBytes`+`analyzeClassFile`) checks for a local `.class` first (unconditionally correct on a GraalVM-built `scalino-dotc`, since it always writes one), falls back to a local `.nir` (the *only* format a self-hosted `scalino-dotc` ever produces), and only then falls through to jar-based `.class` lookup for real dependency jars -- which are always ordinary JVM artifacts regardless of which toolchain built `scalino-dotc`, so that half of the ancestry walk (e.g. `MySuite extends munit.FunSuite extends munit.Suite`) is completely untouched.

**Why this doesn't touch the GraalVM path at all**: every fallback is gated on "does `classesDir` have any `.class` files" (`detectMainClass`/`discoverTestClasses` both check `walkClassFiles(classesDir).nonEmpty` first) -- on a GraalVM-built `scalino-dotc`, that's always true, so the NIR branch is dead code there, byte-for-byte the same behavior as before this fix. Confirmed, not just argued: rebuilt `dist/scalino` with the change and re-ran it against the current GraalVM `dist/scalino-dotc`/`dist/scalino-linkdriver` -- identical output to before on every test below.

**Verified against the self-hosted binaries, for real, through the actual `scalino` CLI** (not by manually invoking `scalino-dotc`/`scalino-linkdriver`, which would have missed exactly this class of bug): built a scratch `dist/`-shaped directory with `scalino-dotc` swapped for `dist/scalino-dotc-selfhosted` (everything else -- `scalino-linkdriver`, `java.base.jar`, `nativelibs.cp`, `compiler.cp`, the rebuilt `scalino` CLI itself -- copied from the real `dist/`) and ran real `scalino run`/`scalino test` invocations against it:
- A hand-written multi-class/multi-file example (`object T` with `def main`, an auxiliary `class Foo(x: Int)`, an unrelated `class Bar`, plus a trait-mixing `class Impl extends Base with Greeter`) -- `scalino run` correctly found `T` as the sole entry point and ran it, producing the expected output; confirmed the compiled `classesDir` had zero `.class` files, only `.nir`, proving the NIR fallback (not some accidental `.class` presence) is what fired.
- A real `munit`-based test project (`class MySuite extends munit.FunSuite`, one real assertion) -- `scalino test .` correctly discovered `MySuite` via the NIR-based ancestry walk (chaining from the local `.nir` classesDir entry into `munit`'s real dependency-jar `.class` ancestry, exactly as designed), ran it, and reported `1 passed, 0 failed` -- identical output to the same project run against the current GraalVM `dist/scalino`.
- **`~/scala/ape`'s full real test suite** (the same 54-test, multi-package, `http4s`/`upickle`/`cats-parse`/`porcupine`-dependent project used as Phase 1's ultimate validation gate), run via `scalino test .` against the scratch self-hosted `dist/` -- **54 passed, 0 failed, 0 errored, 0 skipped**, identical to the GraalVM binary's own result on the same project. Confirmed the compiler's real output directories (`_scratch/classes`, `_scratch/test-classes`, `_scratch/probe/classes`) contained zero `.class` files throughout (the 1475 `.class` files found elsewhere under `.scalino-build/_scratch/probe/link` are harmless jar-extraction noise from the *linker*'s own working directory, not compiler output, and were never read by any of this fix's code).
- Re-ran both standing regression gates to confirm this change didn't interact with anything else: `dotc` determinism (5 consecutive `examples/Hello.scala` compiles via `scalino-dotc-selfhosted` directly, byte-identical `.nir` MD5s every time) and the LSP endpoint suite (`build/lsp-trace-drive.py` against `dist/scalino-lsp-selfhosted`, **8/8**, including the `didChange`/diagnostics check) -- both untouched by this fix (it lives entirely in `cli/ScalinoCli.scala`/`build/07-build-scalino.sh`, nothing shared with either binary), confirmed clean regardless.

**Status**: this was the one functionality gap serious enough to justify the interrupted cutover attempt's decision to stop rather than proceed -- it's now fixed, verified against real projects up to and including `ape`'s full suite, and doesn't regress the GraalVM path. The way is now open to retry the actual `build/all.sh` cutover.

## Cutover: `build/all.sh` now builds and ships self-hosted `scalino-dotc`/`scalino-lsp` (2026-09-07)

Retried the cutover with the NIR-discovery fix in place. Real `git diff --stat`/`dist/` inspection first confirmed the earlier interrupted attempt had left nothing broken: `build/all.sh` was untouched, and `dist/scalino-dotc`/`dist/scalino-lsp` were the original, working GraalVM binaries (the agent had rebuilt them back to a known-good state before it stopped).

**Missing-pieces audit, before touching anything**: traced every script `build/all.sh` calls and what `packaging/`'s scripts assume.
- `07-build-scalino.sh` (builds the `scalino` CLI) was already binary-agnostic -- it invokes whatever's at `$DIST/scalino-dotc`/`$DIST/scalino-linkdriver`, and already had the NIR-discovery fallback wired in from the prior session. No changes needed there.
- `03b-build-scalalib-retained.sh` is already a clean no-op at this project's pinned `SCALA_VERSION=3.8.4` (the published `scala-library` jar for 3.8+ ships real `.tasty` directly, so the whole recompile-with-retained-trees step it exists for is unnecessary at this version) -- unaffected either way.
- `06-package.sh`/`09-package-linux-native.sh`'s packaging layout (`/usr/lib/scalino/{scalino,scalino-dotc,scalino-linkdriver,scalino-lsp,java.base.jar,compiler.cp,nativelibs.cp,nscplugin.jar.txt,lib/,...}`) references binaries **by filename only** -- it has no dependency on which toolchain produced them, so the cutover is transparent to packaging. (Could not run a real `.deb`/`.rpm` build to confirm end-to-end -- `fpm`/`rpmbuild` aren't available on this macOS machine, that script is Linux-only by design. Confirmed instead that `dist/`'s post-cutover file layout exactly matches what the packaging scripts expect, which is the part actually affected by this change.)
- **The one real, load-bearing discovery**: `scalino-linkdriver` (the NIR→native linker, `src/LinkDriver.scala`) has never been self-hosted -- it remains a GraalVM-native-image build of scala-native's own `tools_3` library, and both `build/03-build-scalino-dotc.sh` and `build/08-build-scalino-lsp.sh` (self-hosted or not) depend on it as their link step. This was already implicit in every prior entry in this arc (see e.g. this file's very first self-hosting scoping section, and every build script's own prereq check on `$DIST/scalino-linkdriver`) but is worth stating explicitly here: **self-hosting `scalino-dotc`/`scalino-lsp` does not, by itself, remove GraalVM/native-image from this toolchain's build** -- one binary still needs it. See "What's NOT removed" below.

**Cutover implementation**: folded the content of the now-deleted `build/selfhost/build-dotc.sh`/`build-lsp.sh` (the opt-in experiment scripts from earlier in this arc) directly into `build/03-build-scalino-dotc.sh`/`build/08-build-scalino-lsp.sh` -- same recipe, just writing to the real `$DIST/scalino-dotc`/`$DIST/scalino-lsp` instead of `-selfhosted`-suffixed side files. Added the two prerequisite steps those scripts always needed but `build/all.sh` never ran on its own: `01b-build-patched-javalib.sh` (builds the scala-native-0003-patched javalib jar via a real `sbt javalib3/publishLocal`, so the URI fix actually takes effect) and `02b-gen-megaphase-overrides.sh` (generates `MiniPhaseOverrides.scala`, the build-time reflection-replacement table both binaries' source list includes). Removed `03a-patch-compiler.sh` entirely (its overlay-onto-published-jar trick -- compile 2 patched files, splice into `scala3-compiler_3.jar` -- existed only to avoid a full recompile for the GraalVM path; self-hosting compiles the *whole*, already-patched `vendor/scala3` tree from source regardless, so the same fix arrives for free and the overlay step has zero remaining consumers). `build/all.sh` now reads:
```
00b-setup-vendor.sh, 01-fetch-deps.sh, 01b-build-patched-javalib.sh,
02-build-java-base.sh, 02b-gen-megaphase-overrides.sh,
03-build-scalino-dotc.sh (self-hosted), 03b-build-scalalib-retained.sh,
04a-patch-tools.sh, 04-build-scalino-linkdriver.sh (still GraalVM),
07-build-scalino.sh, 08-build-scalino-lsp.sh (self-hosted), 06-package.sh
```
Also trimmed `05-regen-agent-config.sh` (a maintenance script, not part of `all.sh`) down to just its `scalino-linkdriver` tracing half -- the `scalino-dotc` reachability-tracing half is now dead code (self-hosted Scala Native binaries have no closed-world reflection registry to trace at all), and deleted the now-empty `agent-config/scalino-dotc/` directory it used to populate.

**Full verification gate -- run for real, from the actual cutover pipeline, not a scratch copy**: ran `03-build-scalino-dotc.sh`, `08-build-scalino-lsp.sh`, `07-build-scalino.sh`, `06-package.sh` in sequence (reusing cached `.build-work/` classpath artifacts from earlier in this arc -- all still valid, nothing about them is cutover-specific) to produce the real `dist/scalino-dotc`/`dist/scalino-lsp`/`dist/scalino`, then:
- **Determinism**: `PrivTest.scala` compiled 5x via the new `dist/scalino-dotc` directly -- byte-identical `.nir` MD5s every run.
- **`dist/scalino run examples/Hello.scala`** (real CLI, no `--main-class`) -- correct entry-point auto-detection, `hello from scalino`, exit 0.
- **`examples/interpreter-regressions`** (`--main-class Test`, since the directory also contains `examples/Hello.scala`'s `Hello` object and multi-file auto-detection correctly reported the ambiguity rather than guessing) -- exit 0, all interpreter regression checks pass (`labels=true thunk=42 tag=true ...`).
- **`examples/macro-hello`** -- fails exactly as documented, same pre-existing, self-hosting-unrelated `undefined: x.addOne ... at inlining` error, confirming no regression and no false-positive "fixed" claim.
- **`examples/upickle-enum-writer-bug`** -- exit 0, all `TipoGenerazione`/`ContestoUrbano` enum cases correctly round-trip (the upickle enum-writer fix from earlier in this project's history, still holding).
- **`~/scala/ape`'s full real test suite**, `scalino test .` through the real cutover `dist/`: **54 passed, 0 failed, 0 errored, 0 skipped** -- identical to the GraalVM baseline.
- **LSP**: `build/lsp-trace-drive.py` against the real `dist/scalino-lsp` -- **8/8**. `build/lsp-stress-ape.py` (real multi-package project, cross-file hover/definition/references, incremental edit) -- initially reported `OVERALL: FAIL` with `MissingCoreLibraryException`/cascading `NoSuchElementException`s; root-caused to a **test-setup mistake, not a real bug**: regenerating `~/scala/ape`'s `.dotty-ide.json` by hand with an explicit `src test` file list (rather than pointing `scalino setup-ide` at the whole project) skipped ape's own `project.scala`, silently dropping every real dependency (`upickle`/`cats`/`http4s`) from the generated IDE config -- `scalino setup-ide .` (matching how every other fixture in this project is set up) picks them up correctly. Re-ran with the correct setup: **`OVERALL: PASS`**, including correct cross-package `definition`/`references` resolution and correct incremental re-hover after an edit to the definition file.
- Packaging: see "Missing-pieces audit" above -- layout confirmed consistent, a real `.deb`/`.rpm` build wasn't runnable on this machine.

Every check passed. No regressions found; the LSP stress-test false alarm was resolved and is documented above so it isn't mistaken for a real finding later.

**What WAS removed** (GraalVM machinery now genuinely dead, confirmed via the audit above, not guessed at):
- `build/03a-patch-compiler.sh` (deleted -- zero remaining consumers once `scalino-dotc`/`scalino-lsp` compile the whole patched source tree directly).
- The `scalino-dotc` reachability-tracing half of `build/05-regen-agent-config.sh`, and `agent-config/scalino-dotc/` (deleted -- self-hosted Scala Native binaries have no closed-world reflection registry).
- `build/selfhost/build-dotc.sh`/`build-lsp.sh` (deleted -- their content now lives directly in `build/03-build-scalino-dotc.sh`/`build/08-build-scalino-lsp.sh`, the real pipeline; keeping both the opt-in-experiment copy and the real copy around would just be a drift risk with no upside now that the "experiment" is the default).
- The `-selfhosted`-suffixed scratch binaries in `dist/` (`scalino-dotc-selfhosted`/`scalino-lsp-selfhosted`) -- deleted, since `dist/scalino-dotc`/`dist/scalino-lsp` now *are* those binaries.
- `README.md`/`packaging/homebrew/scalino.rb` updated to describe the new build accurately (GraalVM as bootstrap-JVM-plus-linkdriver-only, not as the thing that builds `scalino-dotc`/`scalino-lsp`).

**What was NOT removed, and why "delete GraalVM native-image altogether" is not fully done yet**: `scalino-linkdriver` (`src/LinkDriver.scala`, wrapping scala-native's own `tools_3` JVM library -- NIR→LLVM IR→clang→native executable) is still built via GraalVM native-image (`build/04-build-scalino-linkdriver.sh`, unchanged), and both self-hosted binaries' own build scripts depend on the already-built `dist/scalino-linkdriver` as their link step -- there is currently no way to produce a working `scalino-dotc`/`scalino-lsp` binary at all without it. `build/04a-patch-tools.sh` (the tools_3 overlay-jar patch) and `00-env.sh`'s `require "$NATIVE_IMAGE"` check both remain live and necessary for exactly this reason. **Self-hosting `scalino-linkdriver` itself was never attempted anywhere in this arc** -- it's a genuinely new, unscoped body of work (compiling scala-native's own build/link/LLVM-codegen library, a different kind of component than dotc's frontend+nscplugin's NIR-emission backend, both of which this arc already covers) with unknown difficulty. Per this task's own instruction to stop rather than force forward on a genuinely unsafe/incomplete cutover, GraalVM native-image is **not** being uninstalled or made a hard-unavailable dependency of this toolchain -- it remains required to (re)build one binary. Self-hosting `scalino-linkdriver` -- and only then being able to truly delete GraalVM native-image from this project's toolchain -- is the natural next phase for whoever picks this up.

**Bottom line**: `scalino-dotc` and `scalino-lsp` -- the two binaries this whole arc's north star named -- are now, by default, in the real shipped `build/all.sh` pipeline, fully self-hosted on Scala Native: compiled by dotc from its own patched source, targeting Scala Native directly, zero GraalVM/native-image AOT compilation anywhere in either binary. This is verified against real, dependency-heavy third-party code (`~/scala/ape`, 54/54 tests; a real multi-package LSP session with cross-file resolution and live edits), not just synthetic fixtures. Fourteen real bugs were found, root-caused, and fixed getting here (patches `scala3-0001..0013`, `scala-native-0001..0003`, plus the NIR-based entry-point/test-discovery fix in `cli/ScalinoCli.scala`), all documented in this file. The one thing standing between this and a GraalVM-native-image-free toolchain altogether is `scalino-linkdriver` -- a real, identified, but not-yet-attempted scope item, not a vague aspiration.

## Self-hosting `scalino-linkdriver`: much more tractable than scoped -- a real, working proof of concept (2026-09-07)

Picked up exactly where the cutover entry above left off: `scalino-linkdriver` (`src/LinkDriver.scala`, wrapping scala-native's own `tools_3` -- NIR→LLVM IR→clang→native executable) was the one binary in this whole arc never attempted for self-hosting, and the last remaining thing keeping GraalVM native-image a hard requirement of this toolchain.

**The scoping assumption going in was wrong, in a good way.** The expectation was "port `tools_3`'s 116 files / ~27,128 lines from source, the same way nscplugin/nir/util were." That's not needed: **scala-native already publishes a real, genuine Scala-Native cross-build of its own `tools_3` module** -- `org.scala-native:tools_native0.5_3:0.5.12` on Maven Central, confirmed via `cs fetch` and `unzip -l` (1,779 real `.nir` files, e.g. `scala/scalanative/build/Build.nir`, `Discover.nir`, etc. -- not an empty decoy jar like `scalalib_native0.5_3` turned out to be earlier in this arc). Self-hosting `scalino-linkdriver` therefore reduces to compiling two small things against that published jar, not porting a large module from scratch:
1. This project's own **already-existing local patch to 5 `tools_3` files** (`patches/scala-native-0001-fix-native-lib-object-cache.patch` -- fixes inert object-file caching for vendored C/C++ dependency sources; currently spliced into the *published JVM* `tools_3.jar`'s bytecode by `build/04a-patch-tools.sh` for the GraalVM-built linkdriver). Compiling these 5 files fresh to NIR (via the same JVM-bootstrap-dotc-with-nscplugin-active recipe used throughout this arc) and placing that output *ahead of* the published `tools_native0.5_3.jar` on the classpath overlays the fix on top of the published jar, exactly the same "patch splice" idea `04a-patch-tools.sh` already uses for bytecode, just for NIR.
2. This project's own **`src/LinkDriver.scala`** (117 lines, `import scala.scalanative.build._`/`scala.scalanative.util.Scope` only -- no `dotty.tools` dependency at all, confirmed by grep, so it doesn't need to be part of the self-hosted-dotc source set or activate nscplugin's dotc-specific machinery; it's an independent NIR-producing compile).

Both compiled clean (2 warnings, 0 errors) against `tools_native0.5_3.jar` + the already-proven `nir_native0.5_3`/`util_native0.5_3` jars + this project's own `nativelibs.cp`.

**Bootstrap-linked successfully**: used the *current*, still-GraalVM-built `dist/scalino-linkdriver` to link the freshly-compiled `LinkDriver` NIR into a real executable -- the obvious, unavoidable chicken/egg step (something has to produce the first self-hosted linkdriver binary; after that, it can link itself and everything else). Produced a real, working Mach-O binary.

**A real, confirmed blocker hit immediately on first use, then genuinely worked around**: running the self-hosted `LinkDriver` against real input (`examples/Hello.scala`'s compiled NIR) crashed with `java.nio.file.ProviderNotFoundException` deep in `scala.scalanative.linker.ClassLoader$.fromDisk` → `VirtualDirectory$.jar` → `FileSystems.newFileSystem`. Root cause: scala-native's own linker reads `.nir` entries **directly out of jar-format classpath entries** via a real `java.nio.file.spi.FileSystemProvider` (a "jar:" zip filesystem) -- and **Scala Native's own javalib implements zero `FileSystemProvider`s** (confirmed: no `ZipFileSystemProvider`/any `FileSystemProvider` implementation anywhere in `vendor/scala-native/javalib`). This is not a rare edge case gated behind some unusual feature -- it's the *default* code path for *every* classpath entry that's a `.jar` rather than a directory, and every real Scala Native classpath (`javalib_native0.5_3.jar`, `nativelib_native0.5_3.jar`, `scala3lib_native0.5_3.jar`, etc.) is shipped as jars. Confirms and generalizes what `vendor/scala-native/project/Build.scala` itself already flags in a comment on its own (never fully working) native cross-build of `tools`: "lack of ZipFileSystemProvider required to operate on JARs" -- this is a real, upstream-acknowledged Scala Native javalib gap, not something introduced by any patch in this project.

**Workaround, not a fix, but genuinely sufficient for real use**: pre-extract every jar on the link classpath to a plain directory before invoking the self-hosted linkdriver, and pass those directory paths instead of the jar paths. `VirtualDirectory.real`'s directory-backed branch needs no `FileSystemProvider` at all, so this sidesteps the gap entirely rather than working around it partially. Verified end to end: extracted all 10 jars on `nativelibs.cp` to plain directories, re-ran the self-hosted linkdriver against `Hello.scala`'s NIR with the extracted directories on the classpath instead -- **linked clean, ran, printed `hello from scalino`**, byte-for-byte the same correct behavior as the current GraalVM-built `scalino-linkdriver` on the identical input. A real fix (a genuine `ZipFileSystemProvider` port for Scala Native's javalib) is out of scope here -- not attempted -- but is now a precisely scoped, well-understood future item rather than an unknown.

**Saved as a real, reproducible script**: `build/04b-build-scalino-linkdriver-selfhosted.sh` -- fetches the three published jars, compiles the 5 patched files + `LinkDriver.scala`, bootstrap-links via the current `dist/scalino-linkdriver`, extracts the classpath-jar workaround, and ends with a real smoke test (compiles+links+runs `Hello.scala`, asserts the exact expected output). Ran fresh end to end: clean pass. Produces `dist/scalino-linkdriver-selfhosted` -- deliberately **not** overwriting the real `dist/scalino-linkdriver` yet, and **not** wired into `build/all.sh`.

**What's left before this can replace the GraalVM-built `scalino-linkdriver` for real** (explicitly not attempted this pass -- this was a scoping-and-proof-of-concept pass, not the full integration, given the remaining work is itself substantial and deserves its own unrushed verification pass, same caution as every cutover step in this arc):
1. Wire the classpath-jar-extraction workaround into the real pipeline permanently (either extract once and cache, or extract on every build -- a real design choice, not made here).
2. Re-point `build/03-build-scalino-dotc.sh`/`build/08-build-scalino-lsp.sh`'s own link steps at the self-hosted linkdriver instead of the GraalVM one, and re-run the FULL verification gate from the cutover entry above (determinism, `~/scala/ape` 54/54, LSP 8/8 + stress test) using the self-hosted linkdriver for every link in the pipeline -- not just the one-off `Hello.scala` smoke test done here.
3. Only once that's genuinely clean: make `dist/scalino-linkdriver-selfhosted` the real `dist/scalino-linkdriver`, and only then is removing GraalVM native-image from `00-env.sh`'s `require "$NATIVE_IMAGE"`/`build/04-build-scalino-linkdriver.sh` actually safe.
4. Separately, consider whether a real `ZipFileSystemProvider` port is worth doing upstream in scala-native itself -- it would remove the extraction workaround entirely and is probably valuable beyond just this project (the gap is general, not scalino-specific).

This is a big change in outlook from the cutover entry above: self-hosting `scalino-linkdriver` looked like a large, unscoped unknown; it turns out to be a small, well-understood integration task blocked by one specific, already-diagnosed, already-worked-around javalib gap -- not a large porting effort.

## Wiring the self-hosted `scalino-linkdriver` in for real: the jar-extraction workaround got a real fix, but a second, deeper blocker stopped the cutover -- GraalVM stays for this one binary (2026-09-07)

Picked up items 1-3 from the list above: wire the self-hosted linkdriver into the real pipeline as `dist/scalino-linkdriver`, re-point `03-build-scalino-dotc.sh`/`08-build-scalino-lsp.sh` at it, and re-run the full verification gate.

**The jar-extraction workaround got upgraded to a real fix, not left as a shell-script step.** Scala Native's javalib has zero `java.nio.file.spi.FileSystemProvider` implementations (confirmed above), but it **does** implement `java.util.zip.ZipFile`/`ZipInputStream` (confirmed: real, non-stub source under `vendor/scala-native/javalib/src/main/scala/java/util/{zip,jar}/`) -- a completely different, lower-level API that doesn't go through NIO's filesystem-provider SPI at all. `src/LinkDriver.scala` now extracts any `.jar` classpath entry via `java.util.zip.ZipFile` directly (cached under the system temp dir, keyed by the jar's absolute path + size + mtime, so repeat links don't re-extract unchanged dependency jars) before ever handing scala-native's `Build` API a path -- correct on every backend (runs unconditionally, harmless on the GraalVM build too, which could already read jars fine) and, unlike the shell-script pre-extraction step from the entry above, doesn't need every future caller (real user `scalino run`/`build`/`test` classpaths included, not just this project's own build scripts) to remember to do its own pre-extraction. Verified directly: the self-hosted linkdriver linked `examples/Hello.scala`'s NIR against its **real, unmodified jar classpath** (not pre-extracted directories) and ran correctly.

**A second, much deeper blocker surfaced the moment the self-hosted linkdriver tried to link something as large as dotc's own NIR (13,053 classes / 76,995 methods) instead of a small example program: a real, reproducible `Out of heap space, grow heap` GC failure.** Investigated thoroughly, not assumed:
- **Not fixed by raising the configured max heap.** `GC_MAXIMUM_HEAP_SIZE` defaults to `SIZE_MAX` already (scala-native immix's own `UNLIMITED_HEAP_SIZE` constant) -- explicitly setting it to `14g` on this 16GB machine made no difference, same fast failure.
- **First hypothesis (concurrent `Interflow.optimize` invocations each cloning a large shared map) was wrong.** There is only ONE `Interflow` instance / one `optimize()` call for the whole program, not one per thread -- built and shipped a targeted fix (a dedicated, deliberately undersized `ExecutionContext` just for the `Interflow.optimize` call site, `ScalaNative.scala`) and confirmed via a live debug marker that it *was* being reached and running with only 1 thread -- and it crashed anyway, in the exact same way, immediately. This disproved the hypothesis outright rather than just failing to fix it: the real crash site, traced via the actual stack trace, is `scala.scalanative.linker.Reach.track`/`reachAllocation` -- and `Reach.scala` has **zero** `ExecutionContext`/`Future` usage anywhere in it (confirmed by grep) -- it is purely single-threaded already. No amount of capping concurrency touches this code path at all.
- **A global concurrency cap (`SCALANATIVE_LINK_THREADS=1`, serializing the entire link pipeline via a new env-var-gated executor size in `Build.scala`) succeeded once**, in isolation, via a direct manual invocation -- a full, real, successful link of dotc's own NIR into a working executable, ~20 minutes wall-clock (vs. ~35s under GraalVM). But **it failed again on retry through the real build script**, with the identical fast `Reach`-phase crash. This machine has real, fluctuating memory pressure from ordinary desktop use (browser, Slack, etc. -- confirmed via `vm_stat`: ~6.5-6.9GB free at the time of the failing retries) -- the one success was very likely favorable timing against that contention, not a genuine, reliable fix. `Reach`'s own sequential reachability walk over ~77K methods appears to need more peak memory than is reliably available here, independent of concurrency.
- **Root cause, to the extent it was pinned down**: `Reach.process()`'s sequential, single-threaded walk building its own `tracked: HashMap[Global, ...]` over every reachable global in a program the size of dotc's own compiler is genuinely memory-heavy, and Scala Native's Immix GC does not handle this gracefully on a machine without several free GB of headroom at that exact moment -- whether this is "just needs more RAM" or a real, fixable GC/allocator inefficiency (fragmentation under load, over-eager retention, something else) was not conclusively determined; that would need real GC-level profiling, out of scope for this pass.

**Given this genuinely isn't a quick fix, and per this whole arc's own standing rule (stop and document rather than force forward past a real problem), all linkdriver-self-hosting changes were reverted** back to the state from before this pass: `src/LinkDriver.scala`, `build/00-env.sh`, `vendor/scala-native`'s `tools_3/build/{Build,ScalaNative}.scala`, `build/04-build-scalino-linkdriver.sh`, `build/08-build-scalino-lsp.sh`'s link step, and the README/packaging text describing `scalino-linkdriver` as self-hosted. One real mistake caught and fixed during the revert: `git checkout -- <file>` inside the `vendor/scala-native` clone reverts all the way to the pristine upstream commit, not just this pass's edits -- it silently undid part of the already-existing, legitimate `patches/scala-native-0001` (the object-file-caching fix) too, which had to be re-applied from the patch file directly (`git apply` on an isolated single-file extract of the patch) before rebuilding.

**Full recovery verified, not just assumed**: rebuilt the GraalVM-based `scalino-linkdriver` from scratch via the restored `04a-patch-tools.sh`/`04-build-scalino-linkdriver.sh` (confirmed via `strings`: real `com.oracle.svm.core.VM.Java.Version` marker present, correct ~44MB size, matching the pre-this-pass binary exactly), then rebuilt `dist/scalino-dotc` (35s total, zero memory issues -- confirms this specific memory constraint is Scala-Native-linker-specific, not something dotc's own size would trouble a JVM-based linker with at all), `dist/lib/scalalib-retained.jar`, `dist/scalino`, and `dist/scalino-lsp` (full `lsp-trace-drive.py`, 8/8 endpoints) -- all clean. Re-ran dotc's determinism check (5x `examples/Hello.scala` compile, byte-identical MD5s) to confirm the unrelated `PlainFile`/`String.intern()` fix from earlier in this arc is completely unaffected. Ran `06-package.sh` successfully.

**Where this leaves the north star**: `scalino-dotc`/`scalino-lsp` remain fully self-hosted on Scala Native (unchanged, unaffected by any of this -- they were always *linked by* GraalVM's `scalino-linkdriver`, never self-linking). `scalino-linkdriver` itself is **not** self-hosted -- GraalVM native-image is still a real, required build dependency for this one binary, and **cannot yet be removed from this project**. The proof-of-concept from the entry above (self-linking, linking small real programs like `Hello.scala` with a real jar classpath) remains valid and unchanged -- the blocker is specifically about linking something as large as dotc's own compiler, not about self-hosting the linker as a concept. A future attempt at this should assume: (a) the jar-extraction fix in `src/LinkDriver.scala` is real and reusable (though currently sitting only in this findings entry, not in the working tree -- it was reverted along with everything else, since landing it alone with no way to exercise/verify it end-to-end wasn't worth the risk of a half-integrated, untested change); (b) the actual blocker is `Reach`'s own memory usage at dotc's scale, not concurrency, not GC config, not the jar/FileSystemProvider gap; (c) testing on a machine with more reliably-free memory (a CI runner rather than an interactively-used development laptop) is the most promising next step before assuming a real scala-native-level fix is needed.

## Digging into `Reach`'s memory profile with real numbers: not one fixable allocation, a genuine structural GC/collections overhead that follows the workload across every stage (2026-09-07, later same day)

Picked up exactly where the entry above left off, per direct instruction to actually profile and reduce the self-hosted linkdriver's memory footprint on dotc's own scale (~77K reachable methods) rather than just retry with more RAM. Reused the already-reverted-to-clean state; nothing landed from the prior pass. All numbers below are real, measured via `/usr/bin/time -l` (macOS), not estimated.

**Baseline, for comparison: GraalVM's `scalino-linkdriver` linking dotc's own real NIR output** (`.build-work/selfhost/nir-out`, the actual output of a real `build/03-build-scalino-dotc.sh` run) end to end, same input used for every test below: **1.77 GB peak RSS, 35.6s wall-clock, exit 0.** This is the number the self-hosted linker has to compete with.

**Self-hosted linkdriver, default config (`Mode.Debug`, `optimize = true` -- `NativeConfig.default` sets `optimize = true` unconditionally, independent of `Mode`, confirmed by reading `NativeConfig.scala`), same dotc NIR input, real jar-classpath-extraction workaround from the entry above (pre-extracted directories, no `FileSystemProvider` needed)**: crashes with a real, literal `[ScalaNative GC|Error] Out of heap space grow heap` at **3.69 GB peak RSS**, ~99s in, right after `Discovered 13053 classes and 76995 methods after classloading` and `Checking intermediate code (quick)` complete. The real stack trace (this session's first actual captured trace, not inferred) pins the crash precisely: `Interflow.result()` -- `originals.clone()` (`interflow/Interflow.scala:184`), a full clone of a `mutable.Map[nir.Global, nir.Defn]` built from `analysis.defns` (one entry per reachable global -- tens of thousands) -- running inside the single `Future { Interflow.optimize(...) }` wrapper (`build/ScalaNative.scala:53-72`) on a real `ThreadPoolExecutor` worker thread. **This directly refines, and partly corrects, the previous entry's read of the crash site**: that entry's own stack trace pointed at `Reach.track`/`reachAllocation`; this run's trace points at `Interflow.result`'s map clone instead -- both are real (see below), and the discrepancy itself is a finding: which specific allocation fails is apparently timing/memory-pressure-dependent, not a single fixed line.

**Hypothesis and test: is `Interflow`'s map clone the (a fixable) root cause, or just the first thing that happens to run out of room?** Built a real, minimal test: a copy of `src/LinkDriver.scala` with one line added, `.withOptimize(false)` (a real, already-existing `NativeConfig` flag, no scala-native source patch needed), compiled via the exact same JVM-bootstrap-dotc-with-nscplugin recipe used throughout this arc, bootstrap-linked via the reliable GraalVM `scalino-linkdriver` (avoiding the chicken/egg problem), and smoke-tested against `Hello.scala` first (linked and ran correctly, `hello from scalino`, confirming the modified tool is itself sound) before pointing it at dotc's real NIR output.

**Result: the hypothesis was half right.** With `optimize = false`, the log shows `Optimizing skipped` -- `Interflow` never runs at all -- and the process genuinely gets **past** the exact point where the `optimize = true` run crashed. But it does NOT succeed: it crashes again, later, at **6.12 GB peak RSS** (worse, not better -- more real work got done first), this time with the identical `Out of heap space` GC error inside `scala.scalanative.codegen.Lower.onDefns` -> `shouldGenerateStackOverflowChecks` -> `ReachabilityAnalysis.Result.references.isReachable`/`isSelfRecursive` -- genuinely running concurrently this time (`codegen/llvm/CodeGen.scala` wraps each compilation unit in its own `Future`, confirmed via `scala.concurrent.Future$.apply` in the trace, and `user` time of 243s against 90s real time shows real multi-core parallelism, unlike the misleading appearance of concurrency in the first crash). One more real, structural data point: **"peak memory footprint" (a separate macOS metric from RSS) was 17.19 GB in BOTH crashes, to the byte** (`17192584832`) -- almost certainly Immix GC's fixed upfront virtual-address-space reservation, not a measurement of real usage; RSS is the number that actually matters here, and it's genuinely rising with real physical/compressed memory pressure (page reclaims went from 1.9M to 7.3M between the two runs).

**Conclusion: this is not a single fixable allocation site. It's a real, generalized memory-overhead gap between Scala Native's own GC/mutable-collections implementation and the JVM's, for maps and sets keyed by tens of thousands of `nir.Global` entries, that recurs independently across multiple pipeline stages** -- `Reach.process()` itself holds at least nine separate `mutable.Map`/`mutable.Set`/`mutable.UnrolledBuffer` structures of comparable scale simultaneously (`done`, `infos`, `from`, `unreachable`, `unsupported`, `enqueued`, `links`, `dyncandidates`, `dynsigs`, confirmed by reading `linker/Reach.scala` directly), `Interflow` separately builds and then fully clones its own `originals` map of the same scale, and `codegen.Lower`'s per-unit reachability checks query the same analysis result concurrently across every compilation unit. Patching or skipping any ONE of these (as the `optimize = false` experiment did for `Interflow`) doesn't fix the underlying problem -- it just relocates the failure to whichever large-map-holding stage runs next. GraalVM's own JVM handles the identical data at under 2 GB; this toolchain needs 3-4x that and counting, and still doesn't reliably finish. This matches and sharpens, rather than contradicts, the prior entry's "real structural ceiling" characterization -- now with two independently reproduced, precisely-traced crash sites as evidence that it's systemic, not localized to `Reach.scala` specifically.

**What a real fix would actually require, none of it attempted here (correctly out of scope for a session-level patch)**: (a) reducing Scala Native's own `mutable.HashMap`/GC per-entry memory overhead at the runtime/collections level -- a scala-native-core change, not a linker-level patch; (b) restructuring the linker pipeline to stream or release intermediate large maps between stages instead of holding several ~77K-entry structures live at once -- a real upstream architectural change to `Reach`/`Interflow`/`Lower`'s own data flow; (c) running on a machine with substantially more reliably-free RAM than this interactive dev laptop's ~2 GB baseline free (the previously-suggested next step, now with real numbers backing exactly how much more is likely needed: comfortably clearing 6+ GB just to reach the SECOND crash site, with no guarantee that's the last one).

**`.withOptimize(false)` is a real, verified, generally-useful partial mitigation, not adopted as a default.** It measurably helps (delays the crash, lets more real work complete) and is trivial to apply (an existing config flag, zero source patches) -- worth keeping in mind for anyone attempting this again on a more memory-constrained machine, as a way to buy headroom for the OTHER stages. It is deliberately NOT wired into `src/LinkDriver.scala`'s real default behavior here: disabling NIR-level optimization changes real output-binary characteristics (size, runtime speed) for every user of `scalino-linkdriver`, not just this project's own self-hosting bootstrap, and it doesn't fully solve the problem regardless (per the second crash above) -- landing a partial, unverified-as-sufficient behavior change to a real user-facing tool isn't warranted here.

**Where this leaves the north star, unchanged from the entry above**: `scalino-dotc`/`scalino-lsp` remain fully self-hosted and working (GraalVM-linked, as always). `scalino-linkdriver` remains GraalVM native-image; GraalVM native-image remains a required build dependency of this project and has **not** been removed. Nothing was committed; no source files were modified in the working tree by this pass (all experimentation happened in `/tmp`).
