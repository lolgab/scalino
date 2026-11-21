# 08: `scalino publish-local` / `scalino publish`

## Plan

### Design

New file `cli/Publish.scala` (compiled into the CLI by build/07), entry points
`Publish.handle(args, local)`, dispatched from `ScalinoCli.run` for `publish-local`,
`publish local` (scala-cli spelling) and `publish`.

1. **Build**: same front half as `scalino compile` (directives, deps, platform), then
   `compileOnly` into `.scalino-build/_scratch/classes[-js]`. Main sources only (test
   sources are excluded, as in `compile`).
2. **Artifacts**, written by an in-process zip writer (CRC32 + raw Deflater, fixed
   timestamps so the bytes are reproducible) under `.scalino-build/publish/`:
   - main jar: everything dotc wrote (`.tasty` + `.nir` for Native, `.tasty` + `.sjsir`
     for Scala.js; this toolchain has no JVM backend, so no `.class`) plus the contents of
     `//> using resourceDir`, and a `META-INF/MANIFEST.MF`;
   - `-sources.jar`: the main `.scala` sources, paths relative to the project directory;
   - `-javadoc.jar`: manifest only (no scaladoc tool without a JVM; Central accepts an empty one);
   - `.pom`, and for ivy repositories `ivy.xml`.
3. **Naming**: `<name>_native0.5_3` / `<name>_sjs1_3` (from BuildInfo), like the
   `org::name::version` dependency form resolves. The POM/ivy lists the platform runtime
   libraries sbt-scala-native / sbt-scalajs would (scala3-library_3, scala3lib_native,
   nativelib, javalib, auxlib, clib, posixlib, windowslib / scala3-library_sjs1_3,
   scalajs-library_2.13) plus the user's `//> using dep`s, so sbt/Mill/scala-cli consumers
   get the same graph as for an sbt-published library. scalino itself excludes the runtime
   libraries when consuming (`excludedArtifacts`).
4. **Metadata**: scala-cli's `//> using publish.*` directives (`organization`, `name`,
   `moduleName`, `version`, `computeVersion`, `url`, `license`, `versionControl`/`scm`/`vcs`,
   `description`, `developer`, `repository`, `gpgKey`, `gpgOptions`, `secretKey`,
   `secretKeyPassword`, `user`, `password`, `signer`, `doc`, `versionScheme`,
   `publish.ci.*` when `$CI` is set) and the matching scala-cli flags.
5. **publish-local**: ivy layout under `~/.ivy2/local` (coursier's default `ivy2Local`
   repository, so another scalino project's `//> using dep org::name::version` finds it
   with no extra repository), `.md5`/`.sha1` next to every file. `--m2` writes the Maven
   layout under `~/.m2/repository` instead (consume with `//> using repository m2Local`
   or `-r file://...`).
