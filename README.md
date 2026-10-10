# scalino

[![CI](https://github.com/lolgab/scalino/actions/workflows/ci.yml/badge.svg)](https://github.com/lolgab/scalino/actions/workflows/ci.yml)

Write, build, and run Scala 3 without installing a JVM. `scalino` compiles
straight to a native executable via Scala Native — no bytecode, no JIT, no
`java` on your machine at all.

Everything you run day to day is native: the compiler, the linker, the
`scalino` build tool, and the `scalino-lsp` language server are all compiled
by dotc from their own patched source, targeting Scala Native directly. See
[`docs/findings.md`](docs/findings.md) for the full story.

## Quick start

```sh
curl -fsSL https://raw.githubusercontent.com/lolgab/scalino/main/install.sh | bash

cat > Hello.scala <<'EOF'
@main def hello() = println("Hello from native Scala!")
EOF

scalino Hello.scala                          # compile + run
scalino package Hello.scala -o hello && ./hello
scalino setup-ide                          # then open the folder in Zed / VS Code / Neovim
```

The only other thing you need is `clang` (see [Install](#install)).

## How it compares

`scalino` is a mini [scala-cli](https://scala-cli.virtuslab.org/), shaped like
the real one on purpose: same command names, same `//> using` directives, same
flags where it implements them. `scalino-lsp` stands in for Metals.

The differences:

- **No JVM**, at build time or at run time.
- **One Scala and one Scala Native version**: the ones it was built with
  (`scalino version` prints them). You can't switch versions per project,
  and there are no JVM or Scala.js targets.

## Install

```sh
curl -fsSL https://raw.githubusercontent.com/lolgab/scalino/main/install.sh | bash
```

The script downloads the latest [release](https://github.com/lolgab/scalino/releases)
for your OS/arch, verifies its checksum, and installs `scalino` to
`~/.local/bin`. You can override this with `$SCALINO_INSTALL_DIR`,
`$SCALINO_BIN_DIR` or `$SCALINO_VERSION`.

**Requirement:** `clang`/`clang++`. Every build links through clang, even a
zero-dependency Hello World. It's already on macOS via the Xcode Command Line
Tools; elsewhere, install it with `apt install clang` or similar. Dependency
resolution doesn't need a separate install: scalino bundles its own renamed copy
of [coursier](https://get-coursier.io/)'s `cs` launcher (`scalino-cs`).

### Other ways to install

- **Tarball:** each release ships a self-contained `dist/` tarball (compiler +
  linker + `scalino` + `scalino-lsp`) for Linux (x86_64/arm64), macOS
  (x86_64/arm64), and Windows (x86_64, experimental, see
  [`docs/findings.md`](docs/findings.md)). Extract it and run `dist/scalino`.
- **Debian/Ubuntu and Fedora/RHEL/openSUSE:** every release publishes
  `.deb` and `.rpm` packages, with `clang` declared as a dependency. Both
  install to `/usr/lib/scalino/`, with symlinks in `/usr/bin/`:

  ```sh
  curl -fsSLO https://github.com/lolgab/scalino/releases/download/vX.Y.Z/scalino_X.Y.Z_amd64.deb
  sudo apt install ./scalino_X.Y.Z_amd64.deb

  sudo dnf install https://github.com/lolgab/scalino/releases/download/vX.Y.Z/scalino-X.Y.Z-1.x86_64.rpm
  ```

- **Arch, Nix, Homebrew:** these recipes exist but aren't published to their
  communities yet. Each file says what's still manual:
  [AUR PKGBUILD](packaging/arch/PKGBUILD),
  [Nix flake](packaging/nix/flake.nix),
  [Homebrew formula](packaging/homebrew/scalino.rb).

**Shell completions:** the brew, apt, dnf, Arch and Nix packages install
bash/zsh/fish completions automatically. With `install.sh` or a tarball, source
the scripts in `dist/completions/` yourself, or generate them with
`scalino completions <bash|zsh|fish>`.

## Use

```sh
scalino examples/Hello.scala                                                # run is the default command
scalino run examples/macro-hello/Test.scala examples/macro-hello/Foo.scala  # a real macro
scalino run examples/ --main-class Hello                                    # a whole directory
scalino run examples/Hello.scala -w                                         # watch mode
scalino test                                                                # munit/utest/scalatest/zio-test
scalino package examples/Hello.scala -o hello && ./hello
```

| command | does |
|---|---|
| `run` (default) | compile, link and run |
| `compile` | compile only, no link |
| `package` | link a native binary; `--format` also wraps it as a deb/rpm/tar/docker/brew package ([Packaging](#packaging)) |
| `test` | compile main + test scope, run the tests |
| `setup-ide` | write the LSP config ([Editor support](#editor-support)) |
| `lock` | write `scalino.lock.json` ([Hermetic builds](#hermetic--nix-builds)) |
| `sysroot build` | set up a cross-compilation sysroot ([Cross-compilation](#cross-compilation)) |
| `clean`, `version`, `completions` | what they say |

- **Sources:** pass files or directories. File order doesn't matter: the entry
  point (`@main`, `extends App`, `def main`) is found automatically. Files
  under `test/` or `src/test/scala/` are test scope, following scala-cli's
  convention.
- **Directives:** scalino understands the usual `//> using` directives:
  `dep`, `test.dep`, `scala`, `mainClass`, `options`, `repository`,
  `resourceDir`, `native*`, and more. Each has an equivalent flag (`--dep`,
  `-S`, `-O`, `--main-class`, `-w`, `-o`, `-- <args...>`, …). Values go
  unquoted, as in current scala-cli (quotes are still accepted):

  ```scala
  //> using dep com.lihaoyi::os-lib::0.11.3
  //> using options -Wunused:all -deprecation
  //> using nativeMode release-fast
  ```
- **Tests:** the test framework is auto-detected from the classpath, so any
  framework with a Scala Native port works. `--test-only <glob>` drops the
  other suites from the link, and `-- <pattern>` is passed through to the
  framework.
- **Builds are incremental:** unchanged sources are reused from the last build
  (`--no-incremental` turns this off). Resolved classpaths and build output are
  cached in `.scalino-build/`.

`scalino --help` and `scalino <command> --help` list every flag and directive.

## Editor support

`scalino-lsp` gives your editor completion, hover, signature help,
go-to-definition / type-definition / implementation, references, rename,
document and workspace symbols, highlights, inlay hints, semantic tokens,
selection ranges, code actions, and diagnostics. It's built on dotty's
presentation compiler and runs without a JVM.

1. Run `scalino setup-ide <sources...>`. This writes
   `.scalino-build/scalino-lsp.json`: one project for the main scope and,
   when there are `test/` sources, a second one that depends on it. Test
   dependencies (`//> using test.dep`) are only visible to the test project.
   Each project gets its own compiler, started on first use. The file is an
   array of `{id, platform, dependsOn, compilerArguments, sourceDirectories,
   dependencyClasspath}`, so further targets (say a js frontend) are more
   entries, not a new format.
2. Install the client for your editor (each directory's README has the steps):
   - **Zed:** [`zed-extension/`](zed-extension/), published in Zed's gallery as "Scalino".
   - **VS Code:** [`vscode-extension/`](vscode-extension/), installed locally for now (it's not on the Marketplace yet).
   - **Neovim:** [`neovim-extension/`](neovim-extension/), a `vim.lsp` config. No plugin needed.

## Status

What's verified to work:

- **The core toolchain** (Scala 3 → NIR → native executable), including
  inline/quote macros. Macros are expanded by a from-scratch TASTy-tree
  interpreter instead of dotc's usual approach of running bytecode on a JVM.
  It's tested against macro fixtures taken from upstream's own test suite.
  Quote-expression and quote-type patterns (`case '{ ... } =>`, `case '[List[t]] =>`) and
  case-class/`UnApply` deconstruction work.
- **The LSP**, on hand-written projects, on a real multi-package third-party
  project, and end to end in Zed.

Known gaps:

- **JUnit-style `@Test` discovery.** Test frameworks have to use
  `SubclassFingerprint`, which munit, utest, scalatest and zio-test-sbt all do.
- **Windows** is experimental.

[`docs/findings.md`](docs/findings.md) has the full breakdown of what's
verified, blocked or still to do.

## Packaging

`scalino package --format` wraps the native binary for distribution. You don't
need fpm or dpkg; `.rpm` uses `rpmbuild`, and docker uses `docker`/`podman`:

```sh
scalino package --format tar,deb,rpm,docker -o packages --pkg-version 1.2.3
scalino package --format brew --release-url https://github.com/me/app/releases/download/v1.2.3
```

| format | output | notes |
|---|---|---|
| `tar` | `<name>-<ver>-<triple>.tar.gz` + `.sha256` | Linux and macOS |
| `deb` | `<name>_<ver>_<arch>.deb` | Linux/glibc |
| `rpm` | `<name>-<ver>-1.<arch>.rpm` | Linux/glibc, needs `rpmbuild` |
| `docker` | `docker/Dockerfile` + image | `debian:stable-slim` base (`alpine` on musl) |
| `brew` | `<name>.rb` | covers every tarball found in the output dir |

The package metadata comes from these directives:
`//> using packageName|packageVersion|packageDescription|packageMaintainer|packageLicense|packageHomepage|packageDep|packageFile|packageDockerBase|packageDockerImage|packageReleaseUrl`
(see `scalino package --help`). With [cross-compilation](#cross-compilation),
one machine can build every target into the same output dir. You can also build
each OS/arch on its own CI runner. Either way, run `--format brew` last.

## Cross-compilation

One machine can build binaries for several platforms in a single command:

```sh
# missing sysroots are built automatically on first use
# (or ahead of time: scalino sysroot build <triple>...)
scalino package app/ -o myapp \
  --native-target-triple x86_64-unknown-linux-musl,aarch64-unknown-linux-musl
# -> myapp-x86_64-unknown-linux-musl, myapp-aarch64-unknown-linux-musl
```

`//> using nativeTargetTriple ...` works too, and `--format tar,...` packages
each target. All targets are compiled in one linker process, so the program is
parsed only once.

| target | from | needs |
|---|---|---|
| `x86_64-unknown-linux-musl`, `aarch64-unknown-linux-musl` | any host | `scalino sysroot build <triple>` (run for you on first use) |
| `x86_64-unknown-linux-gnu`, `aarch64-unknown-linux-gnu` | any host | same; glibc 2.31 from Debian 11, runs on any distro with glibc >= 2.31 |
| `x86_64-apple-darwin`, `aarch64-apple-darwin` | any host | nothing on a Mac (Xcode's SDK serves both); elsewhere `scalino sysroot build <triple>` (run for you on first use) |
| `x86_64-pc-windows-gnu`, `aarch64-pc-windows-gnu` | any host | `scalino sysroot build <triple>`, plus `lld` (**experimental**, see below) |

The musl targets are fully static, so one binary runs on every distro.
Cross links use `ld.lld`; if none is on PATH, scalino downloads one (from the llvm-mingw release,
cached next to the sysroots; `scalino sysroot lld` fetches it ahead of time).
A sysroot holds the target's libc headers and libraries. `scalino sysroot build
<triple>` assembles one on your machine from upstream packages, each checked
against a pinned sha256 (scalino publishes none), and installs it under
`~/.cache/scalino/sysroots`. `scalino package --native-target-triple` runs it
automatically for any target without one. It needs only `curl` and `tar`; nothing is compiled.

| target | assembled from |
|---|---|
| musl | Debian's `musl-dev` (unmodified musl) and `libclang-rt-14-dev` (compiler-rt) |
| gnu | Debian 11's glibc 2.31 and kernel headers, plus the same compiler-rt |
| macOS | the open source Darwin libc headers from the Zig project's source tarball, and a `libSystem` stub generated from them (a stand-in for Apple's SDK, enough for anything that needs no frameworks) |
| Windows | mingw-w64 with libc++ from the [llvm-mingw](https://github.com/mstorsjo/llvm-mingw) project (GitHub) |

- The Windows sysroot's libc++ needs **clang 21 or newer** (older clangs fail on its
  `#pragma clang attribute` headers); point `--native-clang`/`--native-clangpp` at one if
  the `clang` on your `PATH` is older.
- Dependencies that link OpenSSL's `libcrypto` through `@link("crypto")` (`scala-native-crypto`,
  `fs2-core`, `http4s-crypto`, `smithy4s-aws-kernel`, `skunk-core`, also when only transitive) get a
  **static** OpenSSL 3 for every cross target, installed next to the sysroot on first use
  (`scalino sysroot build <triple> --with openssl` does it ahead of time). A host target keeps using
  the system's. Packages are prebuilt and checksum-verified: Alpine (musl), Debian 12 (gnu), MSYS2
  (Windows, needs a `tar` that reads zstd) and Homebrew's `openssl@3` bottle (macOS).
- The same mechanism ships other static C libraries, installed when a classpath dependency links them
  (also transitively) and installable ahead of time with `scalino sysroot build <triple> --with <lib>`:
  `idn2` (sttp-model; libidn2 and libunistring, from the same package sources; LGPL, so a program
  that links it statically must let its users relink), `s2n` (fs2-io, so skunk and http4s-ember too;
  not on Windows, where s2n-tls has no port) and `curl` (sttp's curl backend). s2n-tls and libcurl
  have no static packages, so they are **compiled** on your machine with the `clang` and `llvm-ar`
  on your `PATH` (about a minute per target, once); both need the OpenSSL addon, installed with them,
  and set `SSL_CERT_FILE` to the system's CA bundle at startup unless you have. libcurl is built with
  HTTP(S) over OpenSSL and zlib only (Windows: MSYS2's full build).
- `$SCALINO_SYSROOT_DIR` changes where sysroots are installed.
- Downloads are cached in `<sysroots>/.sources`, or `$SCALINO_SYSROOT_SOURCES`; pre-fill it to build offline.

`run` and `test` always build for the host, because the result has to run on
this machine.

## Hermetic / Nix builds

Nix builds have no network access, except for fixed-output derivations, whose
hash is declared up front. So dependencies need a lockfile that something else
can fetch beforehand:

```sh
scalino lock           # writes scalino.lock.json: per jar, path + URL + sha256
scalino run --offline  # builds from the lock + local cache only, never the network
```

While `scalino.lock.json` exists, builds take their classpath from it instead
of resolving through coursier. The jar cache lives in `SCALINO_CACHE=<dir>`
(else `COURSIER_CACHE`), laid out as `<dir>/https/<host>/...`. `--offline`
(or `SCALINO_OFFLINE=1`) forbids any network use. With the flake in
`packaging/nix`:

```nix
scalino.lib.${system}.mkScalinoApp {
  pname = "myapp";
  src = ./.;                       # contains scalino.lock.json
  # extraArgs = [ "--native-mode" "release-fast" ];
  # C libraries: buildInputs puts them in the sandbox, pkgConfig makes scalino
  # run `pkg-config --cflags/--libs` on each module at build time.
  # buildInputs = [ pkgs.gtk4 pkgs.graphene ];
  # pkgConfig = [ "gtk4" ];
}
```

Outside Nix, the same thing is `//> using nativePkgConfig gtk4` or
`--native-pkg-config gtk4`.

This fetches every locked jar with `pkgs.fetchurl` and builds offline. Re-run
`scalino lock` whenever dependencies change. The flake supports x86_64/aarch64
Linux and macOS.

For editing, `mkScalinoDevShell` gives you a `nix develop` shell with:

- the same offline cache
- the locked `-sources.jar`s
- the pinned stdlib sources shipped in the package

With that, `scalino setup-ide .` and `scalino-lsp` work without network,
including go-to-definition into libraries:

```nix
devShells.${system}.default = scalino.lib.${system}.mkScalinoDevShell {
  lockFile = ./scalino.lock.json;
};
```

The shell's cache is a read-only store path, and offline mode is on. To change
dependencies, re-lock with a writable cache, then re-enter the shell:
`SCALINO_OFFLINE=0 SCALINO_CACHE= scalino lock .`

## Build from source

You need JDK 21+, `sbt`, `clang`, `coursier` (`cs`) and `git`.

```sh
./build/all.sh
```

This clones `scala/scala3` and `scala-native/scala-native` into `vendor/` (at
the versions pinned in `versions.env`) and applies the patches from `patches/`.
It produces `dist/scalino-dotc`, `dist/scalino-lsp`, `dist/scalino-linkdriver`
and `dist/scalino`.

`bin/scalino-bootstrap` is the plain bash wrapper that `scalino` itself was
bootstrapped from:

```sh
./bin/scalino-bootstrap build examples/Hello.scala -o hello && ./hello
```

## Releasing

For maintainers: push a `vX.Y.Z` tag, and the
[release workflow](.github/workflows/release.yml) builds `dist/` for every
supported platform/arch and publishes a GitHub Release. If one platform fails
to build, the others still publish.

```sh
git tag vX.Y.Z && git push origin vX.Y.Z
./build/10-update-package-metadata.sh vX.Y.Z   # once assets are published; commit the result
```

## License

Apache License 2.0, see [`LICENSE`](LICENSE). scalino's binaries are built
from patched checkouts of the [Scala 3](https://github.com/scala/scala3) and
[Scala Native](https://github.com/scala-native/scala-native) toolchains
(both Apache-2.0; scalino's changes are in [`patches/`](patches/)). Their
NOTICE/attribution content is reproduced in full in [`NOTICE`](NOTICE).
