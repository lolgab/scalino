#!/usr/bin/env bash
# Recompiles the real scala-library (Scala 2.13, the same version pinned by
# scala-native's own `scalalib_native0.5_2.13` -- see nativelibs.cp) from its
# published sources with -Yretain-trees, producing dist/lib/scalalib-retained.jar.
#
# Why: the published org.scala-lang:scala-library jar is scalac-compiled --
# it has zero .tasty, ever, for any Scala version, since scalac doesn't
# produce TASTy at all. Our own-implementation macro interpreter
# (Interpreter.scala's resolveExternalDefTree) can only run a stdlib method's
# *real* body if a .tasty file exists to unpickle it from; without this,
# every List/Option/Seq/etc method a macro's own code happens to call needs
# its own hand-written intrinsic, which doesn't scale (see docs/findings.md).
# Recompiling the exact same source with dotc instead of scalac produces
# ordinary TASTy (with real, complete method bodies -- -Yretain-trees only
# matters for the *current* run's own symbols, per Symbols.scala; it's
# irrelevant to what ends up pickled in the .tasty this step produces) that
# `rootTreeContaining` can unpickle from cold, on any later compiler run,
# turning almost all of that hand-written-intrinsic surface into generically
# interpreted real library code instead.
#
# scli (cli/Scli.scala) puts dist/lib/scalalib-retained.jar first on every
# project's compile classpath, ahead of the plain scala-library from
# nativelibs.cp, so this benefits every project automatically -- not just
# ones we've manually special-cased.
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")"
source ./00-env.sh

[[ -x "$DIST/dotc-native" ]] || { echo "run 03-build-dotc-native.sh first" >&2; exit 1; }
[[ -f "$WORK/nativelibs.cp" ]] || { echo "run 01-fetch-deps.sh first" >&2; exit 1; }

VENDOR="$ROOT/vendor/scala3"
[[ -d "$VENDOR/scala2-library-bootstrapped/src" ]] || {
  echo "missing $VENDOR -- clone scala/scala3 there first (see docs/findings.md)" >&2
  exit 1
}

STDLIB_JAR="$(tr ':' '\n' < "$WORK/nativelibs.cp" | grep -E '/scala-library-[0-9.]+\.jar$')"
[[ -n "$STDLIB_JAR" ]] || { echo "could not find scala-library jar on nativelibs.cp" >&2; exit 1; }
STDLIB_VERSION="$(basename "$STDLIB_JAR" .jar | sed 's/^scala-library-//')"

SRC_DIR="$WORK/scalalib-retained-src"
CLASSES_DIR="$WORK/scalalib-retained-classes"
rm -rf "$SRC_DIR" "$CLASSES_DIR"
mkdir -p "$SRC_DIR" "$CLASSES_DIR" "$DIST/lib"

echo "fetching scala-library $STDLIB_VERSION sources..."
SOURCES_JAR="$(cs fetch --classifier sources "org.scala-lang:scala-library:$STDLIB_VERSION")"
(cd "$SRC_DIR" && "$JAR" xf "$SOURCES_JAR")

# These are stubs for the handful of types dotc treats as compiler
# builtins (defined instead by vendor/scala3/library/src) -- never compile
# them from the plain scala-library source, same exclusion
# scala2-library-bootstrapped's own sbt build applies.
for stub in Any AnyVal AnyRef Nothing Null Singleton; do
  rm -f "$SRC_DIR/scala/$stub.scala"
done

# scala2-library-bootstrapped/src holds a handful of dotc-specific overrides
# of files that otherwise come from the plain scala-library source (see its
# own sbt settings) -- copy all of them over, preserving relative paths.
(cd "$VENDOR/scala2-library-bootstrapped/src" && find . -name '*.scala' -print0) |
  while IFS= read -r -d '' relpath; do
    mkdir -p "$SRC_DIR/$(dirname "$relpath")"
    cp -f "$VENDOR/scala2-library-bootstrapped/src/$relpath" "$SRC_DIR/$relpath"
  done

echo "compiling with dist/dotc-native (the real compiler scli itself runs)..."
# `-javabootclasspath`: dotc-native is a native-image build with no real JDK
# rt.jar/module-path of its own to fall back on (unlike a plain `$JAVA -cp
# ... dotty.tools.dotc.Main` invocation) -- without it, even
# `Definitions#init` itself fails (`ObjectClass`/`AnyRefAlias` can't resolve
# `java.lang.Object`). Same flag scli itself always passes.
"$DIST/dotc-native" \
  -javabootclasspath "$DIST/java.base.jar" \
  -classpath "$(cat "$WORK/compiler.cp")" \
  -Ycompile-scala2-library -Yscala2-unpickler:never -Yretain-trees -Werror:false \
  -d "$CLASSES_DIR" \
  $(find "$SRC_DIR" -name '*.scala')

echo "merging in the plain jar's Java-sourced classes (scala.runtime.* etc, never compiled above)..."
# The scala-library *sources* jar we compiled from only has .scala files
# (`find ... -name '*.scala'` above) -- but real scala-library also has a
# handful of .java sources (scala.runtime.Statics, BoxesRunTime, etc,
# runtime-support classes with no .scala equivalent at all). Skipping them
# isn't an oversight to fix by finding more sources to feed dotc (dotc
# can't compile .java here anyway) -- merge the plain jar's own compiled
# classes for anything CLASSES_DIR doesn't already provide, so
# scalalib-retained.jar is a complete, self-contained replacement and
# never needs the plain jar alongside it on the same classpath (see
# cli/Scli.scala's dropPlainScalaLibrary -- two jars both providing
# scala.Option/etc turned out to make dotc's classpath resolution
# non-deterministic, not just redundant).
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
