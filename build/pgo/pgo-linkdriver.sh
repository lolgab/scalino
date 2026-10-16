#!/usr/bin/env bash
# Profile pipeline for scalino-linkdriver (stages: see pgo-lib.sh). The program
# is LinkDriver linked the way build/04 links it natively; the training
# workload is the instrumented linkdriver relinking LinkDriver itself.
# Packs pgo/linkdriver.*.gz. Output: $OUT/final/LinkDriver, dist/ untouched.
#
# Env: BOOT (linker with the PGO support of patches/scala-native-0080, default
#      dist/scalino-linkdriver), OUT (default .build-work/pgo-linkdriver),
#      SCALINO_LINK_MODE (default release-fast), SKIP_TO=<stage>.
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")/.."
# before 00-env.sh, which would default it to `debug`
export SCALINO_LINK_MODE="${SCALINO_LINK_MODE:-release-fast}"
source ./00-env.sh
source ./pgo/pgo-lib.sh
NAME=linkdriver; BIN=LinkDriver
BOOT="${BOOT:-$DIST/scalino-linkdriver}"
OUT="${OUT:-$WORK/pgo-linkdriver}"
LLVM_BIN="${LLVM_BIN:-$(dirname "$(command -v llvm-profdata)")}"
PROFILE_RT="$(pgo_profile_runtime)"
[[ -f "$PROFILE_RT" ]] || { echo "no libclang_rt.profile for $CLANG" >&2; exit 1; }

# link LinkDriver with linker $1 into dir $2 through build/04 (which must not
# apply its own PGO relink here: SCALINO_PGO=0)
pgo_link() {
  local linker="$1" dir="$2"; shift 2
  local t0=$SECONDS extra=""
  if [[ " $* " == *" SCALANATIVE_PGO_GEN=1 "* ]]; then
    extra="$PROFILE_RT"
    # Linux: see pgo-dotc.sh -- without it no .profraw is written
    [[ "$(uname -s)" == Darwin ]] || extra="$extra -Wl,-u,__llvm_profile_runtime"
  fi
  rm -rf "${dir:?}"
  env "$@" SCALINO_PGO=0 SCALINO_EXTRA_LINKING="$extra" \
    SCALINO_BOOTSTRAP_LINKDRIVER="$linker" SCALINO_BOOTSTRAP_EVAC=0 \
    SCALINO_LINK_WORK="$dir" SCALINO_LINK_NO_DIST=1 \
    bash "$ROOT/build/04-build-scalino-linkdriver.sh" > "$dir.log" 2>&1 \
    || { tail -30 "$dir.log" >&2; echo "link into $dir failed" >&2; return 1; }
  echo "  $(basename "$dir"): $((SECONDS - t0))s"
}
pgo_train() { # instrumented linkdriver, raw dir
  local dir="$OUT/train"
  rm -rf "${dir:?}"
  env LLVM_PROFILE_FILE="$2/p-%p-%m.profraw" SCALINO_PGO=0 \
    SCALINO_BOOTSTRAP_LINKDRIVER="$1" SCALINO_BOOTSTRAP_EVAC=0 \
    SCALINO_LINK_WORK="$dir" SCALINO_LINK_NO_DIST=1 \
    bash "$ROOT/build/04-build-scalino-linkdriver.sh" > "$dir.log" 2>&1 \
    || { tail -30 "$dir.log" >&2; echo "training run failed" >&2; return 1; }
}
pgo_pipeline
