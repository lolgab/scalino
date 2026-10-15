#!/usr/bin/env bash
# Installs cross-built binaries (build/cross-lib.sh) into dist/ on the target
# machine: build/13-install-prebuilt.sh <dir with scalino-dotc, scalino-lsp...>
set -euo pipefail
SRC="$(cd "${1:?usage: 13-install-prebuilt.sh <dir>}" && pwd)" # before the cd below
cd "$(dirname "${BASH_SOURCE[0]}")"
source ./00-env.sh
for f in "$SRC"/*; do
  name="$(basename "$f")"
  rm -f "$DIST/$name" # new inode, see 04-build-scalino-linkdriver.sh
  cp "$f" "$DIST/$name"
  chmod +x "$DIST/$name"
  # The cross linker's ad-hoc signature is not always accepted by the kernel on
  # arm64 (see 08-build-scalino-lsp.sh); sign again here, on the target.
  if command -v codesign > /dev/null 2>&1; then codesign -s - -f "$DIST/$name"; fi
  echo "installed $DIST/$name"
done
