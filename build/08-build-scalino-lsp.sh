#!/usr/bin/env bash
# Builds scalino-lsp: dotc + scala3-presentation-compiler (Metals' own PC
# engine) + a thin glue layer, all compiled to NIR and linked into a real
# Scala Native executable -- self-hosted, no JVM/GraalVM in the resulting
# binary.
#
# Until 2026-09-13 this shipped dotty's own narrow pre-Metals
# DottyLanguageServer backend instead (still self-hosted, but a much
# smaller feature surface than Metals' own PC engine gives -- no
# extract-method/inlay-hints/richer completions/etc). That backend has been
# removed entirely (patches/scala3-0017-lsp-pc-backend-switch.patch deletes
# DottyLanguageServer.scala/Memory.scala from vendor/scala3 and rewrites
# Main.scala's dispatch to call presentation-compiler-backed
# `PcLanguageServer` directly) -- see docs/findings.md's "Self-hosting
# scalino-lsp" section for the DottyLanguageServer-era history, and
# `~/.claude/plans/lovely-jumping-anchor.md` for the presentation-compiler
# cutover's design record.
#
# presentation-compiler's own ~140 files (across presentation-compiler/ +
# its mtags-shared dependency) need zero edits -- the only blocker is that
# they're written against org.eclipse.lsp4j.* and org.scalameta:mtags-interfaces'
# scala.meta.pc.*, neither of which have a Scala Native build or any Java
# bytecode->NIR path in this toolchain. Fixed with a Scala-source shim under
# those exact package names (lsp-shim/lsp4j, lsp-shim/mtags-interfaces).
#
# TWO-INVOCATION COMPILE: dotc's "auto-application" leniency for parenless
# no-arg method calls (`params.text` instead of `params.text()`) only
# applies when the called symbol is genuinely JavaDefined -- real lsp4j/
# mtags-interfaces classfiles get it automatically, but a fresh Scala port
# compiled jointly with presentation-compiler's real (unmodifiable) source,
# which calls the same methods both ways, does not. Fix: compile
# presentation-compiler+mtags-shared+the glue against the REAL lsp4j/
# jsonrpc/coursier-interface jars and mtags-interfaces javac'd from its real
# sources jar (invocation 1 below -- matching how Metals' own sbt build
# actually depends on these), while lsp-shim/lsp4j+lsp-shim/mtags-interfaces
# compile SEPARATELY, standalone, to produce the actual NIR definitions the
# linker needs for those same symbolic names (invocation 2) -- mirrors the
# existing nativelibs.cp precompiled-NIR-plus-fresh-compile pattern used
# elsewhere in this build.
#
# Prereqs: same as build/03-build-scalino-dotc.sh (compiler.cp/tools.cp/
# nativelibs.cp/nscplugin.cp/nscplugin.jar.txt/lsp.cp via 01-fetch-deps.sh,
# dist/scalino-linkdriver + driver-classes via 04-build-scalino-linkdriver.sh,
# dist/scalino via 07-build-scalino.sh), plus network access for `cs fetch`
# (real lsp4j/jsonrpc/coursier-interface jars + mtags-interfaces' sources
# jar) and a JDK javac (build/00-env.sh's $JAVAC).
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")"
source ./00-env.sh

for f in compiler.cp tools.cp nativelibs.cp nscplugin.cp nscplugin.jar.txt lsp.cp; do
  [[ -f "$WORK/$f" ]] || { echo "missing $WORK/$f -- run build/01-fetch-deps.sh first" >&2; exit 1; }
done
[[ -f "$WORK/tools-patched-jvm.cp" ]] || { echo "missing $WORK/tools-patched-jvm.cp -- run build/04a-patch-tools.sh first" >&2; exit 1; }
[[ -f "$WORK/generated/MiniPhaseOverrides.scala" ]] || { echo "missing $WORK/generated/MiniPhaseOverrides.scala -- run build/02b-gen-megaphase-overrides.sh first" >&2; exit 1; }
[[ -x "$DIST/scalino-linkdriver" ]] || { echo "missing $DIST/scalino-linkdriver -- run build/04-build-scalino-linkdriver.sh first" >&2; exit 1; }
[[ -d "$WORK/driver-classes" ]] || { echo "missing $WORK/driver-classes -- run build/04-build-scalino-linkdriver.sh first" >&2; exit 1; }
[[ -e "$JAVAC" ]] || { echo "missing javac at $JAVAC -- need a full JDK (not JRE) for JAVA_HOME" >&2; exit 1; }

