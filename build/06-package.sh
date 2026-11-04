#!/usr/bin/env bash
# Vendors every jar bin/scalino-bootstrap and scalino need at runtime into dist/lib and
# rewrites the classpath manifests (compiler.cp, nativelibs.cp, nscplugin.jar.txt) to
# dist-relative paths ("lib/foo.jar") so dist/ can be copied to another machine.
# bin/scalino-bootstrap and cli/ScalinoCli.scala resolve them against their dist/ root.
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")"
source ./00-env.sh

mkdir -p "$DIST/lib"

# Reads a ':'-separated list of absolute jar paths from $1, copies each into
# dist/lib, and writes the dist-relative replacement list to $2.
vendor_cp() {
  local src="$1" out="$2"
  local -a rel=() parts=()
  local IFS="$CP_SEP"
  read -ra parts < "$src"
  for jar in "${parts[@]}"; do
    local base
    base="$(basename "$jar")"
    cp -p "$jar" "$DIST/lib/$base"
    rel+=("lib/$base")
  done
  local joined
  joined="$(IFS="$CP_SEP"; echo "${rel[*]}")"
  echo "$joined" > "$out"
}

vendor_cp "$WORK/compiler.cp" "$DIST/compiler.cp"
vendor_cp "$WORK/nativelibs.cp" "$DIST/nativelibs.cp"

# What every Scala.js build has on its class path (cli/ScalinoCli.scala's jsBaseDeps, plus alwaysIncludedArtifacts),
# vendored so a Scala.js program with no further dependencies builds offline: the CLI uses jslibs.cp instead of
# asking coursier when that is all a build needs.
cs fetch \
  "org.scala-lang:scala3-library_sjs1_3:$SCALA_VERSION" \
  "org.scala-js:scalajs-library_2.13:$SCALAJS_VERSION" \
  "org.scala-js:scalajs-javalib:$SCALAJS_VERSION" \
  "org.typelevel:scalac-compat-annotation_3:0.1.4" \
  --classpath | tr -d '\r' > "$WORK/jslibs.cp"
vendor_cp "$WORK/jslibs.cp" "$DIST/jslibs.cp"

# Vendors each nativelib's sources jar next to its main jar in dist/lib, as
# "<jar>-sources.jar" (the layout scalino-dotc's sourceFromSourcesJar, patches/scala3-0014,
# looks for), so scalino-lsp's go-to-definition lands in the code actually linked against
# (javalib/nativelib are built from vendor/scala-native + patches by 01b/01c; the rest come
# from 01-fetch-deps.sh).
#
# Matched by name: a sources jar's basename with "-sources.jar" swapped for ".jar" must equal an
# already-vendored main jar's basename (coursier's "<artifact>-<version>" naming and the local
# builds' unversioned "<artifact>"). Entries with no match (e.g. windowslib off-Windows) are
# skipped.
vendor_sources() {
  local src="$1"
  [[ -f "$src" ]] || return 0
  local -a parts=()
  local IFS="$CP_SEP"
  read -ra parts < "$src" || true
  for jar in "${parts[@]:-}"; do
    [[ -n "$jar" && -f "$jar" ]] || continue
    local base main
    base="$(basename "$jar")"
    main="${base%-sources.jar}.jar"
    if [[ -f "$DIST/lib/$main" ]]; then
      cp -p "$jar" "$DIST/lib/$base"
    fi
  done
}
vendor_sources "$WORK/nativelibs-sources.cp"

# javalib's .tasty (01a-publish-scala-native.sh): bodies of JDK classes for the
# macro interpreter, see cli/ScalinoCli.scala's computeCompileClasspath.
JAVALIB_TASTY_JAR="$HOME/.ivy2/local/org.scala-native/javalib_native0.5_3/${SCALA_NATIVE_VERSION}/jars/javalib-tasty.jar"
if [[ -f "$JAVALIB_TASTY_JAR" ]]; then
  cp -p "$JAVALIB_TASTY_JAR" "$DIST/lib/javalib-tasty.jar"
else
  echo "warning: $JAVALIB_TASTY_JAR missing -- the macro interpreter will have no JDK method bodies (run build/01a-publish-scala-native.sh)" >&2
fi

PLUGIN_JAR="$(cat "$WORK/nscplugin.jar.txt")"
PLUGIN_BASE="$(basename "$PLUGIN_JAR")"
cp -p "$PLUGIN_JAR" "$DIST/lib/$PLUGIN_BASE"
echo "lib/$PLUGIN_BASE" > "$DIST/nscplugin.jar.txt"

# Bundle coursier's `cs` as scalino-cs so `//> using dep` resolution (cli/ScalinoCli.scala's
# resolveDeps/fetchSourcesBestEffort/fetchStdlibSourcesBestEffort) doesn't depend on the user's
# PATH; renamed to avoid colliding with a user's own `cs`. The `cs` on PATH is the right binary
# for this machine (00-env.sh requires it; CI installs it per target).
cp "$(command -v cs)" "$DIST/scalino-cs"
chmod +x "$DIST/scalino-cs"

# Data `scalino sysroot build` needs to generate the macOS libSystem stub (see
# cli/SysrootBuild.scala, build/gen-libsystem-symbols.py).
mkdir -p "$DIST/share/sysroot"
cp "$ROOT/build"/libsystem-symbols-*.txt "$ROOT/build"/libobjc-symbols-*.txt "$ROOT/build"/curl_config-*.h "$DIST/share/sysroot/"

# Build-time-only intermediates (their classes are already baked into the
# scalino-dotc/scalino-linkdriver binaries) -- not needed at runtime, drop them
# so they don't end up in release tarballs.
rm -f "$DIST/scala3-compiler-patched.jar" "$DIST/tools-patched.jar"

echo "OK: $DIST is ready (self-contained, relocatable). Try: bin/scalino-bootstrap build spike/Hello.scala -o /tmp/hello"
