#!/usr/bin/env bash
# Reproduces the exact source-file list scalino-dotc is self-hosted from --
# used by build/03-build-scalino-dotc.sh (the real pipeline, since the
# 2026-09-07 GraalVM-native-image-to-Scala-Native cutover; see
# docs/findings.md's "Self-hosting" arc for the full history of why each
# excluded file is excluded). Kept as its own script so the exact file set
# is reproducible without re-deriving it from scratch.
#
# Usage: build/selfhost/gen-file-list.sh > /tmp/reduced-file-list.txt
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")/../.."
ROOT="$PWD"

# dotc's own source: everything under compiler/src, minus backend/jvm
# (needs org.scala-lang.modules:scala-asm, no Scala Native cross-build),
# backend/sjs + transform/sjs (Scala.js, irrelevant + same ASM dependency
# transitively), debug (self-contained expression-evaluator, confirmed
# zero references from dotc/), dotc/decompiler/dotc/semanticdb/scripting
# (tooling-only), MainGenericCompiler.scala (separate multi-tool entry
# point), util/ClasspathFromClassloader.scala (dead code), and
# dotc/sbt/{ExtractAPI,ShowAPI,APIUtils,ThunkHolder}.scala (need the rest
# of the real xsbti.api.* surface; ExtractDependencies.scala/package.scala
# don't and are kept, patched -- see patches/scala3-0006).
find "$ROOT/vendor/scala3/compiler/src" -name "*.scala" \
  -not -path "*/dotty/tools/backend/jvm/*" \
  -not -path "*/dotty/tools/backend/sjs/*" \
  -not -path "*/dotty/tools/debug/*" \
  -not -path "*/dotty/tools/dotc/decompiler/*" \
  -not -path "*/dotty/tools/dotc/semanticdb/*" \
  -not -path "*/dotty/tools/scripting/*" \
  -not -path "*/dotty/tools/dotc/transform/sjs/*" \
  -not -name "MainGenericCompiler.scala" \
  -not -name "ClasspathFromClassloader.scala" \
  -not -name "ExtractAPI.scala" \
  -not -name "ShowAPI.scala" \
  -not -name "APIUtils.scala" \
  -not -name "ThunkHolder.scala" \
  -not -name "SJSPlatform.scala"

# DottyPrimitives.scala: the one backend/jvm file that IS needed (nscplugin's
# own NirPrimitives.scala imports it) -- self-contained, no real ASM
# dependency, safe to include on its own without the rest of backend/jvm.
echo "$ROOT/vendor/scala3/compiler/src/dotty/tools/backend/jvm/DottyPrimitives.scala"

# The Blocker-A-era generated MiniPhase override table (build/02b-gen-megaphase-overrides.sh).
echo "$ROOT/.build-work/generated/MiniPhaseOverrides.scala"

# tasty-core's real source (org.scala-lang:tasty-core_3 has no Scala Native
# cross-build either -- confirmed via cs fetch -- but its source lives
# right here in the same vendor/scala3 checkout, as its own sbt project,
# not physically inside compiler/src).
find "$ROOT/vendor/scala3/tasty/src" -name "*.scala"

# scala3-interfaces' real source, ported from Java to Scala (see
# patches/scala3-0006 -- Scala Native's own dotc has no Java-to-NIR path at
# all, so every .java file needed porting regardless of any external
# dependency).
find "$ROOT/vendor/scala3/interfaces/src" -name "*.scala"

# scala-native's own nscplugin (the real NIR-generation backend) + its
# nir/util source dependencies (dependsOnSource in scala-native's own
# project/Build.scala, not separately published artifacts). See
# patches/scala-native-0002 for the small fixups needed (symExtensions'
# moved location, no backend.jvm.GenBCode to runsBefore, no java.util.Locale).
find "$ROOT/vendor/scala-native/nscplugin/src/main/scala-3" -name "*.scala"
find "$ROOT/vendor/scala-native/nir/src/main/scala" -name "*.scala"
find "$ROOT/vendor/scala-native/util/src/main/scala" -name "*.scala"

# Hand-written stub of scala-native's own sbt-buildinfo-generated
# scala.scalanative.nir.ScalaNativeBuildInfo (normally produced by the
# sbt-buildinfo plugin at scala-native's own build time, referenced by
# nir/src's own Versions.scala but never checked into vendor/scala-native's
# source). Keep in sync with versions.env if SCALA_NATIVE_VERSION/
# SCALA_VERSION ever change.
echo "$ROOT/build/selfhost/ScalaNativeBuildInfo.scala"

# NOTE (not a .scala file, so not part of THIS list): after linking the
# self-hosted binary, copy build/selfhost/compiler.properties into the NIR
# output directory (at its root, i.e. `<out>/compiler.properties`) and pass
# `--embed-resources` to scalino-linkdriver. Without it,
# dotty.tools.dotc.config.Properties#versionNumberString silently returns ""
# at runtime (its own `/compiler.properties` classpath resource, normally
# bundled inside the published scala3-compiler jar, is never available when
# compiling from source) -- which breaks nscplugin's own
# AdaptLazyVals.compilerUsesVarHandles version sniff (`ScalaVersion.parse("")`
# succeeds as `AnyScalaVersion`, not a parse failure, so the intended
# `.orElse` fallback never triggers) and silently skips rewriting real
# VarHandle-based lazy vals (Scala 3.8+) into their Scala-Native-compatible
# form. See docs/findings.md's "Wiring in the real backend" section for the
# full root-cause writeup and verification.
