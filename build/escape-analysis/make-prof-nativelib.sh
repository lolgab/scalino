#!/usr/bin/env bash
# Builds a copy of the locally built nativelib jar whose GC counts allocations:
# SCALINO_ALLOC_PROFILE_FILE=<file> makes the program dump, at exit, its top
# allocated classes (count, bytes, name), and SCALINO_SITE_FILE=<file> the
# per-site hit counters of a link done with SCALANATIVE_SITE_PROFILE=<table>.
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")/../.."
source build/00-env.sh >/dev/null 2>&1
EA_WORK=${EA_WORK:-$WORK/ea-dev}
NLJAR="$HOME/.ivy2/local/org.scala-native/nativelib_native0.5_3/$SCALA_NATIVE_VERSION-SNAPSHOT/jars/nativelib_native0.5_3.jar"
PATCH="$PWD/build/escape-analysis/gc-alloc-profile.patch"
GCDIR="$EA_WORK/gc/scala-native/gc/immix"
rm -rf "$EA_WORK/gc"; mkdir -p "$GCDIR"
cp vendor/scala-native/nativelib/src/main/resources/scala-native/gc/immix/ImmixGC.c "$GCDIR/ImmixGC.c"
(cd "$GCDIR" && patch -s -p1 < "$PATCH")
cp "$NLJAR" "$EA_WORK/nativelib-prof.jar"
(cd "$EA_WORK/gc" && "$JAR" uf "$EA_WORK/nativelib-prof.jar" scala-native/gc/immix/ImmixGC.c)
echo "OK: $EA_WORK/nativelib-prof.jar"
