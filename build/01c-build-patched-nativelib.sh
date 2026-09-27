#!/usr/bin/env bash
# Builds a patched org.scala-native:nativelib_native0.5_3 jar via scala-native's
# own real sbt build (same "sbt is the only tractable route for this one
# artifact" situation as build/01b-build-patched-javalib.sh -- a direct
# from-source dotc recompile of nativelib hits unrelated pre-existing
# blockers, see docs/findings.md), then substitutes it into $WORK/nativelibs.cp
# in place -- so patches/scala-native-0018+ (the ObjectMonitor/BasicMonitor
# TLV-read-hoisting fixes) actually take effect in what `scalino package`
# links every user project's binary against, not just scalino-linkdriver's own
# bootstrap. Without this, those source fixes are as inert as the
# already-documented scala-native-0003 case: $WORK/nativelibs.cp (and
# therefore dist/nativelibs.cp, vendored verbatim by 06-package.sh) has always
# been the plain published Maven Central nativelib jar, completely untouched
# by any vendor/scala-native source edit.
#
# sbt's own build compiles against the PUBLISHED scala3-compiler jar --
# patches/scala-native-0001/0002 (nscplugin/tools changes that only compile
# against THIS project's self-hosted dotc) make that fail, so they're
# reverted for the duration of this one build and restored right after,
# success or failure -- identical pattern to 01b.
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")"
source ./00-env.sh

require sbt

VENDOR="$ROOT/vendor/scala-native"
[[ -d "$VENDOR/.git" ]] || { echo "missing $VENDOR -- run 00b-setup-vendor.sh first" >&2; exit 1; }
[[ -f "$WORK/nativelibs.cp" ]] || { echo "missing $WORK/nativelibs.cp -- run 01-fetch-deps.sh first" >&2; exit 1; }

cd "$VENDOR"
REVERTED=()
for p in "$ROOT"/patches/scala-native-000{1,2}-*.patch; do
  [[ -e "$p" ]] || continue
  git apply -R "$p"
  REVERTED+=("$p")
done

restore() {
  cd "$VENDOR"
  for p in "${REVERTED[@]}"; do
    git apply "$p"
  done
}
trap restore EXIT

# Same GITHUB_REF_*/CI unset as 01b -- see its comment for why (scala-native's
# own release-tag/CI-snapshot version-stamping logic otherwise misfires from
# this project's own env).
env -u GITHUB_REF_TYPE -u GITHUB_REF_NAME -u GITHUB_REF -u CI sbt nativelib3/publishLocal
cd - > /dev/null

LOCAL_NATIVELIB_JAR="$HOME/.ivy2/local/org.scala-native/nativelib_native0.5_3/${SCALA_NATIVE_VERSION}-SNAPSHOT/jars/nativelib_native0.5_3.jar"
[[ -f "$LOCAL_NATIVELIB_JAR" ]] || { echo "publishLocal succeeded but $LOCAL_NATIVELIB_JAR is missing" >&2; exit 1; }

# Substitute in place: every downstream consumer of $WORK/nativelibs.cp
# (06-package.sh's dist/nativelibs.cp vendoring, and every script that copies
# it as a base before its own javalib swap -- 03/07/08) picks this up
# automatically, no per-script changes needed.
#
# -F/-x too, not just the "-<version>" pattern, and for the same reason
# 01b-build-patched-javalib.sh's own comment documents for javalib: this
# local jar's filename has no "-<version>" suffix (it's just
# nativelib_native0.5_3.jar, unlike the coursier-cached
# nativelib_native0.5_3-0.5.12.jar it replaces), so the pattern alone never
# matches an entry THIS jar itself already added on a previous run --
# without the exact-match filter, re-running this script (e.g. after
# editing a nativelib source file and rebuilding) appends a duplicate each
# time instead of replacing it. scala-native's linker does not treat a
# repeated classpath entry as harmless: it unpacks each one into its own
# numbered dependencies/ dir, so the same GC/runtime .o files get linked
# twice (or three times, ...) and clang fails with "duplicate symbol" for
# every native nativelib symbol.
TMP="$(mktemp)"
{ tr "$CP_SEP" '\n' < "$WORK/nativelibs.cp" | grep -v '/nativelib_native0\.5_3-' | grep -Fxv "$LOCAL_NATIVELIB_JAR"; echo "$LOCAL_NATIVELIB_JAR"; } | paste -sd"$CP_SEP" - > "$TMP"
mv "$TMP" "$WORK/nativelibs.cp"

# Same "publishLocal also publishes a sources jar" fact 01b relies on for
# javalib -- see its comment for the full sourceFromSourcesJar/vendor_sources
# naming-convention rationale, identical here for nativelib.
LOCAL_NATIVELIB_SOURCES_JAR="$HOME/.ivy2/local/org.scala-native/nativelib_native0.5_3/${SCALA_NATIVE_VERSION}-SNAPSHOT/srcs/nativelib_native0.5_3-sources.jar"
[[ -f "$LOCAL_NATIVELIB_SOURCES_JAR" ]] || { echo "publishLocal succeeded but $LOCAL_NATIVELIB_SOURCES_JAR is missing" >&2; exit 1; }

[[ -f "$WORK/nativelibs-sources.cp" ]] || : > "$WORK/nativelibs-sources.cp"
TMP="$(mktemp)"
# "|| true" on the grep: same empty-file/set-e/pipefail concern 01b's
# comment explains.
{ tr "$CP_SEP" '\n' < "$WORK/nativelibs-sources.cp" | grep -Fxv "$LOCAL_NATIVELIB_SOURCES_JAR" || true; echo "$LOCAL_NATIVELIB_SOURCES_JAR"; } | paste -sd"$CP_SEP" - > "$TMP"
mv "$TMP" "$WORK/nativelibs-sources.cp"

echo "OK: $WORK/nativelibs.cp now points at locally-built, patched nativelib: $LOCAL_NATIVELIB_JAR"
echo "OK: $WORK/nativelibs-sources.cp now includes its matching patched sources: $LOCAL_NATIVELIB_SOURCES_JAR"
