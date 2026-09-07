#!/usr/bin/env bash
# Generates MiniPhaseOverrides.scala: a build-time lookup table that
# replaces MegaPhase.defines' runtime `Class#getDeclaredMethods` reflection.
# Scala Native has no general reflection API (only the narrow, per-class
# opt-in @EnableReflectiveInstantiation) -- see docs/findings.md "Blocker A"
# and the doc comment on `defines` in vendor/scala3's patched MegaPhase.scala
# (patches/scala3-0003-*.patch). This tool computes the exact same fact
# (which of MiniPhase's overridable methods each concrete subclass
# redefines) using real JVM reflection, once, offline, over the same
# classpath scalino-dotc's self-hosted build bakes in.
#
# Must run after 01-fetch-deps.sh (needs compiler.cp/nscplugin.cp) and
# before 03-build-scalino-dotc.sh/08-build-scalino-lsp.sh, which compile
# this generated file in as part of dotc's own self-hosted source set (see
# build/selfhost/gen-file-list.sh).
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")"
source ./00-env.sh

[[ -f "$WORK/compiler.cp" ]] || { echo "run 01-fetch-deps.sh first" >&2; exit 1; }
[[ -f "$WORK/nscplugin.cp" ]] || { echo "run 01-fetch-deps.sh first" >&2; exit 1; }

GEN_DIR="$WORK/generated"
TOOL_CLASSES="$WORK/gen-tool-classes"
mkdir -p "$GEN_DIR" "$TOOL_CLASSES"

"$JAVAC" -d "$TOOL_CLASSES" tools/GenMiniPhaseOverrides.java
"$JAVA" -cp "$TOOL_CLASSES" GenMiniPhaseOverrides \
  "$(cat "$WORK/compiler.cp")" "$(cat "$WORK/nscplugin.cp")" \
  "$GEN_DIR/MiniPhaseOverrides.scala"

echo "OK: $GEN_DIR/MiniPhaseOverrides.scala"
