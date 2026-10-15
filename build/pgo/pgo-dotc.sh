#!/usr/bin/env bash
# Profile pipeline for scalino-dotc (stages: see pgo-lib.sh). The program is
# dotty.tools.dotc.Main from the NIR build/03 produced, linked the way build/03
# links it; the training workload is the instrumented dotc compiling the Scala
# Native tools sources (build/pgo/dotc-workload.sh; WORKLOADS="tools nir" to
# train on several). Packs pgo/dotc.*.gz.
#
# Env: BOOT (PGO capable linker, default dist/scalino-linkdriver),
#      OUT (default .build-work/pgo-dotc), SCALINO_LINK_MODE (default
#      release-fast), SKIP_TO=<stage>, WORKLOADS, EVAC (default 1: without
#      evacuation the heap of a dotc link fragments out).
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")/.."
export SCALINO_LINK_MODE="${SCALINO_LINK_MODE:-release-fast}"
source ./00-env.sh
source ./pgo/pgo-lib.sh
source ./link-lib.sh
NAME=dotc; BIN=dotty.tools.dotc.Main
BOOT="${BOOT:-$DIST/scalino-linkdriver}"
OUT="${OUT:-$WORK/pgo-dotc}"
WORKLOADS="${WORKLOADS:-tools}"
LLVM_BIN="${LLVM_BIN:-$(dirname "$(command -v llvm-profdata)")}"
NIR_OUT="$WORK/selfhost/nir-out"; NATIVELIBS_CP="$WORK/selfhost/nativelibs.cp"
[[ -d "$NIR_OUT" && -f "$NATIVELIBS_CP" ]] || { echo "run build/03 first (need $NIR_OUT)" >&2; exit 1; }
PROFILE_RT="$(pgo_profile_runtime)"
[[ -f "$PROFILE_RT" ]] || { echo "no libclang_rt.profile for $CLANG" >&2; exit 1; }

pgo_link() { # linker, dir, env...  -> dir/$BIN (same command as build/03)
  local linker="$1" dir="$2"; shift 2
  rm -rf "${dir:?}"; mkdir -p "$dir"
  local t0=$SECONDS extra=()
  [[ " $* " == *" SCALANATIVE_PGO_GEN=1 "* ]] && extra=(--linking "$PROFILE_RT")
  # GC_SYNC_TIMEOUT_MS=0: LLVM can sit in one huge instrumented function for
  # longer than the GC's 60s safepoint timeout
  LINK_ENV=("$@" SCALANATIVE_EVAC_ENABLE="${EVAC:-1}" SCALANATIVE_GC_SYNC_TIMEOUT_MS=0)
  LINKDRIVER="$linker" linkdriver_run \
    "$(to_native_path "$NIR_OUT")$CP_SEP$(cat "$NATIVELIBS_CP")" "$(to_native_path "$dir")" \
    "$BIN" --mode "$SCALINO_LINK_MODE" \
    --multithreading --no-incremental-compilation --embed-resources \
    "${extra[@]+"${extra[@]}"}" > "$dir.log" 2>&1 \
    || { tail -30 "$dir.log" >&2; echo "link into $dir failed" >&2; return 1; }
  echo "  $(basename "$dir"): $((SECONDS - t0))s"
}
pgo_train() { # dotc binary, raw dir
  local wl
  for wl in $WORKLOADS; do
    LLVM_PROFILE_FILE="$2/p-%p-%m.profraw" "$ROOT/build/pgo/dotc-workload.sh" "$1" "$wl" "$OUT/wl-out"
  done
}
pgo_pipeline
