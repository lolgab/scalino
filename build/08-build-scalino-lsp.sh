#!/usr/bin/env bash
# Builds scalino-lsp: dotty's own pre-Metals language server
# (Lsp.scala/Main.scala/DottyLanguageServer.scala/Memory.scala) + dotc +
# scala-native's nscplugin, all compiled to NIR and linked into a real Scala
# Native executable -- self-hosted, no JVM/GraalVM native-image anywhere in
# the resulting binary.
#
# Until 2026-09-07 this was a GraalVM-native-image AOT build of JVM
# classfiles compiled from vendor/scala3/language-server/. See
# docs/findings.md's "Self-hosting scalino-lsp" section for the full
# history: why the expected biggest risk (JSON-RPC/lsp4j) was already
# retired before self-hosting started, and the real bugs found+fixed along
# the way (patches/scala3-0011..0013, the release-fast/-Xss64m link-driver
# finding, the javalib URI fix). That prose is the design record; this
# script is the reproducible recipe (folds in what was, until this
# cutover, a separate opt-in experiment at build/selfhost/build-lsp.sh, now
# removed; sibling of 03-build-scalino-dotc.sh).
#
# Prereqs: same as 03-build-scalino-dotc.sh, plus lsp.cp (01-fetch-deps.sh),
# and dist/scalino (07-build-scalino.sh) to regenerate the smoke-test
# fixture's .dotty-ide.json.
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")"
source ./00-env.sh

for f in compiler.cp nativelibs.cp nscplugin.cp nscplugin.jar.txt lsp.cp; do
  [[ -f "$WORK/$f" ]] || { echo "missing $WORK/$f -- run build/01-fetch-deps.sh first" >&2; exit 1; }
done
[[ -f "$WORK/generated/MiniPhaseOverrides.scala" ]] || { echo "missing $WORK/generated/MiniPhaseOverrides.scala -- run build/02b-gen-megaphase-overrides.sh first" >&2; exit 1; }
[[ -x "$DIST/scalino-linkdriver" ]] || { echo "missing $DIST/scalino-linkdriver -- run build/04-build-scalino-linkdriver.sh first" >&2; exit 1; }

SELFHOST_DIR="$WORK/selfhost"
mkdir -p "$SELFHOST_DIR"
FILE_LIST="$SELFHOST_DIR/lsp-file-list.txt"
NIR_OUT="$SELFHOST_DIR/lsp-nir-out"
LINK_WORK="$SELFHOST_DIR/lsp-link-work"

# See 03-build-scalino-dotc.sh's identical block / build/01b-build-patched-javalib.sh.
NATIVELIBS_CP="$SELFHOST_DIR/lsp-nativelibs.cp"
LOCAL_JAVALIB_JAR="$HOME/.ivy2/local/org.scala-native/javalib_native0.5_3/${SCALA_NATIVE_VERSION}-SNAPSHOT/jars/javalib_native0.5_3.jar"
if [[ -f "$LOCAL_JAVALIB_JAR" ]]; then
  { tr "$CP_SEP" '\n' < "$WORK/nativelibs.cp" | grep -v '/javalib_native0\.5_3-'; echo "$LOCAL_JAVALIB_JAR"; } | paste -sd"$CP_SEP" - > "$NATIVELIBS_CP"
  echo "  using locally-built, patched javalib jar: $LOCAL_JAVALIB_JAR"
else
  cp "$WORK/nativelibs.cp" "$NATIVELIBS_CP"
  echo "  WARNING: locally-built javalib jar not found ($LOCAL_JAVALIB_JAR) -- run build/01b-build-patched-javalib.sh first, or patches/scala-native-0003 (URI fix) will NOT take effect. See docs/findings.md."
fi

echo "== generating self-hosted source file list (dotc + LSP + decompiler-support) =="
./selfhost/gen-lsp-file-list.sh > "$FILE_LIST"
echo "  $(wc -l < "$FILE_LIST" | tr -d ' ') files"

