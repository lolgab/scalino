#!/usr/bin/env bash
# Binary compatibility with libraries published for Scala Native 0.5.12: links snunit 0.13.0 (compiled
# against the released 0.5.12 posixlib) and runs code that reaches socket.socket, connect, setsockopt,
# read, write and close through its NIR. Fails with "Unknown extern symbol ...posix.sys.socket$.socket"
# if an @name("scalanative_...") rename of an already-published extern slips in (upstream #5123 did).
# Needs network (Maven Central).
#
# Usage: tests/native-compat/run.sh [path-to-scalino]   (default: dist/scalino)
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
SCALINO="$(cd "$(dirname "${1:-$ROOT/dist/scalino}")" && pwd)/$(basename "${1:-$ROOT/dist/scalino}")"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT
fail() { echo "FAIL: $*" >&2; exit 1; }

mkdir "$WORK/snunit" && cd "$WORK/snunit"
# ControlClient is private[snunit]; status() connects to a unix socket that does not exist
cat > Main.scala <<'SCALA'
//> using platform native
//> using dep com.github.lolgab:snunit_native0.5_3:0.13.0
package snunit
@main def compat() = println("status=" + launcher.ControlClient("/nonexistent.sock").status())
SCALA
out="$("$SCALINO" run . -q 2>&1)" || fail "link/run failed:
$out"
[[ "$out" == *"status=None"* ]] || fail "unexpected output: $out"
echo "OK: library built with Scala Native 0.5.12 links and runs"
