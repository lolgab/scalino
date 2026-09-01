# scala-native-compiler

A self-contained Scala Native compiler toolchain: no JVM required to run it
(a JVM is only needed once, at build time, to run GraalVM's native-image).
Scala 3 only.

## Build

Requires: GraalVM JDK 21+ (with `native-image`), `clang`, `coursier` (`cs`).

```
./build/all.sh
```

Produces `dist/dotc-native` (standalone compiler + scala-native plugin) and
`dist/linkdriver-native` (standalone NIR→native linker), plus the classpath
manifests `bin/snc` needs.

## Use

```
./bin/snc build examples/Hello.scala -o hello
./hello
```

Neither `dotc-native`, `linkdriver-native`, nor `snc` invoke a JVM.

## Status

Core toolchain (compile Scala 3 → NIR → native executable, fully JVM-free) is
proven working end to end for macro-free programs. Macro execution via
tasty-interpreter (replacing the JVM-bytecode execution real dotc uses for
compile-time macro expansion) is not yet implemented. See
[`docs/findings.md`](docs/findings.md) for what's been verified, the
blockers hit and how they were fixed, and what's left.
