#!/usr/bin/env bash
# SHA-1 of tools/jslink/SHA1.scala (the pure Scala digest the Scala.js linker and compiler use, since javalib has no
# MessageDigest) against `shasum -a 1`: files of every length around the 64-byte block and the 56-byte padding
# boundary, bigger ones, and the toolchain's own jars, each fed in nine different ways (see Main.scala).
#
# Usage: tests/sha1/run.sh [path-to-scalino]   (default: dist/scalino)
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
SCALINO="$(cd "$(dirname "${1:-$ROOT/dist/scalino}")" && pwd)/$(basename "${1:-$ROOT/dist/scalino}")"
WORK="$(mktemp -d)"; trap 'rm -rf "$WORK"' EXIT
fail() { echo "FAIL: $*" >&2; exit 1; }

mkdir "$WORK/src" "$WORK/files"
cp "$ROOT/tools/jslink/SHA1.scala" "$ROOT/tests/sha1/Main.scala" "$ROOT/tests/sha1/UTF8String.scala" "$WORK/src/"
for len in $(seq 0 200) 255 256 1000 4095 4096 4097 65535 65536 65537 1000003; do
  if [[ $len -eq 0 ]]; then : > "$WORK/files/r0"; else head -c "$len" /dev/urandom > "$WORK/files/r$len"; fi
done
for jar in "$ROOT"/dist/lib/clib_native0.5_3.jar "$ROOT"/dist/lib/nativelib_native0.5_3.jar; do
  [[ -f "$jar" ]] && cp "$jar" "$WORK/files/"
done

cd "$WORK/src"
"$SCALINO" package . -o check -q 2>&1 | grep -v '^$' || true
[[ -x check ]] || fail "the check program did not build"

shopt -s nullglob
./check "$WORK"/files/* | sort -k2 > "$WORK/got"
( cd "$WORK/files" && shasum -a 1 * | awk '{print $1, "'"$WORK"'/files/" $2}' | sort -k2 ) > "$WORK/want"
grep -q INCONSISTENT "$WORK/got" && { grep INCONSISTENT "$WORK/got" | head -3 >&2; fail "the nine ways of feeding the digest disagree"; }
diff "$WORK/got" "$WORK/want" > /dev/null || { diff "$WORK/got" "$WORK/want" | head -5 >&2; fail "differs from shasum"; }
echo "OK: $(wc -l < "$WORK/got" | tr -d ' ') files, every one equal to shasum -a 1 in all nine ways of feeding the digest"
