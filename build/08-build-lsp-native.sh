#!/usr/bin/env bash
# Builds dotty-lsp-native: a standalone binary of dotty's own pre-Metals LSP
# server (vendor/scala3/language-server/, trimmed by
# patches/scala3-0002-trim-language-server.patch to drop the worksheet
# JVM-subprocess-REPL and TASTy-decompiler endpoints, neither of which core
# LSP needs). No JVM required to run it.
#
# Unlike dotc-native (native-image'd directly from published jars), this
# module isn't published anywhere -- it was dotty's IDE support before
# Metals existed and never shipped a Maven artifact. So we compile it
# ourselves first, JVM-side, the same way 03a-patch-compiler.sh compiles its
# single patched file: run the published (patched) scala3-compiler_3 jar's
# own dotc.Main on the JVM to produce classfiles, then native-image those.
#
# One extra step dotc-native didn't need: config/ProjectConfig.java is Java,
# and dotc (like scalac) only typechecks against .java sources, it doesn't
# compile them to bytecode -- so a real javac pass runs first, same as any
# sbt mixed-compilation project would do.
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")"
source ./00-env.sh

[[ -f "$WORK/compiler-patched.cp" ]] || { echo "run 03a-patch-compiler.sh first" >&2; exit 1; }
[[ -f "$WORK/lsp.cp" ]] || { echo "run 01-fetch-deps.sh first" >&2; exit 1; }

LSP_SRC="$ROOT/vendor/scala3/language-server/src/dotty/tools/languageserver"
[[ -f "$LSP_SRC/Main.scala" ]] || { echo "missing $LSP_SRC -- run 00b-setup-vendor.sh first" >&2; exit 1; }

JAVA_CLASSES="$WORK/lsp-java-classes"
SCALA_CLASSES="$WORK/lsp-classes"
rm -rf "$JAVA_CLASSES" "$SCALA_CLASSES"
mkdir -p "$JAVA_CLASSES" "$SCALA_CLASSES"

LSP_CP="$(cat "$WORK/lsp.cp")"
COMPILER_CP="$(cat "$WORK/compiler-patched.cp")"

echo "== javac: config/ProjectConfig.java =="
"$JAVAC" -cp "$LSP_CP" -d "$JAVA_CLASSES" "$LSP_SRC/config/ProjectConfig.java"

echo "== dotc (JVM mode): trimmed language-server sources =="
"$JAVA" -cp "$COMPILER_CP:$LSP_CP" dotty.tools.dotc.Main \
  -classpath "$COMPILER_CP:$LSP_CP:$JAVA_CLASSES" \
  -d "$SCALA_CLASSES" \
  "$LSP_SRC/Main.scala" "$LSP_SRC/DottyLanguageServer.scala" \
  "$LSP_SRC/DottyClient.scala" "$LSP_SRC/Memory.scala"

echo "== native-image =="
"$NATIVE_IMAGE" \
  -cp "$SCALA_CLASSES:$JAVA_CLASSES:$COMPILER_CP:$LSP_CP" \
  --no-fallback \
  -H:ConfigurationFileDirectories="$ROOT/agent-config/lsp" \
  -H:+ReportExceptionStackTraces \
  -o "$DIST/dotty-lsp-native" \
  dotty.tools.languageserver.Main

echo "OK: $DIST/dotty-lsp-native"
