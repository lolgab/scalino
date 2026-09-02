# scala-native-compiler

A self-contained Scala Native compiler toolchain: no JVM required to run it
(a JVM is only needed once, at build time, to run GraalVM's native-image).
Scala 3 only.

## Build

Requires: GraalVM JDK 21+ (with `native-image`), `clang`, `coursier` (`cs`),
`git`.

```
./build/all.sh
```

This clones `scala/scala3` and `scala-native/scala-native` into `vendor/`
(pinned versions, see `versions.env`), applies our patches from `patches/`,
and produces `dist/dotc-native` (standalone compiler + scala-native plugin,
our patched macro interpreter baked in), `dist/linkdriver-native` (standalone
NIR→native linker), and `dist/scli` (see below) -- plus the classpath
manifests `bin/snc`/`scli` need.

## Use

The easy way — `scli`, a mini scala-cli-style build tool (see
`cli/Scli.scala`), itself compiled by this toolchain, not by a JVM:

```
./dist/scli run examples/Hello.scala
./dist/scli run examples/macro-hello/Test.scala examples/macro-hello/Foo.scala   # a real macro
./dist/scli compile examples/Hello.scala -o hello && ./hello
```

It auto-detects the entry point (`@main`, `extends App`, or `def main`), so
source file order doesn't matter, and understands `//> using dep
"org::name:version"` / `//> using scala "x"` directives — dependency
resolution shells out to `cs` (coursier's own launcher is itself a prebuilt
GraalVM native-image binary, so this costs no JVM either), and both the
resolved classpath and the compiled/linked output are cached in
`.scli-build/`. Only libraries actually cross-published for scala-native
will *link* successfully (JVM-only jars resolve and typecheck fine, but have
no native code to call into) — see `docs/findings.md`.

The lower-level way — `bin/snc`, a plain bash wrapper (what `scli` itself
was bootstrapped from, and what `scli`'s own build script still uses):

```
./bin/snc build examples/Hello.scala -o hello
./hello
```

Neither `dotc-native`, `linkdriver-native`, `scli`, nor `snc` invoke a JVM.

## Status

Core toolchain (compile Scala 3 → NIR → native executable, fully JVM-free) is
proven working end to end, **including real inline/quote macros**: macro
expansion runs through our own from-scratch TASTy-tree interpreter (patched
into dotc, see `patches/`) instead of dotc's normal JVM-bytecode-execution
path, and it's exercised against real macro tests harvested from upstream's
own test suite (`interpreter/test-fixtures/`, sourced from
`vendor/scala3/tests/{run-macros,pos-macros}`). Not every macro shape is
supported yet — general quote-pattern matching (`case '{ ... } => `) is the
main known gap. See [`docs/findings.md`](docs/findings.md) for the full
verified/blocked/remaining breakdown.
