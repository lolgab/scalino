#!/usr/bin/env bash
# The workload the escape analysis is measured on: typecheck dotc's own 550
# source files (errors about missing deps are expected and irrelevant).
#   bench-dotc.sh <scalino-dotc binary> [alloc profile output file]
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")/../.."
source build/00-env.sh >/dev/null 2>&1
OUT=$(mktemp -d)
[[ -n "${2:-}" ]] && export SCALINO_ALLOC_PROFILE_FILE=$2
/usr/bin/time -l "$1" -Ystop-after:typer -javabootclasspath "$DIST/java.base.jar" \
  -classpath "$(cat "$WORK/compiler.cp")" -d "$OUT" "@$WORK/selfhost/file-list.txt" 2>&1 \
  | grep -E "real|maximum resident|elapsed" || true
rm -rf "$OUT"
