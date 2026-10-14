#!/usr/bin/env bash
# Profile pipeline for scalino-lsp (stages: see pgo-lib.sh). The program is
# dotty.tools.languageserver.Main from the NIR build/08 produced (it reads the
# link classpath build/08 left in selfhost/lsp-link.cp); the training workload
# is a real-editor-shaped LSP session (build/lsp-trace-drive.py: initialize,
# didOpen/didChange, hover, definition, completion, references, rename, symbols,
# against real dotc compilation of build/lsp-trace-fixture), run LSP_RUNS times.
# Packs pgo/lsp.*.gz. An LSP link takes longer than dotc's: allow an hour per
# link and about 6GB of free memory. PGO_STAGES=1 (see pgo-lib.sh) skips the
# second round and guided Interflow (two links less, about a third less gain).
#
# Env: BOOT (PGO capable linker, default dist/scalino-linkdriver),
#      OUT (default .build-work/pgo-lsp), SCALINO_LINK_MODE (default
#      release-fast), SKIP_TO=<stage>, LSP_RUNS (default 1).
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")/.."
export SCALINO_LINK_MODE="${SCALINO_LINK_MODE:-release-fast}"
source ./00-env.sh
source ./pgo/pgo-lib.sh
NAME=lsp; BIN=dotty.tools.languageserver.Main
BOOT="${BOOT:-$DIST/scalino-linkdriver}"
OUT="${OUT:-$WORK/pgo-lsp}"
LSP_RUNS="${LSP_RUNS:-1}"
LLVM_BIN="${LLVM_BIN:-$(dirname "$(command -v llvm-profdata)")}"
LINK_CP_FILE="$WORK/selfhost/lsp-link.cp"
[[ -f "$LINK_CP_FILE" ]] || { echo "run build/08 first (need $LINK_CP_FILE)" >&2; exit 1; }
PROFILE_RT="$(pgo_profile_runtime)"
[[ -f "$PROFILE_RT" ]] || { echo "no libclang_rt.profile for $CLANG" >&2; exit 1; }

pgo_link() { # linker, dir, env...  -> dir/$BIN (same command as build/08)
  local linker="$1" dir="$2"; shift 2
  rm -rf "${dir:?}"; mkdir -p "$dir"
  local t0=$SECONDS extra=()
  [[ " $* " == *" SCALANATIVE_PGO_GEN=1 "* ]] && extra=(--linking "$PROFILE_RT")
  env "$@" SCALANATIVE_EVAC_ENABLE="${EVAC:-1}" SCALANATIVE_GC_SYNC_TIMEOUT_MS=0 "$linker" \
    "$(cat "$LINK_CP_FILE")" "$(to_native_path "$dir")" \
    "$BIN" "$CLANG" "$CLANGPP" info --mode "$SCALINO_LINK_MODE" \
    --no-incremental-compilation --embed-resources \
    "${extra[@]+"${extra[@]}"}" > "$dir.log" 2>&1 \
    || { tail -30 "$dir.log" >&2; echo "link into $dir failed" >&2; return 1; }
  echo "  $(basename "$dir"): $((SECONDS - t0))s"
}
pgo_train() { # lsp binary, raw dir
  local fixture="$ROOT/build/lsp-trace-fixture" i
  [[ -x "$DIST/scalino" ]] || { echo "need dist/scalino (build/07) to generate the fixture's lsp config" >&2; return 1; }
  (cd "$fixture" && "$DIST/scalino" setup-ide Model.scala Greeter.scala Main.scala > /dev/null)
  for i in $(seq 1 "$LSP_RUNS"); do
    LLVM_PROFILE_FILE="$2/p-%p-%m.profraw" python3 "$ROOT/build/lsp-trace-drive.py" "$fixture" "$1" -stdio > "$OUT/train.log" 2>&1 \
      || { tail -20 "$OUT/train.log" >&2; echo "LSP training session failed" >&2; return 1; }
  done
}
pgo_pipeline
