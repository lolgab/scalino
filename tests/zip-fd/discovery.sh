#!/usr/bin/env bash
# Test discovery looks every superclass up in each classpath entry, resource directories included; opening a
# directory as a ZipFile used to leak a descriptor per lookup (patches/scala-native-0035, and f332aed in the CLI).
# This project has 400 test classes and a resource directory on the classpath. It runs `scalino test` with a
# descriptor limit far below the number of lookups and samples the CLI's open descriptors while it works.
#
# Usage: tests/zip-fd/discovery.sh [path-to-scalino]   (default: dist/scalino)
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
SCALINO="$(cd "$(dirname "${1:-$ROOT/dist/scalino}")" && pwd)/$(basename "${1:-$ROOT/dist/scalino}")"
WORK="$(mktemp -d)"; trap 'rm -rf "$WORK"' EXIT
fail() { echo "FAIL: $*" >&2; exit 1; }
CLASSES=400
LIMIT=300

mkdir "$WORK/proj" "$WORK/proj/resources" && cd "$WORK/proj"
echo "not a jar" > resources/data.txt
{
  echo '//> using test.dep org.scalameta::munit::1.0.4'
  echo '//> using resourceDir ./resources'
  echo 'package app'
  echo 'object Main { def main(args: Array[String]): Unit = () }'
} > Main.scala
mkdir test
for i in $(seq 1 $CLASSES); do
  printf 'package app\nclass T%s extends munit.FunSuite:\n  test("t%s")(assertEquals(1, 1))\n' "$i" "$i" > "test/T$i.scala"
done

# the build is cached and its descriptors are not what is measured: compile first, with the default limit
"$SCALINO" test -q > "$WORK/warmup.log" 2>&1 || { tail -20 "$WORK/warmup.log" >&2; fail "the project does not pass its tests"; }

# a process can exit between pgrep and the count: that is not a failure, so nothing here may fail the script
count_fds() {
  local n
  if [[ -d "/proc/$1/fd" ]]; then n="$(ls "/proc/$1/fd" 2>/dev/null | wc -l)" || true
  else n="$(lsof -p "$1" 2>/dev/null | wc -l)" || true
  fi
  echo "$(echo "${n:-0}" | tr -d ' ')"
}

rm -rf .scalino-build/test-probe-cache
peak=0
(
  ulimit -n "$LIMIT"
  "$SCALINO" test -q > "$WORK/test.log" 2>&1
) &
pid=$!
while kill -0 "$pid" 2>/dev/null; do
  for p in $(pgrep -P "$pid" 2>/dev/null) "$pid"; do
    n="$(count_fds "$p")"
    [[ "$n" -le "$peak" ]] || peak="$n"
  done
  sleep 0.2
done
wait "$pid" || { tail -20 "$WORK/test.log" >&2; fail "scalino test failed with the descriptor limit at $LIMIT (peak $peak open)"; }
[[ "$peak" -lt "$LIMIT" ]] || fail "descriptors grew to $peak"
echo "OK: test discovery over $CLASSES classes with a resource directory on the classpath peaked at $peak open descriptors (limit $LIMIT)"
