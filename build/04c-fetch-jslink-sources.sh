#!/usr/bin/env bash
# Unpacks the Scala.js linker's sources (scalajs-linker, -linker-interface, -ir, -logging; all pure
# Scala 2.13 code that dotc compiles with -source:3.0-migration) into .build-work/jslink-src and
# applies the changes a Scala Native build needs:
#   - no Google Closure Compiler (a Java library): backend/closure is removed and `closureCompiler`
#     is always off, so fullOpt is the Scala.js optimizer without the GCC pass;
#   - no scala-parallel-collections: the optimizer always uses its sequential collection ops;
#   - no PathIRFile/PathIRContainer/PathOutputDirectory/PathOutputFile (AsynchronousFileChannel is
#     not in javalib): src/JsLink.scala reads and writes the files itself, through the linker's
#     in-memory MemIRFileImpl/MemOutputDirectory.
#   - no java.security.MessageDigest in javalib-native: scalajs-ir's SHA1 is replaced by the pure Scala
#     tools/jslink/SHA1.scala.
# Consumed by build/04d-build-scalino-jslink.sh.
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")"
source ./00-env.sh

OUT="$WORK/jslink-src"
rm -rf "$OUT"
mkdir -p "$OUT"
for art in "scalajs-linker_2.13:$SCALAJS_VERSION" "scalajs-linker-interface_2.13:$SCALAJS_VERSION" \
           "scalajs-ir_2.13:$SCALAJS_VERSION" "scalajs-logging_2.13:$SCALAJS_LOGGING_VERSION"; do
  jar="$(cs fetch --classifier sources "org.scala-js:$art" | tr -d '\r' | grep -- "${art/:/-}-sources.jar")"
  [[ -n "$jar" ]] || { echo "no sources jar for $art" >&2; exit 1; }
  python3 -c 'import sys,zipfile; zipfile.ZipFile(sys.argv[1]).extractall(sys.argv[2])' "$jar" "$OUT"
done
rm -rf "$OUT/META-INF"

# The linker's private library (the emitter's polyfills) is precompiled `.sjsir` in the binary jar's
# resources, read through `getResourceAsStream` at start-up. Only the sources jar is unpacked above, so
# take the resources from the binary jar; scalino embeds them into the executable (--embed-resources).
RES="$WORK/jslink-resources"
rm -rf "$RES"
mkdir -p "$RES"
BIN="$(cs fetch "org.scala-js:scalajs-linker_2.13:$SCALAJS_VERSION" | tr -d '\r' | grep -- "scalajs-linker_2.13-$SCALAJS_VERSION.jar")"
python3 - "$BIN" "$RES" <<'PY'
import sys, zipfile
z = zipfile.ZipFile(sys.argv[1])
for n in z.namelist():
    if not n.endswith(('/', '.class')) and not n.startswith('META-INF/'):
        z.extract(n, sys.argv[2])
PY

L="$OUT/org/scalajs/linker"
rm -rf "$L/backend/closure" \
  "$L/PathIRContainer.scala" "$L/PathIRFile.scala" "$L/PathOutputDirectory.scala" "$L/PathOutputFile.scala" \
  "$L/frontend/optimizer/ParCollOps.scala"

cp "$ROOT/tools/jslink/SHA1.scala" "$OUT/org/scalajs/ir/SHA1.scala"

python3 - "$L" <<'PY'
import sys, pathlib
L = pathlib.Path(sys.argv[1])
def sub(rel, old, new):
    p = L / rel
    s = p.read_text()
    assert s.count(old) == 1, (rel, old)
    p.write_text(s.replace(old, new))
sub('frontend/optimizer/ParIncOptimizer.scala', 'new IncOptimizer(config, ParCollOps)', 'new IncOptimizer(config, SeqCollOps)')
sub('backend/LinkerBackendImplPlatform.scala', 'import org.scalajs.linker.backend.closure.ClosureLinkerBackend\n', '')
sub('backend/LinkerBackendImplPlatform.scala', '''    if (config.closureCompiler)
      new ClosureLinkerBackend(config)
    else
      new BasicLinkerBackend(config)''', '''    // No Google Closure Compiler in this build (see build/04c-fetch-jslink-sources.sh).
    new BasicLinkerBackend(config)''')
# Scala Native's Class.getResourceAsStream does not resolve names relative to the class's package.
sub('backend/emitter/PrivateLibHolder.scala', 'getClass().getResourceAsStream(name)',
    'getClass().getResourceAsStream("/org/scalajs/linker/backend/emitter/" + name)')
sub('backend/LinkerBackendImplPlatformExtensions.scala', 'import org.scalajs.linker.backend.closure.ClosureLinkerBackend\n', '')
sub('backend/LinkerBackendImplPlatformExtensions.scala', 'def closureCompiler: Boolean = self.closureCompilerIfAvailable', 'def closureCompiler: Boolean = false')
PY
echo "  $(find "$OUT" -name '*.scala' | wc -l | tr -d ' ') linker source files -> $OUT, $(find "$RES" -type f | wc -l | tr -d ' ') resources -> $RES"
