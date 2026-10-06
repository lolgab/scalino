#!/usr/bin/env bash
# Builds a patched org.scala-native:javalib_native0.5_3 jar via scala-native's
# own real sbt build, so patches/scala-native-0003 (UnixPath/WindowsPath#toUri
# missing "//" authority -- broke scalino-lsp's definition/references URIs)
# actually takes effect. Without this, scalino-dotc/scalino-lsp link against
# the published (unpatched) jar and that fix is silently inert -- see
# docs/findings.md's "Self-hosting scalino-lsp" javalib section for the full
# story of why a direct from-source recompile (bypassing sbt, the trick used
# everywhere else in this pipeline) turned out not to be tractable here.
#
# sbt's own build compiles against the PUBLISHED scala3-compiler jar --
# patches/scala-native-0001/0002 (nscplugin/tools changes that only compile
# against THIS project's self-hosted dotc) make that fail, so they're
# reverted for the duration of this one build and restored right after,
# success or failure.
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")"
source ./00-env.sh

require sbt

VENDOR="$ROOT/vendor/scala-native"
[[ -d "$VENDOR/.git" ]] || { echo "missing $VENDOR -- run 00b-setup-vendor.sh first" >&2; exit 1; }

cd "$VENDOR"
REVERTED=()
for p in "$ROOT"/patches/scala-native-000{1,2}-*.patch; do
  [[ -e "$p" ]] || continue
  git apply -R "$p"
  REVERTED+=("$p")
done

# cd explicitly here (not relying on the caller's cwd at trap-fire time --
# an EXIT trap runs after the script's own `cd - ` below has already left
# $VENDOR, and a failing `git apply` inside a trap does NOT flip the
# script's exit code, so a cwd mistake here would silently leave the repo
# unpatched while still reporting success).
restore() {
  cd "$VENDOR"
  for p in "${REVERTED[@]}"; do
    git apply "$p"
  done
}
trap restore EXIT

# Confirmed via CI (only ever on a real tag push, e.g. our own release.yml):
# scala-native's own project/ScalaNativeBuildInfo.scala reads GITHUB_REF_TYPE/
# GITHUB_REF_NAME (meant for THEIR OWN release CI, asserting the pushed tag
# equals their own pinned version) -- sbt inherits our full environment, so
# our own tag ("v0.0.1") leaks in and trips their assertion ("tag does not
# match expected version") even though we're only vendoring their source, not
# cutting a scala-native release ourselves. Unset for this subprocess only,
# falling their version logic through to the ordinary CI-snapshot branch.
#
# Also unset CI itself: that same ScalaNativeBuildInfo.scala has a separate
# branch for `envOrNone("CI").isDefined` that stamps the published version as
# "$baseVersion-<commitDate>-<gitHash>-SNAPSHOT" instead of the plain
# "$baseVersion-SNAPSHOT" our build scripts (03/04/08) hardcode via
# $SCALA_NATIVE_VERSION-SNAPSHOT when locating this jar. On a real CI runner
# (GitHub Actions sets CI=true) that mismatch made every LOCAL_JAVALIB_JAR
# check silently miss, so patches/scala-native-0009 (ZipFileSystemProvider)
# never made it into scalino-linkdriver's own build there -- fine as long as
# the old jar-extraction workaround covered for it, fatal once that workaround
# was removed in favor of the patch actually taking effect (see release
# v0.0.3's CI failure). Works locally already since CI is normally unset.
#
# SCALINO_REUSE_PUBLISHED=1 (set by ci.yml on a cache hit for ~/.ivy2/local/
# org.scala-native, keyed on everything that determines these jars) skips the
# ~8 min sbt build when the jars are already there.
_IVY_JAVALIB="$HOME/.ivy2/local/org.scala-native/javalib_native0.5_3/${SCALA_NATIVE_VERSION}-SNAPSHOT"
if [[ "${SCALINO_REUSE_PUBLISHED:-}" == 1 && -f "$_IVY_JAVALIB/jars/javalib_native0.5_3.jar" && -f "$_IVY_JAVALIB/srcs/javalib_native0.5_3-sources.jar" ]]; then
  echo "reusing cached patched javalib from $_IVY_JAVALIB"
else
  env -u GITHUB_REF_TYPE -u GITHUB_REF_NAME -u GITHUB_REF -u CI sbt javalib3/publishLocal
fi
cd - > /dev/null

LOCAL_JAVALIB_JAR="$HOME/.ivy2/local/org.scala-native/javalib_native0.5_3/${SCALA_NATIVE_VERSION}-SNAPSHOT/jars/javalib_native0.5_3.jar"
[[ -f "$LOCAL_JAVALIB_JAR" ]] || { echo "publishLocal succeeded but $LOCAL_JAVALIB_JAR is missing" >&2; exit 1; }

