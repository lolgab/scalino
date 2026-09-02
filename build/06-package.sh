#!/usr/bin/env bash
# Vendors every jar bin/snc and scli need at runtime into dist/lib and
# rewrites the classpath manifests (compiler.cp, nativelibs.cp,
# nscplugin.jar.txt) to hold dist-relative paths ("lib/foo.jar") instead of
# absolute coursier-cache paths -- so dist/ can be tarred up and copied to
# another machine and still work. bin/snc and cli/Scli.scala resolve those
# relative entries against their own dist/ root at runtime.
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")"
source ./00-env.sh

mkdir -p "$DIST/lib"

# Reads a ':'-separated list of absolute jar paths from $1, copies each into
# dist/lib, and writes the dist-relative replacement list to $2.
vendor_cp() {
  local src="$1" out="$2"
  local -a rel=() parts=()
  local IFS=':'
  read -ra parts < "$src"
  for jar in "${parts[@]}"; do
    local base
    base="$(basename "$jar")"
    cp -p "$jar" "$DIST/lib/$base"
    rel+=("lib/$base")
  done
  local joined
  joined="$(IFS=':'; echo "${rel[*]}")"
  echo "$joined" > "$out"
}

vendor_cp "$WORK/compiler.cp" "$DIST/compiler.cp"
vendor_cp "$WORK/nativelibs.cp" "$DIST/nativelibs.cp"

PLUGIN_JAR="$(cat "$WORK/nscplugin.jar.txt")"
PLUGIN_BASE="$(basename "$PLUGIN_JAR")"
cp -p "$PLUGIN_JAR" "$DIST/lib/$PLUGIN_BASE"
echo "lib/$PLUGIN_BASE" > "$DIST/nscplugin.jar.txt"

# Build-time-only intermediates (their classes are already baked into the
# dotc-native/linkdriver-native binaries) -- not needed at runtime, drop them
# so they don't end up in release tarballs.
rm -f "$DIST/scala3-compiler-patched.jar" "$DIST/tools-patched.jar"

echo "OK: $DIST is ready (self-contained, relocatable). Try: bin/snc build spike/Hello.scala -o /tmp/hello"
