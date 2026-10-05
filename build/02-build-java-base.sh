#!/usr/bin/env bash
# dotc needs java.lang/java.util classfile signatures to initialize its core
# symbol table (everything implicitly extends java.lang.Object). Normally it
# reads these from the jrt:/ modules filesystem, but our self-hosted Scala
# Native binaries have no real JDK install and no jrt:/ provider. Fix:
# extract java.base's classfiles into a plain jar and point dotc at it via
# -javabootclasspath.
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")"
source ./00-env.sh

OUT="$DIST/java.base.jar"

# java.base.jar is OpenJDK code (GPLv2 with the Classpath Exception): ship the
# licence texts the JDK itself provides for that module, including the notices
# for the third-party code inside it (ICU, zlib, ...). See NOTICE.
if [[ -d "$JAVA_HOME/legal/java.base" ]]; then
  rm -rf "$DIST/legal/java.base"
  mkdir -p "$DIST/legal"
  cp -R "$JAVA_HOME/legal/java.base" "$DIST/legal/java.base"
fi

if [[ -f "$OUT" ]]; then
  echo "OK: $OUT already built"
  exit 0
fi

rm -rf "$WORK/jrt-extract"
"$JIMAGE" extract --dir="$WORK/jrt-extract" "$JAVA_HOME/lib/modules"
(cd "$WORK/jrt-extract/java.base" && "$JAR" cf "$OUT" .)
echo "OK: $OUT"
