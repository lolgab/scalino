#!/usr/bin/env bash
# Compile workload for a native scalino-dotc (PGO training + benchmark).
#   dotc-workload.sh <dotc-binary> tools|linkdriver [outdir]
# tools      = the scala-native tools sources build/04a compiles (~45 big files,
#              Scala Native plugin on, NIR out): a realistic `scalino build`.
# linkdriver = src/LinkDriver.scala (tiny).
# build/04a's Infos.scala is left out: it trips a native-dotc crash in the
# MegaPhase{...mixin,lazyVals...} (resolved from tools-patched.jar instead).
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")/.."
source ./00-env.sh
BIN="$1"; WL="$2"; OUTD="${3:-$WORK/pgo-exp/wl-out}"
rm -rf "${OUTD:?}"; mkdir -p "$OUTD"
PLUGIN="$(cat "$WORK/nscplugin.jar.txt")"
case "$WL" in
  linkdriver) CP="$(cat "$WORK/tools-patched-javalib.cp")"; SRC=("$ROOT/src/LinkDriver.scala") ;;
  tools)
    LIST="$WORK/pgo-exp/tools-sources.txt"; mkdir -p "$(dirname "$LIST")"
    v="$ROOT/vendor/scala-native"
    { find "$v/tools/src/main/scala" -name '*.scala' -not -name Infos.scala | sort
      for f in LLVMCApi DirectCodeGen DirectCodeGenDispatch; do
        echo "$v/tools/native/src/main/scala/scala/scalanative/codegen/llvm/direct/$f.scala"
      done; } > "$LIST"
    CP="$(cat "$WORK/compiler.cp")$CP_SEP$(cat "$WORK/tools-native.cp")"; SRC=("@$LIST") ;;
  nir)
    # unseen by the training run: the scala-native nir module (needs the util jar only)
    LIST="$WORK/pgo-exp/nir-sources.txt"; mkdir -p "$(dirname "$LIST")"
    find "$ROOT/vendor/scala-native/nir/src/main/scala" -name '*.scala' | sort > "$LIST"
    CP="$(cat "$WORK/compiler.cp")$CP_SEP$(cat "$WORK/tools-native.cp")"; SRC=("@$LIST") ;;
esac
exec "$BIN" -color:never -Xno-enrich-error-messages -Xplugin:"$PLUGIN" -Xplugin-require:scalanative -Yretain-trees \
  -javabootclasspath "$DIST/java.base.jar" -classpath "$CP" -d "$OUTD" "${SRC[@]}" > "$OUTD.log" 2>&1
