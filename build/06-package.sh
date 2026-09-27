#!/usr/bin/env bash
# Vendors every jar bin/scalino-bootstrap and scalino need at runtime into dist/lib and
# rewrites the classpath manifests (compiler.cp, nativelibs.cp,
# nscplugin.jar.txt) to hold dist-relative paths ("lib/foo.jar") instead of
# absolute coursier-cache paths -- so dist/ can be tarred up and copied to
# another machine and still work. bin/scalino-bootstrap and cli/ScalinoCli.scala resolve those
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

# Vendors each nativelib's matching sources jar (built from vendor/scala-
# native+patches for javalib/nativelib by 01b/01c, fetched upstream as-is
# for the rest by 01-fetch-deps.sh) as a sibling of its main jar in dist/lib
# -- the exact "<jar>-sources.jar" sibling layout scalino-dotc's
# sourceFromSourcesJar (patches/scala3-0014) looks for, so scalino-lsp's
# go-to-definition into any of these resolves into the REAL code the
# compiler just linked against, not a best-effort runtime fetch that (for
# javalib/nativelib specifically) could only ever find unpatched upstream
# source, or nothing at all for a "-SNAPSHOT" this project never publishes
# anywhere fetchable.
#
# Matched by name, not order: a sources jar's basename with "-sources.jar"
# swapped for ".jar" must equal some already-vendored main jar's basename
# (true both for coursier's own "<artifact>-<version>-sources.jar" alongside
# "<artifact>-<version>.jar", and for 01b/01c's locally-published
# "<artifact>-sources.jar" alongside their unversioned "<artifact>.jar"
# substitution) -- so an entry with no matching main jar in dist/lib (e.g.
# a lib that got excluded on this platform, like windowslib off-Windows)
# is silently skipped instead of vendored as dead weight.
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

PLUGIN_JAR="$(cat "$WORK/nscplugin.jar.txt")"
PLUGIN_BASE="$(basename "$PLUGIN_JAR")"
cp -p "$PLUGIN_JAR" "$DIST/lib/$PLUGIN_BASE"
echo "lib/$PLUGIN_BASE" > "$DIST/nscplugin.jar.txt"

# Bundle coursier's `cs` launcher as scalino-cs, so `//> using dep`
# resolution (cli/ScalinoCli.scala's resolveDeps/fetchSourcesBestEffort/
# fetchStdlibSourcesBestEffort) never depends on the end user having `cs` on
# their own PATH. Renamed so it can't collide with a user's own real `cs`.
# `command -v cs` here is already the right binary for this machine: 00-env.sh
# requires it on PATH at build time, and CI installs it per-target-OS/arch
# (coursier/setup-action, native runners) before build/all.sh ever runs --
# there's no cross-platform download/pin to manage, just copy what's already
# resolved.
cp "$(command -v cs)" "$DIST/scalino-cs"
chmod +x "$DIST/scalino-cs"

# Build-time-only intermediates (their classes are already baked into the
# scalino-dotc/scalino-linkdriver binaries) -- not needed at runtime, drop them
# so they don't end up in release tarballs.
rm -f "$DIST/scala3-compiler-patched.jar" "$DIST/tools-patched.jar"

echo "OK: $DIST is ready (self-contained, relocatable). Try: bin/scalino-bootstrap build spike/Hello.scala -o /tmp/hello"
