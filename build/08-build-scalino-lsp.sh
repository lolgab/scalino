#!/usr/bin/env bash
# Builds scalino-lsp: dotc + scala3-presentation-compiler (Metals' PC engine) + a thin glue
# layer, compiled to NIR and linked into a Scala Native executable (no JVM).
#
# Until 2026-09-13 this shipped dotty's DottyLanguageServer instead; patches/scala3-0017 removed
# it and routes Main.scala to the presentation-compiler-backed `PcLanguageServer` (history in
# docs/findings.md "Self-hosting scalino-lsp").
#
# presentation-compiler (~140 files, plus mtags-shared) is used unmodified. It is written against
# org.eclipse.lsp4j.* and scala.meta.pc.* (mtags-interfaces), which have no Scala Native build,
# so Scala-source shims under those names live in lsp-shim/lsp4j and lsp-shim/mtags-interfaces.
#
# TWO-INVOCATION COMPILE: dotc only auto-applies parenless calls to no-arg methods
# (`params.text` for `params.text()`) on genuinely Java-defined symbols, and the shim, compiled
# jointly with PC sources that call the methods both ways, isn't. So invocation 1 compiles
# presentation-compiler+mtags-shared+glue against the REAL lsp4j/jsonrpc/coursier-interface jars
# and mtags-interfaces javac'd from its sources jar (as Metals' sbt build does), and invocation 2
# compiles the shims standalone to produce the NIR the linker needs for those same names.
#
# Prereqs: as for 03-build-scalino-dotc.sh (01-fetch-deps.sh manifests, dist/scalino-linkdriver
# and driver-classes from 04, dist/scalino from 07), plus network for `cs fetch` and a JDK javac
# ($JAVAC from 00-env.sh).
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")"
source ./00-env.sh

for f in compiler.cp tools.cp nativelibs.cp nscplugin.cp nscplugin.jar.txt lsp.cp; do
  [[ -f "$WORK/$f" ]] || { echo "missing $WORK/$f -- run build/01-fetch-deps.sh first" >&2; exit 1; }
done
[[ -f "$WORK/generated/MiniPhaseOverrides.scala" ]] || { echo "missing $WORK/generated/MiniPhaseOverrides.scala -- run build/02b-gen-megaphase-overrides.sh first" >&2; exit 1; }
source ./link-lib.sh
link_require
[[ -e "$JAVAC" ]] || { echo "missing javac at $JAVAC -- need a full JDK (not JRE) for JAVA_HOME" >&2; exit 1; }

SELFHOST_DIR="$WORK/selfhost"
mkdir -p "$SELFHOST_DIR"
FILE_LIST="$SELFHOST_DIR/lsp-file-list.txt"
SHIM_FILE_LIST="$SELFHOST_DIR/lsp-shim-file-list.txt"
NIR_OUT_MAIN="$SELFHOST_DIR/lsp-nir-out-main"
NIR_OUT_SHIM="$SELFHOST_DIR/lsp-nir-out-shim"
LINK_WORK="$SELFHOST_DIR/lsp-link-work"

# See 03-build-scalino-dotc.sh / 01a-publish-scala-native.sh.
NATIVELIBS_CP="$SELFHOST_DIR/lsp-nativelibs.cp"
LOCAL_JAVALIB_JAR="$HOME/.ivy2/local/org.scala-native/javalib_native0.5_3/${SCALA_NATIVE_VERSION}/jars/javalib_native0.5_3.jar"
if [[ -f "$LOCAL_JAVALIB_JAR" ]]; then
  # Exact-path filter: see 03-build-scalino-dotc.sh (a duplicate jar entry breaks the link).
  { tr "$CP_SEP" '\n' < "$WORK/nativelibs.cp" | grep -v '/javalib_native0\.5_3-' | grep -Fxv "$LOCAL_JAVALIB_JAR"; echo "$LOCAL_JAVALIB_JAR"; } | paste -sd"$CP_SEP" - > "$NATIVELIBS_CP"
  echo "  using locally-built, patched javalib jar: $LOCAL_JAVALIB_JAR"
else
  cp "$WORK/nativelibs.cp" "$NATIVELIBS_CP"
  echo "  WARNING: locally-built javalib jar not found ($LOCAL_JAVALIB_JAR) -- run build/01a-publish-scala-native.sh first, or patches/scala-native-0003 (URI fix) will NOT take effect. See docs/findings.md."
fi

