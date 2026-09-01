#!/usr/bin/env bash
# Copies the classpath manifests bin/snc needs at runtime into dist/, so dist/
# is usable without .build-work sticking around. NOTE: entries in these
# manifests are still absolute paths into the coursier cache
# (~/.cache/coursier or ~/Library/Caches/Coursier) -- this is NOT yet a
# fully relocatable/vendored distribution. See docs/findings.md "Packaging"
# for the follow-up (vendor jars into dist/lib instead of referencing cache).
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")"
source ./00-env.sh

cp "$WORK/compiler.cp" "$DIST/compiler.cp"
cp "$WORK/nativelibs.cp" "$DIST/nativelibs.cp"
cp "$WORK/nscplugin.jar.txt" "$DIST/nscplugin.jar.txt"

echo "OK: $DIST is ready. Try: bin/snc build spike/Hello.scala -o /tmp/hello"
