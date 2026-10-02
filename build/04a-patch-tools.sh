#!/usr/bin/env bash
# Patches scala-native's tools_native0.5_3 (the JVM-side build/link API LinkDriver.scala
# drives) to fix inert object-file caching for vendored C/S dependency
# sources -- see docs/findings.md "Native-library object-file caching was
# inert". Same splice-not-full-rebuild approach as 03a-patch-compiler.sh.
#
# ALSO patches the plain-JVM tools_3 artifact the same way (see the second
# half of this script). Both patch sets currently share the same shared-source
# file list (tools/src/main/...), since useLLVMDirectCodeGen (NativeConfig/
# Discover/Validator/LLVM/CodeGen) needs to reach BOTH: tools_native0.5_3 is
# what LinkDriver.scala is *compiled against* (NATIVE_DRIVER_CP, so it must
# resolve e.g. NativeConfig.withLLVMDirectCodeGen at all), but tools_3 (plain
# JVM) is what the compiled LinkDriver.class is actually *run* against during
# this bootstrap (DRIVER_CP, in 04-build-scalino-linkdriver.sh) -- tools_3 is
# used there specifically because tools_native0.5_3's link-time intrinsics
# throw UndefinedBehaviorError on a plain JVM (01-fetch-deps.sh's comment).
# Skipping the tools_3 patch would leave LinkDriver.class compiled against a
# method (withLLVMDirectCodeGen) that doesn't exist on the classpath it's
# actually executed with -- NoSuchMethodError, breaking the bootstrap for
# everyone, not just users of the experimental flag.
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")"
source ./00-env.sh
VENDOR="$ROOT/vendor/scala-native"
[[ -f "$VENDOR/tools/src/main/scala/scala/scalanative/build/Build.scala" ]] || {
  echo "missing $VENDOR -- run 00b-setup-vendor.sh first" >&2
  exit 1
}
[[ -f "$WORK/tools-native.cp" ]] || { echo "run 01-fetch-deps.sh first" >&2; exit 1; }
[[ -f "$WORK/tools.cp" ]] || { echo "run 01-fetch-deps.sh first" >&2; exit 1; }
[[ -f "$WORK/compiler.cp" ]] || { echo "run 01-fetch-deps.sh first" >&2; exit 1; }

