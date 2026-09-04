#!/usr/bin/env bash
# Builds sn-cli: the mini scala-cli-style build tool, self-hosted -- compiled
# by the very toolchain it wraps (dist/dotc-native + dist/linkdriver-native),
# not by any JVM. See cli/SnCli.scala and docs/findings.md "Toward a
# build-tool experience without a JVM".
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")"
source ./00-env.sh

for f in "$DIST/dotc-native" "$DIST/linkdriver-native" "$DIST/java.base.jar" \
         "$DIST/compiler.cp" "$DIST/nativelibs.cp" "$DIST/nscplugin.jar.txt"; do
  [[ -e "$f" ]] || { echo "missing $f -- run build/all.sh first" >&2; exit 1; }
done

SRC_DIR="$WORK/sn-cli-src"
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

# sn-cli locates its own dist/ root via a tiny OS-specific native binding
# (cli/selfexe/*.scala) -- pick the one matching the host we're building on.
case "$(uname -s)" in
  Linux) SELFEXE="$ROOT/cli/selfexe/Linux.scala" ;;
  Darwin) SELFEXE="$ROOT/cli/selfexe/Macos.scala" ;;
  MINGW*|MSYS*|CYGWIN*) SELFEXE="$ROOT/cli/selfexe/Windows.scala" ;;
  *) echo "07-build-sn-cli.sh: unsupported host OS $(uname -s)" >&2; exit 1 ;;
esac

CLASSES_DIR="$WORK/sn-cli-classes"
LINK_DIR="$WORK/sn-cli-link"
rm -rf "$CLASSES_DIR" "$LINK_DIR"
mkdir -p "$CLASSES_DIR"

# compiler.cp/nativelibs.cp/nscplugin.jar.txt hold dist-relative paths
# (see 06-package.sh) so dist/ stays relocatable -- resolve to absolute here.
resolve_cp() { echo "$DIST/${1//:/:$DIST/}"; }

PLUGIN_JAR="$DIST/$(cat "$DIST/nscplugin.jar.txt")"
COMPILE_CP="$(resolve_cp "$(cat "$DIST/compiler.cp")"):$(resolve_cp "$(cat "$DIST/nativelibs.cp")")"

"$DIST/dotc-native" \
  -javabootclasspath "$DIST/java.base.jar" \
  -classpath "$COMPILE_CP" \
  -Xplugin:"$PLUGIN_JAR" -Xplugin-require:scalanative \
  -Yretain-trees \
  -d "$CLASSES_DIR" \
  "$ROOT/cli/SnCli.scala" "$SRC_DIR/BuildInfo.scala" "$SELFEXE"

LINK_CP="$CLASSES_DIR:$(resolve_cp "$(cat "$DIST/nativelibs.cp")")"
"$DIST/linkdriver-native" "$LINK_CP" "$LINK_DIR" SnCli "$CLANG" "$CLANGPP"

cp "$LINK_DIR/SnCli" "$DIST/sn-cli"
chmod +x "$DIST/sn-cli"
echo "OK: $DIST/sn-cli"
