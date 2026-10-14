#!/usr/bin/env bash
# Regenerates the committed PGO profiles (pgo/*.gz) of the performance critical
# binaries: usage `build/pgo/refresh-profiles.sh [linkdriver] [dotc] [lsp]`
# (default: all three). Needs a finished build in dist/ (build/all.sh) -- the
# linker there is the one that runs the instrumented and the profile guided
# links -- and llvm-profdata on PATH. Takes a couple of hours (the instrumented
# dotc/lsp links are ~20 min each); run it on the machine you cut releases
# from, after changes that move a lot of code, and commit pgo/. Stale profiles
# are harmless (functions whose CFG changed just lose their profile).
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")"
want=("$@"); [[ ${#want[@]} -gt 0 ]] || want=(linkdriver dotc lsp)
for n in "${want[@]}"; do
  echo "######## $n"
  ./pgo-"$n".sh
done
