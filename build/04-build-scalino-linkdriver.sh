#!/usr/bin/env bash
# Builds scalino-linkdriver: a standalone binary wrapping scala-native's
# tools_3 library (NIR -> LLVM IR -> clang -> native executable). Self-hosted
# like the rest of the toolchain: dotc compiles LinkDriver.scala to NIR, then
# LinkDriver links itself (run on the JVM, same bootstrap step 03/08 reuse).
#
# Uses tools-patched.cp (04a-patch-tools.sh): fixes scala-native's
# object-file caching for vendored C/S dependencies, which was otherwise
# effectively always-recompile regardless of whether anything changed --
# see docs/findings.md "Native-library object-file caching was inert".
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")"
source ./00-env.sh

[[ -f "$WORK/tools-patched.cp" ]] || { echo "run 04a-patch-tools.sh first" >&2; exit 1; }
[[ -f "$WORK/tools-patched-jvm.cp" ]] || { echo "run 04a-patch-tools.sh first" >&2; exit 1; }

# tools-patched-jvm.cp (04a-patch-tools.sh), NOT the raw tools.cp: LinkDriver
# is compiled (below) against the *native*-targeted patched classpath
# (NATIVE_DRIVER_CP), so its bytecode can reference e.g.
# NativeConfig.withLLVMDirectCodeGen -- but it then actually *runs* here, on
# a plain JVM, against DRIVER_CP. Those two classpaths must agree on
# NativeConfig's shape or this throws NoSuchMethodError at runtime for
# everyone, not just users of that experimental flag.
DRIVER_CP="$(cat "$WORK/compiler.cp")$CP_SEP$(cat "$WORK/tools-patched-jvm.cp")$CP_SEP$(to_native_path "$WORK/driver-classes")"

# Substitute the published javalib_native0.5_3 jar for the locally-built one
# with patches/scala-native-0009 (ZipFileSystemProvider) actually applied --
# see build/01b-build-patched-javalib.sh. This classpath becomes the actual
# runtime javalib baked into the linked scalino-linkdriver binary itself (not
# just this build step's own tooling), which is what determines whether the
# jar classpath entries LinkDriver hands to `Build` at RUNTIME (any real
# `scalino build`, not just this bootstrap) can be read via NIO's "jar:"
# FileSystemProvider instead of needing extractJarIfNeeded's workaround.
TOOLS_PATCHED_JAVALIB_CP="$WORK/tools-patched-javalib.cp"
LOCAL_JAVALIB_JAR="$HOME/.ivy2/local/org.scala-native/javalib_native0.5_3/${SCALA_NATIVE_VERSION}-SNAPSHOT/jars/javalib_native0.5_3.jar"
if [[ -f "$LOCAL_JAVALIB_JAR" ]]; then
  { tr "$CP_SEP" '\n' < "$WORK/tools-patched.cp" | grep -v '/javalib_native0\.5_3-'; echo "$LOCAL_JAVALIB_JAR"; } | paste -sd"$CP_SEP" - > "$TOOLS_PATCHED_JAVALIB_CP"
  echo "  using locally-built, patched javalib jar: $LOCAL_JAVALIB_JAR"
else
  cp "$WORK/tools-patched.cp" "$TOOLS_PATCHED_JAVALIB_CP"
  echo "  WARNING: locally-built javalib jar not found ($LOCAL_JAVALIB_JAR) -- run build/01b-build-patched-javalib.sh first, or patches/scala-native-0009 (ZipFileSystemProvider) will NOT take effect and scalino-linkdriver will fall back to needing jar-extraction workarounds. See docs/findings.md."
fi

# Same substitution for nativelib_native0.5_3 -- this bootstrap classpath
# (tools-patched.cp, via tools-native.cp/01-fetch-deps.sh) is a completely
# separate lineage from $WORK/nativelibs.cp (the one build/01c-build-patched-
# nativelib.sh substitutes in place), so 01c's own patched jar never reached
# scalino-linkdriver's own build until this swap was added. Harmless before
# nativelib gained new symbols an end user's javalib/nativelib could fail to
# resolve at NIR-link time (e.g. runtime.SmiBox$, added for SMI-style
# tagged-pointer boxing and referenced directly from javalib's boxed-primitive
# classes) -- scalino-linkdriver's own bootstrap build links a full program
# against javalib+nativelib together, so both need to agree on the same
# patched pair, not just javalib.
LOCAL_NATIVELIB_JAR="$HOME/.ivy2/local/org.scala-native/nativelib_native0.5_3/${SCALA_NATIVE_VERSION}-SNAPSHOT/jars/nativelib_native0.5_3.jar"
if [[ -f "$LOCAL_NATIVELIB_JAR" ]]; then
  TMP_NATIVELIB_SWAP="$(mktemp)"
  { tr "$CP_SEP" '\n' < "$TOOLS_PATCHED_JAVALIB_CP" | grep -v '/nativelib_native0\.5_3-'; echo "$LOCAL_NATIVELIB_JAR"; } | paste -sd"$CP_SEP" - > "$TMP_NATIVELIB_SWAP"
  mv "$TMP_NATIVELIB_SWAP" "$TOOLS_PATCHED_JAVALIB_CP"
  echo "  using locally-built, patched nativelib jar: $LOCAL_NATIVELIB_JAR"
