#!/usr/bin/env bash
# Reproduces the exact source-file list scalino-lsp is self-hosted from --
# used by build/08-build-scalino-lsp.sh (the real pipeline, since the
# 2026-09-07 cutover; see docs/findings.md's "Self-hosting scalino-lsp"
# section for the full history). Extends gen-file-list.sh's dotc list with
# LSP's own real
# source (4 files: Lsp.scala/Main.scala/DottyLanguageServer.scala/
# Memory.scala -- the worksheet/JVM-subprocess-REPL and TASTy-decompiler
# endpoints were already dropped by patches/scala3-0002-trim-language-server.patch,
# both still reference real lsp4j types and stay out of scope here too)
# plus 4 decompiler-support files needed to satisfy
# DottyLanguageServer.decompilerDriverFor's import (none ASM-dependent).
#
# Usage: build/selfhost/gen-lsp-file-list.sh > /tmp/lsp-file-list.txt
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")"

./gen-file-list.sh

ROOT="$(cd .. && cd .. && pwd)"

echo "$ROOT/vendor/scala3/language-server/src/dotty/tools/languageserver/Lsp.scala"
echo "$ROOT/vendor/scala3/language-server/src/dotty/tools/languageserver/Main.scala"
echo "$ROOT/vendor/scala3/language-server/src/dotty/tools/languageserver/DottyLanguageServer.scala"
echo "$ROOT/vendor/scala3/language-server/src/dotty/tools/languageserver/Memory.scala"

echo "$ROOT/vendor/scala3/compiler/src/dotty/tools/dotc/decompiler/IDEDecompilerDriver.scala"
echo "$ROOT/vendor/scala3/compiler/src/dotty/tools/dotc/decompiler/PartialTASTYDecompiler.scala"
echo "$ROOT/vendor/scala3/compiler/src/dotty/tools/dotc/decompiler/TASTYDecompiler.scala"
echo "$ROOT/vendor/scala3/compiler/src/dotty/tools/dotc/decompiler/DecompilationPrinter.scala"
