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

echo "== tracing dotty-lsp-native (real editor-shaped LSP session) =="
# Traced against a realistic client session, not just hover/diagnostics on a
# hand-written fixture: a real editor's `initialize` always sends
# `workspaceFolders`, and a real session eventually calls `workspace/symbol`
# -- both deserialize into lsp4j POJOs (org.eclipse.lsp4j.WorkspaceFolder,
# WorkspaceSymbolParams) that native-image's closed-world reflection needs
# to see reachability-traced, or Gson's reflective construction of them
# fails at runtime with "was never registered" (silent to the client --
# only visible in the server's own stderr). See docs/findings.md "JVM-free
# language server (LSP)" for the bug this step exists to prevent regressing.
[[ -f "$WORK/lsp.cp" ]] || { echo "run 01-fetch-deps.sh first" >&2; exit 1; }
[[ -d "$WORK/lsp-classes" && -d "$WORK/lsp-java-classes" ]] || { echo "run 08-build-lsp-native.sh first (needs its compiled classes)" >&2; exit 1; }
[[ -x "$DIST/sn-cli" ]] || { echo "run 07-build-sn-cli.sh first (needed to generate the fixture's .dotty-ide.json)" >&2; exit 1; }
LSP_CP="$(cat "$WORK/lsp.cp")"
LSP_COMPILE_CP="$(cat "$WORK/compiler-patched.cp")"
LSP_FIXTURE="$ROOT/build/lsp-trace-fixture"
( cd "$LSP_FIXTURE" && rm -f .dotty-ide.json && "$DIST/sn-cli" setup-ide Model.scala Greeter.scala Main.scala >/dev/null )
rm -rf "$ROOT/agent-config/lsp" "$WORK/lsp-agent-out"
mkdir -p "$WORK/lsp-agent-out"
python3 "$ROOT/build/lsp-trace-drive.py" "$LSP_FIXTURE" \
  "$JAVA" -agentlib:native-image-agent=config-output-dir="$WORK/lsp-agent-out" \
  -cp "$WORK/lsp-classes:$WORK/lsp-java-classes:$LSP_COMPILE_CP:$LSP_CP" \
  dotty.tools.languageserver.Main -stdio
mkdir -p "$ROOT/agent-config/lsp"
cp "$WORK/lsp-agent-out/reachability-metadata.json" "$ROOT/agent-config/lsp/"
rm -rf "$LSP_FIXTURE/.dotty-ide.json" "$LSP_FIXTURE/.sn-cli-build"

echo "OK: agent-config refreshed, review with git diff before committing"
