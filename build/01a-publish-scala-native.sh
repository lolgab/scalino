#!/usr/bin/env bash
# Builds Scala Native from vendor/scala-native (upstream main at $SCALA_NATIVE_REF plus
# patches/scala-native-*.patch) with its own sbt build and publishes every module the
# toolchain consumes to ~/.ivy2/local/org.scala-native as $SCALA_NATIVE_VERSION. 01-fetch-deps.sh
# resolves them from there, so no released Scala Native jar is used anywhere.
#
# sbt compiles nscplugin against the *published* scala3-compiler, so the hunks that only
# compile against this project's self-hosted dotc (patches/scala-native-*-selfhost.patch) are
# reverted for the duration of the build and restored right after, success or failure.
#
# Also keeps javalib's .tasty files as javalib-tasty.jar next to the published jar: javalib's
# published jar is NIR-only (project/Settings.scala's NIROnlySettings), but the .tasty files
# carry every method body, which the macro interpreter reads for JDK classes
# (Interpreter's JavalibOverlay).
#
# SCALINO_REUSE_PUBLISHED=1 (set by ci.yml on a cache hit for ~/.ivy2/local/org.scala-native,
# keyed on everything that determines these jars) skips the sbt build.
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")"
source ./00-env.sh

require sbt

VENDOR="$ROOT/vendor/scala-native"
[[ -d "$VENDOR/.git" ]] || { echo "missing $VENDOR -- run 00b-setup-vendor.sh first" >&2; exit 1; }

IVY="$HOME/.ivy2/local/org.scala-native"
# Modules the toolchain needs (see 01-fetch-deps.sh): the compiler plugin, the runtime libraries,
# and the build/link tools for both the JVM and Scala Native platforms.
PROJECTS=(
  nscplugin3
  util3 utilJVM3 nir3 nirJVM3 tools3 toolsJVM3
  nativelib3 javalibintf javalib3 auxlib3 clib3 posixlib3 windowslib3 scala3lib3
)

if [[ "${SCALINO_REUSE_PUBLISHED:-}" == 1 \
      && -f "$IVY/javalib_native0.5_3/$SCALA_NATIVE_VERSION/jars/javalib-tasty.jar" \
      && -f "$IVY/tools_native0.5_3/$SCALA_NATIVE_VERSION/jars/tools_native0.5_3.jar" ]]; then
  echo "reusing cached Scala Native build from $IVY"
  exit 0
fi

cd "$VENDOR"
REVERTED=()
for p in "$ROOT"/patches/scala-native-*-selfhost.patch; do
  [[ -e "$p" ]] || continue
  git apply -R "$p"
  REVERTED+=("$p")
done

# cd explicitly: an EXIT trap runs after this script's own `cd -` below has left $VENDOR, and a
# failing `git apply` inside a trap does not flip the script's exit code, so a cwd mistake here
# would silently leave the tree unpatched while still reporting success.
restore() {
  cd "$VENDOR"
  for p in "${REVERTED[@]}"; do
    git apply "$p"
  done
}
trap restore EXIT

# project/ScalaNativeBuildInfo.scala reads GITHUB_REF_TYPE/GITHUB_REF_NAME (for Scala Native's own
# release CI: it asserts the pushed tag equals its own version) and CI (stamps "<base>-<date>-<hash>-
# SNAPSHOT"). Our CI sets all of them, which would trip the assertion or change the version that
# versions.env pins, so unset them for this subprocess only.
env -u GITHUB_REF_TYPE -u GITHUB_REF_NAME -u GITHUB_REF -u CI \
  sbt -batch "$(printf '%s/publishLocal; ' "${PROJECTS[@]}")"

_TASTY_CLASSES="$(ls -d javalib/.3/target/scala-*/classes javalib/target/out/native*/scala-*/javalib/classes 2>/dev/null | head -1 || true)"
if [[ -z "$_TASTY_CLASSES" ]]; then
  # sbt 2 keeps outputs under target/out/...; find the javalib classes dir that has .tasty files
  _TASTY_CLASSES="$(find target/out -type d -path '*javalib*' -name classes 2>/dev/null | while read -r d; do
    [[ -n "$(find "$d" -name '*.tasty' -print -quit)" ]] && echo "$d" && break
  done)"
fi
[[ -n "$_TASTY_CLASSES" ]] || { echo "no javalib classes dir with .tasty files under $VENDOR" >&2; exit 1; }
_TASTY_LIST="$(mktemp)"
(cd "$_TASTY_CLASSES" && find . -name '*.tasty' | sed 's|^\./||' | sort) > "$_TASTY_LIST"
[[ -s "$_TASTY_LIST" ]] || { echo "no .tasty files in $_TASTY_CLASSES" >&2; exit 1; }
mkdir -p "$IVY/javalib_native0.5_3/$SCALA_NATIVE_VERSION/jars"
(cd "$_TASTY_CLASSES" && "$JAR" cf "$IVY/javalib_native0.5_3/$SCALA_NATIVE_VERSION/jars/javalib-tasty.jar" @"$_TASTY_LIST")
rm -f "$_TASTY_LIST"
cd - > /dev/null

for m in nscplugin_$SCALA_VERSION nativelib_native0.5_3 javalib_native0.5_3 auxlib_native0.5_3 \
         posixlib_native0.5_3 clib_native0.5_3 tools_native0.5_3 tools_3 nir_native0.5_3 util_native0.5_3; do
  [[ -f "$IVY/$m/$SCALA_NATIVE_VERSION/jars/$m.jar" ]] \
    || { echo "publishLocal succeeded but $IVY/$m/$SCALA_NATIVE_VERSION/jars/$m.jar is missing" >&2; exit 1; }
done
[[ -f "$IVY/scala3lib_native0.5_3/$SN_COMBINED_VERSION/jars/scala3lib_native0.5_3.jar" ]] \
  || { echo "scala3lib_native0.5_3 $SN_COMBINED_VERSION was not published" >&2; exit 1; }
echo "OK: Scala Native $SCALA_NATIVE_VERSION (patched, from source) published to $IVY"