echo "== generating self-hosted source file lists (dotc + LSP + presentation-compiler + mtags-shared, and the lsp4j/mtags-interfaces NIR-track shim) =="
./selfhost/gen-lsp-file-list.sh | to_native_path_list > "$FILE_LIST"
echo "  $(wc -l < "$FILE_LIST" | tr -d ' ') files (main invocation)"
{
  find "$ROOT/lsp-shim/lsp4j" -name "*.scala"
  find "$ROOT/lsp-shim/mtags-interfaces" -name "*.scala"
  # Real deps with no Scala Native cross-build, needed by mtags-shared/presentation-compiler:
  # the real jars satisfy invocation 1's typecheck, these produce the NIR.
  find "$ROOT/lsp-shim/java-util-logging" -name "*.scala"
  find "$ROOT/lsp-shim/coursierapi" -name "*.scala"
} | to_native_path_list > "$SHIM_FILE_LIST"
echo "  $(wc -l < "$SHIM_FILE_LIST" | tr -d ' ') files (shim NIR-track invocation)"

echo "== fetching real lsp4j/jsonrpc/coursier-interface jars (classpath-only: typechecking against them keeps Java auto-application working; never shipped as NIR) =="
PC_REAL_JARS_CP="$(cs fetch --classpath \
  org.eclipse.lsp4j:org.eclipse.lsp4j:1.0.0 \
  org.eclipse.lsp4j:org.eclipse.lsp4j.jsonrpc:1.0.0 \
  io.get-coursier:interface:1.0.18 | tr -d '\r')"

echo "== fetching+javac-ing mtags-interfaces' real sources jar (classpath-only, like the lsp4j jars above) =="
MTAGS_IFACE_SRC_JAR="$(cs fetch --classifier sources --intransitive org.scalameta:mtags-interfaces:1.6.7 | tr -d '\r')"
MTAGS_IFACE_SRC_DIR="$SELFHOST_DIR/mtags-interfaces-src"
MTAGS_IFACE_CLASSES_DIR="$SELFHOST_DIR/mtags-interfaces-classes"
rm -rf "$MTAGS_IFACE_SRC_DIR" "$MTAGS_IFACE_CLASSES_DIR"
mkdir -p "$MTAGS_IFACE_SRC_DIR" "$MTAGS_IFACE_CLASSES_DIR"
(cd "$MTAGS_IFACE_SRC_DIR" && "$JAR" xf "$MTAGS_IFACE_SRC_JAR")
"$JAVAC" -nowarn -cp "$PC_REAL_JARS_CP" -d "$MTAGS_IFACE_CLASSES_DIR" $(find "$MTAGS_IFACE_SRC_DIR" -name "*.java")

echo "== compiling dotc+nscplugin+language-server+presentation-compiler+mtags-shared to NIR (invocation 1: real lsp4j/mtags-interfaces jars on -classpath) =="
rm -rf "$NIR_OUT_MAIN"
mkdir -p "$NIR_OUT_MAIN"
NSCPLUGIN_JAR="$(cat "$WORK/nscplugin.jar.txt")"
# The compiler sources of Scala 3.10+ are written for -Yexplicit-nulls (scala3/project/Build.scala); the
# rest of the source set (nscplugin, nir, our patches) is not, hence unsafeNulls for the whole compile.
"$JAVA" -cp "$(cat "$WORK/compiler.cp")$CP_SEP$(cat "$WORK/lsp.cp")" dotty.tools.dotc.Main \
  -Yexplicit-nulls -language:unsafeNulls -language:implicitConversions \
  -Xplugin:"$NSCPLUGIN_JAR" \
  -Xplugin-require:scalanative \
  -Yretain-trees \
  -classpath "$(cat "$NATIVELIBS_CP")$CP_SEP$(cat "$WORK/lsp.cp")$CP_SEP$PC_REAL_JARS_CP$CP_SEP$(to_native_path "$MTAGS_IFACE_CLASSES_DIR")" \
  -d "$NIR_OUT_MAIN" \
  "@$FILE_LIST"

# See 03-build-scalino-dotc.sh (nscplugin's VarHandle-vs-pre-3.8 lazy-val version sniff).
cp "$ROOT/build/selfhost/compiler.properties" "$NIR_OUT_MAIN/compiler.properties"

