#!/usr/bin/env bash
# Compiles the patched vendor/scala3 (+ vendor/scala-native nscplugin/nir/util) self-hosted
# source set to plain JVM .class files, using the published, unpatched scala3-compiler_3 jar.
# Needed so 02b's reflection scan sees MiniPhase subclasses added by our patches (e.g.
# transform/CollectEntryPoints.scala, patches/scala3-0006); scanning the published jar alone
# omits them and a self-hosted binary crashes when fusing phases
# (`MiniPhaseOverrides has no entry for ...`).
#
# Plain JVM compile, not NIR: the backendPhases patch only affects the compiled sources, not the
# running compiler. backend/jvm/backend/sjs sources stay excluded (see
# build/selfhost/gen-file-list.sh) since they'd need scala-asm.
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")"
source ./00-env.sh

[[ -f "$WORK/compiler.cp" ]] || { echo "run build/01-fetch-deps.sh first" >&2; exit 1; }

BOOT_DIR="$WORK/selfhost-bootstrap"
rm -rf "$BOOT_DIR"
mkdir -p "$BOOT_DIR"

# gen-file-list.sh's list includes 02b's output (MiniPhaseOverrides.scala), which doesn't exist
# yet. Use an empty stand-in: this compile only needs MegaPhase.scala's reference to
# `MiniPhaseOverrides.declaredMethodNames` to type-check; 03 compiles the real one.
DUMMY_OVERRIDES="$BOOT_DIR/MiniPhaseOverridesDummy.scala"
cat > "$DUMMY_OVERRIDES" <<'EOF'
package dotty.tools.dotc.transform
object MiniPhaseOverrides {
  val declaredMethodNames: Map[String, Set[String]] = Map.empty
}
EOF

FILE_LIST="$BOOT_DIR/file-list.txt"
{
  ./selfhost/gen-file-list.sh | grep -v '/generated/MiniPhaseOverrides\.scala$'
  echo "$DUMMY_OVERRIDES"
} | to_native_path_list > "$FILE_LIST"
echo "  $(wc -l < "$FILE_LIST" | tr -d ' ') files"

OUT_CLASSES="$BOOT_DIR/classes"
mkdir -p "$OUT_CLASSES"

# Compile against the non-compiler dependencies only: these sources redefine many classes of
# the published scala3-compiler_3 jar, and having both risks duplicate-symbol errors.
DEPS_CP="$(tr "$CP_SEP" '\n' < "$WORK/compiler.cp" | grep -v '/scala3-compiler_3-' | paste -sd"$CP_SEP" -)"

echo "== compiling patched dotc+nscplugin source to plain JVM classes (bootstrap, for MiniPhase discovery only) =="
# The compiler sources of Scala 3.10+ are written for -Yexplicit-nulls (scala3/project/Build.scala); the
# rest of the source set (nscplugin, nir, our patches) is not, hence unsafeNulls for the whole compile.
"$JAVA" -Xss64m -cp "$(cat "$WORK/compiler.cp")" dotty.tools.dotc.Main \
  -Yexplicit-nulls -language:unsafeNulls -language:implicitConversions \
  -classpath "$DEPS_CP" \
  -d "$OUT_CLASSES" \
  "@$FILE_LIST"

echo "OK: $OUT_CLASSES"