SELFHOST_DIR="$WORK/selfhost"
mkdir -p "$SELFHOST_DIR"
FILE_LIST="$SELFHOST_DIR/lsp-file-list.txt"
SHIM_FILE_LIST="$SELFHOST_DIR/lsp-shim-file-list.txt"
NIR_OUT_MAIN="$SELFHOST_DIR/lsp-nir-out-main"
NIR_OUT_SHIM="$SELFHOST_DIR/lsp-nir-out-shim"
LINK_WORK="$SELFHOST_DIR/lsp-link-work"

# See 03-build-scalino-dotc.sh's identical block / build/01b-build-patched-javalib.sh.
NATIVELIBS_CP="$SELFHOST_DIR/lsp-nativelibs.cp"
LOCAL_JAVALIB_JAR="$HOME/.ivy2/local/org.scala-native/javalib_native0.5_3/${SCALA_NATIVE_VERSION}-SNAPSHOT/jars/javalib_native0.5_3.jar"
if [[ -f "$LOCAL_JAVALIB_JAR" ]]; then
  # See build/03-build-scalino-dotc.sh's identical substitution for why the
  # exact-path filter (not just the old "-<version>" suffix pattern) is
  # needed: 01b-build-patched-javalib.sh may have already rewritten
  # $WORK/nativelibs.cp to point straight at $LOCAL_JAVALIB_JAR, and listing
  # it twice makes scala-native's linker fail with duplicate symbols.
  { tr "$CP_SEP" '\n' < "$WORK/nativelibs.cp" | grep -v '/javalib_native0\.5_3-' | grep -Fxv "$LOCAL_JAVALIB_JAR"; echo "$LOCAL_JAVALIB_JAR"; } | paste -sd"$CP_SEP" - > "$NATIVELIBS_CP"
  echo "  using locally-built, patched javalib jar: $LOCAL_JAVALIB_JAR"
else
  cp "$WORK/nativelibs.cp" "$NATIVELIBS_CP"
  echo "  WARNING: locally-built javalib jar not found ($LOCAL_JAVALIB_JAR) -- run build/01b-build-patched-javalib.sh first, or patches/scala-native-0003 (URI fix) will NOT take effect. See docs/findings.md."
fi

echo "== generating self-hosted source file lists (dotc + LSP + presentation-compiler + mtags-shared, and the lsp4j/mtags-interfaces NIR-track shim) =="
./selfhost/gen-lsp-file-list.sh | to_native_path_list > "$FILE_LIST"
echo "  $(wc -l < "$FILE_LIST" | tr -d ' ') files (main invocation)"
{
  find "$ROOT/lsp-shim/lsp4j" -name "*.scala"
  find "$ROOT/lsp-shim/mtags-interfaces" -name "*.scala"
  # Real deps with no Scala Native cross-build, needed only by mtags-shared/
  # presentation-compiler (not by presentation-compiler's public API surface
  # itself) -- real jars satisfy invocation 1's typecheck (java.util.logging
  # comes from the real JDK automatically; coursierapi from PC_REAL_JARS_CP's
  # io.get-coursier:interface jar), these produce the actual NIR.
  find "$ROOT/lsp-shim/java-util-logging" -name "*.scala"
  find "$ROOT/lsp-shim/coursierapi" -name "*.scala"
} | to_native_path_list > "$SHIM_FILE_LIST"
echo "  $(wc -l < "$SHIM_FILE_LIST" | tr -d ' ') files (shim NIR-track invocation)"

echo "== fetching real lsp4j/jsonrpc/coursier-interface jars (classpath-only -- presentation-compiler+mtags-shared compile against these for typechecking, matching Metals' own sbt build; never compiled from source or shipped as NIR) =="
PC_REAL_JARS_CP="$(cs fetch --classpath \
  org.eclipse.lsp4j:org.eclipse.lsp4j:1.0.0 \
  org.eclipse.lsp4j:org.eclipse.lsp4j.jsonrpc:1.0.0 \
  io.get-coursier:interface:1.0.18 | tr -d '\r')"

