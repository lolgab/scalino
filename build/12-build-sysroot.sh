#!/usr/bin/env bash
# Builds the sysroot `scalino` needs to cross-compile to a Linux target:
#
#   build/12-build-sysroot.sh <x86_64|aarch64>-unknown-linux-musl
#   build/12-build-sysroot.sh <x86_64|aarch64>-unknown-linux-gnu
#   build/12-build-sysroot.sh <x86_64|aarch64>-apple-darwin
#   build/12-build-sysroot.sh <x86_64|aarch64>-pc-windows-gnu
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
# The gnu sysroots are Debian 11's glibc 2.31 (libc6, libc6-dev, linux-libc-dev), unpacked
# from the .debs, so binaries run on any distro with glibc >= 2.31.
#
# The windows sysroots are the data directories of an llvm-mingw release (mingw-w64,
# libc++, libunwind, compiler-rt builtins), pinned by checksum.
#
# The macOS sysroot is not Apple's SDK (which can't be redistributed): it is
# the Darwin libc headers (Apple open source) that the Zig project maintains in
# lib/libc, taken from a checksum-pinned Zig source tarball -- only that data, no
# Zig tooling -- and a libSystem.tbd stub generated from those headers' own
# declarations (build/libsystem-symbols-*.txt). It is enough for Scala Native,
# which needs nothing past libSystem (no frameworks). Link with lld.
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

TRIPLE="${1:?usage: $0 <x86_64|aarch64>-unknown-linux-<musl|gnu> | <x86_64|aarch64>-apple-darwin | <x86_64|aarch64>-pc-windows-gnu}"
case "$TRIPLE" in
  x86_64-unknown-linux-musl|aarch64-unknown-linux-musl) KIND=musl ;;
  x86_64-unknown-linux-gnu|aarch64-unknown-linux-gnu) KIND=gnu ;;
  x86_64-apple-darwin|aarch64-apple-darwin) KIND=macos ;;
  x86_64-pc-windows-gnu|aarch64-pc-windows-gnu) KIND=windows ;;
  *) echo "unsupported triple '$TRIPLE'" >&2; exit 1 ;;
esac
ARCH="${TRIPLE%%-*}"
MUSL_TARGET="$ARCH-linux-musl"

# Every sysroot ships the licence texts of what it contains, and a README naming
# the components (with where to get their source, for the LGPL glibc), so that
# redistributing it -- and binaries linked against it -- stays compliant.
add_license() { # file-in-source  name-in-LICENSES
  mkdir -p "$OUT/LICENSES"; cp -L "$1" "$OUT/LICENSES/$2"
}
write_readme() { # body
  mkdir -p "$OUT/LICENSES"
  {
    echo "scalino sysroot for $TRIPLE (built by build/12-build-sysroot.sh from scalino $(git -C "$ROOT" describe --tags --always 2>/dev/null || echo dev))."
    echo
    echo "This is NOT part of scalino's own Apache-2.0 licensed code: it is third-party software,"
    echo "each component under its own licence, whose text is in this directory's LICENSES/."
    echo
    echo "$1"
  } > "$OUT/LICENSES/README"
}

