#!/usr/bin/env bash
# Builds scalino-dotc: dotc + scala-native's compiler plugin (nscplugin),
# both compiled to NIR and linked into a real Scala Native executable --
# self-hosted, no JVM/GraalVM native-image anywhere in the resulting binary.
#
# Until 2026-09-07 this was a GraalVM native-image build of the published dotty bytecode; see
# docs/findings.md "Self-hosting" for the history and why each compiler source is excluded.
#
# Prereqs: 00b-setup-vendor.sh, 01-fetch-deps.sh, 01a-publish-scala-native.sh,
# 02-build-java-base.sh, 02b-gen-megaphase-overrides.sh, 04-build-scalino-linkdriver.sh (the
# link step runs it, and the smoke test links Hello.scala with $DIST/scalino-linkdriver).
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")"
source ./00-env.sh

for f in compiler.cp tools.cp nativelibs.cp nscplugin.cp nscplugin.jar.txt; do
  [[ -f "$WORK/$f" ]] || { echo "missing $WORK/$f -- run build/01-fetch-deps.sh first" >&2; exit 1; }
done
[[ -f "$WORK/generated/MiniPhaseOverrides.scala" ]] || { echo "missing $WORK/generated/MiniPhaseOverrides.scala -- run build/02b-gen-megaphase-overrides.sh first" >&2; exit 1; }
source ./link-lib.sh
link_require
[[ -f "$DIST/java.base.jar" ]] || { echo "missing $DIST/java.base.jar -- run build/02-build-java-base.sh first" >&2; exit 1; }

SELFHOST_DIR="$WORK/selfhost"
mkdir -p "$SELFHOST_DIR"
FILE_LIST="$SELFHOST_DIR/file-list.txt"
NIR_OUT="$SELFHOST_DIR/nir-out"
LINK_WORK="$SELFHOST_DIR/link-work"

# Substitute the locally-built javalib jar (patches/scala-native-0003: UnixPath/WindowsPath#toUri
# "//" authority; see 01a) in a copy of nativelibs.cp; $WORK/nativelibs.cp stays untouched since
# 06-package.sh vendors it for user-code compilation.
NATIVELIBS_CP="$SELFHOST_DIR/nativelibs.cp"
LOCAL_JAVALIB_JAR="$HOME/.ivy2/local/org.scala-native/javalib_native0.5_3/${SCALA_NATIVE_VERSION}/jars/javalib_native0.5_3.jar"
if [[ -f "$LOCAL_JAVALIB_JAR" ]]; then
  # -F/-x rather than the old regex: 01a rewrites $WORK/nativelibs.cp to already contain
  # $LOCAL_JAVALIB_JAR, and listing a jar twice makes clang fail with "duplicate symbol" for every
  # native javalib symbol (the linker unpacks each entry separately). This keeps it idempotent.
  { tr "$CP_SEP" '\n' < "$WORK/nativelibs.cp" | grep -v '/javalib_native0\.5_3-' | grep -Fxv "$LOCAL_JAVALIB_JAR"; echo "$LOCAL_JAVALIB_JAR"; } | paste -sd"$CP_SEP" - > "$NATIVELIBS_CP"
  echo "  using locally-built, patched javalib jar: $LOCAL_JAVALIB_JAR"
else
  cp "$WORK/nativelibs.cp" "$NATIVELIBS_CP"
  echo "  WARNING: locally-built javalib jar not found ($LOCAL_JAVALIB_JAR) -- run build/01a-publish-scala-native.sh first, or patches/scala-native-0003 (URI fix) will NOT take effect. See docs/findings.md."
fi

echo "== generating self-hosted source file list =="
./selfhost/gen-file-list.sh | to_native_path_list > "$FILE_LIST"
echo "  $(wc -l < "$FILE_LIST" | tr -d ' ') files"

echo "== compiling dotc+nscplugin to NIR (bootstrap JVM dotc, real nscplugin jar as -Xplugin) =="
rm -rf "$NIR_OUT"
mkdir -p "$NIR_OUT"
NSCPLUGIN_JAR="$(cat "$WORK/nscplugin.jar.txt")"
# The compiler sources of Scala 3.10+ are written for -Yexplicit-nulls (scala3/project/Build.scala); the
# rest of the source set (nscplugin, nir, our patches) is not, hence unsafeNulls for the whole compile.
"$JAVA" -cp "$(cat "$WORK/compiler.cp")" dotty.tools.dotc.Main \
  -Yexplicit-nulls -language:unsafeNulls -language:implicitConversions \
  -Xplugin:"$NSCPLUGIN_JAR" \
  -Xplugin-require:scalanative \
  -Yretain-trees \
  -classpath "$(cat "$NATIVELIBS_CP")" \
  -d "$NIR_OUT" \
  "@$FILE_LIST"