echo "== fetching link-only Scala-Native cross-build jars (jsoniter-scala-core, java.time/Locale polyfills) =="
# Compile-time typechecking uses the JVM jsoniter-scala-core jar (lsp.cp) --
# these native cross-build jars are needed only at link time, to satisfy the
# NIR calls nscplugin emits. See docs/findings.md's "Self-hosting
# scalino-lsp" section for why each is needed. --intransitive, fetched ONE
# ARTIFACT PER cs INVOCATION: each jar is published against its own pinned
# scala-native patch version, which pulls in a second, conflicting copy of
# javalib/posixlib/nativelib/clib's C sources if resolved transitively, or
# together in one --intransitive call (a coursier quirk, confirmed
# empirically) -- fetch each separately and concatenate.
LSP_NATIVE_JARS=(
  "com.github.plokhotnyuk.jsoniter-scala:jsoniter-scala-core_native0.5_3:2.37.3"
  "io.github.cquiroz:scala-java-time_native0.5_3:2.6.0"
  "io.github.cquiroz:scala-java-locales_native0.5_3:1.5.4"
  "io.github.cquiroz:cldr-api_native0.5_3:4.5.0"
  "org.portable-scala:portable-scala-reflect_native0.5_2.13:1.1.3"
)
LSP_NATIVE_CP=""
for artifact in "${LSP_NATIVE_JARS[@]}"; do
  jar="$(cs fetch --intransitive "$artifact" --classpath)"
  LSP_NATIVE_CP="${LSP_NATIVE_CP:+$LSP_NATIVE_CP$CP_SEP}$jar"
done

echo "== compiling dotc+nscplugin+lsp to NIR (bootstrap JVM dotc, real nscplugin jar as -Xplugin) =="
rm -rf "$NIR_OUT"
mkdir -p "$NIR_OUT"
NSCPLUGIN_JAR="$(cat "$WORK/nscplugin.jar.txt")"
"$JAVA" -cp "$(cat "$WORK/compiler.cp")$CP_SEP$(cat "$WORK/lsp.cp")" dotty.tools.dotc.Main \
  -Xplugin:"$NSCPLUGIN_JAR" \
  -Xplugin-require:scalanative \
  -Yretain-trees \
  -classpath "$(cat "$NATIVELIBS_CP")$CP_SEP$(cat "$WORK/lsp.cp")" \
  -d "$NIR_OUT" \
  "@$FILE_LIST"

# See 03-build-scalino-dotc.sh / docs/findings.md "Wiring in the real
# backend" for why this is needed (nscplugin's VarHandle-vs-pre-3.8
# lazy-val version sniff).
cp "$ROOT/build/selfhost/compiler.properties" "$NIR_OUT/compiler.properties"

echo "== linking (scalino-linkdriver, entry point dotty.tools.languageserver.Main, --mode release-fast) =="
rm -rf "$LINK_WORK"
mkdir -p "$LINK_WORK"
# -Xss64m: scalino-linkdriver itself (a GraalVM native-image tool, not the
# self-hosted binary being produced) can StackOverflow in its own
# release-fast null-guard-elimination pass against dotc's unusually large,
# heavily-branching methods -- see docs/findings.md's release-fast section.
# release-fast itself is needed for the `definition` endpoint's latency
# (~40s debug-mode, ~10s release-fast, driven by lsp-trace-drive.py's 25s
# client timeout).
"$DIST/scalino-linkdriver" -Xss64m \
  "$NIR_OUT$CP_SEP$(cat "$NATIVELIBS_CP")$CP_SEP$LSP_NATIVE_CP" \
  "$LINK_WORK" \
  dotty.tools.languageserver.Main \
  "$CLANG" \
  "$CLANGPP" \
  info \
  --mode release-fast \
  --embed-resources

BUILT="$LINK_WORK/dotty.tools.languageserver.Main"
[[ -f "$BUILT" ]] || { echo "link did not produce $BUILT" >&2; exit 1; }
cp "$BUILT" "$DIST/scalino-lsp"
echo "OK: $DIST/scalino-lsp"

echo "== smoke test: build/lsp-trace-drive.py against build/lsp-trace-fixture =="
[[ -x "$DIST/scalino" ]] || { echo "missing $DIST/scalino -- run build/07-build-scalino.sh first (needed only to regenerate .dotty-ide.json)" >&2; exit 1; }
FIXTURE="$ROOT/build/lsp-trace-fixture"
(cd "$FIXTURE" && "$DIST/scalino" setup-ide Model.scala Greeter.scala Main.scala >/dev/null)
python3 "$ROOT/build/lsp-trace-drive.py" "$FIXTURE" "$DIST/scalino-lsp" -stdio
echo "OK: smoke test passed (scalino-lsp handled every endpoint lsp-trace-drive.py exercises)"
