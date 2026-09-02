#!/usr/bin/env bash
# Re-traces reflection/JNI/resource usage via the native-image agent on a
# real JVM run, for both binaries. Run this after bumping versions.env or
# after any change that might touch a new reflective code path (e.g. a new
# compiler flag, a different plugin option). Overwrites agent-config/*.
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")"
source ./00-env.sh

[[ -f "$WORK/compiler-patched.cp" ]] || { echo "run 03a-patch-compiler.sh first" >&2; exit 1; }
[[ -f "$DIST/java.base.jar" ]] || { echo "run 02-build-java-base.sh first" >&2; exit 1; }

FULL_CP="$(cat "$WORK/compiler-patched.cp"):$(cat "$WORK/nativelibs.cp")"
PLUGIN_JAR="$(cat "$WORK/nscplugin.jar.txt")"

# Traced against a real macro (not just a macro-free hello-world) so the
# reflection/JNI reachability capture also covers whatever the macro
# interpreter's own code paths touch (Symbol.defTree walking, QuotesImpl,
# ExprImpl, ...), not just plain compilation.
FIXTURE="$ROOT/interpreter/test-fixtures/macros-in-same-project1"

echo "== tracing dotc + nscplugin (with a real macro) =="
rm -rf "$ROOT/agent-config/dotc" "$WORK/agent-out"
mkdir -p "$WORK/agent-out" "$WORK/agent-compile-out"
"$JAVA" -agentlib:native-image-agent=config-output-dir="$WORK/agent-out" \
  -cp "$FULL_CP:$PLUGIN_JAR" dotty.tools.dotc.Main \
  -javabootclasspath "$DIST/java.base.jar" -classpath "$FULL_CP" \
  -Xplugin:"$PLUGIN_JAR" -Xplugin-require:scalanative \
  -Yretain-trees \
  -d "$WORK/agent-compile-out" "$FIXTURE/Foo.scala" "$FIXTURE/Test.scala"
mkdir -p "$ROOT/agent-config/dotc"
cp "$WORK/agent-out/reachability-metadata.json" "$ROOT/agent-config/dotc/"

echo "== tracing linkdriver =="
DRIVER_CP="$(cat "$WORK/compiler.cp"):$(cat "$WORK/tools.cp"):$WORK/driver-classes"
mkdir -p "$WORK/driver-classes"
"$JAVA" -cp "$DRIVER_CP" dotty.tools.dotc.Main -classpath "$DRIVER_CP" -d "$WORK/driver-classes" "$ROOT/src/LinkDriver.scala"

NATIVELIBS="$(cat "$WORK/nativelibs.cp")"
rm -rf "$ROOT/agent-config/linkdriver" "$WORK/agent-out2" "$WORK/agent-link-out"
mkdir -p "$WORK/agent-out2" "$WORK/agent-link-out"
"$JAVA" -agentlib:native-image-agent=config-output-dir="$WORK/agent-out2" \
  -cp "$DRIVER_CP" LinkDriver "$WORK/agent-compile-out:$NATIVELIBS" "$WORK/agent-link-out" Test "$CLANG" "$CLANGPP"
mkdir -p "$ROOT/agent-config/linkdriver"
cp "$WORK/agent-out2/reachability-metadata.json" "$ROOT/agent-config/linkdriver/"

echo "OK: agent-config refreshed, review with git diff before committing"
