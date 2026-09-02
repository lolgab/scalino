#!/usr/bin/env bash
# Sourced by every build/*.sh script. Not run directly.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
source "$ROOT/versions.env"

GRAAL_HOME="${GRAAL_HOME:-/Library/Java/JavaVirtualMachines/graalvm-25.jdk/Contents/Home}"

# On Windows GraalVM's bin/ tools are .exe/.cmd, not extension-less -- and
# unlike a bare `command -v name` (which the shell's own PATHEXT-style
# lookup resolves), a full literal path like "$GRAAL_HOME/bin/java" is
# checked as-is, so the extension has to be found here explicitly.
resolve_tool() {
  local base="$1"
  for ext in "" ".exe" ".cmd"; do
    [[ -e "$base$ext" ]] && { echo "$base$ext"; return; }
  done
  echo "$base"
}
JAVA="$(resolve_tool "$GRAAL_HOME/bin/java")"
NATIVE_IMAGE="$(resolve_tool "$GRAAL_HOME/bin/native-image")"
JIMAGE="$(resolve_tool "$GRAAL_HOME/bin/jimage")"
JAR="$(resolve_tool "$GRAAL_HOME/bin/jar")"

DIST="$ROOT/dist"
WORK="$ROOT/.build-work"
mkdir -p "$DIST" "$WORK"

CLANG="$(command -v clang)"
CLANGPP="$(command -v clang++)"

# For a bare name, PATH-search via `command -v`. For a full path (as
# resolve_tool above returns), check existence directly instead -- under
# git-bash on Windows, `command -v` doesn't recognize a .cmd script given
# as a literal path as executable, even though it runs fine when invoked.
require() {
  if [[ "$1" == */* ]]; then
    [[ -e "$1" ]] || { echo "missing required tool: $1" >&2; exit 1; }
  else
    command -v "$1" >/dev/null 2>&1 || { echo "missing required tool: $1" >&2; exit 1; }
  fi
}
require cs
require "$JAVA"
require "$NATIVE_IMAGE"
require "$CLANG"
