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

## JVM-free language server (LSP)

Goal: a Metals-equivalent for editors like Zed, without a JVM at runtime.
Metals itself doesn't work here — it's an LSP *client architecture*
(BSP + semanticdb + `mtags-interfaces`) built to bridge Scala into arbitrary
JVM build tools, none of which this toolchain needs (it already owns
compile/link/deps end to end via `scli`). What's actually needed is much
smaller: dotc's own `interactive`/`InteractiveDriver` machinery, wired to a
stdio LSP server.

That server already existed: `vendor/scala3/language-server/` is dotty's own
pre-Metals LSP implementation (`DottyLanguageServer.scala`, ~1000 lines, on
`org.eclipse.lsp4j`), superseded and unmaintained once Metals took over, but
functionally complete — `didOpen`/`hover`/`definition`/`completion`/
`references`/`rename`/`documentSymbol`/workspace `symbol`/`implementation`/
`signatureHelp`, all thin wrappers over `InteractiveDriver`. `dist/dotty-lsp-native`
(`build/08-build-lsp-native.sh`) native-images a trimmed version of it —
`patches/scala3-0002-trim-language-server.patch` drops `worksheet/` (spawns
a forked JVM REPL subprocess — genuinely JVM-shaped, out of scope) and
`decompiler/` (TASTy-decompile-on-request, not core LSP). Unlike
`dotc-native`, this module was never published as a jar (dead since ~2019),
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
   native-image**, exactly like `dotc-native`'s own compiles already do
   (`docs/findings.md` "Blocker 1") — under a real JVM, dotc happily
   resolves `java.lang.Object` etc. from the host JVM's own modules with no
   flag needed, so this was invisible testing JVM-mode first; under
   native-image there are no real JDK modules at runtime, so
   `Definitions.init` fails opaquely (`asTerm called on not-a-Term val
   <none>`) without it. Not a code fix — a requirement on whatever
   generates a project's `.dotty-ide.json` (see below).

**Verified working, real native binary, zero JVM at runtime** (checked via
process-tree inspection while running): a hand-written `.dotty-ide.json`
fixture pointing `dist/dotty-lsp-native -stdio` at `examples/Hello.scala`
gets a correct `initialize` response, correct (empty) diagnostics on
`didOpen`, real Scaladoc-sourced hover text for `println`, and a correct
compiler type-error diagnostic (`Found: String, Required: Int`, right
range) after a `didChange` introducing a real type error. Also confirmed:
the real stdlib jar is `org.scala-lang:scala-library` (no `_3` suffix) —
`scala3-library_3` is, as of the unified 2.13/3.x versioning line (3.8.x+),
a 319-byte relocation stub on Maven Central, not real classfiles (same
"decoy artifact" shape as `scalalib_native0.5_3`, documented above, but for
an unrelated reason).

Done since: `scli setup-ide <sources...>` (`cli/Scli.scala`) generates
`.dotty-ide.json` (including `-javabootclasspath`) by reusing
`buildBinary`'s own classpath/directive-parsing plumbing, instead of
hand-writing it; and [`zed-extension/`](../zed-extension/) is a real Zed
extension (modeled on
[metals-zed](https://github.com/scalameta/metals-zed)) registering
`dotty-lsp-native` as a second server on Zed's `Scala` language — its own
`README.md` has install steps. Compiles clean (`cargo check`) against
`zed_extension_api` 0.7.0; not yet built to wasm or run inside real Zed (no
`rustup`/`wasm32-wasip2` target in this environment) — first thing to check
if it doesn't load is `dev: open language server logs` in Zed.

Not yet done: deeper verification of `completion`/`references`/`rename`/
workspace `symbol` (they reuse the same now-verified
`InteractiveDriver`/dispatch plumbing as `hover`/diagnostics, so should
work, but aren't individually exercised yet).

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
   above for what's implemented (including watch mode, directory/CLI-flag
   parity with scala-cli as of the "closing the gap" pass, and incremental
   compilation -- see item 7). Still missing: multi-module/multi-target
   projects, a real (not heuristic-text-scan) entry-point detector, and
   scala-cli's `fmt`/`repl`/`package`/`publish`/`bsp`/`export` commands --
   none of those have an obvious JVM-free equivalent yet (no scalafmt, no
   bloop), so they currently just print "not implemented" rather than being
   faked.

   `scli test` (2026-09-04) *is* now implemented, with no JVM anywhere in
   the chain -- see the doc comment above `readAllBytes`/`ClassInfo` in
   `cli/Scli.scala` for the full design. In short: real (JVM) sbt-scala-native
   drives test execution from the sbt/JVM side over a ComRunner socket
   protocol; `scli` instead (1) structurally scans the resolved test
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
   no bridging work at all on `scli`'s end: scala-native's native test-port
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
   now compiles, links, and passes via `scli test` end to end -- see
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
   `scli test` pipeline (`dotc-native`), so not an artifact of either path.
   Repro: `//> using dep "com.lihaoyi::utest::0.8.4"` +
   `object T extends utest.TestSuite { val tests = utest.Tests { test("x")
   { assert(1 == 1) } } }`, `scli test .`.

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
6. `bin/snc`'s "first source file's basename is the main class" convention is
   still naive (unlike `scli`, which auto-detects) — for multi-file macro
   examples via `bin/snc` directly, the entry-point file must be listed
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

   Instead, `compileToClasses`/`buildBinary` (`cli/Scli.scala`) reconstruct
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
   Only that closure is handed to `dotc-native`; the classes directory is no
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
   (compile classpath, scalac options, `dotc-native`'s own mtime) forces a
   full rebuild whenever any of those change, since none of them are
   tracked per-file. `--no-incremental` forces a full rebuild on demand.
   Known gap: the declaration scan is top-level-only (by regex, not real
   parsing), so a name declared only inside a nested object has no edges
   in the graph -- the file containing it still recompiles correctly on
   its own changes, the gap is only in rippling to files that reference
   that nested name specifically.
