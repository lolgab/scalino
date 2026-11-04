#!/usr/bin/env bash
# Builds scalino, the scala-cli-style build tool, self-hosted with dist/scalino-dotc +
# dist/scalino-linkdriver. See cli/ScalinoCli.scala and docs/findings.md "Toward a
# build-tool experience without a JVM".
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")"
source ./00-env.sh
source ./link-lib.sh
link_require

for f in "$DIST/scalino-dotc" "$DIST/java.base.jar"; do
  [[ -e "$f" ]] || { echo "missing $f -- run build/all.sh first" >&2; exit 1; }
done
for f in "$WORK/compiler.cp" "$WORK/nativelibs.cp" "$WORK/nscplugin.jar.txt"; do
  [[ -e "$f" ]] || { echo "missing $f -- run build/01-fetch-deps.sh first" >&2; exit 1; }
done

SRC_DIR="$WORK/scalino-src"
rm -rf "$SRC_DIR"
mkdir -p "$SRC_DIR"

# scala-native artifacts are named with major.minor only (e.g. nativelib_native0.5_3).
NATIVE_BINARY_VERSION="$(echo "$SCALA_NATIVE_VERSION" | cut -d. -f1,2)"

cat > "$SRC_DIR/BuildInfo.scala" <<EOF
object BuildInfo:
  val scalinoVersion: String = "$SCALINO_VERSION"
  val scalaVersion: String = "$SCALA_VERSION"
  val nativeBinaryVersion: String = "$NATIVE_BINARY_VERSION"
  val nativeVersion: String = "$SCALA_NATIVE_VERSION"
  val scalaJsVersion: String = "$SCALAJS_VERSION"
  /** Upstream versions and checksums of what \`scalino sysroot build\` downloads (versions.env). */
  val sysrootPins: Map[String, String] = Map(
$(for v in DEBIAN_SNAPSHOT MUSL_DEB_VERSION MUSL_DEV_AMD64_SHA256 MUSL_DEV_ARM64_SHA256 ZIG_VERSION ZIG_SHA256 LLVM_MINGW_VERSION LLVM_MINGW_SHA256 LLVM_MINGW_LINUX_AARCH64_SHA256 LLVM_MINGW_MACOS_UNIVERSAL_SHA256 MINGW_W64_LICENSE_TAG MINGW_W64_RUNTIME_LICENSE_SHA256 MINGW_W64_LICENSE_SHA256 GLIBC_DEB_VERSION LINUX_LIBC_DEV_VERSION LIBCLANG_RT_DEB_VERSION GLIBC_LIBC6_AMD64_SHA256 GLIBC_LIBC6_DEV_AMD64_SHA256 LINUX_LIBC_DEV_AMD64_SHA256 GLIBC_LIBC6_ARM64_SHA256 GLIBC_LIBC6_DEV_ARM64_SHA256 LINUX_LIBC_DEV_ARM64_SHA256 LIBCLANG_RT_AMD64_SHA256 LIBCLANG_RT_ARM64_SHA256 OPENSSL_ALPINE_BRANCH OPENSSL_ALPINE_VERSION OPENSSL_ALPINE_LICENSE_VERSION OPENSSL_ALPINE_LICENSE_SHA256 OPENSSL_ALPINE_STATIC_X86_64_SHA256 OPENSSL_ALPINE_DEV_X86_64_SHA256 OPENSSL_ALPINE_STATIC_AARCH64_SHA256 OPENSSL_ALPINE_DEV_AARCH64_SHA256 OPENSSL_DEBIAN_VERSION OPENSSL_DEBIAN_AMD64_SHA256 OPENSSL_DEBIAN_ARM64_SHA256 OPENSSL_MSYS2_VERSION OPENSSL_MSYS2_X86_64_SHA256 OPENSSL_MSYS2_AARCH64_SHA256 OPENSSL_BREW_VERSION OPENSSL_BREW_X86_64_SHA256 OPENSSL_BREW_ARM64_SHA256 IDN2_ALPINE_VERSION IDN2_ALPINE_STATIC_X86_64_SHA256 IDN2_ALPINE_DEV_X86_64_SHA256 IDN2_ALPINE_STATIC_AARCH64_SHA256 IDN2_ALPINE_DEV_AARCH64_SHA256 UNISTRING_ALPINE_VERSION UNISTRING_ALPINE_STATIC_X86_64_SHA256 UNISTRING_ALPINE_STATIC_AARCH64_SHA256 IDN2_DEBIAN_VERSION IDN2_DEBIAN_AMD64_SHA256 IDN2_DEBIAN_ARM64_SHA256 UNISTRING_DEBIAN_VERSION UNISTRING_DEBIAN_AMD64_SHA256 UNISTRING_DEBIAN_ARM64_SHA256 IDN2_MSYS2_VERSION IDN2_MSYS2_X86_64_SHA256 IDN2_MSYS2_AARCH64_SHA256 UNISTRING_MSYS2_VERSION UNISTRING_MSYS2_X86_64_SHA256 UNISTRING_MSYS2_AARCH64_SHA256 ICONV_MSYS2_VERSION ICONV_MSYS2_X86_64_SHA256 ICONV_MSYS2_AARCH64_SHA256 IDN2_BREW_VERSION IDN2_BREW_X86_64_SHA256 IDN2_BREW_ARM64_SHA256 UNISTRING_BREW_VERSION UNISTRING_BREW_X86_64_SHA256 UNISTRING_BREW_ARM64_SHA256 GETTEXT_BREW_VERSION GETTEXT_BREW_X86_64_SHA256 GETTEXT_BREW_ARM64_SHA256 IDN2_LICENSE_VERSION IDN2_LICENSE_LESSER_SHA256 IDN2_LICENSE_UNICODE_SHA256 UNISTRING_LICENSE_VERSION UNISTRING_LICENSE_SHA256 S2N_VERSION S2N_SHA256 CURL_VERSION CURL_SHA256 ZLIB_VERSION ZLIB_SHA256 CURL_MSYS2_VERSION CURL_MSYS2_X86_64_SHA256 CURL_MSYS2_AARCH64_SHA256 LIBSSH2_MSYS2_VERSION LIBSSH2_MSYS2_X86_64_SHA256 LIBSSH2_MSYS2_AARCH64_SHA256 ZLIB_MSYS2_VERSION ZLIB_MSYS2_X86_64_SHA256 ZLIB_MSYS2_AARCH64_SHA256 BROTLI_MSYS2_VERSION BROTLI_MSYS2_X86_64_SHA256 BROTLI_MSYS2_AARCH64_SHA256 ZSTD_MSYS2_VERSION ZSTD_MSYS2_X86_64_SHA256 ZSTD_MSYS2_AARCH64_SHA256 NGHTTP2_MSYS2_VERSION NGHTTP2_MSYS2_X86_64_SHA256 NGHTTP2_MSYS2_AARCH64_SHA256 NGTCP2_MSYS2_VERSION NGTCP2_MSYS2_X86_64_SHA256 NGTCP2_MSYS2_AARCH64_SHA256 NGHTTP3_MSYS2_VERSION NGHTTP3_MSYS2_X86_64_SHA256 NGHTTP3_MSYS2_AARCH64_SHA256 LIBPSL_MSYS2_VERSION LIBPSL_MSYS2_X86_64_SHA256 LIBPSL_MSYS2_AARCH64_SHA256; do echo "    \"$v\" -> \"${!v}\","; done)
  )
