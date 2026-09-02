#!/usr/bin/env bash
# Clones scala/scala3 at the pinned tag (if not already present) and applies
# every patch in patches/, in order. vendor/ is gitignored (a full scala3
# clone is ~140MB) -- this script is what "pulls and patches the scala/scala3
# code" from the original project brief actually refers to.
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")"
source ./00-env.sh

VENDOR="$ROOT/vendor/scala3"

if [[ ! -d "$VENDOR/.git" ]]; then
  git clone --depth 1 --branch "$SCALA_VERSION" https://github.com/scala/scala3.git "$VENDOR"
fi

cd "$VENDOR"
if [[ -n "$(git status --short)" ]]; then
  echo "vendor/scala3 has local changes already -- resetting before reapplying patches" >&2
  git checkout -q -- .
fi

for p in "$ROOT"/patches/*.patch; do
  [[ -e "$p" ]] || continue
  echo "applying $(basename "$p")"
  git apply "$p"
done

echo "OK: $VENDOR patched"
