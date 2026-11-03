#!/usr/bin/env bash
# Link a small app (NIR dir from `scalino compile`/`package`, e.g.
# .scalino-build/_scratch/classes) on the JVM with the escape-analysis overlay of
# dev-link.sh, for testing the analysis on a minimal program in seconds.
#
#   build/escape-analysis/dev-link-app.sh <nir-dir> <main-class> <out-dir> [mode]
#
# Environment: EXTRA_CP=<jars> adds dependency NIR jars; otherwise as dev-link.sh (SKIP_COMPILE=1 reuses the overlay of the previous
# run). Writes <out-dir>/<main-class>; run it directly.
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")/../.."
source build/00-env.sh >/dev/null 2>&1
NIR=$(cd "${1:?usage: dev-link-app.sh <nir-dir> <main-class> <out-dir> [mode]}" && pwd)
MAIN=${2:?main class}
OUT=${3:?out dir}
MODE=${4:-release-fast}
EA_WORK=${EA_WORK:-$WORK/ea-dev}
V=vendor/scala-native/tools/src/main/scala/scala/scalanative
OV=$EA_WORK/overlay
mkdir -p "$EA_WORK"
if [[ -z "${SKIP_COMPILE:-}" ]]; then
  rm -rf "$OV"; mkdir -p "$OV"
  "$JAVA" -Xss64m -cp "$(cat "$WORK/compiler.cp")" dotty.tools.dotc.Main \
    -classpath "$(cat "$WORK/compiler.cp")$CP_SEP$(cat "$WORK/tools.cp")" -d "$OV" \
    $V/interflow/EscapeAnalysis.scala $V/interflow/Inline.scala $V/codegen/Lower.scala \
    $V/checker/Check.scala $V/build/ScalaNative.scala $V/build/Build.scala
fi
rm -rf "$OUT"; mkdir -p "$OUT"
"$JAVA" -Xss64m -XX:MaxRAMPercentage=50 \
  -cp "$OV$CP_SEP$(cat "$WORK/compiler.cp")$CP_SEP$(cat "$WORK/tools.cp")$CP_SEP$WORK/driver-classes" LinkDriver \
  "$NIR$CP_SEP${EXTRA_CP:+$EXTRA_CP$CP_SEP}$(cat "$WORK/selfhost/nativelibs.cp")" "$OUT" "$MAIN" "$CLANG" "$CLANGPP" info \
  --mode "$MODE" --multithreading 2>&1 | grep -vE '^\[info\] (Linking|Discover|Total|Postproc)' || true
# the native/ build dir is large: keep only the binary
rm -rf "$OUT/native" "$OUT"/*.dSYM
ls -la "$OUT/$MAIN"
