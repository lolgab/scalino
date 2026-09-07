# scalino

[![CI](https://github.com/lolgab/scalino/actions/workflows/ci.yml/badge.svg)](https://github.com/lolgab/scalino/actions/workflows/ci.yml)

Write, build, and run Scala 3 without installing a JVM. `scalino` compiles
straight to a native executable via Scala Native — no bytecode, no JIT, no
`java` on your machine at all.

The compiler and LSP server are themselves self-hosted: compiled by dotc from
their own patched source, targeting Scala Native directly, so nothing you run
day to day touches a JVM. The one holdout is `scalino-linkdriver` (the
NIR→native linker), still built with GraalVM native-image — see
[`docs/findings.md`](docs/findings.md) for why.

Today: compiler + linker + build tool (`scalino`). Next: full editor support —
see [Status](#status).

## Install

```
curl -fsSL https://raw.githubusercontent.com/lolgab/scalino/main/install.sh | bash
```

Grabs the latest [release](https://github.com/lolgab/scalino/releases) for
your OS/arch, verifies its checksum, and installs `scalino` to
`~/.local/bin` (override with `$SCALINO_INSTALL_DIR`/`$SCALINO_BIN_DIR`/`$SCALINO_VERSION`).

Prefer to do it by hand? Each release ships a self-contained `dist/` tarball
(compiler + linker + `scalino`) for Linux (x86_64/arm64), macOS
(x86_64/arm64), and Windows (x86_64 only, experimental — see
[`docs/findings.md`](docs/findings.md)). Download, extract, run
`scalino`/`dist/scalino`.

You'll still need two small pre-existing binaries on the machine — neither is
a JVM:
- **`clang`/`clang++`** — every build links through clang, even a
  zero-dependency Hello World. Already on macOS via Xcode Command Line
  Tools; `apt install clang` etc. elsewhere.
- **[`cs`](https://get-coursier.io/)** — only needed if your project has
  dependencies (`//> using dep`, `scalino setup-ide`).

### Linux package managers

Every release also publishes `.deb`/`.rpm` packages, with `clang` wired in as
a real dependency:

```
# Debian/Ubuntu
curl -fsSLO https://github.com/lolgab/scalino/releases/download/vX.Y.Z/scalino_X.Y.Z_amd64.deb
sudo apt install ./scalino_X.Y.Z_amd64.deb

# Fedora/RHEL/openSUSE
sudo dnf install https://github.com/lolgab/scalino/releases/download/vX.Y.Z/scalino-X.Y.Z-1.x86_64.rpm
```

Both install to `/usr/lib/scalino/` with symlinks in `/usr/bin/`.

Also available, though not yet published to their communities — see each
file for what's still manual:
[Arch (AUR)](packaging/arch/PKGBUILD),
[Nix flake](packaging/nix/flake.nix),
[Homebrew formula](packaging/homebrew/scalino.rb).

## Use

`scalino` is a mini scala-cli, shaped like the real
[scala-cli](https://scala-cli.virtuslab.org/) on purpose:

```
scalino examples/Hello.scala                                                # run is the default command
scalino run examples/macro-hello/Test.scala examples/macro-hello/Foo.scala  # a real macro
scalino run examples/ --main-class Hello                                    # a whole directory
scalino run examples/Hello.scala -w                                         # watch mode
scalino compile examples/Hello.scala -o hello && ./hello
```

It finds your entry point automatically (`@main`, `extends App`, `def main`),
so file order doesn't matter. It understands the usual
`//> using <key> "value"` directives — `dep`/`deps`, `scala`, `mainClass`,
`options` — and the equivalent flags: `--dep`, `-S/--scala`,
`-O/--scalac-option`, `--main-class`, `-w/--watch`, `-o/--output`, and
`-- <args...>`. Run `scalino --help` for the full list.

Dependency resolution shells out to `cs`; resolved classpaths and build
output are cached in `.scalino-build/`. Only libraries actually
cross-published for scala-native will link — JVM-only jars resolve and
typecheck but have no native code to call into (see
[`docs/findings.md`](docs/findings.md)).

Unlike scala-cli, `scalino` only targets the one Scala/Scala Native version
it was built for — no per-project version switching, no JVM/Scala.js
targets — and commands it doesn't implement (`test`, `fmt`, `repl`,
`package`, `publish`, `bsp`, `export`) print a clear "not implemented"
instead of guessing.

There's also `bin/scalino-bootstrap`, the plain bash wrapper `scalino` itself
was bootstrapped from:

```
./bin/scalino-bootstrap build examples/Hello.scala -o hello
./hello
```

## Editor support

`dist/scalino-lsp` gives editors like Zed Scala diagnostics, hover,
go-to-definition, references, and rename — without Metals' JVM dependency.
Run `scalino setup-ide <sources...>` to generate its project config
(`.scalino-build/scalino-lsp.json`).

For Zed specifically, [`zed-extension/`](zed-extension/) wires it up as a
real extension, published to Zed's gallery as "Scalino LSP" — see that
directory's README for install steps.

## Status

The core toolchain — Scala 3 → NIR → native executable — works end to end,
including real inline/quote macros: macro expansion runs through a
from-scratch TASTy-tree interpreter instead of dotc's normal
bytecode-execution path, tested against real macro fixtures harvested from
upstream's own test suite. Not every macro shape is supported yet — general
quote-pattern matching (`case '{ ... } => `) is the main known gap. Full
verified/blocked/remaining breakdown in [`docs/findings.md`](docs/findings.md).

The LSP server works too, and as of 2026-09-07 is self-hosted the same way
as the compiler — no GraalVM native-image anywhere in the binary. Verified
against a real native binary on both a hand-written project and a real
multi-package third-party project, and end-to-end in actual Zed.

## Build from source

Requires GraalVM JDK 21+ (with `native-image`), `sbt`, `clang`, `coursier`
(`cs`), `git`.

```
./build/all.sh
```

Clones `scala/scala3` and `scala-native/scala-native` into `vendor/` (pinned
versions in `versions.env`), applies patches from `patches/`, and produces
`dist/scalino-dotc`, `dist/scalino-lsp`, `dist/scalino-linkdriver`, and
`dist/scalino`.

## Releasing

Maintainers: push a `vX.Y.Z` tag and the
[release workflow](.github/workflows/release.yml) builds `dist/` for every
supported platform/arch and publishes a GitHub Release. A platform that
fails to build doesn't block the others.

```
git tag vX.Y.Z
git push origin vX.Y.Z
```

Once assets are published, refresh third-party packaging metadata and commit
the result:

```
./build/10-update-package-metadata.sh vX.Y.Z
```
