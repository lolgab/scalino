#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")"
./00b-setup-vendor.sh
./01-fetch-deps.sh
./01b-build-patched-javalib.sh
./02-build-java-base.sh
./02b-gen-megaphase-overrides.sh
# Must run before 03/03b/07/08: they all link against dist/scalino-linkdriver
# as their own link step.
./04a-patch-tools.sh
./04-build-scalino-linkdriver.sh
./03-build-scalino-dotc.sh
./03b-build-scalalib-retained.sh
./07-build-scalino.sh
./08-build-scalino-lsp.sh
# Must run LAST: it deletes the build-time-only intermediate (tools-patched.jar)
# that 04-build-scalino-linkdriver.sh reads up to this point -- see
# 06-package.sh's own comment on that rm.
./06-package.sh
echo "OK: toolchain built in $(cd .. && pwd)/dist"
