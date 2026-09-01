#!/usr/bin/env bash
# Builds linkdriver-native: a standalone binary wrapping scala-native's
# tools_3 library (NIR -> LLVM IR -> clang -> native executable). All classes
# are known at build time (no dynamic plugin loading here), so this is a
# plain native-image build, no reflection surgery needed beyond the agent
# trace already captured in agent-config/linkdriver.
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")"
source ./00-env.sh

[[ -f "$WORK/compiler.cp" ]] || { echo "run 01-fetch-deps.sh first" >&2; exit 1; }

DRIVER_CP="$(cat "$WORK/compiler.cp"):$(cat "$WORK/tools.cp"):$WORK/driver-classes"

rm -rf "$WORK/driver-classes"
mkdir -p "$WORK/driver-classes"
"$JAVA" -cp "$DRIVER_CP" dotty.tools.dotc.Main -classpath "$DRIVER_CP" -d "$WORK/driver-classes" "$ROOT/src/LinkDriver.scala"

"$NATIVE_IMAGE" \
  -cp "$DRIVER_CP" \
  --no-fallback \
  -H:ConfigurationFileDirectories="$ROOT/agent-config/linkdriver" \
  -H:+ReportExceptionStackTraces \
  --enable-url-protocols=http,https \
  -o "$DIST/linkdriver-native" \
  LinkDriver

echo "OK: $DIST/linkdriver-native"
