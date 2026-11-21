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
