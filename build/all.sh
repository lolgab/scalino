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
# Must run here, right after 04 and before 07/08: it deletes tools-patched.jar
# (build-time-only, 04's own input, unneeded from here on) and writes the
# dist-relative compiler.cp/nativelibs.cp/nscplugin.jar.txt that 08's smoke
# test needs -- it drives the real dist/scalino binary through `setup-ide`
# against a real dist/ layout, exactly like an end user would.
./06-package.sh
./03-build-scalino-dotc.sh
./03b-build-scalalib-retained.sh
./07-build-scalino.sh
./08-build-scalino-lsp.sh
echo "OK: toolchain built in $(cd .. && pwd)/dist"
