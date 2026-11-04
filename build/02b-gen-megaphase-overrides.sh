#!/usr/bin/env bash
# Generates MiniPhaseOverrides.scala: a build-time lookup table replacing MegaPhase.defines'
# runtime `Class#getDeclaredMethods` reflection, which Scala Native lacks (docs/findings.md
# "Blocker A", and the doc comment on `defines` in patches/scala3-0003-*.patch). Computes which
# of MiniPhase's methods each concrete subclass overrides, via JVM reflection over the same
# classpath scalino-dotc bakes in.
#
# Runs after 01-fetch-deps.sh and before 03/08, which compile the generated file in.
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")"
source ./00-env.sh

[[ -f "$WORK/compiler.cp" ]] || { echo "run 01-fetch-deps.sh first" >&2; exit 1; }
[[ -f "$WORK/nscplugin.cp" ]] || { echo "run 01-fetch-deps.sh first" >&2; exit 1; }
PATCHED_CLASSES="$WORK/selfhost-bootstrap/classes"
[[ -d "$PATCHED_CLASSES" ]] || { echo "missing $PATCHED_CLASSES -- run build/02a-build-compiler-patched.sh first" >&2; exit 1; }

GEN_DIR="$WORK/generated"
TOOL_CLASSES="$WORK/gen-tool-classes"
mkdir -p "$GEN_DIR" "$TOOL_CLASSES"

"$JAVAC" -d "$TOOL_CLASSES" tools/GenMiniPhaseOverrides.java
# $PATCHED_CLASSES (from 02a) goes first: URLClassLoader resolves from the first URL defining a
# class, so the patched shape wins and patch-added classes (e.g. CollectEntryPoints) are found.
"$JAVA" -cp "$TOOL_CLASSES" GenMiniPhaseOverrides \
  "$PATCHED_CLASSES" "$(cat "$WORK/compiler.cp")" "$(cat "$WORK/nscplugin.cp")" \
  "$GEN_DIR/MiniPhaseOverrides.scala"

echo "OK: $GEN_DIR/MiniPhaseOverrides.scala"
