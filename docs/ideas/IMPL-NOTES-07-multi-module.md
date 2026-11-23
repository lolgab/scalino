# Multi-module projects (findings "Remaining work" item 4)

## Plan

### Syntax

scala-cli has no module graph (one project per invocation), so there is nothing to be
compatible with. Kept to one directive, in the style of the existing `jsClient`/`shared`:

```scala
//> using dependsOn ../core      // relative to the declaring file (or the cwd), like jsClient
```

- A **module** is a directory. Its sources are the directory's `.scala` files (same scan rules
  as `scalino run <dir>`: `file`/`exclude` directives, `test/` = test scope), its directives are
  the `//> using` lines in those sources. Nothing else to declare: no build file.
- The **root module** is what the command line names (`scalino run .`, `scalino run app/`).
- A module's own nested modules are not part of it: any directory reached through `dependsOn`
  is cut out of every other module's directory scan (like a `jsClient` directory already is).
- `dependsOn` is transitive and may form any DAG; a cycle is an error naming the cycle.

### Per-module semantics

| directive | scope |
|---|---|
| `dep`, `jar`, `resourceDir`, `repository` | transitive: a module sees its upstream modules' (sbt `dependsOn`) |
| `compileOnly.dep`, `options`, `test.*` | the module only |
| `platform` | the module; a module without one takes its dependent's platform, so one `core` serves a native server and a js client. A module declaring another platform than its dependent is an error |
| `mainClass`, `native*`, `js*` link options, `postLink`, `runEnv`, `package*` | root module only (they describe the program being linked) |

### Build

- Upstream modules compile in dependency order (topological), main scope only, each into its own
  `.scalino-build/modules/<dirname>-<hash of abs path>/classes[-js]` with its own incremental
  manifest. The root keeps today's `_scratch/classes` path, so single-module builds are unchanged.
- A module's compile classpath = its classes dir + its upstream modules' classes dirs + the
  resolution of the union of its closure's `dep`s (one coursier call per closure, cached as today).
- Cross-module incremental invalidation: `compileToClasses` notices classpath entries that are
  module outputs (a classes dir with an `.incr-manifest` sibling), keeps a snapshot of their
  manifests (`<classesDir>.upstream`), and on the next build treats the top-level names declared in
  upstream files that changed/appeared/vanished as changed names, invalidating the files that
  mention them (same text-level graph as within a module). No snapshot yet: full rebuild.
  The dotc daemon's warm symbols for upstream classes would be stale after such a change: then the
  daemon generation is dropped (warm state discarded, compile runs cold in the daemon).
- run/package/test link the root's classes + every upstream classes dir.
- `compile` compiles every module.
- `test` tests the root module (its tests can use upstream code); `scalino test core` tests `core`.
- `jsClient` directories may use `dependsOn` too (resolved with platform js), which generalizes
  `shared`.
- `-w` watches every module's sources.
- `setup-ide`: one LSP project per (module, platform), `dependsOn` = upstream module ids
  (scalino-lsp already puts dependsOn projects' sources on the PC source path and searches them for
  references/rename).
- `lock`: the dep sets resolved for modules are covered.

### Steps

1. Module graph discovery + exclusion of module dirs from directory scans.
2. Compile upstream modules; merge their deps/jars/resources into the root's classpath in
   run/compile/package/test/js/jsClient.
3. Cross-module incremental invalidation (+ daemon generation reset).
4. setup-ide, watch, lock.
5. Tests under tests/modules/, README + findings.

### Risks

- Name-based invalidation across modules is as approximate as within one (conservative, may
  over-compile; never under-compiles for top-level names).
- Up to 3 dotc daemons (LRU) are shared by all modules' classes dirs; with many modules being
  edited at once they evict each other (correct, just colder).

## Done (2026-11-21)

Implemented as planned; commits on `impl/07-multi-module`.

- `cli/Modules.scala` (new): `Module`, `resolve` (graph, cycle/platform/missing-dir errors,
  dependency order), `withUpstream` (directives passed down), `classpathOf`, `compileAll`,
  `prepare` (what every command calls), `watchedSources`.
