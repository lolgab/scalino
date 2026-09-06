#!/usr/bin/env bash
# Builds scalino-dotc: a standalone binary of dotc + the scala-native compiler
# plugin, no JVM required to run it.
#
# The plugin MUST be on native-image's own build classpath. Compiler plugins
# are loaded reflectively at runtime (Class.forName on the jar named by
# -Xplugin), and native-image is closed-world AOT: it cannot load a class
# that wasn't reachable at build time. So we bake nscplugin's classes into
# the same image as dotc, and just point -Xplugin at its jar at runtime to
# activate it (the classes are already resident).
#
# Also bakes in our patched dotty.tools.dotc.quoted.Interpreter (see
# 03a-patch-compiler.sh / vendor/scala3's Interpreter.scala): the
# JVM-reflection-free macro-execution path. compiler-patched.cp is identical
# to compiler.cp except scala3-compiler_3's jar is swapped for the patched one.
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")"
source ./00-env.sh

[[ -f "$WORK/compiler-patched.cp" ]] || { echo "run 03a-patch-compiler.sh first" >&2; exit 1; }

BUILD_CP="$(cat "$WORK/compiler-patched.cp"):$(cat "$WORK/nativelibs.cp"):$(cat "$WORK/nscplugin.cp")"

# -R:StackSize: our own-implementation Interpreter.scala (see
# 03a-patch-compiler.sh) walks a tree-shaped call stack directly, no
# JVM-reflection shortcut -- several native frames per one interpreted
# step. A heavily-nested `derives` expansion (many mutually-referencing
# case classes/enums all deriving the same typeclass) can blow GraalVM
# native-image's default ~8MB main-thread stack well before it'd trouble
# a real JVM; 64MB gives real-world derivation chains headroom.
"$NATIVE_IMAGE" \
  -cp "$BUILD_CP" \
  --no-fallback \
  -H:ConfigurationFileDirectories="$ROOT/agent-config/scalino-dotc" \
  -H:+ReportExceptionStackTraces \
  -R:StackSize=67108864 \
  -J--sun-misc-unsafe-memory-access=allow \
  -o "$DIST/scalino-dotc" \
  dotty.tools.dotc.Main

echo "OK: $DIST/scalino-dotc"