# dotty.tools.dotc.config.Properties#versionNumberString reads this classpath resource, which is
# bundled in the published compiler jar but absent when compiling from source; without it
# nscplugin's version-gated lazy-val codegen breaks (docs/findings.md "Wiring in the real backend").
cp "$ROOT/build/selfhost/compiler.properties" "$NIR_OUT/compiler.properties"

echo "== linking (scalino-linkdriver, entry point dotty.tools.dotc.Main) =="
source ./pgo/pgo-lib.sh
# The classpath needs the just-compiled NIR plus the Scala-Native-cross-compiled stdlib
# (NIR_OUT alone has no java.lang.Object etc.).
#
# Runs the native scalino-linkdriver (04b-fetch-scalino-linkdriver.sh: the one
# from the latest release; 04-build-scalino-linkdriver.sh's JVM-built one also
# works) rather than LinkDriver on a JVM: it reads the classpath jars through
# the ZipFileSystemProvider (patches/scala-native-0009) and the GC's evacuation
# keeps its heap small enough for dotc's link.
#
# --mode release-size is the shipped build (v0.0.1 shipped the default debug mode). The old JVM
# run needed -Xss64m because release-fast's null-guard-elimination pass overflowed the stack on
# dotc's large methods (docs/findings.md); if the native linkdriver hits that, raise `ulimit -s`.
# SCALANATIVE_EVAC_ENABLE=1 (compacting GC), for this command only: linking dotc-sized programs
# fragments the Immix heap badly (interflow garbage) and otherwise runs out of heap.
LINK_EXTRA=()
dotc_link() {
  rm -rf "$LINK_WORK"
  mkdir -p "$LINK_WORK"
  LINK_ENV=("$@")
  linkdriver_run \
    "$(to_native_path "$NIR_OUT")$CP_SEP$(cat "$NATIVELIBS_CP")" \
    "$(to_native_path "$LINK_WORK")" \
    dotty.tools.dotc.Main \
    --mode "$SCALINO_LINK_MODE" \
    --lto "$SCALINO_LINK_LTO" \
    --multithreading \
    --no-incremental-compilation \
    --embed-resources \
    ${LINK_EXTRA[@]+"${LINK_EXTRA[@]}"}
}
# Release builds use the committed PGO profiles (pgo/, see docs/pgo.md); a
# failing PGO link falls back to the plain one rather than failing the build.
pgo_prepare dotc
if [[ ${#PGO_ENV[@]} -gt 0 ]]; then
  dotc_link "${PGO_ENV[@]}" || { echo "WARNING: PGO link failed, relinking without PGO" >&2; dotc_link; }
else
  dotc_link
fi

BUILT="$LINK_WORK/dotty.tools.dotc.Main"
[[ -f "$BUILT" ]] || { echo "link did not produce $BUILT" >&2; exit 1; }
rm -f "$DIST/scalino-dotc" # new inode, see 04-build-scalino-linkdriver.sh
cp "$BUILT" "$DIST/scalino-dotc"
echo "OK: $DIST/scalino-dotc"

echo "== smoke test: compiling+linking+running examples/Hello.scala =="
SMOKE_DIR="$SELFHOST_DIR/smoke"
rm -rf "$SMOKE_DIR"
mkdir -p "$SMOKE_DIR/out" "$SMOKE_DIR/link"
"$DIST/scalino-dotc" \
  -Xplugin:"$NSCPLUGIN_JAR" \
  -Xplugin-require:scalanative \
  -Yretain-trees \
  -javabootclasspath "$DIST/java.base.jar" \
  -classpath "$(cat "$NATIVELIBS_CP")" \
  -d "$SMOKE_DIR/out" \
  "$ROOT/examples/Hello.scala"
"$DIST/scalino-linkdriver" \
  "$(to_native_path "$SMOKE_DIR/out")$CP_SEP$(cat "$NATIVELIBS_CP")" \
  "$(to_native_path "$SMOKE_DIR/link")" \
  Hello \
  "$CLANG" \
  "$CLANGPP" \
  quiet
OUT="$("$SMOKE_DIR/link/Hello")"
[[ "$OUT" == "hello from scalino" ]] || { echo "smoke test FAILED: expected 'hello from scalino', got '$OUT'" >&2; exit 1; }
echo "OK: smoke test passed (scalino-dotc compiled+linked+ran Hello.scala)"
