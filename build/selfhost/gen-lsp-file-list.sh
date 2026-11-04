#!/usr/bin/env bash
# Reproduces the source-file list of scalino-lsp's FRONTEND compile, used by
# build/08-build-scalino-lsp.sh. The backend is scala3-presentation-compiler behind a thin
# `PcLanguageServer` over the hand-rolled JSON-RPC transport. Extends gen-file-list.sh's dotc
# list with the LSP transport (Lsp.scala/Main.scala), presentation-compiler, its mtags-shared
# port and the languageserver glue (PcLanguageServer/BuildInfo), but NOT lsp-shim/lsp4j and
# lsp-shim/mtags-interfaces, which compile in a separate invocation against the real jars (see
# 08: auto-application of parenless calls only works on genuinely Java-defined symbols).
#
# Usage: build/selfhost/gen-lsp-file-list.sh > /tmp/lsp-file-list.txt
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")"

./gen-file-list.sh

ROOT="$(cd .. && cd .. && pwd)"

# LSP transport: stdio framing/dispatch (Main.scala) + hand-written jsoniter-scala wire model (Lsp.scala).
echo "$ROOT/vendor/scala3/language-server/src/dotty/tools/languageserver/Lsp.scala"
echo "$ROOT/vendor/scala3/language-server/src/dotty/tools/languageserver/Main.scala"

# presentation-compiler's own ~59 files, unmodified (ASM/gson/guava usage is only in mtags-shared).
find "$ROOT/vendor/scala3/presentation-compiler/src/main" -name "*.scala"

# dotc's dotty.tools.dotc.semanticdb package (37 files): excluded from gen-file-list.sh as
# tooling-only, but presentation-compiler's SemanticdbSymbols/SemanticdbTextDocumentProvider call
# it (symbolsFromName/symbolToName/textDocumentBytes), and lsp-shim/mtags-shared's Compression.scala
# substitutes SemanticdbInputStream/OutputStream for protobuf's (Metals' `replaceProtobuf` in
# vendor/scala3/project/Build.scala). Needs no protobuf jar or ASM.
find "$ROOT/vendor/scala3/compiler/src/dotty/tools/dotc/semanticdb" -name "*.scala"

# mtags-shared, ported and patched (bloom filter, protobuf substitution, Gson codecs); see lsp-shim/mtags-shared/.
find "$ROOT/lsp-shim/mtags-shared" -name "*.scala"

# Glue in dotty.tools.(languageserver|pc.buildinfo) with no upstream identity; see lsp-shim/.
echo "$ROOT/lsp-shim/BuildInfo.scala"
echo "$ROOT/lsp-shim/PcLanguageServer.scala"