if [[ "$KIND" == macos ]]; then
  SRC="$WORK/sysroot-src"; mkdir -p "$SRC"
  sha256_of() { if command -v sha256sum >/dev/null; then sha256sum "$1" | cut -d' ' -f1; else shasum -a 256 "$1" | cut -d' ' -f1; fi; }
  ZIG_TAR="$SRC/zig-$ZIG_VERSION.tar.xz"
  [[ -f "$ZIG_TAR" ]] || curl -fsSL -o "$ZIG_TAR" "https://ziglang.org/download/$ZIG_VERSION/zig-$ZIG_VERSION.tar.xz"
  [[ "$(sha256_of "$ZIG_TAR")" == "$ZIG_SHA256" ]] || { echo "checksum mismatch for zig-$ZIG_VERSION.tar.xz" >&2; rm -f "$ZIG_TAR"; exit 1; }
  NAME="scalino-sysroot-$TRIPLE"
  B="$WORK/sysroot-build/$TRIPLE"; OUT="$B/$NAME"
  rm -rf "$B"; mkdir -p "$OUT/usr/lib"
  tar xf "$ZIG_TAR" -C "$B" --strip-components=1 "zig-$ZIG_VERSION/LICENSE" "zig-$ZIG_VERSION/lib/libc/include/any-darwin-any"
  cp -R "$B/lib/libc/include/any-darwin-any" "$OUT/usr/include"
  # libSystem.tbd is our own: the symbol names the Darwin headers declare (see
  # build/gen-libsystem-symbols.py), not the stub from Apple's SDK.
  python3 - "$ROOT/build" "$OUT/usr/lib/libSystem.tbd" <<'PY'
import sys
build, out = sys.argv[1:]
def load(arch):
    return {l.strip() for l in open("%s/libsystem-symbols-%s.txt" % (build, arch)) if l.startswith("_")}
extra = {l.strip() for l in open("%s/libsystem-symbols-extra.txt" % build) if l.strip() and not l.startswith("#")}
x86, arm = load("x86_64") | extra, load("arm64") | extra
groups = [("x86_64-macos, arm64-macos", x86 & arm), ("x86_64-macos", x86 - arm), ("arm64-macos", arm - x86)]
with open(out, "w") as f:
    f.write("--- !tapi-tbd\ntbd-version:     4\ntargets:         [ x86_64-macos, arm64-macos ]\n")
    f.write("install-name:    '/usr/lib/libSystem.B.dylib'\ncurrent-version: 1351\ncompatibility-version: 1\nexports:\n")
    for targets, syms in groups:
        if syms:
            f.write("  - targets:         [ %s ]\n    symbols:         [ %s ]\n" % (targets, ", ".join(sorted(syms))))
    f.write("...\n")
