# Changelog

What changed in each scalino release. The section for a version is the body of its GitHub release.

## v0.0.35 (unreleased)

### Code coverage

`scalino test --coverage` and `scalino run --coverage` build your program with the Scala 3 compiler's coverage instrumentation, run it and print a per-file table.

- `--coverage-report` writes html, lcov, cobertura, scoverage or json.
- `--coverage-fail-under` (plus `-branches` and `-file` variants) fails the command below a threshold.
- `scalino coverage` reports again from the last run.
- Every flag has a `//> using coverage.*` directive, plus `coverage.exclude` and `coverage.excludeClass`.

Works on Scala Native and Scala.js, with `dependsOn` modules, and with `run -w` (a report each time the program ends). The plain build stays incremental: instrumented classes go to their own directories.

### Scala Native 0.5.13 and 4-byte object headers

- Scala Native is now 0.5.13. It brings soft and phantom references and a reworked RTTI.
- Object headers are 4 bytes again. A heap of 5M small objects and 2M Integers takes 279MB, against 399MB without compact headers.
- Locking works like HotSpot's lightweight locking, and the old 1023-thread and 32K-monitor limits are gone. An uncontended lock is 1.8x cheaper than before.
- Breaking: `--native-compact-byte-arrays` and `//> using nativeCompactByteArrays` are gone. Byte arrays always keep their data where jsoniter-scala expects it. An old directive only prints a warning.

### JDK 21 API

Code now compiles against the JDK 21 API instead of JDK 17's, so virtual threads (`Executors.newVirtualThreadPerTaskExecutor()` and friends) are available.

### VS Code extension on the Marketplace

The extension is on the VS Code Marketplace as `lolgab.scalino`, so you no longer need to install a `.vsix` by hand. Its settings and commands moved from `scalino-lsp.*` to `scalino.*`. Rename them in your settings if you set any. Future releases update the extension only when it changes.

### Fixes

- macOS Intel: multithreaded programs could crash with SIGILL when the GC stopped a thread. The safepoint signal handler overwrote part of the kernel's signal frame.
- `scalino-lsp` no longer fails every request with "Could not find package scala" after `brew upgrade`. It reruns `setup-ide` when its config points at a deleted install, and picks up a new config without a window reload.

## v0.0.34 (2026-11-23)

### Multi-module builds

`//> using dependsOn ../core` splits a project into modules. Each module compiles on its own, with its own dependencies. See the README's Modules section.

### `//> using toolkit`

scala-cli's syntax: `//> using toolkit default` (Scala Toolkit 0.9.2), `latest`, or a version, and `test.toolkit` for test scope. `typelevel:` picks the Typelevel Toolkit.

The Scala Toolkit works as in its tutorials (os-lib, upickle, sttp, munit). On Linux, sttp needs `libcurl4-openssl-dev` and `libidn2-dev`. On the Typelevel Toolkit, circe's `derives Codec.AsObject`, http4s's `uri"..."` and weaver's source positions don't expand in the macro interpreter yet; the README lists workarounds.

### A better `scalino repl`

- Multi-line editing, syntax highlighting as you type, Tab completion, history in `~/.scalino_history`, bracketed paste.
- Values print with pprint: `P(x = 1, name = "a")`, case objects by name, `Range(1, 2, 3)`.
- `scalino run --interpret` works for code in packages.

### Macros

