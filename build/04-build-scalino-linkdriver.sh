#!/usr/bin/env bash
# Builds scalino-linkdriver: a standalone binary wrapping scala-native's
# tools_3 library (NIR -> LLVM IR -> clang -> native executable). Self-hosted
# like the rest of the toolchain: dotc compiles LinkDriver.scala to NIR, then
# LinkDriver links itself (run on the JVM, same bootstrap step 03/08 reuse).
#
# Links against the locally built, patched tools (build/01a-publish-scala-native.sh):
# tools_native0.5_3 for the native binary, tools_3 for the JVM bootstrap run.
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")"
source ./00-env.sh

[[ -f "$WORK/tools-native.cp" ]] || { echo "run 01-fetch-deps.sh first" >&2; exit 1; }
[[ -f "$WORK/tools.cp" ]] || { echo "run 01-fetch-deps.sh first" >&2; exit 1; }

# LinkDriver is compiled (below) against the *native*-targeted tools classpath
# (NATIVE_DRIVER_CP) but runs here on a plain JVM against DRIVER_CP, so the two
# must agree on NativeConfig's shape (both come from the same local build).
DRIVER_CP="$(cat "$WORK/compiler.cp")$CP_SEP$(cat "$WORK/tools.cp")$CP_SEP$(to_native_path "$WORK/driver-classes")"

# Substitute the locally-built javalib jar (patches/scala-native-0009, ZipFileSystemProvider; see
# 01a). It becomes the javalib baked into the linked scalino-linkdriver, so jar classpath
# entries handed to `Build` at runtime are read via NIO's "jar:" FileSystemProvider instead of
# needing extractJarIfNeeded's workaround.
TOOLS_PATCHED_JAVALIB_CP="$WORK/tools-patched-javalib.cp"
LOCAL_JAVALIB_JAR="$HOME/.ivy2/local/org.scala-native/javalib_native0.5_3/${SCALA_NATIVE_VERSION}/jars/javalib_native0.5_3.jar"
if [[ -f "$LOCAL_JAVALIB_JAR" ]]; then
  { tr "$CP_SEP" '\n' < "$WORK/tools-native.cp" | grep -Fxv "$LOCAL_JAVALIB_JAR"; echo "$LOCAL_JAVALIB_JAR"; } | paste -sd"$CP_SEP" - > "$TOOLS_PATCHED_JAVALIB_CP"
  echo "  using locally-built, patched javalib jar: $LOCAL_JAVALIB_JAR"
else
  cp "$WORK/tools-native.cp" "$TOOLS_PATCHED_JAVALIB_CP"
  echo "  WARNING: locally-built javalib jar not found ($LOCAL_JAVALIB_JAR) -- run build/01a-publish-scala-native.sh first, or patches/scala-native-0009 (ZipFileSystemProvider) will NOT take effect and scalino-linkdriver will fall back to needing jar-extraction workarounds. See docs/findings.md."
fi

# Same substitution for nativelib_native0.5_3. This bootstrap classpath (tools-native.cp) is a
# separate lineage from $WORK/nativelibs.cp (patched in place by 01c), and javalib and nativelib
# must agree on the patched pair (e.g. runtime.SmiBox$, which javalib's boxed-primitive classes
# reference) for the bootstrap build to link.
LOCAL_NATIVELIB_JAR="${SCALINO_NATIVELIB_JAR:-$HOME/.ivy2/local/org.scala-native/nativelib_native0.5_3/${SCALA_NATIVE_VERSION}/jars/nativelib_native0.5_3.jar}"
if [[ -f "$LOCAL_NATIVELIB_JAR" ]]; then
  TMP_NATIVELIB_SWAP="$(mktemp)"
  { tr "$CP_SEP" '\n' < "$TOOLS_PATCHED_JAVALIB_CP" | grep -Fxv "$LOCAL_NATIVELIB_JAR"; echo "$LOCAL_NATIVELIB_JAR"; } | paste -sd"$CP_SEP" - > "$TMP_NATIVELIB_SWAP"
  mv "$TMP_NATIVELIB_SWAP" "$TOOLS_PATCHED_JAVALIB_CP"
  echo "  using locally-built, patched nativelib jar: $LOCAL_NATIVELIB_JAR"
