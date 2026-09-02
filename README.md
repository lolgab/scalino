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

This clones `scala/scala3` into `vendor/scala3` (pinned tag, see
`versions.env`), applies our patches from `patches/`, and produces
`dist/dotc-native` (standalone compiler + scala-native plugin, our patched
macro interpreter baked in) and `dist/linkdriver-native` (standalone
NIR→native linker), plus the classpath manifests `bin/snc` needs.

## Use

```
./bin/snc build examples/Hello.scala -o hello
./hello

./bin/snc build examples/macro-hello/Test.scala examples/macro-hello/Foo.scala -o macro-hello
./macro-hello   # a real inline/quote macro, expanded with no JVM involved
```

Neither `dotc-native`, `linkdriver-native`, nor `snc` invoke a JVM.

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
