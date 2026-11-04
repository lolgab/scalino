#!/usr/bin/env bash
# Reproduces the source-file list scalino-dotc is self-hosted from, used by
# build/03-build-scalino-dotc.sh. See docs/findings.md "Self-hosting" for why each excluded
# file is excluded.
#
# Usage: build/selfhost/gen-file-list.sh > /tmp/reduced-file-list.txt
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")/../.."
ROOT="$PWD"
source "$ROOT/versions.env"

# dotc's own source: everything under compiler/src, minus backend/jvm
# (needs org.scala-lang.modules:scala-asm, no Scala Native cross-build),
# debug (self-contained expression-evaluator, confirmed
# zero references from dotc/), dotc/decompiler/dotc/semanticdb/scripting
# (tooling-only), MainGenericCompiler.scala (separate multi-tool entry
# point), util/ClasspathFromClassloader.scala (dead code), and
# dotc/sbt/{ExtractAPI,ShowAPI,APIUtils,ThunkHolder}.scala (need the rest
# of the real xsbti.api.* surface; ExtractDependencies.scala/package.scala
# don't and are kept, patched -- see patches/scala3-0006).
find "$ROOT/vendor/scala3/compiler/src" -name "*.scala" \
  -not -path "*/dotty/tools/backend/jvm/*" \
  -not -path "*/dotty/tools/debug/*" \
  -not -path "*/dotty/tools/dotc/decompiler/*" \
  -not -path "*/dotty/tools/dotc/semanticdb/*" \
  -not -path "*/dotty/tools/scripting/*" \
  -not -name "MainGenericCompiler.scala" \
  -not -name "ClasspathFromClassloader.scala" \
  -not -name "ExtractAPI.scala" \
  -not -name "ShowAPI.scala" \
  -not -name "APIUtils.scala" \
  -not -name "ThunkHolder.scala" \
  -not -name "ProfiledThreadPool.scala"

# Scala.js backend (`-scalajs`): backend/sjs, transform/sjs and SJSPlatform are part
# of the find above. They need scalajs-ir, which dotc's own build also compiles from
# source under `dotty.tools.sjs.ir` (build/00c-fetch-scalajs-ir.sh generates it).
find "$ROOT/.build-work/generated/scalajs-ir-src" -name "*.scala"

# (nscplugin's NirPrimitives extends CompilerCompat.NirPrimitivesBase, which builds on the renamed
# backend/ScalaPrimitives.scala picked up by the plain `find` above.)

# The generated MiniPhase override table (build/02b-gen-megaphase-overrides.sh).
echo "$ROOT/.build-work/generated/MiniPhaseOverrides.scala"

# tasty-core's source (no Scala Native cross-build) lives in vendor/scala3 as its own sbt project.
find "$ROOT/vendor/scala3/tasty/src" -name "*.scala"

# scala3-interfaces' source, ported from Java to Scala (patches/scala3-0006): Scala Native's dotc
# has no Java-to-NIR path.
find "$ROOT/vendor/scala3/interfaces/src" -name "*.scala"

# scala-native's nscplugin (the NIR backend) + its nir/util sources (dependsOnSource in
# scala-native's Build.scala, not published artifacts). Fixups: patches/scala-native-0002.
find "$ROOT/vendor/scala-native/nscplugin/src/main/scala-3" -name "*.scala"
# The compiler-version-specific sources (CompilerCompat) live in scala-between_<from>_<to> /
# scala-since_<from> directories next to scala-3: take the ones that cover SCALA_VERSION.
python3 -I - "$ROOT/vendor/scala-native/nscplugin/src/main" "$SCALA_VERSION" <<'PY'
import os, re, sys
base, version = sys.argv[1], sys.argv[2]
def v(s): return tuple(int(x) for x in s.split('.'))
for d in sorted(os.listdir(base)):
    m = re.fullmatch(r'scala-between_([\d.]+)_([\d.]+)', d) or re.fullmatch(r'scala-since_([\d.]+)', d)
    if not m: continue
    lo = v(m.group(1)); hi = v(m.group(2)) if m.lastindex == 2 else (99, 99, 99)
    if lo <= v(version) <= hi:
        for root, _, files in os.walk(os.path.join(base, d)):
            for f in files:
                if f.endswith('.scala'): print(os.path.join(root, f))
PY
find "$ROOT/vendor/scala-native/nir/src/main/scala" -name "*.scala"
find "$ROOT/vendor/scala-native/util/src/main/scala" -name "*.scala"

# Hand-written stub of scala.scalanative.nir.ScalaNativeBuildInfo, which scala-native's
# sbt-buildinfo generates at its own build time (referenced by nir/src's Versions.scala, not
# checked in). Keep in sync with SCALA_NATIVE_VERSION/SCALA_VERSION in versions.env.
echo "$ROOT/build/selfhost/ScalaNativeBuildInfo.scala"

# Not a .scala file, so not in this list: after linking, copy build/selfhost/compiler.properties
# to `<out>/compiler.properties` in the NIR output and pass `--embed-resources` to
# scalino-linkdriver. Otherwise dotty.tools.dotc.config.Properties#versionNumberString returns ""
# at runtime, and nscplugin's AdaptLazyVals.compilerUsesVarHandles sniff
# (`ScalaVersion.parse("")` succeeds as AnyScalaVersion, so the `.orElse` fallback never fires)
# silently skips rewriting VarHandle-based lazy vals (Scala 3.8+). See docs/findings.md
# "Wiring in the real backend".