# Shared-source files common to both patched jars (tools/src/main/...) --
# always safe to recompile+splice into either target, JVM or Native.
SHARED_SOURCES=(
  "$VENDOR/tools/src/main/scala/scala/scalanative/build/Build.scala"
  "$VENDOR/tools/src/main/scala/scala/scalanative/build/LLVM.scala"
  "$VENDOR/tools/src/main/scala/scala/scalanative/build/NativeLib.scala"
  "$VENDOR/tools/src/main/scala/scala/scalanative/build/ScalaNative.scala"
  "$VENDOR/tools/src/main/scala/scala/scalanative/build/IO.scala"
  "$VENDOR/tools/src/main/scala/scala/scalanative/build/NativeConfig.scala"
  "$VENDOR/tools/src/main/scala/scala/scalanative/build/Discover.scala"
  "$VENDOR/tools/src/main/scala/scala/scalanative/build/Validator.scala"
  "$VENDOR/tools/src/main/scala/scala/scalanative/codegen/IncrementalCodeGenContext.scala"
  "$VENDOR/tools/src/main/scala/scala/scalanative/codegen/SourceCodeCache.scala"
  "$VENDOR/tools/src/main/scala/scala/scalanative/codegen/Lower.scala"
  "$VENDOR/tools/src/main/scala/scala/scalanative/linker/Infos.scala"
  "$VENDOR/tools/src/main/scala/scala/scalanative/linker/Reach.scala"
  "$VENDOR/tools/src/main/scala/scala/scalanative/linker/Link.scala"
  "$VENDOR/tools/src/main/scala/scala/scalanative/linker/ClassLoader.scala"
  "$VENDOR/tools/src/main/scala/scala/scalanative/linker/ClassPath.scala"
  "$VENDOR/tools/src/main/scala/scala/scalanative/linker/Sub.scala"
  "$VENDOR/tools/src/main/scala/scala/scalanative/linker/Extractors.scala"
  "$VENDOR/tools/src/main/scala/scala/scalanative/checker/Check.scala"
  "$VENDOR/tools/src/main/scala/scala/scalanative/codegen/Generate.scala"
  "$VENDOR/tools/src/main/scala/scala/scalanative/codegen/GenerateReflectiveProxies.scala"
  "$VENDOR/tools/src/main/scala/scala/scalanative/codegen/PerfectHashMap.scala"
  "$VENDOR/tools/src/main/scala/scala/scalanative/codegen/DynamicHashMap.scala"
  "$VENDOR/tools/src/main/scala/scala/scalanative/codegen/CommonMemoryLayouts.scala"
  "$VENDOR/tools/src/main/scala/scala/scalanative/codegen/Metadata.scala"
  "$VENDOR/tools/src/main/scala/scala/scalanative/codegen/FieldLayout.scala"
  "$VENDOR/tools/src/main/scala/scala/scalanative/codegen/RuntimeTypeInformation.scala"
  "$VENDOR/tools/src/main/scala/scala/scalanative/codegen/PlatformInfo.scala"
  "$VENDOR/tools/src/main/scala/scala/scalanative/codegen/llvm/AbstractCodeGen.scala"
  "$VENDOR/tools/src/main/scala/scala/scalanative/linker/LinktimeValueResolver.scala"
  "$VENDOR/tools/src/main/scala/scala/scalanative/codegen/VirtualTable.scala"
  "$VENDOR/tools/src/main/scala/scala/scalanative/codegen/llvm/CodeGen.scala"
  "$VENDOR/tools/src/main/scala/scala/scalanative/codegen/llvm/MetadataCodeGen.scala"
  "$VENDOR/tools/src/main/scala/scala/scalanative/codegen/llvm/direct/DirectCodeGenPlan.scala"
  "$VENDOR/tools/src/main/scala/scala/scalanative/interflow/Interflow.scala"
  "$VENDOR/tools/src/main/scala/scala/scalanative/interflow/Combine.scala"
  "$VENDOR/tools/src/main/scala/scala/scalanative/interflow/Eval.scala"
  "$VENDOR/tools/src/main/scala/scala/scalanative/interflow/MergeProcessor.scala"
  "$VENDOR/tools/src/main/scala/scala/scalanative/interflow/State.scala"
  "$VENDOR/tools/src/main/scala/scala/scalanative/interflow/PolyInline.scala"
  "$VENDOR/tools/src/main/scala/scala/scalanative/interflow/Inline.scala"
  "$VENDOR/tools/src/main/scala/scala/scalanative/interflow/Visit.scala"
  "$VENDOR/tools/src/main/scala/scala/scalanative/interflow/NoOpt.scala"
  "$VENDOR/tools/src/main/scala/scala/scalanative/interflow/UseDef.scala"
)
NSCPLUGIN_JAR="$(cat "$WORK/nscplugin.jar.txt")"

# ---- util-targeted patch (util_native0.5_3 + util_3): adds
# VirtualDirectory.lastModified, which ClassPath.scala (patched below, via
# SHARED_SOURCES) needs to invalidate its persistent in-memory NIR parse
# cache per-file instead of per-whole-build -- see patches/scala-native-0047.
# Single shared source file (pure java.nio, no jvm/native split in
# util/src/main), so one source list patches both jars. Must run before the
# nir/tools patches below so their compiles (which need ClassPath.scala to
# resolve the new method) see the patched util jar, not the stock one. ----
UTIL_SOURCES=(
  "$VENDOR/util/src/main/scala/scala/scalanative/io/VirtualDirectory.scala"
)

ORIG_UTIL_JAR="$(tr "$CP_SEP" '\n' < "$WORK/tools-native.cp" | grep "util_native0.5_3-$SCALA_NATIVE_VERSION.jar$")"
[[ -n "$ORIG_UTIL_JAR" ]] || { echo "could not find util_native0.5_3-$SCALA_NATIVE_VERSION.jar on tools-native.cp" >&2; exit 1; }

PATCHED_UTIL_DIR="$WORK/patched-util-native-classes"
PATCHED_UTIL_JAR="$DIST/util-native-patched.jar"

rm -rf "$PATCHED_UTIL_DIR"
mkdir -p "$PATCHED_UTIL_DIR"
"$JAVA" -cp "$(cat "$WORK/compiler.cp")" dotty.tools.dotc.Main \
  -Xplugin:"$NSCPLUGIN_JAR" \
  -Xplugin-require:scalanative \
  -classpath "$(cat "$WORK/compiler.cp")$CP_SEP$(cat "$WORK/tools-native.cp")" \
  -d "$PATCHED_UTIL_DIR" \
  "${UTIL_SOURCES[@]}"

