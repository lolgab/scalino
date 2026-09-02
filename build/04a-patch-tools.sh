#!/usr/bin/env bash
# Patches scala-native's tools_3 (the JVM-side build/link API LinkDriver.scala
# drives) to fix inert object-file caching for vendored C/S dependency
# sources -- see docs/findings.md "Native-library object-file caching was
# inert". Same splice-not-full-rebuild approach as 03a-patch-compiler.sh.
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")"
source ./00-env.sh

VENDOR="$ROOT/vendor/scala-native"
[[ -f "$VENDOR/tools/src/main/scala/scala/scalanative/build/Build.scala" ]] || {
  echo "missing $VENDOR -- run 00b-setup-vendor.sh first" >&2
  exit 1
}
[[ -f "$WORK/tools.cp" ]] || { echo "run 01-fetch-deps.sh first" >&2; exit 1; }
[[ -f "$WORK/compiler.cp" ]] || { echo "run 01-fetch-deps.sh first" >&2; exit 1; }

ORIG_JAR="$(tr ':' '\n' < "$WORK/tools.cp" | grep "tools_3-$SCALA_NATIVE_VERSION.jar$")"
[[ -n "$ORIG_JAR" ]] || { echo "could not find tools_3-$SCALA_NATIVE_VERSION.jar on tools.cp" >&2; exit 1; }

PATCHED_DIR="$WORK/patched-tools-classes"
PATCHED_JAR="$DIST/tools-patched.jar"

# Must compile with -cp/-classpath both set to the SAME combined classpath:
# tools_3's own declared scala3-library_3 (3.1.3, pulled in via tools.cp) and
# our compiler's scala3-library_3 (3.7.1, via compiler.cp) can't both be on
# the classpath used to *run* dotc vs. the one used to *typecheck* -- that
# split caused a scala.runtime.LazyVals TASTy/binary mismatch. A single
# unified classpath for both flags avoids it (same pattern LinkDriver.scala's
# own build step already uses).
FULL_CP="$(cat "$WORK/compiler.cp"):$(cat "$WORK/tools.cp")"

rm -rf "$PATCHED_DIR"
mkdir -p "$PATCHED_DIR"
"$JAVA" -cp "$FULL_CP" dotty.tools.dotc.Main \
  -classpath "$FULL_CP" \
  -d "$PATCHED_DIR" \
  "$VENDOR/tools/src/main/scala/scala/scalanative/build/Build.scala" \
  "$VENDOR/tools/src/main/scala/scala/scalanative/build/LLVM.scala" \
  "$VENDOR/tools/src/main/scala/scala/scalanative/build/NativeLib.scala" \
  "$VENDOR/tools/src/main/scala/scala/scalanative/codegen/IncrementalCodeGenContext.scala" \
  "$VENDOR/tools/src/main/scala/scala/scalanative/codegen/llvm/CodeGen.scala"

cp "$ORIG_JAR" "$PATCHED_JAR"
(cd "$PATCHED_DIR" && "$JAR" uf "$PATCHED_JAR" $(find scala -type f))

sed "s#$ORIG_JAR#$PATCHED_JAR#" "$WORK/tools.cp" > "$WORK/tools-patched.cp"

echo "OK: $PATCHED_JAR"