6. **publish**: default repository `central` = Sonatype Central Portal: Maven-layout bundle
   zip with `.asc` signatures (gpg) + md5/sha1, uploaded with `curl` to
   `/api/v1/publisher/upload`, then the deployment status is polled until it is validated
   / published (or failed, with the portal's error messages). `-SNAPSHOT` versions go to
   the Central snapshots repository by HTTP PUT instead. Any other `https://` repository
   gets HTTP PUTs (Maven deploy layout); a path / `file:` URL gets the Maven layout
   written there. `--dummy` builds everything (incl. signing) but uploads nothing.
7. **Signing**: `gpg --detach-sign --armor` (`--local-user <gpgKey>`), or with
   `publish.secretKey` (armored or base64 of armored, scala-cli/sbt-ci-release style
   `PGP_SECRET`), imported into a throwaway `GNUPGHOME` first. `--signer none` skips.
8. **Checksums**: no MessageDigest in this javalib: MD5 is `Packaging.md5Hex`, SHA-1 is a
   new hand-rolled one in Publish.scala.

### Steps

1. Publish.scala: zip writer, sha1, pom/ivy writers, metadata resolution.
2. publish-local (ivy + m2), wire into CLI + help + completions.
3. publish: bundle, gpg, Central Portal upload + status polling, PUT repos, file repos.
4. tests/publish/run.sh: lib published locally (native + js), consumed from a second
   project via `//> using dep`; publish to a file repo; Central upload against a local
   mock server; signature verification with a throwaway gpg key.
5. README + docs.

### Risks

- Jars carry `.tasty` but no `.class`: fine for scalino (it already compiles against
  tasty-only class dirs) and for Scala 3.5+ JVM-hosted compilers (tasty-only classpath
  entries are supported for pipelining), but untested with sbt.
- Runtime-library version in the POM: this toolchain's Scala Native is a `-SNAPSHOT`
  (built from main). A released artifact must not depend on a snapshot, so the POM uses
  the last release (see "Decisions").
- Real Central upload can't be tested without publishing for real; covered against a
  mock server that checks the request shape (auth header, multipart field, query).

## Done (2026-11-21)

Implemented as planned, in `cli/Publish.scala`. Wired into `cli/ScalinoCli.scala`: dispatch
(`publish-local`, `publish local`, `publish`), `compileForPublish`, per-command help, completions,
and `publish.*` keys exempted from the unsupported-directive warning. Also added to
build/07 and build/13, plus README "Publishing libraries", docs/findings.md, tests/publish/ and a
CI step on the `js` smoke leg.

### Decisions (Lorenzo's to revisit)

- **Central publishing type defaults to USER_MANAGED** (Lorenzo's call, 2026-11-21; the first cut
  defaulted to AUTOMATIC): the deployment waits, validated, to be published from the portal, since a
  Central release can't be undone. This is also the portal's own default. `--auto-release` gives
  AUTOMATIC (sbt's `sonaRelease`).
- **Scala Native runtime version in POMs** is the release before this toolchain's snapshot
  (`0.5.13-SNAPSHOT` -> `0.5.12`), overridable with `SCALINO_PUBLISH_NATIVE_VERSION`. This is a
  heuristic. `scala3lib_native0.5_3:3.10.0+0.5.12` may not exist on Central. sbt consumers would
  evict it in favour of their own, but a strict resolver could complain. A pinned
  "last released Scala Native" value in versions.env might be cleaner.
- **Default organization**: `io.github.<owner>` from a GitHub `origin` remote (as scala-cli does),
  else `default` for publish-local; `publish` fails without one. Default name = directory name,
  default publish-local version `0.1.0-SNAPSHOT`.
- **No `.class` in the jar** (no JVM backend). Untested with sbt consumers.
- **Snapshots to Central** use plain `-SNAPSHOT` file names (non-timestamped, as sbt does) and an
  artifact-level `maven-metadata.xml`. The portal docs don't say whether timestamped names or
  version-level metadata are required. Unverified against the real snapshots repository.
- **Signers**: `gpg` and `none` only. scala-cli's `bouncycastle` needs a JVM. `publish.secretKey`
  (armored, or base64 of armored, default `$PGP_SECRET`) is imported into a throwaway GNUPGHOME
  in /tmp.
- One platform per invocation (`--js` for Scala.js). A `//> using platform native js` project
  publishes only the first platform listed. Run publish twice for both.

### Verification

Toolchain: worktree `dist/` = symlinks to the main checkout's dist (dotc, linkdriver, jslink, cs,
lib) plus a real `dist/scalino`, built with `bash build/07-build-scalino.sh` (`OK:
.../08-publish/dist/scalino`) under the heavy-build lock. `.build-work/*.cp` copied from main.

```
$ tests/publish/run.sh dist/scalino
== publish-local (Scala Native) into ~/.ivy2/local
== a second project depends on it
== publish-local --js, consumed with --js
== publish-local --m2-home, consumed through -r
== publish to a directory, signed with PGP_SECRET
== publish to Central (mock Portal API)
== without --auto-release the deployment is USER_MANAGED, then a FAILED deployment
== -SNAPSHOT goes to the snapshots repository by PUT
== Central needs the POM metadata
OK
```

What it checks: the library (with an `inline` method and a `fansi` dependency) is published to the
real `~/.ivy2/local` under a random org (removed at exit). A second project with only
`//> using dep <org>::greeter::0.1.0` runs and prints `hello, world 42` on Native and on Scala.js
(node). It also checks: md5/sha1 against `md5`/`shasum`; jar entries (`.tasty`, `.nir`/`.sjsir`,
resources, manifest); byte-identical jars on republish; POM fields (license URL, scm, developer
email, versionScheme, no SNAPSHOT deps); `--m2-home` consumed with `-r file://`; gpg signatures
(`gpg --verify`) for a directory publish and for the Central bundle; the upload request (path,
`publishingType`, `bundle` field, `Authorization: Bearer base64(user:token)`); status polling; the
portal's FAILED errors shown and a non-zero exit; snapshot PUTs with Basic auth; and the
missing-metadata error for Central.

Checked by hand: `--gpg-key <fpr>` with `GNUPGHOME` set (user-keyring path); `computeVersion
git:dynver` (`1.4.0` on the tag, `1.4.0+1-cf72ef35-SNAPSHOT` past it); GitHub-remote defaults
(`io.github.someone`, url/scm from `git@github.com:SomeOne/cool-lib.git`); `--dummy`;
`publish local` alias; `publish --help`.

### Not done / remaining

- No real upload to Central (would publish for real). Only the mock is covered. The first real
  publish should be inspected in the portal before publishing it there (the default).
- No scaladoc: the javadoc jar is manifest-only (Central accepts it).
- sbt/Mill consumption of a scalino-published jar is untested.
- `publish.checksums`, `publish.classifier`, extra artifacts, and `publish.ci.*` beyond
  key-for-key overriding are not supported.
- tests/publish needs `gpg` and `python3` (present on the GitHub Linux/macOS runners).
