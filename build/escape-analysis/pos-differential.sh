#!/usr/bin/env bash
# Differential test of a compiler binary against a known-good one: compile
# Scala 3's tests/pos programs with the full Scala Native pipeline and record,
# per file, the exit code, a hash of the diagnostics and the output sizes/TASTy
# hash. Run it for both binaries and diff the results (ignoring ` sz=`: the
# NIR size has some run-to-run noise even for one binary).
#   pos-differential.sh <scalino-dotc binary> <result file> [list of .scala files]
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")/../.."
source build/00-env.sh >/dev/null 2>&1
BIN=$1; OUTF=$2
LIST=${3:-}
if [[ -z "$LIST" ]]; then LIST=$(mktemp); ls vendor/scala3/tests/pos/*.scala | sort > "$LIST"; fi
CP="$(cat "$WORK/compiler.cp")$CP_SEP$(cat "$WORK/nativelibs.cp")"
NSC=$(cat "$WORK/nscplugin.jar.txt")
one() {
  f=$1; d=$(mktemp -d /tmp/pt.XXXXXX)
  out=$("$BIN" -Xplugin:"$NSC" -Xplugin-require:scalanative -javabootclasspath "$DIST/java.base.jar" -classpath "$CP" -d "$d" "$f" 2>&1); rc=$?
  h="n=$(cd "$d" && find . -type f | wc -l | tr -d ' ') sz=$(cd "$d" && find . -type f -exec stat -f %z {} + 2>/dev/null | awk '{s+=$1} END {print s}') tasty=$(cd "$d" && find . -name '*.tasty' | sort | xargs shasum 2>/dev/null | shasum | cut -c1-8)"
  rm -rf "$d"
  echo "$(basename "$f") rc=$rc out=$(echo "$out" | sed 's#/tmp/pt\.[A-Za-z0-9]*#D#g' | shasum | cut -c1-8) $h"
}
export -f one; export BIN DIST CP NSC
xargs -P 4 -I{} bash -c 'one {}' < "$LIST" | sort > "$OUTF"
echo "$(wc -l < "$OUTF") files; rc0=$(grep -c ' rc=0 ' "$OUTF")"