cp "$ORIG_UTIL_JAR" "$PATCHED_UTIL_JAR"
(cd "$PATCHED_UTIL_DIR" && "$JAR" uf "$PATCHED_UTIL_JAR" $(find scala -type f))

sed "s#$ORIG_UTIL_JAR#$PATCHED_UTIL_JAR#" "$WORK/tools-native.cp" > "$WORK/util-native-patched.cp"

echo "OK: $PATCHED_UTIL_JAR"

ORIG_UTIL_JAR_JVM="$(tr "$CP_SEP" '\n' < "$WORK/tools.cp" | grep "util_3-$SCALA_NATIVE_VERSION.jar$")"
[[ -n "$ORIG_UTIL_JAR_JVM" ]] || { echo "could not find util_3-$SCALA_NATIVE_VERSION.jar on tools.cp" >&2; exit 1; }

PATCHED_UTIL_DIR_JVM="$WORK/patched-util-jvm-classes"
PATCHED_UTIL_JAR_JVM="$DIST/util-jvm-patched.jar"

rm -rf "$PATCHED_UTIL_DIR_JVM"
mkdir -p "$PATCHED_UTIL_DIR_JVM"
"$JAVA" -cp "$(cat "$WORK/compiler.cp")" dotty.tools.dotc.Main \
  -classpath "$(cat "$WORK/compiler.cp")$CP_SEP$(cat "$WORK/tools.cp")" \
  -d "$PATCHED_UTIL_DIR_JVM" \
  "${UTIL_SOURCES[@]}"

cp "$ORIG_UTIL_JAR_JVM" "$PATCHED_UTIL_JAR_JVM"
(cd "$PATCHED_UTIL_DIR_JVM" && "$JAR" uf "$PATCHED_UTIL_JAR_JVM" $(find scala -type f))

sed "s#$ORIG_UTIL_JAR_JVM#$PATCHED_UTIL_JAR_JVM#" "$WORK/tools.cp" > "$WORK/util-jvm-patched.cp"

echo "OK: $PATCHED_UTIL_JAR_JVM"

# ---- nir-targeted patch (nir_native0.5_3 only -- NOT nir_3/the JVM jar: the
# JVM bootstrap path's own perf isn't a target, only the final compiled
# scalino-linkdriver binary's is, and that binary's nir.Val/Type behavior
# comes from whatever nir_native0.5_3 was reachable when NATIVE_DRIVER_CP
# linked it, in 04-build-scalino-linkdriver.sh). Caches Val/Type/Global's
# hashCode (MurmurHash3.productHash) the same way nir.Op/nir.Sig already do
# -- see docs/findings.md profiling notes: uncached case-class structural
# hashing on these was the largest remaining scalino-owned hot path.
# Global.Top/Member added after a follow-up profile showed anyHash/
# caseClassHash/Global$Member.equals still hot -- Global.Member is the key
# type for most of linker/interflow's reachability & dedup Maps/Sets, so its
# default (uncached) case-class hashCode was recomputed on every lookup. ----
NIR_SOURCES=(
  "$VENDOR/nir/src/main/scala/scala/scalanative/nir/Vals.scala"
  "$VENDOR/nir/src/main/scala/scala/scalanative/nir/Types.scala"
  "$VENDOR/nir/src/main/scala/scala/scalanative/nir/Insts.scala"
  "$VENDOR/nir/src/main/scala/scala/scalanative/nir/Global.scala"
  "$VENDOR/nir/src/main/scala/scala/scalanative/nir/ControlFlow.scala"
  "$VENDOR/nir/src/main/scala/scala/scalanative/nir/SourcePosition.scala"
  "$VENDOR/nir/src/main/scala/scala/scalanative/nir/Show.scala"
)
ORIG_NIR_JAR="$(tr "$CP_SEP" '\n' < "$WORK/util-native-patched.cp" | grep "nir_native0.5_3-$SCALA_NATIVE_VERSION.jar$")"
[[ -n "$ORIG_NIR_JAR" ]] || { echo "could not find nir_native0.5_3-$SCALA_NATIVE_VERSION.jar on util-native-patched.cp" >&2; exit 1; }