PY
  # Scala Native links -lpthread -ldl -lm (-lc): on macOS those are all libSystem,
  # which the real SDK expresses as re-exporting stubs. A copy does the same job.
  for l in c m pthread dl; do cp "$OUT/usr/lib/libSystem.tbd" "$OUT/usr/lib/lib$l.tbd"; done
  # -lz (java.util.zip): zlib's public API, a stub for /usr/lib/libz.1.dylib.
  ZSYMS="adler32 adler32_combine adler32_z compress compress2 compressBound crc32 crc32_combine crc32_z deflate deflateBound deflateCopy deflateEnd deflateGetDictionary deflateInit2_ deflateInit_ deflateParams deflatePending deflatePrime deflateReset deflateResetKeep deflateSetDictionary deflateSetHeader deflateTune get_crc_table gzbuffer gzclearerr gzclose gzclose_r gzclose_w gzdirect gzdopen gzeof gzerror gzflush gzfread gzfwrite gzgetc gzgets gzoffset gzopen gzopen64 gzprintf gzputc gzputs gzread gzrewind gzseek gzseek64 gzsetparams gztell gztell64 gzungetc gzvprintf gzwrite inflate inflateBack inflateBackEnd inflateBackInit_ inflateCopy inflateEnd inflateGetDictionary inflateGetHeader inflateInit2_ inflateInit_ inflateMark inflatePrime inflateReset inflateReset2 inflateResetKeep inflateSetDictionary inflateSync inflateSyncPoint inflateUndermine inflateValidate uncompress uncompress2 zError zlibCompileFlags zlibVersion"
  {
    echo "--- !tapi-tbd"
    echo "tbd-version:     4"
    echo "targets:         [ x86_64-macos, arm64-macos ]"
    echo "install-name:    '/usr/lib/libz.1.dylib'"
    echo "current-version: 1.2.12"
    echo "exports:"
    echo "  - targets:         [ x86_64-macos, arm64-macos ]"
    echo -n "    symbols:         [ "
    first=1; for s in $ZSYMS; do if [[ $first == 1 ]]; then first=0; else echo -n ", "; fi; echo -n "_$s"; done; echo " ]"
    echo "..."
  } > "$OUT/usr/lib/libz.tbd"
  add_license "$B/LICENSE" zig-LICENSE-MIT
  write_readme "Components:
  usr/include/**   Darwin libc headers (Apple open source: mostly the Apple Public Source License,
                   some BSD-style), unmodified, from the Zig project's lib/libc (zig-$ZIG_VERSION,
                   MIT, see zig-LICENSE-MIT). Each header keeps its own licence notice.
  usr/lib/libSystem.tbd, libc/libm/libpthread/libdl.tbd
                   generated by scalino (build/gen-libsystem-symbols.py): the names of the functions
                   and variables those headers declare, as a linker stub for
                   /usr/lib/libSystem.B.dylib. Symbol names only; not taken from Apple's SDK.
  usr/lib/libz.tbd generated by scalino from zlib's public API (symbol names only).
Source of the headers: https://github.com/apple-oss-distributions (Libc, xnu, ...)."
  mkdir -p "$DIST/sysroot-packages"
  TARBALL="$DIST/sysroot-packages/$NAME.tar.gz"
  tar czf "$TARBALL" -C "$B" "$NAME"
  (cd "$DIST/sysroot-packages" && echo "$(sha256_of "$TARBALL")  $NAME.tar.gz" > "$NAME.tar.gz.sha256")
  echo "OK: $TARBALL"
  exit 0
fi

if [[ "$KIND" == windows ]]; then
  SRC="$WORK/sysroot-src"; mkdir -p "$SRC"
  sha256_of() { if command -v sha256sum >/dev/null; then sha256sum "$1" | cut -d' ' -f1; else shasum -a 256 "$1" | cut -d' ' -f1; fi; }
  LM="llvm-mingw-$LLVM_MINGW_VERSION-ucrt-ubuntu-22.04-x86_64"
  LM_TAR="$SRC/$LM.tar.xz"
  [[ -f "$LM_TAR" ]] || curl -fsSL -o "$LM_TAR" "https://github.com/mstorsjo/llvm-mingw/releases/download/$LLVM_MINGW_VERSION/$LM.tar.xz"
  [[ "$(sha256_of "$LM_TAR")" == "$LLVM_MINGW_SHA256" ]] || { echo "checksum mismatch for $LM.tar.xz" >&2; rm -f "$LM_TAR"; exit 1; }
  NAME="scalino-sysroot-$TRIPLE"
  B="$WORK/sysroot-build/$TRIPLE"; OUT="$B/$NAME"
  rm -rf "$B"; mkdir -p "$OUT/resource/lib/windows"
  MW="$ARCH-w64-mingw32"
  tar xf "$LM_TAR" -C "$B" "$LM/LICENSE.TXT" "$LM/generic-w64-mingw32" "$LM/$MW" "$LM/lib/clang"
  mv "$B/$LM/generic-w64-mingw32" "$B/$LM/$MW" "$OUT/"
  cp "$B"/$LM/lib/clang/*/lib/windows/libclang_rt.builtins-$ARCH.a "$OUT/resource/lib/windows/"
  add_license "$B/$LM/LICENSE.TXT" llvm-LICENSE.TXT
  # The mingw-w64 runtime's licensing file, written for binaries statically linked against it.
  MINGW_W64_TAG="${MINGW_W64_LICENSE_TAG:-v13.0.0}"
  for f in COPYING.MinGW-w64-runtime COPYING.MinGW-w64; do
    curl -fsSL -o "$B/$f.txt" "https://raw.githubusercontent.com/mingw-w64/mingw-w64/$MINGW_W64_TAG/$f/$f.txt"
    add_license "$B/$f.txt" "mingw-w64-$f.txt"
  done
  write_readme "Components (from the llvm-mingw $LLVM_MINGW_VERSION release, https://github.com/mstorsjo/llvm-mingw):
  $MW/, generic-w64-mingw32/   mingw-w64 headers, CRT and import libraries (ZPL-2.1, public domain and
                               BSD/MIT-style, see mingw-w64-COPYING.MinGW-w64*.txt; texts from mingw-w64 $MINGW_W64_TAG),
                               winpthreads (MIT-style, same files), libc++, libc++abi and libunwind
                               (Apache-2.0 with LLVM Exceptions, see llvm-LICENSE.TXT).
  resource/lib/windows/        compiler-rt builtins (Apache-2.0 with LLVM Exceptions).
