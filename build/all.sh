#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")"
./00b-setup-vendor.sh
./01-fetch-deps.sh
./02-build-java-base.sh
./03a-patch-compiler.sh
./03-build-scalino-dotc.sh
./04a-patch-tools.sh
./04-build-scalino-linkdriver.sh
./06-package.sh
./07-build-scalino.sh
./08-build-scalino-lsp.sh
echo "OK: toolchain built in $(cd .. && pwd)/dist"
