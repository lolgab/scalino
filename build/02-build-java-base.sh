#!/usr/bin/env bash
# dotc needs java.lang/java.util classfile signatures to initialize its core
# symbol table (everything implicitly extends java.lang.Object). Normally it
# reads these from the jrt:/ modules filesystem, but native-image binaries
# have no real JDK install and no jrt:/ provider. Fix: extract java.base's
# classfiles into a plain jar and point dotc at it via -javabootclasspath.
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")"
source ./00-env.sh

OUT="$DIST/java.base.jar"
if [[ -f "$OUT" ]]; then
  echo "OK: $OUT already built"
  exit 0
fi

rm -rf "$WORK/jrt-extract"
"$JIMAGE" extract --dir="$WORK/jrt-extract" "$GRAAL_HOME/lib/modules"
(cd "$WORK/jrt-extract/java.base" && "$JAR" cf "$OUT" .)
echo "OK: $OUT"
