#!/usr/bin/env bash
# Compiles the patched vendor/scala3 (+ vendor/scala-native nscplugin/nir/util)
# self-hosted source set to plain JVM .class files, using the published,
# unpatched scala3-compiler_3 jar as the executing compiler. Needed only so
# build/02b-gen-megaphase-overrides.sh's reflection scan can see MiniPhase
# subclasses this project's own patches add (e.g.
# transform/CollectEntryPoints.scala, patches/scala3-0006) -- those never
# exist in the published jar, only in the patched source tree, so scanning
# the published jar alone (what 02b did on its own, pre-2026-09-13) silently
# omits them: MegaPhase.defines' generated lookup table then has no entry for
# them, and any self-hosted binary crashes the first time it actually fuses
# phases (`MiniPhaseOverrides has no entry for ...`).
#
# This step exists because build/selfhost/build-dotc.sh, an earlier opt-in
# GraalVM-native-image-era script, used to do this same reflection-discovery
# compile (leftover evidence: the now-orphaned, stale-3.8.4-pinned
# .build-work/compiler-patched.cp / compiler-sources.cp files) but was
# deleted in the 2026-09-07 self-host cutover (commit 94851ee) without this
# capability being carried over into the new 02b script. Restored here as
# its own step so it's easy to find again.
#
# Note this is a *plain JVM* compile, not a Scala Native/NIR one: it uses the
# real published scala3-compiler_3 jar's own already-loaded GenBCode backend
# to emit real, reflectable .class bytecode (this project's own
# vendor/scala3 patches Nil out `backendPhases` only in the source *files*
# being compiled here -- that has zero effect on the already-running compiler
# process actually doing the compiling). backend/jvm/backend/sjs sources are
# still excluded from the input (see build/selfhost/gen-file-list.sh's own
# comment) purely because they'd need a real org.scala-lang.modules:scala-asm
# dependency to compile as *source*, which nothing here needs.
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")"
source ./00-env.sh

[[ -f "$WORK/compiler.cp" ]] || { echo "run build/01-fetch-deps.sh first" >&2; exit 1; }

BOOT_DIR="$WORK/selfhost-bootstrap"
rm -rf "$BOOT_DIR"
mkdir -p "$BOOT_DIR"

# selfhost/gen-file-list.sh's own list includes the *output* of 02b
# (.build-work/generated/MiniPhaseOverrides.scala), which doesn't exist yet
# at this point in the pipeline (that's the file 02b is about to generate,
# using classes compiled by *this* script). Swap in an empty stand-in: the
# real self-hosted NIR build (build/03-build-scalino-dotc.sh) always compiles
# the real generated one, never this stand-in -- this bootstrap compile only
# needs MegaPhase.scala's reference to `MiniPhaseOverrides.declaredMethodNames`
# to type-check, not to contain real data.
DUMMY_OVERRIDES="$BOOT_DIR/MiniPhaseOverridesDummy.scala"
cat > "$DUMMY_OVERRIDES" <<'EOF'
package dotty.tools.dotc.transform
object MiniPhaseOverrides {
  val declaredMethodNames: Map[String, Set[String]] = Map.empty
}
EOF

FILE_LIST="$BOOT_DIR/file-list.txt"
{
  ./selfhost/gen-file-list.sh | grep -v '/generated/MiniPhaseOverrides\.scala$'
  echo "$DUMMY_OVERRIDES"
} | to_native_path_list > "$FILE_LIST"
echo "  $(wc -l < "$FILE_LIST" | tr -d ' ') files"

OUT_CLASSES="$BOOT_DIR/classes"
mkdir -p "$OUT_CLASSES"

# The classpath the sources are compiled *against* must NOT include the
# published scala3-compiler_3 jar itself: these sources redefine plenty of
# its own classes (Compiler.scala, every transform/*.scala file, ...), and
# having both on the same classpath used for typechecking risks duplicate/
# shadowed-symbol errors. Only its non-compiler dependencies are needed.
DEPS_CP="$(tr "$CP_SEP" '\n' < "$WORK/compiler.cp" | grep -v '/scala3-compiler_3-' | paste -sd"$CP_SEP" -)"

echo "== compiling patched dotc+nscplugin source to plain JVM classes (bootstrap, for MiniPhase discovery only) =="
# The compiler sources of Scala 3.10+ are written for -Yexplicit-nulls (scala3/project/Build.scala); the
# rest of the source set (nscplugin, nir, our patches) is not, hence unsafeNulls for the whole compile.
"$JAVA" -Xss64m -cp "$(cat "$WORK/compiler.cp")" dotty.tools.dotc.Main \
  -Yexplicit-nulls -language:unsafeNulls -language:implicitConversions \
  -classpath "$DEPS_CP" \
  -d "$OUT_CLASSES" \
  "@$FILE_LIST"

echo "OK: $OUT_CLASSES"
