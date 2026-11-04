#!/usr/bin/env bash
# Resolves and caches every jar the toolchain needs. Writes classpath files
# under .build-work/*.cp so later steps don't re-resolve.
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")"
source ./00-env.sh

echo "== dotc compiler classpath =="
# `| tr -d '\r'` on every `cs fetch --classpath` below: on Windows cs prints "\r\n", leaving a
# trailing \r on the last entry that byte-strict readers (cp in 06-package.sh's vendor_cp)
# choke on ("cannot stat '...jar'$'\r''").
cs fetch "org.scala-lang:scala3-compiler_3:$SCALA_VERSION" "org.scala-lang:scala3-library_3:$SCALA_VERSION" \
  --classpath | tr -d '\r' > "$WORK/compiler.cp"

# Everything below resolves org.scala-native:* from ~/.ivy2/local: build/01a-publish-scala-native.sh
# built it from vendor/scala-native (upstream main + patches/), nothing here is a released jar.
echo "== scala-native compiler plugin =="
cs fetch -r ivy2Local "org.scala-native:nscplugin_$SCALA_VERSION:$SCALA_NATIVE_VERSION" \
  --classpath | tr -d '\r' > "$WORK/nscplugin.cp"
# jar path alone, for -Xplugin:
tr "$CP_SEP" '\n' < "$WORK/nscplugin.cp" | grep "nscplugin_$SCALA_VERSION" > "$WORK/nscplugin.jar.txt"

echo "== scala-native target runtime libs (java.base-for-native, stdlib port, etc) =="
# scalalib_native0.5_3 is an empty decoy for this version line; the real stdlib port is
# scala3lib_native0.5_3 (see docs/findings.md).
cs fetch -r ivy2Local \
  "org.scala-native:nativelib_native0.5_3:$SCALA_NATIVE_VERSION" \
  "org.scala-native:javalib_native0.5_3:$SCALA_NATIVE_VERSION" \
  "org.scala-native:auxlib_native0.5_3:$SCALA_NATIVE_VERSION" \
  "org.scala-native:posixlib_native0.5_3:$SCALA_NATIVE_VERSION" \
  "org.scala-native:clib_native0.5_3:$SCALA_NATIVE_VERSION" \
  "org.scala-native:scala3lib_native0.5_3:$SN_COMBINED_VERSION" \
  --classpath | tr -d '\r' > "$WORK/nativelibs.cp"

echo "== scala-native JVM-side build/link tool (tools_3, NOT tools_native0.5_3) =="
# tools_native0.5_3 is the self-hosted variant meant to run AS a native binary;
# it uses link-time intrinsics that throw UndefinedBehaviorError on a plain JVM.
cs fetch -r ivy2Local "org.scala-native:tools_3:$SCALA_NATIVE_VERSION" --classpath | tr -d '\r' > "$WORK/tools.cp"

echo "== scala-native Native-side build/link tool (tools_native0.5_3) =="
# it includes the classpath for nativelibs since in the scalino-linkdriver we need a classpath with both
# tools and nativelibs and if we merge two separate classpaths we end up with duplicates.
cs fetch -r ivy2Local \
  "org.scala-native:nativelib_native0.5_3:$SCALA_NATIVE_VERSION" \
  "org.scala-native:javalib_native0.5_3:$SCALA_NATIVE_VERSION" \
  "org.scala-native:auxlib_native0.5_3:$SCALA_NATIVE_VERSION" \
  "org.scala-native:posixlib_native0.5_3:$SCALA_NATIVE_VERSION" \
  "org.scala-native:clib_native0.5_3:$SCALA_NATIVE_VERSION" \
  "org.scala-native:scala3lib_native0.5_3:$SN_COMBINED_VERSION" \
  "org.scala-native:tools_native0.5_3:$SCALA_NATIVE_VERSION" --classpath | tr -d '\r' > "$WORK/tools-native.cp"

# The locally published scala3lib's ivy descriptor lists its dependencies' sources jars as ordinary
# artifacts, which a Maven-resolved release did not: they don't belong on a compile/link classpath.
for cp in nativelibs.cp tools-native.cp; do
  tr "$CP_SEP" '\n' < "$WORK/$cp" | grep -v -- '-sources\.jar$' | paste -sd"$CP_SEP" - > "$WORK/$cp.tmp"
  mv "$WORK/$cp.tmp" "$WORK/$cp"
done

echo "== LSP server deps (hand-rolled JSON-RPC + jsoniter-scala, no lsp4j/Gson) =="
# Not lsp4j + Gson: Gson's reflective TypeAdapter construction never completes in the
# AOT-compiled binary on a real editor's `initialize` payload (fine on a JVM; see
# docs/findings.md "JVM-free language server (LSP)"). dotty.tools.languageserver implements
# JSON-RPC by hand (Main.scala) with hand-written jsoniter-scala codecs, avoiding
# JsonCodecMaker, since the macro interpreter (patches/scala3-0001) may not expand arbitrary
# third-party macros.
cs fetch "com.github.plokhotnyuk.jsoniter-scala:jsoniter-scala-core_3:2.37.3" \
  --classpath | tr -d '\r' > "$WORK/lsp.cp"

echo "== scala-native target runtime libs sources (from the local Scala Native build) =="
cs fetch -r ivy2Local \
  "org.scala-native:nativelib_native0.5_3:$SCALA_NATIVE_VERSION" \
  "org.scala-native:javalib_native0.5_3:$SCALA_NATIVE_VERSION" \
  "org.scala-native:auxlib_native0.5_3:$SCALA_NATIVE_VERSION" \
  "org.scala-native:posixlib_native0.5_3:$SCALA_NATIVE_VERSION" \
  "org.scala-native:clib_native0.5_3:$SCALA_NATIVE_VERSION" \
  "org.scala-native:scala3lib_native0.5_3:$SN_COMBINED_VERSION" \
  --classifier sources --classpath 2>/dev/null | tr -d '\r' > "$WORK/nativelibs-sources.cp" \
  || : > "$WORK/nativelibs-sources.cp"
