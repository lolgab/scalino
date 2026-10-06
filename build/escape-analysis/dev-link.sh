#!/usr/bin/env bash
# Link dotc (the self-hosted compiler's NIR) on the JVM with a freshly compiled
# overlay of the escape-analysis sources in vendor/scala-native, without
# rebuilding the patched tools jars: the dev loop for patches 0061/0064/0068.
#
#   build/escape-analysis/dev-link.sh <tag>      -> $EA_WORK/<tag>/dotty.tools.dotc.Main
#
# Environment:
#   EA_WORK           work dir (default: .build-work/ea-dev)
#   SKIP_COMPILE=1    reuse the overlay of the previous run
#   PROF=1            link against the allocation-profiling nativelib
#                     (build/escape-analysis/make-prof-nativelib.sh)
#   anything the analysis reads: SCALANATIVE_ESCAPE_REPORT, SCALANATIVE_ESCAPE_EXIT,
#   SCALANATIVE_SITE_PROFILE=<table file>, SCALANATIVE_STACK_POISON, SCALANATIVE_EA_X, ...
#   (see docs/findings.md, "Escape analysis, round 2")
#
# Needs build/04a-patch-tools.sh, 03-build-scalino-dotc.sh to have run once
# (tools-patched-jvm.cp, driver-classes, selfhost/nir-out).
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")/../.."
source build/00-env.sh >/dev/null 2>&1
TAG=${1:?usage: dev-link.sh <tag>}
EA_WORK=${EA_WORK:-$WORK/ea-dev}
V=vendor/scala-native/tools/src/main/scala/scala/scalanative
OV=$EA_WORK/overlay
mkdir -p "$EA_WORK"
if [[ -z "${SKIP_COMPILE:-}" ]]; then
  rm -rf "$OV"; mkdir -p "$OV"
  "$JAVA" -Xss64m -cp "$(cat "$WORK/compiler.cp")" dotty.tools.dotc.Main \
    -classpath "$(cat "$WORK/compiler.cp")$CP_SEP$(cat "$WORK/tools-patched-jvm.cp")" -d "$OV" \
    $V/interflow/EscapeAnalysis.scala $V/interflow/Inline.scala $V/codegen/Lower.scala \
    $V/checker/Check.scala $V/build/ScalaNative.scala
fi
NLCP=$(cat "$WORK/selfhost/nativelibs.cp")
if [[ -n "${PROF:-}" ]]; then
  [[ -f "$EA_WORK/nativelib-prof.jar" ]] || { echo "run make-prof-nativelib.sh first" >&2; exit 1; }
  OLDNL="$HOME/.ivy2/local/org.scala-native/nativelib_native0.5_3/$SCALA_NATIVE_VERSION-SNAPSHOT/jars/nativelib_native0.5_3.jar"
  [[ "$NLCP" == *"$OLDNL"* ]] || { echo "nativelib jar not found on nativelibs.cp" >&2; exit 1; }
  NLCP=${NLCP//"$OLDNL"/$EA_WORK/nativelib-prof.jar}
fi
LW=$EA_WORK/$TAG; rm -rf "$LW"; mkdir -p "$LW"
"$JAVA" -Xss64m -XX:MaxRAMPercentage=80 \
  -cp "$OV$CP_SEP$(cat "$WORK/compiler.cp")$CP_SEP$(cat "$WORK/tools-patched-jvm.cp")$CP_SEP$WORK/driver-classes" LinkDriver \
  "$WORK/selfhost/nir-out$CP_SEP$NLCP" "$LW" dotty.tools.dotc.Main "$CLANG" "$CLANGPP" info \
  --mode release-fast --multithreading --embed-resources 2>&1 | tee "$LW.log"
# the native/ build dir is several GB: keep only the binary
rm -rf "$LW/native" "$LW"/*.dSYM
echo "OK: $LW/dotty.tools.dotc.Main"
