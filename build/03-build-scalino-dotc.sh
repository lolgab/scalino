#!/usr/bin/env bash
# Builds scalino-dotc: dotc + scala-native's compiler plugin (nscplugin),
# both compiled to NIR and linked into a real Scala Native executable --
# self-hosted, no JVM/GraalVM native-image anywhere in the resulting binary.
#
# Until 2026-09-07 this was a GraalVM-native-image AOT build of the
# published, unmodified dotty JVM bytecode. See docs/findings.md's
# "Self-hosting" arc for the full history: why each excluded compiler
# source file is excluded, and the many real bugs found+fixed getting here
# (patches/scala3-0001..0013, patches/scala-native-0001..0003). That prose
# is the design record; this script is the reproducible recipe (folds in
# what was, until this cutover, a separate opt-in experiment at
# build/selfhost/build-dotc.sh, now removed).
#
# Prereqs: 00b-setup-vendor.sh, 01-fetch-deps.sh, 01b-build-patched-javalib.sh,
# 02-build-java-base.sh, 02b-gen-megaphase-overrides.sh,
# 04-build-scalino-linkdriver.sh (scalino-linkdriver is used as the link step
# here too -- it remains a GraalVM-native-image tool; see docs/findings.md's
# cutover entry for why that one binary hasn't been self-hosted yet).
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")"
source ./00-env.sh

for f in compiler.cp nativelibs.cp nscplugin.cp nscplugin.jar.txt; do
  [[ -f "$WORK/$f" ]] || { echo "missing $WORK/$f -- run build/01-fetch-deps.sh first" >&2; exit 1; }
done
[[ -f "$WORK/generated/MiniPhaseOverrides.scala" ]] || { echo "missing $WORK/generated/MiniPhaseOverrides.scala -- run build/02b-gen-megaphase-overrides.sh first" >&2; exit 1; }
[[ -x "$DIST/scalino-linkdriver" ]] || { echo "missing $DIST/scalino-linkdriver -- run build/04-build-scalino-linkdriver.sh first" >&2; exit 1; }
[[ -f "$DIST/java.base.jar" ]] || { echo "missing $DIST/java.base.jar -- run build/02-build-java-base.sh first" >&2; exit 1; }

SELFHOST_DIR="$WORK/selfhost"
mkdir -p "$SELFHOST_DIR"
FILE_LIST="$SELFHOST_DIR/file-list.txt"
NIR_OUT="$SELFHOST_DIR/nir-out"
LINK_WORK="$SELFHOST_DIR/link-work"

# Substitute the published javalib_native0.5_3 jar for the locally-built one
# with patches/scala-native-0003 (UnixPath/WindowsPath#toUri missing "//"
# authority) actually applied -- see build/01b-build-patched-javalib.sh.
# Scoped to a copy of nativelibs.cp under $SELFHOST_DIR; $WORK/nativelibs.cp
# itself is left untouched (still what 06-package.sh vendors for user-code
# compilation, unaffected by which jar scalino-dotc's own build used).
NATIVELIBS_CP="$SELFHOST_DIR/nativelibs.cp"
LOCAL_JAVALIB_JAR="$HOME/.ivy2/local/org.scala-native/javalib_native0.5_3/${SCALA_NATIVE_VERSION}-SNAPSHOT/jars/javalib_native0.5_3.jar"
if [[ -f "$LOCAL_JAVALIB_JAR" ]]; then
  { tr ':' '\n' < "$WORK/nativelibs.cp" | grep -v '/javalib_native0\.5_3-'; echo "$LOCAL_JAVALIB_JAR"; } | paste -sd: - > "$NATIVELIBS_CP"
  echo "  using locally-built, patched javalib jar: $LOCAL_JAVALIB_JAR"
else
  cp "$WORK/nativelibs.cp" "$NATIVELIBS_CP"
  echo "  WARNING: locally-built javalib jar not found ($LOCAL_JAVALIB_JAR) -- run build/01b-build-patched-javalib.sh first, or patches/scala-native-0003 (URI fix) will NOT take effect. See docs/findings.md."
fi

echo "== generating self-hosted source file list =="
./selfhost/gen-file-list.sh > "$FILE_LIST"
echo "  $(wc -l < "$FILE_LIST" | tr -d ' ') files"

echo "== compiling dotc+nscplugin to NIR (bootstrap JVM dotc, real nscplugin jar as -Xplugin) =="
rm -rf "$NIR_OUT"
mkdir -p "$NIR_OUT"
NSCPLUGIN_JAR="$(cat "$WORK/nscplugin.jar.txt")"
"$JAVA" -cp "$(cat "$WORK/compiler.cp")" dotty.tools.dotc.Main \
  -Xplugin:"$NSCPLUGIN_JAR" \
  -Xplugin-require:scalanative \
  -Yretain-trees \
  -classpath "$(cat "$NATIVELIBS_CP")" \
  -d "$NIR_OUT" \
  "@$FILE_LIST"

# dotty.tools.dotc.config.Properties#versionNumberString reads this as a
# classpath resource, normally bundled inside the published scala3-compiler
# jar -- absent when compiling dotc from source. Missing it breaks
# nscplugin's version-gated lazy-val codegen (VarHandle vs pre-3.8 CAS) --
# see docs/findings.md "Wiring in the real backend" for the full root cause.
cp "$ROOT/build/selfhost/compiler.properties" "$NIR_OUT/compiler.properties"

echo "== linking (scalino-linkdriver, entry point dotty.tools.dotc.Main) =="
rm -rf "$LINK_WORK"
mkdir -p "$LINK_WORK"
# scalino-linkdriver's classpath needs both the just-compiled NIR AND the
# Scala-Native-cross-compiled stdlib the compiled code links against --
# NIR_OUT alone has no java.lang.Object etc.
"$DIST/scalino-linkdriver" \
  "$NIR_OUT:$(cat "$NATIVELIBS_CP")" \
  "$LINK_WORK" \
  dotty.tools.dotc.Main \
  "$CLANG" \
  "$CLANGPP" \
  info \
  --embed-resources

BUILT="$LINK_WORK/dotty.tools.dotc.Main"
[[ -f "$BUILT" ]] || { echo "link did not produce $BUILT" >&2; exit 1; }
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
  "$SMOKE_DIR/out:$(cat "$NATIVELIBS_CP")" \
  "$SMOKE_DIR/link" \
  Hello \
  "$CLANG" \
  "$CLANGPP" \
  quiet
OUT="$("$SMOKE_DIR/link/Hello")"
[[ "$OUT" == "hello from scalino" ]] || { echo "smoke test FAILED: expected 'hello from scalino', got '$OUT'" >&2; exit 1; }
echo "OK: smoke test passed (scalino-dotc compiled+linked+ran Hello.scala)"
