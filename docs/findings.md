# Findings from the self-hosting journey

Background for the comments in `build/*.sh`. Scala 3 only.

Note: the sections through "Confirmed working: full pipeline, zero JVM at
runtime" and "JVM-free language server (LSP)" describe the original packaging
(before 2026-09-07), where every shipped binary (`scalino-dotc`,
`scalino-linkdriver`, `scalino-lsp`) was a GraalVM native-image build of
published, unmodified JVM bytecode. That is superseded: from the "Self-hosting"
sections (2026-09-07 onward, `scalino-linkdriver` last on 2026-09-10) the whole
toolchain is dotc compiled to Scala Native, with no GraalVM in any shipped
binary. The blockers and fixes below are kept as history. The underlying
constraints (no `jrt:/`, no dynamic class loading, no reflection-heavy JSON
libs) apply to any closed-world AOT binary without a JVM install, so they carry
over unchanged.

## Decisions made

- **Packaging strategy: GraalVM native-image initially**, not full
  self-hosting (compiling dotc itself to Scala Native). Self-hosting was first
  ruled out as "multi-year/high-risk given dotty's deep ties to the JVM (zinc,
  java.nio, reflection)". That estimate was wrong: "Toward self-hosting
  scalino-dotc/scalino-lsp on scala-native" revises it to "many months" after
  file:line-level investigation, and "Self-hosting, in progress" covers the
  fixes through full self-hosting of every shipped binary. native-image needed
  a JVM only at build time; the same holds for self-hosting (a JVM still runs
  dotc and code-generation tools at scalino's own build time, but the shipped
  binaries need no JVM).
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
metadata for the plugin's classes was captured via the native-image tracing
agent (`-agentlib:native-image-agent=...`) and checked into
`agent-config/dotc/`. (The native-image build, the tracing script and `agent-config/` are gone; see
the "Self-hosting" sections. The self-hosted `scalino-dotc` still bakes in
`nscplugin` for the same reason, with no reflection tracing, since it never
calls `Class.forName`.)

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

Using the wrong one gives failures far from the cause (missing symbols during
linking, or `UndefinedBehaviorError`). `build/01-fetch-deps.sh` documents the
correct coordinates.

## Confirmed working: full pipeline, zero JVM at runtime

(Historical: the original native-image-built pipeline.)

`scalino-dotc` (dotc + nscplugin baked in) compiles `.scala` → `.nir`, and a
second native-image binary (`scalino-linkdriver`, wrapping scala-native's
`tools_3` link API) drives clang to turn `.nir` into a native executable —
both steps using standalone binaries with no `java` process.
`bin/scalino-bootstrap` wires the two into one CLI.

Default JVM bytecode output (GenBCode, plain `.class` files from
`scalino-dotc`) hits a `VerifyError` in this setup, likely from the
jimage-extracted `java.base.jar`. Not fixed: scala-native bypasses GenBCode
(NIR is generated from typed trees), so it matters only if this binary should
also emit JVM `.class` files.

## Macro execution: own-implementation tree interpreter

The upstream `tasty-interpreter` project (referenced in the original brief)
turned out to be incomplete/unmaintained, so we wrote our own from scratch:
`compiler/src/dotty/tools/dotc/quoted/Interpreter.scala` in `vendor/scala3`,
patched via `patches/0001-own-implementation-tasty-interpreter.patch`
(applied by `build/00b-setup-vendor.sh`, baked into `scalino-dotc` by
`build/03a-patch-compiler.sh`).

**How upstream does it, and why that fails in a closed-world AOT binary:** dotc expands `inline def foo = ${ fooImpl }` by reflectively
loading `fooImpl`'s already-compiled classfile and having the real JVM
execute the real bytecode (`java.lang.reflect.Method.invoke` off a
`URLClassLoader` built from `-classpath`). Tree-walking was only ever used to
evaluate the small expressions that become that reflective call's
*arguments*. Neither GraalVM native-image nor a self-hosted Scala Native binary can do
this: both are closed-world AOT and cannot load a class that was not part of
the build, and a user's macro implementation is unknown until the compilation
run. No reflection config fixes it, so the reflective call has to go.

**Design:** recursively interpret the callee's own body from its typed tree
(`Symbol.defTree`), walking arbitrarily deep into the macro's call graph,
with two reflection-free escape hatches:
- a curated set of primitive/stdlib intrinsics (arithmetic, `String`,
  `Seq`/collections basics) implemented natively in Scala, and
- the `scala.quoted`/`Quotes` API surface, called as ordinary direct Scala
  method calls into `QuotesImpl`/`ExprImpl` — not reflection, since those
  classes are already statically linked into this compiler binary and the
  AOT build sees every such call site at build time like any other code.

**Cross-module macros work.** `Symbol.defTree` is only populated for symbols
whose tree was retained (`-Yretain-trees`). The flag also retains trees for
symbols unpickled from a dependency's `.tasty`, because TASTy always pickles
full method bodies. So a macro compiled in one
`dotc` invocation and used from a separate one (the normal real-world shape)
interprets exactly like a same-run macro, as long as **both** invocations
pass `-Yretain-trees` (`bin/scalino-bootstrap` always does). Verified against
`interpreter/test-fixtures/{i4515,i4515b,inline-varargs-1,tasty-getfile}` —
real upstream tests, originally `Macro_1.scala`/`Test_2.scala`-style separate
compilations.

**JDK classes: bodies from scala-native's javalib TASTy (2026-10-20,
`patches/scala3-0036`).** `java.*` classes are Java-defined as far as dotc is
concerned (the `java.base.jar` stub), so there is no TASTy to unpickle and the
old answer was one hand-written intrinsic per method. scala-native's javalib
*is* Scala, and its sbt build produces a `.tasty` per class -- it just drops
them from the published jar (`NIROnlySettings`). `build/01b-build-patched-
javalib.sh` now packs them into `dist/lib/javalib-tasty.jar` (3 MB, ~790 files),
and `cli/ScalinoCli.scala` passes `-Xmacro-settings:javalibTasty=<jar>`.
`JavalibOverlay` (new, `quoted/`) maps a Java-defined symbol to the same
(class, name, erased params) member of the javalib class and the interpreter
runs that body. Design points:
- **The jar must NOT go on the classpath.** dotc then loads javalib's
  `Throwable.tasty` etc. in place of the JDK classes. Hence the setting.
- javalib classes have the *same names* as the JDK ones, so unpickling them
  normally would replace the JDK symbols in their package scope. Files are
  unpickled **detached** (`TreeUnpickler#enterDetached`, same patch): shadow
  symbols, never entered in a package, no companion linking. Qualified
  references inside javalib TASTy resolve to *other shadow classes* (loaded on
  demand) -- needed because javalib classes call javalib-only helpers
  (`AbstractList#checkIndexOnBounds`, `URIEncoderDecoder`) that the JDK stub
  lacks. `java.lang._Enum`/`_String`/... (javalib's names for compiler-special
  classes) resolve to the JDK class.
- Two-stage loading: *entering* a file (symbols) on reference, *indexing* it
  (trees) only when a body is needed -- eagerly reading every referenced file
  broke on unrelated ones (`Collector`, via `Optional`).
- `java.lang.*` stays host-native (String, boxes, Math, Throwable, ...): the
  interpreter represents them by real host objects, javalib's `_String` wraps
  a char array. Those are covered by `HostValueCalls` (whole `String` API by
  name + runtime arg types, numeric conversions, `new String(...)`), not one
  overload at a time.
- Also fixed on the way: `return` inside a *local* def escaped as
  `LabeledReturn`, and `field = ...` written unqualified inside an interpreted
  class was "unexpected tree".
Verified on the JVM fast loop only (`examples/interpreter-regressions`
`javalib=true`; upstream fixtures unchanged): StringJoiner, Optional, UUID,
java.net.URI. **Not yet verified: a native `scalino-dotc` rebuild, `01b` end to
end, the real `scalino` CLI.** Known open: `java.util.Base64` (a different
stdlib gap, and its failure mode differs with `SCALINO_INTERP_DEBUG`), and the
host-vs-interpreted object rule -- an interpreted javalib `ArrayList` passed to
code expecting a host one (or the reverse) is the same hybrid hazard as
patch 0035's HashSet.

**Test fixtures**: `interpreter/test-fixtures/` holds real macro tests
harvested from `vendor/scala3/tests/{run-macros,pos-macros}`. Of the curated
set: 9 pass correctly end-to-end (including through the actual `scalino-dotc`
+ `scalino-linkdriver` binaries, not just a JVM test harness — see
`examples/macro-hello/`; this now includes `i7715`/`i8746`, see "General
quote-pattern matching" below), and 1 (`i10863`) partially passes (resolves
the right value but `.show` renders through dotc's own pretty-printer rather
than the exact printer real macros get, so output differs in detail).

**Known gaps** (see the `Interpreter` class doc comment for specifics):
- ~~Structural quote-*type* patterns~~ — done (patch 0028, 2026-10-07):
  `case '[List[t]]`, `'[h *: tl]`, bounded type vars etc. match via a subtype
  check with GADT-constrained pattern variables, as stock `QuoteMatcher` does.
  Fixture `interpreter/test-fixtures/quote-type-structural`. Same patch also
  fills in much of the `quotes.reflect` surface jsoniter-scala's
  `JsonCodecMaker.make` needs; verified against stock-JVM output on a
  35-field case class (collections, enums, ADTs, tuples, Either, BigDecimal,
  java.time, recursion, >32 fields). `readFromString` on Scala Native is still
  blocked by the separate `lub(unit, bool)` issue below.
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

Goal: scala-cli-like ergonomics (single command, dependency resolution,
caching, watch mode) without a JVM. scala-cli itself is not reused because it
is JVM-coupled (coursier-as-library, bloop, zinc). The one JVM-free piece
reused is coursier's `cs`, shipped as a prebuilt GraalVM native-image
launcher and invoked for dependency resolution. Directives, an
incremental-compile cache and watch mode are hand-rolled instead of porting
zinc/BSP.

### `scalino`: implemented, self-hosted

`cli/ScalinoCli.scala` is a mini scala-cli-style CLI (`scalino run`/`scalino compile`),
built by `build/07-build-scalino.sh`. It is compiled by
`dist/scalino-dotc` + `dist/scalino-linkdriver` once those exist, not by a JVM,
so the build tool is a product of the compiler it drives.

What it does:
- Parses `//> using dep "..."` in all three scala-cli coordinate formats
  (one per line, anywhere in any given source file):
  - `org:name:version` (sbt `%`, exact artifact) — unchanged
  - `org::name:version` (sbt `%%`, Scala-version cross) — `org:name_3:version`
  - `org::name::version` (sbt `%%%`, platform+Scala cross) —
    `org:name_native<binVer>_3:version` (`<binVer>` = scala-native's
    major.minor, e.g. `0.5`, baked into `BuildInfo` from `versions.env`)

  `//> using scala "x"` is parsed and warns (no error) if it differs from the
  toolchain's pinned version. There is no multi-version support.
- Resolves dependencies via a native `cs fetch --classpath` subprocess,
  cached on disk (`.scalino-build/deps-cache/`) keyed by the sorted dependency
  list, so repeat runs skip resolution and its network round-trip (`cs` caches
  artifacts itself). Excludes scala-native's own runtime artifacts
  (`nativelib_native<v>_3`, `javalib_native<v>_3`, etc., see
  `excludedArtifacts`) and plain `scala3-library_3`/`scala-library`: a
  `::name::version` dependency is cross-compiled against some scala-native
  release (rarely our exact patch version), and its transitive copies collide
  with the ones we supply (`dist/nativelibs.cp`), giving hundreds of `clang`
  duplicate-symbol errors at link time (two copies of the GC, libc shims, ...).
- Detects the entry point with a text scan (`@main def`,
  `object X extends App`, or `def main(args: Array[String])` inside an
  `object` block, tracked by naive brace-depth counting), not via the
  compiler. A `{`/`}` inside a string or comment throws off the depth counter.
  Ambiguous or missing entry points are an error asking for `--main-class`.
  This also removes `bin/scalino-bootstrap`'s "first file's basename"
  limitation: source file order no longer matters.
- Drives `scalino-dotc`/`scalino-linkdriver` directly (reading the same
  `dist/*.cp` manifests `bin/scalino-bootstrap` uses), with the resolved dependency
  classpath folded in, into the same persistent `.scalino-build/<mainClass>/`
  caching layout `bin/scalino-bootstrap` uses (see the C-object-cache fix above).

Verified against `examples/Hello.scala` (plain), `examples/macro-hello/`
(real macro; `Foo.scala Test.scala` and `Test.scala Foo.scala` both detect
`Test` as the entry point), the ambiguous/missing-entry-point error paths, and
all three dependency formats in one resolution (`scala3-interfaces` exact,
`com.lihaoyi::pprint` cross-Scala, `com.lihaoyi::os-lib::0.11.7`
cross-platform). The last was linked and called: `println(os.pwd)` (POSIX
`getcwd` via a third-party scala-native library) printed the correct working
directory.

### `scalino`: closing the gap with scala-cli's CLI surface

Second pass on the command line itself:

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
  file mtimes every 500ms instead of `java.nio.file.WatchService`, whose
  support in this toolchain's javalib port is unverified. Build/link/resolve
  failures in a watch iteration are caught and reported without ending the
  loop (`BuildFailed` in `ScalinoCli.scala`); a bad CLI invocation exits
  before the loop starts.
- `scalino version` and `scalino --help`/`-h`.
- Unimplemented scala-cli commands (`test`, `fmt`, `repl`,
  `package`, `publish`, `publish-local`, `clean`, `bsp`, `export`,
  `doctor`, `setup-ide`, `install-completions`, `dependency-update`,
  `shebang`) print a "not implemented" message naming what is supported,
  instead of being misparsed as a source file.

Verified: implicit-run, `run` on a directory (aggregates every `.scala` file
and applies entry-point detection across all of them), `-O` passthrough to
`scalino-dotc`, the `mainClass` directive, the `-d` flag (resolution failure
gives the same error as a directive-declared dependency), and a full watch
cycle (initial build/run, edit, detect, rebuild, rerun, same process).

**Known limitation (scala-native, not this tool)**: a resolved dependency jar
typechecks but only links if it is cross-published for scala-native. A
JVM-only `org::name:version` dependency (e.g. `org.typelevel::cats-core`)
resolves and typechecks but has no `.nir` bodies, so calling into it fails at
link with unreachable symbols, as under scala-cli `--native`.

Not implemented: incremental Scala compilation (every build recompiles all
sources; only the native-library object cache and the dependency-resolution
cache are incremental), multi-module projects, and
`--compiler-plugin`/`//> using plugin`. Watch mode and `//> using file`/
`exclude` are implemented (below).

**`--compiler-plugin`/`-P`/`//> using plugin`: skipped, not a CLI-level gap.**
scala-cli loads a user plugin jar into dotc reflectively at runtime. This
toolchain's dotc is a closed-world AOT binary (originally native-image, now
self-hosted Scala Native; same constraint), so `Class.forName` only succeeds
for classes resident at build time (see "Blocker 2"; same as the shipped
`nscplugin`). No `-Xplugin:<jar picked at runtime>` can load a class that
`scalino-dotc` did not see at build time. Supporting it needs an alternative
to reflective loading (e.g. a fixed registry of plugins baked in at build time,
`kind-projector` included). Future work.

### `scalino`: second CLI-parity pass -- directive/flag aliases, jars, repositories (2026-09-06)

More scala-cli directive/flag gaps found by comparison with scala-cli's CLI,
each verified with a real end-to-end build:

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
  directive: extra Maven repositories passed to `cs fetch` as repeated
  `-r <repo>` (`resolveDeps` in `ScalinoCli.scala`), on top of Central. The
  classpath cache key includes the repository list, so changing repositories
  invalidates a stale cached classpath. Verified with a non-Central artifact
  (`com.github.jitpack:gradle-simple:1.1`, only on `https://jitpack.io`):
  resolution fails without `--repository`, and resolve/compile/link/run
  succeeds with `--repository https://jitpack.io` or the equivalent directive.
- `//> using file "./Other.scala"` / `files` (extra source, resolved relative
  to the declaring file's directory, not cwd) and `//> using exclude "glob"`
  (drops matching sources; glob matched against each source's path relative
  to cwd), implemented in `expandSources` (`ScalinoCli.scala`) so its three
  call sites (`run`/`compile`, `test`, `setup-ide`) get both. One level deep:
  a `file`-referenced source's own `file`/`exclude` directives are not
  expanded, as in scala-cli. The glob matcher is a hand-rolled
  `*`/`**`/`?`-to-regex translator (~15 lines), not
  `java.nio.file.FileSystem#getPathMatcher("glob:...")`, whose support in the
  javalib port is unverified. Bug found in end-to-end testing:
  `collectScalaFiles` produces paths like `./generated/Broken.scala` (literal
  `.` element from a directory arg of `.`), and `Path#relativize` does not
  strip it, so the relative string was `./generated/Broken.scala` and every
  exclude glob matched nothing. Fixed by `.normalize()`-ing both sides before
  relativizing. Verified: a `generated/` subtree containing a file that does
  not parse is included (and fails the build) without `exclude`, and skipped
  with `//> using exclude "generated/**"` in a sibling source.
- `--args-file <path>`: forwarded to `scalino-dotc` as `@<path>`, not parsed
  by `scalino`. dotc already expands any `@file` arg
  (`CommandLineParser.expandArg`/`CliCommand.scala:47`: one option per line,
  `#` line comments, quoting via `CommandLineParser.tokenize`). Verified with
  an args-file containing `-Xfatal-warnings` (dotc's "deprecated alias, use
  -Werror" warning became an error, so the line reached dotc's parser) and a
  clean file with `-explain` and a `#` comment.
- `--watching`/`--watching-path <path>` (repeatable; file or directory,
  recursive, any extension, not `.scala`-filtered, since it covers resource
  files a build depends on but does not compile) adds to `watchLoop`'s
  mtime-polled path set. Verified with a background
  `scalino run -w --watching-path <dir>`: editing a non-`.scala` file under the
  directory triggered "change detected, rebuilding..." and a rerun.

**Not implemented: `--restart`/`--revolver`** (background run,
kill-and-restart on change). It needs background process lifecycle management
(spawn detached, track the child, kill and respawn) on top of
`runInherited`'s spawn-and-block model, and the scala-native javalib port is
not verified to support `Process#destroy`. (Later implemented as `run -w`
restart; see "`run -w` restarts the program".)

### `bin/scalino-bootstrap` created a fresh tmp dir every build — first real bug found

Every dependency `.c` file (scala-native's vendored runtime, ~160 files)
recompiled on every build, even with no source changes. Two causes:

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
   `build/04a-patch-tools.sh`, same splice approach as the dotc patch): the
   hash-file path is now an explicit parameter through
   `LLVM.compile`/`needsCompiling`/`Build.userConfigHasChanged`/
   `dumpUserConfigHash`, and `compileNativeLibrary` has its own hash file
   scoped to the library's `destPath`, computed from and compared against its
   own `projConfig`. Other call sites keep the top-level file
   (`Build.defaultUserConfigHashPath`). The change can only make the cache
   more accurate: the old behavior was to almost always recompile, never to
   wrongly skip. Verified: editing `examples/Hello.scala` mid-build gives the
   new output, and a warm rebuild went from 162 dependency recompiles / ~4.6s
   to 0 recompiles / ~1.7s.

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
3. **`InteractiveDriver` needs `-javabootclasspath` under native-image**,
   like `scalino-dotc`'s own compiles ("Blocker 1"). Under a real JVM dotc
   resolves `java.lang.Object` from the host's modules, so this was invisible
   when testing on the JVM first; under native-image `Definitions.init` fails
   with `asTerm called on not-a-Term val <none>`. It is a requirement on
   whatever generates `.dotty-ide.json` (see below), not a code fix.

**Verified working, native binary, no JVM at runtime** (checked via
process-tree inspection): a hand-written `.dotty-ide.json` fixture pointing
`dist/scalino-lsp -stdio` at `examples/Hello.scala` gets a correct `initialize`
response, empty diagnostics on `didOpen`, Scaladoc-sourced hover text for
`println`, and a correct type-error diagnostic (`Found: String, Required: Int`,
right range) after a `didChange` introducing one. The real stdlib jar is
`org.scala-lang:scala-library` (no `_3` suffix): from the unified 2.13/3.x
versioning (3.8.x+), `scala3-library_3` on Maven Central is a 319-byte
relocation stub (another decoy artifact, like `scalalib_native0.5_3`).

Done since: `scalino setup-ide <sources...>` (`cli/ScalinoCli.scala`)
generates `.dotty-ide.json` (including `-javabootclasspath`) from
`buildBinary`'s classpath/directive plumbing. [`zed-extension/`](../zed-extension/)
is a Zed extension (modeled on
[metals-zed](https://github.com/scalameta/metals-zed), reusing its
`languages/scala/` tree-sitter files under the same NOTICE) with its own
`Scala (scalino)` language/grammar (renamed from `Scala`, see below) and
`scalino-lsp` as its only language server. An earlier design added it as a
second server on Zed's `Scala` language; Zed has no extension-to-extension
dependency or override mechanism, so that would have required installing
metals-zed's JVM-backed `metals` and excluding it per project. Its
`README.md` has install steps. Passes `cargo check` against
`zed_extension_api` 0.7.0.

**Fourth bug (2026-09-05), found by running the extension in a real Zed
instance** (isolated `--user-data-dir` profile with only the `scalino-lsp` dev
extension): `extension.toml` declared `[grammars.scala]` but not the top-level
`languages = ["languages/scala"]` key that makes Zed load
`languages/scala/config.toml`, so Zed's extension index showed
`"languages": []`. The `Scala` language was owned by whichever other extension
defined it (metals-zed, if installed). With metals-zed installed this was
invisible (both servers start together, within ~1s in real logs). Without it,
`.scala` files got no language mode and `scalino-lsp` never started, since
`language_servers.scalino-lsp` is bound to a `Scala` language nothing defined.
Fixed by adding `languages = ["languages/scala"]`. An already-running Zed needs
a dev-extension reinstall, since it does not rescan `extension.toml`.

Also found via direct `-stdio` probing of `dist/scalino-lsp` (not fixed at the
time, lower priority): a project with no `.dotty-ide.json` (`scalino setup-ide`
never run) crashes the whole native-image process on the first `didOpen`.
`DottyLanguageServer.drivers()` (`DottyLanguageServer.scala:74`) throws an
uncaught `FileNotFoundException` inside a `CompletableFuture` on a
ForkJoinPool thread, which kills the process instead of returning an LSP error.
(Fixed in item 4 below.)

A suspected second bug from the same probing, a `NullPointerException` at
`RemoteEndpoint.handleRequest:279` on each `didOpen`/`didChange`/`initialized`,
was a false positive: the probe script's `send()` helper defaulted to sending
a request (with a JSON-RPC `id`), and three call sites lacked
`is_notif=True`. lsp4j then treats them as requests, and
`SafeLocalEndpoint.request` (`Main.scala`) invokes a void method and casts the
`null` result to `CompletableFuture`, reproducing the NPE. With notifications
sent correctly, a 2-minute idle session had zero errors. Check a probe script
by fixing it and confirming the symptom disappears before writing it up as a
bug.

**Fifth fix (same day).** Registering the language was not enough: with the
same name (`Scala`) as metals-zed, Zed still had to pick between the two
extensions' definitions for `.scala` files, and the pick flipped after a
dev-extension reinstall (`metals` and `scalino-lsp` kept launching together,
since metals-zed's `language_servers.metals` entry is bound to the name
`Scala`). Fixed by renaming the language to `Scala (scalino)` and narrowing
`path_suffixes` to `.scala` (was `["scala", "sbt", "sc", "mill"]`, copied from
metals-zed). `scalino setup-ide` also writes a `file_types` override into
`.zed/settings.json` (`"file_types": {"Scala (scalino)": ["scala"]}`) so
`.scala` files resolve to this language regardless of extension load order or
whether metals-zed is installed.

**Log file (same day).** `Log`
(`vendor/scala3/language-server/.../Main.scala`) appends a timestamped line per
dispatched RPC method (`-> method`, then `<- method ok` or
`<- method FAILED: <exception>`) to `.scalino-lsp.log` in the project root.
Zed's own stderr capture only ever showed a truncated "Starting server" line
on connection failures. It wraps `SafeLocalEndpoint.invoke` (`Main.scala`) and
unwraps `InvocationTargetException` so the log shows the real exception class
and message. Best-effort: a no-op if the file cannot be opened (e.g. read-only
cwd).

**`Scala (scalino)` rename confirmed live (same day):** after a dev-extension
reinstall and fresh Zed relaunch, `Zed.log` shows `scalino-lsp` alone starting
for a `.scala` buffer, with no `metals`, across several sessions.

**Sixth bug (same day): first `initialize` hangs under a real Zed.**
`scalino-lsp`, launched by a freshly restarted Zed, reliably failed its first
`initialize`. The request bytes were read completely within milliseconds
(confirmed with a temporary byte-logging `InputStream` wrapper), but
`SafeLocalEndpoint.invoke` / `Log("-> initialize")` never ran: no exception,
no log line. A temporary watchdog thread (periodic `Thread.getAllStackTraces`
dump plus a `Runtime.addShutdownHook`) showed every thread idle within ~3s,
and ~60.0s after the read the process ran its shutdown hook and exited with
code 0. That matches the 60s keep-alive of a `SynchronousQueue`-backed cached
thread pool: the pool thread handling the request finished or was abandoned,
and once it timed out `DestroyJavaVM`'s `joinAllNonDaemons()` had nothing
left to wait for.

Zed's exact `initialize` bytes, captured with a `tee` wrapper substituted for
`dist/scalino-lsp` in `.zed/settings.json`'s `binary.path`, replayed offline
reproduce it every time, so Zed's transport is not involved (an earlier
"Zed never writes to stdin" reading came from a botched capture; the editor's
richer capabilities payload was the variable). Bisection of the payload first
isolated `capabilities.general` and `capabilities.experimental`, two newer
`ClientCapabilities` fields the trace fixture never exercised. Adding them to
`build/lsp-trace-drive.py` and re-running the LSP retrace step
(`build/05-regen-agent-config.sh`) fixed those two fields in isolation, but the
full payload still hung. Removing every top-level `workspace.*` and
`textDocument.*` key one at a time (and `general`+`experimental` together)
found no single field whose removal fixes it, so it is not one missing
reflection registration.

Decisive test: the same bytes against the same compiled classes under a plain
JVM (`java -cp <lsp-classes>:<lsp-java-classes>:<compiler-patched.cp>:<lsp.cp>
dotty.tools.languageserver.Main -stdio`) returned a response in under 1s. So
this is specific to GraalVM native-image: an interaction between Gson's
reflective `TypeAdapter` construction (many nested capability classes touched
for the first time in one large parse) and SVM's closed-world
reflection/threading model, triggered by a rich enough payload. It resembles
known native-image non-daemon-thread lifecycle/exit-timing differences
(oracle/graal#12116, #434) and is not fixable with more reachability metadata.

**Fixed (same day): lsp4j, Gson and Jackson removed.** Instead of chasing the
native-image/Gson interaction, the transport was rewritten. `Main.scala` now
implements JSON-RPC/LSP framing and dispatch by hand (Content-Length reader, a
single worker thread draining a queue so a slow request never stops the reader
from seeing `exit`, response/error/notification encoding). A new `Lsp.scala`
defines every wire type (`Position`/`Range`/`Location`/`Diagnostic`/
`CompletionItem`/... and the JSON-RPC envelopes) with hand-written
`jsoniter-scala`
(`com.github.plokhotnyuk.jsoniter-scala:jsoniter-scala-core_3:2.37.3`)
`JsonValueCodec` instances written against the low-level
`JsonReader`/`JsonWriter` API. `JsonCodecMaker` is not used: it is a
third-party Scala 3 macro, and expanding it would depend on the macro
interpreter (patches/scala3-0001) handling code it was not built for. The
module has no reflection at compile time or runtime. Each params type models
only the fields `DottyLanguageServer` reads (unknown keys are skipped), so a
rich `initialize` capabilities payload is never parsed into a type.
`DottyLanguageServer.scala` keeps its compiler-facing logic
(`InteractiveDriver`, `Interactive.*`, driver-per-project management); only
method signatures and return construction changed from lsp4j types to `Lsp.*`,
and the lsp4j workarounds (`rpcDispatch`/`rpcMethods`/`CancelChecker`/
`CompletableFutures`) are gone. `.dotty-ide.json` parsing moved off Jackson to
a hand-written `Lsp.ProjectConfig` codec. Dropped:
`window/showMessageRequest` (server-initiated request needing bidirectional id
correlation), used only by `rename`'s "also rename overridden members?" prompt,
which now always answers yes (the previous default); and `$/cancelRequest`
(no handler checked it under lsp4j either).

Verified: (1) the exact `initialize` bytes captured from Zed (previously ~60s
hang then silent exit) replayed against the new binary: response in under a
second, every time; (2) `build/lsp-trace-drive.py`'s full session (real
capabilities+clientInfo+workspaceFolders `initialize`, didOpen x3, hover,
definition, completion, references, rename, documentSymbol, workspace/symbol,
didChange introducing a type error) against real dotc compilation of
`build/lsp-trace-fixture`: every step OK, correct diagnostics, no errors in
`.scalino-lsp.log`. The binary shrank 99MB to 81MB and native-image build time
dropped from ~2m30s to ~1m55s. `build/08-build-scalino-lsp.sh` no longer needs
a javac step (deleted `config/ProjectConfig.java`) or
`-H:ConfigurationFileDirectories` (deleted `agent-config/lsp/`;
`build/05-regen-agent-config.sh`'s LSP tracing step is gone).

Not done at this point (item 5 below closes most of it): a check against real
Zed (only the offline harness and ad hoc clients were used), and whether
per-project multi-driver behavior (`references`/`rename`/`implementation`
across dependent projects) still works with a single worker thread processing
requests serially.

4. **Fixed (2026-09-06): missing-`.dotty-ide.json` whole-process crash.** Root cause, found with a minimal Python LSP client (`initialize`/`initialized`/`didOpen` against a project with no config): `DottyLanguageServer.initialize`'s warmup thread (`new Thread(() => { try { drivers; () } catch { case NonFatal(ex) => ex.printStackTrace; sys.exit(1) } })`) calls `sys.exit(1)` on any warmup failure. This is ported from the original lsp4j code's `CompletableFuture(...).exceptionally { ex => ex.printStackTrace; sys.exit(1) }`, so it is upstream dotty behavior, not a regression from the rewrite. Under native-image an uncaught exception on any thread already terminates the process, so the thread dying would kill the server even without the explicit exit. With `sys.exit(1)` removed, the exception is printed to stderr/`.scalino-lsp.log` and the process stays alive. The next request needing `drivers` (`didOpen`, `hover`, ...) hits the same exception inside `Main.scala`'s per-request `try`/`catch`, which returns a JSON-RPC error for requests (they have an id). Notifications (`didOpen`) had no response channel and failed silently from the client's view until item 6 (`window/showMessage`).

   The source now has a clearer error (`drivers`, `DottyLanguageServer.scala`): it checks `configFile.exists` and throws `FileNotFoundException(s"$IDE_CONFIG_FILE not found at $rootUri -- run \`scalino setup-ide <sources...>\` in the project root first")` instead of a bare `NoSuchFileException`. This toolchain needs an explicit `scalino setup-ide` before opening the editor (Metals bootstraps this file itself via BSP), so users hit this more often here.

   Verified with the same test client before (process dies, exit code 1) and after (process survives `didOpen` against a config-less project and answers a later `shutdown`). Patch: `patches/scala3-0002-trim-language-server.patch`.

5. **Re-verified (2026-09-06): `references`/`rename`/`documentSymbol`/`workspace/symbol` on a cross-file, single-project fixture** (the `lsp-trace-drive.py` run covered these against `build/lsp-trace-fixture`, but not cross-file). Two-file project (`Lib.scala`: `object Lib` with `def greet`/`case class Point`; `Main.scala`: `@main def run` calling both), driven by an ad hoc Python client (`initialize`/`initialized`/two `didOpen`s, then each request): `references` on `greet`'s definition returned the definition and the call site in `Main.scala`; `documentSymbol` on `Lib.scala` nested `Lib` -> `greet`/`Point` -> `Point`'s `x`/`y`/`norm`; `workspace/symbol` for `"Point"` found it; `rename` on `greet` returned a `WorkspaceEdit` with edits in both files. All four work under the native-image binary.

7. **Re-verified (2026-09-06): multi-project driver behavior (`references`/`definition`/`rename` across two dependent `.dotty-ide.json` projects).** `.dotty-ide.json` is a JSON array (`ProjectConfig`/`projectConfigListCodec`, `Lsp.scala:81-88`), and `DottyLanguageServer.drivers` (`DottyLanguageServer.scala:67-104`) builds one `InteractiveDriver` per entry, with `projectsSeeing`/`dependentProjects` (`DottyLanguageServer.scala:163-178,560-577`) searching dependent projects via each config's `projectDependencies`. This existed but no fixture had exercised it. Fixture: `ProjA` (`object Shared { def util(x: Int) = x + 1 }`, pre-compiled with `scalino-dotc` for a real `classDirectory`) and `ProjB` (imports and calls it; `dependencyClasspath` includes `ProjA`'s `classDirectory`, `projectDependencies: ["ProjA"]`), with a hand-written `.dotty-ide.json`. Iterated on plain JVM (`java -cp <lsp-classes>:<compiler-patched.cp>:<lsp.cp> dotty.tools.languageserver.Main -stdio`, no native-image rebuild per cycle), then re-confirmed on the native `scalino-lsp`.

   **Fixture mistake that looked like a product bug.** A `.dotty-ide.json` with 2 projects where one project's `dependencyClasspath` lacks a Scala library jar makes `Definitions.init` throw `MissingCoreLibraryException: Could not find package scala` while constructing that project's driver (`DottyLanguageServer.scala:103`, in the `for (config <- configs)` loop). The exception is thrown from the async `initialize` warmup thread (item 4), which only does `ex.printStackTrace()`, not `Log(...)`, so `.scalino-lsp.log` shows nothing. Also, `myDrivers` was assigned an empty `new mutable.HashMap` before the loop, so aborting partway leaves it non-null and empty forever (the `if myDrivers == null` guard never retries). Every later request then fails with `java.util.NoSuchElementException: next on empty iterator` from `configFor`'s `drivers.keys.head` fallback (`DottyLanguageServer.scala:137`), far from the cause. Temporary `System.err.println` instrumentation (reverted; confirmed identical to the tracked patch) showed decode succeeded (2 configs, correct ids) and the first driver construction threw. The cause was the hand-written fixture omitting the real compiler classpath that `scalino setup-ide` always supplies (`cli/ScalinoCli.scala:1776`, `dependencyClasspath = cc.compileCp...`).

   **Fixed anyway (2026-09-06).** A misconfigured project (e.g. a hand-edited or generated multi-project config missing a dependency) could hit the same shape for real. `drivers`' per-project `myDrivers(config) = new InteractiveDriver(settings)` is now wrapped in `try`/`catch NonFatal`: a failing project is logged ("drivers: failed to construct driver for project '<id>': <exception>") and excluded from `myDrivers`, instead of aborting the loop and leaving every project unusable. Verified with a broken-A/healthy-B fixture on plain JVM and the rebuilt native binary: `.scalino-lsp.log` shows project A's exception immediately, and `documentSymbol` on project B's file succeeds in the same session. Patch regenerated (`patches/scala3-0002-trim-language-server.patch`); it reapplies cleanly from a fresh vendor checkout.

   **Process note: regenerating this patch.** Run `git add -N` on `Lsp.scala` before every `git diff` used to regenerate `patches/scala3-0002-trim-language-server.patch`. `Lsp.scala` is a new file carried by the patch, so it is untracked in the vendor checkout. Forgetting this does not error: the diff silently has no hunks for that file, and copying it over the tracked patch destroys the file on the next apply (seen as `source file not found: Lsp.scala` on rebuild; recovered from the last commit's copy). `build/00b-setup-vendor.sh`'s `git reset --hard && git clean -fd` makes the file untracked again each time, so this recurs.

   With a correct `dependencyClasspath`, all three cross-project operations work, verified on plain JVM and the native binary: `references` on `util`'s definition in `ProjA` found the definition and the call site in the dependent `ProjB`; `definition` from `ProjB`'s call site jumped to `ProjA`'s definition; `rename` from `ProjA`'s definition produced a `WorkspaceEdit` with edits in both projects' files. The multi-project design inherited from dotty works under the single-worker-thread rewrite.

6. **`window/showMessage` support (2026-09-06), closing the gap in item 4.** A failed notification (`didOpen`/`didChange`/`didClose`, which have no JSON-RPC response channel) only reached `.scalino-lsp.log`/stderr, invisible in the editor. Added `ShowMessageParams`/`showMessageParamsCodec` (`Lsp.scala`; `type: Int` per the LSP `MessageType` enum, `1` = Error) and a `sendShowMessage` helper (`Main.scala`) wired into `handle`'s catch-all: requests still get `sendError`, notifications now also get a `window/showMessage`. Verified on the native binary: `didOpen` against a config-less project produces a `window/showMessage` with the `FileNotFoundException` message, the server stays alive and answers `shutdown`; a normal `didOpen` against a configured project produces the usual `publishDiagnostics` with no `showMessage`. Patch regenerated, reapplied cleanly from a fresh vendor checkout, rebuilt and re-verified from that tree.

## Remaining work

1. ~~**General quote-pattern matching.**~~ Fixed (2026-09-17):
   `matchQuoteBodyTree` (`Interpreter.scala`) handles lambda-shaped quote
   bodies (`case '{ (x: T) => ... } => ...`, via dotc's `closureDef` extractor,
   aliasing the pattern's param to the scrutinee's param) and a brace-wrapped
   single-expression body (`Block(Nil, expr)`, which any `{ ... }`-bodied
   sub-position can produce on either side). `i7715`/`i8746`, the two fixtures
   blocked on this, compile, link and run end to end. Verified on the JVM path
   (recompile `Interpreter.scala` alone against the published
   `scala3-compiler_3` jar, splice into a copy of the jar, compile+run each
   fixture's `Macro_1.scala` then `Test_2.scala` with `-Yretain-trees`), plus a
   regression pass over the other separate-compilation fixtures (`i4515`,
   `i4515b`, `i10863`, `inline-varargs-1`, `macros-in-same-project1`,
   `tasty-getfile`). General `UnApply`/case-class deconstruction was already
   handled by the `callUserDefDef` fallback (see the utest work below), so the
   category is closed. The remaining quote-pattern gap is structural
   quote-type patterns beyond a bare type variable (`case '[List[t]] => ...`),
   tracked in the `Interpreter` class doc comment's "Known gaps" (since
   closed by patch 0028).
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
4. **`scalino` follow-ups.** Implemented items are under "Toward a build-tool
   experience without a JVM" (watch mode, directory/CLI-flag parity with
   scala-cli, incremental compilation, see item 7). Still missing:
   multi-module/multi-target projects, an entry-point detector that is not a
   text scan, and scala-cli's `fmt`/`repl`/`package`/`publish`/`bsp`/`export`
   (no JVM-free equivalent: no scalafmt, no bloop). These print "not
   implemented".

   `scalino test` (2026-09-04) is implemented with no JVM in the chain; see the
   doc comment above `readAllBytes`/`ClassInfo` in `cli/ScalinoCli.scala`. sbt-scala-native
   drives test execution from the JVM side over a ComRunner socket protocol;
   `scalino` instead (1) scans the resolved test classpath's jars for a class
   implementing `sbt.testing.Framework` (no per-framework list, so any
   framework with a scala-native port is found), (2) compiles, links and runs a
   throwaway probe binary that instantiates it and prints its `fingerprints()`
   (cached after the first run), (3) scans the user's compiled test classes
   against those fingerprints (ancestry walk across compiled output and
   classpath jars, replacing `Class#isAssignableFrom`), and (4) generates a
   driver source that instantiates the framework(s) by literal name (no
   reflection) and drains `Task.execute` synchronously. Instantiating test
   objects inside a discovered `Task` needs no bridging: scala-native's test
   ports already use `scala.scalanative.reflect.Reflect`/
   `@EnableReflectiveInstantiation`. A hand-rolled `sbt.testing.Framework`
   smoke test using that mechanism passed discovery, probe, driver codegen,
   link, run, and pass/fail/exit-code.

   **munit: fixed (2026-09-04), verified end to end.** The blocker
   (`PositionMethods`/`TermMethods` position-capture macros) is closed.
   `Interpreter.scala` implements `TreeMethods#pos`, `SymbolMethods#pos`,
   `TermMethods#underlyingArgument`/`underlying`, and all of `PositionMethods`
   (`start`/`end`/`sourceFile`/`startLine`/`endLine`/`startColumn`/`endColumn`/
   `sourceCode`) as direct dotc calls, like every other `XMethods` bag. A
   `munit.FunSuite` test (`assertEquals`, `Location`/`Clue` macros) compiles,
   links and passes via `scalino test`. Diff in `patches/scala3-0001-*.patch`
   (applied to
   `vendor/scala3/compiler/src/dotty/tools/dotc/quoted/Interpreter.scala`).

   **utest: interpreter-side gaps closed; blocked on a separate dotc bug
   (2026-09-04).** Interpreting `assert(...)`/`Tests { ... }` needed these
   `Interpreter.scala` fixes, in the order they surfaced:
   - **General user-defined `unapply`/`unapplySeq` fallback** (the fix for item
     1's gap, for custom extractor objects with a retained tree). `UnApply`
     matching only worked for a curated list of extractors. When none match, it
     now interprets the extractor's retained `unapply` body via
     `callUserDefDef`, as for any other macro helper. This handles curried
     `unapply`s (`def unapply(using Quotes)(tree): Option[...]`) by unwrapping
     `Apply(Select(qual,"unapply"), givenArgs)` to the base `Select` and
     threading the leading args.
   - `quotes.reflect.Term`'s `TermTypeTest`, `Inferred`/`InferredTypeTest`,
     `Closure`/`ClosureTypeTest`, `Return`/`Try`/`While`/`SummonFrom` (and their
     TypeTests): the tree-shape extractors needed by the default
     `TreeMap#transformTerm`/`transformTypeTree` (`Quotes.scala`, interpreted
     like other code) when a macro's `new TreeMap { override def transformTerm
     = ... }` calls `super.transformTerm` for shapes it does not override.
   - `XxxModule#copy(original)(...)` for each of those shapes (`Select`/`Ident`/
     `Super`/`Apply`/`TypeApply`/`Literal`/`New`/`Typed`/`NamedArg`/`Assign`/
     `Block`/`If`/`Match`/`Closure`/`Repeated`/`CaseDef`/`ValDef`/`DefDef`/
     `Return`/`Try`/`While`/`SummonFrom`), as direct `tpd.cpy.Xxx(original)(...)`
     calls like QuotesImpl.
   - `reflectModule.this` (the `quotes.reflect` bundle's self-type, referenced
     by its default trait methods) falls back to a fresh `QuotesImpl()`
     (stateless beyond the ambient `Context`) instead of failing as an unbound
     `This`.
   - `SymbolMethods#isDefDef`/`isClassConstructor`, `DefinitionMethods#name`,
     `ValDefMethods#tpt`/`rhs`, `DefDefMethods#returnTpt`/`rhs`/`paramss`.
   - `Expr#show` (declared on `trait Quotes`, not an `XMethods` bag, so it is
     dispatched on an `ExprImpl` in the args rather than by owner name) and
     `TermModule#betaReduce` (a direct call to dotc's
     `dotty.tools.dotc.transform.BetaReduce`).
   - **Secondary/auxiliary-constructor interpretation bug**, found via
     `scala.util.matching.Regex`'s `def this(regex: String, ...) =
     this(Pattern.compile(regex), ...)`: `interpretNewFromTree` always used
     `fn.owner.primaryConstructor`'s body regardless of the overload called, so
     the call's arguments were bound to the primary constructor's differently
     shaped params (`Regex#pattern` held the source string, not a compiled
     `Pattern`). It now interprets the called constructor's own body when it is
     not the primary one (usually a single delegating `this(...)` call; that
     call's result is kept as the instance, since a constructor body is
     `Unit`-typed and would discard it).
   - `java.util.regex.Pattern`/`Matcher` and `java.lang.StringBuilder` (JDK
     classes, no retained source) as direct-call intrinsics, needed by
     `Regex#replaceAllIn`'s interpreted source.
   - Quote-pattern matching gap: the same symbol can be reached through a
     different number of `Select` hops depending on context (a package vs. its
     synthetic package-object accessor, e.g. `utest.package.test` vs.
     `utest.test`). `matchQuoteBodyTree`'s `Select` case now falls back to
     whole-reference symbol identity when the scrutinee is not also
     `Select`-shaped.

   With these, utest's `Tests { ... }`/`assert(...)` macros interpret and
   expand (verified on the JVM `dotty.tools.dotc.Main` path, avoiding the ~2min
   native-image rebuild per change). The expanded code then crashes dotc's
   `LambdaLift` phase (`Dependencies$NoPath` in `Dependencies.markFree`),
   after the interpreter's job is done. This is a separate dotc internals
   issue, not an interpretation gap, and needs its own investigation (likely an
   owner-chain/`-Yretain-trees` interaction affecting free variables of a
   macro-expanded closure). Reproduced on the JVM path and with the native
   `scalino-dotc` via `scalino test`. Repro: `//> using dep
   "com.lihaoyi::utest::0.8.4"` + `object T extends utest.TestSuite { val tests
   = utest.Tests { test("x") { assert(1 == 1) } } }`, `scalino test .`.

   scalatest was not retested (untouched since the original blocker note). It
   also uses position-capture macros and likely has a similar tail of gaps.

   **General bug (2026-09-04), found with a third-party project: the
   interpreter's `StopInterpretation` "unsupported" signal could be swallowed
   by an ordinary `try`/`catch` in interpreted macro code.** `matchesRuntimeType`
   (for a catch clause's `case _: SomeType =>`) falls back to `else true //
   best effort` for any type other than `Int`/`Long`/`Boolean`/`String`.
   `NonFatalInterpretedException.unapply` was a bare
   `scala.util.control.NonFatal.unapply`, which cannot tell a program exception
   from the interpreter's abort signal (`StopInterpretation extends Exception`
   is non-fatal). So whenever interpreted code caught with `case _: X => ...`
   for any `X`, a `StopInterpretation` raised inside the `try` was treated as a
   match and swallowed, turning a clean "unsupported feature" error into wrong
   output. Found via `scala.sys.SystemProperties#wrapAccess` (`try Some(body)
   catch case _: AccessControlException => None`, used by munit's
   `MacroCompat` working-directory computation): the then-unsupported
   `System.getProperty` inside `body` silently returned `None` -> `null`,
   corrupting `MacroCompat`'s path and causing unrelated-looking failures
   (`endsWith`/`getJPath`) across every test file in the project.
   **Fixed** by making `StopInterpretation extends
   scala.util.control.ControlThrowable`, which `NonFatal` does not match, so
   no catch site can swallow it and no per-site rethrow guard is needed (an
   earlier fix added such a guard to the one `Try` site found; the
   `ControlThrowable` change replaces it and gave the same error reduction).
   The bug was independent of any macro library: any macro wrapping an
   unsupported call in `try`/`catch` risked silently wrong expansion.

   **Other gaps closed the same session** (found with a large third-party
   project: http4s/upickle/porcupine/cats-effect): `SourceFileMethods#getJPath`/
   `name`/`path` (munit 1.3.4's `MacroCompat` uses `getJPath`, not the
   deprecated `jpath` of 1.0.0); `java.io.File.separator`/`separatorChar`/
   `pathSeparator`/`pathSeparatorChar` and `java.lang.System.getProperty`/
   `getenv` (JDK statics, no retained source); a `New(...)` quote-pattern case
   in `matchQuoteBodyTree` (`case '{ new StringContext(...) } =>`, from
   scala-library's `FromExpr[StringContext]`): `New`'s own `.symbol` is always
   `NoSymbol`, so matching recurses into the `tpt` child.

   **Open gaps found with the same project** (third-party-specific, lower
   priority than the bug above): upickle's `derives ReadWriter` for
   `enum`/case-object types needs `java.lang.Class#getClassLoader` somewhere in
   its `isSingleton`/`Mirror`-adjacent macro machinery
   (`upickle.implicits.macros`); the call site is not traced. A separate `self`
   field-accessor failure appears deep inside `scala.collection.IterableOps.WithFilter`
   interpretation (stdlib `for`-comprehension-with-guard desugaring),
   encountered via http4s's `uri"..."` macro (`org.typelevel.literally.Literally`).
   It is not part of that macro's quote-pattern matching; the error's
   `fn.owner` name "Typed" is coincidental. Not traced.

   Test framework auto-detection works against a published framework: a
   project depending on `munit` with no test classes reports "no tests found"
   rather than "no test framework found" (`munit.Framework` was found and
   probed). Scope gaps vs scala-cli, by design: only `SubclassFingerprint`-based
   frameworks (munit/utest/scalatest/zio-test-sbt), not JUnit4-style
   `AnnotatedFingerprint`; whole-test-class selection only (`-- <pattern>` is
   forwarded to the framework's runner, as in scala-cli).
5. CI (`.github/workflows/ci.yml`) covers Linux and macOS, x86_64 and arm64:
   all four build, link and run the plain-program and macro smoke tests, plus a
   relocatability check mirroring the release tarball's flat layout. Windows
   (x86_64 and arm64) is experimental. CI found and fixed three bugs (GraalVM
   tool paths need `.exe`/`.cmd` resolved explicitly for a literal path; `git
   apply` needs `-c core.autocrlf=false` on the vendor clone plus
   `.gitattributes` pinning `patches/*.patch` to LF; `require()` needs an
   existence check rather than `command -v` for literal paths; see the "resolve
   GraalVM tool paths"/"force LF for vendor clone" commits). It now reaches
   `02-build-java-base.sh`, then fails silently in `03a-patch-compiler.sh`
   with no error output. Prime suspect: `cs fetch --classpath`'s output
   separator. Every build script (`01-fetch-deps.sh` onward) joins/splits
   classpaths on a hardcoded `:` (Unix `java.io.File.pathSeparator`); Windows
   uses `;`, and splitting `C:\...` on `:` mis-parses regardless. Confirming
   needs a failed Windows run's `.build-work/*.cp` files (not uploaded) or a
   Windows machine.
6. `bin/scalino-bootstrap`'s "first source file's basename is the main class"
   convention is still naive (`scalino` auto-detects). For multi-file macro
   examples through `bin/scalino-bootstrap`, list the entry-point file first
   (see `examples/macro-hello/` in the README).
7. ~~Only compiling Scala source is incremental-cache-free.~~ **Fixed
   (2026-09-04): own zinc-style incremental compilation, no Zinc or JVM.** Zinc
   gets its invalidation graph from a compiler bridge hooked into the
   compiler's symbol table, the kind of dotty-internals tie the vendor+patch+
   splice approach avoids (and the reason full self-hosting was first ruled
   out). Zinc also loads the bridge by reflection, a closed-world-hostile shape.

   Instead, `compileToClasses`/`buildBinary` (`cli/ScalinoCli.scala`) build an
   approximate dependency graph from source text: a regex scan for the
   top-level names each file declares, and which of those names each other
   file's text mentions. On a rebuild: hash each source (FNV-1a, as for the
   dependency-classpath cache keys), diff against the last successful build's
   manifest (`.incr-manifest`, a tab/newline file next to the classes dir, so
   no JSON parser) to get the changed set, then widen it by following the
   "mentions a declared name" graph to a fixed point. Only that closure is
   passed to `scalino-dotc`. The classes directory is no longer wiped and is
   added to the compile classpath, so files outside the closure resolve from
   their compiled `.tasty`/`.class`, as in Zinc but with a text-scan graph.
   Stale artifacts (renamed or removed class, dropped source file) are purged
   by filename match before recompiling.

   More conservative than Zinc's name-hashing: any change to a mentioned file
   invalidates the dependent (no body-only vs signature distinction), so it
   recompiles more than Zinc but cannot under-invalidate. A whole-project
   fingerprint (compile classpath, scalac options, `scalino-dotc`'s mtime)
   forces a full rebuild when any changes, since none are tracked per file.
   `--no-incremental` forces a full rebuild. Known gap: the declaration scan
   is top-level only (regex, not parsing), so a name declared only inside a
   nested object has no graph edges; the file itself still recompiles on its
   own changes, but changes do not ripple to files referencing that name.
8. **Four more general interpreter bugs fixed (2026-09-04/05), found with
   `scalino test .` on a large third-party project (`~/scala/ape`:
   http4s/ip4s/cats-effect/upickle).** Regression coverage for all four is in
   `examples/interpreter-regressions/` (self-contained, no external deps), run
   in CI with `examples/macro-hello/`.

   - **`Regex#unapplySeq`'s varargs/`_*` pattern slot** (`case
     SomeRegex(_*) => ...`) went through the general `Typed(Ident(WILDCARD),
     tpt)` case, which type-tests the value's runtime class against
     `tpt.tpe`'s class. That slot's declared type is the synthetic
     `scala.<repeated>[...]` marker, unrelated to the extracted `List`/`Nil`, so
     a matching regex (`unapplySeq` returning `Some(Nil)`) fell through to
     `case _ => None`. Found via ip4s's `Hostname.fromString` (`case
     Pattern(_*) => ...`) reporting "Invalid hostname" for a valid hostname.
     Fixed in `Interpreter.scala`'s `matchPattern` (varargs-size-mismatch
     branch) by unwrapping `Typed`/`Bind` directly: `_*` always matches,
     `xs @ _*` binds the whole list.
   - **`String#split` intrinsic returned `.toSeq`** instead of an
     `Array[String]`. The source tree already has an implicit
     `scala.Predef.refArrayOps`/`genericArrayOps` conversion around the result
     (inserted by the typer from the static `Array[String]` type). Interpreting
     it with an `ArraySeq` crashes inside `ArrayOps`, which pattern-matches
     `xs`'s runtime type (`Array[AnyRef]`/`Array[Int]`/...) and has no case for
     a boxed `ArraySeq`. Found via `Hostname.fromString` (`value.split('.')
     .iterator.map(...)`), right after the varargs fix. Fixed by returning the
     real array from both `split` overloads.
   - **`new Base(args) {}` (anonymous subclass) lost its constructor args**
     whenever an arg needed evaluating (e.g. a closure literal for a
     `Function0` field). The synthetic `$anon` class's primary constructor
     takes no value params; the args live in the superclass constructor call in
     `$anon`'s `Template.parents`, which `-Yretain-trees` never puts in
     `$anon`'s trivial ctor body. `moduleSuperCtorCall`/`findTemplate` already
     worked around this for `object Foo extends Bar(args)`, but two gaps
     remained: `ClassSymbol#rootTree` is `EmptyTree` for any non-top-level
     class (confirmed with `SCALINO_INTERP_DEBUG`), and `findTemplate` only
     recursed into nested class-body members, not into method/`val` bodies
     where a local anonymous class lives. Fixed: `moduleSuperCtorCall` now
     starts from `topLevelClass` (nearest top-level symbol; a no-op for
     modules), and `findTemplate` also recurses into `Block`s and `ValOrDefDef`
     bodies. `interpretNewFromTree`'s primary-ctor branch then binds the found
     superclass call's args onto the anon instance's fields, gated by
     `isAnonymousClass` so the named-class path is untouched (a broader
     ungated version was tried and reverted earlier; see the comment above
     it). Found via cats' `Eval.defer` (`new Eval.Defer[A](() => a) {}`) as
     "Interpreter (own implementation) does not support calling method apply
     in trait Function0" (`Defer`'s `thunk` field never held the closure),
     reached through http4s's `uri"..."` macro.
   - **`scala.runtime.ClassValueCompat`'s abstract `computeValue` was
     unreachable from modules extending it.** `interpretModuleAccess`'s
     workaround for `object Foo extends Bar(args)` builds a separate throwaway
     superclass instance rather than one identified as the module, so a module
     like `scala.reflect.ClassTag`'s private `cache` object, which overrides
     `computeValue`, never got virtual dispatch to its override; only the
     abstract declaration was seen by `notInterpretable`. Binding the instance
     as the module class generally was tried earlier and reverted (it regressed
     upickle's `ReadWriter` derivation with `StackOverflowError`). Fixed
     narrowly: `interpretModuleAccess` builds the module-identified instance
     only when the super-called class is `scala.runtime.ClassValueCompat`
     (checked by the extended class, not the module's name). Found via
     `ClassTag`'s caching, through http4s's `uri"..."` macro.
   - **A curated by-bare-name extractor (`"Ident"`, for `quotes.reflect.Ident`)
     shadowed an unrelated user case class**: cats' private
     `Eval.Ident[A, B](ev: A <:< B)` (a continuation-stack ADT of `Eval`'s
     trampoline). The curated handler correctly returns "no match" for a value
     that is not a `quotes.reflect` tree, but because the `None` came from
     invoking the handler, the generic case-class-unapply fallback never ran,
     and `case Ident(ev) => ev(a)` reported "doesn't match", giving a
     `MatchError` several frames later. Composing every curated handler with the
     generic fallback regressed other macros (ip4s's `port"..."`, upickle enum
     derivation, several `derives ReadWriter` sites), since
     `genericUserUnapply` tries candidates speculatively regardless of
     scrutinee shape and is unsafe as a blanket fallback. Now only the one
     colliding name (`"Ident"`) is composed; other curated extractors are
     unchanged. Found via cats' `Eval#value` trampoline, through http4s's
     `uri"..."` macro.

   **Open gap (not fixed then):** http4s's `uri"..."` macro still does not
   interpret end to end. After the four fixes, `Main.scala`'s
   `host"..."`/`port"..."` literals and every `derives ReadWriter` site in
   `~/scala/ape` compile, but it fails in cats-parse's parser simplification:
   `Parser.Impl.unmap(pa: Parser[Any])` (an exhaustive match with no case for
   `Pure`) receives a `Pure` instance as `pa`, which correctly typed compiled
   code cannot do (`Pure` is a `Parser0`, not the `Parser` that `unmap`
   requires), giving a `MatchError` in `cats.parse.Parser.Impl.unmap`. It is
   not `matchesRuntimeType` being wrong again: every `Parser`-vs-`Parser0`
   runtime type test observed via `SCALINO_INTERP_DEBUG` in a full test run,
   including several involving `Pure`, returned the correct answer. The `Pure`
   likely arrives by another path (`Parser.Impl.expect1`'s `case p1: Parser[A]
   => p1` narrowing, or an upstream `OneOf0` simplification); origin not
   traced. `scalino test .` on `~/scala/ape` still fails on
   `test/http/RoutesTest.scala` (every test method using the `uri"/"` literal).
9. **Three more interpreter bugs fixed (2026-09-05), found via
   `examples/macro-hello`, which was switched from a `println` macro to a
   `jsoniter-scala` `JsonCodecMaker.make` derivation (a harder third-party
   macro; see `examples/macro-hello/Test.scala`). It did not pass end to end at
   the time (open gap below), so it was not wired into CI.**
   - **`mutable.Map#getOrElse`/`#getOrElseUpdate` did not force their by-name
     default/`op` parameter.** Both take `=> V1`, but the `mutable.Map` cases
     in `interpretStdlibIntrinsic` matched it as a plain `Object` and passed the
     wrapped `ByNameArg` through as the value instead of calling `.force()` on a
     miss. Found via jsoniter's `refs.getOrElse(refKey, { ...builds a new
     ref... })` (shared-method memoization for recursive derivations). The raw
     `ByNameArg` then leaked into an `ApplyModule.apply` call as its `fun: Term`
     argument, which does not type-test as `Tree`, so it failed with "no
     built-in intrinsic exists" several frames from the bug.
   - **A local `val`/parameter bound to an interpreted function value (not a
     method symbol), called with arguments via a bare symbol reference, dropped
     every argument.** The `Call(fn, args)` fallback (`else if
     (env.contains(fn.symbol)) env(fn.symbol)`, meant for a bare reference with
     zero args; `Call`'s extractor also yields this shape for plain `Ident(f)`)
     never checked `rawArgs.isEmpty`, so a real call on such a value (e.g.
     jsoniter's `genReadCollection`, whose `readVal`/`result` parameters are
     `Quotes ?=> Expr[B] => Expr[C]` callbacks called as `readVal('x)`) returned
     the unapplied closure. No error appeared directly: `resolveNestedSplices`'s
     fuel loop (see `Splicer.scala`) keeps feeding a fresh `QuotesImpl` to any
     unapplied function result, binding a value parameter (e.g.
     `genReadValForGrowable`'s `x: Expr[G]`) to a bogus `QuotesImpl`, which
     surfaced later as "Unexpected result interpreting a nested splice:
     QuotesImpl@...". Traced with `SCALINO_INTERP_DEBUG`-gated prints (kept): at
     `resolveNestedSplices` entry/failure, at `Ident` lookups for `"x"`, and at
     every `Apply` that falls through the `SpliceInterpreter` override's special
     cases. Fixed by applying `rawArgs` (via `applyFn1`, folded left to right
     over the curried groups `Call` flattened) when `rawArgs.nonEmpty`.
   - **`BlockModule.apply(stats, expr)` did not convert `stats` through
     `asObjectList`**, as the neighboring `ApplyModule.apply` case does. A
     `List[Statement]` from `ListBuffer#toList` (jsoniter's `Block(defs.toList,
     codecDef)`; see `asObjectList`'s doc comment on why `::`/`Nil` built this
     way stay `InterpretedInstance`s) failed to match `stats: List[Tree
     @unchecked]` and fell through to "no built-in intrinsic exists".

   **Open gap (not fixed then):** with those fixed, `JsonCodecMaker.make`'s
   expansion completes with no interpreter errors, but the spliced result fails
   during inlining: `undefined: x.addOne # -1: TermRef(TermRef(NoPrefix,val
   x),addOne) at inlining`. This looks like an owner-chain gap in the spliced
   tree (a generated local `val x` not reachable from its use site's owner chain
   when a later phase looks it up), in the same family as the
   `moduleSuperCtorCall`/anonymous-class owner-chain fixes in item 8, here for
   `ListBuffer`-accumulated `defs` spliced via `Block(defs.toList, ...)`. Not
   traced. Suggested start: `SCALINO_INTERP_DEBUG=1`, grep `changeOwner`/
   `changeNonLocalOwners` call sites reachable from `BlockModule.apply`, and
   check whether each `DefDef` in `defs` (built by separate
   `Symbol.newMethod`/`DefDef.apply` calls) has its owner rechained to the
   enclosing class before the final `Block.apply`. (Resolved later; see
   "jsoniter-scala's `JsonCodecMaker.make`" below.)
10. **`scalino test .` against `~/scala/ape` fixed end to end (2026-09-05).** The
    http4s `uri"..."` macro failed to compile; the full test suite now compiles,
    links and runs natively. Eight bugs across the interpreter, native-image
    reflection reachability, and dependency resolution:
    - **`isInstanceOf` was unconditionally `true`** ("best effort: we don't
      retain the type argument through the Call extractor"). Stale:
      `callTypeArgs(tree)` (recovered from the original tree, already used for
      `classOf[T]` and callee type-param binding) was unused. This broke runtime
      type dispatch such as cats' `AndThen.apply`/`#andThen` (`case ref:
      AndThen[A, B] @unchecked => ref` always matched, even for a bare closure,
      skipping the `Single(f)` construction), so an unwrapped closure reached
      `AndThen#runLoop`, whose exhaustive match over `Single`/`Concat` threw a
      `MatchError`. Fixed by passing `typeArgs.headOption` to
      `matchesRuntimeType` (already used for typed pattern tests). Also,
      `matchesRuntimeType`'s lenient "unrecognized host object -> true" fallback
      needed a carve-out for a bare closure (a raw `FunctionN` never built via
      `interpretNew`; a wrapped instance takes the ancestry-checked branch).
    - **`var x: T = compiletime.uninitialized` (the 3.x replacement for `_`) was
      not recognized as an uninitialized placeholder.** The `UninitializedDefs`
      MiniPhase rewrites it to the `Ident(WILDCARD)` sentinel, but
      `-Yretain-trees` captures trees before that phase, so the interpreter's
      check (`vdef.rhs.isEmpty || Ident(WILDCARD)`) missed it and interpreted the
      `compiletime.uninitialized` call (body: `throw new
      NotImplementedError(...)`, a `@compileTimeOnly` marker). Fixed by also
      recognizing `defn.Compiletime_uninitialized`, the symbol
      `UninitializedDefs` checks.
    - **The "no initializer" fallback used `null`**, correct only for reference
      types. `compiletime.uninitialized` also covers value types, which need a
      scalar zero. Added `defaultValueOf(tpe)`, mirroring `matchesRuntimeType`'s
      `cls ==` dispatch over `defn.{Int,Long,Boolean,Double,Float,Char,Byte,Short,Unit}Class`.
    - **No intrinsic for `new IndexOutOfBoundsException(msg)`** (only
      `UnsupportedOperationException`/`IllegalArgumentException`/
      `IllegalStateException`/`NoSuchElementException`/`RuntimeException` were
      curated), which stdlib code throws in normal control flow
      (`ArrayBuffer#checkWithinBounds`). Same for `Throwable#getMessage`/
      `#getCause`, needed by any interpreted `catch` handler.
    - **An uncurated method of a host object reading/writing its own private
      field went stale or crashed.** `ArrayBuffer#update` has no intrinsic, so
      its body (`checkWithinBounds` reading `size0`, then `mutationCount =
      mutationCount + 1`) is tree-interpreted with `this` bound to the host
      `ArrayBuffer`, which has no `.fields` map. Reads replayed the field's
      original initializer, hiding mutations done through a curated intrinsic
      (`ArrayBuffer#addOne` calls the host `+=`): `size0` stayed `0`, so a later
      in-bounds `.update(0, ...)` threw `IndexOutOfBoundsException`. Writes
      (`Assign` on a `Select` whose qualifier is not an `InterpretedInstance`)
      crashed with `unexpectedTree`. Fixed with `reflectiveFieldRead`/
      `reflectiveFieldWrite`, which use reflection on the real field (walking
      superclasses) when the receiver is a host object, and fall back to the old
      behavior on failure. Traced via cats-parse's `DropRightIterator`
      (`scala.collection.View#dropRight`), reached through the `uri"..."` macro.
    - **`java.lang.String#regionMatches`** (both overloads) had no intrinsic;
      cats-parse's literal matching needs it.
    - **A top-level `val` was not computed once.** `interpretStaticFieldAccess`
      re-interpreted the initializer on every access, returning a fresh value.
      Libraries rely on `eq` against a cached top-level `val` as a sentinel
      (cats-parse's `Parser.unit`). Fixed with a per-`Interpreter`
      (per-macro-expansion) cache, `staticFieldCache`.
    - **Under native-image only** (not reproducible in the unrestricted-reflection
      JVM loop), the reflective calls in `reflectiveFieldRead`/
      `reflectiveFieldWrite` needed the touched classes/fields in
      `agent-config/dotc/reachability-metadata.json`, and an untraced class still
      crashed. `build/05-regen-agent-config.sh`'s dotc tracing fixture
      (`interpreter/test-fixtures/macros-in-same-project1`) never exercised this
      path, so `scalino-dotc` hit
      `org.graalvm.nativeimage.MissingReflectionRegistrationError`. Being an
      `Error`, not a `ReflectiveOperationException`, it escaped both helpers'
      `catch` and crashed the whole compiler instead of falling back per macro.
      Two fixes, both needed: (1) a second tracing pass in
      `05-regen-agent-config.sh`, merged via `config-merge-dir` (not
      `config-output-dir`, which overwrites) over
      `examples/interpreter-regressions/{Foo,Test}.scala` (they cannot join the
      first pass: both declare top-level `object Foo`/`object Test` with no
      package), which registered `scala.collection.mutable.ArrayBuffer`'s
      `array`/`mutationCount`/`size0`; (2) `isMissingReflectionRegistration`,
      which checks the exception's class name (the type is native-image-only and
      absent from this file's Maven compile classpath), added alongside
      `ReflectiveOperationException` in both helpers' catches. The second is the
      long-term fix: tracing cannot cover the unbounded set of host
      classes/fields a macro may reach, so graceful fallback is the only general
      answer. Retracing alone fixed the `ArrayBuffer` gap, but the next class
      reached this way (`scala.collection.immutable.NumericRange`'s `step`)
      crashed the compiler until the catch was widened.
    - **`cs fetch` excludes Maven `provided`-scope dependencies by default, with
      no CLI flag to change it** (neither `cs fetch --help` nor `cs resolve
      --help` exposes a scope override). This is a `cli/ScalinoCli.scala`
      resolution gap, not an interpreter bug.
      `org.typelevel:scalac-compat-annotation_3` is `provided` for
      `org.typelevel::literally` (pulled in by `http4s-core` and others) and is
      needed at macro-expansion time (an inlined reference to one of its
      annotation classes appears in the retained tree), so it was missing from
      the classpath and `uri"..."` failed with a dotc `"undefined: ..."` error.
      Fixed by always adding it as a root `cs fetch` coordinate
      (`alwaysIncludedArtifacts`); a root coordinate resolves at its own default
      scope (compile). Same category as `excludedArtifacts`, for a compile-time
      gap instead of a runtime duplicate-symbol one.

    Regression coverage for the interpreter-side fixes is in
    `examples/interpreter-regressions/{Foo,Test}.scala`. The native-image and
    `ScalinoCli.scala` fixes have no unit-test equivalent; they are covered by
    the `scalino test .` run on `~/scala/ape`. Verified: the full `ape` project
    (all `src/`+`test/` files, including every `uri"..."` literal in
    `test/http/RoutesTest.scala`) compiles, links and runs: 42 tests passed.
    `examples/interpreter-regressions` and `examples/Hello.scala` still pass;
    `examples/macro-hello` still fails on the known gap in item 9
    (`undefined: x.addOne ... at inlining`), in code this work did not touch.

    **New open gap (runtime, not interpretation):** 12 of `~/scala/ape`'s 54 tests
    fail with `scala.MatchError: null` inside
    `upickle.core.Types$TaggedWriter.write0`, reached via
    `db.BuildingRepo#save`'s `upickle.default.write` on a `domain.BuildingInput`.
    The tests compile and link; the failure is in the linked native binary.

    **Isolation.** A minimal repro (~3s; `~/scala/ape`'s
    `domain/{Building,WindowCatalog,AmbienteNonRiscaldato}.scala` verbatim, no
    `db`/`http`/porcupine/cats-effect) shows that every `enum ... derives
    ReadWriter` serializes only its ordinal-0 case; every other case throws
    `MatchError: null`. This is not specific to `BuildingInput`, defaulted
    params or nested case classes; an earlier hypothesis blamed all three.
    Confirmed with `TipoGenerazione.values.foreach(v => upickle.default.write(v))`:
    `CaldaiaStandard` (ord 0) gives `"CaldaiaStandard"`;
    `CaldaiaCondensazione`/`PompaDiCalore`/`Biomassa` (ord 1-3) give
    `MatchError: null`, as for `ContestoUrbano` and `MetodoCalcolo`. The
    `BuildingInput` failure only looked field-related: the object references
    several non-zero ordinals, and some ordinal-0 values were never serialized
    because upickle omits fields equal to their default.

    **Mechanism** (upickle-core `Types.scala`, upickle-implicits `macros.scala`):
    `TaggedWriter.write0` does `val (tagKey, tagValue, w) = findWriterWithKey(v)`,
    a tuple-pattern binding that throws `MatchError: null` when
    `findWriterWithKey` returns `null` (`Node` scans its per-case `Leaf` writers;
    `Leaf` returns `null` on a checker miss). So for every case past ordinal 0,
    no Leaf's `Annotator.Checker` matched. The per-case Leaf writers come from
    `defineEnumWriters[T0, T <: Tuple](prefix): T0 = ${ defineEnumVisitorsImpl[T0, T](...) }`
    (`upickle-implicits/macros.scala:555-602`), a macro interpreted by this
    project's `Interpreter.scala`. It walks `T` (`Mirror.MirroredElemTypes`) and
    for each case synthesizes `implicit lazy val xN: Reader/Writer[CaseType] =
    prefix.macroR/macroW[CaseType]` via `Symbol.newVal(Symbol.spliceOwner, ...)` +
    `ValDef.apply`, bundling all of them (`x0..xN-1`, plus one more `xN` for the
    sum type `T0`) into a `Block` whose result is only `xN`. The per-case vals
    are meant to be found by implicit search from inside `xN`'s nested macro
    expansion, which needs `Writer[EachCase]` to build the `TaggedWriter.Node`.

    **Ruled out:**
    - **Owner-chain mismatch across per-case `Symbol.newVal` calls.** With
      `SCALINO_INTERP_DEBUG` tracing at the `"spliceOwner"`/`"newVal"` cases in
      `Interpreter.scala`, `x0`/`x1`/`x2` have the same owner identity
      (`System.identityHashCode`) within one `defineEnumVisitorsImpl` expansion,
      for both Reader and Writer passes. This is not item 9's `x.addOne`
      owner-chain bug, which is a hard compile-time `undefined` error; here the
      code compiles and links and misbehaves at runtime.
    - **A Scala Native codegen bug with local `implicit lazy val`s in a `Block`.**
      A hand-written control (`Repro5.scala`: a `Block` of `implicit lazy val
      x0`/`x1`/`x2`, `x2` built via an explicit varargs call using `x0`/`x1`,
      the shape of `defineEnumVisitorsImpl`) compiles and runs correctly
      (`result.tag=MERGED:x0,x1`).

    **Second session: deeper trace.** (This corrects an earlier guess, "the
    nested `macroR[CaseType]`/`macroW[CaseType]` call is the divergence point",
    which was wrong.) The `src-3` variants of upickle's
    `ReadersVersionSpecific`/`WritersVersionSpecific`/`macros.scala` are not in
    either published sources jar (only the shared `src/` tree is); they were
    fetched with `gh api repos/com-lihaoyi/upickle/contents/...?ref=4.4.3` (tree
    listing via `gh api repos/com-lihaoyi/upickle/git/trees/4.4.3?recursive=true`).
    The same minimal repro under unmodified `scala-cli` (JVM Scala 3.8.4, upickle
    4.4.3) serializes all ordinals correctly, so the bug is in this toolchain.

    `getSingletonImpl` (`WritersVersionSpecific.scala`'s `macroW[T]`, singleton
    branch: `Annotator.Checker.Val(macros.getSingleton[T])`) is:
    ```scala
    TypeRepr.of[T] match
      case tref: TypeRef => Ref(tref.classSymbol.get.companionModule).asExpr.asInstanceOf[Expr[T]]
      case v => '{valueOf[T]}
    ```
    `SCALINO_INTERP_DEBUG`-gated traces (kept): `[classSymbol]`/`[companionModule]`
    (with the scrutinee's `getClass`, e.g. `dotty.tools.dotc.core.Types$CachedTermRef`)
    at the `TypeReprMethods#classSymbol`/`SymbolMethods#companionModule` sites, and
    `[genericTypeTest]`/`[genericTypeTest.check]` at the generic
    `TypeTest[TypeRepr, X]` dispatch (`typeTestClass`-based `cls.isInstance(v)`),
    used for named-extractor patterns (`case TypeRef(prefix, name) => ...`) and
    type-ascription binds (`case tref: TypeRef => ...`).

    - **`getSingletonImpl` is correct.** For `T = Motivo.A.type` the scrutinee is a
      `CachedTermRef` (`TermRef` and `TypeRef` are disjoint siblings under
      `NamedType` in dotc's `Types.scala`). The `TypeRefTypeTest` returns `false`,
      so the interpreter takes `case v => '{valueOf[T]}`, as dotc would. (The
      earlier `[classSymbol]`/`[companionModule]` hits that seemed to come from
      this function came from another macro helper with the same
      `Ref(...companionModule(classSymbol(...)))` shape; its bound variable is `t`,
      not `tref`.)
    - **`matchesRuntimeType` improvement, kept, not the fix.** Its lenient
      "unrecognized host object -> `true`" fallback now checks whether the
      scrutinee is a dotc-internal `Type`/`Tree` (never an `InterpretedInstance`/
      `ModuleValue`/collection representation) and, if so, uses the
      `typeTestClass`-based `cls.isInstance(v)` lookup already used for named
      extractors. Before, any `case tref: TypeRef => ...` against any unrecognized
      object returned `true`. Verified safe (all 38 `src/`+`test/` files of the
      full `ape` project compile), but rerunning
      `examples/upickle-enum-writer-bug/Main.scala` gives the same `MatchError`s.
    - **`summonFrom`/`ValueOf[T]` is correct.** `summonFrom`'s vendored definition
      (`library/src/scala/compiletime/package.scala`) is a dead stub
      (`error("Compiler bug: summonFrom was not evaluated by the compiler")`); its
      semantics come from a special case in `typer/Applications.scala`
      (`fun1.symbol == defn.Compiletime_summonFrom`). A control without macros or
      `derives` (`getIt[T](using vt: ValueOf[T]): T = vt.value`, called for
      `Metodo.A/B/C.type`) through normal `scalino run` resolved all three cases
      (`A: A`, `B: B`, `C: C`, all `==` checks `true`). `getSingletonImpl`'s
      `quoteTypeBindings` for `'{valueOf[T]}` bind `type T` to the per-case
      singleton (`(Motivo.A : Motivo)` vs `(Motivo.B : Motivo)`) in both Reader
      and Writer passes.
    - **`Splicer.scala` type-argument reification is correct.** A debug print where
      a quote's type bindings are substituted into its body before wrapping as an
      `ExprImpl` (`Splicer.scala`, `Apply(Select(Quote(body, _), nme.apply), _)`
      case, after `body3`) shows `body3.show=valueOf[Motivo.A]` /
      `valueOf[Motivo.B]` with the correct singleton `body3.tpe`.
    - **`defineEnumVisitorsImpl` never interprets the calls it builds.** Its
      `handleType` builds each `macroW[CaseType]`/`macroW[T0]` call with raw
      `quotes.reflect` builders (`Symbol.newVal` + `TypeApply(Select(prefix.asTerm,
      ...methodMember("macroW")...), ...)`) and returns the whole thing as an inert
      `Tree` (`Block(allDefs, Ident(allDefs.head._2.termRef))`); it never calls
      `interpretTree` on them. In the full debug trace, `macroW` appears 3 times,
      all as the string literal passed as `defineEnumWriters`'s `macroX` argument,
      with no `macroW[...]` Apply/Block interpretation lines. The `getSingletonImpl`
      interpretation came from dotc's own inliner expanding `macroW[CaseType]`
      (still `inline`) after the splice; the inliner returns to this interpreter
      only at the next `${...}` splice boundary (`macros.getSingleton[T]`,
      `macros.tagKey[T]`, ...). So `inline m match { case _: ProductOf[T] => ...;
      case _: SumOf[T] => ... }` branch selection and
      `compiletime.summonAll[Tuple.Map[MirroredElemTypes, Writer]]` (special-cased
      in `Inlines.scala:575`) are dotc's, not this interpreter's. If implicit
      search failed to find all the interpreter-built `x0`/`x1`/... symbols, the
      `TaggedWriter.Node` would be built from an incomplete list and give exactly
      the observed `MatchError: null`. Hence the next test.

    **Isolated repro of implicit-search visibility: refuted.** Scratch repros
    `Repro8..12*.scala` (not committed) use an independent typeclass
    (`W[T]`/`Helper.macroW[T]`/`Checker`/`Leaf`/`Node`) mirroring upickle's
    mechanism (checked against upickle 4.4.3 `Writers.scala:16-76`,
    `MacroImplicits.scala`, `Types.scala:194-300`):
    - `Repro10`: `defineEnumVisitorsImpl`'s `handleType`/`getDefs`
      `AppliedType` destructuring and `Symbol.newVal`-built `Block` of per-case
      `implicit lazy val x0..xN`, plus `inline m match { case _: Mirror.ProductOf[T]
      => ...; case _: Mirror.SumOf[T] => compiletime.summonAll[Tuple.Map[m.MirroredElemTypes, W]] }`,
      on `enum Color { case Red, Green, Blue }`. Prints `sum:Red,Green,Blue len=3`:
      `summonAll` finds all three spliced siblings.
    - `Repro11`: adds upickle's matching (`Checker.Val(v0) if v0 == v`, `Node`
      throwing `MatchError(null)` when no leaf matches; the shape of
      `TaggedWriter.Leaf`/`Node`/`findWriterWithKey`/`write0`, `Types.scala:194-221`).
      All three `Color` values serialize.
    - `Repro12`: `derives ReadWriter` calls `ReadWriter.join(macroRAll[T],
      macroWAll[T])` (`MacroImplicits.scala:11-14`), i.e. two
      `defineEnumVisitorsImpl`-shaped expansions in one inline expansion, with
      same-named `x0..xN` locals. Two back-to-back `defineEnumWriters` calls in
      one inline method are correct for all three cases, with no cross-talk.
    - `Repro11` through the full scala-native pipeline
      (`-Xplugin:nscplugin` -> NIR -> `LinkDriver` -> native executable) gives the
      same correct result, ruling out NIR codegen.

    The general derivation mechanism (Block/`Symbol.newVal` splicing, inliner
    re-entry for `macroW[T]`, `Mirror.SumOf` + `compiletime.summonAll` finding
    spliced siblings, `getSingleton[T]`'s `valueOf[T]` fallback, `Checker.Val`
    matching, dual Reader+Writer derivation, NIR codegen) works in isolation from
    upickle. Suggested next step at that point: stop building toy repros; add
    `SCALINO_INTERP_DEBUG` instrumentation to a real run of
    `examples/upickle-enum-writer-bug/Main.scala` (JVM fast loop against the
    resolved upickle classpath,
    `.build-work/deps-cache/deps-com_lihaoyi__upickle__4_4_3.cp`), tracing the
    `Checker.Val`'s captured value at construction, and consider upickle's real
    `SingletonWriter[T]`/`CaseClassWriter[T]`/`annotate[T]`/
    `isMemberOfSealedHierarchy[T]`/`tagKey`/`tagName`/`shortTagName`
    (`Writers.scala:19-61`, `CaseClassReadWriters.scala`) and the `ObjVisitor`/
    `Visitor` layer `write0` drives, which the repros replaced with stand-ins.
    The tracing mentioned above (`[spliceOwner]`, `[newVal]`, `[classSymbol]`,
    `[companionModule]`, `[genericTypeTest]`/`[genericTypeTest.check]`,
    `[quoteBody3]`) remains in `Interpreter.scala`/`Splicer.scala`.

    **Side fix from building the repros:** `quotes.reflect.Symbol.requiredMethod(path:
    String): Symbol` (`Quotes.scala:3844`, distinct from the per-symbol
    `Symbol#requiredMethod(name)` used internally) had no intrinsic, so a macro
    calling it directly hit "no built-in intrinsic exists". Added a one-line case
    in `Interpreter.scala`'s `SymbolModule` dispatch next to
    `requiredClass`/`requiredModule`, delegating to
    `dotty.tools.dotc.core.Symbols.requiredMethod`. Not the enum-writer bug.

11. **`scalino test .` against `~/scala/ape` still fails (2026-09-06): porcupine's
    `sql"..."` interpolator macro (`com.armanbilge::porcupine`, used by
    `db/Schema.scala`/`DraftRepo.scala`/`BuildingRepo.scala`) hits
    `resolveExternalDefTree`'s `notInterpretable` fallback ("no built-in intrinsic
    exists for it (... this own-implementation interpreter does not yet unpickle
    cross-module TASTy bodies ...)" for `method inline$sqlImpl in package
    porcupine`). One bug fixed, a second one found but not fixed.**

    **Bug 1 (fixed): `resolveExternalDefTree` searched the TASTy name table by the
    symbol's displayed name, wrong for any derived name.** `sym.name.toString` for
    `inline$sqlImpl` renders dotc's `NameKinds.InlineAccessorName` (a
    `PrefixNameKind` wrapping `sqlImpl` with an `"inline$"` prefix,
    `NameKinds.scala:389`), but `DottyUnpickler#mightContain`
    (`core/tasty/DottyUnpickler.scala:99-106`) indexes only plain `SimpleName`s from
    the name table (`unpickler.nameAtRef.contents.toArray.collect { case name:
    SimpleName => name.toString }`), so the flattened derived name was never an
    entry it binary-searches. Confirmed by a temporary
    `SCALINO_TRACE_RESOLVE`-gated `System.err.println` (removed): `root.isEmpty=true`
    for `rootTreeContaining("inline$sqlImpl")` although the class (`sql$package$`)
    and its TASTy exist. Any compiler-synthesized derived name reached this way
    (`foo$default$1` default getters, `super$foo` superaccessors) was affected.
    Fixed with `rootTreeSearchName`, which unwraps a `DerivedName` to its innermost
    `SimpleName` before `rootTreeContaining`. (`Name#toSimpleName` is a trap here:
    for a `DerivedName` it is `termName(toString)`, re-flattening the displayed
    name; the code walks `DerivedName#underlying` instead.) This only fixes the
    fast-path hint: `findDefTree` already matched by symbol identity.

    **Bug 2 (found, not fixed): the fix would need to patch `core/Symbols.scala`,
    outside the single-file-overlay patch mechanism.** With bug 1 fixed,
    `top.asClass.rootTreeContaining("sqlImpl")` still returns `EmptyTree` for
    `sql$package$`. Traced (temporary tracing in `core/Symbols.scala` and
    `core/tasty/DottyUnpickler.scala`, reverted; see `03a-patch-compiler.sh`'s file
    list if reopening): `ClassSymbol#rootTreeContaining` (`core/Symbols.scala:470-486`)
    has two branches depending on whether `myTree` is still a lazy
    `tpd.TreeProvider` or already a concrete `Tree`. For `sql$package$`, `myTree` is
    already a concrete `EmptyTree` (`tree.getClass=Trees$EmptyTree`) when the
    interpreter asks. An earlier caller in the same compilation called
    `rootTreeContaining` (possibly with `id=""`, i.e. `.rootTree`, which forces
    materialization), got nothing, and cached it permanently (`myTree = tree` at
    `Symbols.scala:479`, no re-check). Undetermined: (a) who makes the first
    `rootTree` call on `sql$package$` (candidate: the scala-native
    backend/nscplugin's whole-classpath reachability pass, unconfirmed), and (b) why
    `TreeUnpickler#unpickle(UnpickleMode.TopLevel)` (`core/tasty/TreeUnpickler.scala:140-148`,
    `rdr.readTopLevel()`) returns `Nil` for this file/mode/phase. Next step:
    instrument `TreeReader#readTopLevel()` (not `resolveExternalDefTree` or
    `Symbols.scala`) to see whether it throws-and-swallows or reads zero entries,
    and get a stack trace of the first call to `sql$package$.rootTree`.

    The bug-1 fix alone was checked for regressions: full `scalino-dotc` rebuild
    (native-image), and `dist/scalino run` on Hello World still works. Not yet
    re-run against `examples/interpreter-regressions`/`examples/upickle-enum-writer-bug`;
    do so before more interpreter work, in case the derived-name fix interacts.

12. **The upickle enum-writer `MatchError: null` bug (items 9/10; `~/scala/ape`'s
    live `/calcola` endpoint hit it): diagnostic progress over four rounds on
    2026-09-06, root cause found and fixed in round 4 (`List#:::` operand order).**

    **Technique: instrument the real upickle jars.** Fetch upickle 4.4.3's source
    for the relevant files with `gh api repos/com-lihaoyi/upickle/contents/<path>?ref=4.4.3`
    (`upickle/core/src/upickle/core/Types.scala`,
    `upickle/implicits/src-3/upickle/implicits/{Writers,macros}.scala`,
    `upickle/implicits/src-3/upickle/implicits/MacroImplicits.scala`), add
    `System.err.println`/stack-trace instrumentation gated by
    `SCALINO_TRACE_UPICKLE=1`, recompile each file standalone with
    `dist/scalino-dotc` (plugin + `-Yretain-trees`, matching the real build flags),
    and splice the `.class`/`.nir` into a copy of the published jar (`jar uf`). The
    temp source file must keep the original name (`macros.scala`, not
    `upickle-macros-patched.scala`), because dotc derives package-object class
    names (`<filename>$package`) from the source filename, and a mismatched name
    yields classes that override nothing. Point `scalino`'s dependency classpath at
    the patched jar instead of the original for the diagnostic run. This works for
    any bug inside a published dependency that is not obviously in the interpreter.

    **Flags matter.** Running the `-Xprint:inlining` dump from items 9/10 via
    `dotty.tools.dotc.Main` without `-Xplugin:.../-Xplugin-require:scalanative
    -Yretain-trees` (the flags `scalino run` always passes) shows a different, wrong
    picture: a hard error, `Interpreter (own implementation) does not support
    calling method inline$defineEnumVisitorsImpl in package
    upickle.implicits.macros: ... does not yet unpickle cross-module TASTy bodies`
    (the same derived-name/`inline$` gap as item 11, since
    `defineEnumWriters`/`defineEnumReaders` are `inline` package-object members).
    With the real flags it disappears (0 errors). Ad hoc JVM-mode dotc runs must
    use the flags `cli/ScalinoCli.scala`'s `buildBinary`/`compileToClasses` use
    (`-Xplugin:<plugin>`, `-Xplugin-require:scalanative`, `-Yretain-trees`); the
    JVM-fast-loop repros of items 9/10 may have exercised a different path.

    **Round 1 finding (real flags).** Runtime tracing (`Leaf.findWriterWithKey`,
    patched into `upickle.core.Types` and spliced into a copy of
    `upickle-core_native0.5_3-4.4.3.jar`) shows that for every enum tested
    (`TipoGenerazione`, `ContestoUrbano`) only one `TaggedWriter.Leaf`, the
    ordinal-0 case, is queried, whatever the value's ordinal. `Leaf#findWriterWithKey`'s
    `checker match { case Annotator.Checker.Val(v0) if v0 == v => ...}` is correct at
    runtime (`v0==v=true` for the match; `false` with distinct `hashCode`s
    otherwise), which rules out an equals/hashCode/identity bug for enum singletons
    (also confirmed with a scala-native `enum Color` test: `direct.eq(viaValues)`,
    `==` and `ValueOf`-derived identity all correct for a non-zero ordinal;
    `/tmp/enum-identity-check`, scratch only). Patching `TaggedWriter.Node`'s
    constructor and `Writer.merge` (`Types.scala`'s `object Writer { def
    merge[T](writers: Writer[_ <: T]*) = ... }`, the only construction site for the
    write side) to print on entry: neither fires, for either enum, at any ordinal.
    Yet `-Xprint:inlining` shows the program source contains `Writer.merge[domain.TipoGenerazione](writers*)`,
    fed by `Tuple4.apply(x0, x1, x2, x3).productIterator.toList` of four correct
    per-case `implicit lazy val`s (each `SingletonWriter`/`annotate`/`Checker.Val(...)`
    verified by tree inspection, for the real enums as well as the repros).
    Code present in the source never ran.

    **Ruled out in round 1:** (1) `defineEnumVisitorsImpl`'s statement ordering
    (`macros.scala:600`, `val allDefs = topTraitDefs.toList ::: subTypeDefs`, which
    seemed to place the whole-type `xN` before the per-case vals). Reversing the
    order (`Block(allDefs.reverse.map(_._1), ...)`) changed nothing. The reasoning
    attached to this was a misreading (round 3 below), but the patch result stands.
    (2) Enum singleton `equals`/`hashCode`/identity under scala-native, including via
    `ValueOf[T]`. (3) Interpreter tree/quote reification for
    `getSingletonImpl`/`Checker.Val`'s per-case captured value (also reconfirmed
    with `-Xprint:inlining` on the real enums).

    **Round 2.** `w0.getClass` (`ReadWriter.join`'s `implicit w0: Writer[T]`
    parameter; named `w1` in the pattern-match text) is
    `upickle.core.Types$TaggedWriter$Leaf`: `macroWAll[TipoGenerazione]`'s result is
    a bare `Leaf`, not a `Node`. Tracing upickle's `isSingletonImpl`
    (`macros.scala:541-543`) with `System.err.println` hit another interpreter bug:
    `System.err`/`System.out` field access had no intrinsic (`Interpreter (own
    implementation) does not support calling value err in object System`, a hard
    `StopInterpretation`). When that failure occurs while producing an
    `Expr[Boolean]` feeding an `inline if` (`isSingletonImpl`'s shape,
    `Writers.scala:45`'s `inline if macros.isSingleton[T] then ... else ...`), the
    caller sees `Cannot reduce 'inline if' because its condition is not a constant
    value: ??? :Boolean` instead of the real error. Isolated repro
    (`/tmp/expr-fold-repro/{Macros,Repro}.scala`, scratch: `inline def
    check(inline flag: Boolean): Boolean = ${ checkImpl('flag) }`; plain
    `Expr(result)` works, adding a `System.err.println` statement first triggers
    both failures). Fixed the field access: `javaStaticFieldConstant`
    (`Interpreter.scala`, next to `StandardCharsets`/`Character`/`File`) gets
    `case "System" => case "out"/"err" => Some(System.out)/Some(System.err)`. Checked
    with the repro and with `examples/{Hello,interpreter-regressions,macro-hello,upickle-enum-writer-bug}`,
    unchanged. Methods on the `PrintStream` were still unsupported (round 3).

    Using `quotes.reflect.report.warning(msg)` for tracing instead (existing
    intrinsic, `("error"|"errorAndAbort"|"warning") if fn.owner.name.toString == "reportModule"`)
    on `isSingletonImpl`, while compiling all of `examples/upickle-enum-writer-bug/domain/*.scala`
    (a dozen-plus `derives ReadWriter` enums of 2-5 cases, so 40+ checks) emitted
    one `COMPILE-TRACE isSingleton[...]` warning, for an unrelated enum
    (`AmbienteAdiacentePavimento`). Probably dotc's reporter deduplicates repeated
    warnings from the same macro site (unconfirmed). `report.warning` is not a
    reliable trace channel for a macro invoked this often; dead end.

    **Round 3.** Added curated `java.io.PrintStream` cases to
    `interpretStdlibIntrinsic` (`println()`, `println(String)`, `println(Any)`,
    `print(String)`, `print(Any)`, `flush()`), in the hand-curated-per-host-object
    style of `BitSet`/`Pattern`/`Matcher`. There is no generic reflective-call
    fallback for host-object methods in this file (`java.lang.reflect.Method`/
    `.invoke` is unused apart from an unused import), so every host-object method
    needs its own case. `System.err.println` in a macro body now works. Tracing
    `methodMember`'s call site (`macros.scala:580`,
    `prefix.asTerm.tpe.typeSymbol.methodMember(macroX)`) over the full
    `examples/upickle-enum-writer-bug` compile, across all 166 `handleType` calls:
    `candidates.length=1` every time, including whole-enum types (`name=x4
    macroX=macroW arg=domain.TipoGenerazione skipTrait=false candidates.length=1
    candidates=method macroW`; same for `macroR`/`ContestoUrbano` and every other
    enum). So `.head` cannot pick the wrong overload.

    The round 2 "statement-ordering" analysis was a misreading of the dump. A
    minimal repro (`/tmp/tiny-enum/Tiny.scala`, 2-case `enum Color derives
    ReadWriter`, ~650-line `-Xprint:inlining` dump) shows the executed order of
    `defineEnumVisitorsImpl`'s block is `x0, x1, ..., x(N-1), xN` (per-case vals
    first, the whole-type val containing `Writer.merge` last), the opposite of what
    round 2 reported. For the 2-case repro, `x2`'s block, printed and executed last,
    contains `WritersVersionSpecific_this.Writer.merge[Color](writers*)`. Round 2
    had mistaken one enum's trailing `x0` for a sibling of the preceding enum's
    `x4` in a 33,805-line dump. Read `-Xprint` output from a minimal repro, not a
    full-project dump.

    **Round 4: root cause. `List#:::` was implemented like `++`**
    (`case (a, "++" | ":::", b) => Some(a ++ b)`, `Interpreter.scala`). The real
    contract is reversed: `a ::: b` puts `b`'s elements first, so the result is
    `b ++ a`.

    Found by testing on plain JVM bytecode (`dotty.tools.dotc.Main` +
    `-Yretain-trees`, no `-Xplugin`, no scala-native) instead of rebuilding the
    native `scalino-dotc` per iteration. The bug reproduces identically there
    (patched dotc compiled against the plain, non-`_native` `com.lihaoyi::upickle:4.4.3`,
    run with `java`; `cs fetch --classpath "com.lihaoyi::upickle:4.4.3"`, excluding
    its `scala3-library_3`/`scala-library` in favor of the pinned 3.8.4 ones):
    `Green FAILED: scala.MatchError: null`. The loop dropped from ~100s
    (native-image) to ~15s (`03a-patch-compiler.sh`'s JVM-only recompile + a
    `dotty.tools.dotc.Main` compile + a `java` run), and `javap -c` could read the
    produced bytecode, which is more reliable than a 30,000-line `-Xprint` dump.

    `javap -c` on the tiny repro's `Color$.class`: `derived$ReadWriter$lzyINIT1`
    calls `x0$1`/`x0$2` (case 0's own reader/writer) as the arguments to
    `ReadWriter$.join`, and allocates two more `LazyRef`s that should have been for
    `x1`/`x2` but never uses them. So `derived$ReadWriter` never touches the merged
    `x2` writer and uses the ordinal-0 writer as the whole type's. `x2`'s body
    (`x2$lzyINIT2$1`) is correct (calls `x0$2`/`x1$2`, builds a `Tuple2`,
    `Writer$.merge`s into a 2-leaf `Node`). The problem is what
    `derived$ReadWriter` references as the merged instance:
    `Block(allDefs, Ident(allDefs.head._2.termRef))`'s `allDefs.head` resolved to
    `x0` instead of `x2`.

    A `System.err.println` of `allDefs.map(_._2.name)` at `macros.scala`'s `val
    allDefs = topTraitDefs.toList ::: subTypeDefs`, patched into a copy of the plain
    JVM `upickle-implicits_3-4.4.3.jar`, showed `allDefs.head` was `x0` and
    `subTypeDefs` was `[x0, x1]` (natural order) instead of the `[x1, x0]` that
    `getDefs`'s `:::` recursion (`defAndSymbol.toList ::: defs`) should produce.
    With `:::` computed as `a ++ b`, `subTypeDefs` accumulates by appending, and
    `topTraitDefs.toList ::: subTypeDefs` puts the whole-type definition last, so
    `allDefs.head` is the first per-case definition (always ordinal 0). That
    matches the symptom (only ordinal 0 serializes) for every multi-case enum.

    **Fix:** the `"++" | ":::"` case is split: `++` stays `a ++ b`, `:::` becomes
    `Some(b ++ a)` (`Interpreter.scala`, `interpretStdlibIntrinsic`). Verified on the
    JVM fast loop (tiny repro: `Red`/`Green` serialize), on the full
    `examples/upickle-enum-writer-bug` (all 4 `TipoGenerazione` cases and 3
    `ContestoUrbano` cases serialize) via plain JVM bytecode and after rebuilding the
    native `scalino-dotc` and running the full `scalino run`/scala-native pipeline.
    `examples/{Hello,interpreter-regressions,macro-hello}` unchanged (`macro-hello`
    still fails on the `undefined: x.addOne` gap, item 9). This closes items 9, 10 and
    12 (11 is unrelated): the upickle enum-writer bug that blocked `~/scala/ape`'s
    `/calcola` endpoint and every multi-case-enum JSON round-trip is fixed.

    Why it took four rounds: `:::` is rarely used in macro code (`++`/`::`/`:+` are
    more common), and upickle's enum derivation is the first code to depend on its
    prepend contract to order a spliced `Block`'s result. Every layer checked on the
    way (equals/hashCode, `methodMember`, quote/tree construction, `Symbol.newVal`,
    TASTy name-table lookups from items 11/12) was correct.

## Toward self-hosting scalino-dotc/scalino-lsp on scala-native (research only, not started)

Scoping only, for the lowest-priority goal of migrating off GraalVM native-image. At this point `cli/ScalinoCli.scala` (~1700 lines) already self-hosts: it is compiled by `scalino-dotc`/`scalino-linkdriver` into a scala-native binary. `scalino-dotc` and `scalino-lsp` are unmodified dotty JVM bytecode AOT-compiled by GraalVM native-image. Migrating means dotc compiling its own (and the language server's) source to native code. Scale: the compiler is 591 `.scala` files / 222,003 lines (`vendor/scala3/compiler/src`); the language-server driver is 13 files / 2,510 lines (`vendor/scala3/language-server/src`), but the LSP links the same compiler engine, so the target is not meaningfully smaller.

**Blocker A (most load-bearing): `MegaPhase.defines` reflects on `MiniPhase` classes on every compilation.** `transform/MegaPhase.scala:498-513` caches `cls.getDeclaredMethods` (`java.lang.reflect.Method`) per `MiniPhase` subclass to detect which mini-phases override a callback, walking the superclass chain (`hasRedefinedMethod`). This runs during pipeline construction on every dotc invocation, with no flag (confirmed by reading the source). It matters more for scala-native than for GraalVM: native-image lets you register classes for reflection at build time (how Blocker 2's `nscplugin` fix works), while scala-native has only the narrow `@EnableReflectiveInstantiation` opt-in used for test-framework discovery (see "Remaining work" item 4). `MegaPhase.defines` reflects over an open-ended, unregistered set of classes with `getDeclaredMethods`/`getSuperclass`, which scala-native does not support. It needs a source change (e.g. each `MiniPhase` declaring its overridden callbacks explicitly, checked at compile time) before the pipeline can run under scala-native.

**Blocker B: two narrower reflection sites in dotc**, both flag-gated: `Driver.scala:93` (`-Yreporter <class>`, loads a custom `Reporter` by name via `Class.forName`) and `transform/CheckUnused.scala:1005` (`-Wunused`'s check for `quotes.reflect` tree-extractor parameter names, `Class.forName(s"dotty.tools.dotc.ast.Trees$$...")`). Both could be stubbed to fail loudly when the flag is passed, as is done for `--compiler-plugin`. (Downgraded below.)

**Blocker C (same category as Blocker 2): `transform/MacroAnnotations.scala` reflectively invokes a macro annotation's compiled bytecode** (`java.lang.reflect.InvocationTargetException` imported, a reflective `invoke`). This is a different, less common feature than the inline/quote macros `Interpreter.scala` replaces, with the same closed-world problem as the original Splicer bottleneck ("Macro execution"). No project used macro annotations so far. It would need the own-interpreter treatment extended to this path, or stay unsupported (experimental, niche feature).

**Lower risk: jar reading.** scala-native's javalib has `java.util.zip`/`java.util.jar` (`vendor/scala-native/javalib/src/main/scala/java/util/{zip,jar}/`, including `ZipFile`/`JarFile`/`Manifest`, with tests under `unit-tests/.../javalib/util/jar/`), which is what dotc's `dotty.tools.io.ZipArchive`/`Jar.scala` need for classpath jars. The `jrt:/` fix (Blocker 1; extract `java.base` into a jar and use `-javabootclasspath`) does not depend on GraalVM and carries over unchanged.

**Open, unverified risk: dotc's TASTy pickling phase uses concurrency.** `transform/Pickler.scala` imports `scala.concurrent.Future`/`Await` (aliased `StdFuture`) and dotc's `util.concurrent.Executor`/`Future` (`util/concurrent.scala`) to overlap TASTy/bytecode assembly with later phases (`useExecutor`, `backendFuture`). scala-native has OS-thread multithreading (this project's build logs show "multithreadingEnabled" auto-detection), so this is plausibly portable, but `scala.concurrent.Future`/`ExecutionContext` under scala-native for this use is untested.

**No upstream precedent.** `vendor/scala3/project/Build.scala` has no scala-native cross-build targets for the compiler or language-server modules (every "native" hit is OS packaging/`libexec-native-overrides`). Dotty has never published a scala-native build of dotc.

**Why `cli/ScalinoCli.scala` self-hosting does not derisk this much.** It shows `scalino-dotc` can compile ordinary Scala 3 to a scala-native binary, but it has no dependency on the reflection/concurrency-using dotc internals above; self-hosting dotc compiles 222,003 lines that hit all three. The patching approach (recompile one file, splice into the published jar; "Patching mechanism") does not scale past single-file, dependency-only changes; "Remaining work" item 2 notes anything bigger needs the dotty/scala-native sbt bootstrap build, which does not exist for this purpose yet. Fixing Blocker A is a multi-file change of that kind.

**The language server does not shrink the target.** Its driver code (`Main.scala`/`Lsp.scala`/`DottyLanguageServer.scala`, 2,510 lines) would self-host like `cli/ScalinoCli.scala`, but `InteractiveDriver` goes through `Run`/`Phases`/`MegaPhase` and hits Blocker A identically.

**Scope: many months at minimum.** (1) Fix Blocker A (bounded source surgery on dotc's phase-pipeline machinery). (2) Fix or stub Blockers B/C. (3) Verify the `scala.concurrent`-based pickling under scala-native. (4) Get the dotty/scala-native sbt bootstrap build (a separate open item) building the whole compiler module. (5) A two-stage bootstrap this project has not needed (`scalino-dotc` only self-hosts small tools so far; self-hosting dotc means compiling dotc-native with dotc-JVM, verifying it, then recompiling dotc-native with itself). (6) Unknown scala-native maturity risk at 222K lines, large binary, long-running process. No step is exotic, but there is no upstream precedent and a real risk of stalling on scala-native's maturity.

**Suggested first step:** fix or stub Blockers A/B/C in isolation and confirm `scalino-dotc` (still GraalVM-built) behaves identically, a cheap check independent of scala-native. Then, as a feasibility smoke test, compile a reduced subset of dotc's own source (frontend only: parser/typer, no backend phases) into a scala-native binary, to see whether dotc's source at large scale compiles and links under the current scala-native support before committing to all 222K lines.

## Self-hosting, in progress: Blocker A fixed (2026-09-06)

First step from the plan above. Findings in order:

**Blocker A is real: `getDeclaredMethods` does not exist on scala-native's `java.lang.Class`.** `vendor/scala-native/nativelib/src/main/scala/scala/scalanative/runtime/Class.scala` (the class backing `java.lang.Class`) has it commented out (`// def getMethods(): Array[Method] = ???`), so `MegaPhase.defines`'s call would fail to compile against scala-native's javalib, not just misbehave at runtime. `cls.getName()` and `cls.getSuperclass()` (the rest of the `hasRedefinedMethod` recursion) are implemented on scala-native's `_Class`. Only the one call needs replacing.

**Blocker B downgraded: already handled.** The companion object in the same file implements `forName` as `LinkedClassesRepository.byName.get(name).getOrElse(throw new ClassNotFoundException(name))`, a `ClassNotFoundException` for any name the linker dropped, as on the JVM. `Driver.scala:93` (`-Yreporter`) and `CheckUnused.scala:1005` (`-Wunused`'s `Trees$X` lookup) already catch `ReflectiveOperationException`/`ClassNotFoundException`, so under scala-native these features degrade to "unavailable" with no source change.

**Blocker C: left unsupported, not attempted.** `MacroAnnotations.scala`'s `callMacro` needs a dynamically instantiated host object (`annotInstance.getClass.getClassLoader.loadClass(...)`, then a structurally typed reflective `.transform(...)`) for the annotation's compiled bytecode. It is the problem the interpreter solved for ordinary macros but is not extended here; doing so would need `Interpreter`/`callMacro` reworked to invoke compiled bytecode without reflection or to interpret the annotation's `transform` body. Macro annotations remain experimental and rarely used.

**Blocker A fix, implemented and verified.** A "sentinel invocation" approach (call each phase's callback with a dummy tree and compare the result by identity to infer an override) was rejected as unsound: a phase's `transformIdent` may act only on trees matching a runtime condition the dummy never satisfies, so it would misreport a conditionally transforming method as absent and corrupt pipeline fusion. Instead the same computation (`cls.getDeclaredMethods` on a `MiniPhase` subclass) moves from runtime to build time, using a JVM (already required at build time, e.g. for `jimage`/`-javabootclasspath`) and baking the result in as a reflection-free `Map`.

- `build/tools/GenMiniPhaseOverrides.java` (new, plain Java, build-time only): loads every class on the classpath `scalino-dotc`'s native-image build bakes in (`compiler.cp` + `nscplugin.cp`), finds every one assignable from `dotty.tools.dotc.transform.MegaPhase$MiniPhase`, and records which of MiniPhase's ~70 overridable `prepareFor*`/`transform*` methods it declares (`cls.getDeclaredMethods()`), via `Class.forName(name, false, loader)` (no static init) over every classfile in those jars. Best effort: a class that fails to link off this closed classpath is skipped, and a missed MiniPhase subclass fails loudly at its lookup site. Emits `MiniPhaseOverrides.scala`.
- `build/02b-gen-megaphase-overrides.sh` (new, after `01-fetch-deps.sh`, before `03a-patch-compiler.sh`): compiles and runs the tool, writing `.build-work/generated/MiniPhaseOverrides.scala`. Wired into `build/all.sh`.
- `patches/scala3-0003-fix-megaphase-reflection.patch` (new): `MegaPhase.scala`'s `defines`/`hasRedefinedMethod` look up `MiniPhaseOverrides.declaredMethodNames.getOrElse(cls.getName, /* throw */)` instead of `cls.getDeclaredMethods`; `cls.getName`/`cls.getSuperclass`/`cls.eq` are unchanged. A missing entry throws with a message to rebuild the table, rather than defaulting to "no overrides".
- `build/03a-patch-compiler.sh` now compiles `MegaPhase.scala` and the generated `MiniPhaseOverrides.scala` together (with the existing `Interpreter.scala`/`Splicer.scala` splice) and overlays both into the patched jar. This avoids the full sbt bootstrap because only `MegaPhase.scala`'s body changes; none of the 83 `MiniPhase` subclass files (`RefChecks.scala`, `EtaReduce.scala`, ...) change, unlike the per-subclass-annotation designs considered and rejected.

**Verified, still on GraalVM native-image:** rebuilt `scalino-dotc` and `scalino-lsp` from scratch with the patch (`GenMiniPhaseOverrides` found 83 `MiniPhase` subclasses, matching `grep -rl "extends MiniPhase"` over `compiler/src`) and ran `examples/Hello.scala` (correct output), `examples/interpreter-regressions/{Test,Foo}.scala` (unchanged), and a two-stage separate compilation of `interpreter/test-fixtures/i4515` (`Macro_1.scala` first, `Test_2.scala` against its `.tasty`, linked and run, clean exit). `examples/macro-hello` still fails on the known `undefined: x.addOne` gap (item 9).

**Regression caught by `build/lsp-trace-drive.py`, not by those three tests.** The rebuilt `scalino-lsp` failed `initialize`/parsing on every message, with `.scalino-lsp.log` showing `java.lang.NullPointerException: null` on every incoming byte. The pre-patch baseline succeeded (`initialize` in 670ms; confirmed by `git stash` on `MegaPhase.scala` only, rebuild, rerun). Temporarily adding `t.printStackTrace()` to `Main.scala`'s catch block showed the NPE inside `Lsp$.incomingEnvelopeCodec$lzyINIT1`, a `VarHandle`-based lazy-val CAS guard failing on a null field: `Lsp.scala`'s bytecode, produced by the patched dotc, was wrong. The cause was in `transform/LazyVals.scala` (the `MiniPhase` that lowers `lazy val` to bitmap+CAS): `javap` on the unmodified `scala3-compiler_3.jar` shows `LazyVals` declares `transformStats`, but the generated table recorded only `Set("prepareForUnit", "transformDefDef", "transformTemplate", "transformValDef")`.

**Root cause: a transcription bug in `GenMiniPhaseOverrides.java`'s `OVERRIDABLE_NAMES` literal.** The `prepareFor*` half included `prepareForStats`/`prepareForUnit`, but the `transform*` half lacked `transformStats` and `transformUnit`, so all 83 MiniPhase subclasses lost those two callbacks from their recorded override sets. `LazyVals`'s `transformStats` (which installs the CAS-based lazy-val machinery) caused a visible symptom because `Lsp.scala` is a large `object` with many lazy vals; `Hello.scala`, `interpreter-regressions` and `i4515` do not exercise a `transformStats`/`transformUnit` override enough to differ, which is why they passed. Passing tests are weaker evidence than checking generated data against the real bytecode. Fixed by adding `"transformStats"` and `"transformUnit"` to the list, regenerating `MiniPhaseOverrides.scala` (`LazyVals` now includes `transformStats`), and rebuilding `scalino-dotc` and `scalino-lsp` from scratch. After the fix the three non-LSP tests pass and the LSP trace matches the baseline: `initialize` OK in 692ms, `workspace/symbol` OK. The same pre-existing, unrelated failure remains on `hover`/`definition`/`completion`/`references`/`rename`/`documentSymbol`: a stale absolute path in `build/lsp-trace-fixture/.dotty-ide.json` (`/Users/lorenzo/scala/scala-native-compiler/...`, from before the repo was moved) gives `NoSuchElementException: next on empty iterator` in `DottyLanguageServer.configFor`. Not fixed here.

Note for extending `GenMiniPhaseOverrides.java`'s method-name list: cross-check the `prepareFor*` and `transform*` halves against each other, and verify a sample of the generated table against `javap` on the real jar, as caught this bug. Blocker A is fixed and verified against real compiles including the LSP, still shipping via GraalVM native-image. Cross-compiling `scalino-dotc` to a scala-native target is the next, not yet attempted step.

**Next step:** a feasibility smoke test: compile a reduced subset of dotc's source (frontend only: no `backend/jvm`, no `backend/sjs`, `dotc/sbt` stubbed or excluded) as a scala-native target, to get signal on the open risks (`Pickler.scala`'s `scala.concurrent.Future`/`Await` concurrency, and whether a codebase this size compiles to scala-native at all). Blockers A/B/C no longer block it.

## Frontend-only smoke test: reduced dotc source set compiles under plain JVM dotc (2026-09-06)

The smoke test from the previous section, done like Blocker A: patch, then run and check.

**The exclusions are mandatory.** `cs fetch org.scala-lang.modules:scala-asm_native0.5_3:9.9.0-scala-1` and `cs fetch org.scala-sbt:compiler-interface_native0.5_3:1.10.5` both fail with "not found" on Maven Central. So `backend/jvm` (needs scala-asm) and a zinc integration (needs `compiler-interface`/`xsbti`) cannot be resolved for scala-native. `backend/sjs` (irrelevant here, and it needs `backend/jvm`'s ASM-based types) is excluded too.

**Every `.java` file is also a hard blocker.** This toolchain's dotc compiles `.scala` to NIR (via `nscplugin`) but has no Java-to-NIR path; `javac` is not part of the self-compile (unlike `-javabootclasspath`'s pre-extracted `java.base.jar`, which holds real JDK classfiles). Two `.java` files had to be ported to Scala for this reason alone (neither depends on `xsbti`): `sbt/interfaces/{IncrementalCallback,ProgressCallback}.java` became `.scala` with the same package/class shape. `backend/jvm`'s three `.java` files (`MethodNode1`/`ClassNode1`/`LabelNode1`) and `profile/{ExternalToolHook,ExtendedThreadMxBean}.java` needed no port: `backend/jvm` is excluded, and JMX-based profiling is meaningless without a JVM, so `RealProfiler` was dropped.

**The reduced source set was found by compiling and fixing forward**, not by deriving a minimal file list first. Take every `.scala` file under `compiler/src`, minus `backend/jvm`, `backend/sjs`, `debug` (an expression-evaluator subsystem for debugger support, with no references from `dotc/`), `dotc/decompiler`, `dotc/semanticdb`, `scripting`, `transform/sjs`, `MainGenericCompiler.scala` (a separate multi-tool entry point), `dotc/util/ClasspathFromClassloader.scala` (unreferenced), and `dotc/sbt/{ExtractAPI,ShowAPI,APIUtils,ThunkHolder}.scala` (they need the rest of `xsbti.api.*`; `sbt/ExtractDependencies.scala`/`sbt/package.scala` do not, and were kept, patched). Result: 468 files. This is larger than the earlier research estimate (~136K lines, ~61% of 222K), which hand-derived a minimal `transform/` subset by following imports; keeping everything not hard-excluded was simpler and safer.

**Patches beyond Blocker A/B/C:**
- `core/NameOps.scala`: `compactify`'s name-shortening hash moved from `java.security.MessageDigest` (no Scala Native port; no hits in `vendor/scala-native`) to `scala.util.hashing.MurmurHash3`. Four 32-bit hashes are rendered as hex with `Integer.toHexString` (not `java.util.Formatter`, also unported), giving the 32-hex-char length MD5 gave. Gotcha: unqualified `scala.util.hashing...` resolved to `dotty.tools.dotc.core.StdNames.nme.scala` (a `Name` constant `"scala"`, via the file's `import nme.*`) instead of the package; fixed with `_root_.scala`, the idiom used elsewhere in the codebase.
- `transform/MacroAnnotations.scala`: `callMacro`'s reflective invoke (`ClassLoader#loadClass`, absent from scala-native's `java.lang.ClassLoader`, which has only `getResourceAsStream`/`getUnnamedModule`) is replaced with a compile-time error ("macro annotations are not supported by this toolchain"), per the Blocker C decision. The rest of the file (`hasMacroAnnotation`, needed unconditionally by `PostTyper`/`Inlining`) is reflection-free and untouched.
- `core/Phases.scala`, `Compiler.scala`, `transform/CollectEntryPoints.scala`, `config/ScalaSettingsProperties.scala`, `core/SymUtils.scala`/new `core/SymExtensions.scala`: the `backend.jvm`/`backend.sjs` exclusion. Phase-list entries are removed (`Compiler.scala`'s `backendPhases` is `Nil`; nothing here emits JVM bytecode or Scala.js IR, as scala-native does for user code, per the earlier `GenBCode`/`VerifyError` finding); `phaseOfClass(classOf[GenBCode])`-style lookups become `NoPhase`; `DottyBackendInterface.symExtensions` (a self-contained `Symbol` extension bag used by three unrelated core/transform files) moves verbatim to its own file.
- **A wider Scala.js leak than the earlier cut-list research found.** `backend.sjs.JSDefinitions`/`transform.sjs.JSSymUtils` are imported by six ordinary typer/core files (`typer/{Typer,Implicits,ImportSuggestions}.scala`, `core/{TypeErasure,SymUtils,Contexts}.scala`, `core/unpickleScala2/Scala2Unpickler.scala`, `interactive/InteractiveDriver.scala`) plus `Mixin.scala`, `Constructors.scala`, `Memoize.scala`. All is special-casing for Scala.js pseudo-unions/JS types. All but one site (`SymUtils.scala`'s `isConstExprFinalVal`, called unconditionally) is already gated on `ctx.settings.scalajs.value`, which cannot be true once `config.SJSPlatform` is excluded and `core/Contexts.scala`/`interactive/InteractiveDriver.scala` always build a plain `JavaPlatform`. Each dead branch is replaced by its always-taken alternative, with a one-line comment. They were found from compiler errors ("Not Found"/"value X is not a member"), and a later grep for `sjs`/`JSDefinitions`/`isJSType` over the reduced set found only comments and one block of inert generated-table data.
- **Near-miss:** `sbt/interfaces/IncrementalCallback`'s `enabled` was dropped as unused (a grep for `\.enabled(` found nothing outside `backend/jvm`), but `core/Contexts.scala:182` calls `local.enabled` with no parens, which the grep missed. The compile caught it ("value enabled is not a member of ..."); it is restored as `def enabled: Boolean = false`. A "nothing else calls this" grep for a no-arg method must check both call styles, or defer to the compiler.

**Verified:** the `compiler/src` `.scala` files filtered per the exclusions, plus the generated `MiniPhaseOverrides.scala`, compile in one `dotty.tools.dotc.Main` invocation (468 files) against a classpath without the published `scala3-compiler_3` jar (so it does not shadow the sources) but with `scala-library`, `scala3-library`, `scala3-interfaces` and `tasty-core_3`: zero errors, only upstream style warnings (`eq` used infix, `-feature` migration notices). This is a plain-JVM dotc compile, not `scalino-dotc` or NIR: the reduced slice typechecks and pickles on a JVM. Compiling to a scala-native binary is next.

## Self-hosting milestone: `dotty.tools.dotc.Main` compiles, links and runs as a Scala Native binary (2026-09-06)

The reduced dotc is a working, incomplete self-hosted compiler. `dotty.tools.dotc.Main` compiles, links with zero unreachable symbols, and runs on Scala Native with no JVM. With no args it prints dotc's usage text. It parses and typechecks a two-line source (`object Hello { def main(args: Array[String]): Unit = println("hi") }`, via `-javabootclasspath dist/java.base.jar -classpath <nativelibs.cp>`) with exit code 0 and no errors. The frontend, typer, inliner and the full, untrimmed `transformPhases` list ran on real code, compiled by a binary built from dotc's own source with the same toolchain.

**Additions on top of the 468-file JVM-mode pass, each verified by re-running the `-Xplugin:<nscplugin> -Xplugin-require:scalanative -Yretain-trees` compile and the `scalino-linkdriver` link:**

1. **Two more dependencies with no Scala Native build**, found with the same `cs fetch ..._native0.5_3` check: `org.scala-lang:tasty-core_3` and `org.scala-lang:scala3-interfaces` (`cs fetch org.scala-lang:tasty-core_native0.5_3:3.8.4+0.5.12` and `cs fetch org.scala-lang:scala3-interfaces_native0.5_3:3.8.4` fail "not found"). Both are dotty modules with source in `vendor/scala3`: `tasty-core` at `vendor/scala3/tasty/src` (1,608 lines, all `.scala`; not inside `compiler/src`, despite the two-file `compiler/src/dotty/tools/tasty/besteffort/` subtree), `scala3-interfaces` at `vendor/scala3/interfaces/src` (177 lines, 8 `.java` files). Adding `tasty-core`'s source to the compile (no patch needed) closed ~30 tasty-related unreachable symbols, taking the link from 77 unreachable symbols to 24.
2. **Java-to-NIR is missing in general:** `scalino-dotc` never compiles `.java` sources, so any `.java` file in a self-hosted build, with or without external dependencies, must be ported to Scala. Four files were ported: `sbt/interfaces/{IncrementalCallback,ProgressCallback}.java` (known earlier) and `interfaces/src/dotty/tools/dotc/interfaces/*.java` (8 files: `AbstractFile`, `SourceFile`, `SourcePosition`, `Diagnostic`, `DiagnosticRelatedInformation`, `ReporterResult`, `SimpleReporter`, `CompilerCallback`, the `scala3-interfaces` embedding-API types), as 1:1 Scala traits with the same package/class names.
3. **Consequence of porting a Java interface to a Scala trait:** Scala lets an override of a Java-declared abstract method use either call convention (`foo.bar` or `foo.bar()`), but once `interfaces.SourceFile.content()` etc. became Scala trait methods, Scala 3's strict parens rules applied, and the existing code called these methods with both conventions in different files. This gave 34 compile errors over two iterations (11 "must be called with () argument" sites needing parens, then 24 "does not take parameters"/override-incompatible sites needing parens removed from the trait declarations, plus a few stragglers each round). Resolved by matching each of the 8 interfaces to the convention its override sites used (`content()` with parens; `name`, `path`, `jfile`, all of `SourcePosition`'s accessors and all of `Diagnostic`/`ReporterResult`'s nullary). Porting another Java interface needs an iterative, compiler-driven fixup pass; only the compiler shows which convention call sites assume.
4. **Narrow reflection/dynamic-loading gaps**, fixed like `-Yreporter` (Blocker B) and macro annotations (Blocker C) with a compile-time "not supported" instead of a reflective call, found from linker "Unknown method/type/constructor" output:
   - `Driver.scala`'s `-Yreporter <class>` (`Class#getDeclaredConstructor`) is now an error. The earlier Blocker B analysis ("degrades via catch, safe to leave") was right about runtime crashes but wrong about linking: the method does not exist on Scala Native's `Class`, and reachability analysis is static, so even a conditionally reached call fails to link. Same correction for `transform/CheckUnused.scala`'s `-Wunused` parameter-name lookup.
   - `plugins/Plugin.scala`'s dynamic `instantiate`/`load`/`loaderFor` (`Class#getConstructor`, `ClassLoader#loadClass`, `new URLClassLoader(...)`) are errors, consistent with the stance that `-Xplugin` plugins must be baked into the dotc build ("Blocker 2").
   - `core/MacroClassLoader.scala`'s `makeMacroClassLoader` (`new URLClassLoader(...)`): a full-codebase grep shows its result is only passed as a constructor parameter to `Interpreter`/`Splicer.splice`, neither of which calls `.loadClass` on it (both are reflection-free), so it now returns the existing `getClass.getClassLoader`.
   - `quoted/Interpreter.scala`'s `findRealField`/`reflectiveFieldRead`/`reflectiveFieldWrite` (`Class#getDeclaredField`, `java.lang.reflect.Field`), the host-object reflective escape hatch (e.g. `ArrayBuffer#size0` when no intrinsic covers a field mutation), now always fails gracefully, as its doc comments describe for "field not found". This is more conservative than the GraalVM build, which could find registered fields; Scala Native has no registration mechanism. Also removed the dead `AbstractFileClassLoader` REPL-session branch (the REPL module is not in the source set).
   - `config/Settings.scala`'s generic `Setting[Option[T]]` reflective instantiation (`propertyClass.get.getConstructor().newInstance()`): its only user, `-rewrite` (`OptionSetting[Rewrites]`), becomes an unsupported flag.
5. **JDK-API gaps unrelated to reflection**, same pattern (drop the branch, since it was dead or degrades gracefully):
   - `java.lang.Runtime$Version`/`Runtime.version()`: no Scala Native port. Three sites: `config/ScalaSettingsProperties.scala`'s `supportedReleaseVersions` (previously capped at the running JDK's feature version; now reports the full known range); `classpath/DirectoryClassPath.scala`'s `JrtClassPath.apply` (chose between `ct.sym` and live `jrt:/` resolution; `jrt:/` does not exist without a JDK, Blocker 1; now always `None`, the fallback its caller comment documents); `io/ZipArchive.scala`'s `FileZipArchive.openZipFile` (the multi-release-aware `JarFile` constructor; now the plain `ZipFile` constructor, so release-specific classfiles of multi-release jars are not selected, but the jar reads).
   - `java.util.Timer`/`TimerTask`: two best-effort timeout features. `typer/ImportSuggestions.scala`'s per-candidate implicit-search cancellation (the outer `deadLine`/`System.currentTimeMillis` check remains the real timeout) and `Run.scala`'s periodic progress printer (gated by `debugPrintProgress`, a hardcoded-`false` `inline val`, already dead).
   - `java.nio.file.FileStore`: `io/PlainFile.scala`'s `underlyingSource` `"jar"` case (resolving a NIO jar-filesystem entry to its jar). This toolchain reads jars via `ZipArchive`/`java.util.zip`, never NIO's `jar:` provider (the JVM-coupling survey found no NIO `FileSystemProvider` use in `dotty.tools.io`); now `None`.
6. **`java.net.URL` does not exist on Scala Native** (no `URL.scala` in `vendor/scala-native`'s javalib; `java.net.URI` exists). `dotty.tools.io`'s `ClassPath`/`Path`/`AbstractFile` expose `Seq[URL]`/`URL` in public signatures across 13 files (`ClassPath.asURLs`, `Path.toURL`, `AbstractFile.toURL`, and 9 `asURLs` overrides in `classpath/*.scala`/`interactive/LogicalSourcePath.scala`). Nothing in the reduced set reads these for URL semantics (the one consumer, `core/MacroClassLoader.scala`'s `URLClassLoader`, was removed in item 4), and each implementation builds a `URI` before calling `.toURL()`. So all 13 signatures changed from `URL`/`Seq[URL]` to `URI`/`Seq[URI]`, dropping the trailing `.toURL()`. Knock-on: `classpath/ClassPathFactory.scala`'s `classesInManifest`-adjacent code called `.toURI()` on the old `URL` for `java.nio.file.Paths.get(...)`; with a `URI` that call is invalid and was removed. A third-party `java.net.URL` library for Scala Native was considered and set aside: nothing needs real URL semantics, so the `URI` change is simpler and adds no dependency.

**End-to-end verification (real compile, link, run):** `-Xplugin:<nscplugin_3.8.4-0.5.12.jar> -Xplugin-require:scalanative -Yretain-trees` over all 484 files (468 + `tasty/src`'s 8 + `interfaces/src`'s 8) against `nativelibs.cp` (the Scala-Native-cross-compiled stdlib) produced 11,329 `.nir` files, zero errors. `scalino-linkdriver` with entry point `dotty.tools.dotc.Main` produced a standalone 46MB executable with zero unreachable symbols (77, then 24, then 2, then 0 across the fix passes). With no args it prints dotc's usage; on a two-line `Hello.scala` (with `-javabootclasspath`/`-classpath` at `dist/java.base.jar`/`nativelibs.cp`) it exits 0 with no errors, having parsed and typechecked through the full `transformPhases` pipeline.

**Scope.** `backendPhases` is still `Nil`: this binary parses, typechecks, inlines and runs every transform phase through `Constructors`/`LambdaLift`/`Flatten`/etc., but emits no output (no `.class`, no NIR). It does not yet replace `scalino-dotc` (GraalVM-built, which compiles user Scala to NIR with the baked-in `nscplugin`). Replacing it means wiring `nscplugin`'s NIR-generation phases in; the rest of the compiler is proven to compile, link and run. Unsupported or degraded in this build: macro annotations (Blocker C), `-Yreporter`, `--compiler-plugin`/dynamic `-Xplugin` loading, `-rewrite`, `-scalajs`, the interpreter's host-object reflective field access (always falls back to "field not found"), and multi-release-jar-aware classpath resolution. None affects ordinary compilation of ordinary Scala code.

## Wiring in the real backend: nscplugin baked in, end-to-end pipeline works; a non-determinism bug blocks completion (2026-09-06)

Goal: have the self-hosted binary emit NIR for user code with scala-native's `nscplugin`.

**How `nscplugin` gets its phases into the pipeline (not through `Compiler.backendPhases`):** `plugins/Plugins.scala`'s `loadRoughPluginsList` (returns `List[Plugin]`) feeds `addPluginPhases`, which schedules each `StandardPlugin`'s `initialize(options)`-returned `List[PluginPhase]` via `Plugins.schedule`, ordered by each phase's `runsAfter`/`runsBefore`. `-Xplugin-require:scalanative` only asserts presence (`plugs exists (_.name == req)`). So the fix replaces `loadRoughPluginsList`'s dynamic jar-scanning body with `List(new scala.scalanative.nscplugin.ScalaNativePlugin())` (`patches/scala3-0007-bake-in-nscplugin.patch`): no jar scanning, no `plugin.properties` parsing, no classloader. This is the "baked in" posture of Blocker 2 taken further: the GraalVM version still uses `Class.forName` on a build-time-registered class, and Scala Native has no registration mechanism.

**`nscplugin`'s source is needed, as for `tasty-core`/`scala3-interfaces`.** Its only real dependency is `org.scala-lang:scala3-compiler_3` (`provided`, already satisfied by compiling dotc's source in the same command), but the published `nscplugin_3.8.4-0.5.12.jar` has no Scala Native build and must be compiled as part of the self-hosted dotc. Source is in the already-cloned `vendor/scala-native` (`build/00b-setup-vendor.sh` clones it for the user-code pipeline): `nscplugin/src/main/scala-3` (18 files, 7,141 lines), plus two modules its `project/Build.scala` compiles in via `dependsOnSource`: `nir/src/main/scala` (34 files, 5,821 lines) and `util/src/main/scala` (12 files, 631 lines). All plain `.scala`: 64 files, ~13,600 lines, added to dotc's own 486.

**Compile-time fixups in `nscplugin`'s source** (`patches/scala-native-0002-nscplugin-selfhost.patch`):
- `NirGenExpr.scala`/`NirGenName.scala` imported `dotty.tools.backend.jvm.DottyBackendInterface.symExtensions` (the old location); repointed to `dotty.tools.dotc.core.SymExtensions`.
- `NirPrimitives.scala` imports `dotty.tools.backend.jvm.DottyPrimitives`, a self-contained `Symbol`-to-`Int` primitive-op table depending only on core `dotc.*` types and `backend.ScalaPrimitivesOps` (already outside `backend/jvm` and in scope). That one file from `backend/jvm` was added to the source list (an explicit file list, not directory-based, so the rest of `backend/jvm`/ASM is not pulled in).
- `GenNIR.scala`'s `runsBefore = Set(backend.jvm.GenBCode.name)`: `GenBCode` does not exist here; changed to `Set.empty`.
- `NirCodeGen.scala`'s `caseInsensitiveNameOf` used `.toLowerCase(java.util.Locale.ENGLISH)` (determinism, per its doc comment) for a case-insensitive-filesystem name-collision check; `java.util.Locale` does not exist on Scala Native (no `Locale.scala` in the javalib). Switched to `toLowerCase()`, deterministic here since there is no per-locale case table.
- New stub: `nir/src/main/scala`'s `Versions.scala` references `ScalaNativeBuildInfo.version`, normally an sbt-buildinfo-generated object (`buildInfoPackage = "scala.scalanative.nir"`, per scala-native's `project/Build.scala`) absent from the source tree. `build/selfhost/ScalaNativeBuildInfo.scala` (package `scala.scalanative.nir`, `version`/`scalaVersion` hardcoded from `versions.env`) replaces it.

**Verified:** 550 files total (the 486 above, nscplugin+nir+util's 64, `DottyPrimitives.scala`, and the `ScalaNativeBuildInfo` stub; reproducible with `build/selfhost/gen-file-list.sh`) compiled with `-Xplugin:<the published, unpatched nscplugin jar> -Xplugin-require:scalanative` (this activates the plugin in the compiling JVM dotc, independent of patches to the compiled source: two different processes) gave 12,622 `.nir` files, zero errors. `scalino-linkdriver` with entry point `dotty.tools.dotc.Main` gave a standalone 49MB executable with zero unreachable symbols (the only new link gap was `java.util.Locale`/`Locale.ENGLISH`). The binary compiled user code: `/tmp/HelloTest.scala` (`object Hello { def main(args) = println("hi") }`) produced `Hello.nir`/`Hello$.nir`, and `scalino-linkdriver` linked them into an executable. This was the first end-to-end run of dotc-compiled-by-dotc compiling user code with no JVM or GraalVM in the chain.

**Non-deterministic output: root-caused and fixed (2026-09-06, later).** Compiling the same trivial file (`object PrivTest { private def secret(): Int = 42; def main(args) = println(secret()) }`) repeatedly gave different NIR on different runs. `strings` on 5 consecutive runs showed `writeReplace`/`ModuleSerializationProxy`/`Class` (the synthesized Java-serialization support method every `object` gets) present with `main`/`secret` missing in some, and the reverse or other subsets in others.

Ruled out, each by direct test:
- **Not `Pickler.scala`'s `ParallelPickling`/`scala.concurrent.Future` concurrent TASTy assembly.** Forcing `Pickler.ParallelPickling` from `true` to `false` and rebuilding from scratch did not change it.
- **Not threading.** On a JVM with the published `nscplugin` jar, 5/5 compiles of `PrivTest.scala` were byte-identical, so the bug is Scala-Native-specific. On Scala Native, the one `Thread.start()` call site (`Pickler.scala:396`'s `executor.start()`, the only `java.lang.Thread` use in the pipeline; `util/concurrent.scala`'s `Executor extends Thread`) was patched out and the binary relinked with `withMultithreading(Some(false))` forced at the scala-native `Config` level (`src/LinkDriver.scala`'s `--multithreading` flag only forces it on, so this used a one-off standalone driver bypassing `Opts`). The link log showed `Linking (multithreadingEnabled=false)` and the linker's `SystemThreads`-unreachability check passed, so the binary was single-threaded, and the non-determinism remained.
- **Not the typed AST or the frontend/typer/transform pipeline.** Repeated `-Vprint:erasure` dumps of the same input were byte-identical; the bug is inside nscplugin's NIR emission/file writing.

**Root cause (targeted tracing).** Temporary `System.err.println` in `NirGenStat.scala`'s `genMethods` and `NirCodeGen.scala`'s `genCompilationUnit` (reverted) showed `generatedDefns` (a `mutable.UnrolledBuffer[nir.Defn]` of the unit's members) was always the same 5 defns in insertion order (module class header, `<init>`, `writeReplace`, `secret`, `main`). The divergence was in `NirCodeGen.scala`:
```scala
generatedDefns.toSeq
  .groupBy(defn => getFileFor(defn.name.top))
  .foreach(genIRFile(_, _))
```
All 5 defns belong to one top-level module, so `getFileFor` should return the same key and `groupBy` should give one group of 5. The trace showed up to 5 one-element groups, each calling `genIRFile` (which opens and truncates the target file) against the same path, so only the singleton group the `Map` iteration visited last survived; which one varied by run.

`groupBy` failed to merge equal keys because `getFileFor` (`NirCodeGen.scala:171-178`) returns a fresh `PlainFile` (`dir.fileNamed(...)`) on every call, and `PlainFile`'s `equals`/`hashCode` (`vendor/scala3/compiler/src/dotty/tools/io/PlainFile.scala`) were:
```scala
override def hashCode(): Int = System.identityHashCode(absolutePath)
override def equals(that: Any): Boolean = that match {
  case x: PlainFile => absolutePath `eq` x.absolutePath
  case _            => false
}
```
This requires `absolutePath` (`givenPath.toAbsolute.toString.intern`) to be reference-identical across separately built `PlainFile`s for the same path, which holds only if `String#intern()` canonicalizes. Scala Native's `String.intern()` (`vendor/scala-native/javalib/src/main/scala/java/lang/String.scala:559`) is a no-op stub (`def intern(): _String = this`). So two `PlainFile`s for the same path have distinct strings: `equals` returns `false`, and `hashCode` (`System.identityHashCode`, the raw heap address under Scala Native's default `Object.__hashCode()` in `nativelib/.../runtime/Object.scala`) differs and varies with ASLR/heap layout. This is why the bug is Scala-Native-only and independent of threading.

**Fix** (`patches/scala3-0008-fix-plainfile-nondeterministic-hashcode.patch`): `PlainFile.hashCode`/`equals` use value equality on `absolutePath` (`absolutePath.hashCode()` / `absolutePath == x.absolutePath`), correct on every backend and independent of `intern()`. After rebuilding the 550-file binary and relinking under the original repro conditions (`multithreadingEnabled=detect`, as `full-link2`/`full-link3`), `PrivTest.scala` compiled 10 times gave byte-identical MD5 output, with `secret`/`main`/`writeReplace` always present. The resulting `PrivTest$.nir`/`PrivTest.nir` link into an executable that prints `42`, exit 0.

**Second bug (found and fixed the same day): `ArrayBuffer#update`.** `examples/interpreter-regressions/{Test,Foo}.scala` (a macro case: `inline def myMacro(): String = ${ aMacroImplementation }`, needs `-Yretain-trees`) failed 3/3 with `Caused by class java.lang.IndexOutOfBoundsException: 0 is out of bounds (min 0, max -1)` during macro interpretation. The same files and flags compile (exit 0) with the shipped GraalVM `dist/scalino-dotc`.

Root cause (via `SCALINO_INTERP_DEBUG=1`, no new instrumentation): `Regressions.hostObjectMutationVisible`'s `buf.update(0, 99)` on a host `scala.collection.mutable.ArrayBuffer`. `ArrayBuffer#update` has no intrinsic, so its retained source body is tree-interpreted: `checkWithinBounds` reads private `size0`, `mutationCount += 1` writes another private field, and `array(index) = elem` touches a third. That needs `reflectiveFieldRead`/`reflectiveFieldWrite` (`Interpreter.scala`), which the earlier self-hosting pass stubbed to always return `None`/`false`, because Scala Native's javalib has no `java.lang.reflect.Field`/`Class#getDeclaredField` (no `Field.scala` in `vendor/scala-native/javalib`). The read fails, `size0` falls back to replaying its initial value (0) instead of the real size (2), `checkWithinBounds` computes `max = size0 - 1 = -1`, and the in-bounds `update(0, ...)` throws. This is the original symptom described in the interpreter's own doc comments.

**Fix** (`patches/scala3-0009-curate-arraybuffer-update.patch`): curate `mutable.IndexedSeq#update` as an intrinsic in `interpretStdlibIntrinsic`'s host-object dispatch table, calling `b(i) = v` directly instead of interpreting the retained body, like `Array#update`/`Map#update` just above (and like `.apply`/`.size` on `ArrayBuffer`, which is why those never hit the bug). It avoids `size0`/`mutationCount`/`array` rather than working around unreadable fields, is correct on every backend, and also fixes `update` for any other `mutable.IndexedSeq`.

**Verified:** rebuilt the 550-file binary from scratch (`$JAVA -cp compiler.cp dotty.tools.dotc.Main -Xplugin:<nscplugin jar> -Xplugin-require:scalanative -Yretain-trees -classpath nativelibs.cp @reduced-file-list.txt`, then `scalino-linkdriver`): zero compile errors, zero unreachable link symbols. `examples/interpreter-regressions/{Test,Foo}.scala` compiles 3/3, exit 0 (as the GraalVM binary). `PrivTest.scala` 10/10 byte-identical MD5; a `Hello.scala` compile, link and run prints `hi`, exit 0.

**Status at this point:** the self-hosted architecture works (dotc's source plus nscplugin's source linked into one binary that compiles user code to NIR that links to an executable, with no GraalVM in the chain) and gives deterministic, correct output for `Hello.scala`, `PrivTest.scala` and `examples/interpreter-regressions`. The patches (`patches/scala3-0007-bake-in-nscplugin.patch`, `patches/scala3-0008-fix-plainfile-nondeterministic-hashcode.patch`, `patches/scala3-0009-curate-arraybuffer-update.patch`, `patches/scala-native-0002-nscplugin-selfhost.patch`, `build/selfhost/{gen-file-list.sh,ScalaNativeBuildInfo.scala}`) are saved and reproducible but not yet wired into `build/all.sh`. Before it could replace the GraalVM-built `scalino-dotc`: broader coverage (`examples/macro-hello` needs a jsoniter dependency on the compile classpath, not set up here; `examples/upickle-enum-writer-bug` is multi-file and not yet run through this binary), and a platform gap: Scala Native has no field reflection, so any other host object whose private state a macro reaches only through `reflectiveFieldRead`/`Write` will hit the same bug until it gets an intrinsic, which can only be done per case. Promoting it to the default pipeline is a separate decision.

### Broader regression sweep: a third bug (missing `compiler.properties` breaks lazy-val codegen); `.tasty` output via the early-tasty mechanism (2026-09-06, later)

Before starting scalino-lsp, the fixed self-hosted binary (patches 0001-0009) was run against the other regression fixtures to find remaining interpreter/javalib gaps. Rebuilt from scratch first (`$JAVA -cp compiler.cp dotty.tools.dotc.Main -Xplugin:<nscplugin jar> -Xplugin-require:scalanative -Yretain-trees -classpath nativelibs.cp @reduced-file-list.txt`, then `scalino-linkdriver`): clean compile, 12,622 `.nir` files, clean link.

**`examples/upickle-enum-writer-bug` (multi-case-enum JSON round-trip via macro derivation): third self-hosting-specific bug.** Compile succeeded but LINK failed: `Unknown type java.lang.invoke.MethodHandles$Lookup` / `Unknown static method java.lang.invoke.MethodHandles.lookup()`, referenced from `AmbienteAdiacenteParete$`'s static constructor. The shipped GraalVM `dist/scalino run` on the same fixture has no `MethodHandles`/`VarHandle` references in its cached `.nir` for that class.

Root cause, found by diffing the two builds' NIR byte for byte: dotc's Scala 3.8+ lazy-val codegen (`transform/LazyVals.scala`) synthesizes `java.lang.invoke.MethodHandles.lookup().findVarHandle(...)` to get a `VarHandle` for CAS-based lazy-val initialization, for any top-level lazy val (here a `derives ReadWriter`-synthesized recursive typeclass instance). A plain published JVM dotc does the same. In a normal build scala-native's `nscplugin` rewrites this: `AdaptLazyVals.scala` (`nscplugin/src/main/scala-3/.../AdaptLazyVals.scala`) detects VarHandle-based lazy vals (Scala 3.8+) via `compilerUsesVarHandles` and rewrites the field access/CAS calls to `scala.scalanative.runtime.LazyVals$`'s `objCAS`/`get`/`setFlag`, with no `java.lang.invoke` at runtime. The detection broke: `compilerUsesVarHandles` decides "is this dotc 3.8+?" from `dotty.tools.dotc.config.Properties.versionNumberString`, which loads `/compiler.properties` via `pickJarBasedOn.getResourceAsStream(propFilename)`, a classpath resource normally at the root of the published `scala3-compiler_3-*.jar` (`unzip -l scala3-compiler_3-3.8.4.jar` lists a 113-byte `compiler.properties` with `version.number=3.8.4`) and registered in the GraalVM build (`agent-config/scalino-dotc/reachability-metadata.json` has `"glob": "compiler.properties"`). The self-hosted binary compiles dotc from source, never from that jar, so the resource is absent at build and run time: `getResourceAsStream` returns `null`, `scalaProps` is empty and `versionNumberString` is `""`. This fails silently: `ScalaVersion.parse("")` matches `case "" | "any" => Success(AnyScalaVersion)`, so `.toOption` is `Some(AnyScalaVersion)` and the `.orElse(Some(ScalaVersion.current))` fallback (which only fires on `None`) never runs. `AnyScalaVersion` falls through every `SpecificScalaVersion` case to `case _ => false`, so `compilerUsesVarHandles` reports false, `AdaptLazyVals` applies the pre-3.8 field-offset heuristic to a compiler that emits the VarHandle form, nothing is rewritten, and the `MethodHandles`/`VarHandle` calls reach the NIR and fail to link (Scala Native's javalib has no `java.lang.invoke`; no `MethodHandle`/`VarHandle` hits in `vendor/scala-native/javalib`).

**Fix:** a missing build-time resource, not a source patch. `build/selfhost/compiler.properties` has the same four keys as the published jar's copy, with `version.number`/`maven.version.number` pinned to `versions.env`'s `SCALA_VERSION`. Whoever links the self-hosted binary must copy it to `<out>/compiler.properties` in the NIR output directory (`scalino-linkdriver`'s resource embedder scans the link classpath, jars and directories) and pass `--embed-resources` to `scalino-linkdriver` (this flag existed, wired to scala-native's `NativeConfig.withEmbedResources`/`ResourceEmbedder`, but had only been used for user programs, not the compiler itself). Documented in `build/selfhost/gen-file-list.sh`'s header (it is not a `.scala` file, so it cannot go in that script's list).

**Verified:** relinked with the resource and `--embed-resources` (log: `Embedded resource: /compiler.properties`). The `MethodHandles`/`VarHandle` references are absent from the recompiled `AmbienteAdiacenteParete$.nir` (`strings`: zero hits, like the GraalVM binary), the link succeeds, and the binary's output matches the GraalVM build byte for byte (`TipoGenerazione`/`ContestoUrbano`, non-ordinal-0 cases serialized; that fixture's upickle bug was already fixed per item 12). `PrivTest.scala` 10/10 byte-identical MD5s and `examples/interpreter-regressions` 3/3 clean exits with this binary.

**`interpreter/test-fixtures/macros-in-same-project1`**: compiles in a single invocation as expected (also used by `build/05-regen-agent-config.sh`'s tracing).

**`interpreter/test-fixtures/i4515` (two-stage separate compilation): a missing flag, not a missing capability; no source patch needed (2026-09-06, later).** Stage 1 (`Macro_1.scala` alone) compiled but produced only `.nir`, no `.tasty`; stage 2 (classpath at stage 1's output) failed with `Not found: Macro`. The first reading was that dotc's TASTy writing lives in `backend/jvm/ClassfileWriters.scala`/`PostProcessor.scala`, both excluded (they need `scala-asm`), implying a nontrivial port.

That was wrong. In `ClassfileWriters.scala`, `TastyWriter#writeTasty` is a plain `underlying.writeFile(path, bytes)` with no ASM (ASM serves only the `.class` half), and the file's top comment says: "This file is now copied in `dotty.tools.io.FileWriters` in a more general way that does not rely upon `PostProcessorFrontendAccess`." `dotty.tools.io.FileWriters` (package `dotty.tools.io`, not `backend.jvm`, so already in the reduced list) is an ASM-free reimplementation, and `transform/Pickler.scala` already uses it (`Pickler.EarlyFileWriter`, `Pickler.writeSigFilesAsync`). This is dotc's early/pipelined TASTy output (for Zinc pipelining: write `.tasty` once `Pickler` finishes, without the JVM backend), gated by `-Xearly-tasty-output <dir>` (aliases `-Ypickle-write`/`-Yearly-tasty-output`/`-Xpickle-write`); `Run.scala:415`'s trigger is `Phases.picklerPhase.exists && !ctx.settings.XearlyTastyOutput.isDefault`, with no backend dependency. Since this build schedules nscplugin's `GenNIR` instead of `GenBCode`, this is the only way to get `.tasty` from the binary, and it was compiled in and simply never passed the flag.

**Verified, no source changes:** rebuilt the binary from the current tree (patches 0001-0009 + `compiler.properties`), relinked with `--embed-resources`: clean compile (12,622 `.nir`), clean link, zero unreachable symbols. Stage 1 with `-Xearly-tasty-output /tmp/i4515-stage1` (same dir as `-d`) produced `Macro.nir`/`Macro$.nir`/`Macro.tasty` (non-empty). Stage 2 with `-classpath` including stage 1's output exited 0 and produced `Test.nir`/`Test$.nir`, resolving `Macro`'s signature from the `.tasty` alone. Linking both stages' NIR via `scalino-linkdriver` and running exits 0, matching `dist/scalino-dotc` on the same fixture (which writes `.class`+`.tasty` through the same early-output mechanism). `PrivTest.scala` 5/5 byte-identical MD5s, `examples/interpreter-regressions` 3/3 clean exits.

**For `scalino`'s build tooling (not done):** a driver invoking the self-hosted binary must always pass `-Xearly-tasty-output <dir>` (the `-d` dir is simplest) for incremental/multi-module compilation. This is a CLI-argument change in the driver, not a compiler patch.

**`~/scala/ape`: not attempted in this pass.** `scalino`'s build model is incremental/multi-invocation by default (`ScalinoCli.scala`'s doc comment: "compilation is incremental by default... unchanged sources... reused from the last build"). Whether `ape`, a multi-file, dependency-heavy project, needs cross-invocation TASTy reads even under `--no-incremental` (a single dotc invocation over all sources avoids the gap, as in every fixture above) was unknown. It needed reading `ape`'s macro usage file by file or running it end to end, and was left as the next check.

**Sweep summary:** three self-hosting-specific correctness bugs were found and fixed (non-deterministic NIR output, `ArrayBuffer#update` reflective-field gap, missing `compiler.properties` breaking lazy-val codegen). One apparent gap (no `.tasty` output) needed only the existing `-Xearly-tasty-output` flag. Cross-invocation separate compilation works end to end. `~/scala/ape` was still untried. New artifact: `build/selfhost/compiler.properties`; no source patches for the `.tasty` finding. Still not wired into `build/all.sh`/the shipped pipeline.

## `~/scala/ape` validation: a severe self-hosting-specific performance bug (2026-09-06/07)

The untried check from the sweep: the self-hosted binary (patches 0001-0009, `compiler.properties`) on `~/scala/ape`'s full `src/`+`test/` (38 files; http4s/upickle/porcupine/cats-effect/cats-parse dependencies, `uri"..."` literal macros, multi-case-enum `derives ReadWriter`), using the classpath (`.scalino-build/deps-cache/*.cp`) that the shipped `scalino test .` resolves.

**Baseline (re-measured; the earlier "42 tests passed" figure predates `-javabootclasspath` being required and used different flags):** `dist/scalino-dotc` (GraalVM native-image), same classpath, same 38 files, same `-Xplugin`/`-Yretain-trees`/`-javabootclasspath` flags: exit 0 in 2:14 (134s).

**The self-hosted binary on the same input did not finish within 20+ minutes.** It was working, not hung: two `lldb -p <pid> -o "thread backtrace" -o detach` samples ~20s apart and a later third showed 100% CPU, with the top of stack in `Interpreter.interpretTree0`'s recursive descent, deep in calls repeatedly evaluating `scala.sys.package.env` (`sys.env`).

**Root cause (confirmed by microbenchmark).** `Interpreter.scala`/`Splicer.scala` (added by `patches/scala3-0001-own-implementation-tasty-interpreter.patch`; not upstream dotc) gate ~35 `System.err.println` debug-tracing sites, hit on every node of `interpretTree0`'s descent (every `interpretStaticCall`/`interpretInstanceCall`/`matchCase`), behind `if sys.env.contains("SCALINO_INTERP_DEBUG") then ...`, re-querying `sys.env` on every recursive step. On a JVM this is cheap: `scala.sys.package.env` calls `System.getenv()`, which returns a map parsed once at JVM startup. A 2,000,000-iteration microbenchmark of `sys.env.contains(...)` (`/tmp/ape-check/EnvBenchJVM.scala`) took 4.8s on plain JVM dotc. The same benchmark (`/tmp/ape-check/EnvBench.scala`) built with the self-hosted toolchain and run as a Scala Native executable took 34.8s: ~7.2x per call (~17.4µs vs ~2.4µs). Scala Native's `System.getenv()` (`vendor/scala-native/javalib/.../System.scala`) caches the parsed env in a module-level `val` (`EnvVars.envVars`), so that is not the bottleneck; the rest of the per-call cost, multiplied over the hundreds of thousands of interpretation steps in a macro-heavy project, gave the overrun against GraalVM's 134s. This is a javalib cost difference invisible at small scale; performance, not correctness.

**Fix** (`patches/scala3-0010-cache-interp-debug-env-check.patch`): two `private[dotc]` vals (`Interpreter.debugEnabled`, `Interpreter.debugMatchEnabled`) in `Interpreter`'s companion object, computed once from `sys.env.contains(...)`, replace all ~35 call-site `sys.env.contains("SCALINO_INTERP_DEBUG"[_MATCH])` checks in `Interpreter.scala`/`Splicer.scala`. The env var cannot change mid-compile, so this is correct on every backend.

**Result:** `~/scala/ape`'s `src/`+`test/` compiles in 3:22 (202s), exit 0, ~1.5x slower than GraalVM's 134s (a tree-walking interpreter doing recursive macro evaluation will not match an AOT-optimized binary; closing that is a larger task). `lldb` samples of the fixed process show no `sys.env`/`Map.from`; they land in `TypeOps.asSeenFrom`, `SymDenotation.overridingSymbol` and GC allocation, so the multi-minute run was never stuck, just paying the per-step cost.

**Bug in the first draft of the fix, caught before shipping.** The first attempt used `replace_all` to swap every `sys.env.contains("SCALINO_INTERP_DEBUG"[_MATCH])` site for `debugEnabled`/`debugMatchEnabled`, which also rewrote the two new companion declarations into `private[dotc] val debugEnabled: Boolean = debugEnabled`, a self-referential module-level `val`. It compiles, but reads its own zero default (`false`) at initialization, so `debugEnabled` would be permanently `false` and `SCALINO_INTERP_DEBUG` tracing silently disabled. Noticed while reconstructing the patch file (the "before" reconstruction did not round-trip). Fixed by writing the two vals' right-hand sides explicitly. Re-verified: rebuilt from scratch (clean compile, 12,622 `.nir`; clean link), `PrivTest.scala` 10/10 byte-identical MD5s, `examples/interpreter-regressions` 3/3 clean exits, `SCALINO_INTERP_DEBUG=1` still prints trace output (`[interp] Block: ...`, `[spliceFallback] ...`), and `~/scala/ape` exit 0 with the same timing. Saved as `patches/scala3-0010-cache-interp-debug-env-check.patch`; applies cleanly in sequence (0001 through 0010) on a fresh clone, with files identical to the working tree.

**Linked and ran the test suite as well** (the original bar: "42 tests passed" under GraalVM). `~/scala/ape`'s `src/`+`test/` plus its generated `.scalino-build/_scratch/ScalinoCliTestMain.scala` (the munit runner entry point that `scalino test .` generates; `sbt.testing.Framework`/`EventHandler`-based, runs all 9 suites, prints a `passed`/`failed`/`errored`/`skipped` tally) compiled through the self-hosted binary and linked with `scalino-linkdriver`.

The first link failed (`LinkingException: Unreachable symbols... java.time.OffsetDateTime, cats.FlatMap$Ops`) because of the verification command: the link classpath had only the run's NIR and `nativelibs.cp`, not the dependency jars (http4s/cats-effect/scala-java-time/etc.), which `scalino-linkdriver` needs as well as the compile step. With the full classpath (own output + `maindeps.cp` + `testdeps.cp` + `nativelibs.cp`): clean link, zero unreachable symbols, 9.7s.

**GraalVM baseline today** (the "42 tests" figure is stale; the project has grown): `dist/scalino test .` gives 54 passed, 0 failed, 0 errored, 0 skipped, across 9 suites (`db.{Backup,BuildingRepo,Schema}Test`, `domain.{AmbienteNonRiscaldato,ClimateCatalog,DhwCalc,PrimaryEnergy,Raccomandazioni}Test`, `http.RoutesTest`).

**The self-hosted binary's linked test executable: 54 passed, 0 failed, 0 errored, 0 skipped**, identical suite by suite, in 0.56s for the test run (compile and link time separate). This was the first time a dependency-heavy third-party test suite was compiled, linked and run correctly by a self-hosted dotc with no JVM or GraalVM, matching the GraalVM-built compiler.

**Lesson:** a blanket find-and-replace is easy to approve by eye. This one broke the declarations it introduced, and `git diff` looks like a uniform substitution. Compile, link and the standard regression suite also passed, because no regression check sets `SCALINO_INTERP_DEBUG`, so `debugEnabled` being permanently `false` looked correct. Verify the new declarations a refactor introduces, not only the call sites it rewrites.

## Self-hosting scalino-lsp: Phase 2 starts (2026-09-07)

Phase 1 (scalino-dotc self-hosting) is done and validated on a real project. Phase 2 self-hosts `scalino-lsp`, dotty's pre-Metals language server. The expected biggest risk, JSON-RPC/`lsp4j` (reflection-heavy, no Scala Native story), was already retired: the GraalVM-era work ("JVM-free language server (LSP)") replaced `lsp4j`/Gson/Jackson with a hand-rolled `Main.scala` (Content-Length JSON-RPC framing, single-worker-thread dispatch) and hand-written `jsoniter-scala` codecs in `Lsp.scala`, with no reflection. `build/08-build-scalino-lsp.sh` already trims the shipped `scalino-lsp` to four source files (`Lsp.scala`, `Main.scala`, `DottyLanguageServer.scala`, `Memory.scala`); `patches/scala3-0002-trim-language-server.patch` dropped the worksheet (JVM-subprocess REPL) and TASTy-decompiler endpoints, which still reference `lsp4j` types and are out of scope. `DottyLanguageServer.scala`'s only compiler-facing dependency, `dotty.tools.dotc.interactive.*`, is already in the 550-file dotc source list (`compiler/src` minus a short exclusion list), so Phase 1 had covered nearly the whole dependency surface.

The one external dependency, `jsoniter-scala-core`, has a version-matched Scala Native build on Maven Central (`jsoniter-scala-core_native0.5_3:2.37.3`, the version the GraalVM build pins). Unlike `tasty-core`/`scala3-interfaces`/`nscplugin` in Phase 1, it needs no source porting, only the jar on the compile and link classpaths.

**Done:** the 4 LSP source files compiled in a self-hosted JVM-mode compile alongside the 550-file dotc list (`nscplugin.cp` + `nativelibs.cp` + the JVM `jsoniter-scala-core_3` jar on `-classpath`; same `-Xplugin:<nscplugin jar> -Xplugin-require:scalanative` recipe as Phase 1). It compiled with zero errors (12,863 `.nir`/`.class`/`.tasty` files) once `nativelibs.cp` was on the compile classpath. That was needed regardless of LSP: `scala.scalanative.unsafe.extern` etc., referenced in dotc's existing source, are not resolvable from `nscplugin.cp` alone, a latent gap in Phase 1's classpath assembly.

Linking (`scalino-linkdriver`, entry point `dotty.tools.languageserver.Main`) hit three sequential unreachable-symbol gaps, each fixed that session:
1. **`java.util.logging.Logger`**, used only by two best-effort `logger.warning(...)` calls on an already-swallowed exception in `dotc/interactive/Completion.scala` (dead code for a batch compile; the only caller is LSP `textDocument/completion`). Scala Native's javalib has no `java.util.logging` (no files under `vendor/scala-native/javalib/.../util/logging`) and there is no external polyfill. `patches/scala3-0011-lsp-selfhost-no-java-util-logging.patch` replaces the `Logger`-backed `logger` val with a two-line local object whose `warning(msg)` calls `System.err.println`. This is correct on every backend (diagnostics on a swallowed exception).
2. **`java.time.Instant`/`ZoneOffset`** (used by `Main.scala`'s `.scalino-lsp.log` timestamps) and, transitively, **`java.util.Locale`** (needed by `java.time`'s formatter machinery). Neither is in scala-native's javalib (`Locale` also came up in the `nscplugin` `caseInsensitiveNameOf` fix). External polyfill jars cover them: `scala-java-time_native0.5_3:2.6.0`, plus `scala-java-locales_native0.5_3:1.5.4` + `cldr-api_native0.5_3:4.5.0` + `portable-scala-reflect_native0.5_2.13:1.1.3` (the locales polyfill's service-loader dependency). All four go on the link classpath; no source patch.

With those additions the link succeeded: zero unreachable symbols, a ~51MB standalone `dotty.tools.languageserver.Main` executable.

**Ran it as a server:** a hand-framed `initialize` JSON-RPC request (`Content-Length: N\r\n\r\n{...}`) piped into the binary with `-stdio` returned a correctly framed response on stdout. The transport (Content-Length reader, envelope decode via the `jsoniter-scala` codecs, worker-thread dispatch, response encode) works on Scala Native, built entirely by the self-hosted toolchain.

**Bug found by that test, fixed (2026-09-07):** the `initialize` response was a JSON-RPC error: `scala.scalanative.runtime.UndefinedBehaviorError` inside `Memory.isCritical()` (`DottyLanguageServer.initialize` calls `checkMemory` on startup), from `java.lang.Runtime.getRuntime().{maxMemory,totalMemory,freeMemory}`. scala-native's `java.lang.Runtime` (`vendor/scala-native/javalib/src/main/scala/java/lang/Runtime.scala`) declares only `gc()`, not those three. It compiled cleanly because the JVM-mode compile step runs on a real JVM (GraalVM's `java`), whose bootstrap classloader supplies the JDK's `java.lang.Runtime` ahead of anything on `-classpath`, including `nativelibs.cp`'s scala-native javalib jar, so dotc's typer resolves these calls against the JDK class. nscplugin still emits NIR calls to the nonexistent scala-native methods; the only signal is the linker's non-fatal "can't call ... on Runtime$" warnings in the "checking intermediate code" step, easy to miss. **A new risk category for this effort:** a method that exists on the JDK class but not on scala-native's same-named class typechecks against the JDK class in a JVM-mode self-hosted compile, shows up only as a non-fatal link warning, then traps at runtime. Other calls in dotc's or nscplugin's source with this shape may be hiding.

**Fix, no platform split:** `Memory.scala` need not use `Runtime`. `java.lang.management.ManagementFactory`/`MemoryMXBean`/`MemoryUsage` (`vendor/scala-native/javalib/src/main/scala/java/lang/management/{ManagementFactory,MemoryMXBean,MemoryUsage}.scala`) is implemented on Scala Native (`MemoryMXBean`'s `getHeapMemoryUsage()` uses `GC.get{Used,Max,Init}HeapSize()`) and is also the standard JDK API on the GraalVM side (`java.management` is a JDK module native-image supports). `isCritical()`/`stats()` now call `ManagementFactory.getMemoryMXBean().getHeapMemoryUsage()`, one change to shared source correct on every backend. Saved as `patches/scala3-0012-memory-use-management-api.patch`; applies cleanly in sequence (0001-0012) on a fresh clone (checked with `git reset --hard && git clean -fd` and a full reapply on this session's vendor checkout).

**Verified on both sides:** the rebuilt self-hosted LSP binary (554-file compile, link with zero unreachable symbols) now returns a success `initialize` result (`"result":{"capabilities":{...}}`). The GraalVM `dist/scalino-lsp` rebuilt with the same patch (native-image clean) was run through `build/lsp-trace-drive.py` (the full-endpoint functional test) against both binaries, with a fresh `.dotty-ide.json` (`dist/scalino setup-ide` on `build/lsp-trace-fixture`, avoiding the stale-path issue from the LazyVals-era trace). **GraalVM: 8/8 endpoints pass** (`initialize`/`hover`/`definition`/`completion`/`references`/`rename`/`documentSymbol`/`workspace/symbol`). **Self-hosted: 2/8** (`initialize`, `workspace/symbol`); the other six hit `java.util.NoSuchElementException: next on empty iterator` in `DottyLanguageServer.configFor` (`.head` on an empty `Iterable`), unrelated to `Memory.scala`, then open.

**The `configFor`/`NoSuchElementException` gap: root-caused and fixed (2026-09-07).** Rebuilt the self-hosted LSP binary and regenerated a fresh `.dotty-ide.json` with `dist/scalino setup-ide` in `build/lsp-trace-fixture`. A stale-path `.dotty-ide.json` (under a renamed directory) was in the fixture, but regenerating it did not fix the failure, so this is a different bug. `.scalino-lsp.log` had the cause one line above the misleading `configFor` crash: `drivers: failed to construct driver for project 'lsp-trace-fixture': java.util.NoSuchElementException: key not found: posttyper`, thrown inside `new InteractiveDriver(settings)` and caught by the per-project `NonFatal` guard (see the "fixture mistake" item above). With one project configured, `myDrivers` ends up empty, so every later request dies in `configFor`'s `drivers.keys.head` fallback.

Root cause: `InteractiveCompiler.phases` (`compiler/src/dotty/tools/dotc/interactive/InteractiveCompiler.scala`) overrides the phase list to a minimal frontend pipeline for IDE latency (`Parser`, `TyperPhase`, `SetRootTree`, `CookComments`), with no `PostTyper`. Every compile runs `Run.compileUnits`'s `ctx.base.addPluginPhases(ctx.base.phasePlan)` (`Run.scala:355`), which schedules active plugins' phases by resolving their `runsAfter`/`runsBefore` names (`Plugins.schedule`). nscplugin's `PrepNativeInterop.runsAfter = Set(transform.PostTyper.name)` ("posttyper") resolves in a full batch phase list but not in `InteractiveCompiler`'s. Patch 0007 (`loadRoughPluginsList`, "Wiring in the real backend") activates the plugin unconditionally, unlike unpatched dotc, which activates a plugin only when `-Xplugin:<jar>` is passed (`InteractiveDriver` never does, since its settings come from `.dotty-ide.json`'s `compilerArguments`). So every `InteractiveDriver` construction in the self-hosted binary tried to schedule nscplugin's phases into a plan that cannot satisfy them.

**Why the GraalVM `dist/scalino-lsp`/`dist/scalino-dotc` are unaffected:** `Plugins.scala` is shared source, but `build/08-build-scalino-lsp.sh` (and `03a-patch-compiler.sh`, which produces the `compiler-patched.cp` jar it compiles against) builds the GraalVM binaries from the published, essentially unmodified `scala3-compiler_3` jar, overlaying only a narrow set of classes from patched source (`quoted/Interpreter*`, `transform/Splicer*`, `transform/MegaPhase*`; see `03a-patch-compiler.sh`'s header). `plugins/Plugins.scala` is not among them; patches 0007/0013 apply only to the full `vendor/scala3` tree this self-hosting compiles. Checked: `build/lsp-trace-drive.py` against the installed `dist/scalino-lsp` with the fresh `.dotty-ide.json` passes 8/8.

**Fix** (`patches/scala3-0013-gate-baked-in-plugin-on-require-flag.patch`): restore dotc's opt-in gating on top of the no-jar-scanning simplification. `loadRoughPluginsList` returns `List(new ScalaNativePlugin())` only when `ctx.settings.require.value.contains("scalanative")` (`-Xplugin-require:scalanative` passed, as every batch self-hosted compile does), and `Nil` otherwise. Applies cleanly as 0001-0013 on a fresh clone.

**Verified:** rebuilt the self-hosted LSP binary (557-file compile: the 550 dotc files, the 4 LSP files, plus the decompiler-support files `IDEDecompilerDriver.scala`/`PartialTASTYDecompiler.scala`/`TASTYDecompiler.scala`/`DecompilationPrinter.scala` that `DottyLanguageServer.decompilerDriverFor`'s import needs, none ASM-dependent; zero compile errors; link with zero unreachable symbols using the compile-time `jsoniter-scala-core_3` jar plus link-time native jars `jsoniter-scala-core_native0.5_3-2.37.3`, `scala-java-time_native0.5_3-2.6.0`, `scala-java-locales_native0.5_3-1.5.4`, `cldr-api_native0.5_3-4.5.0`, `portable-scala-reflect_native0.5_2.13-1.1.3`). With a fresh `.dotty-ide.json`, `build/lsp-trace-drive.py` passes 7 of 8 endpoints (`initialize`/`hover`/`completion`/`references`/`rename`/`documentSymbol`/`workspace/symbol`); the `configFor` crash is gone and `.scalino-lsp.log` shows every request succeeding server-side. `definition` also completes server-side (~40s per the log timestamps) but the script's 25s client timeout drops it: cold-first-typecheck latency (consistent with the interpreter-heavy path being ~1.5-7x slower than JVM per operation), not a correctness bug. A `--mode:release-fast` link, per the earlier `~/scala/ape` finding, is the first thing to try. `PrivTest.scala` 5/5 byte-identical MD5s and `examples/interpreter-regressions` 3/3 clean exits with the fix.

**State:** `patches/scala3-0011-lsp-selfhost-no-java-util-logging.patch`, `0012-memory-use-management-api.patch` and `0013-gate-baked-in-plugin-on-require-flag.patch` apply cleanly in sequence after 0001-0010 on a fresh clone. No script yet for the LSP file list and link classpath (7 extra source files, 5 extra link-only jars); one should be written (e.g. `build/selfhost/gen-lsp-file-list.sh` plus a documented jar list). Not done: the `definition` cold-start latency; wiring any of this into `build/all.sh`. Most of the feared risk (JSON-RPC, `lsp4j`, JVM-only threading/streaming) was already a non-issue, all four bugs found were fixed, and 7 of 8 LSP endpoints work on a self-hosted binary with no JVM; Phase 2 was earlier-stage than Phase 1's real-world validation but close.

## Reproducible build script for Phase 1: `build/selfhost/build-dotc.sh` (2026-09-07)

Phase 1's recipe (patches 0001-0010, `build/selfhost/{gen-file-list.sh,ScalaNativeBuildInfo.scala,compiler.properties}`) was reproducible only by following prose in this document. `build/selfhost/build-dotc.sh` is a single idempotent script that (1) applies all patches via `build/00b-setup-vendor.sh`, (2) generates the ~550-file source list via `build/selfhost/gen-file-list.sh`, (3) compiles it with a plain JVM dotc (`$JAVA -cp compiler.cp dotty.tools.dotc.Main -Xplugin:<nscplugin jar> -Xplugin-require:scalanative -Yretain-trees -classpath nativelibs.cp -d <out> @file-list`) and copies in `compiler.properties`, (4) links via `dist/scalino-linkdriver` with `--embed-resources` (classpath `<out>:nativelibs.cp`; the NIR output alone lacks `java.lang.Object` etc., which need the Scala-Native-compiled stdlib), producing `dist/scalino-dotc-selfhosted` (it never touches the GraalVM-built `dist/scalino-dotc`), and (5) smoke-tests by compiling, linking and running `examples/Hello.scala` with the new binary and asserting the output. Not wired into `build/all.sh`; still opt-in.

Two gaps found while writing it, now handled in the script: the link classpath needs `nativelibs.cp` next to the NIR output dir (otherwise `LinkingException: Class info not available for Top(java.lang.Object)`), and the self-hosted binary needs `-javabootclasspath dist/java.base.jar` to compile any code (otherwise `AssertionError: asTerm called on not-a-Term val <none>` inside `Definitions.init`, since `-classpath` alone has no JDK classes).

**Verified from a run of the script:** clean compile, clean link (zero unreachable symbols), smoke test passed (`hello from scalino`). `PrivTest.scala` compiled 10 times with the script's binary gave byte-identical MD5s on both `.nir` outputs, so it reproduces the bug-fixed state.

## `--mode release-fast` for the LSP `definition` latency; a scala-native linker stack overflow (2026-09-07)

Phase 2's last open item was `definition` taking ~40s server-side in a debug-mode link, past `build/lsp-trace-drive.py`'s 25s timeout. Relinking the same NIR with `--mode release-fast` (`scalino-linkdriver ... --mode release-fast --embed-resources`, `scala.scalanative.build.Mode.releaseFast`) first hit a `StackOverflowError` in `scalino-linkdriver` itself (a GraalVM native-image tool, not the binary being produced) during its release-fast optimization, in `scala.scalanative.codegen.Lower$Impl.findNonRecursive` (`vendor/scala-native/tools/.../codegen/Lower.scala:66-72`): a non-tail-recursive CFG-predecessor walk (one frame per predecessor block) used by null-guard elimination. This is a scala-native linker limitation, not caused by any patch here: `release-fast`'s inlining/lowering gives deeper CFGs than `debug`, and dotc's methods are large and branchy, so this input exceeds the driver's default thread stack. Reproduced twice with the same trace; `Lower.scala` confirmed the recursion; the crash is in the driver's worker thread (`pool-5-thread-1`), not the produced binary.

**Fix, no source patch:** `scalino-linkdriver` is a native-image executable and honors `-Xss<size>` for worker-thread stack size: `dist/scalino-linkdriver -Xss64m <cp> <workdir> <mainClass> <clang> <clang++> info --mode release-fast --embed-resources`, with `-Xss64m` as the first argv token (the native-image runtime consumes it before `LinkDriver.main` sees `args`). 64m sufficed: clean link, zero unreachable symbols, ~38.5MB binary (51MB in debug). General rule for the self-hosting effort: if `scalino-linkdriver` itself crashes with `StackOverflowError` during optimization (not the produced binary at its runtime), try `-Xss<size>` on the driver before assuming a linker bug needs a source fix.

**Verified:** relinked the LSP binary in release-fast with patches 0001-0013 (confirmed by a fresh recompile of the 558-file LSP+dotc list against the current tree; the first release-fast attempt reused a stale pre-0013 NIR output and reproduced the old `configFor`/`NoSuchElementException`, confirming that bug is specific to patch 0013's absence, not to the mode). `build/lsp-trace-drive.py`: 8/8 endpoints pass: `initialize` 401ms, `hover` 88ms, `definition` 9805ms (from ~40s in debug, under the 25s timeout), `completion` 2065ms, `references` 26ms, `rename` 3ms, `documentSymbol`/`workspace/symbol` near-instant; 15.9s wall-clock for the run.

**Phase 2 status:** all known open items closed. The self-hosted `scalino-lsp` passes every endpoint `build/lsp-trace-drive.py` exercises at acceptable latency, with no JVM or GraalVM in the binary. Remaining: a decision on wiring either self-hosted binary into `build/all.sh`.

## Reproducible build script for Phase 2: `build/selfhost/build-lsp.sh` (2026-09-07)

The LSP counterpart of `build/selfhost/build-dotc.sh`. `build/selfhost/gen-lsp-file-list.sh` extends `gen-file-list.sh`'s dotc list with the 4 LSP files plus 4 decompiler-support files (558 total, matching the hand-validated count). `build/selfhost/build-lsp.sh` applies patches, generates that list, compiles it (plain JVM dotc + nscplugin jar + `lsp.cp`'s JVM `jsoniter-scala-core_3` jar), links via `scalino-linkdriver -Xss64m ... --mode release-fast --embed-resources` against `nativelibs.cp` plus the 5 link-only Scala-Native jars, writes `dist/scalino-lsp-selfhosted` (never touching `dist/scalino-lsp`), then smoke-tests via `build/lsp-trace-drive.py` against `build/lsp-trace-fixture` (regenerating `.dotty-ide.json` first with `dist/scalino setup-ide`).

Jar-fetch wrinkle: `cs fetch --intransitive` with all 5 native coordinates in one call still pulled in `jsoniter-scala-core_native0.5_3`'s transitive scala-native runtime deps (a second copy of javalib/posixlib/nativelib/clib at an older scala-native version than `nativelibs.cp`'s 0.5.12): 1045 duplicate-symbol linker errors, then, narrowed to that one jar, `cannot use 'throw' with exceptions disabled` in that older nativelib's `eh.cpp`. `--intransitive` works with exactly one coordinate per invocation, so each jar is fetched with its own `cs fetch --intransitive <one-coordinate> --classpath` and the results concatenated.

**Verified from a run of the script:** clean 558-file compile, clean release-fast link (zero unreachable symbols; `-Xss64m` needed), and `lsp-trace-drive.py` passes 8/8 (`initialize` 415ms, `hover` 76ms, `definition` 9804ms, `completion` 2050ms, `references` 26ms, `rename` 3ms, `documentSymbol`/`workspace/symbol` near-instant), matching the manual result.

## Stress-testing the self-hosted LSP on a multi-file project (`~/scala/ape`): one bug found (2026-09-07)

`build/lsp-trace-drive.py`'s fixture is a small 3-file single-directory project. It covers incremental `didChange` but not cross-package resolution across multiple `sourceDirectories`, nor whether editing a definition file invalidates a different unedited usage file. `build/lsp-stress-ape.py` (a reusable sibling fixture) tests that against `~/scala/ape`'s 19-file, 6-directory tree: `initialize` on a multi-dir project, `didOpen` on two files in different packages (`domain/Building.scala` defining `BuildingInput`, `db/BuildingRepo.scala` importing and using it), `hover`/`definition`/`references` at the cross-package use site, then a `didChange` injecting a type error into the definition file and a re-`hover` on the unedited usage file.

Fixture setup: `dist/scalino setup-ide src` alone yields a `.dotty-ide.json` missing ape's declared dependencies (http4s/upickle/cats/porcupine): `scalino setup-ide` reads `// > using dep` directives only from files it is given, and `project.scala` (where ape declares them) is not under `src/`. `scalino setup-ide src project.scala` includes them. Not a self-hosting bug.

**Cross-package resolution is correct on the self-hosted binary:** `hover`/`definition`/`references` at the `BuildingInput` use site resolve across the `domain`-to-`db` boundary, and editing `domain/Building.scala` to add a type error produces diagnostics there while a later `hover` on the unedited `db/BuildingRepo.scala` still returns a live result (cross-file dependency tracking works).

**Bug (self-hosting-specific): `definition`/`references` responses had a malformed `uri`:** `file:/Users/.../Building.scala` (one slash) instead of `file:///Users/.../Building.scala`, which the LSP spec and editors expect and the `didOpen` URIs use. The same script against the GraalVM `dist/scalino-lsp` gives `file:///...`. This is not cosmetic: an editor matching a `definition` URI against its open-buffer URIs would treat every self-hosted jump-to-definition as a different, unopened document.

**Root cause (from source):** `InteractiveDriver.toUriOption` (`compiler/src/dotty/tools/dotc/interactive/InteractiveDriver.scala:334`) uses `Paths.get(file.path).toUri`. On a JVM, `sun.nio.fs.UnixUriUtils.toUri` builds `"file:" + "//" + <path>` (the path already starts with `/`) and parses it. Scala Native's `java.nio.file.Path` (`vendor/scala-native/javalib/.../nio/fs/unix/UnixPath.scala:63-72`, identically `.../windows/WindowsPath.scala:215-224`) uses the 7-argument `java.net.URI(scheme, userInfo, host, port, path, query, fragment)` constructor with `host = null`. `java.net.URI` (`vendor/scala-native/javalib/src/main/scala/java/net/URI.scala:103-105`) emits the authority and `"//"` only `if (userinfo != null || host != null || port != -1)`, so the result is `file:/path`. This is a bug in scala-native's `UnixPath`/`WindowsPath` `toUri()`, not dotc/nscplugin, not caused by any patch here, and not LSP-specific: any Scala Native program calling `Path#toUri()` on an absolute local path gets it.

**Fix, correct but initially ineffective:** both `UnixPath.scala` and `WindowsPath.scala` pass `host = ""` (empty, not `null`), which signals "authority present but empty" so the constructor emits `"//"`: `file:///path`. Saved as `patches/scala-native-0003-fix-path-touri-missing-authority-slash.patch`; applies cleanly after `scala-native-0001`/`0002` on a fresh clone. Rebuilding the self-hosted LSP with it did not change behavior (still `file:/...`) because the link classpath (`nativelibs.cp`, from `build/01-fetch-deps.sh`) uses the published `javalib_native0.5_3-0.5.12.jar` from Maven Central, not anything compiled from the patched `vendor/scala-native/javalib`. Self-hosting so far covered the compiler (dotc+nscplugin); the runtime library that produced binaries link against is the published distribution, so edits to `vendor/scala-native` source had no effect on it. The patch was inert.

**Left open then:** making it take effect needs either (a) reporting it upstream to scala-native, or (b) building `javalib` from patched source and putting that jar ahead of the published one on every relevant link classpath, a larger change affecting every scala-native build here. The patch file stays as the fix for whichever path is taken.

`definition`'s ~9-10s latency (release-fast) reproduced on ape's larger project, consistent with the earlier figure.

## Building a patched `javalib` from source: scope investigation (option (b) above; not done in this pass) (2026-09-07)

Question: is building `vendor/scala-native/javalib` from source (so `patches/scala-native-0003`'s fix reaches a linked binary) thin or a real undertaking? A real undertaking, with three blockers found empirically. No source changed (work in `/tmp` scratch, cleaned up).

1. **The published `javalib_native0.5_3.jar` cannot be a partial-recompile base.** Compiling only the patched files (`UnixPath.scala`/`WindowsPath.scala`, plus `java/net/URI.scala` for its named-constructor-arg dependency) against the published jar, as `build/03a-patch-compiler.sh` does for the compiler, fails because `commonJavalibSettings`'s `NIROnlySettings` (`vendor/scala-native/project/Settings.scala:694`) strips `.class`/`.tasty` from javalib's jar (the published jar is 3421 `.nir`, 0 `.class`, 0 `.tasty`). A `.nir` carries no type information usable by dotc's frontend, so anything outside the passed source files (`URIEncoderDecoder`, `UnixFileSystem`, `Files`, ...) is invisible, unlike `scala3-compiler_3`'s jar (real `.class` files, which is why 03a works). So compiling any of javalib means compiling nearly all of it as one unit.

2. **Compiling javalib's ~601 files together (`src/main/scala` + the `scala-3` version overlay; found with `find .../javalib/src/main -maxdepth 1 -type d`) hits a pre-existing interpreter gap**, not caused by the Path patch or self-hosting: `java.net.*` files (`InetAddress.scala`, `Socket.scala`, `ServerSocket.scala`, and the platform socket-impl variants) call an `inline def` in `scala.scalanative.unsafe.UnsafePackageCompat` (`validatedSize`, a size-validation helper in `nativelib`), and dotc's macro/inline interpreter cannot expand it: `"Interpreter (own implementation) does not support calling method validatedSize in object UnsafePackageCompat: ... this own-implementation interpreter does not yet unpickle cross-module TASTy bodies"`. This is the documented cross-module limitation (cf. `scalalib-retained.jar`/`build/03b-build-scalalib-retained.sh`).

3. **Adding `nativelib`'s ~103 files to the same compile** (making `UnsafePackageCompat` local, which removes that error class) **exposes compile errors specific to `nativelib`**: a cyclic reference in `runtime/Continuations.scala`; syntax errors in `runtime/NativeThread.scala`/`reflect/Reflect.scala` (Java-style no-parens calls, e.g. `_aliveThreads.size`, on methods that resolve here to strict-parens Scala definitions; likely the Java-interop-leniency pitfall of the dotc/nscplugin self-hosting section, a different instance); and type mismatches in `runtime/Class.scala`/`unsafe/package.scala`. None appear when `nativelib` is compiled as its own sbt module (how the published jar was built), so a from-source build must replicate `nativelib`'s sbt flags, classpath, compile order and overlay directories (`project/Build.scala`'s `MultiScalaProject("nativelib")`, `.dependsOn(javalibintf % Provided)`, `scala-3`/`scala-2`/`scala-next` overlays), which cannot be reconstructed by inspection in one pass.

**Conclusion:** building a patched `javalib` (and enough of `nativelib`) needs either (a) getting scala-native's sbt build to run here (untested then: `sbt` is installed, and a stale `~/.ivy2/local` SNAPSHOT publish of these artifacts from earlier work exists, so sbt itself is probably not the blocker; a full sbt build was not tried because it may be slow/network-dependent and differs from every other patch here, which avoid sbt) or (b) reconstructing `nativelib`+`javalib`'s compile flags/order/overlays by hand. Either is future work. `patches/scala-native-0003-fix-path-touri-missing-authority-slash.patch` stays saved, correct, and inert; reporting upstream is the lowest-effort route.

## The `javalib` patch takes effect: scala-native's sbt build works here (option (a), done) (2026-09-07)

Option (a) worked on the second try; the first failure is a finding in itself.

**sbt was not the blocker.** `sbt` 1.12.9 is installed with network access (checked against Maven Central). `MultiScalaProject("javalib")` (`vendor/scala-native/project/Build.scala`) publishes its Scala 3 variant as sbt project id `javalib3` (`MultiScalaProject.projectID`: `<name><major>.replace('.', '_')`), so the targeted command is `sbt javalib3/publishLocal`.

**First attempt failed for a structural reason.** `javalib3/publishLocal` failed compiling `nscplugin` (a dependency of `javalib` via `.withNativeCompilerPlugin`, needed to cross-compile javalib to NIR) with 7 `E008 Not Found` errors (`value javaClassName/javaSimpleName is not a member of ... Symbol`, suggesting `import dotty.tools.backend.jvm.DottyBackendInterface.symExtensions`). This is `patches/scala-native-0002-nscplugin-selfhost.patch` working as intended against the wrong compiler: it repoints `nscplugin`'s import to `dotty.tools.dotc.core.SymExtensions`, the location in this project's self-hosted dotc (via `patches/scala3-...`). sbt's build of `nscplugin` uses the published, unmodified `scala3-compiler_3` jar, where `symExtensions` is still at the old location. `patches/scala-native-0001`/`0002` (touching `nscplugin`/`tools`, needed only for the self-hosted dotc path) therefore make the same tree incompatible with sbt's normal build. **`vendor/scala-native`'s patched checkout serves two incompatible compiler targets from one source tree: building `javalib` (which pulls in `nscplugin`) needs the unpatched variant; every other self-hosting step needs the patched one.**

**Fix, no source patch:** revert `scala-native-0001`/`0002` (`git apply -R`, both reverse-apply cleanly) before `javalib3/publishLocal`, and reapply both after (`git apply`; `git diff --stat` matched the pre-build patched state, all 11 files). Then `sbt javalib3/publishLocal` succeeded in 41s: `nscplugin` (65 files), `nativelib` (103 files + 1 Java), `clib` (21), `windowslib` (34), `posixlib` (67), then `javalib` (601), against the crossScala3 build's pinned Scala 3.8.3 (not this project's 3.8.4; irrelevant, as the `_3` suffix denotes Scala 3 binary compatibility, like the published jar this replaces). Output: `javalib_native0.5_3-0.5.12-SNAPSHOT.jar` under `~/.ivy2/local/org.scala-native/javalib_native0.5_3/0.5.12-SNAPSHOT/jars/`, with patched `UnixPath`/`WindowsPath` `.nir` (3421 `.nir`, 0 `.class`/`.tasty`, same shape as the published jar; `NIROnlySettings` strips those from every javalib build).

**Wired in, for self-hosting only:** `build/selfhost/build-dotc.sh` and `build/selfhost/build-lsp.sh` generate a copy of `nativelibs.cp` under `$WORK/selfhost/` (`NATIVELIBS_CP`) with the published `javalib_native0.5_3` entry replaced by `$HOME/.ivy2/local/.../javalib_native0.5_3.jar` when it exists (otherwise the published jar, with a loud warning); every compile/link in both scripts reads this copy. `$WORK/nativelibs.cp` is untouched, so the GraalVM pipeline (`build/03..09-*.sh`, `build/07-build-scalino.sh`, `build/06-package.sh`, which only reference `$WORK/nativelibs.cp`/`$DIST/nativelibs.cp`) is unaffected.

**Verified:** `build-dotc.sh`: clean compile, link and smoke test with the patched javalib. `build-lsp.sh`: clean compile and link, `lsp-trace-drive.py` 8/8. Then `build/lsp-stress-ape.py` against `dist/scalino-lsp-selfhosted`: `definition(BuildingInput)` resolves to `file:///Users/lorenzo/scala/ape/src/domain/Building.scala` (triple slash, matching `building_uri`'s construction; before, the comparison silently failed against the malformed `file:/...`), `references` returns two correct `file:///...` URIs, and the script's `OVERALL: PASS` gate (which depends on that equality) passes. The dotc determinism check (`PrivTest.scala`, 5 runs) with the swapped javalib stays byte-identical, so the swap does not interact with the earlier `PlainFile`/`intern()` bug.

This closes the last ape stress-test item. All three Phase 2 self-hosting-specific bugs (missing `java.util.logging`, `Memory.isCritical()`'s `Runtime` gap, this URI bug) are fixed and effective in the built binaries. `patches/scala-native-0003` is live and reproducible via `build-dotc.sh`/`build-lsp.sh`, provided `sbt javalib3/publishLocal` has been run once (with `scala-native-0001`/`0002` temporarily reverted) to populate `~/.ivy2/local`.

## Full cutover to self-hosted binaries: `ScalinoCli.scala`'s entry-point/test discovery was the blocker, fixed via NIR (2026-09-07)

With the user's authorization ("continue on self-hosting the missing pieces and then delete graalvm native-image altogether when finished"), a cutover attempt (making `build/all.sh` produce the self-hosted binaries as the shipped `dist/scalino-dotc`/`dist/scalino-lsp`) stopped before anything destructive when it hit a functionality gap.

**The blocker:** `cli/ScalinoCli.scala` detects `scalino run`'s entry point and `scalino test`'s test classes with a hand-rolled JVM classfile reader (`scanClassFile`/`analyzeClassFile`) over the `.class` files `scalino-dotc` writes to `classesDir` next to `.nir`. With the GraalVM-built `scalino-dotc`, an `-Xplugin:nscplugin` compile still runs the JVM backend (GenBCode) in parallel with nscplugin's NIR phases, so `.class` files exist. A self-hosted `scalino-dotc` (backend/jvm/ASM excluded from its source set since the start of Phase 1) never produces a `.class` file, so `detectMainClass`/`discoverTestClasses` would find nothing for any project. This is the default path for `scalino run`/`scalino test` without an explicit `--main-class`, so it would have broken everyday use.

**TASTy tried first, abandoned for NIR.** The first attempt used dotc's `-from-tasty`/`ReadTasty` machinery (the basis of `scala.tasty.inspector.TastyInspector` and this project's `-Xearly-tasty-output` support) to read `Symbol`-level info (superclass, interfaces, module-ness, method signatures). A `ScalinoDiscoverCompiler`/`ScalinoDiscoverPhase` (a `TASTYCompiler` subclass driven by a new `-Yscalino-discover` flag) compiled, then was redirected to NIR for three reasons: (1) NIR is produced unconditionally by every self-hosted compile, with no need to opt every invocation into `-Xearly-tasty-output`, a narrower path; (2) NIR is flat and post-erasure, structurally close to a classfile, so "does this top-level definition have a runnable `main`" is a scan over `Defn.Define`/`Sig` instead of a TASTy AST walk; (3) it ports the same heuristic the existing classfile scanner uses (same field names and semantics), a smaller and more mechanical change. The TASTy work was reverted (`Driver.scala`, `ScalaSettings.scala` and the new `fromtasty/ScalinoDiscover.scala` restored; `git diff` matched the committed patch set) before the NIR version.

**The fix, entirely in `cli/ScalinoCli.scala` (no dotc/nscplugin patch):** `nir_native0.5_3`/`util_native0.5_3` (scala-native's NIR data model and binary (de)serializer, used by nscplugin) are published as Scala Native cross-build artifacts on Maven Central (checked with `cs fetch`; fetched `--intransitive`, one artifact per `cs fetch` call, the convention of `build/selfhost/build-lsp.sh`'s `LSP_NATIVE_JARS`, to avoid a conflicting second copy of javalib/nativelib/clib). They are a compile and link dependency of `ScalinoCli.scala`'s build (`build/07-build-scalino.sh`'s `COMPILE_CP`/`LINK_CP`). That script builds the shipped `scalino` CLI, which has self-hosted its own build regardless of whether `scalino-dotc` is GraalVM- or Scala-Native-built, so this is a permanent addition, not scoped to `build/selfhost/`.

Ported the classfile scanner's exact heuristics to NIR, verified empirically against real compiled output (a throwaway JVM-side inspector using the published `nir_3`/`util_3` JVM cross-builds was used to dump real `.nir` files from small hand-written examples and confirm the exact shape of each case, rather than guessing at NIR's structure from source alone):
- **Main-class detection** (`findMainClassesNir`, used by `detectMainClass` as a fallback): an object's `def main(args: Array[String]): Unit` (`@main def`, `extends App`, or hand-written; all lower to the same shape before nscplugin) compiles to a synthetic top-level `Defn.Class`, not the module itself. A dump shows `object Hello`'s module (`Hello$.nir`) has a two-arg instance `main` with `this` as the explicit first parameter (the shape of the module's JVM classfile), while the forwarder class (`Hello.nir`, no `$`) has a one-arg `main` with no receiver, equivalent to a JVM `public static void main(String[])` (NIR method signatures always list the receiver as the first `Type.Function` argument, so "no receiver" means static). `isNirMainDefine` checks for a one-arg `(Array[String]) => Unit` with sig id `"main"`.
- **Test discovery / ancestry walk** (`nirClassInfoOf`, `findClassInfo`, used by `discoverTestClasses` and `isAssignable`): `Defn.Class`/`Defn.Module` carry `parent: Option[Global.Top]`/`traits: Seq[Global.Top]` as fields, so ancestry needs no reconstruction (the classfile version rebuilds it from a constant pool). A public no-arg constructor is a same-file `Defn.Define` with `isCtor`, `!isPrivate`, and exactly one NIR argument (the implicit receiver; a dump shows a zero-arg class's ctor has `ty.args.size == 1`, one with an explicit `Int` arg has `size == 2`).
- `findClassInfo` (replacing `findClassBytes`+`analyzeClassFile`) checks a local `.class` first (always present with a GraalVM-built `scalino-dotc`), then a local `.nir` (the only format a self-hosted `scalino-dotc` produces), then jar-based `.class` lookup for dependency jars. Dependency jars are ordinary JVM artifacts whichever toolchain built `scalino-dotc`, so that half of the walk (e.g. `MySuite extends munit.FunSuite extends munit.Suite`) is unchanged.

**The GraalVM path is untouched:** every fallback is gated on `classesDir` having no `.class` files (`detectMainClass`/`discoverTestClasses` check `walkClassFiles(classesDir).nonEmpty` first), always true with a GraalVM-built `scalino-dotc`, so the NIR branch is dead code there. Checked: rebuilt `dist/scalino` with the change and ran it against the GraalVM `dist/scalino-dotc`/`dist/scalino-linkdriver` with identical output on every test below.

**Verified against the self-hosted binaries through the `scalino` CLI** (invoking `scalino-dotc`/`scalino-linkdriver` by hand would miss this class of bug): a scratch `dist/`-shaped directory with `scalino-dotc` replaced by `dist/scalino-dotc-selfhosted` (everything else, including the rebuilt `scalino`, copied from `dist/`), with real `scalino run`/`scalino test` runs:
- A multi-class/multi-file example (`object T` with `def main`, `class Foo(x: Int)`, an unrelated `class Bar`, `class Impl extends Base with Greeter`): `scalino run` found `T` as the only entry point and ran it. `classesDir` had no `.class` files, only `.nir`, so the NIR fallback fired.
- A `munit` project (`class MySuite extends munit.FunSuite`, one assertion): `scalino test .` found `MySuite` through the NIR-based ancestry walk (from the local `.nir` entry into `munit`'s dependency-jar `.class` ancestry), ran it, and reported `1 passed, 0 failed`, as with the GraalVM `dist/scalino`.
- **`~/scala/ape`'s full suite** (54 tests; `http4s`/`upickle`/`cats-parse`/`porcupine`; Phase 1's validation gate) via `scalino test .` on the scratch self-hosted `dist/`: 54 passed, 0 failed, 0 errored, 0 skipped, as with GraalVM. The compiler's output directories (`_scratch/classes`, `_scratch/test-classes`, `_scratch/probe/classes`) contained no `.class` files. (The 1475 `.class` files under `.scalino-build/_scratch/probe/link` are jar-extraction output from the linker's working directory, never read by this code.)
- Standing regression gates: dotc determinism (5 compiles of `examples/Hello.scala` with `scalino-dotc-selfhosted`, byte-identical `.nir` MD5s) and `build/lsp-trace-drive.py` against `dist/scalino-lsp-selfhosted` (8/8, including `didChange`/diagnostics). The fix is confined to `cli/ScalinoCli.scala`/`build/07-build-scalino.sh`, shared with neither binary.

**Status:** this was the gap that justified stopping the first cutover attempt. It is fixed, verified up to `ape`'s full suite, and does not regress the GraalVM path. The `build/all.sh` cutover can be retried.

## Cutover: `build/all.sh` builds and ships self-hosted `scalino-dotc`/`scalino-lsp` (2026-09-07)

Retried with the NIR-discovery fix. `git diff --stat` and `dist/` inspection showed the interrupted attempt had left nothing broken: `build/all.sh` was untouched and `dist/scalino-dotc`/`dist/scalino-lsp` were the working GraalVM binaries.

**Audit before changing anything:** every script `build/all.sh` calls and what `packaging/` assumes.
- `07-build-scalino.sh` (builds the `scalino` CLI) was already binary-agnostic (it invokes whatever is at `$DIST/scalino-dotc`/`$DIST/scalino-linkdriver`) and already had the NIR-discovery fallback. No change.
- `03b-build-scalalib-retained.sh` is a no-op at the pinned `SCALA_VERSION=3.8.4` (the published `scala-library` jar for 3.8+ ships `.tasty`, so the recompile-with-retained-trees step is unneeded). Unaffected.
- `06-package.sh`/`09-package-linux-native.sh` (layout `/usr/lib/scalino/{scalino,scalino-dotc,scalino-linkdriver,scalino-lsp,java.base.jar,compiler.cp,nativelibs.cp,nscplugin.jar.txt,lib/,...}`) reference binaries by filename, so the cutover is transparent to packaging. A real `.deb`/`.rpm` build could not be run (`fpm`/`rpmbuild` are unavailable on this macOS machine; the script is Linux-only); `dist/`'s post-cutover layout was checked against what the scripts expect.
- **Key finding:** `scalino-linkdriver` (the NIR-to-native linker, `src/LinkDriver.scala`) has never been self-hosted. It is still a GraalVM native-image build of scala-native's `tools_3`, and both `build/03-build-scalino-dotc.sh` and `build/08-build-scalino-lsp.sh` use it as their link step. Self-hosting `scalino-dotc`/`scalino-lsp` alone does not remove GraalVM from the build; see "What was not removed".

**Implementation:** the content of the now-deleted `build/selfhost/build-dotc.sh`/`build-lsp.sh` moved into `build/03-build-scalino-dotc.sh`/`build/08-build-scalino-lsp.sh` (same recipe, writing `$DIST/scalino-dotc`/`$DIST/scalino-lsp` instead of `-selfhosted` files). Added two prerequisite steps those scripts always needed but `build/all.sh` did not run: `01b-build-patched-javalib.sh` (builds the scala-native-0003-patched javalib via `sbt javalib3/publishLocal`, so the URI fix takes effect) and `02b-gen-megaphase-overrides.sh` (generates `MiniPhaseOverrides.scala`). Removed `03a-patch-compiler.sh`: its overlay (compile 2 patched files, splice into `scala3-compiler_3.jar`) existed to avoid a full recompile on the GraalVM path, and self-hosting compiles the whole patched `vendor/scala3` tree anyway. `build/all.sh` now runs:
```
00b-setup-vendor.sh, 01-fetch-deps.sh, 01b-build-patched-javalib.sh,
02-build-java-base.sh, 02b-gen-megaphase-overrides.sh,
03-build-scalino-dotc.sh (self-hosted), 03b-build-scalalib-retained.sh,
04a-patch-tools.sh, 04-build-scalino-linkdriver.sh (still GraalVM),
07-build-scalino.sh, 08-build-scalino-lsp.sh (self-hosted), 06-package.sh
```
`05-regen-agent-config.sh` (a maintenance script, not in `all.sh`) was cut to its `scalino-linkdriver` tracing half; the `scalino-dotc` half is dead (self-hosted Scala Native binaries have no reflection registry), and the empty `agent-config/scalino-dotc/` was deleted.

**Verification gate, from the real cutover pipeline** (`03-build-scalino-dotc.sh`, `08-build-scalino-lsp.sh`, `07-build-scalino.sh`, `06-package.sh` in sequence, reusing cached `.build-work/` classpath artifacts, which are not cutover-specific), producing `dist/scalino-dotc`/`dist/scalino-lsp`/`dist/scalino`:
- Determinism: `PrivTest.scala` compiled 5 times with the new `dist/scalino-dotc`: byte-identical `.nir` MD5s.
- `dist/scalino run examples/Hello.scala` (no `--main-class`): entry point detected, `hello from scalino`, exit 0.
- `examples/interpreter-regressions` (`--main-class Test`; the directory also holds `examples/Hello.scala`'s `Hello`, and auto-detection reported the ambiguity): exit 0, all checks pass (`labels=true thunk=42 tag=true ...`).
- `examples/macro-hello`: fails as documented, with the `undefined: x.addOne ... at inlining` error, so no regression and no false "fixed" claim.
- `examples/upickle-enum-writer-bug`: exit 0, all `TipoGenerazione`/`ContestoUrbano` enum cases round-trip.
- `~/scala/ape` via `scalino test .` through the cutover `dist/`: 54 passed, 0 failed, 0 errored, 0 skipped, as with GraalVM.
- LSP: `build/lsp-trace-drive.py` against `dist/scalino-lsp`: 8/8. `build/lsp-stress-ape.py` first reported `OVERALL: FAIL` with `MissingCoreLibraryException` and cascading `NoSuchElementException`s: a setup mistake. Regenerating `~/scala/ape`'s `.dotty-ide.json` by hand with an explicit `src test` list skipped ape's `project.scala` and dropped every dependency (`upickle`/`cats`/`http4s`) from the IDE config; `scalino setup-ide .` picks them up. With that setup: `OVERALL: PASS`, including cross-package `definition`/`references` and re-hover after editing the definition file.
- Packaging: layout consistent (see audit); no real `.deb`/`.rpm` build.

All checks passed, with no regressions.

**What was removed** (GraalVM machinery found dead in the audit):
- `build/03a-patch-compiler.sh` (no remaining consumers).
- The `scalino-dotc` tracing half of `build/05-regen-agent-config.sh`, and `agent-config/scalino-dotc/`.
- `build/selfhost/build-dotc.sh`/`build-lsp.sh` (content now in `build/03-build-scalino-dotc.sh`/`build/08-build-scalino-lsp.sh`; two copies would drift).
- The `-selfhosted` scratch binaries in `dist/`; `dist/scalino-dotc`/`dist/scalino-lsp` are now those binaries.
- `README.md`/`packaging/homebrew/scalino.rb` now describe GraalVM as bootstrap JVM plus linkdriver only, not as what builds `scalino-dotc`/`scalino-lsp`.

**What was not removed:** `scalino-linkdriver` (`src/LinkDriver.scala`, wrapping scala-native's `tools_3`: NIR to LLVM IR to clang to native executable) is still built with GraalVM native-image (`build/04-build-scalino-linkdriver.sh`, unchanged), and both self-hosted binaries' build scripts need the built `dist/scalino-linkdriver` as their link step; no `scalino-dotc`/`scalino-lsp` can be produced without it. `build/04a-patch-tools.sh` (the `tools_3` overlay-jar patch) and `00-env.sh`'s `require "$NATIVE_IMAGE"` stay for the same reason. Self-hosting `scalino-linkdriver` had not been attempted: compiling scala-native's build/link/LLVM-codegen library is a different kind of component from dotc's frontend and nscplugin's backend, with unknown difficulty. GraalVM native-image is therefore not removed from the toolchain; it is required to rebuild that one binary. Self-hosting it is the next phase.

**Summary:** `scalino-dotc` and `scalino-lsp` are now by default in the shipped `build/all.sh` pipeline, self-hosted on Scala Native (dotc compiled from its own patched source, targeting Scala Native, with no GraalVM native-image in either binary). Verified on dependency-heavy third-party code (`~/scala/ape`, 54/54 tests; a multi-package LSP session with cross-file resolution and live edits). Fourteen bugs were found and fixed on the way (patches `scala3-0001..0013`, `scala-native-0001..0003`, and the NIR-based entry-point/test discovery in `cli/ScalinoCli.scala`). The remaining GraalVM dependency is `scalino-linkdriver`.

## Self-hosting `scalino-linkdriver`: proof of concept (2026-09-07)

`scalino-linkdriver` (`src/LinkDriver.scala`, wrapping scala-native's `tools_3`) was the last binary not self-hosted and the last reason GraalVM native-image is required.

**Scoping was too pessimistic.** The expectation was porting `tools_3`'s 116 files / ~27,128 lines from source, as for nscplugin/nir/util. Unnecessary: scala-native publishes a Scala Native cross-build of `tools_3`, `org.scala-native:tools_native0.5_3:0.5.12` on Maven Central (confirmed with `cs fetch` and `unzip -l`: 1,779 `.nir` files, e.g. `scala/scalanative/build/Build.nir`, `Discover.nir`; not an empty decoy like `scalalib_native0.5_3`). Self-hosting needs two things compiled against that jar:
1. The existing **local patch to 5 `tools_3` files** (`patches/scala-native-0001-fix-native-lib-object-cache.patch`: fixes inert object-file caching for vendored C/C++ sources; `build/04a-patch-tools.sh` splices it into the published JVM `tools_3.jar`'s bytecode for the GraalVM linkdriver). Compile those 5 files to NIR (JVM-bootstrap dotc with nscplugin active, as elsewhere) and put the output ahead of the published `tools_native0.5_3.jar` on the classpath: the same splice idea as `04a-patch-tools.sh`, for NIR.
2. **`src/LinkDriver.scala`** (117 lines; imports only `scala.scalanative.build._`/`scala.scalanative.util.Scope`, with no `dotty.tools` dependency per grep, so it needs neither the self-hosted-dotc source set nor nscplugin's dotc machinery; an independent NIR-producing compile).

Both compiled with 2 warnings and 0 errors against `tools_native0.5_3.jar` + `nir_native0.5_3`/`util_native0.5_3` + this project's `nativelibs.cp`.

**Bootstrap link:** the current GraalVM-built `dist/scalino-linkdriver` linked the compiled `LinkDriver` NIR into an executable (a bootstrap step: something must produce the first self-hosted linkdriver; afterwards it can link itself and everything else). Result: a working Mach-O binary.

**Blocker on first use, worked around.** Running the self-hosted `LinkDriver` on `examples/Hello.scala`'s NIR crashed with `java.nio.file.ProviderNotFoundException` in `scala.scalanative.linker.ClassLoader$.fromDisk` → `VirtualDirectory$.jar` → `FileSystems.newFileSystem`. scala-native's linker reads `.nir` entries directly out of jar classpath entries via a `java.nio.file.spi.FileSystemProvider` (a "jar:" zip filesystem), and Scala Native's javalib implements no `FileSystemProvider` (no `ZipFileSystemProvider` or any other under `vendor/scala-native/javalib`). That is the default path for every jar on the classpath, and every Scala Native classpath is jars (`javalib_native0.5_3.jar`, `nativelib_native0.5_3.jar`, `scala3lib_native0.5_3.jar`, ...). A comment in `vendor/scala-native/project/Build.scala` about its own (never fully working) native cross-build of `tools` says the same ("lack of ZipFileSystemProvider required to operate on JARs"): an upstream-acknowledged javalib gap, not caused by a patch here.

**Workaround:** extract every jar on the link classpath to a plain directory and pass the directories instead of the jars. `VirtualDirectory.real`'s directory branch needs no `FileSystemProvider`. Verified: with all 10 jars on `nativelibs.cp` extracted, the self-hosted linkdriver linked `Hello.scala`'s NIR, and the result printed `hello from scalino`, as with the GraalVM-built linkdriver on the same input. A real `ZipFileSystemProvider` port for Scala Native's javalib was not attempted.

**Script:** `build/04b-build-scalino-linkdriver-selfhosted.sh` fetches the three published jars, compiles the 5 patched files and `LinkDriver.scala`, bootstrap-links with the current `dist/scalino-linkdriver`, extracts the classpath jars, and smoke-tests (compiles, links and runs `Hello.scala`, asserting the exact output). A fresh run passed. It produces `dist/scalino-linkdriver-selfhosted`; it does not overwrite `dist/scalino-linkdriver` and is not in `build/all.sh`.

**Left before it can replace the GraalVM linkdriver** (a scoping and proof-of-concept pass):
1. Put the jar-extraction workaround in the pipeline (extract once and cache, or on every build; undecided).
2. Point `build/03-build-scalino-dotc.sh`/`build/08-build-scalino-lsp.sh`'s link steps at the self-hosted linkdriver and re-run the full gate from the cutover entry (determinism, `~/scala/ape` 54/54, LSP 8/8 + stress test) with it for every link, not just this `Hello.scala` smoke test.
3. Then make `dist/scalino-linkdriver-selfhosted` the real `dist/scalino-linkdriver`; only then is dropping GraalVM native-image from `00-env.sh`'s `require "$NATIVE_IMAGE"`/`build/04-build-scalino-linkdriver.sh` safe.
4. Consider a `ZipFileSystemProvider` port upstream in scala-native, which would remove the extraction workaround and help beyond this project.

This is a big change in outlook from the cutover entry above: self-hosting `scalino-linkdriver` looked like a large, unscoped unknown; it turns out to be a small, well-understood integration task blocked by one specific, already-diagnosed, already-worked-around javalib gap -- not a large porting effort.

## Wiring the self-hosted `scalino-linkdriver` in: jar-extraction fixed, memory blocker stops the cutover (2026-09-07)

Items 1-3 of the list above: make the self-hosted linkdriver `dist/scalino-linkdriver`, point `03-build-scalino-dotc.sh`/`08-build-scalino-lsp.sh` at it, re-run the gate.

**The jar-extraction workaround became a code fix.** Scala Native's javalib has no `java.nio.file.spi.FileSystemProvider`, but implements `java.util.zip.ZipFile`/`ZipInputStream` (real source under `vendor/scala-native/javalib/src/main/scala/java/util/{zip,jar}/`), which does not use NIO's provider SPI. `src/LinkDriver.scala` now extracts any `.jar` classpath entry with `java.util.zip.ZipFile` before handing a path to scala-native's `Build` API, cached under the system temp dir keyed by absolute path + size + mtime (unchanged jars are not re-extracted). It runs unconditionally (harmless on the GraalVM build) and, unlike the shell-script pre-extraction, callers (including user `scalino run`/`build`/`test` classpaths) need not pre-extract. Verified: the self-hosted linkdriver linked `examples/Hello.scala`'s NIR against its unmodified jar classpath and ran correctly.

**Second blocker: `Out of heap space, grow heap`** when linking something as large as dotc's own NIR (13,053 classes / 76,995 methods).
- Raising the max heap did not help: `GC_MAXIMUM_HEAP_SIZE` already defaults to `SIZE_MAX` (immix's `UNLIMITED_HEAP_SIZE`), and setting it to `14g` on this 16GB machine gave the same quick failure.
- First hypothesis (concurrent `Interflow.optimize` calls each cloning a large shared map) was wrong. There is one `Interflow` instance and one `optimize()` call per program. A dedicated, undersized `ExecutionContext` for the `Interflow.optimize` call site (`ScalaNative.scala`) was reached and ran with 1 thread (debug marker), and it crashed identically. The stack trace showed `scala.scalanative.linker.Reach.track`/`reachAllocation`, and `Reach.scala` has no `ExecutionContext`/`Future` use (grep), so it is single-threaded; capping concurrency cannot affect it.
- A global concurrency cap (`SCALANATIVE_LINK_THREADS=1`, via an env-var-gated executor size in `Build.scala`) succeeded once in a manual run: a full link of dotc's NIR in ~20 minutes (vs ~35s under GraalVM). It failed on retry through the build script with the same quick `Reach`-phase crash. The machine has fluctuating memory pressure from desktop use (`vm_stat`: ~6.5-6.9GB free during the failing retries), so the success was likely favorable timing. `Reach`'s sequential walk over ~77K methods appears to need more peak memory than is reliably available here, whatever the concurrency.

## Migrated `scalino-linkdriver` off GraalVM native-image (2026-09-10)

This sidesteps the memory blocker: `scalino-linkdriver` never had to link a self-hosted copy of itself through the memory-hungry self-hosted `Reach` pipeline; the earlier arc conflated the two. `04-build-scalino-linkdriver.sh` now compiles `src/LinkDriver.scala` with the JVM-hosted dotc+nscplugin (as every other build step does) and links it with `tools.cp`'s JVM-runnable `tools_3` (not the self-hosted `tools_native0.5_3` of the proof of concept) driving clang. There is no `native-image` invocation, no `-H:ConfigurationFileDirectories`/`agent-config/scalino-linkdriver` tracing, and `00-env.sh` no longer requires `NATIVE_IMAGE`/`GRAAL_HOME`. It is the plain Scala Native build `03-build-scalino-dotc.sh` and `08-build-scalino-lsp.sh` already use for their link step, and does not wait on the `Reach`/GC memory blocker (specific to linking dotc's ~77K-method NIR through a self-hosted linkdriver binary, still open if that path is revisited).

**This supersedes the "GraalVM stays for this one binary" conclusion of the previous pass:** every shipped binary (`scalino-dotc`, `scalino-linkdriver`, `scalino-lsp`, `scalino`) is built without GraalVM/native-image.

Notes from the superseded pass:
- **Root cause, as far as pinned down:** `Reach.process()`'s sequential walk building `tracked: HashMap[Global, ...]` over every reachable global in a program the size of dotc is memory-heavy, and Scala Native's Immix GC copes poorly on a machine without several free GB at that moment. Whether this is "needs more RAM" or a fixable GC/allocator inefficiency (fragmentation, retention) was not determined; that needs GC-level profiling.
- **That pass's linkdriver changes were reverted** (`src/LinkDriver.scala`, `build/00-env.sh`, `vendor/scala-native`'s `tools_3/build/{Build,ScalaNative}.scala`, `build/04-build-scalino-linkdriver.sh`, `build/08-build-scalino-lsp.sh`'s link step, and the README/packaging text calling `scalino-linkdriver` self-hosted). `git checkout -- <file>` inside the `vendor/scala-native` clone reverts to the pristine upstream commit, not just this pass's edits: it silently undid part of the legitimate `patches/scala-native-0001` (object-file-caching fix), which had to be re-applied from the patch (`git apply` on an isolated single-file extract) before rebuilding.
- **Recovery checks:** rebuilt the GraalVM-based `scalino-linkdriver` via `04a-patch-tools.sh`/`04-build-scalino-linkdriver.sh` (`strings` shows `com.oracle.svm.core.VM.Java.Version`, ~44MB, matching the pre-pass binary), then `dist/scalino-dotc` (35s, no memory issues, so the constraint is specific to the Scala Native linker), `dist/lib/scalalib-retained.jar`, `dist/scalino`, `dist/scalino-lsp` (`lsp-trace-drive.py` 8/8). dotc determinism (5x `examples/Hello.scala`, byte-identical MD5s) was unaffected. `06-package.sh` ran.
- **State then:** `scalino-dotc`/`scalino-lsp` were self-hosted (linked by GraalVM's `scalino-linkdriver`); `scalino-linkdriver` was not. The proof of concept (self-linking, linking small programs like `Hello.scala` with a jar classpath) remained valid; the blocker was linking dotc-sized NIR. Advice for a retry: (a) the jar-extraction fix in `src/LinkDriver.scala` is reusable (it was reverted with everything else, as landing it alone, with no way to verify it end to end, was not worth the risk); (b) the blocker is `Reach`'s memory at dotc's scale, not concurrency, GC config or the `FileSystemProvider` gap; (c) try a machine with reliably free memory (CI runner) before assuming a scala-native fix is needed.

## `Reach`'s memory profile: structural GC/collections overhead across stages (2026-09-07, later)

Per instruction to profile and reduce the self-hosted linkdriver's footprint at dotc's scale (~77K reachable methods) rather than retry with more RAM. Nothing from the prior pass had landed. All numbers are measured with `/usr/bin/time -l` (macOS).

**Baseline:** GraalVM's `scalino-linkdriver` linking dotc's NIR (`.build-work/selfhost/nir-out`, from a `build/03-build-scalino-dotc.sh` run; the same input for every test below): 1.77 GB peak RSS, 35.6s wall-clock, exit 0.

**Self-hosted linkdriver, default config** (`Mode.Debug`, `optimize = true`; `NativeConfig.default` sets `optimize = true` independent of `Mode`, per `NativeConfig.scala`), same NIR, with the jar pre-extraction workaround: crashes with `[ScalaNative GC|Error] Out of heap space grow heap` at 3.69 GB peak RSS, ~99s in, after `Discovered 13053 classes and 76995 methods after classloading` and `Checking intermediate code (quick)`. The captured stack trace pins it at `Interflow.result()`'s `originals.clone()` (`interflow/Interflow.scala:184`), a full clone of a `mutable.Map[nir.Global, nir.Defn]` built from `analysis.defns` (tens of thousands of entries), inside the single `Future { Interflow.optimize(...) }` (`build/ScalaNative.scala:53-72`) on a `ThreadPoolExecutor` worker. This refines the previous entry's trace, which pointed at `Reach.track`/`reachAllocation`; both are real, so which allocation fails depends on timing and memory pressure.

**Test: is `Interflow`'s map clone the cause?** A copy of `src/LinkDriver.scala` with `.withOptimize(false)` (an existing `NativeConfig` flag, no scala-native patch), compiled with the usual JVM-bootstrap dotc with nscplugin, bootstrap-linked by the GraalVM `scalino-linkdriver`, and smoke-tested on `Hello.scala` (`hello from scalino`) before use on dotc's NIR.

**Result: half right.** With `optimize = false` the log shows `Optimizing skipped`, `Interflow` does not run, and the process gets past the earlier crash point, but crashes later at 6.12 GB peak RSS, with the same `Out of heap space` error in `scala.scalanative.codegen.Lower.onDefns` -> `shouldGenerateStackOverflowChecks` -> `ReachabilityAnalysis.Result.references.isReachable`/`isSelfRecursive`. This crash is concurrent: `codegen/llvm/CodeGen.scala` wraps each compilation unit in a `Future` (`scala.concurrent.Future$.apply` in the trace), and `user` time of 243s against 90s real time shows multi-core work. "Peak memory footprint" (a separate macOS metric from RSS) was 17.19 GB in both crashes, to the byte (`17192584832`), likely Immix GC's fixed upfront address-space reservation, not real use. RSS is the relevant number and rises with real memory pressure (page reclaims 1.9M to 7.3M between runs).

**Conclusion: no single fixable allocation site.** Scala Native's GC/mutable-collection implementation has a general memory-overhead gap versus the JVM for maps and sets keyed by tens of thousands of `nir.Global` entries, recurring across pipeline stages. `Reach.process()` holds at least nine `mutable.Map`/`mutable.Set`/`mutable.UnrolledBuffer` structures of that scale at once (`done`, `infos`, `from`, `unreachable`, `unsupported`, `enqueued`, `links`, `dyncandidates`, `dynsigs`; `linker/Reach.scala`), `Interflow` builds and clones its own `originals` map of that scale, and `codegen.Lower`'s per-unit reachability checks query the same analysis concurrently across units. Skipping one stage (as `optimize = false` did for `Interflow`) moves the failure to the next large-map stage. GraalVM's JVM handles the same data in under 2 GB; this toolchain needs 3-4x that and still does not reliably finish. Two independently traced crash sites show the problem is systemic, not specific to `Reach.scala`.

**What a fix would need (not attempted):** (a) lower per-entry memory overhead of Scala Native's `mutable.HashMap`/GC at the runtime/collections level, a scala-native-core change; (b) restructure the linker pipeline to stream or release intermediate large maps between stages instead of holding several ~77K-entry structures at once, an upstream architectural change to `Reach`/`Interflow`/`Lower`; (c) a machine with more reliably free RAM than this laptop's ~2 GB baseline (now with numbers: clearing 6+ GB just to reach the second crash site, with no guarantee it is the last).

**`.withOptimize(false)` is a verified partial mitigation, not adopted as a default.** It delays the crash and lets more work complete, with no source patch, which may help on memory-constrained machines. It is not in `src/LinkDriver.scala`'s default because disabling NIR-level optimization changes output size and speed for every `scalino-linkdriver` user, and it does not solve the problem.

**State:** `scalino-dotc`/`scalino-lsp` were self-hosted and working (GraalVM-linked); `scalino-linkdriver` was still GraalVM native-image, and native-image was still required. Nothing was committed or modified in the working tree by this pass (experiments in `/tmp`). (The 2026-09-10 entry above removed the GraalVM dependency by a different route.)

## jsoniter-scala's `JsonCodecMaker.make` (item 9's `undefined: x.addOne` gap): five interpreter bugs fixed; `writeToString` and `readFromString` work (2026-09-17)

Item 9's open gap (`undefined: x.addOne # -1: TermRef(TermRef(NoPrefix,val x),addOne) at inlining`, `examples/macro-hello` failing on jsoniter-scala's `JsonCodecMaker.make`) was reopened to make jsoniter-scala work end to end. Five separate interpreter bugs were fixed, each confirmed by a rebuild and rerun (11 `build/03-build-scalino-dotc.sh` rebuilds). Minimal repro: `case class User(name: String, devices: Seq[Int])` with `given codec: JsonValueCodec[User] = JsonCodecMaker.make`.

**Bug 1: owner hygiene of a quote-declared local forced mid-splice-resolution** (`Splicer.scala`, `SpliceInterpreter`'s top-level quote-apply case). jsoniter's `genReadCollection[B: Type, C: Type]` declares `val x = $newBuilder` directly inside its `'{ ... }` body (plain quote syntax, not `Symbol.newVal`). `x`'s retained-tree symbol, tree-interpreted from jsoniter-scala-macros' separately compiled TASTy, keeps its original foreign owner (`method genReadCollection`, from a finished compilation run) until `changeNonLocalOwners`/`resolveNestedSplices` rebase it. The old code ran `resolveNestedSplices` on the raw body before that rebase. Resolving a splice nested in `x.addOne(${...})`'s arguments (e.g. `genReadVal`'s `in.readInt()`) makes `TypedTreeCopier` rebuild and retypecheck the enclosing `x.addOne(...)` Apply, re-deriving `x`'s member `addOne` against the foreign symbol, which fails (`SCALINO_INTERP_DEBUG=1`: `qual.symbol` was `NoSymbol`, denotation invalid at the current period, while `qual.tpe.termSymbol.owner` was still the foreign `method genReadCollection`). **Fix:** run the owner rebase (`changeNonLocalOwners(ctx.owner)`) on the raw `body1` before `resolveNestedSplices`. A first attempt that also moved the `typeBindings` (quote-pattern type variable) substitution earlier, unfiltered, regressed Bug 2's case; the early substitution now filters to `sym.isType`, and the original unfiltered application after `resolveNestedSplices` is unchanged.

**Bug 2: `typeBindings` substitution corrupting term-level `given Type[V]` references (introduced and fixed within Bug 1's fix).** `env` can map a term symbol (a `given Type[V]` parameter, e.g. `evidence$2`, bound by the interpreter's call-binding convention) to a raw `Type`, not only a type variable. The early, term-unaware substitution on `body1` hit such a term as a live `Ident` inside a not-yet-evaluated nested splice (e.g. `genNullValue[V](...)(evidence$2, ...)`); `TreeTypeMap`'s `Type#subst` replaced the whole `TermRef` with the bare `Type`, and the interpreter then tried to evaluate that argument as a call on the type's class ("does not support calling class Int in package scala"). Restricting the early substitution to `sym.isType` fixed it; the later unfiltered application is safe because by then such references are values.

**Bug 3: `Array.apply[T](xs: T*)(ct: ClassTag[T])`'s implicit `ClassTag` param list broke varargs unwrapping** (`Interpreter.scala`, `interpretCollectionFactory0`). Its `elems` extractor matched only `case (s: Seq[Object]) :: Nil => s` (correct for `List`/`Vector`/`Seq`/`Set`/`Map#apply`, which take no `ClassTag`). `Array.apply` has a second, implicit param list, so `args` is `[elemsSeq, classTagInstance]`, missed the match, fell to `case other => other`, and the two-element `args` was returned as `elems` instead of the varargs `Seq`. This showed up as jsoniter-scala-core's `JsonWriter.escapedChars: Array[Byte]` (an already-compiled dependency, not macro code; a 128-element `Array(-1,-1,...)` literal read by tree-interpreting its retained TASTy at macro-expansion time) becoming a 2-element array, and `escapedChars(ch.toInt)` threw `ArrayIndexOutOfBoundsException` at `ch.toInt=110` ('n'). Fixed with a second arm, `case (s: Seq[Object]) :: _ => s`, which ignores a trailing `ClassTag`.

**Bug 4: intermittent `MatchError: 23` in stock `TreeUnpickler.scala`, from a missing warm-up pass in `Splicer.scala`'s quote handling.** The failure varied between builds and between debug and non-debug mode. Tag 23 is `SYNTHETIC` in `TastyFormat`'s tables, a modifier-flag tag, never a standalone tree/type tag, so this was a TASTy read-position desync, not a missing construct. Two candidate causes were ruled out by reading `TreeUnpickler.scala`/`Contexts.scala`: a mode mismatch between LazyReader creation and force time (`LazyReader.complete`'s `ctx.withModeBits(mode)` is a full `setMode`, so the captured mode wins), and shared mutable reader state across re-entrant forces (`readLaterWithOwner`'s `fork` gives each lazy thunk its own `TreeReader`/`TastyReader`). The fix follows stock dotc: `PickledQuotes.unpickle`, after `unpickleExprV2`, calls `tree.foreachSubTree(identity)` "to make sure trees and positions are fully loaded". `Splicer.scala`'s own quote path (for a retained-source `Quote` node, reached when `-Yretain-trees` exposes jsoniter's macro helpers as raw `Quote`/`Splice` AST instead of lowered `unpickleExprV2` calls) did not. Without it, a lambda nested in a quote body (jsoniter's `x => ${writeVal(out, 'x)}` in `genWriteArray`) has its lazy, TASTy-backed `.rhs` read for the first time much later, inside `changeNonLocalOwners`'s `TreeTypeMap` walk, which sometimes corrupts the reader position (before the fix: 4/4 failures without `SCALINO_INTERP_DEBUG=1`, 0/2 with it; its `.show` calls forced things in a safe order). Added the same `body1.foreachSubTree(identity)` right after obtaining `body1`. After the fix: 5/5 clean runs with and without `SCALINO_INTERP_DEBUG=1`.

**Bug 5: curried function-value application flattened param-list groups** (`Interpreter.scala`, `interpretTree0`). `Call`'s extractor keeps param-list grouping (`List[List[Tree]]`), but the `env.contains(fn.symbol)` path (a local `val`/param bound to an interpreted function value) flattened it (`rawArgs = args.flatten`) and folded `applyFn1` one arg at a time, correct only if every list had exactly one arg. jsoniter's `writeVal`/`readVal` params are `Quotes ?=> (Expr[JsonWriter], Expr[T]) => Expr[Unit]` (shape `(1)(2)`), so `writeVal.apply(quotesInstance)(out, x)` flattens to `[quotesInstance, out, x]`; the first group applies, then `applyFn1(resultOfFirstApply, out)` runs against a `Function2` and fails with "not a callable single-argument function" (a temporary arity-hint diagnostic showed a real `Function2`). Fixed by folding over `args` (the unflattened `List[List[Tree]]`) and applying each group at its own arity via a new `applyFnGroup` helper (arities 0 to 4, dispatching to the matching `FunctionN` or `InterpretedInstance`).

**Verified:** the `User(name, devices: Seq[Int])` repro's `writeToString`/`readFromString` round-trip works (`{"name":"bob","devices":[1,2,3]}` / `User(bob,List(1, 2, 3))`, exit 0, 3/3 runs). A richer `Person` (nested `Address` case class, `Option[String]`, `List[String]`, `Map[String,Int]`, `Boolean`, a string with an escaped quote) confirmed the encode side. `examples/Hello.scala` and `examples/interpreter-regressions` still compile and run (regression check only; the scratch fixture has no test framework wired in). (At the time `readFromString` was blocked on a `lub(unit, bool)` failure, assumed a Scala Native codegen bug. The next paragraph corrects that.)

**Correction (2026-10-07): the `readFromString` / `lub(unit, bool)` failure was not a Scala Native bug.** The NIR of the generated decoder (`T$package$.$d0$1`) showed `varstore %7 <_active> : var[bool], unit`: the macro-generated initial value of a `var _active: Boolean` was `()`. Cause: `fieldInfo.defaultValue.getOrElse(genNullValue(...))`: `Option#getOrElse`'s by-name default reaches the interpreter as a `ByNameArg` thunk, and the intrinsic returned the thunk instead of forcing it (same for `orElse`/`fold`), so the spliced `ValDef` got a wrong right-hand side. Interflow then tried to merge that Unit with Bool values. Fixed in patches/scala3-0028 (force the thunk). With it, the unmodified linker with the optimizer on links and runs the `Foo(active: Boolean)` repro (`Foo(true)`) and the 35-field `JW.scala` round-trip, byte-identical to the JVM-compiled output. Workaround for older binaries: `--native-optimize=false`.

**Files changed:** `patches/scala3-0010-cache-interp-debug-env-check.patch` (regenerated to include Bugs 1/2/3/5, all in `Interpreter.scala`/`Splicer.scala`); new `patches/scala3-0021-tasty-invalid-tag-diagnostic.patch` (Bug 4's fix, plus a diagnostic in `TreeUnpickler.scala`'s `readSimpleType` fallback, unconditional but rare, guarded by checking the tag against `TastyFormat`'s valid constant-tag set rather than `SCALINO_INTERP_DEBUG`, since this bug showed that the heavier debug path can mask a timing-sensitive bug). Both apply cleanly in sequence with the other `patches/scala3-*.patch` on a fresh worktree of vendor/scala3's base commit (`777528f1`, Scala 3.9.0 LTS release), and the resulting files were diffed byte for byte against the working tree used for testing.

## Intermittent "Unknown SMI discriminant N" / NPE / SIGBUS in the self-hosted `scalino` build: missing acquire fence in the ported `ForkJoinPool`'s work-stealing queue, not the GC (2026-09-22)

**Symptom:** `build/07-build-scalino.sh` (scalino-linkdriver compiling and linking `scalino`'s sources with `--multithreading`) crashes in ~10-20% of runs, always iterating an immutable `Vector` (NIR `Defn`/`Inst` sequences) in the codegen pipeline's parallel phase (`Future.sequence` over per-file compiles in `NativeLib.scala`; `CodeGen.genLlIncrementally`'s thread pool). Three signatures, one cause: `IllegalStateException: Unknown SMI discriminant N in tagged pointer` (`SmiBox$.reify`/`Object.__getClass`, see `patches/scala-native-0022-smi-tagged-pointer-boxing.patch`), `NullPointerException` in `VectorStatics$.foreachRec`, and `SIGBUS` in `SafepointTrapHandler`.

**GC ruled out by test and code review.** It reproduces identically on stock immix, plain commix, and commix with `--gc-stw-sweep` (a new flag), and at every `SWEEP_BATCH_SIZE` tried (32/128/256/512), so it is not GC-algorithm-specific. Evacuation (the moving collector) is off by default: `Evac_enabled()` in `vendor/scala-native/nativelib/.../gc/immix/Marker.c` checks `$SCALANATIVE_EVAC_ENABLE`, unset in every repro run, so an evacuation fixup rewriting a SMI-tagged word mistaken for a pointer cannot happen. Where it does run, `Evac_FixupSlot` (same file, line ~422) checks `Bytemap_isPtrAligned(value)` first; a SMI-tagged word has its LSB set (`nir.Type.Smi`'s tag bit), so it is odd, and `Bytemap_isPtrAligned` rejects odd addresses via both GCs' `ALLOCATION_ALIGNMENT_INVERSE_MASK` check. Every other GC site that treats a raw word as a candidate pointer is either tag-aware (`Marker_markField` in `immix/Marker.c` and `commix/Marker.c`, explicitly tag-bit-guarded by patch 0022) or safe because a tagged word is never pointer-aligned (`Marker_markConservative`/`Marker_markRange` for stack/register roots in both GCs). The GC's SMI-vs-pointer handling is sound; the first hypothesis in the original brief is dead.

**Root cause (strong circumstantial case, not confirmed by a fix and repro): `javalib/src/main/scala/java/util/concurrent/ForkJoinPool.scala`'s `WorkQueue`, this project's Scala Native port of the JDK's `ForkJoinPool`, reads its task array with plain loads on the stealing/polling side**, where the JDK uses `VarHandle` acquire reads. `WorkQueue.push` (line ~1642) publishes a task with a plain store, `a(m & s) = task`, then calls `getAndSetAccess(0)`, a `seq_cst` exchange on the unrelated `access` field (`AtomicInt.exchange`, `clib/.../stdatomic.scala.gyb`, default `atomic_exchange`, seq_cst). That full fence stops the producer's own reordering of the store, but establishes nothing on the consumer side. Every steal/poll path reading the array (`scan` line 362, `pollScan` lines 629/704/809, `WorkQueue.poll`/`tryUnpush`/etc. lines 1779/1786/1812/1858/1916/1958/1963) reads the slot with a plain `a(k)`/`a(nk)`, then claims it with `WorkQueue.casSlotToNull` (an atomic CAS via `arraySlotAtomicAccess`). The CAS orders only the claim of the slot; it creates no synchronizes-with edge for the plain load that read `t`. Single-word pointer reads and writes are atomic on x86-64 and ARM64, so the risk is not a torn pointer but unsafe publication: a stealer can see the new task pointer while seeing a stale pre-construction view of what it points to (the compiled NIR `Vector`'s structural-sharing arrays), because nothing on the read side synchronizes with the producer's release. This is what the JDK's `QA.getAcquire`/`setRelease` VarHandles prevent, and this port's `arraySlotAtomicAccess` (used correctly for `getAndClearSlot`/`casSlotToNull`) could give the same acquire load for these reads; it is not used there.

This explains every symptom: garbage bits from a not-yet-visible `Vector` node give `Unknown SMI discriminant N`; a not-yet-visible slot read as its zero default gives the `NullPointerException` in `VectorStatics.foreachRec`; a corrupt pointer dereferenced through the stale view gives a real `SIGBUS`, not related to `patches/scala-native-0033-fix-stale-safepoint-trap-race.patch` (0033 fixes a signal-delivery-timing race against a benign trap; this is a genuinely invalid access, so the third hypothesis in the brief is very likely wrong). It is independent of GC backend and `SWEEP_BATCH_SIZE`, being a `ExecutionContext`/`ForkJoinPool` race; with `--multithreading`, `scalalib/overrides-2.13/scala/concurrent/ExecutionContext.scala.patch` routes `ExecutionContext.global` (used by `Future.sequence` in `NativeLib.scala`) to `impl.ExecutionContextImpl.fromExecutor(null)`, a multi-worker `ForkJoinPool`, not the single-threaded `NativeExecutionContext.queue` of non-multithreaded builds. The roughly constant failure rate (~1 in 5-8) regardless of GC config matches a race dominated by scheduling and cache-visibility timing; GC pauses correlated only because they perturb scheduling.

**Severity:** silent corruption or a crash in the self-hosted compiler's own build, and any multithreaded `Future`/`ExecutionContext.global` code that publishes objects across threads is at risk on a weak-memory host. It reproduced on Apple Silicon/arm64, where the missing acquire matters; it may be rarer or unreproducible on x86-64's TSO, which would corroborate it.

**Not yet done**: no patch applied, no fix+repro cycle run to confirm. Proposed fix, for a follow-up session: change the plain `a(k)`/`a(nk)`/`a(m&s)` speculative reads on the steal/poll paths (`scan`, `pollScan`, `WorkQueue.poll` and friends) to go through `arraySlotAtomicAccess(a, k).load(memory_order_acquire)` instead of plain Scala array indexing -- mirroring the JDK's `QA.getAcquire`. Confirm by re-running the existing `for i in $(seq 1 15); do ./build/07-build-scalino.sh; done` repro loop before/after; if the failure rate drops to ~0 post-fix, that's strong confirmation. A smaller, faster repro (a minimal multithreaded `Future.sequence` over tasks that build+publish nested immutable collections, on arm64) would be worth building to avoid the ~15-full-self-host-build repro cost for future iteration.

## Follow-up: the ForkJoinPool fix tested; the executor in play is not `ForkJoinPool`; bug still open (2026-09-22, later)

**`patches/scala-native-0035-fix-workqueue-push-missing-release-fence.patch` applied and verified.** It adds the missing `VarHandle.releaseFence()` before `WorkQueue.push`'s common-path array store in `javalib/.../ForkJoinPool.scala`, paired with the existing `acquireFence()` on the steal/poll read side. A full `build/all.sh` rebuild succeeded. Caveat: a background-task completion notification fired while `build/all.sh` was still running (confirmed via `ps` and log tail after corrupted-jar failures from a repro loop that overlapped the build); it fired early twice. Confirm completion via `ps`/`pgrep` and log content. After a real rebuild, a 15x repro loop (`for i in $(seq 1 15); do ./build/07-build-scalino.sh; done`) gave 14/15 clean and 1/15 crashing: run 15 hit the same `NullPointerException` in `VectorStatics$.foreachRec`.

**That failure's stack trace showed the original hypothesis was wrong about the executor.** The crash runs through `java.util.concurrent.ThreadPoolExecutor.runWorker`, not `ForkJoinWorkerThread`. `codegen.llvm.CodeGen`'s `implicit ec: ExecutionContext` comes from `tools/src/main/scala/scala/scalanative/build/Build.scala`'s private `await()`, the single entry point that creates the `ec` for the whole build/link/codegen pipeline (including `NativeLib.scala`'s per-file `Future.sequence`), and it is always `Executors.newFixedThreadPool(availableProcessors, ...)` (`Build.scala` line ~364): a `ThreadPoolExecutor` on a `LinkedBlockingQueue`, never `ExecutionContext.global`/`ForkJoinPool`. The earlier assumption was incorrect. The missing release fence in `ForkJoinPool.push` (patch 0035) is a real bug (keep the fix: anything using `ExecutionContext.global` could hit it) but not the cause of this crash. The 1-in-15 result matches the ~1-in-5-to-8 baseline within sampling noise.

**The executor's primitives, `LinkedBlockingQueue` (`javalib/.../LinkedBlockingQueue.scala`) and the `ReentrantLock`/`AbstractQueuedSynchronizer` (`javalib/.../locks/AbstractQueuedSynchronizer.scala`) under it, look correct on inspection.** `LinkedBlockingQueue` is a faithful two-lock (`putLock`/`takeLock`) Doug Lea port with `count` as an `AtomicInteger`; `put`/`take`/`offer`/`poll` do `enqueue`/`dequeue` inside the relevant lock's critical section, the standard way to publish a task (and its payload, e.g. a captured `Vector`) to the dequeuing thread. AQS's `state` is `@volatile` and CAS'd through `AtomicInt` (`compareAndSetState`), as in the JDK. `LockSupport.park`/`unpark` (`javalib/.../locks/LockSupport.scala`) delegate to `NativeThread`'s per-platform `park`/`unpark`, not audited then.

**Status then: open.** Ruled out: GC (both algorithms, evac on/off, every SMI-tag pointer-vs-word site, `SWEEP_BATCH_SIZE`); `ForkJoinPool` (a real bug fixed, but not this pipeline's executor); and, on inspection only, `LinkedBlockingQueue`/AQS locking. Next candidates in priority order: (1) `LockSupport`/`NativeThread.park`/`unpark`'s pthread-mutex-or-condvar implementation; (2) stock `scala.concurrent.impl.Promise`/`DefaultPromise` CAS-based callback chaining, used by every `Future{}`/`.map`/`.onComplete` here, not audited for a Scala-Native-specific memory-ordering gap; (3) Scala's `lazy val` codegen (double-checked-locking bitmap fields), in case a `Vector`'s lazily computed internal field publishes without the right fence on this backend. A faster repro than 15 self-host builds (e.g. a minimal `Executors.newFixedThreadPool` handoff of closures building nested `Vector`s, looped until corruption) would speed this up.

## A faster in-process repro: no data corruption, but a deterministic AQS lost-wakeup deadlock (2026-09-22, later)

**A standalone `dist/scalino run`-compiled repro** (`--multithreading`, with `Build.scala`'s `Executors.newFixedThreadPool(availableProcessors)` construction) iterates in seconds instead of full self-host builds. First shape: one long-lived executor, `tasksPerIter` parallel `Future(buildNestedVector).map(transform)` chains per loop (the cross-thread `DefaultPromise` callback dispatch the real pipeline uses), `Future.sequence`d and checksum-verified every iteration. It ran clean for 100,000 iterations (800,000 `Future` chains, `40x40` nested `Vector[Vector[Int]]` payloads). (An earlier version recreated the thread pool every iteration and OOM'd the host from thread/stack churn; hoisting the executor out of the loop, as in `Build.scala`, fixed that harness bug.)

**Next: bypass `Future`/`Promise`/`Vector` and stress raw `LinkedBlockingQueue` handoff.** N producer threads (`availableProcessors`) `put()` a `Payload` (array-of-arrays + a seed-derived checksum) into a `LinkedBlockingQueue(64)` (bounded, for constant not-full/not-empty pressure); N consumer threads `take()` and verify the checksum. This drives the same `LinkedBlockingQueue`/`AbstractQueuedSynchronizer` machinery as `Build.scala`'s executor work queue, directly.

**First run: a full deadlock, not corruption.** At `produced=66, consumed=2` the process went idle (0.0% CPU on every thread, `ps -M`). `lldb -p <pid> -o "bt all"` showed all 10 producers parked in `LinkedBlockingQueue.put` -> `notFull.await()` (correct: 66-2=64, at capacity) and all 10 consumers parked in `LinkedBlockingQueue.take` -> `notEmpty.await()` (wrong: the queue held 64 items; `count.get()` was nowhere near 0). A lost wakeup, deterministic enough to hit on a first run at moderate concurrency.

**Likely mechanism (from the code, not yet confirmed by a fix and repro):** `LinkedBlockingQueue.put`/`take` call `signalNotEmpty()`/`signalNotFull()` only on the transition (`if (c == 0) signalNotEmpty()` in `put`, `if (c == capacity) signalNotFull()` in `take`), as in the JDK; the woken thread cascades further wakeups (`if (c + 1 < capacity) notFull.signal()` / `if (c > 1) notEmpty.signal()`). In this port `Condition.signal()` (`AbstractQueuedSynchronizer.scala`'s `ConditionObject.doSignal` -> `enqueue(first)`) does not unpark the signalled thread: it moves the node from the condition's wait-list to the lock's AQS sync queue (`private[locks] final def enqueue`, ~line 137), which calls `LockSupport.unpark` only when the previous tail's `status < 0` (cancelled). Normally the node stays parked until the signalling thread's `takeLock.unlock()`/`putLock.unlock()` (`AbstractQueuedSynchronizer.release` -> `signalNext(head)`) unparks `head.next`. That is a second handoff (condition queue -> sync queue -> unpark on release). If `head`/`tail`/a node's `status` become inconsistent with who is waiting (plausible under concurrent `casTail`/`cleanQueue` conditions here; `notEmpty` and `notFull` belong to different locks, `takeLock`/`putLock`, each with its own sync queue, so the problem is within one lock's signal-to-release-unpark chain), a signalled thread can be stranded: out of the condition wait-list (so no `signal()` finds it again) but never woken by any `release()`'s `signalNext`.

**This is a total hang, not the SMI-discriminant/NPE/SIGBUS signatures of the original report.** It may be the same AQS defect showing up differently (a stranded thread could hang a phase; a partial lost signal could let a thread pass a wait guard early and read a `Vector` node before its publisher's writes are visible, a weaker failure that would not deadlock), or two bugs in the same subsystem. Unresolved then.

**Not yet done:** the trigger (which `head`/`tail`/`status` interleaving strands the node) was not isolated. Next: per-call tracing (thread id, node identity, `head`/`tail` snapshot) in `AbstractQueuedSynchronizer.enqueue`/`ConditionObject.doSignal`/`release`/`signalNext`, run with this raw-LBQ repro (`scratchpad/repro/Repro.scala`, not committed; rebuild from this description: N producer/consumer threads, bounded `LinkedBlockingQueue`, checksum-verified array-of-arrays payload). No fix then. The raw-LBQ repro iterates in seconds, against minutes for the 15x self-host loop.

## Root cause and fix: a permit-counter race in `PosixThread.park()`/`unpark()` (2026-09-22, later)

**Instrumenting the compiled javalib caught the lost wakeup.** Reimplementing `AbstractQueuedSynchronizer`/`ConditionObject`/`LinkedBlockingQueue` by hand in a standalone program ran 75,000+ operations clean across two variants (one mirroring `ForkJoinPool.managedBlock`'s nested park loop) and never hung. The bug depends on the compiled code's codegen/layout, not the algorithm as read. So the vendored source was instrumented directly: `AQS_TRACE`-env-gated `println` in `AbstractQueuedSynchronizer.scala`'s `enqueue`/`doSignal`/`release`/`signalNext`/`acquire` park points and in `PosixThread.scala`'s `park()`/`unpark()` (fast-path exchange result, trylock failure, the mutex-held recheck, `unpark`'s old counter/conditionIdx/willSignal). The real javalib was rebuilt with `build/01b-build-patched-javalib.sh` (~30s per rebuild) and the raw-`LinkedBlockingQueue` stress test deadlocked again almost immediately, now with a trace.

**The trace (`/tmp/smi_repro_1.log`, not committed):**
```
[AQST][pool-1-thread-1] PT.park: this=@40021bc3 fastExchange=0 time=25000 isAbsolute=false
[AQST][pool-1-thread-2] PT.unpark: this=@40021bc3 oldCounter=0 conditionIdx=-1 willSignal=false
[AQST][main] PT.park: this=@40009493 fastExchange=0 time=25000 isAbsolute=false
[AQST][pool-1-thread-2] PT.park: this=@40023863 fastExchange=0 time=25000 isAbsolute=false
[AQST][pool-1-thread-1] PT.park: this=@40021bc3 fastExchange=0 time=25000 isAbsolute=false
[AQST][pool-1-thread-1] PT.park: this=@40021bc3 TRYLOCK-FAILED-RETURN
```
`pool-1-thread-2` calls `unpark()` on `pool-1-thread-1`'s `PosixThread` (`@40021bc3`) while thread-1 is not parked (`conditionIdx=-1`, `ConditionUnset`). That is normal: `unpark()` banks a permit for thread-1's next `park()` to consume on the fast path, as `LockSupport.park`/`unpark` require. `unpark()` writes `counter = 1`. Thread-1's next `park()` (nothing else touched `@40021bc3`) reads `fastExchange=0`: the banked permit was not visible. Thread-1 blocked on its condvar and was never woken, because its AQS wakeup was already spent: an earlier `signalNext` had moved its node out of `WAITING` and called this `unpark()`, so no other thread would retry. The AQS-level trace confirms the strand: every later `release()` by other threads found `signalNext`'s target node at `status=0` (already unset by the first, lost signal) and did nothing (`NOT-woken status=0`), in a loop for the rest of the log.

**Root cause:** `PosixThread`'s permit field (`@volatile private var counter: Int = 0`, `javalib/src/main/scala/java/lang/impl/PosixThread.scala`) was written by `unpark()` with a plain assignment (`counter = 1`) while holding `PosixThread`'s private pthread mutex, but read by `park()`'s fast-path check (`counterAtomic.exchange(0)`, an `AtomicInt` wrapper over the same raw field) without the mutex, which is the point of the fast path. A mutex-protected plain store paired with a lock-free atomic load on the same field is not guaranteed to compose: the mutex's ordering does not extend to an independent lock-free read, and `@volatile` did not bridge it on this backend. This is what suspect (1) from the previous entry predicted (`LockSupport`/`NativeThread.park`/`unpark`), though the mechanism, a mutex-write/atomic-read mismatch and not a condvar-signal pairing problem, was not visible from reading the code; the AQS trace showing `unpark()` running ahead of the target's `park()` (an expected window in AQS's wakeup protocol) pointed to this field.

**Fix (`patches/scala-native-0036-fix-posixthread-park-unpark-lost-permit.patch`):** only `unpark()`'s write changes, to `counterAtomic.exchange(1)`. The other plain accesses to `counter` (the mutex-held recheck at the top of `park()`'s slow path, and the reset to `0` after a wait) stay plain: they and `unpark()`'s write all happen under the same `lock`, whose acquire/release semantics order them; only the fast-path/unpark boundary (one side without the mutex) needed the fix. A broader attempt that routed every access through `counterAtomic` broke the build: with no plain reference to the field left, the compiler eliminated it and `classFieldRawPtr(this, "counter")` failed with "class PosixThread does not contain field counter".

**Verification:**
- The raw `LinkedBlockingQueue` stress test (`availableProcessors` producers and consumers, bounded queue of 64, checksum-verified payloads), which deadlocked almost instantly before the fix, ran 2,000,000 items with no stalls after it (`release-fast`, no tracing).
- 20 runs of `dist/scalino run` compiling a small program (with `AQS_TRACE=1`): 0/20 stalled (2/20 hit the separate SMI-discriminant/`VectorStatics` corruption bug, not a fix failure).
- 15 runs with the final tracing-free patch: 0/15 stalled (1/15 hit the NPE variant of the same separate bug).

**Housekeeping:** the `AQS_TRACE` tracing in `AbstractQueuedSynchronizer.scala` was reverted (`git checkout --` in the vendor tree) and `PosixThread.scala`'s tracing was stripped, leaving the fix and explanatory comments. `vendor/scala-native`'s tree has one file modified beyond the patched baseline (`PosixThread.scala`), backed by the new patch. `dist/lib/javalib_native0.5_3.jar` contains the fix (copied from a local `sbt javalib3/publishLocal` build); the pre-fix jar is at `/tmp/javalib_native0.5_3.jar.orig-backup` (scratch path).

## commix GC tuning for scalino-linkdriver's own build, after patch 0036: a ~12% win and a core-proportional GC_NPROCS default (2026-09-22, later)

With the AQS deadlock fixed (patch 0036), the commix-vs-immix comparison for `scalino-linkdriver`'s self-compile was re-run. Earlier that day `patches/scala-native-0034-commix-parallel-stw-sweep.patch` added a `--gc-stw-sweep` `NativeConfig` flag (mutators stay paused through commix's parallel sweep instead of resuming early, trading concurrency for throughput) and raised `SWEEP_BATCH_SIZE` from the stock `32` to `256`, chosen from an `NPROCS=4` sweep of 128/256/512 (256 fastest; none differed in correctness).

**Benchmark:** repeated `./build/07-build-scalino.sh` (scalino-linkdriver compiling and linking the `scalino` CLI's ~56 sources), 3 runs per config, with a fresh immix baseline under the current system load:

| config | wall time (avg of 3) |
|---|---|
| immix (baseline) | ~22.5s |
| commix+stw-sweep, batch=256, `GC_NPROCS=2` | ~36s |
| commix+stw-sweep, batch=256, `GC_NPROCS=3` | **~19.9s** (6 runs, tightest cluster) |
| commix+stw-sweep, batch=256, `GC_NPROCS=4` | ~25.4s |
| commix+stw-sweep, batch=256, `GC_NPROCS=6` | ~26.1s |
| commix+stw-sweep, batch=256, `GC_NPROCS=8` (commix's stock default on this 10-core host) | ~26.1s |

`GC_NPROCS=3` is a repeatable sweet spot (~12% faster than immix), sharply better than its neighbors on both sides. On this 10-core host that is ~30% of cores. The linkdriver's self-compile is short-lived with a modest live heap, so commix's default thread count (tuned for big, long-running programs with real parallel sweep/mark work) adds wake/park and shared-atomic-cursor contention without enough work per thread, past a small thread count.

**One hang, not reproduced:** a `GC_NPROCS=3` run stalled for 23+ minutes at ~100% CPU before being killed; 7 follow-up runs of the same config finished in the usual ~19-21s. With one data point it may be a rare race in the new `--gc-stw-sweep` path or unrelated system contention; not root-caused. If it recurs, run `lldb -p <pid> -o "bt all"` at once (an overnight repeated-run loop would help).

**Also seen again:** the GC-independent SMI-discriminant/`VectorStatics` corruption bug (one `GC_NPROCS=4` run in the sweep and one bootstrap rebuild afterward), both with the `Unknown SMI discriminant N in tagged pointer` signature; unrelated to this GC work.

**Decision: a default proportional to core count, not a constant.** A hardcoded `GC_NPROCS=3` suits only machines with about this host's core count (oversubscribed on a small one, undersubscribed on a big one). It is implemented as a default only, proportional to `availableProcessors` (~30% of cores, floor 2, cap 8, within the range commix's stock default already exercises), in two places:
- `build/00-env.sh` exports `GC_NPROCS` (only if unset) for every `build/*.sh` script that sources it, covering this project's bootstrap (`03`/`07`/`08`, which invoke `dist/scalino-linkdriver` directly).
- `cli/ScalinoCli.scala`'s new `linkDriverGcNprocsEnv`, the path a user's `scalino build`/`run`/`package` takes. It computes the same default from `Runtime.getRuntime.availableProcessors()` and passes it in the child process environment (via `runInherited`'s new `extraEnv` parameter) only when the invoking shell has not set `GC_NPROCS`, at both `scalino-linkdriver` call sites (the link step and the test-framework probe link step).

Both formulas (`cores * 3 / 10`, clamped `[2, 8]`) are kept in sync by hand, since one is bash and the other is Scala in the compiled `scalino` binary, and no shared script or library spans both. Caveat: one host (10 cores) informs the 30% constant and its shape. It is a proportional heuristic, not a fitted curve; revisit if a different core count (a 4-core laptop, a 64-core CI runner) puts the peak elsewhere or shows it is not proportional (e.g. if heap or work size is the real driver).

**Not done:** `scalino-linkdriver`'s own build (`build/04-build-scalino-linkdriver.sh`) went from immix back to `--gc commix --gc-stw-sweep` as the default, since the ~12% win outweighs the one unreproduced hang. If the hang returns with a backtrace, reconsider commix against immix's slower but known-safe behavior.

**Lead for the separate corruption bug** (`Unknown SMI discriminant N` / `NullPointerException` in `VectorStatics.foreachRec` / `SIGBUS`): two more hits, both from `dist/scalino run` compiling a ~70-line test program. That repro takes a few seconds per attempt at a ~10% hit rate, against minutes for the 56-file self-hosted-build loop, and is the better repro for the next investigation. The mechanism is unexplained. The corruption bug reproduced before and after the permit fix (a different subsystem), so they are likely unrelated, but two bugs in this codebase's javalib concurrency primitives suggest auditing plain-vs-atomic field access patterns in `javalib`/`nativelib`.

## In-memory incremental linking for `scalino-linkdriver` (after scala-js-cli#64); incremental Reach, body-only fast path (2026-09-30)

**Goal:** fast `scalino build -w`/watch-mode relinks by keeping linker state in memory across builds, like `scala-js-cli`'s `--longRunning` (VirtusLab/scala-js-cli#64). Two earlier attempts failed. Branch `incremental-reach` (patch `scala-native-0043`, not on `main`) cached Reach's per-method reachability facts on disk; on a real project it made incremental builds slower (Reach's worklist bookkeeping was the cost, not the instruction pattern-matching the cache skipped) and caused a miscompile (dropped dynamic-dispatch targets). Branch `separate-compilation` (`FileReach`) used a coarser file-granularity reachability graph, but stalled with the codegen half (stable vtable-slot numbering, needed to reuse cached `.o` files safely) unfinished, and ~10-16x more transitive-closure bloat than Reach on a repro.

**Approach, two independent layers, active only in a new `scalino-linkdriver --long-running` process (patches scala-native-0047/0048); one-shot builds are unaffected:**

1. **In-memory NIR parse cache** (`patches/scala-native-0047-classpath-in-memory-incremental-cache.patch`, `linker.ClassPath`/`io.VirtualDirectory`): `ClassPath.Impl` instances live in a process-global registry keyed by directory URI instead of being rebuilt per build, with a per-file `(mtime, parsedDefns)` cache; a `.nir` file is reparsed only when its mtime moves. Dependency jars (stdlib, libraries) are never reparsed after the first build of a session; only the project's recompiled classes pay a parse cost per relink.

2. **Incremental Reach, body-only** (`patches/scala-native-0048-incremental-reach-body-only.patch`, `linker.Reach`/`linker.Link`): `Link` keeps the same `Reach` instance across calls (`previousReach`, process-global like the registry in (1)) and calls a new `Reach.incrementalReprocess` before building a fresh one. It patches in only a changed `Defn.Define`'s instructions, for a Top whose class hierarchy, attrs and full member-signature set are identical to what this instance saw (checked with a cheap `eq` on the `Seq[Defn]` that (1)'s ClassPath cache returns, falling back to a per-member structural diff only for Tops that changed), then re-runs the worklist (`process()`/`processDelayed()`) over just those bodies. If it cannot prove safety (added/removed/re-signatured member, changed parent/traits/attrs, a vanished Top, changed entry points/service providers/`config`/`entries`, or a lingering failure from the last build) it returns `None` and the caller builds a fresh `Reach` for that relink, an always-correct fallback.

**Why this slice avoids the first attempt's miscompile:** it never memoizes or replays a fact. Every reach rule (`reachOp`/`reachMethodTargets`/`reachAllocation`/dynamic-dispatch registration/...) runs as in a fresh build, just skipped for Tops that cannot have produced new facts. Every rule only adds to `done`/`infos`/`dyncandidates`/`dynimpls`/responds maps and never removes, so a stale fact from a body that no longer needs something is dead code kept reachable (a bigger binary, never wrong). Reachability may be monotonically non-decreasing across incremental builds, the direction `Build.scala`'s `postReachabilityAnalysisConfigUpdate` comment already documents as safe ("Optimizer and codegen may never increase reachability past what's known now").

**Bug caught in design:** `Reach.newInfo`'s `owner.members += info` always appended. That is fine for a fresh build (each member visited once) but would add a duplicate, stale `Method` entry to `ScopeInfo.members` every time `incrementalReprocess` re-ran `reachDefine` for a known member. `ScopeInfo.members` numbers vtable/itable slots by iteration position (per its doc comment), so shipping this would repeat the abandoned attempt's corrupted slot numbering. Fixed by replacing in place (`indexWhere` + `update`); a normal build is unchanged (the index is always -1).

**Status then: implemented, not benchmarked.** Both patches compiled (standalone `dotc` against scala-native 0.5.12 jars, one coursier resolution to avoid the `scala.runtime.LazyVals` cross-version trap that `04a-patch-tools.sh` documents), but `build/all.sh` had not been re-run and no relink win was measured. Next: run the bootstrap, then `scalino build -w` on a multi-file project touching only method bodies, and check that (a) relink wall-time drops build over build and (b) the binary behaves like a full non-incremental build.

**Compared with Scala.js's linker** (`analyzer/InfoLoader.scala`, `analyzer/Analyzer.scala`; the project's reference for linker design), patch 0048 is more aggressive than Scala.js. `InfoLoader` caches per-method extracted facts (`Infos.MethodInfo`: declared references/calls, not resolved reachability) keyed by a compiler-emitted `Version` stamp, but `Analyzer` never persists or patches its worklist or graph across links; each link reruns the full worklist from entry points over cached facts. That matches patch 0047 (cache extraction, not the graph), not 0048 (keeps the same `Reach` instance and patches its live graph). Scala.js's deep incrementality is later, in `backend/emitter/KnowledgeGuardian.scala`/`Emitter.scala` (per-method JS output cached and invalidated by tracked dependencies), the counterpart of scala-native's codegen/LLVM-IR-gen plus `LLVM.compile`'s per-`.o` mtime cache, not of Reach/Analyzer.

**Decision: keep patch 0048 as built.** The abandoned `incremental-reach` branch's postmortem found that caching facts alone did not help scala-native: the worklist bookkeeping over the full reachable set (tens of thousands of symbols with javalib/scalalib) dominated, not per-instruction fact extraction. Patch 0048 is the only design that avoids revisiting untouched symbols. If a benchmark shows no win, fall back to the Scala.js-style shape (cache facts only, rerun Reach's worklist fully) instead of concluding incremental Reach is a dead end.

**Bootstrap and real-project benchmark (2026-09-30, same day):** `build/all.sh` ran clean end to end with both patches (0047, 0048) in `SHARED_SOURCES`. Two bugs fixed on the way (cross-Scala-version and patch-tooling mistakes, not design problems):
- `ClassPath.scala`'s `cache.filterInPlace(...)` does not compile: `mutable.Map.filterInPlace` is a 2.13+ addition, and this file also cross-builds under Scala 2.12 for scala-native's sbt meta-build (`vendor/scala-native/project`). Replaced with `keysIterator.filterNot(...).foreach(cache.remove)`.
- Patch files 0047 and 0048 twice failed to `git apply` from a fresh vendor checkout. Plain `git diff` (no ref) compares the working tree to the index, which in this disposable, never-`git add`-ed vendor checkout is the pristine clone, not "pristine plus patches 0001-0046". For files that other patches also touch (Reach.scala/Link.scala are touched by ~4 earlier patches, including 0039's `collection.Seq` rename), it captures the cumulative diff from pristine, which conflicts when reapplied over those patches. Fix: snapshot the post-0046 (or post-0047) file into an isolated directory and use `git diff --no-index` against the edited copy. `ClassPath.scala`/`VirtualDirectory.scala` (patch 0047) were fine with plain `git diff`, since no earlier patch touches them. Use the snapshot method when a new patch touches a file already modified by more than one earlier patch.

**Benchmark: `~/scala/ape` (30 own source files / ~6.4k lines; http4s+cats-effect+porcupine/sqlite+scalatags+upickle; 8265 classes / 52226 methods reachable), via a driver script on `scalino-linkdriver --long-running`'s stdin/stdout protocol** (bypassing `ScalinoCli` to isolate the linker from its separate incremental-compile heuristic; see the caveat):

| build | what changed | Reach ("Linking") phase | Total pipeline |
|---|---|---|---|
| 1 (cold, fresh process) | n/a (first build) | 8077 ms | 59.6 s |
| 2 (warm, no-op) | nothing | (skipped: `Build.buildCachedAwait`'s whole-build checksum hit) | 0.03 s |
| 3 (warm, incremental) | one method body in one file (`util/Logger.scala`), **all 30 files force-recompiled** (`--no-incremental`, see caveat) so every `.nir`'s mtime moved | 1879 ms | 71.5 s |

Build 3's reachable set equalled build 1's (8265 classes / 52226 methods, `DONE` both times). **Reach dropped from 8.08s to 1.88s (~76%, ~4.3x) although every file's mtime changed**, because `incrementalReprocess`'s per-Top structural comparison (not the `eq` fast path) found 29 of 30 Tops content-identical to what it had and re-walked one. This is the harder case: the `eq` fast path (ClassPath's mtime cache, patch 0047) could not help, since a forced full recompile bumps every mtime and gives each Top a fresh, not `eq`-identical, `Seq[Defn]` whatever its content.

**Caveat on `--no-incremental` for the compile step:** `ScalinoCli.compileToClasses`'s incremental-compile heuristic (a name-usage BFS closure over identifier tokens, unrelated to the linker work) has a pre-existing bug: a partial recompile after the single-file edit broke cross-package symbol resolution (`db.BuildingRepo`/`db.DraftRepo`/`http.Views` "not found" in unchanged files). Not investigated here (see the next entry); `--no-incremental` avoids it.

**Not measured:** total-pipeline time is dominated by codegen (`Generating intermediate code`, `Compiling to native code`), which these patches do not change; those stages have their own caching (`LLVM.compile`'s per-`.o` mtime cache, `codegen/IncrementalCodeGenContext.scala`/`SourceCodeCache.scala`). A dev-loop win needs `ScalinoCli`'s real incremental compile to touch only the changed file's `.nir` on a body-only edit, which the compile-heuristic bug blocks. Until then `scalino build -w`'s end-to-end win is smaller than the isolated Reach number, though the Reach gain applies fully to setups (e.g. Metals-driven incremental compilation) that bypass scalino's invalidation heuristic.

## Crash on a second project (`~/scala/test-native`): every jar's zip FileSystem reopened on every `--long-running` relink (2026-09-30, later)

**Reported symptom:** `scalino package -w` on a second project failed on a warm incremental rebuild with `scalino-linkdriver: cats-mtl_native0.5_3-1.7.0.jar` then `scalino: linking failed`: a bare jar filename as the whole error, printed by the new `catch` block in `LinkDriver.scala`'s `--long-running` loop (`e.getMessage`).

**Root cause:** `ClassLoader.fromDisk(config)` called `ClassPath(VirtualDirectory.real(path), config.logger)` for every classpath entry on every call, including warm relinks where `ClassPath`'s registry (patch 0047) already had the entry. For a jar, `VirtualDirectory.real` means `FileSystems.newFileSystem(uri, ...)`, so every relink reopened every dependency jar's zip `FileSystem` and threw it away once `ClassPath.apply`'s registry lookup hit (`computeIfAbsent`, keyed by the already-constructed `VirtualDirectory`'s identity; Scala evaluates arguments eagerly). The discarded reopen racing the still-open cached one produced the bare jar-filename exception.

**Fix:** `ClassPath.forPath(path, log)(mkDirectory: => VirtualDirectory)` checks the registry before evaluating `mkDirectory`, so a hit never touches the filesystem. `ClassLoader.fromDisk` uses it. Folded into patch 0047, which now touches `ClassLoader.scala` too (needing the isolated-snapshot `git diff --no-index` technique, since patch 0039 also touches `ClassLoader.scala`).

**Verified:** a driver script on `scalino-linkdriver --long-running`'s stdin/stdout protocol ran 2 consecutive relinks (real edits, real recompiles) cleanly where the same pattern crashed on the first warm relink.

**Separate, not fixed: a native-heap OOM by the 3rd consecutive relink.** With 3+ consecutive real edits, `scalino-linkdriver` crashes with its GC's `Out of heap space cannot allocate more objects` on `~/scala/ape` (~8265 classes / 52226 methods). Ruled out:

- **Not memory pressure.** First reproduced with under 1GB free RAM (16GB machine); with ~3.7GB free it still OOM'd by build 3, sooner (~21s vs ~65s) and at a lower peak RSS (5952MB) than build 1's successful peak (8943MB). Immix's default max heap is unbounded (limited by available memory at process start), so a bigger heap ceiling is not the answer.
- **Not a false positive in `incrementalReprocess`'s change detection.** A temporary debug line (`safe`/`bodyPatches.size` per call; rebuilt only `scalino-linkdriver` with `04a-patch-tools.sh` + `04-build-scalino-linkdriver.sh`) showed `safe=true, bodyPatches.size=0` on build 2: the comment-only edit used to force a relink does not change compiled NIR, `incrementalReprocess` correctly took its "nothing changed" path, and `previousReach` was reused. The OOM is not this Reach-reuse logic misfiring.
- **Not the multithreading-config-reload path.** `Build.scala`'s `postReachabilityAnalysisConfigUpdate` can trigger a second `Link.apply`/`Reach.apply` in one `Build.build` with an updated `config` (disabling multithreading if unused) whenever `config.compilerConfig.multithreading.isEmpty`, which would make `incrementalReprocess`'s `newConfig != config` guard bail every time. The manual benchmark driver omitted `--multithreading` (which `ScalinoCli` passes by default), so it probably hit this path. With `--multithreading` and verbose logging, the debug line confirmed `previousReach` reuse engaged, and it still OOM'd by build 3. The driver's argv was a benchmark gap (fix before trusting its timings), not the cause.
- **Not implicated:** `IncrementalCodeGenContext`/`SourceCodeCache` (the codegen-side caches in `SHARED_SOURCES`) are built fresh per build and persist state to disk, not in memory.

**Not found:** what is retained and growing. Untested suspects: (a) `ClassPath.Impl` cache churn: each `--no-incremental` full recompile bumps every project file's mtime, forcing a fresh `deserializeBinary` reparse even for identical files, and the resulting "reparsed, compared equal, discarded" `Defn` garbage may not be collected promptly between builds (an immix GC timing/threshold effect, not a leak); (b) something reachable through the process-lifetime `Scope` this work's `LinkDriver.scala` change introduced (one `Scope.apply` around the whole `--long-running` loop, where a one-shot process got a fresh per-build `Scope`): any `acquire`/`defer`'d resource in the build pipeline (temp files, native LLVM contexts, thread pools) that assumed "closes at the end of this build" would accumulate for the process lifetime. (b) was flagged as a structural risk when the single-Scope design was written and is not ruled out.

**Repro:** ad hoc `/tmp/ape_bench_driver*.py`-style scripts (not committed) talk to `scalino-linkdriver --long-running`'s stdin/stdout protocol against `~/scala/ape` (or a similar project) with `--multithreading` in argv, toggling a trailing comment in one file and forcing `scalino compile --no-incremental` between triggers so each relink is real (not the whole-build checksum skip). It OOMs by the 3rd or 4th iteration. Next diagnostics: a `--heap-histogram`-instrumented rebuild of `scalino-linkdriver` itself, or giving `LinkDriver.scala` a fresh per-build `Scope` while keeping `ClassPath`'s registry and `Link.previousReach` process-global, to test (b). **Paused here at the user's direction to investigate the `ScalinoCli.compileToClasses` incremental-compile bug (next entry).**

## `scalino compile`'s incremental (partial-recompile) path does not work with self-hosted `scalino-dotc` (2026-09-30, later)

**Symptom:** on `~/scala/ape`, editing only `src/util/Logger.scala` (a trailing comment, so the compiled `.nir` is byte-identical) and running `scalino compile` again selects `toCompile = [Main.scala, Logger.scala, ErrorHandling.scala, Backup.scala]` (temporary debug print, reverted), a reasonable invalidation set (files mentioning `Logger`/`Backup`). The `scalino-dotc` invocation then fails with "not found" for `db.BuildingRepo`, `db.DraftRepo`, `db.Schema`, `http.Views`, declared in other, unchanged files correctly left out of `toCompile`.

**Root cause: self-hosted `scalino-dotc` emits only `.nir`, never `.tasty`/`.class`.** `find .scalino-build/_scratch/classes -name '*.tasty' -o -name '*.class'` returns nothing; only `.nir` (851 for this project). `compileToClasses`'s incremental scheme needs a partial recompile to resolve symbols from unchanged files via their compiled artifacts (`compileCp = classesDir:...`; its comment: "classesDir on the compile classpath ... is what lets scalino-dotc resolve symbols from files it isn't recompiling this round out of their already-compiled .tasty/.class"). dotc's frontend/typer reads compiled-symbol signatures from `.tasty` (or `.class`); `.nir` is scalino's backend/linker IR, meaningless to the typer. So any partial recompile whose file set references a symbol declared outside it fails. That is the normal case for a multi-file project (`db.BuildingRepo`/`db.DraftRepo`/`db.Schema` are files in the changed file's transitive graph that did not change).

**Why it was not caught:** `compileToClasses`'s doc comment and the `.tasty`/`.class` layout it assumes predate the 2026-09-07 self-hosting cutover, when `scalino-dotc` was a GraalVM native-image build of upstream dotty that emitted real `.tasty`/`.class`, and the classpath-resolution assumption held. After `scalino-dotc` became self-hosted (backend emits `.nir` only), the assumption stopped holding without being exercised: `scalino build`/`run`/`package` mostly does full rebuilds (the first build always is), and the repeated partial recompiles of a built project in this session's `--long-running` benchmarking exposed it. `fullRebuild` (on a fingerprint change or the first build) compiles everything in one dotc invocation with full source visibility and needs no classpath resolution, so every first build in every benchmark looked fine.

**Not fixed in that session.** A fix needs one of: (a) make self-hosted `scalino-dotc` also emit `.tasty` (or an equivalent) next to `.nir`, so the typer resolves previously compiled symbols normally; (b) widen `toCompile` to the full transitive dependency closure of every changed file, so nothing outside it is referenced from inside it (correct, but degrades toward `fullRebuild` for interconnected projects); or (c) at minimum, detect the failure and fall back to a full rebuild instead of failing with a "not found" error that looks like a source bug. (a) and (b) are non-trivial ((a) touches the compiler's output format); (c) is the cheapest correctness patch but restores no incrementality. Severity: it affects `scalino compile`/`build`/`run` for any multi-file project whenever a partial recompile triggers, independent of the `--long-running`/incremental-Reach work. Related: the self-hosted `scalino-dotc` can write `.tasty` with `-Xearly-tasty-output <dir>` (see "Broader regression sweep").

## Making the whole reach -> optimize -> codegen pipeline minimal-work, not just Reach: investigation (2026-09-30, later)

**State of the other two stages, checked against source:**

- **Codegen is already incremental** (pre-existing). `codegen/llvm/CodeGen.scala`'s `separateIncrementally()` uses `IncrementalCodeGenContext` (`codegen/IncrementalCodeGenContext.scala`) to hash each per-source-file/per-directory group of post-optimization `Defn`s and skip regenerating that group's `.ll`/`.o` (`ctx.shouldCompile(key)`) if the hash matches the persisted one (`package2hash`, disk-backed, gated on `Build.userConfigHasChanged`). It works across processes, not just within one `--long-running` session. `LLVM.compile`'s per-`.o` mtime cache (separate) sits below it.
- **Reach is now incremental** (patches 0047/0048): untouched Tops are not re-walked.
- **Optimize (`Interflow`) is not incremental.** `Interflow.optimize` (`interflow/Interflow.scala:187-195`) always creates a fresh `new Interflow(config)(analysis)` and runs a full `visitEntries()` + `visitLoop()` worklist over `analysis.defns` (every reachable `Defn`), keeping no state between calls: the walk-everything-and-discard shape `Reach` had before 0047/0048.

**Why Interflow is not a repeat of the Reach fix.** Reach's monotonic-safety argument (an incremental relink only adds reachability facts, so skipping untouched Tops is safe) does not carry over. Interflow transforms across methods: inlining (`Inline.scala`/`PolyInline.scala`) and constant/shape propagation (`Combine.scala`/`Eval.scala`/`State.scala`) can bake one method's current body into another's optimized output. If `A` is inlined into `B` and `A`'s body then changes, `B`'s old optimized artifact is wrong (it ships `A`'s old logic), not merely stale. Reusing per-method optimized output keyed only on that method's own content hash (the Reach strategy) would risk a silent miscompile, the failure class patch 0048's design avoids.

**What a safe design needs:** a dependency-aware cache recording, for each optimized output, which other Defns' content it was built from (everything inlined into it, everything whose constant-propagated shape it consumed), invalidating an entry when anything in that set changes. `Interflow` does not track this (`Inline`/`PolyInline` make decisions without recording them durably). That means instrumenting the inliner to emit a dependency record per optimized Defn and a safety-gated reprocessing patch like 0048, for a stage whose job is to blur method boundaries, so dependency sets are denser and invalidation cascades likely larger than Reach's.

**Not started at that point:** a scoping investigation only, by direction, with the native-heap OOM (see "Crash on a second project") unresolved. Suggested start: instrument `Inline.scala` to record, per inlined call site, `(caller: Global.Member, callee: Global.Member, calleeBodyHash)` durably enough to check on a later build, before any skip logic (as for Reach's `rawLoaded`/structural-key comparison before touching `process()`/`processDelayed()`).

## Scala.js's optimizer solves this problem, and the same technique applies to Interflow (2026-09-30, later)

Scala.js's `IncOptimizer.scala` (`linker/frontend/optimizer/`) ships incremental cross-method inlining and constant propagation in production. Its mechanism transfers to `Interflow`; both sides were checked against source.

**The mechanism (`IncOptimizer.scala`, `Processable`/`MethodImpl`):** every optimizable method is a `Processable` with an `originalDef` (raw, pre-optimization body, replaced when a source change arrives) and a cached `optimizedDef`. When A's optimizer wants to inline or reason about B, it calls `B.askBody(asker = A)`, which returns B's `originalDef`, never its `optimizedDef`, and registers A as a body asker of B (`B.bodyAskers.put(A, ())`) and B as a dependency of A (`A.registerTo(B)`). When B's `updateWith(newDef)` finds B's original body changed, it calls `tagBodyAskers()`: each asker (A) is `.tag()`ed (marked dirty, scheduled for reprocessing, and unregistered from everything it had asked, so its dependency edges are rebuilt when it is reprocessed). Untagged `Processable`s reuse their cached `optimizedDef`.

**Why it stops at one hop:** the cross-method information channel is `askBody`/`originalDef`. An asker's inlining decision depends only on the callee's original source-level body, never on what the callee optimized to. If B's original body is unchanged, A's inlining of B stays valid whatever B's own optimized form becomes. Invalidation walks one edge per changed original body, not a chain.

**Does `Interflow` read another method's optimized output anywhere?** Checked: `interflow/Inline.scala` calls `maybeOriginal`/`getOriginal`, which read `Interflow`'s own `originals` map (built from `analysis.defns`, Reach's raw pre-optimization result), never `done` (the map of optimized results). `interflow/Combine.scala`/`State.scala` do not reference `done` or `getOriginal` across methods: their inter-procedural reasoning happens within one method's `optimize()`, over callee bodies pulled in via `getOriginal`/inlining. `done`/`isDone` (`Visit.scala`) is only a worklist dedup guard, the same role `Reach`'s `done` plays. So `Interflow`'s cross-method information flow already has the property Scala.js's `IncOptimizer` relies on, with no change; scala-native just has no incremental reuse layer on top (inlining from the pristine definition is the natural way to write an inliner).

**The design is concrete.** A `Reach`-shaped patch:
- Persist one `Interflow` across builds (`previousInterflow`, process-global like `Link.previousReach`) instead of a fresh instance per `Interflow.optimize`.
- Each call, diff the new `analysis.defns` against the per-`Global.Member` cache with the `eq`-first-then-structural check from patch 0048's `rawLoaded` handling. This needs nothing from Reach: its `done` map already returns the same `Defn` reference for anything it did not reprocess, and a fresh one for anything it did.
- Instrument `getOriginal`/`maybeOriginal`/`hasOriginal` to record askers (Scala.js's `bodyAskers`), mirroring `MethodImpl.askBody`.
- Dirty set = members whose original `Defn` changed ∪ their one-hop askers. Reprocess those; reuse `done`'s optimized `Defn` for the rest.
- The same safety valve as 0048: anything not provably safe (a method appearing/disappearing, `analysis` shape changes not handled elsewhere) falls back to a fresh `Interflow`.

**State then: a validated design, not started.** Patch 0048's budget went largely into safety-proving edge cases even with its simpler monotonic invalidation, so this is comparable work. Recommendation: resolve the OOM first, since a `--long-running` session that crashes by the 3rd relink should not get more long-lived state.

**Update, same day: implemented anyway, on direction to fix this now and set the OOM aside.** `patches/scala-native-0049-incremental-interflow-one-hop-askers.patch` (`interflow/Interflow.scala`, `interflow/Visit.scala`) follows the design and Scala.js's `IncOptimizer`:

- `Interflow`'s `analysis` constructor param is now `implicit var` (was `val`), so a later call can swap in a new `ReachabilityAnalysis.Result`, as `Reach` does with `protected var loader`.
- `askers`/`askedBy` (`ConcurrentHashMap[Global.Member, java.util.Set[Global.Member]]`, both directions, mirroring `IncOptimizer`'s `bodyAskers`/`registeredTo`) and a `currentAskerTl` thread-local with `withAsker`/`recordAsk`. `hasOriginal`/`getOriginal`/`maybeOriginal` each call `recordAsk(name)` first; they are the only cross-method read path in the optimizer, so this is the single interception point.
- `Visit.visitMethod` wraps its body in `withAsker(name) { ... }`, setting "who is asking" for that method's optimize pass, with save/restore so nested `visitMethod` calls (specialization only, gated off below) attribute correctly.
- `Interflow.incrementalReprocess(newConfig, newAnalysis)` returns `None` (disabled) for `build.Mode.ReleaseFull`, where call-site specialization (`shallDuplicate`/`duplicateName`) creates extra `Global.Member`s (`Sig.Duplicate`) on demand that are not in `originals`; reasoning about them against the asker graph is extra design this patch does not attempt. Other modes never create one (`shallDuplicate` is unconditionally `false`). It also bails on `newConfig != config` (as `Reach`). Otherwise, for every member in the new `analysis.defns`, two independent proofs make reusing `done`'s cached entry safe: the raw original `Defn` is unchanged (`eq`, falling back to structural `==`), and `analysis.infos` for that member is the same object as last time (Reach's `incrementalReprocess` is all-or-nothing safe, so a member's `Info` is `eq`-identical only if its class/trait shape is unchanged). Any non-`Defn.Define` top-level content change bails to `None` as defense in depth (should be unreachable given the `infos` argument). Dirty set = changed members ∪ one-hop askers (`askers.get(changed)`); each gets `done`/`started`/`denylist`/`reached` cleared and its stale asker edges dropped (`resetForReprocessing`) before being pushed onto the existing `todo`/`visitLoop`/`visitMethod` machinery, which then does the minimal work and rebuilds the asker edges.
- `Interflow.optimize` (companion) has a process-global `previousInterflow: Option[Interflow]`, as `Link.previousReach`: it tries `incrementalReprocess`, else builds and runs a fresh `Interflow`. `ScalaNative.scala` (the only caller) is unchanged.

**Verified:** compiles standalone (as 0047/0048: isolated `linker`+`interflow`+`VirtualDirectory.scala` sources, stock scala-native 0.5.12 jars via one coursier resolution, `nir.Type.Smi` lines stubbed because that scalino-only patch is not in the stock jar); applies cleanly from a fresh `00b-setup-vendor.sh` reset after 0047/0048; the full bootstrap succeeds.

**Correctness, with a miscompile-detecting test:** a small project (`Main.main` calling `Helper.add(2, 3)` and printing the result) through `scalino-linkdriver --long-running`: build 1 (cold) prints `5`. Editing `Helper.add`'s body from `a + b` to `a * b` (an inlining candidate, the edit the one-hop mechanism must handle) and forcing a relink (`scalino compile --no-incremental` + a trigger line) prints `6`, not a stale `5`: `Main.main`, which inlined the old body in build 1, was identified as a one-hop asker, invalidated and re-optimized. A third (no-op) relink stayed correct and near-instant. At this scale `Linking` went 1297ms -> 430ms and `Optimizing` 871ms -> 380ms on the real-edit build.

**On `~/scala/ape` (8265 classes / 52226 methods), both phases got slower, and the OOM hits one build earlier (build 2 instead of 3), reproduced twice.** `Linking` stayed at the cold level (~8s) and `Optimizing` went from 8.3s (cold) to 15.5s before OOMing. Not chased, by direction. It is consistent with the OOM entry's hypothesis that a `--long-running` session retains more live state than it can afford at this scale: patch 0049 adds a second persistent ~52K-method-sized structure (`Interflow`'s `originals`/`done`/`askers`/`askedBy`) on top of `Reach`'s (`previousReach`'s `loaded`/`rawLoaded`/`infos`/`done`), the retention the OOM entry flagged as a risk, now confirmed to bring the ceiling sooner. The logic is sound (shown above); the problems are additive. Re-check patch 0049 when the OOM is fixed, since it makes that retention worse.

## Escape analysis, round 2: specializing callee summaries on the exact class of fresh arguments (2026-10-06)

State when this started: the interprocedural escape analysis (patch 0061, bounded by 0064) and allocation-oriented inlining were already **on by default in the release modes**; the entry from 2026-10-04 in the project notes calling them opt-in was stale. Dotc typecheck of its own 550 source files allocated 98.0M objects with both off and 83.6M with both on.

**Where the allocations actually are** (new per-site profiling, `build/escape-analysis/README.md`: a profiling GC counts allocations per class, `SCALANATIVE_SITE_PROFILE` counts executions per allocation site and the report joins them with the analysis' escape reason): closures were the biggest category by far, 22% of all allocations (17.5M; 438MB), well ahead of `::` (11%, mostly real list building), trees/types (9%), `Some`/`Tuple2` (3.6%), boxes (0.3%: `Int`/`Boolean`/`Char`/... boxes are SMI tagged pointers and never allocate; only `Long`/`Double` boxes do). Closures escaped because the callee calls them through `Function1.apply` & co, a call with thousands of possible targets that the analysis answered with "unknown call: the receiver escapes", even though at the call site the closure was allocated a few instructions earlier and its class is known exactly.

**What patch 0068 does**
- *Summaries specialized on exact argument classes.* A call whose argument is a fresh allocation (NIR type `Ref(C, exact)`) analyzes the callee as `Key(callee, {param -> C})`: inside it the parameter has the exact class `C`, so `f.apply(x)` resolves to `C.apply` and its real summary is used. Only parameters that the unspecialized summary says escape *and* that are the receiver of a virtual call are specialized (`Summary.recv`); at most 4 hints per key; callees over 2500 instructions are not specialized. ~50k specialized keys on dotc. Lambda sites that are stack allocatable: 126 -> 6544 of 43927; anonymous iterators 36 -> 702; stack objects in dotc 45.0k -> 51.0k; analysis time ~10s -> ~19s (JVM).
- *Depth-aware escape summaries*: `escapes(i)` (the parameter itself may be stored/captured) is distinguished from `escDeep(i)` (only objects reachable through its fields), so a callee that leaks a captured value no longer forces the closure holding it onto the heap.
- Atomic externs (`scalanative_atomic_*`) are modeled as memory operations and `llvm.*` intrinsics as non-capturing, instead of "unknown call".
- `scope.targets` is cached per (scope, sig) (it walked every implementor on every analysis of every call site: analysis time 42s -> 18s on its own), and a function's callers are registered only after the callee is analyzed (no needless requeue of the caller that is about to use the fresh summary: reanalyses 99k -> 79k).
- Tooling: escape reasons with the callee chain that explains them, per-site profiling, `SCALANATIVE_STACK_POISON`, bisecting by site hash, mutation knob; see `build/escape-analysis/README.md`.

**Result** (dotc typecheck of its own 550 files): 98.0M allocations off -> 83.6M (before) -> **78.2M** (-20% vs off, -6.4% vs before; `Types$Type$$Lambda$34` 1.2M -> 0, `Some` 2.2M -> 1.6M, `Long` boxes 0.9M -> 0.25M, `ObjectRef`, `OrderingConstraint`/`TypeComparer` lambdas, ...). **Wall time did not move** (12.0s vs 12.1s, noise): the GC is ~4% of this workload and allocation is cheap, so fewer allocations alone are not a performance win here (RSS is unchanged for the same reason, it is heap-growth policy, not garbage). Link time on dotc: +~10s.

**A soundness bug that only specialization exposed, and how it was found.** The first specialized build compiled most things fine and passed the typecheck workload, but `examples/interpreter-regressions` failed with a `MatchError` whose lazily computed message read a dead stack slot (`objString$lzyINIT1`, SIGSEGV). Bisecting the stack allocated sites by hash (`SCALANATIVE_STACK_HASH`) down to 16 candidates, then reading the NIR (`SCALANATIVE_ESCAPE_DUMP`), found it: `ScopedVar.scoped(ass, body: => T)` calls `body.apply()`; specialized on the `Function0` lambda, the callee's summary says "the contents of `body` escape" (the lambda's `apply` calls an unknown closure it captured), which the analysis recorded as a `ContentsGlobal` flag on the parameter node, **but `scoped` never loads a field of `body`, so the node had no content nodes and the summary builder, which only looked for Global content nodes, reported no deep escape**. Fixed in `summarize` (the flag itself counts). Lessons that are now part of the workflow:
1. A dangling stack object is silent: dead stack memory stays intact until reused (a build with the analysis deliberately made unsound still printed the right answer). `SCALANATIVE_STACK_POISON=1` makes every function overwrite its stack objects when it returns or throws, so the same unsound build crashes 5 out of 5 runs. Validate every change with it.
2. `examples/escape-analysis` is only worth something if it fails when the analysis is wrong: `SCALANATIVE_EA_X=mutate-deepfix` re-introduces the bug above and the example then prints a different sum (needs `@noinline` helpers, otherwise Interflow inlines the whole call chain before the analysis sees it and the test passes with the bug in). CI runs it with poison in `release-fast` and `release-full`.
3. Differential testing of the compiler built with the analysis against the known good one over Scala 3's `tests/pos` (3376 single-file programs, full Scala Native pipeline: exit code, diagnostics, TASTy hash and output sizes) shows no difference; `build/escape-analysis/pos-differential.sh`.

**Evaluated and not done, with the numbers** (all on the dotc workload):
- *Flow-insensitive throw paths* (`Tree.tpe` capturing `this`, `MatchError(x)`, ...): ignoring **every** exception-capturing flow (unsound, `SCALANATIVE_EA_X=throwcap,matcherror`) makes only ~600 more of 210k allocation sites eligible (+0.3%). The one pattern that matters is `throw new MatchError(xs)` in `ScalaRunTime.array_apply/array_update` (the `Array.copy` slow path), about 1% of allocations; the only sound fix is path pruning with type refinement (the `is[array]` tests fail for the argument's known exact type), not copying the object into the exception (that changes identity) and not eager `toString` (the unknown `toString` call makes the receiver escape anyway).
- *Allocation-returning small functions* (`Some`, `Tuple2`, `::`, iterators): 14M dynamic hits (15%) sit in sites whose object is only returned, but the biggest are virtual (`List.iterator`, `ArraySeq.iterator`: several targets, no caller-allocated slot possible), strings (`StringBuilder.toString`), or list cells built in loops. Letting the allocation inliner ignore the caller size limit for tiny callees changed nothing (only ~700 of 160k decisions were size limited), so the factories left (`Tuple2.apply`, `unsafeWrapArray`, ...) are not blocked by inlining limits. A caller-allocated return slot would cover direct calls only; estimated 2-3% of allocations for a new function variant per callee. Not started.
- *Boxes*: see above, `Int`/`Boolean`/`Char`/`Float`/unsigned/`Ptr` boxes never allocate (SMI/PtrTag), `Long` boxes are now 0.25M (0.3%), nothing left worth a Lower change.
- `scala.reflect.ClassTag.apply(Class)` allocates a `GenericClassTag` on every call, 2.3M times (3%): Scala Native's scalalib override removed the `ClassValue` cache. A fix is a cache in a scalalib override, outside the escape analysis.
- The remaining closures/contexts mostly are real: stored in buffers (`BaseData` lambdas in an `ArrayBuffer`), put in the `Context` store map, or used through a megamorphic call on a *field* (`CheckedIterator.hasNext` calls the wrapped iterator), which would need a heap-type (field class) analysis.

## Intermittent `ArrayIndexOutOfBoundsException: Index N out of bounds for length -1925004801` on Linux x86_64: the GC safepoint trampoline clobbered `%r10` and the red zone (2026-10-06)

**Symptom.** In CI on `ubuntu-24.04` (x86_64 only; v0.0.16 crashed with a segfault, v0.0.17 with this exception), the self-hosted `scalino-linkdriver` died in `java.lang.String.compareTo` called from a sort / red-black tree with `Index N out of bounds for length -1925004801`. Same length (`0x8d42c1ff`) at different sites and versions, intermittent, never on arm64.

**Root cause (not GC, not compact headers).** `SafepointPollTrampoline-x86_64.S` (the stub the SIGSEGV handler redirects a thread to when it hits an armed yield-point page) had two bugs:
1. It ended with `movq 256(%rsp), %r10 ... jmp *%r10`. `%r10` had just been restored, then overwritten with the resume PC. LLVM allocates `%r10` freely, so any thread stopped at a poll with a live `%r10` resumed with that register replaced by a code address.
2. The 400-byte frame was written at `%rsp-400`, on top of the 128-byte SysV red zone that leaf functions use for spills. (AArch64 has no red zone in LLVM's default codegen, which is why only x86_64 was affected.)

The constant length is the proof. In the shipped v0.0.17 linkdriver, `String.compareTo`'s loop keeps `string.value` (`target`) in `%r10`, polls (`mov (%r14),%rax; mov (%rax),%rax`), then reads `target.length` with `mov 0x4(%r10),%eax`. After the bad resume `%r10` is the address of the polling instruction, so the "length" is the 4 code bytes at `pc+4`: `ff c1 42 8d` (`inc %r9; lea ...`), i.e. `0x8d42c1ff`. Same loop, same machine code in every version, hence the same value. A second variant in the logs (`Index 1446198218 out of bounds for length 1024` in `IO$Sha1Digest.update`) is the same clobber landing on an index register.

**Fix: patch 0069.** `prepare_redirect` now emulates a `call` that steps over the red zone (`%rsp -= 128 + 8`, interrupted `%rip` stored in the new top slot) and the trampoline returns with `ret $128`, which needs no scratch register and leaves the flags alone. The trampoline also aligns `%rsp` to 16 bytes before calling C (it used to pass on whatever alignment the poll site had). aarch64 is unchanged (it resumes through `x16`; see the open question below).

**Verification (Linux x86_64 container).**
- `tests/safepoint-trampoline/run.sh`: poll against an armed page with known GPRs/XMM/flags/red zone, through the real `prepare_redirect` + trampoline. Before: `r10 = 0000555555555918`, 16/16 red-zone qwords clobbered. After: passes, aligned and misaligned `%rsp`.
- `examples/safepoint-trampoline`: leaf loop with 22 live values (spills into the red zone) in 4 threads while another thread calls `System.gc()`. v0.0.17: 230-293 wrong results per run, 3/3 runs. With the 0069 nativelib: `ok (400 checks)`, 3/3.
- Reproduced the CI failure itself: 5 of 53 clean-directory builds of a snunit hello-world with the unmodified v0.0.17 linkdriver (`--cpus 3`, 3 GB, amd64 container), all with this signature.
- Real linkdriver with the 0069 trampoline (an `LD_PRELOAD` shim swapping it into the unmodified v0.0.17 binary, same container and project): 0 crashes in the first 50 builds, versus 5 in 53 without it. The linkdriver itself still has to be rebuilt on x86_64 to ship the fix.

## Scala.js support, phase 0 spike (2026-10-31)

Goal: confirm the version set and the riskiest assumptions before patching anything. Everything below ran on a plain JVM with published artifacts; nothing in scalino changed.

- **End to end works**: published `scala3-compiler_3:3.9.0` with `-scalajs` -> `.sjsir`, linked with `scalajs-linker_2.13:1.22.0` (ESModule, no optimizer, no Closure), `node main.js` prints the expected output. Link of Hello: ~1s wall on the JVM, 294 KB unoptimized JS.
- **Artifact set** (all resolvable by plain coursier, no JVM-only tooling): `org.scala-lang:scala3-library_sjs1_3:3.9.0` (note org `org.scala-lang`, not `org.scala-js`), which pulls `scalajs-library_2.13`, `scalajs-scalalib_2.13:3.9.0` and `scalajs-javalib`. Resolution picks scalajs-javalib **1.22.0**, so the linker must be >= 1.22.0 even though dotc 3.9.0 builds against Scala.js 1.20.x (an older IR is fine for a newer linker). Pin Scala.js 1.22.0.
- **Entry point**: `@main def hello` is linked with `ModuleInitializer.mainMethodWithArgs("hello", "main")`.
- **Linker compiles as Scala 3**: all 188 sources of scalajs-linker, linker-interface, ir and logging 1.2.0 compile with the published dotc using `-source:3.0-migration -language:implicitConversions`, no errors (2014 classes). dotc itself already bundles scalajs-ir source (`vendor/scala3/project/Build.scala`, "sourcedeps").
- **Known gaps for a Scala Native build of the linker (phase 2)**:
  1. `backend/closure/*` (4 files, needs `com.google.javascript`): exclude; `fullOpt` then means the Scala.js optimizer without GCC.
  2. `PathIRFile` / `PathOutputDirectory` use `AsynchronousFileChannel`: replace with our own `IRFile`/`OutputDirectory` implementations (plain blocking IO; interfaces live in linker-interface).
  3. `frontend/optimizer/ParCollOps.scala` uses scala-parallel-collections: use the sequential variant (the JS build of the linker already has one).
  4. Everything else imported from `java.*` is ordinary (`ConcurrentHashMap`, atomics, `MessageDigest`, nio.file).
- **Phase 1 reminder**: `gen-file-list.sh` excludes `backend/sjs`, `transform/sjs`, `SJSPlatform`; findings above (Scala.js leak sites) say patches were applied to make `ctx.settings.scalajs` dead. Those need to be reversed, not just re-included.

## Scala.js support, phase 1: dotc `-scalajs` backend re-included (2026-10-31)

`patches/scala3-0039-scalajs-backend.patch` reverses the Scala.js cut-outs of patch 0006 and adds the build wiring:

- `Compiler.scala`: `sjs.PrepJSInterop`, `ExplicitJSClasses`, `AddLocalJSFakeNews`, `JUnitBootstrappers` back in the phase list, `backendPhases = List(GenSJSIR)`. All are `isEnabled` only under `-scalajs`, and the nscplugin stays gated on `-Xplugin-require:scalanative`, so native builds are unaffected.
- `Contexts.newPlatform` / `InteractiveContextBase.newPlatform` select `SJSPlatform` again; the 12 `scalajs.value`/`isJSType`/`sjsNeedsField` sites in TypeErasure, SymUtils, Scala2Unpickler, Typer, Implicits, ImportSuggestions, Mixin, Constructors, Memoize are restored to upstream.
- The earlier note that `backend/sjs` needs `backend/jvm`/ASM types no longer holds for 3.9.0: the only compile errors were `ScopedVar` (`v.nn` under explicit nulls) and `JUnitBootstrappers` calling `sbt.APIUtils.registerDummyClass` (zinc-only, dropped).
- `build/00c-fetch-scalajs-ir.sh` generates `dotty.tools.sjs.ir` from the scalajs-ir sources (what vendor/scala3's sbt build does); `gen-file-list.sh` no longer excludes backend/sjs, transform/sjs, SJSPlatform and adds that directory; `versions.env` has `SCALAJS_VERSION=1.22.0`.

Verified on the JVM only (02a + 02b, then running the bootstrap classes): `-scalajs` on Hello emits `.sjsir` byte-identical to the published `scala3-compiler_3:3.9.0`, links with the 1.22.0 linker and runs on node. The compile classpath for JS must contain `dist/java.base.jar` besides the sjs jars: java.lang.Object etc. come from there, not from `jrt`. Not yet done: the Scala Native build of dotc (03) with these sources.

## Scala.js support, phase 2: the linker on Scala Native, `scalino-jslink` (2026-10-31)

`build/04c-fetch-jslink-sources.sh` + `build/04d-build-scalino-jslink.sh` + `src/JsLink.scala` build `dist/scalino-jslink` (25 MB, debug mode): the unmodified Scala.js 1.22.0 linker compiled by `scalino-dotc` to Scala Native and linked by `scalino-linkdriver` (about 4.5 minutes in debug mode). No JVM anywhere. CLI: `scalino-jslink --out DIR --main CLASS [--module-kind esmodule|commonjs|none] [--mode fast|full] [--no-source-maps] CLASSPATH_ENTRY...`.

What the Native build needed, each found by running it:
- **Compiles as plain Scala 3.9** (no `-source:3.0-migration`, which would reject our driver's braceless syntax); only `-language:implicitConversions`.
- **No `java.security.MessageDigest`** in javalib-native: scalajs-ir's `SHA1` replaced by a pure Scala one (`tools/jslink/SHA1.scala`).
- **Source changes in 04c**: Closure backend removed (`closureCompiler` always false), `ParIncOptimizer` uses `SeqCollOps`, `Path*` IO classes removed (the driver reads class path directories/jars into `MemIRFileImpl`s and writes the `MemOutputDirectory` itself).
- **Private library resources**: the emitter reads its polyfill `.sjsir` files with `getResourceAsStream(name)`. They live in the binary jar only, so 04c extracts them and the link uses `--embed-resources`; Native's `Class.getResourceAsStream` doesn't resolve relative names, hence an absolute-path patch to `PrivateLibHolder`. The embedded names carry the resource dir as given, so it must be an absolute path.
- **Executor**: `ExecutionContext.global` and the single-thread executor the analyzer creates for `withParallel(false)` hang on Native (both threads parked, nothing completes). Running tasks inline fails too (`ConcurrentHashMap.computeIfAbsent` "Recursive update" in the analyzer's class loader). The driver therefore uses a `RunLoop`: a queue drained by the main thread while it waits on the link future. 20/20 runs of a medium program were identical.
- The linker prints "Using the Scala.js Linker API on JDK 8" because javalib-native reports Java 8; the driver sets `java.version=17`.
- `scalino package` of the same sources hit a SIGSEGV in `scalanative_GC_alloc_array` inside `scalino-linkdriver` once (not seen in the 04d builds). A rebuild fixed it; unrelated to this work.

Verification (node 26): Hello and a program with futures, BigInt, regex, enums and `f""` run correctly in both `fast` (294 KB / 1.8 MB) and `full` (89 KB / 833 KB, optimizer + minify); the `fast` output is byte-identical to the JVM linker's (`ESModule`, no source maps), links take 0.7 s / 2.6 s. A missing main class and a missing JDK class (`java.time`) are reported as linking errors with the call chain, exit code 1.
Not done: incremental/long-running mode (every link is from scratch), `commonjs`/`none` module kinds untested, release-size build and PGO, packaging in dist tarballs/CI.

## Scala.js support, phase 3: the CLI (2026-10-31)

`//> using platform js` (or `--js`, `--platform js`) switches `run`, `package` and `compile` to Scala.js; the code is `buildJs`/`buildJsAndMaybeRun` in `cli/ScalinoCli.scala`.

- **Pipeline**: dependencies resolve with `jsBaseDeps` added (`scala3-library_sjs1_3`, `scalajs-library_2.13`, `scalajs-javalib`, the last two pinned to `SCALAJS_VERSION`) and `org::name::version` meaning `name_sjs1_3` (`jsPlatform` switches `toCoursierCoord`, the lock key and the deps-cache key; no native artifacts are excluded). dotc runs with `-scalajs` instead of the nscplugin, against the dependencies and `java.base.jar` only (no nativelibs, no scalalib-retained), into `.scalino-build/_scratch/classes-js` with the usual incremental manifest (`.sjsir` is purged with `.class/.tasty/.nir`). The entry point comes from `scalino-jslink --find-mains` (a static `main(Array[String])`, which is what `@main` and `object X { def main }` compile to). `scalino-jslink` links into `.scalino-build/<main>/js/`; `run` executes it with node (`$SCALINO_NODE` or `node` on PATH), `package -o app.js` copies the bundle and its source map (reference renamed).
- **Options**: `--js-mode dev|release` / `jsMode` (fast link, or optimizer + minifier), `--js-module-kind commonjs|esmodule|nomodule` / `jsModuleKind` (commonjs is scala-cli's default; esmodule is written as `.mjs` so node needs no package.json), `--js-source-maps` / `jsEmitSourceMaps`.
- **Program arguments are linked in**: Scala.js has no process arguments, so `run -- a b` passes them as `--main-arg`s, which become the `args` of the main module initializer. A `run` is therefore always a fresh link (0.7 s for hello). `package` links with no arguments.
- **Not supported yet** (clear error): `test`, `setup-ide`, `--format`. Watch mode relinks from scratch each time.

Verified with the JVM-hosted bootstrap build of dotc (patch 0039) standing in for `scalino-dotc`, the new CLI and the `04d` jslink, in a separate `dist-js-test/` (symlinks into `dist/`): hello, args, release + esmodule package (92 KB), commonjs package, an unchanged rebuild (1.5 s total), `object App { def main }` with `upickle` (macro derivation through the interpreter) and `cats-core` from `::` Scala.js dependencies, compile errors (rendered like native ones, exit 1), missing main, a missing JDK class (`java.time`: linking error with the call chain), missing node, bad module kind; and a native hello through the same CLI to check the native path is unchanged.

## Scala.js support, phase 4: `setup-ide`, the LSP and `scalino test` (2026-10-31)

**LSP.** `scalino setup-ide` on a JS project writes `"platform": "js"` and `-scalajs` in `compilerArguments`, with the Scala.js class path (`jsBaseDeps` + dependencies, `java.base.jar`). `scalino-lsp` needed no code change: `InteractiveDriver` builds `SJSPlatform` from `-scalajs` (patch 0039), it only had to be rebuilt with the sjs sources (`build/08`, 17 min). That build failed first on `java.security.MessageDigest` from dotc's own bundled `dotty.tools.sjs.ir` (SHA1); `build/00c-fetch-scalajs-ir.sh` now swaps in the pure Scala SHA-1 (`tools/jslink/SHA1.scala`, shaded), which the native `build/03` needs just the same. Smoke test: `build/lsp-js-smoke.py` against `build/lsp-js-fixture` (no diagnostics on valid code with js.Function1/js.UndefOr/js.|, an error is reported, hover on `js.UndefOr`); the existing native `lsp-trace-drive.py` still passes on every endpoint. The JS smoke cannot tell SJSPlatform from the plain platform (the previous LSP binary passes it too): Scala 3 accepts those programs either way, so that selection is only covered by the compiler check from phase 1.

**Tests.** `scalino test` on JS follows the native pipeline (`testJs` in `cli/ScalinoCli.scala`): `scalino-jslink --class-table` reads the class hierarchy from the IR, since `.sjsir`-only jars have no class files (kind, superclass, interfaces, "has a no-argument constructor", "declares a body-less method" standing in for abstract); frameworks are the public concrete `sbt.testing.Framework` implementors in it; a probe program (compiled with `-scalajs`, linked, run on node) reports the fingerprints; suites are matched with `matchTestClasses` (the matching loop shared with the native path, now taking a class lookup); the driver (`generateTestMainJs`) runs tasks through Scala.js's asynchronous `Task.execute(handler, loggers, continuation)` and sets `process.exitCode = 1` on failures. No `--test-only` link trimming (the linker keeps only what the driver reaches). Framework arguments after `--` are linked in as the driver's `args`.
- **One resolution**: main, test and framework dependencies are resolved together. Resolved apart, munit brought its own Scala 3.3 / 2.13.13 libraries next to 3.9.0 ("several versions of the Scala standard library"), and the duplicate classes would have linked first-wins.
- Verified with munit 1.0.2 (`_sjs1_3`) on node: passing, asynchronous (`Future`), failing (diff printed, exit 1) and `.ignore`d tests; `--test-only`. Not tried: utest, scalatest, zio-test on JS; JUnit4-style annotation discovery is unsupported as on native.
- Abstract test base classes are only recognised if they declare a body-less method (the IR has no abstract flag), same blind spot as the NIR path.

## Scala.js support, phase 5: client/server projects, shared sources (2026-10-31)

- **`//> using jsClient <dir>`** (in the Scala Native server): `<dir>` is a Scala.js project with its own `//> using` lines and entry point. `buildJsClient` builds it (platform flipped to js around it, so its `::` dependencies resolve to `_sjs1_3`), copies the bundle to `.scalino-build/_client/resources/client/main.js` (+ `.map`, reference renamed) and puts that directory on the server's class path with `--embed-resources` forced on, so the server reads it with `getClass.getResourceAsStream("/client/main.js")`. The client is linked as a classic script (`nomodule`, what a `<script>` tag wants) unless `jsModuleKind` says otherwise, in dev mode for `run` and release mode for `package` (unless `--js-mode`/`jsMode` says otherwise). One client per project; `compile` skips it. The client directory is excluded from the server's own sources automatically (`scalino run .` from the root works), and `-w` watches it.
- **`//> using shared <path>`** (files or directories): sources compiled into the declaring project, and, when it is a server with a `jsClient`, into the client too; a client can declare its own as well. No platform split is needed for plain Scala. `./shared/A.scala` from a directory scan and the absolute path a directive yields are the same file, so source lists are now de-duplicated by normalized absolute path (this also fixes `file` overlapping a scanned directory).
- **Paths** in `jsClient`/`shared` are relative to the declaring file, and, when nothing exists there, to the directory scalino runs in: `client`, `./client` and `../client` all work.
- **`setup-ide`** emits a second project `client` (platform js, `-scalajs`, its own class path, sources = client + shared) next to `main`.
- Verified with `examples/client-server` (server: a `ServerSocket` HTTP server in Scala Native; client: Scala.js writing into the DOM; shared: `Greeting`): `run` serves the page and `/client/main.js`, the fetched bundle executed on node against a stub `document` prints the shared greeting; `package` gives a native binary with an 82 KB release bundle that serves the same; bare-name directive layouts; `compile`. Not tested: `-w` rebuilds of a client change, several clients.
- `scalino test` builds the `jsClient` first (dev mode unless `--js-mode`/`jsMode` says otherwise), puts its output directory on the test class path and forces `--embed-resources`, so a test can read `/client/main.js`. A project without a `jsClient` builds as before. Covered by `tests/js/run.sh`; on `data-centric` 7 `missing resource client/main.js` failures went away (268 -> 274 passed).

## Scala.js options checked against scala-cli 1.17.1 (2026-10-31)

Compared with `scala-cli run --help-js`/`--help-full` and the directives reference. Real scala-cli's defaults, checked by running `scala-cli package --js`: a plain script (no module wrapper, i.e. `nomodule`) and no source map. Mine were commonjs with source maps; both changed. Now matching: `--js`, `--native`, `--platform scala-js|scala-native` (`js`/`native` accepted; `jvm` is a clear error), `--js-mode` (`dev|fast|fastLinkJS`, `release|full|fullLinkJS`), `--js-module-kind` (`commonjs|common`, `esmodule|es`, `nomodule|none`), `--js-emit-source-maps` (renamed from my `--js-source-maps`), `--js-no-opt`, `--js-check-ir`, `--js-header`, `--js-es-version`, `--js-avoid-classes`, `--js-avoid-lets-and-consts`, `--js-allow-big-ints-for-longs`, `--js-runtime node|deno|bun`; directives `platform`/`platforms`, `target.platform`, `jsMode`, `jsModuleKind`, `jsEmitSourceMaps`, `jsNoOpt`, `jsCheckIr`, `jsHeader`, `jsEsVersionStr`, `jsAvoidClasses`, `jsAvoidLetsAndConsts`, `jsAllowBigIntsForLongs` (bare form = true). `--js-version` is refused like `--native-version`; `--js-dom`, `--js-emit-wasm`, `--js-module-split-style`, `--js-small-module-for-package`, `--js-es-module-import-map`, `--js-source-maps-path` are refused ("not supported"); the same directives fall into the existing "Unsupported directive" warning. Scalino-only: `jsClient`, `shared` (scala-cli has no equivalent; its cross-platform story is `target.platform` plus `--cross`). Multiple `platforms` values: the first wins (no `--cross`).

## Scala.js support, phase 6: native toolchain, packaging, CI, offline, watch (2026-10-31)

**Native toolchain built and verified.** `build/03` (19.5 min) now links the sjs backend into `scalino-dotc`; `build/07` and `04d` then built the CLI and `scalino-jslink` against it. Two link failures on the way, both missing JDK API in javalib-native, both from sources the earlier JVM-hosted checks could not catch: dotc's bundled `dotty.tools.sjs.ir` used `java.security.MessageDigest` (fixed in `00c`, see phase 4) and `JSCodeGen` used `java.util.Locale.ENGLISH` (patch 0039 now has a locale-free `toLowerCase`; class names are ASCII). A first `build/03` attempt also died on a corrupted directory-entry name read by the native linkdriver (`%ghlight.nir` for `Highlight.nir`, the files were fine), the same flaky linkdriver as the earlier SIGSEGV; the retry linked. `tests/js/run.sh` passes against the real `dist/` (49 s), `build/08b` passes (native and JS LSP smoke), native `examples/Hello.scala` still runs. The patch files alone reproduce the vendor tree I built from (checked in a throwaway worktree).

**Build and CI wiring.** `build/all.sh`: `00c` runs in core before `02a` (the file list needs the shaded IR sources, and the state artifact that hands `.build-work` to the dotc/lsp jobs carries them), `04d` in the dotc stage after `07`. `ci.yml` and `release.yml` run `tests/js/run.sh` after the plain smoke test (continue-on-error in the release job, like the macro canary); `08b-smoke-lsp.sh` also runs `build/lsp-js-smoke.py`. `scalino-jslink` is in `dist/`, so the tarball and deb/rpm (`cp -a dist/.`) already contain it; deb/rpm suggest `nodejs`, Arch has it as optdepends, Homebrew as an optional dependency, the Nix wrapper puts `nodejs` on PATH. `04d` links release-size outside debug builds; no PGO profile for jslink.

**LSP.** `build/lsp-js-smoke.py` now distinguishes the Scala.js platform from the plain one: `Union.scala` has `(1: Int | Int).merge`, which only errors when `-scalajs` makes `js.|` a plain union (the pre-sjs binary fails this test, the new one passes). It also checks go-to-definition: library code with TASTy (`Option`) resolves into the sources jar; classes from the Scala 2-compiled scalajs-library (`js.Promise`, `js.Function1`, `js.UndefOr`) return nothing, they have no source positions. Editor extensions with the `client` project are untested.

**Offline and hermetic.** `build/06` vendors the Scala.js base libraries (`scala3-library_sjs1_3`, scalajs-library, javalib, scalac-compat-annotation and their dependencies) into `dist/lib` with `dist/jslibs.cp`; `resolveJsDeps` uses it when a build needs nothing else (a lockfile still wins), so a plain Scala.js program builds with no network. `scalino lock` knows the Scala.js sets (base libraries + dependencies, test dependencies as one set, a `jsClient`'s own) and records them under `platform=js` keys; an offline build from that lockfile is in `tests/js/run.sh`. The Nix recipe's JS side is untested (no Nix here).

**Watch mode.** `scalino-jslink --long-running` keeps the linker, its analysis/optimizer caches and the IR file cache alive (files keyed by mtime/size, jar entries kept in memory), links once, then once per stdin line, with the `SCALINO_LINKING_DONE/FAILED` protocol of the native linkdriver. `-w` uses it (`linkJsIncremental`), respawning when the argv changes. A fully optimized link of the test program: 4.3 s first, 1.25 s on relinks. Verified: editing a JS program re-runs it in 1.2 s; editing a `jsClient` or a `shared` source rebuilds the client and the server (the persistent process was alive during the run).

## `run -w` restarts the program (2026-10-31)

`run -w` used to block in `runInherited` until the program exited, so a server never let the watcher rebuild. Under `-w` the program now runs in the background (`runManaged`): sources are rebuilt while it keeps running, and once a build has succeeded the old process is stopped (SIGTERM, SIGKILL after 3 s) and the new one started ("Restarting the application"); a failed build leaves the running program alone, like scala-cli's `--restart`. Native and Scala.js, a shutdown hook stops the program when scalino is killed. Checked by hand for both platforms (restart, one process afterwards, nothing left after SIGTERM of scalino) and in `tests/js/run.sh` (restart on edit, no restart on a failed build). `test -w` is unchanged: tests run to completion. Press Enter still forces a rebuild and restart.

## Escape analysis: collapsed caller memory never escaped (wes `Envelope`, 2026-11-03)

The NES emulator wes produced distorted audio in `release-fast` and segfaulted in `release-full`. Bisected with `SCALANATIVE_STACK_HASH` to one site, `wes.nes.apu.Envelope` in `State$PulseRegisters.$init$` (a `val envelope = new Envelope` in a sealed trait, stored through the synthetic setter on `this`); `SCALANATIVE_STACK_OBJECTS=0` or `_EXCLUDE='wes\.nes\.apu\.Envelope'` fixed it.

**Root cause** (`EscapeAnalysis.finalizeFlags`, patch 0005). A call whose callee stores parameter *j* into the memory of parameter *i* has `group(i) == group(j)` in its summary, which the caller applies as `union(arg_i, arg_j); collapse(arg_i)`. If `arg_i` is the caller's own parameter the merged node carries `ExtRoot`, and `ExtRoot` only becomes `Ext` (the flag that makes a candidate escape) by propagating to the node's *content* nodes. A collapsed node is its own content and, when no content node was ever created (`fmap == null`), there was nothing to propagate to: trace showed the candidate with flags `Collapsed|NonParam|ExtRoot` (88, 1112 with `Recv`) and no `Ext`. The hypothesis in the report (setter summarized as a no-op / abstract setters) was wrong: the setter summaries were fine (`group = (0,0)`), the caller-side bookkeeping dropped them. In 0.0.27 the shape came from polymorphic inlining of `$init$` (setter called on a cast of `this`, resolved to the anonymous classes' setters); on the rebased tree the same flags appear for any non-inlined store into a parameter, so the minimal reproduction does not need traits.

**Fix.** A `Collapsed` node with `ExtRoot` or `Ext` gets `Ext` (conservative: contents of a collapsed node are the node). Mutant `SCALANATIVE_EA_X=mutate-collapsedext` removes it.

**Validation.** `tests/escape-analysis` (links on/off/poison/mutant). wes harness, 8000 ms of flappy.nes against the JVM reference `jvm.raw`, dev toolchain: fixed `release-fast` and `release-fast` + poison: `cmp` identical; mutant `release-fast`: differs at byte 12361; fixed `release-full`: identical; mutant `release-full`: abort (exit 134). Allocation reduction: unchanged, `Allocated 8792 objects on the stack` linking dotc both with and without the fix (static count only; the runtime allocation profile was not re-run, the set of stack sites being identical).

**Why poison alone did not show it at first:** LLVM inlines a single-call-site internal function, and a tail-recursive one becomes a loop, so the stack object ended up in a frame that never died; the test makes `init` non-tail-recursive and marks the store `@noinline` so the allocating frame is real.

**Separate change: link cache key** (`Build.checkSum`, patch 0008). Changing `SCALANATIVE_STACK_OBJECTS` / `_HASH` / `_EXCLUDE` / ... between runs gave "Build skipped: No changes detected" because the checksum covered only the config, class path mtimes and the artifact. It now includes the link-time variables that change generated code (`STACK_OBJECTS/INCLUDE/EXCLUDE/HASH/POISON`, `INLINE_ALLOC`, `SITE_PROFILE`, `PGO_HOT_FACTOR`, `EA_X`); diagnostic-only ones (`*_VERBOSE`, `*_TRACE`, `*_STATS`, ...) are left out so they do not force relinks. Checked on the JVM link driver: same env skips, a changed `STACK_OBJECTS` relinks, `STACK_VERBOSE` alone does not.
