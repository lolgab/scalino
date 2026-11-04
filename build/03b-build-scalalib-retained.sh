#!/usr/bin/env bash
# Recompiles scala-library (Scala 2.13, the version scala-native's `scalalib_native0.5_2.13`
# pins; see nativelibs.cp) from source with -Yretain-trees into dist/lib/scalalib-retained.jar.
#
# Why: the published scala-library is scalac-compiled and has no .tasty. The macro interpreter
# (Interpreter.scala's resolveExternalDefTree) can only run a stdlib method's real body if a
# .tasty exists to unpickle; otherwise every List/Option/Seq method a macro calls needs a
# hand-written intrinsic (docs/findings.md). Compiling with dotc produces ordinary TASTy that
# `rootTreeContaining` can unpickle on any later run.
#
# cli/ScalinoCli.scala puts this jar first on every project's compile classpath, ahead of the
# plain scala-library from nativelibs.cp.
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")"
source ./00-env.sh

[[ -x "$DIST/scalino-dotc" ]] || { echo "run 03-build-scalino-dotc.sh first" >&2; exit 1; }
[[ -f "$WORK/nativelibs.cp" ]] || { echo "run 01-fetch-deps.sh first" >&2; exit 1; }

VENDOR="$ROOT/vendor/scala3"

# Since 3.8.x the published scala-library jar already ships per-class .tasty and the
# scala2-library-bootstrapped source tree is gone, so this step is unnecessary and can't find
# its sources: skip it.
if [[ ! -d "$VENDOR/scala2-library-bootstrapped/src" ]]; then
  echo "skipping: vendor/scala3 has no scala2-library-bootstrapped (removed as of Scala's unified 2.13/3.x versioning, 3.8.x+) -- the published scala-library jar already ships real .tasty for this SCALA_VERSION, so scalalib-retained.jar is unnecessary" >&2
  exit 0
fi

STDLIB_JAR="$(tr "$CP_SEP" '\n' < "$WORK/nativelibs.cp" | grep -E '/scala-library-[0-9.]+\.jar$')"
[[ -n "$STDLIB_JAR" ]] || { echo "could not find scala-library jar on nativelibs.cp" >&2; exit 1; }
STDLIB_VERSION="$(basename "$STDLIB_JAR" .jar | sed 's/^scala-library-//')"

SRC_DIR="$WORK/scalalib-retained-src"
CLASSES_DIR="$WORK/scalalib-retained-classes"
rm -rf "$SRC_DIR" "$CLASSES_DIR"
mkdir -p "$SRC_DIR" "$CLASSES_DIR" "$DIST/lib"

echo "fetching scala-library $STDLIB_VERSION sources..."
SOURCES_JAR="$(cs fetch --classifier sources "org.scala-lang:scala-library:$STDLIB_VERSION")"
(cd "$SRC_DIR" && "$JAR" xf "$SOURCES_JAR")

# Stubs for types dotc treats as compiler builtins (defined by vendor/scala3/library/src);
# never compile them from plain scala-library source.
for stub in Any AnyVal AnyRef Nothing Null Singleton; do
  rm -f "$SRC_DIR/scala/$stub.scala"
done

# scala2-library-bootstrapped/src holds dotc-specific overrides of plain scala-library files;
# copy them all, preserving relative paths.
(cd "$VENDOR/scala2-library-bootstrapped/src" && find . -name '*.scala' -print0) |
  while IFS= read -r -d '' relpath; do
    mkdir -p "$SRC_DIR/$(dirname "$relpath")"
    cp -f "$VENDOR/scala2-library-bootstrapped/src/$relpath" "$SRC_DIR/$relpath"
  done

echo "compiling with dist/scalino-dotc (the real compiler scalino itself runs)..."
# -javabootclasspath: scalino-dotc has no JDK rt.jar/module path, and without it
# `Definitions#init` can't resolve java.lang.Object. Same flag scalino always passes.
"$DIST/scalino-dotc" \
  -javabootclasspath "$DIST/java.base.jar" \
  -classpath "$(cat "$WORK/compiler.cp")" \
  -Ycompile-scala2-library -Yscala2-unpickler:never -Yretain-trees -Werror:false \
  -d "$CLASSES_DIR" \
  $(find "$SRC_DIR" -name '*.scala')

echo "merging in the plain jar's Java-sourced classes (scala.runtime.* etc, never compiled above)..."
# dotc can't compile the .java sources (scala.runtime.Statics, BoxesRunTime, ...), so merge the plain
# jar's classes for anything missing. The result must be a self-contained replacement: two jars
# providing scala.Option make dotc's classpath resolution non-deterministic
# (cli/ScalinoCli.scala's dropPlainScalaLibrary).
PLAIN_DIR="$WORK/scalalib-plain-classes"
rm -rf "$PLAIN_DIR"
mkdir -p "$PLAIN_DIR"
(cd "$PLAIN_DIR" && "$JAR" xf "$STDLIB_JAR")
(cd "$PLAIN_DIR" && find . -type f -print0) |
  while IFS= read -r -d '' relpath; do
    [[ -f "$CLASSES_DIR/$relpath" ]] || {
      mkdir -p "$CLASSES_DIR/$(dirname "$relpath")"
      cp -f "$PLAIN_DIR/$relpath" "$CLASSES_DIR/$relpath"
    }
  done

(cd "$CLASSES_DIR" && "$JAR" cf "$DIST/lib/scalalib-retained.jar" .)
echo "OK: $DIST/lib/scalalib-retained.jar"
