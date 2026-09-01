# Findings from the native-image spike

Background for the comments in `build/*.sh`. Scala 3 only, GraalVM
native-image approach (see decision log below).

## Decisions made

- **Packaging strategy: GraalVM native-image**, not full self-hosting (compiling
  dotc itself down to Scala Native). Self-hosting was ruled out as multi-year/
  high-risk given dotty's deep ties to the JVM (zinc, java.nio, reflection).
  native-image only needs a JVM at *build* time, never at runtime.
- Macros that currently run compiled JVM bytecode at compile time are meant to
  be re-routed through **tasty-interpreter** instead. Not yet started — see
  "Remaining work" below.

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

## Remaining work

1. **Macros via tasty-interpreter.** Nothing done yet. Scala 3 inline/quote
   macros that currently execute compiled JVM bytecode at compile time need
   to run through tasty-interpreter instead so the whole pipeline stays
   JVM-free. This is the big remaining unknown — tasty-interpreter's coverage
   of real-world macros (whitebox macros, macros calling into arbitrary
   stdlib/reflection) is unclear and needs its own spike.
2. **Packaging/relocatability.** `dist/*.cp` manifests (used by `bin/snc`)
   currently hold absolute paths into the local coursier cache
   (`~/Library/Caches/Coursier/...`). This works on the machine that built it
   but isn't yet a distributable, self-contained tarball. Follow-up: vendor
   the referenced jars into `dist/lib/` and rewrite the manifests to relative
   paths.
3. **Patch/vendor mechanism.** Original ask was a repo that pulls and patches
   `scala/scala3` and `scala-native/scala-native` sources directly (git
   submodules + patch files), rather than consuming published Maven
   artifacts as this spike does. Artifacts were used here to validate the
   core approach fast; switching to source + patches is needed once actual
   compiler modifications are required (e.g. for tasty-interpreter
   integration, which will likely need to hook into dotc internals that
   aren't exposed as compiler flags).
4. Only tested on macOS/arm64 with a trivial no-macro, no-stdlib-heavy
   program. Linux/other-arch, and anything using more of the stdlib, is
   unverified.