else
  echo "  WARNING: locally-built nativelib jar not found ($LOCAL_NATIVELIB_JAR) -- run build/01a-publish-scala-native.sh first, or nativelib source patches will NOT take effect in scalino-linkdriver's own bootstrap build."
fi

NATIVE_DRIVER_CP="$(to_native_path "$WORK/driver-classes")$CP_SEP$(cat "$TOOLS_PATCHED_JAVALIB_CP")"

NSCPLUGIN_JAR="$(cat "$WORK/nscplugin.jar.txt")"

rm -rf "$WORK/driver-classes"
mkdir -p "$WORK/driver-classes"
"$JAVA" -cp "$DRIVER_CP" dotty.tools.dotc.Main \
  -Xplugin:"$NSCPLUGIN_JAR" \
  -Xplugin-require:scalanative \
  -classpath "$NATIVE_DRIVER_CP" \
  -d "$WORK/driver-classes" \
  "$ROOT/src/LinkDriver.scala"

LINK_WORK="${SCALINO_LINK_WORK:-$WORK/driver-link}"
rm -rf "$LINK_WORK"
mkdir -p "$LINK_WORK"

# NativeConfig's direct LLVM-C codegen backend is on by default, so the tools_3 link step below
# (even linking scalino-linkdriver itself) pulls in the @extern LLVM-C bindings
# (LLVMCApi.scala) and needs libLLVM unconditionally; without it the link fails with
# "undefined reference to LLVMFunctionType".
#
# Linked statically: the released binary runs on machines with no libLLVM (e.g. bare Fedora; see
# docs/findings.md "static LLVM link"). Per-component archives (libLLVMCore.a, ...) have no
# same-named .so/.dylib, so "-lLLVMCore" always resolves to the archive on Linux and macOS;
# `llvm-config --link-static` supplies those flags instead of the combined "-lLLVM".
LLVM_DIRECT_CODEGEN_LINKING_OPTS=()
if command -v llvm-config >/dev/null 2>&1; then
  LLVM_CONFIG_VERSION="$(llvm-config --version 2>/dev/null || true)"
  LLVM_CONFIG_MAJOR="${LLVM_CONFIG_VERSION%%.*}"
  LLVM_CONFIG_LIBDIR="$(llvm-config --libdir 2>/dev/null || true)"
  if [[ "${LLVM_CONFIG_MAJOR:-0}" =~ ^[0-9]+$ ]] && (( LLVM_CONFIG_MAJOR >= 13 )) \
      && [[ -n "$LLVM_CONFIG_LIBDIR" && -d "$LLVM_CONFIG_LIBDIR" ]]; then
    # "all" includes optional components (e.g. Polly) that llvm-config lists although the .a is not
    # installed (Ubuntu's llvm-dev has no libPolly*): keep only -lFoo with an existing libFoo.a.
    LLVM_STATIC_LIBS_ALL=($(llvm-config --link-static --libs all 2>/dev/null))
    LLVM_STATIC_LIBS=()
    for lib in "${LLVM_STATIC_LIBS_ALL[@]}"; do
      if [[ "$lib" == -l* && -f "$LLVM_CONFIG_LIBDIR/lib${lib#-l}.a" ]]; then
        LLVM_STATIC_LIBS+=("$lib")
      elif [[ "$lib" != -l* ]]; then
        LLVM_STATIC_LIBS+=("$lib")
      else
        echo "  skipping $lib: no lib${lib#-l}.a in $LLVM_CONFIG_LIBDIR (component not actually installed)" >&2
      fi
    done
    # llvm-config --system-libs omits two things the archives need: (1) the C++ runtime (the
    # combined libLLVM.so carried its own dependency; raw .a archives don't), left dynamic since
    # it is ubiquitous; (2) zstd (LLVM_ENABLE_ZSTD is on in Homebrew and Ubuntu builds), which must
    # be static since it may be absent on end-user machines. Both Homebrew and apt ship libzstd.a
    # next to the dylib/.so, so plain -lzstd picks the dynamic one; resolve the .a path via
    # pkg-config and pass it directly, which forces static on every platform.
    ZSTD_STATIC=""
    if command -v pkg-config >/dev/null 2>&1 && pkg-config --exists libzstd 2>/dev/null; then
      ZSTD_LIBDIR="$(pkg-config --variable=libdir libzstd 2>/dev/null || true)"
      [[ -f "$ZSTD_LIBDIR/libzstd.a" ]] && ZSTD_STATIC="$ZSTD_LIBDIR/libzstd.a"
    fi
    if [[ -z "$ZSTD_STATIC" ]]; then
      echo "  WARNING: no static libzstd.a found via pkg-config -- falling back to dynamic -lzstd, which reintroduces a runtime dependency (install e.g. 'libzstd-dev' on Debian/Ubuntu, or 'zstd' via Homebrew)." >&2
      ZSTD_STATIC="-lzstd"
    fi
    # terminfo: libLLVMSupport.a calls set_curterm/setupterm/tigetnum/del_curterm directly, and
    # llvm-config --system-libs omits it. Linux only (macOS resolves it via libSystem). Probe
    # candidate libs with a throwaway link since the -dev package providing the .so varies.
    TERMINFO_LIB=""
    if [[ "$(uname -s)" != "Darwin" ]]; then
      for cand in tinfo ncursesw ncurses curses; do
        if echo 'int main(){return 0;}' | "$CLANG" -x c - -l"$cand" -o /dev/null >/dev/null 2>&1; then
          TERMINFO_LIB="-l$cand"
          break
        fi
      done
      [[ -n "$TERMINFO_LIB" ]] || echo "  WARNING: no linkable terminfo library found (tried tinfo/ncursesw/ncurses/curses) -- link will likely fail with undefined references to set_curterm/setupterm/tigetnum/del_curterm. Install e.g. 'libtinfo-dev' (Debian/Ubuntu) or 'ncurses-devel' (Fedora)." >&2
    fi
    case "$(uname -s)" in
      Darwin) LLVM_SYSTEM_LIBS=(-lc++ "$ZSTD_STATIC") ;;
      *) LLVM_SYSTEM_LIBS=(-lstdc++ "$ZSTD_STATIC" ${TERMINFO_LIB:+"$TERMINFO_LIB"}) ;;
    esac
    LLVM_SYSTEM_LIBS+=($(llvm-config --system-libs 2>/dev/null))
    if [[ "${#LLVM_STATIC_LIBS[@]}" -gt 0 ]]; then
      LLVM_DIRECT_CODEGEN_LINKING_OPTS=(--linking "-L$LLVM_CONFIG_LIBDIR")
      # Array-guard idiom: macOS's bash 3.2 treats expanding an empty array as unbound under
      # `set -u`, and llvm-config --system-libs returns nothing on some builds (e.g. Homebrew's).
      #
      # LLVM_STATIC_LIBS is listed twice: the component archives reference each other circularly
      # and ld64 resolves archives in a single left-to-right pass (no --start-group equivalent).
      for f in "${LLVM_STATIC_LIBS[@]}" "${LLVM_STATIC_LIBS[@]}" ${LLVM_SYSTEM_LIBS[@]+"${LLVM_SYSTEM_LIBS[@]}"}; do
        LLVM_DIRECT_CODEGEN_LINKING_OPTS+=(--linking "$f")
      done
      echo "  useLLVMDirectCodeGen: statically linking ${#LLVM_STATIC_LIBS[@]} LLVM component libs + ${#LLVM_SYSTEM_LIBS[@]} system libs from $LLVM_CONFIG_LIBDIR"
    fi
  fi
