#!/usr/bin/env bash
# Builds scalino: the mini scala-cli-style build tool, self-hosted -- compiled
# by the very toolchain it wraps (dist/scalino-dotc + dist/scalino-linkdriver),
# not by any JVM. See cli/ScalinoCli.scala and docs/findings.md "Toward a
# build-tool experience without a JVM".
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")"
source ./00-env.sh

for f in "$DIST/scalino-dotc" "$DIST/scalino-linkdriver" "$DIST/java.base.jar" \
         "$DIST/compiler.cp" "$DIST/nativelibs.cp" "$DIST/nscplugin.jar.txt"; do
  [[ -e "$f" ]] || { echo "missing $f -- run build/all.sh first" >&2; exit 1; }
done

SRC_DIR="$WORK/scalino-src"
rm -rf "$SRC_DIR"
mkdir -p "$SRC_DIR"

# scala-native's own artifact-naming convention uses just major.minor (see
# e.g. nativelib_native0.5_3) -- not the full patch version.
NATIVE_BINARY_VERSION="$(echo "$SCALA_NATIVE_VERSION" | cut -d. -f1,2)"

cat > "$SRC_DIR/BuildInfo.scala" <<EOF
object BuildInfo:
  val scalaVersion: String = "$SCALA_VERSION"
  val nativeBinaryVersion: String = "$NATIVE_BINARY_VERSION"
EOF

# scalino locates its own dist/ root via a tiny OS-specific native binding
# (cli/selfexe/*.scala) -- pick the one matching the host we're building on.
case "$(uname -s)" in
  Linux) SELFEXE="$ROOT/cli/selfexe/Linux.scala" ;;
  Darwin) SELFEXE="$ROOT/cli/selfexe/Macos.scala" ;;
  MINGW*|MSYS*|CYGWIN*) SELFEXE="$ROOT/cli/selfexe/Windows.scala" ;;
  *) echo "07-build-scalino.sh: unsupported host OS $(uname -s)" >&2; exit 1 ;;
esac

CLASSES_DIR="$WORK/scalino-classes"
LINK_DIR="$WORK/scalino-link"
rm -rf "$CLASSES_DIR" "$LINK_DIR"
mkdir -p "$CLASSES_DIR"

# compiler.cp/nativelibs.cp/nscplugin.jar.txt hold dist-relative paths
# (see 06-package.sh) so dist/ stays relocatable -- resolve to absolute here.
resolve_cp() { echo "$DIST/${1//:/:$DIST/}"; }

PLUGIN_JAR="$DIST/$(cat "$DIST/nscplugin.jar.txt")"
COMPILE_CP="$(resolve_cp "$(cat "$DIST/compiler.cp")"):$(resolve_cp "$(cat "$DIST/nativelibs.cp")")"

"$DIST/scalino-dotc" \
  -javabootclasspath "$DIST/java.base.jar" \
  -classpath "$COMPILE_CP" \
  -Xplugin:"$PLUGIN_JAR" -Xplugin-require:scalanative \
  -Yretain-trees \
  -d "$CLASSES_DIR" \
  "$ROOT/cli/ScalinoCli.scala" "$SRC_DIR/BuildInfo.scala" "$SELFEXE"

LINK_CP="$CLASSES_DIR:$(resolve_cp "$(cat "$DIST/nativelibs.cp")")"
"$DIST/scalino-linkdriver" "$LINK_CP" "$LINK_DIR" ScalinoCli "$CLANG" "$CLANGPP"

cp "$LINK_DIR/ScalinoCli" "$DIST/scalino"
chmod +x "$DIST/scalino"
echo "OK: $DIST/scalino"
