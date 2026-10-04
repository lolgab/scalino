#!/usr/bin/env bash
# Builds scalino: the mini scala-cli-style build tool, self-hosted -- compiled
# by the very toolchain it wraps (dist/scalino-dotc + dist/scalino-linkdriver),
# not by any JVM. See cli/ScalinoCli.scala and docs/findings.md "Toward a
# build-tool experience without a JVM".
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")"
source ./00-env.sh

for f in "$DIST/scalino-dotc" "$DIST/scalino-linkdriver" "$DIST/java.base.jar"; do
  [[ -e "$f" ]] || { echo "missing $f -- run build/all.sh first" >&2; exit 1; }
done
for f in "$WORK/compiler.cp" "$WORK/nativelibs.cp" "$WORK/nscplugin.jar.txt"; do
  [[ -e "$f" ]] || { echo "missing $f -- run build/01-fetch-deps.sh first" >&2; exit 1; }
done

SRC_DIR="$WORK/scalino-src"
rm -rf "$SRC_DIR"
mkdir -p "$SRC_DIR"

# scala-native's own artifact-naming convention uses just major.minor (see
# e.g. nativelib_native0.5_3) -- not the full patch version.
NATIVE_BINARY_VERSION="$(echo "$SCALA_NATIVE_VERSION" | cut -d. -f1,2)"

cat > "$SRC_DIR/BuildInfo.scala" <<EOF
object BuildInfo:
  val scalinoVersion: String = "$SCALINO_VERSION"
  val scalaVersion: String = "$SCALA_VERSION"
  val nativeBinaryVersion: String = "$NATIVE_BINARY_VERSION"
  val nativeVersion: String = "$SCALA_NATIVE_VERSION"
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
  jar="$(cs fetch --intransitive "$artifact" --classpath | tr -d '\r')"
  NIR_NATIVE_CP="${NIR_NATIVE_CP:+$NIR_NATIVE_CP$CP_SEP}$jar"
done

PLUGIN_JAR="$(cat "$WORK/nscplugin.jar.txt")"

# Substitute the published javalib_native0.5_3 jar for the locally-built one
# with any patches/scala-native-000* actually applied -- see
# build/01b-build-patched-javalib.sh and 03-build-scalino-dotc.sh's identical
# substitution. Without this, scalino itself (not just scalino-dotc) links
# against the unpatched upstream javalib.
NATIVELIBS_CP="$WORK/scalino-src/nativelibs.cp"
LOCAL_JAVALIB_JAR="$HOME/.ivy2/local/org.scala-native/javalib_native0.5_3/${SCALA_NATIVE_VERSION}-SNAPSHOT/jars/javalib_native0.5_3.jar"
if [[ -f "$LOCAL_JAVALIB_JAR" ]]; then
  # -F/-x too, not just the old -v '/javalib_native0\.5_3-' regex: see
  # 03-build-scalino-dotc.sh's identical substitution for why -- 01b already
  # rewrites $WORK/nativelibs.cp in place to point straight at
  # $LOCAL_JAVALIB_JAR (whose filename has no "-<version>" suffix, so the old
  # pattern doesn't match it there), so appending it again unconditionally
  # duplicated the entry and made scala-native's linker fail with "duplicate
  # symbol" for every native javalib symbol (z.c, etc). Filtering the exact
  # local path too makes this idempotent regardless of whether
  # $WORK/nativelibs.cp already has it.
  { tr "$CP_SEP" '\n' < "$WORK/nativelibs.cp" | grep -v '/javalib_native0\.5_3-' | grep -Fxv "$LOCAL_JAVALIB_JAR"; echo "$LOCAL_JAVALIB_JAR"; } | paste -sd"$CP_SEP" - > "$NATIVELIBS_CP"
  echo "  using locally-built, patched javalib jar: $LOCAL_JAVALIB_JAR"
else
  cp "$WORK/nativelibs.cp" "$NATIVELIBS_CP"
  echo "  WARNING: locally-built javalib jar not found ($LOCAL_JAVALIB_JAR) -- run build/01b-build-patched-javalib.sh first, or scala-native patches will NOT take effect in scalino itself."
fi

COMPILE_CP="$(cat "$WORK/compiler.cp")$CP_SEP$(cat "$NATIVELIBS_CP")$CP_SEP$NIR_NATIVE_CP"

"$DIST/scalino-dotc" \
  -javabootclasspath "$DIST/java.base.jar" \
  -classpath "$COMPILE_CP" \
  -Xplugin:"$PLUGIN_JAR" -Xplugin-require:scalanative \
  -Yretain-trees \
  -d "$CLASSES_DIR" \
  "$ROOT/cli/ScalinoCli.scala" "$ROOT/cli/Packaging.scala" "$SRC_DIR/BuildInfo.scala" "$SELFEXE"

LINK_CP="$(to_native_path "$CLASSES_DIR")$CP_SEP$(cat "$NATIVELIBS_CP")$CP_SEP$NIR_NATIVE_CP"
# --mode release-size: v0.0.1 shipped scala-native's *default* Mode (debug --
# no --mode flag was passed at all). -Xss64m defensively -- see
# 03-build-scalino-dotc.sh's identical note (release-fast/-size's own
# optimizer StackOverflowed there against dotc's large methods; ScalinoCli
# itself is much smaller, but this is cheap insurance either way).
"$DIST/scalino-linkdriver" "$LINK_CP" "$(to_native_path "$LINK_DIR")" ScalinoCli "$CLANG" "$CLANGPP" info --mode release-size --multithreading

cp "$LINK_DIR/ScalinoCli" "$DIST/scalino"
chmod +x "$DIST/scalino"
# See 08-build-scalino-lsp.sh's identical guard: on macOS/arm64 the
# linker's own ad-hoc signature is sometimes rejected by the kernel at
# exec time (SIGKILL, 0 CPU time used, no crash report -- AMFI
# code-signature rejection, not a runtime crash). Re-signing ad-hoc
# ourselves after the copy reliably fixes it. Harmless no-op on Linux.
if command -v codesign >/dev/null 2>&1; then
  codesign -s - -f "$DIST/scalino"
fi

# Shell completion scripts (see cli/ScalinoCli.scala's `completions`
# subcommand): pre-generated here and shipped in dist/completions/ rather
# than left for the end user to run themselves, so every packaging format
# (brew/apt/dnf/arch/nix -- see build/09-package-linux-native.sh and
# packaging/*) can just install these static files at their own
# convention's completion path. Generated by the binary we just built, not
# hand-written, so they can never drift from the option list `main`/
# `parseRunOpts` actually accept.
mkdir -p "$DIST/completions"
"$DIST/scalino" completions bash > "$DIST/completions/scalino.bash"
"$DIST/scalino" completions zsh > "$DIST/completions/_scalino"
"$DIST/scalino" completions fish > "$DIST/completions/scalino.fish"

echo "OK: $DIST/scalino"
