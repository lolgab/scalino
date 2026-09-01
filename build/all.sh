#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")"
./01-fetch-deps.sh
./02-build-java-base.sh
./03-build-dotc-native.sh
./04-build-linkdriver-native.sh
./06-package.sh
echo "OK: toolchain built in $(cd .. && pwd)/dist"