PATCHED_NIR_DIR="$WORK/patched-nir-native-classes"
PATCHED_NIR_JAR="$DIST/nir-native-patched.jar"

rm -rf "$PATCHED_NIR_DIR"
mkdir -p "$PATCHED_NIR_DIR"
"$JAVA" -cp "$(cat "$WORK/compiler.cp")" dotty.tools.dotc.Main \
  -Xplugin:"$NSCPLUGIN_JAR" \
  -Xplugin-require:scalanative \
  -classpath "$(cat "$WORK/compiler.cp")$CP_SEP$(cat "$WORK/util-native-patched.cp")" \
  -d "$PATCHED_NIR_DIR" \
  "${NIR_SOURCES[@]}"

cp "$ORIG_NIR_JAR" "$PATCHED_NIR_JAR"
(cd "$PATCHED_NIR_DIR" && "$JAR" uf "$PATCHED_NIR_JAR" $(find scala -type f))

sed "s#$ORIG_NIR_JAR#$PATCHED_NIR_JAR#" "$WORK/util-native-patched.cp" > "$WORK/nir-native-patched.cp"

echo "OK: $PATCHED_NIR_JAR"

# ---- Native-targeted patch (tools_native0.5_3): also gets the direct-codegen
# implementation itself (tools/native/-only: @extern doesn't exist for JVM).
# Built on top of nir-native-patched.cp (not the raw tools-native.cp) so the
# nir patch above chains through into tools-patched.cp below, and from there
# into NATIVE_DRIVER_CP in 04-build-scalino-linkdriver.sh. ----
ORIG_JAR="$(tr "$CP_SEP" '\n' < "$WORK/nir-native-patched.cp" | grep "tools_native0.5_3-$SCALA_NATIVE_VERSION.jar$")"
[[ -n "$ORIG_JAR" ]] || { echo "could not find tools_native0.5_3-$SCALA_NATIVE_VERSION.jar on nir-native-patched.cp" >&2; exit 1; }

PATCHED_DIR="$WORK/patched-tools-classes"
PATCHED_JAR="$DIST/tools-patched.jar"

# Must compile with -cp/-classpath both set to the SAME combined classpath:
# tools_3's own declared scala3-library_3 (3.1.3, pulled in via tools.cp) and
# our compiler's scala3-library_3 (3.7.1, via compiler.cp) can't both be on
# the classpath used to *run* dotc vs. the one used to *typecheck* -- that
# split caused a scala.runtime.LazyVals TASTy/binary mismatch. A single
# unified classpath for both flags avoids it (same pattern LinkDriver.scala's
# own build step already uses).
FULL_CP="$(cat "$WORK/compiler.cp")$CP_SEP$(cat "$WORK/nir-native-patched.cp")"

rm -rf "$PATCHED_DIR"
mkdir -p "$PATCHED_DIR"
"$JAVA" -cp "$(cat "$WORK/compiler.cp")" dotty.tools.dotc.Main \
  -Xplugin:"$NSCPLUGIN_JAR" \
  -Xplugin-require:scalanative \
  -classpath "$FULL_CP" \
  -d "$PATCHED_DIR" \
  "${SHARED_SOURCES[@]}" \
  "$VENDOR/tools/native/src/main/scala/scala/scalanative/codegen/llvm/direct/LLVMCApi.scala" \
  "$VENDOR/tools/native/src/main/scala/scala/scalanative/codegen/llvm/direct/DirectCodeGen.scala" \
  "$VENDOR/tools/native/src/main/scala/scala/scalanative/codegen/llvm/direct/DirectCodeGenDispatch.scala"

cp "$ORIG_JAR" "$PATCHED_JAR"
(cd "$PATCHED_DIR" && "$JAR" uf "$PATCHED_JAR" $(find scala -type f))

sed "s#$ORIG_JAR#$PATCHED_JAR#" "$WORK/nir-native-patched.cp" > "$WORK/tools-patched.cp"

echo "OK: $PATCHED_JAR"

