#!/usr/bin/env bash
# Applies our patch to the vendored scala3 source and produces a "patched"
# scala3-compiler jar: the published scala3-compiler_3 jar with
# dotty/tools/dotc/quoted/{Interpreter,ModuleValue,InterpretedInstance,
# InterpretedVar,LocalDef,LabeledReturn,NonFatalInterpretedException}* and
# dotty/tools/dotc/transform/Splicer* classes overlaid by our own-implementation,
# JVM-free rewrite (see vendor/scala3/compiler/src/dotty/tools/dotc/quoted/Interpreter.scala,
# .../transform/Splicer.scala, and docs/findings.md "Macro execution").
#
# This is a source-level patch (`git diff` against the vendored scala3 clone
# is the actual patch), but NOT a full dotty sbt rebuild -- we only recompile
# the two changed files (together, so Splicer.scala's `SpliceInterpreter
# extends Interpreter` sees our patched Interpreter from source rather than
# the original class on the classpath) against the published, unmodified
# scala3-compiler_3 jar and splice the result in. This works because the
# change is confined to files with no other dependents that also need
# patching. A change spanning further compiler files would need the full sbt
# bootstrap build instead; see docs/findings.md "Remaining work".
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")"
source ./00-env.sh

VENDOR="$ROOT/vendor/scala3"
[[ -f "$VENDOR/compiler/src/dotty/tools/dotc/quoted/Interpreter.scala" ]] || {
  echo "missing $VENDOR -- clone scala/scala3 there first (see docs/findings.md)" >&2
  exit 1
}
[[ -f "$WORK/compiler.cp" ]] || { echo "run 01-fetch-deps.sh first" >&2; exit 1; }

ORIG_JAR="$(tr ':' '\n' < "$WORK/compiler.cp" | grep "scala3-compiler_3-$SCALA_VERSION.jar$")"
[[ -n "$ORIG_JAR" ]] || { echo "could not find scala3-compiler_3-$SCALA_VERSION.jar on compiler.cp" >&2; exit 1; }

PATCHED_DIR="$WORK/patched-interp-classes"
PATCHED_JAR="$DIST/scala3-compiler-patched.jar"

rm -rf "$PATCHED_DIR"
mkdir -p "$PATCHED_DIR"
"$JAVA" -cp "$(cat "$WORK/compiler.cp")" dotty.tools.dotc.Main \
  -classpath "$(cat "$WORK/compiler.cp")" \
  -d "$PATCHED_DIR" \
  "$VENDOR/compiler/src/dotty/tools/dotc/quoted/Interpreter.scala" \
  "$VENDOR/compiler/src/dotty/tools/dotc/transform/Splicer.scala"

cp "$ORIG_JAR" "$PATCHED_JAR"
(cd "$PATCHED_DIR" && "$JAR" uf "$PATCHED_JAR" $(find dotty -type f))

# compiler.cp, but with the original scala3-compiler_3 jar swapped for the
# patched one -- this is what every later step should compile/link against.
sed "s#$ORIG_JAR#$PATCHED_JAR#" "$WORK/compiler.cp" > "$WORK/compiler-patched.cp"

echo "OK: $PATCHED_JAR"
