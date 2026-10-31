#!/usr/bin/env bash
# Unpacks the scalajs-ir sources into .build-work/generated/scalajs-ir-src, with the package
# renamed org.scalajs.ir -> dotty.tools.sjs.ir. Same thing vendor/scala3's own sbt build does
# (project/Build.scala, "Add the sources of scalajs-ir"): backend/sjs/JSCodeGen emits against
# dotty.tools.sjs.ir.*, which only exists as shaded source, never as a jar. Needed by
# build/selfhost/gen-file-list.sh (and so by build/03-build-scalino-dotc.sh).
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")"
source ./00-env.sh

OUT="$WORK/generated/scalajs-ir-src"
JAR="$(cs fetch --classifier sources "org.scala-js:scalajs-ir_2.13:$SCALAJS_VERSION" | tr -d '\r' | grep "scalajs-ir_2.13-$SCALAJS_VERSION-sources.jar")"
rm -rf "$OUT"
mkdir -p "$OUT"
python3 - "$JAR" "$OUT" <<'PY'
import sys, zipfile, pathlib
jar, out = sys.argv[1], pathlib.Path(sys.argv[2])
with zipfile.ZipFile(jar) as z:
    for n in z.namelist():
        if not n.endswith('.scala'):
            continue
        t = z.read(n).decode('utf-8').replace('org.scalajs.ir', 'dotty.tools.sjs.ir')
        d = out / n.replace('org/scalajs/ir', 'dotty/tools/sjs/ir')
        d.parent.mkdir(parents=True, exist_ok=True)
        d.write_text(t, encoding='utf-8')
PY
# javalib-native has no java.security.MessageDigest, which the original SHA1 wraps (the Scala.js linker
# build, 04c-fetch-jslink-sources.sh, replaces the same file): use the pure Scala one, shaded the same way.
sed 's/org\.scalajs\.ir/dotty.tools.sjs.ir/' "$ROOT/tools/jslink/SHA1.scala" > "$OUT/dotty/tools/sjs/ir/SHA1.scala"
echo "  $(find "$OUT" -name '*.scala' | wc -l | tr -d ' ') scalajs-ir files -> $OUT"
