#!/usr/bin/env bash
# Builds dotc-native: a standalone binary of dotc + the scala-native compiler
# plugin, no JVM required to run it.
#
# The plugin MUST be on native-image's own build classpath. Compiler plugins
# are loaded reflectively at runtime (Class.forName on the jar named by
# -Xplugin), and native-image is closed-world AOT: it cannot load a class
# that wasn't reachable at build time. So we bake nscplugin's classes into
# the same image as dotc, and just point -Xplugin at its jar at runtime to
# activate it (the classes are already resident).
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")"
source ./00-env.sh

[[ -f "$WORK/compiler.cp" ]] || { echo "run 01-fetch-deps.sh first" >&2; exit 1; }

BUILD_CP="$(cat "$WORK/compiler.cp"):$(cat "$WORK/nativelibs.cp"):$(cat "$WORK/nscplugin.cp")"

"$NATIVE_IMAGE" \
  -cp "$BUILD_CP" \
  --no-fallback \
  -H:ConfigurationFileDirectories="$ROOT/agent-config/dotc" \
  -H:+ReportExceptionStackTraces \
  -o "$DIST/dotc-native" \
  dotty.tools.dotc.Main

echo "OK: $DIST/dotc-native"