- `cli/ScalinoCli.scala`:
  - `dependsOn` directive (`Directives.dependsOn`, absolute dirs); `--help` lists it.
  - `expandSources` cuts every directory reachable through `dependsOn` out of the scan
    (`moduleDirsReachableFrom`), except one the command line names or that holds it.
  - `buildAndMaybeRun`, `buildJsAndMaybeRun`, `buildJsClient` (client modules built for js),
    `handleTest`, `testJs`: `Modules.prepare` first, module classes dirs first on the class path.
  - watch (`run -w`, `test -w`): module sources (and a jsClient's modules) are watched.
  - `lock`: each module's closure set (and compileOnly set) is locked.
  - `setup-ide`: a project per module (`id` = the module directory's path from
    where setup-ide runs, `js:` in front for js, `./` kept for a top-level `main`/`test`/`client`;
    a function of the module alone, so adding or renaming another module never changes it), `dependsOn` set on
    main/test/client/module projects. Module classes dirs are not on LSP class paths (sources come
    through `dependsOn`, as for `test` -> `main`).
  - `compileToClasses`: upstream snapshot + changed-name invalidation, daemon generation reset when
    upstream changed, `what` label ("Compiling module core (...)").
  - `findClassInfo`: classpath directories are looked up too (test discovery across modules).
- Incremental-compiler fixes found on the way (also single-module):
  - a failed compile blanks the hash of its batch's manifest records (reverting a breaking edit
    left the dependents' purged outputs missing; seen as "Unknown type core.Greeting" at link);
  - column-0 top-level `def`/`val`/`var`/`type`/`given` names (`FileRecord.terms`, a 5th manifest
    column; old manifests still load) widen invalidation.
- build/07 and build/13 compile `cli/Modules.scala`.
- `tests/modules/run.sh`, README "Modules" section (+ Use bullets, jsClient note, Editor support),
  findings item 4.

### Verification

Dev loop: worktree `dist/` = symlinks to the main checkout's dist (toolchain) except `scalino`,
`.build-work/{compiler.cp,nativelibs.cp,nscplugin.jar.txt}` copied; `bash build/07-build-scalino.sh`
(under the heavy-build lock) builds `dist/scalino` from this worktree.

```
$ tests/modules/run.sh
== diamond graph, modules compiled in dependency order
== no change: nothing recompiles
== an upstream edit nobody uses stays in its module
== an upstream change reaches its dependents
== a breaking upstream change fails in the dependent; reverting it recovers
== a changed top-level def reaches its callers (a compile error, not a link error)
== test: the root's tests see its modules
== package and compile
== setup-ide: one LSP project per module
== graph errors
== one module for a Scala Native server and its Scala.js client
OK
```

Regressions: `tests/dotc-daemon/run.sh` OK, `tests/native/run.sh` OK, `tests/js/run.sh`
"OK: Scala.js smoke tests passed" (the trailing "Terminated: 15" line is its server cleanup).

By hand (scratch fixture app -> core -> util, app -> util, util has upickle):
- `SCALINO_DOTC_DAEMON=verify` over three successive edits of `util` (daemon output compared to a
  cold compile each time): no difference; `daemon status` shows one daemon per module dir.
- `scalino run -w app`, edit `util/Util.scala`: util, core, project recompile, program restarts
  with the new output.
- `scalino lock server` on the native-server + js-client + shared `model` fixture: 2 sets
  (native upickle, js base + upickle); after deleting `.scalino-build/deps-cache`, `run server`
  resolves nothing (all from the lock).
- scalino-lsp (main dist) driven over stdio with the generated config: `definition` on `Greeting`
  in `app/Main.scala` -> `core/Core.scala:3`; hover `case class Greeting`; `references` from
  `core/Core.scala` -> `app/Main.scala`, `app/test/MainTest.scala`, `core/Core.scala`.

### Decisions taken (Lorenzo may want to revisit)

- Directive name `dependsOn` (sbt's word and the LSP config field); no CLI flag equivalent.
- `scalino test app` runs app's tests only, not those of its modules (`scalino test core` for
  core's). An aggregate `test` would link one test binary per module.
- `--option`/`-O` from the command line apply to every module; `--dep` only to the root.
- Each module resolves its closure's deps on its own; the root resolves the union. Versions can
  therefore differ between what a module was compiled against and what is linked (sbt eviction,
  same as there).
- After an upstream change the dependents compile cold in their daemon (warm symbols from the
  upstream classes dir would be stale). Unlinking just the changed upstream symbols in the daemon
  (as it already does for the project's own) would keep them warm: needs a scala3-0045 change.

### Remaining / known limits

- Invalidation stays text-based: methods of a multi-line `extension` block and indented
  top-level definitions are not tracked as names.
- `_scratch/classes` is still shared by whatever is the root (pre-existing: `run a` / `run b` /
  `test` thrash it); modules have stable per-directory outputs.
- Not verified: Windows (paths are built with `Path`, `CP_SEP` used throughout), cross targets
  (`package --native-target-triple` only sees the extra class path entries, nothing module-specific).
