#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")"
./00b-setup-vendor.sh
./01-fetch-deps.sh
./02-build-java-base.sh
./03a-patch-compiler.sh
./03-build-scalino-dotc.sh
./03b-build-scalalib-retained.sh
./04a-patch-tools.sh
./04-build-scalino-linkdriver.sh
./07-build-scalino.sh
./08-build-scalino-lsp.sh
# Must run LAST: it deletes the build-time-only intermediates
# (scala3-compiler-patched.jar, tools-patched.jar) that 08 (and, if it ever
# needs a recompile, 07) still read up to this point -- see 06-package.sh's
# own comment on that rm.
./06-package.sh
echo "OK: toolchain built in $(cd .. && pwd)/dist"
