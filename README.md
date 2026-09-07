# scalino

[![CI](https://github.com/lolgab/scalino/actions/workflows/ci.yml/badge.svg)](https://github.com/lolgab/scalino/actions/workflows/ci.yml)

Goal: a complete Scala 3 toolchain that never needs a JVM installed, at any
step -- compile, link, build, and IDE tooling. A JVM is only ever needed
transiently, at *this project's own* build time, as a bootstrap compiler
(`scalino-dotc`/`scalino-lsp` are self-hosted -- compiled by dotc from their
own patched source, targeting Scala Native directly, no native-image AOT
step involved) and, for the one remaining GraalVM-native-image binary
(`scalino-linkdriver`, the NIR→native linker -- see
[`docs/findings.md`](docs/findings.md) for why that one hasn't been
self-hosted yet, and what a from-source self-hosting attempt found). Nothing
any of this produces touches a JVM again. People should be able to write,
build, and run Scala without installing Java.

Today this covers the compiler + linker + build tool (`scalino`). Next up: a
JVM-free language server (LSP), so editors like Zed can get Scala IntelliSense
without Metals' JVM dependency -- see [Status](#status).

## Prebuilt binaries

```
curl -fsSL https://raw.githubusercontent.com/lolgab/scalino/main/install.sh | bash
```

Downloads the latest [release](https://github.com/lolgab/scalino/releases) for
your OS/arch, verifies its checksum, and installs `scalino` to `~/.local/bin`
(override with `$SCALINO_INSTALL_DIR`/`$SCALINO_BIN_DIR`/`$SCALINO_VERSION`). Or do it by
hand: each release ships a self-contained `dist/` tarball (compiler + linker
+ `scalino`, no JVM needed to run any of it) for Linux, macOS, and Windows, on
both x86_64 and arm64 -- download it, extract it, and use `scalino`/`dist/scalino`
as described below. Windows support is experimental/best-effort -- see
[`docs/findings.md`](docs/findings.md).

### Linux package managers

Every tagged release also publishes `.deb`/`.rpm` assets (built via
[`fpm`](https://github.com/jordansissel/fpm), see
`build/09-package-linux-native.sh`) alongside the tarballs -- `clang` is
declared as a real package dependency, so it's pulled in automatically:

```
# Debian/Ubuntu
curl -fsSLO https://github.com/lolgab/scalino/releases/download/vX.Y.Z/scalino_X.Y.Z_amd64.deb
sudo apt install ./scalino_X.Y.Z_amd64.deb

# Fedora/RHEL/openSUSE
sudo dnf install https://github.com/lolgab/scalino/releases/download/vX.Y.Z/scalino-X.Y.Z-1.x86_64.rpm
```

Both install `scalino`/`scalino-lsp` under `/usr/lib/scalino/` with symlinks
in `/usr/bin/`, same relocatable layout as the tarball.

There's also a real, GPG-signed apt/dnf repo (`.github/workflows/publish-repo.yml`
+ `build/11-build-apt-dnf-repo.sh`) so `apt install`/`dnf install` work
without a manual download, once
[GitHub Pages is enabled](https://github.com/lolgab/scalino/settings/pages)
for this repo (blocked while it's private -- GitHub's free plan doesn't
support Pages on private repos) -- see that workflow's own header comment.
The signing key (`packaging/apt-dnf-repo/scalino-signing-key.pub.asc`) is
already generated and stored as the `APT_GPG_PRIVATE_KEY` repo secret.

Also in the repo, versioned by `build/10-update-package-metadata.sh` after
each release (not yet published to their respective communities -- these are
ready to submit, see each file's own header comment for what's still
manual):
- [`packaging/arch/PKGBUILD`](packaging/arch/PKGBUILD) -- `scalino-bin` for
  the AUR, wraps the linux-x86_64/linux-arm64 tarball.
- [`packaging/nix/flake.nix`](packaging/nix/flake.nix) -- `nix run
  github:lolgab/scalino?dir=packaging/nix`, patches the release binaries'
  ELF interpreter/rpath via `autoPatchelfHook` instead of rebuilding from
  source.
- [`packaging/homebrew/scalino.rb`](packaging/homebrew/scalino.rb) -- for a
  future `lolgab/homebrew-scalino` tap (not homebrew-core -- that requires
  building from source, which needs this project's own transient
  GraalVM/native-image build step).

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
  `//> using dep`/`--dep` or `scalino setup-ide` on a project with dependencies.

## Build from source

Requires: GraalVM JDK 21+ (with `native-image` -- still needed to build
`scalino-linkdriver`, and used as the bootstrap JVM that compiles
`scalino-dotc`/`scalino-lsp` from source), `sbt` (builds a small javalib
patch via scala-native's own build -- see `build/01b-build-patched-javalib.sh`),
`clang`, `coursier` (`cs`), `git`.

```
./build/all.sh
```

This clones `scala/scala3` and `scala-native/scala-native` into `vendor/`
(pinned versions, see `versions.env`), applies our patches from `patches/`,
and produces `dist/scalino-dotc` (self-hosted: dotc + scala-native's compiler
plugin + our patched macro interpreter, all compiled to NIR and linked into a
real Scala Native executable -- no JVM/GraalVM native-image in the binary
itself), `dist/scalino-lsp` (self-hosted the same way), `dist/scalino-linkdriver`
(standalone NIR→native linker -- still a GraalVM-native-image build, see
[`docs/findings.md`](docs/findings.md)), and `dist/scalino` (see below) --
plus the classpath manifests `bin/scalino-bootstrap`/`scalino` need.

## Use

The easy way — `scalino`, a mini scala-cli, self-hosted (see `cli/ScalinoCli.scala`),
itself compiled by this toolchain, not by a JVM. Its CLI is deliberately
shaped like [scala-cli](https://scala-cli.virtuslab.org/)'s:

```
./dist/scalino examples/Hello.scala                                                # `run` is the default command
./dist/scalino run examples/macro-hello/Test.scala examples/macro-hello/Foo.scala  # a real macro
./dist/scalino run examples/ --main-class Hello                                    # a directory: every .scala file under it
./dist/scalino run examples/Hello.scala -w                                         # watch mode: rebuild+rerun on change
./dist/scalino compile examples/Hello.scala -o hello && ./hello
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
`scalino --help` for the full list. Dependency resolution shells out to `cs`
(coursier's own launcher is itself a prebuilt GraalVM native-image binary,
so this costs no JVM either), and both the resolved classpath and the
compiled/linked output are cached in `.scalino-build/`. Only libraries actually
cross-published for scala-native will *link* successfully (JVM-only jars
resolve and typecheck fine, but have no native code to call into) — see
`docs/findings.md`.

Unlike scala-cli, this toolchain only ever targets the one pinned
Scala/scala-native version it was built for (no per-project version
switching, no JVM/Scala.js platforms), and doesn't implement scala-cli's
`test`/`fmt`/`repl`/`package`/`publish`/`bsp`/`export` commands — running
any of those prints a clear "not implemented" instead of guessing.

The lower-level way — `bin/scalino-bootstrap`, a plain bash wrapper (what `scalino` itself
was bootstrapped from, and what `scalino`'s own build script still uses):

```
./bin/scalino-bootstrap build examples/Hello.scala -o hello
./hello
```

Neither `scalino-dotc`, `scalino-linkdriver`, `scalino`, nor `scalino-bootstrap` invoke a JVM.

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

**Also proven working, fully JVM-free: an LSP server** (`dist/scalino-lsp`,
`build/08-build-scalino-lsp.sh`) for editors like Zed, so Scala gets
diagnostics/hover without Metals' JVM dependency. Built by trimming and
patching dotty's own pre-Metals `language-server/` module, and, since
2026-09-07, self-hosted the same way as `scalino-dotc` -- compiled to NIR
and linked into a real Scala Native executable, no GraalVM native-image
anywhere in the binary (see `docs/findings.md`'s "Self-hosting scalino-lsp"
section for the real bugs found and fixed along the way). Verified against a
real native binary: correct diagnostics/hover/definition/references/rename
for both a hand-written project config and a real multi-package third-party
project. `dist/scalino setup-ide <sources...>` generates that project
config (`.dotty-ide.json`) instead of hand-writing it, and
[`zed-extension/`](zed-extension/) wires the server into Zed as a real
extension, published to Zed's extension gallery as "Scalino LSP"
(`README.md` there for install steps) — verified end-to-end against real
Zed (see `docs/findings.md`'s "JVM-free language server (LSP)" section).

## Releasing

Maintainers: push a `vX.Y.Z` tag and the [release workflow](.github/workflows/release.yml)
builds `dist/` on every supported platform/arch and publishes a GitHub Release
with one tarball per target, plus `.deb`/`.rpm` for the two Linux targets. A
platform that fails to build doesn't block the others — check the workflow
run for which targets actually shipped.

```
git tag vX.Y.Z
git push origin vX.Y.Z
```

Once that release has actually published its assets, refresh the third-party
packaging metadata (Arch/Nix/Homebrew, see above) and commit the result:

```
./build/10-update-package-metadata.sh vX.Y.Z
```
