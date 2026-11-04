#!/usr/bin/env bash
# Usage: all.sh [all|core|dotc|lsp]
#   all   everything, in order (default; what release.yml runs)
#   core  00b..06: everything up to dist/scalino-linkdriver and the packaged dist/
#   dotc  03, 03b, 07: scalino-dotc and the scalino CLI (needs core's output)
#   lsp   08: scalino-lsp, without its smoke test (needs core's output only)
# ci.yml runs dotc and lsp as parallel jobs on top of core's saved state
# (ci-state.sh); the LSP smoke test then runs via 08b-smoke-lsp.sh once both exist.
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")"
STAGE="${1:-all}"
case "$STAGE" in
  all|core|dotc|lsp) ;;
  *) echo "usage: $0 [all|core|dotc|lsp]" >&2; exit 2 ;;
esac

if [[ "$STAGE" == all || "$STAGE" == core ]]; then
./00b-setup-vendor.sh
./01a-publish-scala-native.sh
./01-fetch-deps.sh
./00c-fetch-scalajs-ir.sh
./02-build-java-base.sh
./02a-build-compiler-patched.sh
./02b-gen-megaphase-overrides.sh
# Must run before 03/03b/07/08, which link with dist/scalino-linkdriver.
./04-build-scalino-linkdriver.sh
# Must run right after 04 and before 07/08: it writes the dist-relative manifests that 08's
# smoke test needs (it drives the real dist/scalino through `setup-ide`).
./06-package.sh
fi

if [[ "$STAGE" == all || "$STAGE" == dotc ]]; then
./03-build-scalino-dotc.sh
./03b-build-scalalib-retained.sh
./07-build-scalino.sh
./04d-build-scalino-jslink.sh
fi

if [[ "$STAGE" == all || "$STAGE" == lsp ]]; then
  if [[ "$STAGE" == lsp ]]; then
    # dist/scalino doesn't exist in this stage's job; the smoke test runs later.
    SCALINO_SKIP_LSP_SMOKE=1 ./08-build-scalino-lsp.sh
  else
    ./08-build-scalino-lsp.sh
  fi
fi

[[ "$STAGE" == all ]] || { echo "OK: stage '$STAGE' done in $(cd .. && pwd)/dist"; exit 0; }
echo "OK: toolchain built in $(cd .. && pwd)/dist"