EOF

# scalino locates its dist/ root via an OS-specific native binding (cli/selfexe/*.scala).
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

# ScalinoCli's entry-point/test scanner needs a NIR reader: the self-hosted scalino-dotc emits
# only .nir (no JVM .class files), so the classfile scanner can't find main/test classes
# (cli/ScalinoCli.scala "Entry-point detection"). nir_native0.5_3/util_native0.5_3 are fetched
# one `cs fetch --intransitive` call each, as in 08-build-scalino-lsp.sh (LSP_NATIVE_JARS):
# together they pull in a second javalib/nativelib/clib copy that conflicts with nativelibs.cp
# (duplicate-symbol link errors).
NIR_NATIVE_JARS=(
  "org.scala-native:nir_native0.5_3:$SCALA_NATIVE_VERSION"
  "org.scala-native:util_native0.5_3:$SCALA_NATIVE_VERSION"
)
NIR_NATIVE_CP=""
for artifact in "${NIR_NATIVE_JARS[@]}"; do
  jar="$(cs fetch -r ivy2Local --intransitive "$artifact" --classpath | tr -d '\r')"
  NIR_NATIVE_CP="${NIR_NATIVE_CP:+$NIR_NATIVE_CP$CP_SEP}$jar"
done

PLUGIN_JAR="$(cat "$WORK/nscplugin.jar.txt")"