# ---- JVM-targeted nir patch (nir_3 only -- the plain-JVM counterpart to
# the nir_native0.5_3 patch above). Previously this patch only cached
# hashCode (a pure implementation detail, no public API change), so the
# JVM-targeted tools_3 patch below could get away with never patching its
# own nir_3 jar -- SHARED_SOURCES compiled fine against the stock published
# nir_3 either way. That stopped being true the moment nir/Types.scala
# gained a real new public member (Type.Smi, for SMI-style tagged-pointer
# boxing): SHARED_SOURCES (Lower.scala) now references it, so without this
# section the tools_3 compile below fails with "value Smi is not a member
# of object scala.scalanative.nir.Type" even though the native-targeted
# compile above (which already substitutes nir-native-patched.cp) works
# fine. Mirrors the native-targeted nir patch immediately above it. ----
ORIG_NIR_JAR_JVM="$(tr "$CP_SEP" '\n' < "$WORK/util-jvm-patched.cp" | grep "nir_3-$SCALA_NATIVE_VERSION.jar$")"
[[ -n "$ORIG_NIR_JAR_JVM" ]] || { echo "could not find nir_3-$SCALA_NATIVE_VERSION.jar on util-jvm-patched.cp" >&2; exit 1; }

PATCHED_NIR_DIR_JVM="$WORK/patched-nir-jvm-classes"
PATCHED_NIR_JAR_JVM="$DIST/nir-jvm-patched.jar"

rm -rf "$PATCHED_NIR_DIR_JVM"
mkdir -p "$PATCHED_NIR_DIR_JVM"
"$JAVA" -cp "$(cat "$WORK/compiler.cp")" dotty.tools.dotc.Main \
  -classpath "$(cat "$WORK/compiler.cp")$CP_SEP$(cat "$WORK/util-jvm-patched.cp")" \
  -d "$PATCHED_NIR_DIR_JVM" \
  "${NIR_SOURCES[@]}"

cp "$ORIG_NIR_JAR_JVM" "$PATCHED_NIR_JAR_JVM"
(cd "$PATCHED_NIR_DIR_JVM" && "$JAR" uf "$PATCHED_NIR_JAR_JVM" $(find scala -type f))

sed "s#$ORIG_NIR_JAR_JVM#$PATCHED_NIR_JAR_JVM#" "$WORK/util-jvm-patched.cp" > "$WORK/nir-jvm-patched.cp"

echo "OK: $PATCHED_NIR_JAR_JVM"

# ---- JVM-targeted patch (tools_3): the no-op DirectCodeGenDispatch stub
# only, since @extern/LLVMCApi/DirectCodeGen can't compile for the JVM. ----
ORIG_JAR_JVM="$(tr "$CP_SEP" '\n' < "$WORK/nir-jvm-patched.cp" | grep "tools_3-$SCALA_NATIVE_VERSION.jar$")"
[[ -n "$ORIG_JAR_JVM" ]] || { echo "could not find tools_3-$SCALA_NATIVE_VERSION.jar on nir-jvm-patched.cp" >&2; exit 1; }

PATCHED_DIR_JVM="$WORK/patched-tools-jvm-classes"
PATCHED_JAR_JVM="$DIST/tools-patched-jvm.jar"
FULL_CP_JVM="$(cat "$WORK/compiler.cp")$CP_SEP$(cat "$WORK/nir-jvm-patched.cp")"

rm -rf "$PATCHED_DIR_JVM"
mkdir -p "$PATCHED_DIR_JVM"
# No -Xplugin/-Xplugin-require:scalanative here, unlike the native-targeted
# pass above: this jar is plain JVM bytecode, never linked as NIR, and
# tools.cp's classpath deliberately has no nativelib on it (tools_3 doesn't
# need it) -- requiring the scalanative plugin anyway makes it crash during
# its own prepareInterop phase looking for scala.scalanative.unsafe.extern,
# which isn't resolvable without nativelib on the classpath.
"$JAVA" -cp "$(cat "$WORK/compiler.cp")" dotty.tools.dotc.Main \
  -classpath "$FULL_CP_JVM" \
  -d "$PATCHED_DIR_JVM" \
  "${SHARED_SOURCES[@]}" \
  "$VENDOR/tools/jvm/src/main/scala/scala/scalanative/codegen/llvm/direct/DirectCodeGenDispatch.scala"

cp "$ORIG_JAR_JVM" "$PATCHED_JAR_JVM"
(cd "$PATCHED_DIR_JVM" && "$JAR" uf "$PATCHED_JAR_JVM" $(find scala -type f))

sed "s#$ORIG_JAR_JVM#$PATCHED_JAR_JVM#" "$WORK/nir-jvm-patched.cp" > "$WORK/tools-patched-jvm.cp"

echo "OK: $PATCHED_JAR_JVM"
