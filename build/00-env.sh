#!/usr/bin/env bash
# Sourced by every build/*.sh script. Not run directly.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
source "$ROOT/versions.env"

GRAAL_HOME="${GRAAL_HOME:-/Library/Java/JavaVirtualMachines/graalvm-25.jdk/Contents/Home}"
JAVA="$GRAAL_HOME/bin/java"
NATIVE_IMAGE="$GRAAL_HOME/bin/native-image"
JIMAGE="$GRAAL_HOME/bin/jimage"
JAR="$GRAAL_HOME/bin/jar"

DIST="$ROOT/dist"
WORK="$ROOT/.build-work"
mkdir -p "$DIST" "$WORK"

CLANG="$(command -v clang)"
CLANGPP="$(command -v clang++)"

require() { command -v "$1" >/dev/null 2>&1 || { echo "missing required tool: $1" >&2; exit 1; }; }
require cs
require "$JAVA"
require "$NATIVE_IMAGE"
require "$CLANG"
