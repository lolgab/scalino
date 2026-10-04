# scalino

[![CI](https://github.com/lolgab/scalino/actions/workflows/ci.yml/badge.svg)](https://github.com/lolgab/scalino/actions/workflows/ci.yml)

Write, build, and run Scala 3 without installing a JVM. `scalino` compiles
straight to a native executable via Scala Native — no bytecode, no JIT, no
`java` on your machine at all.

The compiler, linker, and LSP server are all themselves self-hosted: compiled
by dotc from their own patched source, targeting Scala Native directly, so
nothing you run day to day touches a JVM — see
[`docs/findings.md`](docs/findings.md) for the full story.

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

You'll still need one small pre-existing binary on the machine — not a JVM:
- **`clang`/`clang++`** — every build links through clang, even a
  zero-dependency Hello World. Already on macOS via Xcode Command Line
  Tools; `apt install clang` etc. elsewhere.

Dependency resolution (`//> using dep`, `scalino setup-ide`) needs no
separate install — scalino bundles its own renamed copy of
[coursier](https://get-coursier.io/)'s `cs` launcher (`scalino-cs`).

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

Every one of the above (brew/apt/dnf/arch/nix) installs bash/zsh/fish
completions for you automatically, at each shell's own standard lookup
path — nothing to source by hand. `install.sh`/a manual tarball extract has
no single system-wide place to drop them, so those ship the same
pre-generated scripts under `dist/completions/` for you to source/copy
yourself (`scalino completions <bash|zsh|fish>` regenerates them too, e.g.
if you've built from source).

## Use

`scalino` is a mini scala-cli, shaped like the real
[scala-cli](https://scala-cli.virtuslab.org/) on purpose:

```
scalino examples/Hello.scala                                                # run is the default command
scalino run examples/macro-hello/Test.scala examples/macro-hello/Foo.scala  # a real macro
scalino run examples/ --main-class Hello                                    # a whole directory
scalino run examples/Hello.scala -w                                         # watch mode
scalino package examples/Hello.scala -o hello && ./hello
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
targets — and commands it doesn't implement print a clear "not implemented"
instead of guessing.

There's also `bin/scalino-bootstrap`, the plain bash wrapper `scalino` itself
was bootstrapped from:

```
./bin/scalino-bootstrap build examples/Hello.scala -o hello
./hello
```

## Hermetic / Nix builds

Nix builds run without network access, except fixed-output derivations whose
hash is declared up front. So dependencies need a lockfile that something
else can fetch beforehand:

```
scalino lock .          # writes scalino.lock.json: per jar, path + URL + sha256
scalino run . --offline # builds from the lock + local cache only, never the network
```

While `scalino.lock.json` exists, builds take their classpath from it
instead of resolving through coursier. `SCALINO_CACHE=<dir>` (else
`COURSIER_CACHE`) sets where the jars live, laid out as
`<dir>/https/<host>/...`; `--offline` / `SCALINO_OFFLINE=1` forbids any
network use. With the flake in `packaging/nix`:

```nix
scalino.lib.${system}.mkScalinoApp {
  pname = "myapp";
  src = ./.;                       # contains scalino.lock.json
  # extraArgs = [ "--native-mode" "release-fast" ];
}
```

This fetches every locked jar with `pkgs.fetchurl` and builds offline. Re-run
`scalino lock` whenever dependencies change. The flake supports
x86_64/aarch64 Linux and macOS.

For editing, `mkScalinoDevShell` gives a `nix develop` shell with the same
offline cache plus the locked `-sources.jar`s (and the pinned stdlib sources
shipped in the package), so `scalino setup-ide .` and `scalino-lsp` work,
including go-to-definition into libraries, without network:

```nix
devShells.${system}.default = scalino.lib.${system}.mkScalinoDevShell {
  lockFile = ./scalino.lock.json;
};
```

The shell's cache is a read-only store path and offline is on. To change
dependencies, re-lock with a writable cache, then re-enter the shell:
`SCALINO_OFFLINE=0 SCALINO_CACHE= scalino lock .`

## Editor support

`dist/scalino-lsp` gives editors like Zed, VS Code, and Neovim Scala
diagnostics, hover, go-to-definition, references, and rename — without
Metals' JVM dependency. Run `scalino setup-ide <sources...>` to generate its
project config (`.scalino-build/scalino-lsp.json`).

For Zed, [`zed-extension/`](zed-extension/) wires it up as a real
extension, published to Zed's gallery as "Scalino". For VS Code,
[`vscode-extension/`](vscode-extension/) does the same, packaged locally
for now (not yet on the Marketplace). For Neovim,
[`neovim-extension/`](neovim-extension/) provides a `vim.lsp` client config
instead (no packaged plugin needed) — see each directory's README for
install steps.

## Status

The core toolchain — Scala 3 → NIR → native executable — works end to end,
including real inline/quote macros: macro expansion runs through a
from-scratch TASTy-tree interpreter instead of dotc's normal
bytecode-execution path, tested against real macro fixtures harvested from
upstream's own test suite. Not every macro shape is supported yet — the main
remaining known gap is structural quote-*type* patterns beyond a bare type
variable (e.g. `case '[List[t]] => `, as opposed to `case '[t] => `); general
quote-*expr* pattern matching (`case '{ ... } => `, including lambda-shaped
bodies) and case-class/`UnApply` deconstruction are both supported. Full
verified/blocked/remaining breakdown in [`docs/findings.md`](docs/findings.md).

The LSP server works too, and as of 2026-09-07 is self-hosted the same way
as the compiler — no JVM anywhere in the binary. Verified against a real
native binary on both a hand-written project and a real multi-package
third-party project, and end-to-end in actual Zed.

## Build from source

Requires a JDK 21+, `sbt`, `clang`, `coursier` (`cs`), `git`.

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

## License

Apache License 2.0, see [`LICENSE`](LICENSE). scalino's binaries are built
from patched checkouts of the [Scala 3](https://github.com/scala/scala3) and
[Scala Native](https://github.com/scala-native/scala-native) toolchains
(both Apache-2.0; scalino's changes are in [`patches/`](patches/)) — their
NOTICE/attribution content is reproduced in full in [`NOTICE`](NOTICE).
