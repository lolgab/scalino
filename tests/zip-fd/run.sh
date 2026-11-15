#!/usr/bin/env bash
# javalib file-descriptor leaks (patches/scala-native-0035): ZipFile constructors that throw and walkFileTree
# stopped early. Main.scala counts /dev/fd entries around 20000 iterations of each, then spawns a process.
# The second half checks the CLI path that hit it: test discovery looks classes up in every classpath entry,
# resource directories included (tests/zip-fd/discovery.sh).
#
# Usage: tests/zip-fd/run.sh [path-to-scalino]   (default: dist/scalino)
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
SCALINO="$(cd "$(dirname "${1:-$ROOT/dist/scalino}")" && pwd)/$(basename "${1:-$ROOT/dist/scalino}")"
WORK="$(mktemp -d)"; trap 'rm -rf "$WORK"' EXIT
fail() { echo "FAIL: $*" >&2; exit 1; }

mkdir "$WORK/src" "$WORK/data"
cp "$ROOT/tests/zip-fd/Main.scala" "$WORK/src/"
cd "$WORK/src"
"$SCALINO" package . -o check -q 2>&1 | grep -v '^$' || true
[[ -x check ]] || fail "the check program did not build"
./check "$WORK/data"

"$ROOT/tests/zip-fd/discovery.sh" "$SCALINO"
