#!/usr/bin/env bash
# Unit test for the GC safepoint trampoline (x86_64: patch 0069, aarch64: patch 0074), no Scala Native build needed.
#
# poll_test.S loads known values into every GPR, three XMM registers, the flags and the 128-byte
# red zone, then does a poll load against an armed (PROT_NONE) page. The SIGSEGV handler calls the
# real scalanative_gc_safepoint_prepare_redirect, the real trampoline runs (calling a stub
# Synchronizer_yield that scribbles on the caller-saved state and disarms the page), and the test
# checks that everything came back intact. Fails before 0069 (%r10 = resume address, red zone
# overwritten), passes after.
#
#   tests/safepoint-trampoline/run.sh [dir containing SafepointPollTrampoline.{c,h} and -x86_64.S]
set -euo pipefail
here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
root="$(cd "$here/../.." && pwd)"
dir="${1:-$root/vendor/scala-native/nativelib/src/main/resources/scala-native/gc/shared}"
cc="${CLANG:-clang}"
arch=x86_64
case "$(uname -s)-$(uname -m)" in
  Linux-x86_64) ;;
  Linux-aarch64|Linux-arm64|Darwin-arm64) arch=aarch64 ;;
  *) echo "skipped: the trampoline test only runs on Linux x86_64 and Linux/macOS arm64"; exit 0 ;;
esac
tmp="$(mktemp -d)"; trap 'rm -rf "$tmp"' EXIT
flags=(-O1 -g -DSCALANATIVE_MULTITHREADING_ENABLED -DSCALANATIVE_GC_USE_YIELDPOINT_TRAPS -DSCALANATIVE_GC_IMMIX -I"$here/stubs" -I"$dir")
suffix=""; [[ "$arch" == aarch64 ]] && suffix="_aarch64"
"$cc" ${flags[@]+"${flags[@]}"} -c "$dir/SafepointPollTrampoline.c" -o "$tmp/tramp_c.o"
"$cc" ${flags[@]+"${flags[@]}"} -c "$dir/SafepointPollTrampoline-$arch.S" -o "$tmp/tramp_s.o"
"$cc" ${flags[@]+"${flags[@]}"} -c "$here/poll_test$suffix.S" -o "$tmp/poll_test.o"
extra=(); [[ "$(uname -s)" == Linux ]] && extra=(-Wl,-z,noexecstack)
"$cc" ${flags[@]+"${flags[@]}"} "$here/test$suffix.c" "$tmp/tramp_c.o" "$tmp/tramp_s.o" "$tmp/poll_test.o" ${extra[@]+"${extra[@]}"} -o "$tmp/tt"
"$tmp/tt"