Source: https://github.com/mstorsjo/llvm-mingw, https://www.mingw-w64.org, https://github.com/llvm/llvm-project"
  # shared-library import stubs and static libs we never link
  rm -rf "$OUT/$MW/bin" "$OUT/$MW/share" "$OUT/generic-w64-mingw32/share"
  mkdir -p "$DIST/sysroot-packages"
  TARBALL="$DIST/sysroot-packages/$NAME.tar.gz"
  tar czf "$TARBALL" -C "$B" "$NAME"
  (cd "$DIST/sysroot-packages" && echo "$(sha256_of "$TARBALL")  $NAME.tar.gz" > "$NAME.tar.gz.sha256")
  echo "OK: $TARBALL"
  exit 0
fi

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
[[ "$KIND" == musl ]] && fetch "https://musl.libc.org/releases/musl-$MUSL_VERSION.tar.gz" "musl-$MUSL_VERSION.tar.gz" "$MUSL_SHA256"
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
[[ "$KIND" == musl ]] && tar xf "$SRC/musl-$MUSL_VERSION.tar.gz" -C "$B/src/musl" --strip-components=1

if [[ "$KIND" == gnu ]]; then
  # 1. glibc: unpack Debian's runtime + dev + kernel-header packages.
  case "$ARCH" in x86_64) DEB_ARCH=amd64 ;; aarch64) DEB_ARCH=arm64 ;; esac
  DEB_URL="https://archive.debian.org/debian/pool/main"
  deb_sha() { case "$1" in
    libc6_${GLIBC_DEB_VERSION}_amd64.deb) echo 05f7264da867b37f4c5ce49266b558ea1e81e05a9464f623152fca70f3550282 ;;
    libc6-dev_${GLIBC_DEB_VERSION}_amd64.deb) echo e7f7b45d9c5cfcf37609f0b6efd3c645272c812144703af89dfd32218fcb0fd3 ;;
    linux-libc-dev_${LINUX_LIBC_DEV_VERSION}_amd64.deb) echo e3be603bd12377bb90fcfb0f2048ca3a81c886e49d6f09945b82159d1c54f7b2 ;;
    libc6_${GLIBC_DEB_VERSION}_arm64.deb) echo baaa9aa184e2f21738c5819055e6740cc5b22f198e3f416e33f82b40ff6933d8 ;;
    libc6-dev_${GLIBC_DEB_VERSION}_arm64.deb) echo 28d478134722dcd4b0bd2045a199301d18713bf95947b9fce66634e7aeacab2e ;;
    linux-libc-dev_${LINUX_LIBC_DEV_VERSION}_arm64.deb) echo 8b6374a64412d33eac61d74f77b8f932da4b8a707ea8a614791e2a35b8917618 ;;
  esac; }
  for deb in "libc6_${GLIBC_DEB_VERSION}_$DEB_ARCH.deb:g/glibc" "libc6-dev_${GLIBC_DEB_VERSION}_$DEB_ARCH.deb:g/glibc" "linux-libc-dev_${LINUX_LIBC_DEV_VERSION}_$DEB_ARCH.deb:l/linux"; do
    f="${deb%%:*}"; dir="${deb##*:}"
    fetch "$DEB_URL/$dir/$f" "$f" "$(deb_sha "$f")"
    mkdir -p "$B/deb"; (cd "$B/deb" && ar x "$SRC/$f" && tar xf data.tar.* -C "$OUT" && rm -f data.tar.* control.tar.* debian-binary)
  done
  # Debian's dev symlinks are absolute (/lib/...): make them relative so they
  # resolve inside the sysroot.
  python3 - "$OUT" <<'PY'
