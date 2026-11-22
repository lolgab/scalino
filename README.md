# scalino

[![CI](https://github.com/lolgab/scalino/actions/workflows/ci.yml/badge.svg)](https://github.com/lolgab/scalino/actions/workflows/ci.yml)

scalino builds and runs Scala 3 without a JVM. It is a small
[scala-cli](https://scala-cli.virtuslab.org/) for
[Scala Native](https://scala-native.org/) and [Scala.js](https://www.scala-js.org/):
the commands and the `//> using` directives are the ones you know, but the
compiler, the linker, the build tool and the language server are native
executables. Nothing in a build runs bytecode, and the machine doesn't need
`java`.

```console
$ cat Hello.scala
@main def hello() = println("Hello from native Scala!")

$ scalino Hello.scala
Compiling project (Scala 3.10.0, Scala Native 0.5.13-SNAPSHOT)
Compiled project (Scala 3.10.0, Scala Native 0.5.13-SNAPSHOT)
[info] Linking (multithreadingEnabled=true) (1843 ms)
...
[info] Total (8682 ms)
Hello from native Scala!

$ scalino package Hello.scala -o hello
$ ./hello
Hello from native Scala!
```

dotc is a Scala program, so in principle Scala Native can compile it. In
practice that took the fixes logged in [`docs/findings.md`](docs/findings.md),
and now `scalino-dotc` is dotc compiled by dotc into a native binary. The linker
(`scalino-linkdriver`), the Scala.js linker (`scalino-jslink`), the language
server (`scalino-lsp`) and `scalino` itself are built the same way, from the
patched sources in this repository. The one thing that could not be carried
over is macro expansion: dotc runs a macro by loading its bytecode into the
compiler's own JVM. Here a TASTy interpreter runs it instead.

The compiler, the linker and the language server are profile-guided builds,
which makes them about 11–14% faster ([`docs/pgo.md`](docs/pgo.md)).

## scalino or scala-cli?

If you use scala-cli you already know scalino. `run`, `test`, `package`,
`setup-ide`, `-w`, `--dep` and the `//> using` directives mean the same thing
here, wherever they are implemented, and `scalino-lsp` plays the part of Metals.

What you give up:

- The choice of versions. A scalino release is built from one Scala, one Scala
  Native and one Scala.js, and those are the ones you get. `scalino version`
  prints them.
- The JVM target. Every dependency has to be published for Scala Native or for
  Scala.js.
- Most of scala-cli's commands. The list in [Use](#use) is the whole tool.

What you get is a toolchain with no JDK in it, on your laptop and in CI.
Scala.js builds don't need a JVM either (`//> using platform js` or `--js`),
and a Scala Native server can carry its Scala.js client inside the binary, as
[`examples/client-server`](examples/client-server) does.

## Install

```sh
curl -fsSL https://raw.githubusercontent.com/lolgab/scalino/main/install.sh | bash
```

The script downloads the latest [release](https://github.com/lolgab/scalino/releases)
for your OS and architecture, verifies its checksum and installs `scalino` to
`~/.local/bin`. `$SCALINO_INSTALL_DIR`, `$SCALINO_BIN_DIR` and
`$SCALINO_VERSION` override where it goes and which version it takes.

On Windows (x86_64 and arm64), from PowerShell:

```powershell
irm https://raw.githubusercontent.com/lolgab/scalino/main/install.ps1 | iex
```

This installs into `%LOCALAPPDATA%\scalino` and adds it to your `PATH`. It
needs no administrator rights. More in [Windows](#windows).

### What else you need

Every Scala Native build links through clang, so Linux and macOS need
`clang`/`clang++`. macOS has them with the Xcode Command Line Tools; elsewhere
it is `apt install clang` or the equivalent. Windows needs nothing, because
scalino downloads llvm-mingw's clang on the first build.

Scala.js builds need no C toolchain, only `node` (or `deno`, or `bun`) to run
the output. The Scala.js base libraries ship in the tarball, so plain programs
build offline.

You don't need coursier. scalino bundles its `cs` launcher under the name
`scalino-cs` and resolves dependencies with it.

### Other ways to install

Each release has a self-contained tarball for Linux and macOS (x86_64 and
arm64) and a `.zip` for Windows (x86_64 and arm64). It holds the compiler, the
linker, the Scala.js linker, `scalino` and `scalino-lsp`. Extract it and run
`dist/scalino`.

There are `.deb` and `.rpm` packages as well, with `clang` declared as a
dependency. Both install to `/usr/lib/scalino/` with symlinks in `/usr/bin/`:

```sh
curl -fsSLO https://github.com/lolgab/scalino/releases/download/vX.Y.Z/scalino_X.Y.Z_amd64.deb
sudo apt install ./scalino_X.Y.Z_amd64.deb

sudo dnf install https://github.com/lolgab/scalino/releases/download/vX.Y.Z/scalino-X.Y.Z-1.x86_64.rpm
```

With Homebrew, on macOS or Linux:

```sh
brew tap lolgab/tap
brew install lolgab/tap/scalino
```

The formula lives in [lolgab/homebrew-tap](https://github.com/lolgab/homebrew-tap)
and every release updates it.

For Arch and Nix there are recipes that are not published to their communities
yet: an [AUR PKGBUILD](packaging/arch/PKGBUILD) and a
[Nix flake](packaging/nix/flake.nix). Each file says what is still manual.

The brew, apt, dnf, Arch and Nix packages install bash, zsh and fish
completions. With `install.sh` or a tarball, source the scripts in
`dist/completions/` yourself or generate them with
`scalino completions <bash|zsh|fish>`.

## Use

```sh
scalino examples/Hello.scala                                                # run is the default command
scalino run examples/macro-hello/Test.scala examples/macro-hello/Foo.scala  # a real macro
scalino run examples/ --main-class Hello                                    # a whole directory
scalino run examples/Hello.scala -w                                         # watch mode
scalino run examples/Hello.scala --interpret                                # no link step: run on the interpreter
scalino run hello.sc -- some args                                           # a script
scalino repl --dep com.lihaoyi::os-lib::0.11.3                              # REPL, no JVM
scalino test                                                                # munit/utest/scalatest/zio-test
scalino package examples/Hello.scala -o hello && ./hello
scalino package . --main-class Server,Worker -o bin/                        # one binary per main, each with its own link cache
```

| command | does |
|---|---|
| `run` (default) | compile, link and run |
| `compile` | compile only, no link |
| `package` | link a native binary (or, with `--js`, a JavaScript bundle); `--format` also wraps it as a deb/rpm/tar/docker/brew package ([Packaging](#packaging)) |
| `test` | compile main + test scope, run the tests |
| `repl` | interactive Scala REPL, interpreted ([REPL and the interpreter](#repl-and-the-interpreter)) |
| `setup-ide` | write the LSP config ([Editor support](#editor-support)) |
| `lock` | write `scalino.lock.json` ([Hermetic builds](#hermetic--nix-builds)) |
| `publish-local`, `publish` | publish a library to `~/.ivy2/local` or Maven Central ([Publishing libraries](#publishing-libraries)) |
| `sysroot build` | set up a cross-compilation sysroot ([Cross-compilation](#cross-compilation)) |
| `clean`, `version`, `completions` | what they say |

`scalino --help` and `scalino <command> --help` list every flag and directive.
The rest of this section is the part worth reading once.

### Sources

Pass files or directories, in any order. scalino finds the entry point by
itself, whether it is an `@main`, an `extends App` or a `def main`. Files under
`test/` or `src/test/scala/` are test scope, as in scala-cli.

### Directives

The `//> using` directives include `dep`, `test.dep`, `scala`, `mainClass`,
`options`, `repository`, `resourceDir` and the `native*` family. Each has a
flag that does the same (`--dep`, `-S`, `-O`, `--main-class`, `-w`, `-o`,
`-- <args...>` and so on). Values go unquoted, as in current scala-cli, though
quotes are still accepted:

```scala
//> using dep com.lihaoyi::os-lib::0.11.3
//> using options -Wunused:all -deprecation
//> using nativeMode release-fast
```

### Tests

The test framework is detected from the classpath, so anything with a Scala
Native port works.

```console
$ cat test/FibSuite.scala
//> using test.dep org.scalameta::munit::1.2.1

class FibSuite extends munit.FunSuite:
  test("fib(10) is 55"):
    assertEquals(fib(10), BigInt(55))
  test("fib(0) is 0"):
    assertEquals(fib(0), BigInt(0))

$ scalino test .
...
FibSuite:
  + fib(10) is 55 0.000s
  + fib(0) is 0 0.000s
scalino: 2 passed, 0 failed, 0 errored, 0 skipped
```

`--test-only <glob>` leaves the other suites out of the link altogether, and
anything after `--` goes to the framework.

### Toolkits

`//> using toolkit default` adds the [Scala Toolkit](https://docs.scala-lang.org/toolkit/introduction.html)
(os-lib, upickle, sttp) to the build and `toolkit-test` (munit) to the tests,
so the examples from the toolkit tutorials build as they are. The syntax is
scala-cli's: a version, `default` (a version pinned in scalino, 0.9.2 for now)
or `latest`, optionally prefixed by an organization. `typelevel:` is short for
`org.typelevel`, the [Typelevel Toolkit](https://typelevel.org/toolkit/):

```scala
//> using toolkit default             // org.scala-lang::toolkit::0.9.2
//> using toolkit typelevel:default   // org.typelevel::toolkit::0.2.0
//> using toolkit com.example:1.0     // com.example::toolkit::1.0 and toolkit-test
//> using test.toolkit default        // only toolkit-test, in test scope
```

On the Typelevel Toolkit, three macros don't expand yet: circe's
`derives Codec.AsObject`, http4s's `uri"..."` literal, and weaver's test
source positions, which leaves weaver suites unusable for now. Write the codec
with `Codec.forProduct*` and parse the URI with `Uri.unsafeFromString`; the
rest (cats-effect, fs2, decline, the Ember client) runs.

### Incremental builds and the warm compiler

Sources that didn't change are reused from the last build (`--no-incremental`
turns that off). Resolved classpaths and build output are cached in
`.scalino-build/`.

Builds compile through a background `scalino-dotc` that keeps the compiler and
the loaded dependencies in memory. It serves `-w` rebuilds and separate
commands alike, so a small edit recompiles in a fraction of the cold time.
There is one per build directory, each exits after 15 idle minutes, and at most
3 run at once. `scalino daemon status` lists them and `scalino daemon stop`
stops them. `--no-daemon` or `SCALINO_DOTC_DAEMON=0` turns the daemon off, and
it is off by default when `$CI` is set.

### Debug info

Binaries carry no source-level debug info (DWARF) unless you ask. Pass
`--native-debug-info` or `//> using nativeDebugInfo true` and lldb, gdb and
profilers show Scala sources and line numbers.

## REPL and the interpreter

The TASTy tree interpreter that expands macros (it lives in `scalino-dotc`) can
also run whole programs, so some things need no link step at all:

- **`scalino repl [sources...]`**: a Scala 3 REPL. Each input is compiled in one
  warm `scalino-dotc` and run on the interpreter, with the given sources and
  `--dep`s on the classpath. Commands: `:help`, `:quit`, `:type <expr>`,
  `:imports`, `:reset`, `:load <file>`. `--repl-init-script <code>` runs code
  first, `--repl-quit-after-init` exits after it. Values print with pprint and
  definitions are highlighted, as in the stock REPL. In a terminal the input is
  edited in place: Tab completes, Up/Down walk the lines of a multi-line input
  and the history (`~/.scalino_history`), Enter on an unfinished input starts a
  new line (Alt-Enter always does, an empty line submits). Ctrl-C drops the
  input but can't stop a running evaluation: it ends the session.
- **`scalino run --interpret`**: compiles as usual, then interprets `main`
  instead of linking: the edit-run loop skips the link step. Works with `-w`
  (there it never falls back to linking: rerun without `--interpret`).
- **Scripts:** a `.sc` file passed as a source runs its top-level statements in
  order, with the program arguments as `args`, linked or (`--interpret`)
  interpreted. Directories never pick up `.sc` files.

The interpreter has no native runtime: code using `@extern`,
`scala.scalanative.unsafe`, threads or `Future` is linked instead
(`run --interpret` says so on stderr and falls back by itself). When the
interpreter meets something it doesn't support while running, `run` also
falls back to linking, unless the program already printed something; then it
stops with an error naming the unsupported call. An uncaught exception prints
its message and the interpreted call stack, without line numbers. It is
slower than a linked binary for long computations: it is meant for the
edit-run loop and for scripts, not benchmarks.

## Scala.js

Scala.js is a second target next to Scala Native, with scala-cli's flags and
defaults (a plain script, no source maps). The compiler is `dotc -scalajs`. The
linker is `scalino-jslink`, which is the Scala.js linker compiled with Scala
Native. So there is no JVM and no C toolchain involved, and all you need to run
the result is `node` (or `deno`, or `bun`).

```sh
scalino run --js Hello.scala                                  # or //> using platform js
scalino test --js                                             # munit etc. on node
scalino package --js --js-mode release -o app.js Hello.scala  # fullLinkJS: optimizer + minifier
scalino package --js --js-module-kind esmodule -o app.mjs
```

| flag | directive | does |
|---|---|---|
| `--js`, `--platform scala-js` | `platform js` | build for Scala.js |
| `--js-mode dev\|release` | `jsMode` | `fastLinkJS` (default) or `fullLinkJS` |
| `--js-module-kind <kind>` | `jsModuleKind` | `commonjs`, `esmodule` (writes `.mjs`) or `nomodule` (default) |
| `--js-emit-source-maps` | `jsEmitSourceMaps` | emit source maps (off by default) |
| `--js-runtime node\|deno\|bun` | | what `run` and `test` execute with |

`--js-no-opt`, `--js-es-version` and a few other linker flags are listed in
`scalino run --help`.

A dependency written as `//> using dep org::name::version` resolves to its
`_sjs1_3` artifact. Test frameworks need a Scala.js port (munit and utest have
one). Under `-w` one linker process stays alive between rebuilds, and
`scalino setup-ide` adds Scala.js projects to the LSP config. The one thing
missing is `package --format`.

### Server and client without a JVM

A Scala Native server can embed a Scala.js frontend:

```scala
//> using jsClient ../client   // a Scala.js project
//> using shared ../shared     // sources compiled into both sides
```

`scalino run` builds the client and embeds it in the server binary as the
resource `/client/main.js`. The client is linked with fastLinkJS, or with
fullLinkJS when the server is built in a `release-*` native mode (`--js-mode`
overrides this). [`examples/client-server`](examples/client-server) is a
complete example.

If something else serves the client (FreeUnit's `share` action, nginx, a CDN),
add `//> using jsClientOut ../client-dist` or pass `--js-client-out <dir>`.
The Scala.js linker then writes straight into that directory and nothing is
embedded in the binary. Each file goes through a temporary name and a rename,
so a server hosting the directory never sees a half-written file. The entry
point, the file names and their sizes are recorded in `manifest.json`. Other
files in the directory are left alone: scalino only removes what a previous
manifest listed and the new build no longer produces.

For a client split into many ES modules, use scala-cli's options:
`--js-module-kind esmodule --js-module-split-style smallestmodules` (also
`fewestmodules`, and `smallmodulesfor` with `--js-small-module-for-package <pkg>`;
the directives are `jsModuleSplitStyleStr` and `jsSmallModuleForPackage`). A
split client is many files, so it needs `jsClientOut` and cannot be embedded.
The manifest lists every module.

## Editor support

`scalino-lsp` is built on dotty's presentation compiler and runs without a
JVM. It does completion, hover, signature help, go to definition, type
definition and implementation, references, rename, document and workspace
symbols, highlights, inlay hints, semantic tokens, selection ranges, code
actions and diagnostics.

1. Run `scalino setup-ide <sources...>`. It writes
   `.scalino-build/scalino-lsp.json` with one project for the main scope and,
   when there are `test/` sources, a second one that depends on it. Test
   dependencies (`//> using test.dep`) are only visible to the test project,
   and each project gets its own compiler, started on first use. The file is
   an array of `{id, platform, dependsOn, compilerArguments, sourceDirectories,
   dependencyClasspath}`, so another target (a js frontend, say) is one more
   entry and not a new format.
2. Install the client for your editor. Each directory has a README with the
   steps:
   - Zed: [`zed-extension/`](zed-extension/), in Zed's gallery as "Scalino".
   - VS Code: [`vscode-extension/`](vscode-extension/), installed locally (it is not on the Marketplace).
   - Neovim: [`neovim-extension/`](neovim-extension/), a `vim.lsp` config. No plugin needed.

## Packaging

`scalino package --format` wraps the native binary for distribution. It needs
neither fpm nor dpkg. `.rpm` uses `rpmbuild`, and docker uses `docker` or
`podman`:

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
(see `scalino package --help`).

With [cross-compilation](#cross-compilation) one machine can build every target
into the same output directory. Building each OS and architecture on its own CI
runner works too. Either way, run `--format brew` last.

## Publishing libraries

`scalino publish-local` publishes the library in the current directory to
`~/.ivy2/local`, where any other scalino project (or sbt, Mill, scala-cli)
resolves it with no extra repository. `scalino publish` sends it to Maven
Central through the Sonatype Central Portal. Both read scala-cli's
`//> using publish.*` directives:

```scala
//> using publish.organization io.github.me
//> using publish.name mylib
//> using publish.version 1.2.3          // or: publish.computeVersion git:tag
//> using publish.description "What it does"
//> using publish.url https://github.com/me/mylib
//> using publish.license Apache-2.0
//> using publish.versionControl github:me/mylib
//> using publish.developer "me|My Name|https://github.com/me"
```

```sh
scalino publish-local                   # -> mylib_native0.5_3; --js for mylib_sjs1_3
# in another project:  //> using dep io.github.me::mylib::1.2.3

SONATYPE_USERNAME=... SONATYPE_PASSWORD=... scalino publish   # signs with gpg
```

The main jar holds the `.tasty` plus the `.nir` (Scala Native) or `.sjsir`
(Scala.js) that dotc wrote, and the resource directories. It has no `.class`
files, since there is no JVM backend. A sources jar, an empty javadoc jar and
the POM go with it. The POM lists the platform runtime libraries as sbt does,
at the last released Scala Native version.

| target | how |
|---|---|
| `publish-local` | ivy layout in `~/.ivy2/local` (`--ivy2-home`); `--m2` for `~/.m2/repository` |
| `publish` (`-R central`, default) | one signed bundle to the Central Portal, published once it validates (`--manual-release`: you publish it from the portal); `-SNAPSHOT` versions go to Central's snapshots repository |
| `publish -R https://...` | Maven deploy by HTTP PUT (`--user`/`--password`) |
| `publish -R <dir>` | Maven layout written to a directory |

Credentials and keys take scala-cli's `env:NAME` / `file:path` / `value:text`
forms. They default to `$SONATYPE_USERNAME`/`$SONATYPE_PASSWORD` (the portal's
user token) and to `$PGP_SECRET`/`$PGP_PASSPHRASE`. A `$PGP_SECRET` key is
imported into a temporary GNUPGHOME, so your keyring is left alone; `--gpg-key`
signs with a key from your keyring instead. `--dummy` builds and signs
everything but uploads nothing. See `scalino publish --help`.

## Cross-compilation

One command can build for several platforms:

```console
$ scalino package Hello.scala -o hello \
    --native-target-triple x86_64-unknown-linux-musl,aarch64-unknown-linux-musl
Wrote hello-x86_64-unknown-linux-musl
Wrote hello-aarch64-unknown-linux-musl

$ file hello-*
hello-aarch64-unknown-linux-musl: ELF 64-bit LSB executable, ARM aarch64, version 1 (SYSV), statically linked, with debug_info, not stripped
hello-x86_64-unknown-linux-musl:  ELF 64-bit LSB executable, x86-64, version 1 (SYSV), statically linked, with debug_info, not stripped
```

That was run on a Mac. `//> using nativeTargetTriple ...` works too, and
`--format tar,...` packages each target. All targets are compiled in one linker
process, so the program is parsed only once.

| target | from | needs |
|---|---|---|
| `x86_64-unknown-linux-musl`, `aarch64-unknown-linux-musl` | any host | a sysroot, built for you on first use |
| `x86_64-unknown-linux-gnu`, `aarch64-unknown-linux-gnu` | any host | the same; glibc 2.31 from Debian 11, so it runs on any distro with glibc >= 2.31 |
| `x86_64-apple-darwin`, `aarch64-apple-darwin` | any host | a sysroot, built for you on first use (on a Mac too) |
| `x86_64-pc-windows-gnu`, `aarch64-pc-windows-gnu` | any host | `scalino sysroot build <triple>`, plus `lld` |

The musl targets are fully static, so one binary runs on every distro.

Cross links use `ld.lld`. If none is on `PATH`, scalino downloads one from the
llvm-mingw release and caches it next to the sysroots. `scalino sysroot lld`
fetches it ahead of time.

`run` and `test` always build for the host, because the result has to run on
this machine.

### Sysroots

A sysroot holds the target's libc headers and libraries. scalino publishes
none. `scalino sysroot build <triple>` assembles one on your machine from
upstream packages, each checked against a pinned sha256, and installs it under
`~/.cache/scalino/sysroots`. It needs only `curl` and `tar`, and it compiles
nothing. `scalino package --native-target-triple` runs it for any target that
has no sysroot yet.

| target | assembled from |
|---|---|
| musl | Debian's `musl-dev` (unmodified musl) and `libclang-rt-14-dev` (compiler-rt) |
| gnu | Debian 11's glibc 2.31 and kernel headers, plus the same compiler-rt |
| macOS | the open source Darwin libc headers from the Zig project's source tarball, and `libSystem` and `libobjc` stubs generated from them |
| Windows | mingw-w64 with libc++ from the [llvm-mingw](https://github.com/mstorsjo/llvm-mingw) project |

The macOS sysroot is a stand-in for Apple's SDK. It is enough for anything that
needs no frameworks, and that includes programs that `dlopen` the frameworks
and drive them through the Objective-C runtime.

A sysroot carries a stamp of the recipe that built it. One built by an older
scalino, or by an edited recipe, is rebuilt on next use.
`$SCALINO_SYSROOT_DIR` changes where sysroots are installed. Downloads are
cached in `<sysroots>/.sources`, or in `$SCALINO_SYSROOT_SOURCES`, and you can
fill that directory in advance to build offline.

### Host builds and target builds

`--native-target-triple` always links against scalino's sysroot, even when the
triple is the machine's own. On a Mac that means the sysroot and not the
installed SDK. The result doesn't depend on the system's libc, so a build that
works locally works on any CI.

Without the flag you get a plain system build that uses the machine's
toolchain, SDK, frameworks and libraries (`--native-pkg-config` and friends).
That is the build to use on a Mac when you want Apple frameworks.

### C libraries

Some Scala Native libraries link a C library, and a cross target has no system
copy to link against. scalino ships static ones for the common cases.

Dependencies that link OpenSSL's `libcrypto` through `@link("crypto")` get a
static OpenSSL 3 for every cross target. That covers `scala-native-crypto`,
`fs2-core`, `http4s-crypto`, `smithy4s-aws-kernel` and `skunk-core`, also when
they are only transitive. It is installed next to the sysroot on first use, or
ahead of time with `scalino sysroot build <triple> --with openssl`. A host
build keeps using the system's OpenSSL. The packages are prebuilt and
checksum-verified: Alpine (musl), Debian 12 (gnu), MSYS2 (Windows, which needs
a `tar` that reads zstd) and Homebrew's `openssl@3` bottle (macOS).

Three more libraries work the same way. They are installed when a classpath
dependency links them, transitively or not, and
`scalino sysroot build <triple> --with <lib>` installs them ahead of time:

- `idn2`, for sttp-model. This is libidn2 with libunistring, from the same
  package sources. It is LGPL, so a program that links it statically must let
  its users relink.
- `s2n`, for fs2-io, and so for skunk and http4s-ember. Not on Windows, where
  s2n-tls has no port.
- `curl`, for sttp's curl backend.

s2n-tls and libcurl have no static packages, so they are compiled on your
machine with the `clang` and `llvm-ar` on your `PATH`. That takes about a
minute per target, once. Both need the OpenSSL addon, which is installed with
them, and both set `SSL_CERT_FILE` to the system's CA bundle at startup unless
you already set it. libcurl is built with HTTP(S) over OpenSSL and zlib only
(on Windows it is MSYS2's full build).

### Smaller things

- The Windows sysroot's libc++ needs clang 21 or newer. Older clangs fail on
  its `#pragma clang attribute` headers. Point `--native-clang` and
  `--native-clangpp` at a newer one if the `clang` on your `PATH` is older.
- Programs that call the Objective-C runtime (`objc_msgSend` and so on) and
  `dlopen` the frameworks link against the macOS sysroot's `libobjc` stub.
  `@link("objc")` adds `-lobjc`. C code can autolink it with
  `__asm__(".linker_option \"-lobjc\"")`, which `ld64.lld` honours;
  `#pragma comment(lib)` is not implemented for Mach-O. The frameworks
  themselves are not in the sysroot.
- The macOS sysroot includes a minimal `sys/posix_sem.h`, so `nativeGc commix`
  cross-builds.
- `--native-c-compile` also reaches `.S` files, so guard `-include`d headers
  with `__ASSEMBLER__`.

## Windows

scalino runs natively on Windows 10 and 11, on x86_64 and arm64: `run`, `test`,
`package`, Scala.js and `scalino-lsp` all work. Nothing but scalino has to be
installed. The first build downloads
[llvm-mingw](https://github.com/mstorsjo/llvm-mingw)'s `clang` and `lld`
(about 190 MB) and assembles the mingw-w64 sysroot, both into
`%LOCALAPPDATA%\scalino\sysroots`.

A build on Windows is a cross build to the machine's own `<arch>-pc-windows-gnu`
target, so it links the same way as one from Linux or macOS: statically, with
libc++, and with no dependency on Visual Studio or an MSVC runtime.

- Programs link through llvm-mingw and not MSVC. Libraries that need the MSVC
  ABI, or `.lib` files from a Visual Studio install, are not supported.
- `--native-clang` and `--native-clangpp` replace the downloaded toolchain. The
  target stays MinGW.
- Compact object headers, which Linux and macOS targets use, are off for
  Windows targets, so objects are a little bigger there.
- `$SCALINO_SYSROOT_DIR` moves the cache, and `$SCALINO_SYSROOT_SOURCES` can be
  filled in advance to build offline, as everywhere else.
- The first-time downloads need `tar` (it ships with Windows 10 and later) and
  `curl`.

## Hermetic / Nix builds

A Nix build has no network access. The exception is a fixed-output derivation,
whose hash is declared up front. So dependencies need a lockfile that something
else can fetch beforehand:

```sh
scalino lock           # writes scalino.lock.json: per jar, path + URL + sha256
scalino run --offline  # builds from the lock + local cache only, never the network
```

While `scalino.lock.json` exists, builds take their classpath from it instead
of resolving through coursier. The jar cache lives in `SCALINO_CACHE=<dir>`
(or else `COURSIER_CACHE`), laid out as `<dir>/https/<host>/...`. `--offline`
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

The flake fetches every locked jar with `pkgs.fetchurl` and builds offline.
Re-run `scalino lock` whenever dependencies change. It supports x86_64 and
aarch64, on Linux and macOS.

### Cross-compiling from Nix

This needs no emulation and no remote builder. The build-platform scalino
compiles for the target using scalino's sysroots, which are fetched (hash-pinned)
and assembled offline:

```nix
scalino.lib.${system}.mkScalinoApp {
  pname = "myapp";
  src = ./.;
  nativeTargetTriples = [ "x86_64-unknown-linux-musl" "aarch64-unknown-linux-musl" ];
  # sysrootAddons = [ "openssl" ];   # static C libs the classpath links, see above
}
# -> bin/myapp-x86_64-unknown-linux-musl, bin/myapp-aarch64-unknown-linux-musl
```

With `nativeTargetTriples`, `buildInputs` and `pkgConfig` are rejected. Those
are the builder's libraries, and the target is linked against the sysroot.
`*-linux-musl` binaries are static and run anywhere. `*-linux-gnu` ones are
dynamic against glibc 2.31, so patchelf them on NixOS.

### A dev shell

`mkScalinoDevShell` provides a `nix develop` shell with the same offline cache,
the locked `-sources.jar`s and the pinned stdlib sources that ship in the
package. In that shell `scalino setup-ide .` and `scalino-lsp` work offline,
go to definition into libraries included:

```nix
devShells.${system}.default = scalino.lib.${system}.mkScalinoDevShell {
  lockFile = ./scalino.lock.json;
};
```

The shell's cache is a read-only store path, and offline mode is on. To change
dependencies, lock again with a writable cache and re-enter the shell:
`SCALINO_OFFLINE=0 SCALINO_CACHE= scalino lock .`

## Wrapping scalino

Some tools sit on top of scalino. A deployment CLI that patches the binary is
one example. They can leave rebuilding, restarting and shutting down the
program to `scalino run -w`, and hook into it in two places.

| flag | directive | does |
|---|---|---|
| `--post-link <cmd>` | `//> using postLink <cmd>` | run `<cmd>` on the linked binary, once per link |
| `--run-env KEY=VALUE` | `//> using runEnv KEY=VALUE` | extra environment for the program `run`/`test` start |

```sh
scalino run -w server --post-link "snunit bundle" --run-env PORT=8080 --run-env LOG=debug
```

### `--post-link`

The hook runs after every successful link of a Scala Native executable. That
means `run`, `package`, `test` and each rebuild under `-w`. It runs before the
program starts and, for `package`, before `--format` wraps the binary.

The binary's absolute path is passed as the last argument and as
`$SCALINO_OUTPUT`, so `--post-link "snunit bundle"` runs
`snunit bundle /abs/path/to/bin`. The command is split on whitespace (quotes
group words, nothing is expanded) and runs in the directory scalino was started
in. Repeat the flag to run several hooks in order.

- A non-zero exit fails the build with
  ``post-link command `snunit bundle` failed with exit code 2``, and the binary
  is removed. Under `-w` that is a failed build like a compile error: the
  previous program keeps running.
- The hook always gets a fresh copy of the link output. Relinks and no-change
  rebuilds start from the unhooked file, so a rebuild never hooks a binary
  twice and never skips the hook.
- Each cross target (`--native-target-triple`) is hooked too, with its own
  path.
- A Scala.js build produces a `.js` file and not an executable, so the hook is
  skipped there.

### `--run-env`

The variables are applied on top of scalino's own environment, and again after
each restart under `-w`. A flag wins over a directive for the same key. They
also reach the node process of a Scala.js build.

### Signals under `run -w`

SIGINT and SIGTERM sent to scalino are forwarded to the program. A terminal's
Ctrl+C already reaches the program directly, so SIGINT is only forwarded if the
program is still alive after 2s. scalino then waits for it and exits with its
exit code, or with 128 + the signal if nothing was running. A second signal
kills the program.

A restart sends the old program SIGTERM and waits for it to exit before
starting the new one, so a program that binds a port never overlaps with its
successor. One that ignores SIGTERM for 10s is killed.

## Status

The core toolchain works: Scala 3 to NIR to a native executable, with inline
and quote macros. The interpreter that expands them was written for scalino and
is tested against macro fixtures from upstream's test suite, including
quote-expression and quote-type patterns (`case '{ ... } =>`,
`case '[List[t]] =>`) and case-class/`UnApply` deconstruction.

The LSP has been used on hand-written projects, on a real multi-package
third-party project, and end to end in Zed.

Known gaps:

- The interpreter (`repl`, `run --interpret`) covers common Scala and stdlib
  code, but not all of the JDK: `BigInt`/`BigDecimal` arithmetic, for one, is not
  supported yet.
- Scala.js `package --format`. The deb/rpm/docker/brew wrapping is Native only.
- JUnit-style `@Test` discovery. Test frameworks must use
  `SubclassFingerprint`, as munit, utest, scalatest and zio-test-sbt do.

[`docs/findings.md`](docs/findings.md) has the full list of what is verified,
blocked and pending.

## Build from source

You need JDK 21+, `sbt`, `clang`, `coursier` (`cs`) and `git`. The JVM is for
building scalino. Using it never needs one.

```sh
./build/all.sh
```

This clones `scala/scala3` and `scala-native/scala-native` into `vendor/`, at
the versions pinned in `versions.env`, and applies the patches from `patches/`.
It produces `dist/scalino-dotc`, `dist/scalino-lsp`, `dist/scalino-linkdriver`
and `dist/scalino`.

`bin/scalino-bootstrap` is the plain bash wrapper that `scalino` itself was
bootstrapped from:

```sh
./bin/scalino-bootstrap build examples/Hello.scala -o hello && ./hello
```

## Releasing

Push a `vX.Y.Z` tag. The [release workflow](.github/workflows/release.yml)
builds `dist/` for every supported platform and architecture and publishes a
GitHub Release. If one platform fails, the others still publish.

```sh
git tag vX.Y.Z && git push origin vX.Y.Z
./build/10-update-package-metadata.sh vX.Y.Z   # once assets are published; commit the result
```

## License

Apache License 2.0, see [`LICENSE`](LICENSE). scalino's binaries are built
from patched checkouts of the [Scala 3](https://github.com/scala/scala3) and
[Scala Native](https://github.com/scala-native/scala-native) toolchains, both
Apache-2.0. scalino's changes are in [`patches/`](patches/), and the upstream
NOTICE and attribution content is reproduced in full in [`NOTICE`](NOTICE).
