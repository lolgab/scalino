# scala-native-compiler

[![CI](https://github.com/lolgab/snc/actions/workflows/ci.yml/badge.svg)](https://github.com/lolgab/snc/actions/workflows/ci.yml)

Goal: a complete Scala 3 toolchain that never needs a JVM installed, at any
step -- compile, link, build, and (in progress) IDE tooling. A JVM is only
ever needed once, transiently, at *this project's own* build time, to run
GraalVM's native-image; nothing it produces touches a JVM again. People
should be able to write, build, and run Scala without installing Java.

Today this covers the compiler + linker + build tool (`scli`). Next up: a
JVM-free language server (LSP), so editors like Zed can get Scala IntelliSense
without Metals' JVM dependency -- see [Status](#status).

## Prebuilt binaries

```
curl -fsSL https://raw.githubusercontent.com/lolgab/snc/main/install.sh | bash
```

Downloads the latest [release](https://github.com/lolgab/snc/releases) for
your OS/arch, verifies its checksum, and installs `scli` to `~/.local/bin`
(override with `$SNC_INSTALL_DIR`/`$SNC_BIN_DIR`/`$SNC_VERSION`). Or do it by
hand: each release ships a self-contained `dist/` tarball (compiler + linker
+ `scli`, no JVM needed to run any of it) for Linux, macOS, and Windows, on
both x86_64 and arm64 -- download it, extract it, and use `scli`/`dist/scli`
as described below. Windows support is experimental/best-effort -- see
[`docs/findings.md`](docs/findings.md).

Either way, two things are still required on the machine you *run* this on
(not bundled -- both are pre-existing standalone binaries, not a JVM, so
they don't compromise the "no JVM to write Scala" goal, but they're real
prerequisites a fresh machine may not have):
- **`clang`/`clang++`** -- needed for every build, even a zero-dependency
  Hello World, since scala-native always links through clang. Usually
  already present on macOS (Xcode Command Line Tools) and installable via
  the system package manager elsewhere (`apt install clang`, etc).
- **`cs`** ([coursier](https://get-coursier.io/)'s own launcher, itself a
  prebuilt native binary, not a JVM) -- only needed if you use
  `//> using dep`/`--dep` or `scli setup-ide` on a project with dependencies.

## Build from source

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

The easy way — `scli`, a mini scala-cli, self-hosted (see `cli/Scli.scala`),
itself compiled by this toolchain, not by a JVM. Its CLI is deliberately
shaped like [scala-cli](https://scala-cli.virtuslab.org/)'s:

```
./dist/scli examples/Hello.scala                                                # `run` is the default command
./dist/scli run examples/macro-hello/Test.scala examples/macro-hello/Foo.scala  # a real macro
./dist/scli run examples/ --main-class Hello                                    # a directory: every .scala file under it
./dist/scli run examples/Hello.scala -w                                         # watch mode: rebuild+rerun on change
./dist/scli compile examples/Hello.scala -o hello && ./hello
```

It auto-detects the entry point (`@main`, `extends App`, or `def main`), so
source file order doesn't matter, and understands the common
`//> using <key> "value"` directives: `dep`/`deps` (dependency coordinates,
in any of scala-cli's `org:name:version` / `org::name:version` /
`org::name::version` forms), `scala` (declares a Scala version — must match
this toolchain's, see below), `mainClass`, and `options`/`option` (extra
compiler flags). The same things are available as flags: `--dep` (no `-d`
short form -- real scala-cli's `-d` means `--output`, not `--dependency`),
`-S/--scala`, `-O/--scalac-option`, `--main-class`, `-w/--watch`,
`-o/--output`, and `-- <args...>` for the program's own arguments. Run
`scli --help` for the full list. Dependency resolution shells out to `cs`
(coursier's own launcher is itself a prebuilt GraalVM native-image binary,
so this costs no JVM either), and both the resolved classpath and the
compiled/linked output are cached in `.scli-build/`. Only libraries actually
cross-published for scala-native will *link* successfully (JVM-only jars
resolve and typecheck fine, but have no native code to call into) — see
`docs/findings.md`.

Unlike scala-cli, this toolchain only ever targets the one pinned
Scala/scala-native version it was built for (no per-project version
switching, no JVM/Scala.js platforms), and doesn't implement scala-cli's
`test`/`fmt`/`repl`/`package`/`publish`/`bsp`/`export` commands — running
any of those prints a clear "not implemented" instead of guessing.

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

**Also proven working, fully JVM-free: an LSP server** (`dist/dotty-lsp-native`,
`build/08-build-lsp-native.sh`) for editors like Zed, so Scala gets
diagnostics/hover without Metals' JVM dependency. Built by trimming and
patching dotty's own pre-Metals `language-server/` module (see
`docs/findings.md` "JVM-free language server (LSP)" for the three real bugs
found and fixed along the way). Verified against a real native binary:
correct diagnostics and real Scaladoc-sourced hover for a hand-written
project config. `dist/scli setup-ide <sources...>` generates that project
config (`.dotty-ide.json`) instead of hand-writing it, and
[`zed-extension/`](zed-extension/) wires the server into Zed as a real
extension (`README.md` there for install steps) — untested end-to-end
against the real editor as of this writing.

## Releasing

Maintainers: push a `vX.Y.Z` tag and the [release workflow](.github/workflows/release.yml)
builds `dist/` on every supported platform/arch and publishes a GitHub Release
with one tarball per target. A platform that fails to build doesn't block the
others — check the workflow run for which targets actually shipped.

```
git tag vX.Y.Z
git push origin vX.Y.Z
```
