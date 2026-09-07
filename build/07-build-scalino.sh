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

# ScalinoCli.scala's own entry-point/test-discovery scanner needs a real NIR
# reader (see cli/ScalinoCli.scala's "Entry-point detection" section): on a
# self-hosted scalino-dotc, the compiled user project's classesDir never gets
# real JVM .class files (no backend/jvm/ASM in that toolchain at all), only
# .nir -- so the classfile-based scanner alone can't ever find a main class
# or test class there. nir_native0.5_3/util_native0.5_3 are scala-native's
# own NIR data model + binary (de)serializer, published as real Scala Native
# cross-build artifacts -- --intransitive (one artifact per `cs fetch` call,
# not one call with both) for the same reason build/08-build-scalino-lsp.sh's
# LSP_NATIVE_JARS are fetched that way: resolving them together pulls in
# their own transitive javalib/nativelib/clib copy, which conflicts with
# nativelibs.cp's pinned $SCALA_NATIVE_VERSION build (duplicate-symbol link
# errors) -- fetched one at a time, --intransitive correctly suppresses that.
NIR_NATIVE_JARS=(
  "org.scala-native:nir_native0.5_3:$SCALA_NATIVE_VERSION"
  "org.scala-native:util_native0.5_3:$SCALA_NATIVE_VERSION"
)
NIR_NATIVE_CP=""
for artifact in "${NIR_NATIVE_JARS[@]}"; do
  jar="$(cs fetch --intransitive "$artifact" --classpath)"
  NIR_NATIVE_CP="${NIR_NATIVE_CP:+$NIR_NATIVE_CP:}$jar"
done

PLUGIN_JAR="$DIST/$(cat "$DIST/nscplugin.jar.txt")"
COMPILE_CP="$(resolve_cp "$(cat "$DIST/compiler.cp")"):$(resolve_cp "$(cat "$DIST/nativelibs.cp")"):$NIR_NATIVE_CP"

"$DIST/scalino-dotc" \
  -javabootclasspath "$DIST/java.base.jar" \
  -classpath "$COMPILE_CP" \
  -Xplugin:"$PLUGIN_JAR" -Xplugin-require:scalanative \
  -Yretain-trees \
  -d "$CLASSES_DIR" \
  "$ROOT/cli/ScalinoCli.scala" "$SRC_DIR/BuildInfo.scala" "$SELFEXE"

LINK_CP="$CLASSES_DIR:$(resolve_cp "$(cat "$DIST/nativelibs.cp")"):$NIR_NATIVE_CP"
"$DIST/scalino-linkdriver" "$LINK_CP" "$LINK_DIR" ScalinoCli "$CLANG" "$CLANGPP"

cp "$LINK_DIR/ScalinoCli" "$DIST/scalino"
chmod +x "$DIST/scalino"
echo "OK: $DIST/scalino"
