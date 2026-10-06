#!/usr/bin/env bash
# Smoke test for dist/scalino-lsp (built by 08-build-scalino-lsp.sh): drives a
# real-editor-shaped LSP session against build/lsp-trace-fixture. Needs
# dist/scalino (07) only to regenerate the fixture's .scalino-build/scalino-lsp.json.
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")"
source ./00-env.sh

echo "== smoke test: build/lsp-trace-drive.py against build/lsp-trace-fixture =="
[[ -x "$DIST/scalino-lsp" ]] || { echo "missing $DIST/scalino-lsp -- run build/08-build-scalino-lsp.sh first" >&2; exit 1; }
[[ -x "$DIST/scalino" ]] || { echo "missing $DIST/scalino -- run build/07-build-scalino.sh first (needed only to regenerate .scalino-build/scalino-lsp.json)" >&2; exit 1; }
FIXTURE="$ROOT/build/lsp-trace-fixture"
(cd "$FIXTURE" && "$DIST/scalino" setup-ide Model.scala Greeter.scala Main.scala >/dev/null)
python3 "$ROOT/build/lsp-trace-drive.py" "$FIXTURE" "$DIST/scalino-lsp" -stdio
echo "OK: smoke test passed (scalino-lsp handled every endpoint lsp-trace-drive.py exercises)"
