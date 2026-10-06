#!/usr/bin/env bash
# Unit test for the x86_64 GC safepoint trampoline (patch 0069), no Scala Native build needed.
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
case "$(uname -s)-$(uname -m)" in
  Linux-x86_64) ;;
  *) echo "skipped: the x86_64 trampoline test only runs on Linux x86_64"; exit 0 ;;
esac
tmp="$(mktemp -d)"; trap 'rm -rf "$tmp"' EXIT
flags=(-O1 -g -DSCALANATIVE_MULTITHREADING_ENABLED -DSCALANATIVE_GC_USE_YIELDPOINT_TRAPS -DSCALANATIVE_GC_IMMIX -I"$here/stubs" -I"$dir")
"$cc" "${flags[@]}" -c "$dir/SafepointPollTrampoline.c" -o "$tmp/tramp_c.o"
"$cc" "${flags[@]}" -c "$dir/SafepointPollTrampoline-x86_64.S" -o "$tmp/tramp_s.o"
"$cc" "${flags[@]}" -c "$here/poll_test.S" -o "$tmp/poll_test.o"
"$cc" "${flags[@]}" "$here/test.c" "$tmp/tramp_c.o" "$tmp/tramp_s.o" "$tmp/poll_test.o" -Wl,-z,noexecstack -o "$tmp/tt"
"$tmp/tt"
