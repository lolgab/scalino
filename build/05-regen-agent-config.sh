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

# Second pass, MERGED into the same config dir (`config-merge-dir`, not
# `config-output-dir` -- the latter would overwrite the first pass' trace
# instead of adding to it): `examples/interpreter-regressions/{Foo,Test}
# .scala`, which exercises this interpreter's OWN reflective field
# read/write (`reflectiveFieldRead`/`reflectiveFieldWrite` in
# `Interpreter.scala`, used whenever a real host object's uncurated method
# reads/writes its own private state, e.g. `ArrayBuffer#update` reading
# `size0`/writing `mutationCount`) -- a code path the first fixture never
# touches. Can't just add these files to the FIRST invocation's arg list:
# both fixtures independently declare top-level `object Foo`/`object Test`
# with no package, so compiling them together is a duplicate-definition
# error. Native-image's closed-world reflection needs every class/field
# actually reflected on traced at agent time or the same "silently falls
# back to old, wrong behavior" class of bug this docstring's own project
# has hit repeatedly (see docs/findings.md) bites again -- confirmed via a
# real regression: this exact gap (untraced `ArrayBuffer` fields) is what
# broke `scalino test .` against `~/scala/ape`'s `uri"..."` literal macro
# under `scalino-dotc` even after the underlying interpreter bug itself was
# fixed and verified working via the (unrestricted-reflection) JVM.
REGRESSIONS_FIXTURE="$ROOT/examples/interpreter-regressions"
mkdir -p "$WORK/agent-compile-out2"
"$JAVA" -agentlib:native-image-agent=config-merge-dir="$WORK/agent-out" \
  -cp "$FULL_CP:$PLUGIN_JAR" dotty.tools.dotc.Main \
  -javabootclasspath "$DIST/java.base.jar" -classpath "$FULL_CP" \
  -Xplugin:"$PLUGIN_JAR" -Xplugin-require:scalanative \
  -Yretain-trees \
  -d "$WORK/agent-compile-out2" "$REGRESSIONS_FIXTURE/Foo.scala" "$REGRESSIONS_FIXTURE/Test.scala"
mkdir -p "$ROOT/agent-config/dotc"
cp "$WORK/agent-out/reachability-metadata.json" "$ROOT/agent-config/dotc/"

echo "== tracing linkdriver =="
[[ -f "$WORK/tools-patched.cp" ]] || { echo "run 04a-patch-tools.sh first" >&2; exit 1; }
DRIVER_CP="$(cat "$WORK/compiler.cp"):$(cat "$WORK/tools-patched.cp"):$WORK/driver-classes"
mkdir -p "$WORK/driver-classes"
"$JAVA" -cp "$DRIVER_CP" dotty.tools.dotc.Main -classpath "$DRIVER_CP" -d "$WORK/driver-classes" "$ROOT/src/LinkDriver.scala"

NATIVELIBS="$(cat "$WORK/nativelibs.cp")"
rm -rf "$ROOT/agent-config/linkdriver" "$WORK/agent-out2" "$WORK/agent-link-out"
mkdir -p "$WORK/agent-out2" "$WORK/agent-link-out"
"$JAVA" -agentlib:native-image-agent=config-output-dir="$WORK/agent-out2" \
  -cp "$DRIVER_CP" LinkDriver "$WORK/agent-compile-out:$NATIVELIBS" "$WORK/agent-link-out" Test "$CLANG" "$CLANGPP"
mkdir -p "$ROOT/agent-config/linkdriver"
cp "$WORK/agent-out2/reachability-metadata.json" "$ROOT/agent-config/linkdriver/"

# No more "tracing scalino-lsp" step here: that module (Main.scala,
# Lsp.scala) no longer uses lsp4j/Gson at all -- it's a hand-rolled
# JSON-RPC/LSP implementation over hand-written jsoniter-scala codecs, with
# zero runtime reflection, after discovering a GraalVM native-image-specific
# pathology where Gson's reflective TypeAdapter construction for a real
# editor's full `initialize` payload silently never completed under
# native-image (see docs/findings.md "JVM-free language server (LSP)").
# build/08-build-scalino-lsp.sh's own native-image invocation no longer
# passes `-H:ConfigurationFileDirectories` at all -- add a real trace step
# back here only if a genuine MissingReflectionRegistrationError ever shows
# up at runtime again.

echo "OK: agent-config refreshed, review with git diff before committing"