echo "== compiling lsp-shim/lsp4j+lsp-shim/mtags-interfaces to NIR (invocation 2: standalone; supplies the NIR definitions for the org.eclipse.lsp4j.*/scala.meta.pc.* symbols) =="
rm -rf "$NIR_OUT_SHIM"
mkdir -p "$NIR_OUT_SHIM"
"$JAVA" -cp "$(cat "$WORK/compiler.cp")" dotty.tools.dotc.Main \
  -Xplugin:"$NSCPLUGIN_JAR" \
  -Xplugin-require:scalanative \
  -Yretain-trees \
  -classpath "$(cat "$NATIVELIBS_CP")" \
  -d "$NIR_OUT_SHIM" \
  "@$SHIM_FILE_LIST"

echo "== fetching link-only Scala-Native cross-build jars (jsoniter-scala-core, java.time/Locale polyfills) =="
# --intransitive, one artifact per `cs fetch`: see 03-build-scalino-dotc.sh.
LSP_NATIVE_JARS=(
  "com.github.plokhotnyuk.jsoniter-scala:jsoniter-scala-core_native0.5_3:2.37.3"
  "io.github.cquiroz:scala-java-time_native0.5_3:2.6.0"
  "io.github.cquiroz:scala-java-locales_native0.5_3:1.5.4"
  "io.github.cquiroz:cldr-api_native0.5_3:4.5.0"
  "org.portable-scala:portable-scala-reflect_native0.5_2.13:1.1.3"
)
LSP_NATIVE_CP=""
for artifact in "${LSP_NATIVE_JARS[@]}"; do
  jar="$(cs fetch --intransitive "$artifact" --classpath | tr -d '\r')"
  LSP_NATIVE_CP="${LSP_NATIVE_CP:+$LSP_NATIVE_CP$CP_SEP}$jar"
done

echo "== linking (scalino-linkdriver -- entry point dotty.tools.languageserver.Main, --mode $SCALINO_LINK_MODE) =="
source ./pgo/pgo-lib.sh
# The link classpath is kept for build/pgo/pgo-lsp.sh, which relinks it.
LSP_LINK_CP="$(to_native_path "$NIR_OUT_MAIN")$CP_SEP$(to_native_path "$NIR_OUT_SHIM")$CP_SEP$(cat "$NATIVELIBS_CP")$CP_SEP$LSP_NATIVE_CP"
printf '%s' "$LSP_LINK_CP" > "$SELFHOST_DIR/lsp-link.cp"
# Native scalino-linkdriver (04b), not LinkDriver on a JVM; see 03-build-scalino-dotc.sh.
# SCALANATIVE_EVAC_ENABLE=1 (compacting GC), for this command only: linking dotc-sized programs
# fragments the Immix heap badly and otherwise runs out of heap.
LINK_EXTRA=()
lsp_link() {
  rm -rf "$LINK_WORK"
  mkdir -p "$LINK_WORK"
  LINK_ENV=("$@")
  linkdriver_run \
    "$LSP_LINK_CP" \
    "$(to_native_path "$LINK_WORK")" \
    dotty.tools.languageserver.Main \
    --mode "$SCALINO_LINK_MODE" \
    --lto "$SCALINO_LINK_LTO" \
    --no-incremental-compilation \
    --embed-resources \
    ${LINK_EXTRA[@]+"${LINK_EXTRA[@]}"}
}
# Release builds use the committed PGO profiles (pgo/, see docs/pgo.md); a
# failing PGO link falls back to the plain one rather than failing the build.
pgo_prepare lsp
if [[ ${#PGO_ENV[@]} -gt 0 ]]; then
  lsp_link "${PGO_ENV[@]}" || { echo "WARNING: PGO link failed, relinking without PGO" >&2; lsp_link; }
else
  lsp_link
fi

BUILT="$LINK_WORK/dotty.tools.languageserver.Main"
[[ -f "$BUILT" ]] || { echo "link did not produce $BUILT" >&2; exit 1; }
rm -f "$DIST/scalino-lsp" # new inode, see 04-build-scalino-linkdriver.sh
cp "$BUILT" "$DIST/scalino-lsp"
# macOS/arm64: the linker's ad-hoc signature is sometimes rejected at exec time (silent SIGKILL,
# AMFI, not a runtime crash). Re-signing after the copy fixes it. No-op on Linux (guarded).
if command -v codesign >/dev/null 2>&1; then
  codesign -s - -f "$DIST/scalino-lsp"
fi
echo "OK: $DIST/scalino-lsp"

# CI builds this in parallel with 03/03b/07, so dist/scalino doesn't exist
# yet there; it runs 08b-smoke-lsp.sh afterwards instead.
if [[ "${SCALINO_SKIP_LSP_SMOKE:-}" != 1 ]]; then
  ./08b-smoke-lsp.sh
fi