- Macros can read classpath resources (`getClassLoader`, `getResources`), so Dumbo's `withResourcesIn` expands.
- `Position.ofMacroExpansion` is the inline call site. munit's `Location` now points at your test instead of munit's `MacroCompat.scala` (#13).

### Fixes

- `scalino-lsp` no longer crashes with SIGSEGV in release builds (codegen marked pointers that may hold tagged small integers as dereferenceable).
- Regexes accept `\e`. dotc uses it to strip ANSI codes whenever a `@nowarn("msg=...")` or `-Wconf:msg=` filter is in play, so those projects crashed with `PatternSyntaxException`.
- The Windows build of `scalino-dotc` links again.

### Other

- CI builds the compiler and the LSP with a released `scalino-dotc` (#12).

## v0.0.33 (2026-11-22)

### `scalino repl`, `run --interpret` and scripts

- `scalino repl` runs on the macro interpreter, with one warm compiler kept between inputs.
- `scalino run --interpret` runs a program's TASTy on the interpreter instead of linking it. Code that needs native-only features falls back to linking.
- `.sc` scripts work: the body is wrapped in a main method.

An edit-and-run cycle on a small program goes from 3.1s to 0.29s.

### `publish-local` and `publish`

- `scalino publish-local` writes the jar (`.tasty` + `.nir` or `.sjsir`), sources and javadoc jars, POM and `ivy.xml` to `~/.ivy2/local` (or `~/.m2` with `--m2`), so another project can depend on it with `//> using dep org::name::version`.
- `scalino publish` uploads a signed bundle to the Sonatype Central Portal, PUTs to other Maven repositories, or writes a Maven layout to a directory. It reads scala-cli's `publish.*` directives and flags.

### Warm compiler daemon

scalino now keeps a `scalino-dotc` process running per classes directory, so a small edit doesn't reload the classpath. One-file edits go from 0.67s to 0.22s on a mid-sized project. Turn it off with `--no-daemon` or `SCALINO_DOTC_DAEMON=0` (it is off by default under `$CI`). `scalino daemon status|stop` manages it.

### Faster macros

The macro interpreter got several rounds of work: less work and allocation per call, `return` without exceptions, and caches shared across expansions. Projects that use macros heavily compile noticeably faster.

### Faster linking and runtime

- Interflow allocates much less per block: about 20% less time optimizing natively, same output.
- Idle GC mark workers block instead of spinning: around 35% less system time during links.
- Module loads and stack-overflow checks are inlined: about 7% faster on a compiler-heavy workload.
- Direct codegen respects `-f[no-]omit-frame-pointer` from `--native-compile`, so frame-pointer profilers like macOS `sample` show real stacks.
- Fixed `currentSize` going stale in Interflow.

### Other

- The Homebrew formula is published to `lolgab/homebrew-tap`: `brew install lolgab/tap/scalino`.
- New README, editor READMEs and website.

## v0.0.32 (2026-11-16)

### Fixes

- **File descriptor leak in test discovery.** `ZipFile` leaked its file when the constructor threw (for a directory or a non-zip file). Test discovery hit that once per class on a classpath with a resource directory, and a project with ~450 tests ran out of descriptors on macOS: every later process spawn failed with `Could not adddup2 pipe file descriptor 0 [errno=9]`. `walkFileTree` also closes its directory streams now when a visitor returns `TERMINATE` or throws.
- Test discovery skips classpath directories when reading test classes from jars.

### Windows

Direct LLVM codegen now works for the Windows gnu ABI and is on by default there, as on the other platforms.

## v0.0.31 (2026-11-15)

### Windows

scalino now runs on Windows and builds for it, on x86_64 and aarch64 (gnu ABI, llvm-mingw). Install with `install.ps1` or the release zips.

### Several main classes in one `package`

`scalino package --main-class A,B` (or `//> using mainClass A B`) compiles once and links one binary per main class in a single linkdriver process. Each main class keeps its own incremental state, so building one never throws away another's. In watch mode, every main class and target triple now relinks incrementally. `run`, `--format` packaging and Scala.js still take a single main class.

### Faster GC

With multithreading on, Immix now runs marking, sweeping and evacuation on `GC_NPROCS` threads (default: number of processors, capped at 8). It is still stop-the-world. Linking http4s: GC pauses 6.5s -> 2.6s, link 13s -> 9s. The parallel marker also prefetches, which helps most on scattered heaps.

### Cross-compilation

- Linux musl and gnu sysroots include zlib.
- macOS sysroot generates `sys/posix_sem.h`, needed by Commix.
- Sysroots are rebuilt when their recipe changes.
- Windows `time.c` uses the CRT's `*_s` functions under UCRT.
- `connect()` is decided by `SO_ERROR` instead of `POLLIN`.
- `--native-*` flags now win over `//> using` directives.
- Silenced the `llvm-config` probe.

### Other

- The `_2.13` Scala Native runtime jars are excluded from dependencies, like the `_3` ones.

## v0.0.30 (2026-11-11)

### Faster watch mode

- Relinks after a small edit take 0.3-0.4s instead of 1.0-1.4s. The classpath is no longer re-walked on every relink, Interflow really is incremental now (before, every optimized method looked changed), and native-library jars are scanned once per session.
- Fixed a stale binary when two relinks happened within the same second.
- Sources are polled every 50ms instead of every 500ms, and a build starts once files stop changing, so an editor that truncates then writes no longer triggers two builds.
- Opt-in Lower cache: `SCALINO_LOWER_CACHE=1` (or `verify` to check it against a full Lower).

### Faster one-shot links

A warm hello-world link goes from ~1.1s to ~1.0s: faster SHA-1, jar stamps that skip rehashing unchanged native-library jars, zip scans of classpath jars instead of `Files.walk`, and classes from the previous build parsed in parallel before Reach starts (`SCALINO_REACH_PREFETCH=0` turns that off).

### Smaller `.scalino-build`

- Native-library jars are unpacked without `.nir` and `.tasty` files (35MB -> 3.5MB for hello world).
- `link/<main>` is a hard link to the native binary instead of a second copy, and the `bin` copy is a copy-on-write clone where the filesystem supports it.

### Fixes

- Direct codegen no longer gives `externRedirect` wrappers the bare C name.
- `BasicMonitor.inflate` fixed with compact object headers.
- libunwind's arm64 sysctls are cached.

### Other

- Rebased on Scala Native main (ae1823a).
- The VS Code extension is built and published on `vscode-v*` tags.
- Docs and comments cleaned up (#11).

## v0.0.29 (2026-11-05)

- Existing posixlib and clib externs keep their Scala Native 0.5.12 link names.
- Dropped the `CompletableFuture` fix; it is upstream now.

## v0.0.28 (2026-11-04)

- Rebased on Scala Native main and Scala 3.10.0 (#9), with fixes for two 3.10.0 compiler bugs.
- Fixed an escape analysis miscompile: when a caller's memory is collapsed, it must escape.
- The link cache takes link-time `SCALANATIVE_*` environment variables into account.

## v0.0.27 (2026-11-01)

- `--post-link <cmd>` / `//> using postLink` runs a command on every linked native binary.
- `--run-env KEY=VALUE` / `//> using runEnv` sets environment variables for `run` and `test`.
- `run -w` forwards SIGINT/SIGTERM to the program and exits with its exit code.
- Scala.js: `jsClientOut` writes the client to a directory instead of embedding it, with scala-cli's module split styles. A jsClient follows the server's native mode, and `scalino test` builds and embeds it.
- Scala.js is documented in the README and on the website.

## v0.0.26 (2026-10-31)

### Scala.js

scalino can now target Scala.js, with the Scala.js linker compiled to Scala Native (`scalino-jslink`), still no JVM.

- `--js` or `//> using platform js` for `run`, `package`, `compile`, `test`, `setup-ide` and `lock`, with scala-cli's flags, directives and defaults.
- `jsClient`: embed a Scala.js project in a Scala Native server as `/client/main.js`. `shared` sources compile into both.
- Watch mode keeps one Scala.js linker process alive.
- Base libraries are available offline.

### Other

- `run -w` runs the program in the background and restarts it after each successful build. A failed build leaves the old one running.
- Shell completion for the Scala.js option values.

## v0.0.25 (2026-10-28)

- Debug info is opt-in: `--native-debug-info` or `//> using nativeDebugInfo true`.
- `package` writes its output to a temp file and renames it, so an interrupted build never leaves a half-written binary.
- Fewer allocations in the linker, and GC evacuation skips cycles that would free little.

## v0.0.24 (2026-10-22)

- The macro interpreter runs JDK method bodies from Scala Native's javalib TASTy, so it needs far fewer hand-written intrinsics.
- Faster macro interpreter: intrinsics are pre-filtered by method name, partial functions are probed without stack traces, and name checks don't use regexes.
- `scalino-dotc`, `scalino-linkdriver` and `scalino-lsp` are linked with LTO (thin on linux-x86_64, full elsewhere).

## v0.0.23 (2026-10-18)

- chimney 2.x macros work (#7). Fixed an enum crash under `-Yexplicit-nulls`.
- Macro expansions reuse one big-stack thread instead of starting a new one each time.
- Fixed running out of memory in the monitor table: thin lock owner ids are leased per live thread.
- `test` detects the test directory scope correctly. Fixed "File name too long" in cache keys.
- Nix: `mkScalinoApp` installs the binary for host builds.
- The apt/dnf repositories keep only the latest two releases.

## v0.0.22 (2026-10-16)

### Profile-guided builds

`scalino-linkdriver`, `scalino-dotc` and `scalino-lsp` are built with profile-guided optimization, both in LLVM and in Interflow's inlining. The compiler is about 13-14% faster.

### Macros

chimney macros expand now, and the interpreter handles type tests on host arrays.

### Other

- GC: fixed Immix heap growth thrashing on big links. aarch64 safepoint trampoline resumes through sigreturn.
- Nix: cross-compilation works, using scalino's sysroots.
- macOS sysroot generates a `libobjc.tbd` stub, so cross-built macOS programs can link libobjc.

## v0.0.21 (2026-10-10)

- scalino-lsp: main and test sources are separate projects (test dependencies only resolve in test code), and references and rename search the whole workspace.
- Cross builds fetch `ld.lld` when the system doesn't have one.
- macOS sysroot exports plain `realpath`, `syslog` and `dispatch_*` names.

## v0.0.20 (2026-10-10)

- Cross targets get static OpenSSL, libidn2, s2n-tls and libcurl when a dependency on the classpath needs them, or ahead of time with `scalino sysroot build <triple> --with <lib>`.
- scalino-lsp: go-to-definition works for symbols in project files the editor hasn't opened, and the server stays idle in workspaces without `.scalino-build/scalino-lsp.json`.
- Windows: file type probes no longer use POSIX `S_IS*`.

## v0.0.19 (2026-10-08)

- scalino-lsp uses much less memory: 1.8GB -> ~640MB after 300 completions, thanks to GC evacuation, heap growth based on live data, and recycling the presentation compiler when idle.
- Watch mode runs the long-running linkdriver with evacuation on, and Immix returns free blocks to the OS.
- Nix: pkg-config support for native C dependencies.
- Macro interpreter: structural quote type patterns, more of the `quotes.reflect` API, a fix for by-name `Option` defaults, and `Character.getNumericValue`.

## v0.0.18 (2026-10-07)

### Incremental compilation and watch mode

- Stale `.nir` and `.tasty` files are removed when a file is deleted or its declarations change, including `@main` methods.
- Watch mode re-resolves sources on every poll, so renaming, deleting or adding files doesn't crash it.

### Cross-compilation

- `package --native-target-triple` builds missing sysroots automatically.
- Every non-host Linux target links with lld. Fixed cross builds from a Linux host.
- Shell completions cover target triples and sysroot arguments.

### Other

- Escape analysis specializes on exact argument classes.
- Fixed a GC crash on x86_64: the safepoint trampoline now preserves `%r10` and the red zone.
- scalino-lsp: go-to-definition keeps working after you close a jar source buffer.
- `--help` colors directives, inline code and flags like scala-cli.
- `setup-ide` creates `.scalino-build/` first and no longer creates an unused directory.
- Recursive delete doesn't follow symlinks.

## v0.0.17 (2026-10-06)

### Cross-compilation

- `--native-target-triple` builds for glibc and musl Linux, macOS and Windows (mingw), x86_64 and aarch64.
- `scalino sysroot build <triple>` puts the sysroot together on your machine from pinned upstream files (Debian packages, Zig's Darwin headers, llvm-mingw), using only curl and tar.

### Packaging

`scalino package --format deb,rpm,docker,tar,brew`. Metadata comes from `//> using package*` directives or `--pkg-name`/`--pkg-version`/`--release-url`.

### Optimizer

- Escape analysis: objects that don't escape go on the stack. On by default in release modes.
- `--native-prune <class|package|Class#method>` replaces method bodies with a throw at link time and drops whatever only they reached.

### GC

Evacuation fixes: compact headers, large objects, identity-hashed objects, interior pointers, and a safepoint trampoline that keeps the CPU flags.

### Editors

- Editors offer to run `scalino setup-ide`, and fail fast when the LSP can't warm up.
- Inlay hint kinds can be toggled in editor settings.
- scalino-lsp skips hidden and build directories, and no longer fails on a null `rootUri`.
- The editor extensions are now named "Scalino".

### Other

- `package` fails fast when `-o` is a directory.
- A long-running linkdriver restarts after a failed link.
- `install.sh` makes no API call for pinned versions, supports a token, and reports rate limits clearly.
- `Inflater`/`Deflater` fix.

## v0.0.16 (2026-10-03)

### Compact object headers

Objects now start with a single 4-byte header (lock and class id) instead of an rtti pointer plus a lock word. Smaller objects, on by default.

### Hermetic builds

- `scalino lock` writes `scalino.lock.json` with the URL and sha256 of every dependency jar. When the lock is present, dependencies are resolved from it.
- `--offline` / `SCALINO_OFFLINE=1`, and `SCALINO_CACHE`.
- Nix flake helpers: `mkScalinoCache`, `mkScalinoApp`, `mkScalinoDevShell` (#4).

### CLI

- Output wording and colors match scala-cli for compile, watch mode and diagnostics.
- Each command has its own `--help`.
- Boolean flags like `-w` are accepted before the sub-command.
- `test --test-only <glob>` links only the selected test classes.

### Faster watch mode

The linkdriver stays alive across watch-mode rebuilds and keeps its parsed NIR and reachability state in memory.

### Other

- `.tasty` files are written next to `.nir`.
- scalino-lsp reads dependency sources straight from jars.
- GC: one shared yield-point trap page instead of one per thread, and the heap doubles up to 1GB before it grows more slowly.
- `Files.isDirectory`/`isRegularFile`/`isSymbolicLink` call `stat` instead of throwing.

## v0.0.15 (2026-09-29)

- scalino-lsp bundles the javalib and nativelib sources jars, so go-to-definition on `java.*` opens Scala Native's own sources.
- `Ptr[T]` values are boxed as tagged pointers. A null `Ptr` no longer gets corrupted when boxed.
- `ArrayBuilder` use sites avoid boxing through `Array.newBuilder`.
- Commix GC gets the same fixes as Immix.

## v0.0.14 (2026-09-24)

- `--native-optimize=false` (or `//> using nativeOptimize false`) turns off Scala Native's optimizer.
- The GC heap histogram is opt-in, with `--heap-histogram`.
- Closed the remaining gaps in tagged-pointer boxing: no real allocation, and using a tagged box as a monitor throws.
- scalino-lsp improvements.
- Nix: zstd added to the dependencies (#3).

## v0.0.13 (2026-09-23)

### Smaller, faster runtime

- Small boxed primitives (`Boolean`, `Byte`, `Short`, `Char`, `Int`, `Float` and the unsigned types) are tagged pointers instead of heap objects on 64-bit targets.
- `--gc-stw-sweep` keeps threads paused through Commix's parallel sweep, which trades concurrency for throughput. Immix grows its heap more slowly.
- Faster linking: vtable signatures aren't re-sorted on every read, the control-flow graph is built in linear time, and Interflow's maps use `Long` keys.

### CLI

- `scalino completions` writes shell completions. They are included in every package.
- `--no-incremental` turns off Scala Native's incremental linking too.
- Incremental compilation works with direct codegen, and builds no longer bust the cache by being non-deterministic.

### Language server

scalino-lsp shuts down when idle, and Neovim clients reattach when it restarts.

### Other

- Linux CI uses LLVM 20 from apt.llvm.org.
- New heap viewer for GC heap graph dumps (development tool).

## v0.0.12 (2026-09-21)

### Faster linking

- Native-library jars are unpacked without their `.class` files, and the unpacked jars are cached by hash again.
- Less monitor and thread-local contention, found by profiling `scalino package`.
- Hash codes of NIR values, types and instructions are cached, and the linker's hot maps use `Long` keys.
- No thread is spawned per LLVM subprocess call.
- The compiler stops stat'ing the filesystem on every `Symbol#source`.

### Other

- libLLVM is statically linked into `scalino-linkdriver`.
- The multithreading flag is gone: it is always on.

## v0.0.11 (2026-09-19)

- The direct codegen flags dropped "experimental" from their names.
- Direct codegen no longer falls back to `.ll` files, and the "Produced N LLVM IR files" log line is correct.
- CI installs libLLVM for the linkdriver, and release deploys no longer overwrite the home page.

## v0.0.10 (2026-09-18)

### Direct LLVM codegen, on by default

The linker now builds LLVM IR in memory through the LLVM C API instead of writing `.ll` files and running clang on each one. It supports debug info, C++ exceptions and multithreading, so real programs no longer fall back to the old path. Multithreading is on by default too. Both can be turned off with `=false`.

### Macros

Fixed four macro interpreter bugs hit by jsoniter-scala. `writeToString` works now.

## v0.0.9 (2026-09-17)

- `compile` rejects `-o`/`--output`, which only `package` uses.
- Macro interpreter: quote patterns now match lambda-shaped quote bodies.
- New landing page, deployed to GitHub Pages.

## v0.0.8 (2026-09-17)

- Colored output, with a `--color` flag.
- `scalino clean`.
- `scalino version` shows the real scalino and Scala Native versions.
- macOS: `scalino` is re-signed after linking, so the OS no longer kills it on launch.
- Commands that aren't implemented are gone from `--help` and the README.

## v0.0.7 (2026-09-16)

- Neovim support for scalino-lsp.
- scalino-lsp adds type definition, prepare rename, selection range, inlay hints, semantic tokens and code actions.
- Compiler errors show their code as `E###`, with a link to its docs page.

## v0.0.6 (2026-09-15)

- coursier's `cs` launcher is bundled as `scalino-cs`, so `cs` no longer has to be on `PATH`.

## v0.0.5 (2026-09-15)

Release pipeline only: apt/dnf repositories are published from the release workflow, and the Windows build no longer gates releases.

## v0.0.4 (2026-09-15)

- VS Code extension for scalino-lsp.
- `scalino` with no source arguments uses the current directory.
- `setup-ide` puts `scalino-lsp` on `PATH` and no longer writes the editor's `settings.json`.
- scalino-lsp: diagnostics are debounced on edits, completion items carry more fields, and requests wait instead of failing while the presentation compiler is still starting.

## v0.0.3 (2026-09-14)

### Fully native toolchain

`scalino-linkdriver`, `scalino-dotc` and `scalino-lsp` are now built with Scala Native instead of GraalVM native-image. native-image is gone from the build.

### Language server

`scalino-lsp` now runs on the Scala 3 presentation compiler, the same engine Metals uses. Go-to-definition opens the sources jar of a dependency. The log file moved to `.scalino-build/`.

### CLI

- `compile` and `package` are separate commands, as in scala-cli.
- `package` names its output after the main class by default.
- No more busy-waiting in the CLI: multithreading is on.
- Faster change detection for incremental compilation: each file is tokenized once instead of matched with a regex per name.

### Other

- Scala 3.9.0.
- Jars are read through a real `ZipFileSystemProvider` instead of being extracted.
- Fixed a busy-spin in `CompletableFuture`.
- Nix: libz added to the dependencies (#1).

## v0.0.2 (2026-09-08)

- `scalino` and `scalino-dotc` are linked in release-size mode instead of debug, so they are smaller and faster.

## v0.0.1 (2026-09-07)

First release of scalino: a Scala 3 to Scala Native toolchain that runs without a JVM. The compiler, the linker and the CLI are all native binaries.

### What's in it

- `scalino` CLI modelled on scala-cli: `compile`, `run`, `package`, `test`, `setup-ide`, with most of scala-cli's Scala Native options and `//> using` directives (`dep`, `file`, `exclude`, `repository`, `resourceDir`, ...). All three dependency coordinate formats work.
- Macros run on scalino's own TASTy interpreter instead of a JVM. Tested against munit, upickle and jsoniter-scala derivation.
- Incremental compilation: changed files and the files that reference them are recompiled. `--no-incremental` forces a full build.
- `scalino test` runs sbt test-interface frameworks without a JVM. Test sources are kept apart from main sources, as in scala-cli.
- `scalino-lsp`, a language server that runs without a JVM: diagnostics, hover, go-to-definition (including into dependency sources), references, rename, document and workspace symbols.
- Zed extension.
- One-line installer (`install.sh`) and sha256-checksummed release tarballs. Packaging for Linux `.deb`/`.rpm`, Homebrew, Arch and Nix.
- Scala 3.8.4.
