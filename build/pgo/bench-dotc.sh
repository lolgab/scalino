#!/usr/bin/env bash
# A/B timing of two dotc binaries on a workload of dotc-workload.sh, alternating
# (e.g. a build with SCALINO_PGO=0 against one with PGO).
# usage: bench-dotc.sh <dotc A> <dotc B> [runs=3] [workload=tools]
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")/.."
source ./00-env.sh
A="$1"; B="$2"; runs="${3:-3}"; wl="${4:-tools}"
for r in $(seq 1 "$runs"); do
  for v in A B; do
    bin="$A"; [[ "$v" == B ]] && bin="$B"
    s=$(python3 -c 'import time;print(time.time())')
    "$ROOT/build/pgo/dotc-workload.sh" "$bin" "$wl" "$WORK/pgo-bench-out"
    e=$(python3 -c 'import time;print(time.time())')
    echo "$v $(python3 -c "print(f'{$e-$s:.2f}s')")"
  done
done
