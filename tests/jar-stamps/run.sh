#!/usr/bin/env bash
# The caches that let a one-shot build skip work the previous build of the same directory already did
# (patches/scala-native-0021, 0022, 0023): `jarstamp` next to `jarhash` in each unpacked native library,
# `nativejars` (which classpath jars carry native sources) and `reachset` (the classes the last build loaded,
# parsed in parallel at the start of the next one) in the work directory.
#
# Usage: tests/jar-stamps/run.sh [path-to-scalino]   (default: dist/scalino)
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
SCALINO="$(cd "$(dirname "${1:-$ROOT/dist/scalino}")" && pwd)/$(basename "${1:-$ROOT/dist/scalino}")"
WORK="$(mktemp -d)"; CONTROL="$(mktemp -d)"; trap 'rm -rf "$WORK" "$CONTROL"' EXIT
fail() { echo "FAIL: $*" >&2; exit 1; }
cd "$WORK"
printf 'package app\n\n@main def main = println("stamps 1")\n' > main.scala
NATIVE=.scalino-build/app.main/link/native

echo "== first build writes the caches"
"$SCALINO" package . -o out -v > log1 2>&1 || { tail -5 log1 >&2; fail "first build"; }
[[ "$(./out)" == "stamps 1" ]] || fail "wrong output"
[[ -s $NATIVE/nativejars ]] || fail "no nativejars file"
with_native="$(awk -F'\t' '$3 == "1"' $NATIVE/nativejars | wc -l | tr -d ' ')"
[[ "$with_native" -ge 4 ]] || fail "expected the native library jars to be flagged as having native sources, got $with_native"
awk -F'\t' '$3 == "1" && $1 ~ /nativelib/ {found=1} END {exit !found}' $NATIVE/nativejars || fail "nativelib not flagged"
for dir in $NATIVE/dependencies/*/; do
  [[ -s $dir/jarhash && -s $dir/jarstamp ]] || fail "$dir has no jarhash/jarstamp"
done

echo "== a second build reuses them: same binary, no C file compiled again"
cp out out1
printf 'package app\n\n@main def main = println("stamps 1")\n' > main.scala; touch main.scala
"$SCALINO" package . -o out -v > log2 2>&1 || { tail -5 log2 >&2; fail "second build"; }
[[ "$(grep -c '^	-c$' log2)" -eq 0 ]] || fail "native libraries were compiled again ($(grep -c '^	-c$' log2) files)"

echo "== damaged cache files are only a cache: rebuilt, not trusted"
echo garbage > $NATIVE/nativejars
for dir in $NATIVE/dependencies/*/; do echo garbage > $dir/jarstamp; done
printf 'package app\n\n@main def main = println("stamps 2")\n' > main.scala
"$SCALINO" package . -o out -v > log3 2>&1 || { tail -5 log3 >&2; fail "build with damaged caches"; }
[[ "$(./out)" == "stamps 2" ]] || fail "wrong output after damaged caches"
[[ "$(grep -c '^	-c$' log3)" -eq 0 ]] || fail "damaged stamps made the unpacked libraries be compiled again: the hash should have confirmed them"
[[ "$(awk -F'\t' '$3 == "1"' $NATIVE/nativejars | wc -l | tr -d ' ')" -ge 4 ]] || fail "nativejars not rewritten"
for dir in $NATIVE/dependencies/*/; do [[ "$(cat $dir/jarstamp)" != garbage ]] || fail "$dir/jarstamp not rewritten"; done

echo "== reachset: written by a build, and only a hint"
[[ "$(wc -l < $NATIVE/reachset | tr -d ' ')" -ge 500 ]] || fail "reachset has too few classes: $(wc -l < $NATIVE/reachset)"
grep -qx 'java.lang.Object' $NATIVE/reachset || fail "java.lang.Object not in reachset"
n=0
for damage in 'garbage' '' 'no.such.Class\njava.lang.Object\nanother.Missing$\n' 'ok'; do
  n=$((n + 1))
  printf 'package app\n\n@main def main = println("hint %s")\n' "$n" > main.scala   # new sources: the build is not skipped
  if [[ "$damage" != ok ]]; then printf "$damage" > $NATIVE/reachset; fi
  "$SCALINO" package . -o with-hint -q > build.log 2>&1 || { tail -8 build.log >&2; fail "build with reachset '$damage'"; }
  [[ "$(./with-hint)" == "hint $n" ]] || fail "wrong output with reachset '$damage'"
  [[ "$(wc -l < $NATIVE/reachset | tr -d ' ')" -ge 500 ]] || fail "reachset not rewritten after '$damage'"
  # the same sources from scratch with the hint off: the hint must not change the binary
  rm -rf "$CONTROL"/* "$CONTROL"/.scalino-build && cp main.scala "$CONTROL"/
  (cd "$CONTROL" && SCALINO_REACH_PREFETCH=0 "$SCALINO" package . -o without-hint -q > /dev/null 2>&1) || fail "control build"
  cmp -s with-hint "$CONTROL/without-hint" || fail "reachset '$damage' changes the binary"
done
echo "OK"
