#!/usr/bin/env bash
# Builds scalino-linkdriver: a standalone binary wrapping scala-native's
# tools_3 library (NIR -> LLVM IR -> clang -> native executable). All classes
# are known at build time (no dynamic plugin loading here), so this is a
# plain native-image build, no reflection surgery needed beyond the agent
# trace already captured in agent-config/scalino-linkdriver.
#
# Uses tools-patched.cp (04a-patch-tools.sh): fixes scala-native's
# object-file caching for vendored C/S dependencies, which was otherwise
# effectively always-recompile regardless of whether anything changed --
# see docs/findings.md "Native-library object-file caching was inert".
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")"
source ./00-env.sh

[[ -f "$WORK/tools-patched.cp" ]] || { echo "run 04a-patch-tools.sh first" >&2; exit 1; }

DRIVER_CP="$(cat "$WORK/compiler.cp")$CP_SEP$(cat "$WORK/tools.cp")$CP_SEP$(to_native_path "$WORK/driver-classes")"
NATIVE_DRIVER_CP="$(to_native_path "$WORK/driver-classes")$CP_SEP$(cat "$WORK/tools-patched.cp")"

NSCPLUGIN_JAR="$(cat "$WORK/nscplugin.jar.txt")"

rm -rf "$WORK/driver-classes"
mkdir -p "$WORK/driver-classes"
"$JAVA" -cp "$DRIVER_CP" dotty.tools.dotc.Main \
  -Xplugin:"$NSCPLUGIN_JAR" \
  -Xplugin-require:scalanative \
  -classpath "$NATIVE_DRIVER_CP" \
  -d "$WORK/driver-classes" \
  "$ROOT/src/LinkDriver.scala"

"$JAVA" \
  -cp "$DRIVER_CP" \
    LinkDriver \
    "$(to_native_path "$NATIVE_DRIVER_CP")" \
    "$(to_native_path "$DIST")" \
    LinkDriver \
    "$CLANG" \
    "$CLANGPP" \
    info \
    --mode release-size

echo "OK: $DIST/scalino-linkdriver"