else
  echo "  WARNING: locally-built nativelib jar not found ($LOCAL_NATIVELIB_JAR) -- run build/01c-build-patched-nativelib.sh first, or nativelib source patches will NOT take effect in scalino-linkdriver's own bootstrap build."
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

LINK_WORK="$WORK/driver-link"
rm -rf "$LINK_WORK"
mkdir -p "$LINK_WORK"

# NativeConfig's direct LLVM-C codegen backend is now ON BY DEFAULT (see
# tools-patched), which means the tools_3 link step invoked below -- linking
# scalino-linkdriver itself, not just a later `scalino build` -- pulls in the
# @extern LLVM-C bindings (tools/native/.../codegen/llvm/direct/LLVMCApi.scala)
# and needs libLLVM at link time, unconditionally. This is no longer optional:
# without it, the LinkDriver step below fails with cryptic
# "undefined reference to LLVMFunctionType" linker errors.
#
# Statically linked, not dynamically: the released scalino-linkdriver binary
# must run on end-user machines (curl-installed) that have no libLLVM at all
# (e.g. bare Fedora -- see docs/findings.md "static LLVM link"). Per-component
# archives (libLLVMCore.a, libLLVMSupport.a, ...) have no same-named .so/.dylib
# counterpart -- only the combined libLLVM does -- so plain "-lLLVMCore" etc.
# always resolves to the static archive on both Linux and macOS; no
# -Wl,-Bstatic tricks needed. `llvm-config --link-static` gives us those
# per-component -l flags instead of the one combined "-lLLVM".
LLVM_DIRECT_CODEGEN_LINKING_OPTS=()
if command -v llvm-config >/dev/null 2>&1; then
  LLVM_CONFIG_VERSION="$(llvm-config --version 2>/dev/null || true)"
  LLVM_CONFIG_MAJOR="${LLVM_CONFIG_VERSION%%.*}"
  LLVM_CONFIG_LIBDIR="$(llvm-config --libdir 2>/dev/null || true)"
  if [[ "${LLVM_CONFIG_MAJOR:-0}" =~ ^[0-9]+$ ]] && (( LLVM_CONFIG_MAJOR >= 13 )) \
      && [[ -n "$LLVM_CONFIG_LIBDIR" && -d "$LLVM_CONFIG_LIBDIR" ]]; then
    # "all" includes optional components (e.g. Polly/PollyISL) that some
    # distros' llvm-config reports as available even though the matching
    # .a isn't actually installed (Ubuntu's llvm-dev doesn't ship libPolly*,
    # that's a separate libpolly-<ver>-dev package) -- filter those -lFoo
    # flags down to ones whose libFoo.a actually exists in LLVM_CONFIG_LIBDIR,
    # instead of failing the link over components we don't use anyway.
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
    # llvm-config --system-libs doesn't report these two, but the archives
    # need them once actually resolved at (our) link time rather than
    # deferred to a shared lib's own dependency chain: (1) the C++ runtime --
    # dynamically linking the combined libLLVM.so/.dylib worked without one
    # because THAT link carried its own libc++/libstdc++ dependency,
    # transparently satisfied at load time; raw .a archives have no such
    # thing, their std::__1::... references become our problem directly.
    # (2) zstd -- LLVM_ENABLE_ZSTD is on in both Homebrew's and Ubuntu's LLVM
    # builds, but llvm-config's system-libs output omits it regardless
    # (longstanding llvm-config limitation, not distro-specific). Unlike
    # libc++/libstdc++ (ubiquitous system libs, fine to leave dynamic --
    # every Linux/macOS has them, same as Rust's own rustc does), zstd is
    # exactly the kind of not-guaranteed-present dependency this whole change
    # is meant to eliminate, so it must be forced static too. It isn't
    # LLVM's own archive (no per-component .a to exploit the "no same-named
    # dylib" trick with) -- Homebrew/apt both ship libzstd.a *and*
    # libzstd.dylib/.so side by side, so plain "-lzstd" silently prefers the
    # dynamic one. Resolve its real static archive path via pkg-config
    # (ships a .pc file on both Homebrew and Debian/Ubuntu's libzstd-dev) and
    # pass that path directly instead of "-lzstd" -- passing a linker a full
    # path to a .a, instead of "-l" + "-L", always forces static regardless
    # of platform, with no -Wl,-Bstatic/-Bdynamic (GNU-ld-only) needed.
    ZSTD_STATIC=""
    if command -v pkg-config >/dev/null 2>&1 && pkg-config --exists libzstd 2>/dev/null; then
      ZSTD_LIBDIR="$(pkg-config --variable=libdir libzstd 2>/dev/null || true)"
      [[ -f "$ZSTD_LIBDIR/libzstd.a" ]] && ZSTD_STATIC="$ZSTD_LIBDIR/libzstd.a"
    fi
    if [[ -z "$ZSTD_STATIC" ]]; then
      echo "  WARNING: no static libzstd.a found via pkg-config -- falling back to dynamic -lzstd, which reintroduces a runtime dependency (install e.g. 'libzstd-dev' on Debian/Ubuntu, or 'zstd' via Homebrew)." >&2
      ZSTD_STATIC="-lzstd"
    fi
    # terminfo: libLLVMSupport.a's Process::FileDescriptorHasColors calls
    # set_curterm/setupterm/tigetnum/del_curterm directly. llvm-config
    # --system-libs doesn't report this dependency either (same
    # longstanding limitation as zstd above) -- on Linux it's normally
    # pulled in transitively through ncurses/readline's own link chain,
    # but a raw static-archive link has no such transitive path and
    # leaves these undefined. Not needed on macOS: Apple's libc++/libSystem
    # link closure already resolves them there. Probe candidate lib names
    # (distros vary: tinfo vs the bundled-into-ncurses fallback) with a
    # throwaway link instead of hardcoding one, since the -dev package that
    # ships the linkable .so (not just the runtime .so.N) differs by distro.
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
      # array-guard idiom (not plain "${arr[@]}"): macOS's /bin/bash is stuck
      # on 3.2, which treats expanding a possibly-empty array as an unbound
      # variable under `set -u` -- llvm-config --system-libs legitimately
      # returns nothing on some LLVM builds (e.g. Homebrew's).
      #
      # LLVM_STATIC_LIBS listed *twice*: LLVM's component archives reference
      # each other circularly (e.g. libLLVMX86CodeGen.a's GlobalISel code
      # needs a vtable that only gets pulled in from libLLVMCodeGen.a), and
      # unlike GNU ld's --start-group/--end-group, Apple's ld64 (and a plain
      # single-pass ld in general) resolves archives in one left-to-right
      # pass -- a symbol needed by an earlier archive but defined only in a
      # later one is otherwise left undefined. Repeating the list is the
      # standard portable workaround (no --start-group equivalent on ld64).
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

"$JAVA" \
  -Xss64m -XX:MaxRAMPercentage=80.0 \
  -cp "$DRIVER_CP" \
    LinkDriver \
    "$(to_native_path "$NATIVE_DRIVER_CP")" \
    "$(to_native_path "$LINK_WORK")" \
    LinkDriver \
    "$CLANG" \
    "$CLANGPP" \
    info \
    --mode release-fast \
    --multithreading \
    "${LLVM_DIRECT_CODEGEN_LINKING_OPTS[@]+"${LLVM_DIRECT_CODEGEN_LINKING_OPTS[@]}"}"

BUILT="$LINK_WORK/LinkDriver"
[[ -f "$BUILT" ]] || BUILT="$LINK_WORK/LinkDriver.exe"
[[ -f "$BUILT" ]] || { echo "link did not produce $BUILT" >&2; exit 1; }
# rm first: overwriting a running/mapped signed binary in place leaves a stale code-signature
# cache on macOS and the new file is killed at launch (CODE SIGNING: rejecting invalid page)
rm -f "$DIST/scalino-linkdriver"
cp "$BUILT" "$DIST/scalino-linkdriver"
chmod +x "$DIST/scalino-linkdriver"

echo "OK: $DIST/scalino-linkdriver"
