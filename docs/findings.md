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

## Remaining work

1. **General quote-pattern matching.** The biggest real gap — see
   `i7715`/`i8746` above. Needed for a large class of real-world macros
   (typeclass derivation, anything using `case '{ ... } => ` matching).
2. **Full sbt source build.** Current patching approach (recompile one file,
   splice into the published jar) only works for single-file, dependency-only
   patches. Anything touching multiple compiler files, or needing non-public
   API, needs the real dotty sbt bootstrap build wired up instead.
3. **Packaging/relocatability.** `dist/*.cp` manifests (used by `bin/snc`)
   currently hold absolute paths into the local coursier cache
   (`~/Library/Caches/Coursier/...`). This works on the machine that built it
   but isn't yet a distributable, self-contained tarball. Follow-up: vendor
   the referenced jars into `dist/lib/` and rewrite the manifests to relative
   paths.
4. **`scala-native/scala-native` patching.** Only `scala/scala3` has needed a
   patch so far; scala-native's published artifacts have worked as-is. The
   `vendor`/`patches` mechanism generalizes to it if that changes.
5. Only tested on macOS/arm64. Linux/other-arch is unverified.
6. `bin/snc`'s "first source file's basename is the main class" convention is
   naive — for multi-file macro examples the entry-point file must be listed
   first (see `examples/macro-hello/` usage in the README). A real CLI would
   detect the actual `@main`/`extends App` entry point instead.