# Substitute the locally-built, patched javalib jar (see 01a and 03-build-scalino-dotc.sh);
# otherwise scalino itself links against the unpatched upstream javalib.
NATIVELIBS_CP="$WORK/scalino-src/nativelibs.cp"
LOCAL_JAVALIB_JAR="$HOME/.ivy2/local/org.scala-native/javalib_native0.5_3/${SCALA_NATIVE_VERSION}/jars/javalib_native0.5_3.jar"
if [[ -f "$LOCAL_JAVALIB_JAR" ]]; then
  # -F/-x too: see 03-build-scalino-dotc.sh (01b already rewrites $WORK/nativelibs.cp to contain
  # $LOCAL_JAVALIB_JAR, and a duplicate entry makes the link fail with "duplicate symbol").
  { tr "$CP_SEP" '\n' < "$WORK/nativelibs.cp" | grep -v '/javalib_native0\.5_3-' | grep -Fxv "$LOCAL_JAVALIB_JAR"; echo "$LOCAL_JAVALIB_JAR"; } | paste -sd"$CP_SEP" - > "$NATIVELIBS_CP"
  echo "  using locally-built, patched javalib jar: $LOCAL_JAVALIB_JAR"
else
  cp "$WORK/nativelibs.cp" "$NATIVELIBS_CP"
  echo "  WARNING: locally-built javalib jar not found ($LOCAL_JAVALIB_JAR) -- run build/01a-publish-scala-native.sh first, or scala-native patches will NOT take effect in scalino itself."
fi

COMPILE_CP="$(cat "$WORK/compiler.cp")$CP_SEP$(cat "$NATIVELIBS_CP")$CP_SEP$NIR_NATIVE_CP"

"$DIST/scalino-dotc" \
  -javabootclasspath "$DIST/java.base.jar" \
  -classpath "$COMPILE_CP" \
  -Xplugin:"$PLUGIN_JAR" -Xplugin-require:scalanative \
  -Yretain-trees \
  -d "$CLASSES_DIR" \
  "$ROOT/cli/ScalinoCli.scala" "$ROOT/cli/Packaging.scala" "$ROOT/cli/Sysroot.scala" "$ROOT/cli/SysrootBuild.scala" "$SRC_DIR/BuildInfo.scala" "$SELFEXE"

LINK_CP="$(to_native_path "$CLASSES_DIR")$CP_SEP$(cat "$NATIVELIBS_CP")$CP_SEP$NIR_NATIVE_CP"
# --mode release-size: v0.0.1 shipped the default debug mode. -Xss64m is cheap insurance; see
# 03-build-scalino-dotc.sh's note on the optimizer overflowing the stack on dotc's large methods.
# SCALANATIVE_EVAC_ENABLE=1 (compacting GC), for this command only: linking large programs
# fragments the Immix heap badly (interflow garbage) and otherwise runs out of heap.
LINK_ENV=()
linkdriver_run "$LINK_CP" "$(to_native_path "$LINK_DIR")" ScalinoCli --mode "$([[ "$SCALINO_LINK_MODE" == debug ]] && echo debug || echo release-size)" --multithreading --no-incremental-compilation

cp "$LINK_DIR/ScalinoCli" "$DIST/scalino"
chmod +x "$DIST/scalino"
# Same guard as 08-build-scalino-lsp.sh: on macOS/arm64 the linker's ad-hoc signature is
# sometimes rejected at exec time (SIGKILL, AMFI); re-signing after the copy fixes it. No-op on Linux.
if command -v codesign >/dev/null 2>&1; then
  codesign -s - -f "$DIST/scalino"
fi

# Shell completion scripts (cli/ScalinoCli.scala's `completions` subcommand), generated by the
# binary just built so they track its options, and shipped in dist/completions/ for every
# packaging format (see build/09-package-linux-native.sh and packaging/*).
mkdir -p "$DIST/completions"
"$DIST/scalino" completions bash > "$DIST/completions/scalino.bash"
"$DIST/scalino" completions zsh > "$DIST/completions/_scalino"
"$DIST/scalino" completions fish > "$DIST/completions/scalino.fish"

echo "OK: $DIST/scalino"