import os, sys
root = sys.argv[1]
for d, _, fs in os.walk(root):
    for f in fs:
        p = os.path.join(d, f)
        if os.path.islink(p) and os.readlink(p).startswith('/'):
            new = os.path.relpath(os.path.join(root, os.readlink(p).lstrip('/')), d)
            os.remove(p); os.symlink(new, p)
PY
  for pkg in libc6 libc6-dev linux-libc-dev; do
    [[ -e "$OUT/usr/share/doc/$pkg/copyright" ]] && add_license "$OUT/usr/share/doc/$pkg/copyright" "debian-$pkg-copyright"
  done
  rm -rf "$OUT/etc" "$OUT/usr/share" "$OUT/usr/lib/x86_64-linux-gnu/gconv" "$OUT/usr/lib/aarch64-linux-gnu/gconv"
  write_readme "Components (unmodified Debian 11 packages from archive.debian.org):
  libc6, libc6-dev $GLIBC_DEB_VERSION   the GNU C Library, LGPL-2.1-or-later (some files under other
                                         licences, see debian-*-copyright). Binaries built against this
                                         link it dynamically.
  linux-libc-dev $LINUX_LIBC_DEV_VERSION Linux kernel UAPI headers, GPL-2.0 WITH Linux-syscall-note.
Corresponding source, as the LGPL asks for (written offer; valid for as long as these are served):
  https://archive.debian.org/debian/pool/main/g/glibc/glibc_${GLIBC_DEB_VERSION%%+*}+${GLIBC_DEB_VERSION##*+}.dsc
  https://archive.debian.org/debian/pool/main/g/glibc/  (glibc_2.31.orig.tar.xz, glibc_$GLIBC_DEB_VERSION.debian.tar.xz)
  https://archive.debian.org/debian/pool/main/l/linux/  (linux $LINUX_LIBC_DEV_VERSION)
compiler-rt builtins (resource/): Apache-2.0 with LLVM Exceptions, see llvm-compiler-rt-LICENSE.TXT."
else
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
fi

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
[[ "$KIND" == musl ]] && rm -rf "$OUT/lib" "$OUT/usr/share"
add_license "$B/src/compiler-rt/LICENSE.TXT" llvm-compiler-rt-LICENSE.TXT
if [[ "$KIND" == musl ]]; then
  add_license "$B/src/musl/COPYRIGHT" musl-COPYRIGHT
  write_readme "Components:
  usr/**           musl libc $MUSL_VERSION (MIT; a few files under other permissive licences,
                   see musl-COPYRIGHT), linked statically into binaries built against it.
                   Only change: DEFAULT_STACK_SIZE raised from 128K to 8M. Source: https://musl.libc.org
  resource/lib/**  compiler-rt $LLVM_RT_VERSION builtins and crtbegin/crtend (Apache-2.0 with LLVM
                   Exceptions, see llvm-compiler-rt-LICENSE.TXT). Source: https://github.com/llvm/llvm-project"
fi

mkdir -p "$DIST/sysroot-packages"
TARBALL="$DIST/sysroot-packages/$NAME.tar.gz"
tar czf "$TARBALL" -C "$B" "$NAME"
(cd "$DIST/sysroot-packages" && echo "$(sha256_of "$TARBALL")  $NAME.tar.gz" > "$NAME.tar.gz.sha256")
echo "OK: $TARBALL"
