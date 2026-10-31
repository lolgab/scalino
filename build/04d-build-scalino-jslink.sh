#!/usr/bin/env bash
# Builds scalino-jslink: the Scala.js linker (scalajs-linker, compiled from its sources by dotc to
# Scala Native) behind the small CLI in src/JsLink.scala. `scalino` runs it to turn the `.sjsir`
# files the compiler emits under -scalajs into JavaScript, without a JVM.
#
# Compiled with the native scalino-dotc and linked with the native scalino-linkdriver, like
# 07-build-scalino.sh; the linker sources come from build/04c-fetch-jslink-sources.sh.
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")"
source ./00-env.sh
source ./link-lib.sh
link_require
for f in "$DIST/scalino-dotc" "$DIST/java.base.jar"; do
  [[ -e "$f" ]] || { echo "missing $f -- run build/all.sh first" >&2; exit 1; }
done
for f in "$WORK/nativelibs.cp" "$WORK/nscplugin.jar.txt"; do
  [[ -e "$f" ]] || { echo "missing $f -- run build/01-fetch-deps.sh first" >&2; exit 1; }
done

./04c-fetch-jslink-sources.sh

CLASSES_DIR="$WORK/jslink-classes"
LINK_DIR="$WORK/jslink-link"
rm -rf "$CLASSES_DIR" "$LINK_DIR"
mkdir -p "$CLASSES_DIR"

# Same patched javalib as 07-build-scalino.sh: ZipFileSystemProvider, which the driver's jar reading needs.
NATIVELIBS_CP="$WORK/jslink-nativelibs.cp"
LOCAL_JAVALIB_JAR="$HOME/.ivy2/local/org.scala-native/javalib_native0.5_3/${SCALA_NATIVE_VERSION}-SNAPSHOT/jars/javalib_native0.5_3.jar"
if [[ -f "$LOCAL_JAVALIB_JAR" ]]; then
  { tr "$CP_SEP" '\n' < "$WORK/nativelibs.cp" | grep -v '/javalib_native0\.5_3-' | grep -Fxv "$LOCAL_JAVALIB_JAR"; echo "$LOCAL_JAVALIB_JAR"; } | paste -sd"$CP_SEP" - > "$NATIVELIBS_CP"
else
  cp "$WORK/nativelibs.cp" "$NATIVELIBS_CP"
fi

PLUGIN_JAR="$(cat "$WORK/nscplugin.jar.txt")"
FILES="$WORK/jslink-files.txt"
{ find "$WORK/jslink-src" -name '*.scala'; echo "$ROOT/src/JsLink.scala"; } | to_native_path_list > "$FILES"
"$DIST/scalino-dotc" \
  -javabootclasspath "$DIST/java.base.jar" \
  -classpath "$(cat "$NATIVELIBS_CP")" \
  -Xplugin:"$PLUGIN_JAR" -Xplugin-require:scalanative \
  -language:implicitConversions -nowarn \
  -d "$CLASSES_DIR" \
  "@$FILES"

# The linker's own private library (`.sjsir`) is read as a resource at start-up.
LINK_CP="$(to_native_path "$CLASSES_DIR")$CP_SEP$(to_native_path "$WORK/jslink-resources")$CP_SEP$(cat "$NATIVELIBS_CP")"
LINK_ENV=()
linkdriver_run "$LINK_CP" "$(to_native_path "$LINK_DIR")" JsLink \
  --mode "$([[ "$SCALINO_LINK_MODE" == debug ]] && echo debug || echo release-size)" \
  --multithreading --embed-resources --no-incremental-compilation

BUILT="$LINK_DIR/JsLink"
[[ -f "$BUILT" ]] || BUILT="$LINK_DIR/JsLink.exe"
[[ -f "$BUILT" ]] || { echo "link did not produce $BUILT" >&2; exit 1; }
rm -f "$DIST/scalino-jslink"
cp "$BUILT" "$DIST/scalino-jslink"
chmod +x "$DIST/scalino-jslink"
if command -v codesign >/dev/null 2>&1; then
  codesign -s - -f "$DIST/scalino-jslink"
fi
echo "OK: $DIST/scalino-jslink"
