#!/usr/bin/env bash
# Builds scalino-linkdriver: a standalone binary wrapping scala-native's
# tools_3 library (NIR -> LLVM IR -> clang -> native executable). All classes
# are known at build time (no dynamic plugin loading here), so this is a
# plain native-image build, no reflection surgery needed beyond the agent
# trace already captured in agent-config/linkdriver.
#
# Uses tools-patched.cp (04a-patch-tools.sh): fixes scala-native's
# object-file caching for vendored C/S dependencies, which was otherwise
# effectively always-recompile regardless of whether anything changed --
# see docs/findings.md "Native-library object-file caching was inert".
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")"
source ./00-env.sh

[[ -f "$WORK/tools-patched.cp" ]] || { echo "run 04a-patch-tools.sh first" >&2; exit 1; }

DRIVER_CP="$(cat "$WORK/compiler.cp"):$(cat "$WORK/tools-patched.cp"):$WORK/driver-classes"

rm -rf "$WORK/driver-classes"
mkdir -p "$WORK/driver-classes"
"$JAVA" -cp "$DRIVER_CP" dotty.tools.dotc.Main -classpath "$DRIVER_CP" -d "$WORK/driver-classes" "$ROOT/src/LinkDriver.scala"

"$NATIVE_IMAGE" \
  -cp "$DRIVER_CP" \
  --no-fallback \
  -H:ConfigurationFileDirectories="$ROOT/agent-config/linkdriver" \
  -H:+ReportExceptionStackTraces \
  --enable-url-protocols=http,https \
  -J--sun-misc-unsafe-memory-access=allow \
  -o "$DIST/scalino-linkdriver" \
  LinkDriver

echo "OK: $DIST/scalino-linkdriver"