fi
if [[ "${#LLVM_DIRECT_CODEGEN_LINKING_OPTS[@]}" -eq 0 ]]; then
  echo "no usable llvm-config/static libLLVM (>= 13) found on PATH -- required to link scalino-linkdriver since direct LLVM-C codegen is on by default. Install e.g. 'llvm-dev' (Debian/Ubuntu, ships static libs already) or 'llvm' (Homebrew, ditto); on distros that split them out (e.g. Fedora's 'llvm-static'), install that too. Ensure llvm-config is on PATH." >&2
  exit 1
fi

# Extra static libs for the final link (e.g. the compiler-rt profile runtime
# of a build/pgo instrumented link), space separated.
EXTRA_LINKING_OPTS=()
for f in ${SCALINO_EXTRA_LINKING:-}; do EXTRA_LINKING_OPTS+=(--linking "$f"); done

# Links LinkDriver with an existing native scalino-linkdriver $1 (direct codegen
# is its default) into $2; further args are env settings for that run.
native_link() {
  local linker="$1" dir="$2"; shift 2
  rm -rf "$dir"
  mkdir -p "$dir"
  env SCALANATIVE_EVAC_ENABLE="${SCALINO_BOOTSTRAP_EVAC:-1}" "$@" "$linker" \
    "$(to_native_path "$NATIVE_DRIVER_CP")" \
    "$(to_native_path "$dir")" \
    LinkDriver \
    "$CLANG" \
    "$CLANGPP" \
    info \
    --mode "$SCALINO_LINK_MODE" \
    --lto "$SCALINO_LINK_LTO" \
    --no-incremental-compilation \
    "${LLVM_DIRECT_CODEGEN_LINKING_OPTS[@]+"${LLVM_DIRECT_CODEGEN_LINKING_OPTS[@]}"}" \
    "${EXTRA_LINKING_OPTS[@]+"${EXTRA_LINKING_OPTS[@]}"}" \
    ${SCALINO_LINK_EXTRA_ARGS:-}
}

