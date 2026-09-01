#!/usr/bin/env bash
# Resolves and caches every jar the toolchain needs. Writes classpath files
# under .build-work/*.cp so later steps don't re-resolve.
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")"
source ./00-env.sh

echo "== dotc compiler classpath =="
cs fetch "org.scala-lang:scala3-compiler_3:$SCALA_VERSION" "org.scala-lang:scala3-library_3:$SCALA_VERSION" \
  --classpath > "$WORK/compiler.cp"

echo "== scala-native compiler plugin =="
cs fetch "org.scala-native:nscplugin_$SCALA_VERSION:$SCALA_NATIVE_VERSION" \
  --classpath > "$WORK/nscplugin.cp"
# jar path alone, for -Xplugin:
tr ':' '\n' < "$WORK/nscplugin.cp" | grep "nscplugin_$SCALA_VERSION" > "$WORK/nscplugin.jar.txt"

echo "== scala-native target runtime libs (java.base-for-native, stdlib port, etc) =="
# NOTE: scalalib_native0.5_3 is an empty/decoy artifact for this version line.
# The real Scala stdlib port lives in scala3lib_native0.5_3. Do not add
# scalalib_native0.5_3 here -- see docs/findings.md.
cs fetch \
  "org.scala-native:nativelib_native0.5_3:$SCALA_NATIVE_VERSION" \
  "org.scala-native:javalib_native0.5_3:$SCALA_NATIVE_VERSION" \
  "org.scala-native:auxlib_native0.5_3:$SCALA_NATIVE_VERSION" \
  "org.scala-native:posixlib_native0.5_3:$SCALA_NATIVE_VERSION" \
  "org.scala-native:clib_native0.5_3:$SCALA_NATIVE_VERSION" \
  "org.scala-native:scala3lib_native0.5_3:$SN_COMBINED_VERSION" \
  --classpath > "$WORK/nativelibs.cp"

echo "== scala-native JVM-side build/link tool (tools_3, NOT tools_native0.5_3) =="
# tools_native0.5_3 is the self-hosted variant meant to run AS a native binary;
# it uses link-time intrinsics that throw UndefinedBehaviorError on a plain JVM.
cs fetch "org.scala-native:tools_3:$SCALA_NATIVE_VERSION" --classpath > "$WORK/tools.cp"

echo "OK: classpaths written to $WORK/*.cp"