[[ -f "$WORK/nativelibs.cp" ]] || { echo "missing $WORK/nativelibs.cp -- run 01-fetch-deps.sh first" >&2; exit 1; }

# Substitute in place, same technique 01c uses for nativelib: every real
# `scalino package`/`scalino build` a user runs links against dist/lib's
# copy of $WORK/nativelibs.cp (06-package.sh vendors it verbatim), not just
# this project's own self-hosting bootstrap (03/08's own separately-scoped
# NATIVELIBS_CP copies, which do their own substitution already and keep
# working regardless -- worst case they end up with this same local jar
# listed twice, harmless on a dotc/scalac classpath). Without this, a
# javalib patch is real but completely inert for every end user, exactly
# the "scala-native-0003" gap docs/findings.md describes -- see that file
# for why this was previously left unwired.
#
# -F/-x too, not just the "-<version>" pattern: this local jar's filename
# has no "-<version>" suffix (it's just javalib_native0.5_3.jar, unlike the
# coursier-cached javalib_native0.5_3-0.5.12.jar it replaces), so the
# pattern alone never matches an entry THIS script itself already added on
# a previous run -- re-running it (e.g. across sessions, against a
# $WORK/nativelibs.cp that already has the local jar in place from before)
# appends a duplicate instead of replacing it. Contrary to this comment's
# own prior claim, that is NOT harmless: 06-package.sh vendors this same
# file as dist/nativelibs.cp, which real `scalino build` uses for actual
# native linking, and scala-native's linker unpacks each classpath entry
# into its own numbered dependencies/ dir -- a repeated entry means the
# same javalib .o files (time_nano.c, z.c, etc.) get linked twice and
# clang fails with "duplicate symbol" for every one of them.
TMP="$(mktemp)"
{ tr "$CP_SEP" '\n' < "$WORK/nativelibs.cp" | grep -v '/javalib_native0\.5_3-' | grep -Fxv "$LOCAL_JAVALIB_JAR"; echo "$LOCAL_JAVALIB_JAR"; } | paste -sd"$CP_SEP" - > "$TMP"
mv "$TMP" "$WORK/nativelibs.cp"

# publishLocal above also publishes a sources jar (sbt's default packageSrc
# task, part of the publish graph) -- already named exactly
# "javalib_native0.5_3-sources.jar", matching $LOCAL_JAVALIB_JAR's own
# unversioned basename ("javalib_native0.5_3.jar") with ".jar" swapped for
# "-sources.jar". That's the exact sibling-naming convention
# sourceFromSourcesJar (scala3/compiler/.../Symbols.scala) looks for, and
# 06-package.sh's vendor_sources relies on that same convention to know
# which sources jar belongs to which main jar -- so go-to-definition into
# javalib in scalino-lsp resolves into the REAL patched source, not
# upstream's unpatched code (or nothing at all, if left to a best-effort
# runtime fetch that could never find a "-SNAPSHOT" this project never
# publishes anywhere but ~/.ivy2/local).
LOCAL_JAVALIB_SOURCES_JAR="$HOME/.ivy2/local/org.scala-native/javalib_native0.5_3/${SCALA_NATIVE_VERSION}-SNAPSHOT/srcs/javalib_native0.5_3-sources.jar"
[[ -f "$LOCAL_JAVALIB_SOURCES_JAR" ]] || { echo "publishLocal succeeded but $LOCAL_JAVALIB_SOURCES_JAR is missing" >&2; exit 1; }

[[ -f "$WORK/nativelibs-sources.cp" ]] || : > "$WORK/nativelibs-sources.cp"
TMP="$(mktemp)"
# "|| true" on the grep: an empty (or fully-filtered) nativelibs-sources.cp
# -- e.g. 01-fetch-deps.sh's own sources fetch came up empty -- makes grep
# exit 1 on zero matches, which "set -e"+pipefail would otherwise treat as
# this whole pipeline failing and abort the script.
{ tr "$CP_SEP" '\n' < "$WORK/nativelibs-sources.cp" | grep -Fxv "$LOCAL_JAVALIB_SOURCES_JAR" || true; echo "$LOCAL_JAVALIB_SOURCES_JAR"; } | paste -sd"$CP_SEP" - > "$TMP"
mv "$TMP" "$WORK/nativelibs-sources.cp"

echo "OK: patched javalib published to ~/.ivy2/local/org.scala-native/javalib_native0.5_3/${SCALA_NATIVE_VERSION}-SNAPSHOT/"
echo "OK: $WORK/nativelibs.cp now points at locally-built, patched javalib: $LOCAL_JAVALIB_JAR"
echo "OK: $WORK/nativelibs-sources.cp now includes its matching patched sources: $LOCAL_JAVALIB_SOURCES_JAR"