if [[ -n "${SCALINO_BOOTSTRAP_LINKDRIVER:-}" ]]; then
  # Link with an existing native scalino-linkdriver (e.g. fetched by
  # 04b-fetch-scalino-linkdriver.sh) instead of the JVM-hosted LinkDriver.
  native_link "$SCALINO_BOOTSTRAP_LINKDRIVER" "$LINK_WORK"
else
  "$JAVA" \
    -Xss64m -XX:MaxRAMPercentage="${SCALINO_LINK_RAM_PERCENT:-80}" \
    -cp "$DRIVER_CP" \
      LinkDriver \
      "$(to_native_path "$NATIVE_DRIVER_CP")" \
      "$(to_native_path "$LINK_WORK")" \
      LinkDriver \
      "$CLANG" \
      "$CLANGPP" \
      info \
      --mode "$SCALINO_LINK_MODE" \
      --lto "$SCALINO_LINK_LTO" \
      --multithreading \
      --no-direct-codegen \
      --no-incremental-compilation \
      "${LLVM_DIRECT_CODEGEN_LINKING_OPTS[@]+"${LLVM_DIRECT_CODEGEN_LINKING_OPTS[@]}"}" \
      "${EXTRA_LINKING_OPTS[@]+"${EXTRA_LINKING_OPTS[@]}"}"
fi

BUILT="$LINK_WORK/LinkDriver"
[[ -f "$BUILT" ]] || BUILT="$LINK_WORK/LinkDriver.exe"
[[ -f "$BUILT" ]] || { echo "link did not produce $BUILT" >&2; exit 1; }
# Release builds relink once more, with the plain build as the linker, using the
# committed PGO profiles (pgo/, see docs/pgo.md). The profiles were taken on
# exactly this link (direct codegen, same flags), so it has to be the native
# command above, not the JVM one. A failing PGO link keeps the plain build.
source ./pgo/pgo-lib.sh
pgo_prepare linkdriver
if [[ ${#PGO_ENV[@]} -gt 0 ]]; then
  echo "== relinking scalino-linkdriver with PGO"
  PGO_WORK="${LINK_WORK}-pgo"
  if native_link "$BUILT" "$PGO_WORK" "${PGO_ENV[@]}" && [[ -f "$PGO_WORK/LinkDriver" ]]; then
    BUILT="$PGO_WORK/LinkDriver"
  else
    echo "WARNING: PGO link failed, shipping the plain scalino-linkdriver" >&2
  fi
fi
if [[ -n "${SCALINO_LINK_NO_DIST:-}" ]]; then
  echo "built $BUILT (SCALINO_LINK_NO_DIST: dist/ untouched)"
  exit 0
fi
# rm first: overwriting a running/mapped signed binary in place leaves a stale code-signature
# cache on macOS and the new file is killed at launch (CODE SIGNING: rejecting invalid page)
rm -f "$DIST/scalino-linkdriver"
cp "$BUILT" "$DIST/scalino-linkdriver"
chmod +x "$DIST/scalino-linkdriver"

echo "OK: $DIST/scalino-linkdriver"
