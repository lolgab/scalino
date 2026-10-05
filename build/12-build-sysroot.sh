#!/usr/bin/env bash
# Builds the sysroot `scalino` needs to cross-compile to a Linux target:
#
#   build/12-build-sysroot.sh <x86_64|aarch64>-unknown-linux-musl
#
# Output: dist/sysroot-packages/scalino-sysroot-<triple>.tar.gz (+ .sha256),
# the file `scalino sysroot fetch <triple>` downloads. Layout, relative to the
# tarball's top dir `scalino-sysroot-<triple>/`:
#
#   usr/include, usr/lib    musl libc headers, libc.a, crt1.o...
#   resource/lib/linux/     compiler-rt builtins + crtbegin/crtend, standing
#                           in for libgcc (LinkDriver wires them in with
#                           -rtlib=compiler-rt)
#
# Needs on PATH (or in $LLVM_BIN): clang, llvm-ar, llvm-ranlib, llvm-nm, plus
# cmake, ninja, make, curl. Runs on any host: everything is cross-built with
# clang --target, nothing is executed.
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")"
# Not 00-env.sh: that one wants a JDK, which a sysroot build has no use for.
ROOT="$(cd .. && pwd)"
source "$ROOT/versions.env"
DIST="$ROOT/dist"
WORK="$ROOT/.build-work"
mkdir -p "$DIST" "$WORK"

TRIPLE="${1:?usage: $0 <x86_64|aarch64>-unknown-linux-musl}"
case "$TRIPLE" in
  x86_64-unknown-linux-musl|aarch64-unknown-linux-musl) ;;
  *) echo "unsupported triple '$TRIPLE'" >&2; exit 1 ;;
esac
ARCH="${TRIPLE%%-*}"
MUSL_TARGET="$ARCH-linux-musl"

LLVM_BIN="${LLVM_BIN:-}"
tool() { if [[ -n "$LLVM_BIN" ]]; then echo "$LLVM_BIN/$1"; else command -v "$1" || { echo "missing tool: $1" >&2; exit 1; }; fi; }
CLANG="$(tool clang)"; AR="$(tool llvm-ar)"; RANLIB="$(tool llvm-ranlib)"; NM="$(tool llvm-nm)"

SRC="$WORK/sysroot-src"
mkdir -p "$SRC"

sha256_of() { if command -v sha256sum >/dev/null; then sha256sum "$1" | cut -d' ' -f1; else shasum -a 256 "$1" | cut -d' ' -f1; fi; }
fetch() { # url file sha256
  [[ -f "$SRC/$2" ]] || curl -fsSL -o "$SRC/$2" "$1"
  [[ "$(sha256_of "$SRC/$2")" == "$3" ]] || { echo "checksum mismatch for $2" >&2; rm -f "$SRC/$2"; exit 1; }
}
LLVM_URL="https://github.com/llvm/llvm-project/releases/download/llvmorg-$LLVM_RT_VERSION"
fetch "https://musl.libc.org/releases/musl-$MUSL_VERSION.tar.gz" "musl-$MUSL_VERSION.tar.gz" "$MUSL_SHA256"
fetch "$LLVM_URL/compiler-rt-$LLVM_RT_VERSION.src.tar.xz" "compiler-rt-$LLVM_RT_VERSION.tar.xz" "$LLVM_COMPILER_RT_SHA256"
fetch "$LLVM_URL/cmake-$LLVM_RT_VERSION.src.tar.xz" "cmake-$LLVM_RT_VERSION.tar.xz" "$LLVM_CMAKE_SHA256"

B="$WORK/sysroot-build/$TRIPLE"
NAME="scalino-sysroot-$TRIPLE"
OUT="$B/$NAME"
rm -rf "$B"; mkdir -p "$B" "$OUT"

# compiler-rt's standalone build expects the llvm `cmake/` modules next door.
mkdir -p "$B/src/compiler-rt" "$B/src/cmake" "$B/src/musl"
tar xf "$SRC/compiler-rt-$LLVM_RT_VERSION.tar.xz" -C "$B/src/compiler-rt" --strip-components=1
tar xf "$SRC/cmake-$LLVM_RT_VERSION.tar.xz" -C "$B/src/cmake" --strip-components=1
tar xf "$SRC/musl-$MUSL_VERSION.tar.gz" -C "$B/src/musl" --strip-components=1

# musl's default thread stack is 128K (glibc: 8M). Scala Native's GC and
# deep-recursing Scala code on pool threads overflow that, so match glibc.
sed -i.bak 's/#define DEFAULT_STACK_SIZE 131072/#define DEFAULT_STACK_SIZE 8388608/' "$B/src/musl/src/internal/pthread_impl.h"
grep -q 'DEFAULT_STACK_SIZE 8388608' "$B/src/musl/src/internal/pthread_impl.h" || { echo "musl DEFAULT_STACK_SIZE patch did not apply" >&2; exit 1; }

# 1. musl. LIBCC=" ": libc.a must not reference libgcc, compiler-rt supplies it.
mkdir -p "$B/musl-build"
(
  cd "$B/musl-build"
  "$B/src/musl/configure" --target="$MUSL_TARGET" --prefix="$OUT/usr" --syslibdir="$OUT/lib" --disable-shared \
    CC="$CLANG --target=$MUSL_TARGET" AR="$AR" RANLIB="$RANLIB" LIBCC=" " >/dev/null
  make -j"$(getconf _NPROCESSORS_ONLN 2>/dev/null || echo 4)" >/dev/null
  make install >/dev/null
)

# 2. compiler-rt builtins + crtbegin/crtend, cross-built against that musl.
# emupac.cpp (pointer-auth emulation) needs llvm's third-party siphash tree.
sed -i.bak '/emupac\.cpp/d' "$B/src/compiler-rt/lib/builtins/CMakeLists.txt"
cmake -S "$B/src/compiler-rt/lib/builtins" -B "$B/crt-build" -G Ninja \
  -DCMAKE_C_COMPILER="$CLANG" -DCMAKE_ASM_COMPILER="$CLANG" \
  -DCMAKE_AR="$AR" -DCMAKE_RANLIB="$RANLIB" -DCMAKE_NM="$NM" \
  -DCMAKE_C_COMPILER_TARGET="$TRIPLE" -DCMAKE_ASM_COMPILER_TARGET="$TRIPLE" \
  -DCMAKE_SYSTEM_NAME=Linux -DCMAKE_SYSROOT="$OUT" -DCMAKE_TRY_COMPILE_TARGET_TYPE=STATIC_LIBRARY \
  -DCOMPILER_RT_DEFAULT_TARGET_ONLY=ON -DCOMPILER_RT_BUILD_CRT=ON -DCMAKE_BUILD_TYPE=Release \
  -DCMAKE_INSTALL_PREFIX="$OUT/resource" -DCOMPILER_RT_INSTALL_PATH="$OUT/resource" >/dev/null
ninja -C "$B/crt-build"
ninja -C "$B/crt-build" install >/dev/null

# Drop what the link never reads (the .so stubs musl leaves, empty libs are kept:
# `-lpthread -ldl -lm` must still resolve).
rm -rf "$OUT/lib" "$OUT/usr/share"

mkdir -p "$DIST/sysroot-packages"
TARBALL="$DIST/sysroot-packages/$NAME.tar.gz"
tar czf "$TARBALL" -C "$B" "$NAME"
(cd "$DIST/sysroot-packages" && echo "$(sha256_of "$TARBALL")  $NAME.tar.gz" > "$NAME.tar.gz.sha256")
echo "OK: $TARBALL"
