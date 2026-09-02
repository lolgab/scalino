#!/usr/bin/env bash
# Builds scli: the mini scala-cli-style build tool, self-hosted -- compiled
# by the very toolchain it wraps (dist/dotc-native + dist/linkdriver-native),
# not by any JVM. See cli/Scli.scala and docs/findings.md "Toward a
# build-tool experience without a JVM".
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")"
source ./00-env.sh

for f in "$DIST/dotc-native" "$DIST/linkdriver-native" "$DIST/java.base.jar" \
         "$DIST/compiler.cp" "$DIST/nativelibs.cp" "$DIST/nscplugin.jar.txt"; do
  [[ -e "$f" ]] || { echo "missing $f -- run build/all.sh first" >&2; exit 1; }
done

SRC_DIR="$WORK/scli-src"
rm -rf "$SRC_DIR"
mkdir -p "$SRC_DIR"

# Baked-in paths: scli isn't relocatable any more than dist/*.cp already
# are (see docs/findings.md "Packaging/relocatability") -- it's built for,
# and hardcodes, THIS checkout.
cat > "$SRC_DIR/BuildInfo.scala" <<EOF
object BuildInfo:
  val root: String = "$ROOT"
  val scalaVersion: String = "$SCALA_VERSION"
EOF

CLASSES_DIR="$WORK/scli-classes"
LINK_DIR="$WORK/scli-link"
rm -rf "$CLASSES_DIR" "$LINK_DIR"
mkdir -p "$CLASSES_DIR"

PLUGIN_JAR="$(cat "$DIST/nscplugin.jar.txt")"
COMPILE_CP="$(cat "$DIST/compiler.cp"):$(cat "$DIST/nativelibs.cp")"

"$DIST/dotc-native" \
  -javabootclasspath "$DIST/java.base.jar" \
  -classpath "$COMPILE_CP" \
  -Xplugin:"$PLUGIN_JAR" -Xplugin-require:scalanative \
  -Yretain-trees \
  -d "$CLASSES_DIR" \
  "$ROOT/cli/Scli.scala" "$SRC_DIR/BuildInfo.scala"

LINK_CP="$CLASSES_DIR:$(cat "$DIST/nativelibs.cp")"
"$DIST/linkdriver-native" "$LINK_CP" "$LINK_DIR" Scli "$CLANG" "$CLANGPP"

cp "$LINK_DIR/Scli" "$DIST/scli"
chmod +x "$DIST/scli"
echo "OK: $DIST/scli"