echo "== fetching+javac-ing mtags-interfaces' real sources jar (classpath-only -- same reasoning as the lsp4j jars above; javac gets real Java-defined classfiles' auto-application leniency, which a Scala port compiled jointly with presentation-compiler would not) =="
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
"$JAVA" -cp "$(cat "$WORK/compiler.cp")$CP_SEP$(cat "$WORK/lsp.cp")" dotty.tools.dotc.Main \
  -Xplugin:"$NSCPLUGIN_JAR" \
  -Xplugin-require:scalanative \
  -Yretain-trees \
  -classpath "$(cat "$NATIVELIBS_CP")$CP_SEP$(cat "$WORK/lsp.cp")$CP_SEP$PC_REAL_JARS_CP$CP_SEP$(to_native_path "$MTAGS_IFACE_CLASSES_DIR")" \
  -d "$NIR_OUT_MAIN" \
  "@$FILE_LIST"

# See 03-build-scalino-dotc.sh / docs/findings.md "Wiring in the real
# backend" for why this is needed (nscplugin's VarHandle-vs-pre-3.8
# lazy-val version sniff).
cp "$ROOT/build/selfhost/compiler.properties" "$NIR_OUT_MAIN/compiler.properties"

echo "== compiling lsp-shim/lsp4j+lsp-shim/mtags-interfaces to NIR (invocation 2: standalone, no real jars -- these ARE the real NIR definitions invocation 1's symbolic org.eclipse.lsp4j.*/scala.meta.pc.* calls resolve against at link time) =="
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
# Same reasoning/pinning-fragility notes as build/03-build-scalino-dotc.sh's
# identical block: --intransitive, fetched ONE ARTIFACT PER cs INVOCATION.
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

echo "== linking (LinkDriver, run on the JVM -- entry point dotty.tools.languageserver.Main, --mode release-size) =="
rm -rf "$LINK_WORK"
mkdir -p "$LINK_WORK"
# Run LinkDriver's own main directly via java, not the compiled native
# $DIST/scalino-linkdriver binary -- see 03-build-scalino-dotc.sh's identical
# link step for the full rationale (tools-patched-jvm.cp, not raw tools.cp,
# for the same NoSuchMethodError-avoidance reason explained there).
DRIVER_CP="$(cat "$WORK/compiler.cp")$CP_SEP$(cat "$WORK/tools-patched-jvm.cp")$CP_SEP$(to_native_path "$WORK/driver-classes")"
"$JAVA" \
  -XX:MaxRAMPercentage=80.0 \
  -cp "$DRIVER_CP" \
    LinkDriver \
    "$(to_native_path "$NIR_OUT_MAIN")$CP_SEP$(to_native_path "$NIR_OUT_SHIM")$CP_SEP$(cat "$NATIVELIBS_CP")$CP_SEP$LSP_NATIVE_CP" \
    "$(to_native_path "$LINK_WORK")" \
    dotty.tools.languageserver.Main \
    "$CLANG" \
    "$CLANGPP" \
    info \
    --mode release-fast \
    --embed-resources

BUILT="$LINK_WORK/dotty.tools.languageserver.Main"
[[ -f "$BUILT" ]] || { echo "link did not produce $BUILT" >&2; exit 1; }
rm -f "$DIST/scalino-lsp" # new inode, see 04-build-scalino-linkdriver.sh
cp "$BUILT" "$DIST/scalino-lsp"
# On macOS/arm64, the linker's own ad-hoc signature on this particular
# binary is sometimes rejected by the kernel at exec time (observed: exec
# fails silently, SIGKILL, 0 CPU time used, lldb can't even attach --
# classic AMFI code-signature rejection, NOT a runtime crash in our code).
# Re-signing ad-hoc ourselves after the copy reliably fixes it. Harmless
# no-op on Linux (command doesn't exist there, hence the guard).
if command -v codesign >/dev/null 2>&1; then
  codesign -s - -f "$DIST/scalino-lsp"
fi
echo "OK: $DIST/scalino-lsp"

echo "== smoke test: build/lsp-trace-drive.py against build/lsp-trace-fixture =="
[[ -x "$DIST/scalino" ]] || { echo "missing $DIST/scalino -- run build/07-build-scalino.sh first (needed only to regenerate .scalino-build/scalino-lsp.json)" >&2; exit 1; }
FIXTURE="$ROOT/build/lsp-trace-fixture"
(cd "$FIXTURE" && "$DIST/scalino" setup-ide Model.scala Greeter.scala Main.scala >/dev/null)
python3 "$ROOT/build/lsp-trace-drive.py" "$FIXTURE" "$DIST/scalino-lsp" -stdio
echo "OK: smoke test passed (scalino-lsp handled every endpoint lsp-trace-drive.py exercises)"
