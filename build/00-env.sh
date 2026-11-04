#!/usr/bin/env bash
# Sourced by every build/*.sh script. Not run directly.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
source "$ROOT/versions.env"

# Derived from the git tag (not pinned in versions.env). release.yml tags as vX.Y.Z,
# matching build/10-update-package-metadata.sh's "${tag#v}".
SCALINO_VERSION="$(git -C "$ROOT" describe --tags --always --dirty 2>/dev/null || echo dev)"
SCALINO_VERSION="${SCALINO_VERSION#v}"

# On Windows OpenJDK's bin/ tools are .exe/.cmd, not extension-less -- and
# unlike a bare `command -v name` (which the shell's own PATHEXT-style
# lookup resolves), a full literal path like "$JAVA_HOME/bin/java" is
# checked as-is, so the extension has to be found here explicitly.
resolve_tool() {
  local base="$1"
  for ext in "" ".exe" ".cmd"; do
    [[ -e "$base$ext" ]] && { echo "$base$ext"; return; }
  done
  echo "$base"
}

if [[ -z "${JAVA_HOME:-}" ]]; then
  if command -v /usr/libexec/java_home >/dev/null 2>&1; then
    JAVA_HOME="$(/usr/libexec/java_home 2>/dev/null || true)"
  fi
fi
JAVA="$(resolve_tool "${JAVA_HOME:-}/bin/java")"
JAVAC="$(resolve_tool "$JAVA_HOME/bin/javac")"
JIMAGE="$(resolve_tool "$JAVA_HOME/bin/jimage")"
JAR="$(resolve_tool "$JAVA_HOME/bin/jar")"

DIST="$ROOT/dist"
WORK="$ROOT/.build-work"
mkdir -p "$DIST" "$WORK"

# Classpath separator: java -cp and coursier's --classpath output use ";" on Windows, so every
# *.cp manifest does too. Scripts that join/split them must use $CP_SEP; a hardcoded ":" splits
# at the drive letter ("C:\...").
case "$(uname -s)" in
  MINGW*|MSYS*|CYGWIN*) CP_SEP=';' ;;
  *) CP_SEP=':' ;;
esac

# $ROOT/$WORK/$DIST are MSYS-style on Windows ("/d/a/scalino/..."), which a native .exe
# silently fails to resolve in a classpath string (the linkdriver step couldn't find
# LinkDriver). Use this for any raw $WORK/$DIST-derived directory put on a classpath; the
# coursier-written *.cp files are already native-style.
to_native_path() {
  case "$(uname -s)" in
    MINGW*|MSYS*|CYGWIN*)
      if [[ "$1" =~ ^/([a-zA-Z])/(.*)$ ]]; then
        printf '%s:/%s' "${BASH_REMATCH[1]^^}" "${BASH_REMATCH[2]}"
      else
        printf '%s' "$1"
      fi
      ;;
    *) printf '%s' "$1" ;;
  esac
}

# Stream filter version of to_native_path for a one-path-per-line file. gen-*file-list.sh
# emit MSYS-style paths (they don't source 00-env.sh), and Windows dotc reads them via
# `@file-list.txt` as "\d\a\..." relative to the drive root, reporting every file as not found.
to_native_path_list() {
  case "$(uname -s)" in
    MINGW*|MSYS*|CYGWIN*)
      while IFS= read -r line; do to_native_path "$line"; echo; done
      ;;
    *) cat ;;
  esac
}

# On Windows ask for the ".exe" name directly: scala-native's Validator rejects the bare
# "clang" that git-bash resolves ("does not exist"), and a `[[ -e ]]` + ".exe" fallback did not
# help because bash's -e accepts the bare name.
case "$(uname -s)" in
  MINGW*|MSYS*|CYGWIN*)
    CLANG="$(command -v clang.exe || command -v clang)"
    CLANGPP="$(command -v clang++.exe || command -v clang++)"
    ;;
  *)
    CLANG="$(command -v clang)"
    CLANGPP="$(command -v clang++)"
    ;;
esac

# Bare names are PATH-searched; full paths (from resolve_tool) are checked directly, because
# git-bash's `command -v` doesn't treat a literal .cmd path as executable.
require() {
  if [[ "$1" == */* ]]; then
    [[ -e "$1" ]] || { echo "missing required tool: $1" >&2; exit 1; }
  else
    command -v "$1" >/dev/null 2>&1 || { echo "missing required tool: $1" >&2; exit 1; }
  fi
}
require cs
require "$JAVA"
require "$JAVAC"
require "$CLANG"

# Scala Native link mode for the scalino-dotc / linkdriver / lsp self-links:
# `debug` (fast to link) for local builds and CI; the release workflow (tags)
# sets `release-fast`. The scalino CLI link (07) uses release-size when this is
# anything but `debug`.
export SCALINO_LINK_MODE="${SCALINO_LINK_MODE:-debug}"

# LTO for the scalino-dotc / linkdriver / lsp self-links (--lto <none|thin|full>).
# thin only affects the clang-compiled C/C++ runtime and .ll fallback partitions:
# direct-codegen objects are native, and PGO is applied there, so PGO is unaffected.
# Only linux-x86_64 defaults to thin; everywhere else it is off until the
# failures below are fixed (override with SCALINO_LINK_LTO=thin|full|none):
#  - macOS: ld64 asserts on thin-LTO'd libunwind.cpp (alias/target in different
#    sections), and full LTO takes 1.5-3h for dotc/lsp in CI.
#  - arm64: the LTO'd binaries are broken at runtime (linux-arm64 scalino-dotc
#    dies with "terminate called after throwing ExceptionWrapper"; macOS arm64
#    linkdriver reads .nir files as empty: BufferUnderflowException in
#    Prelude.readNIRVersion).
if [[ -z "${SCALINO_LINK_LTO:-}" ]]; then
  if [[ "$(uname -s)" == Linux && "$(uname -m)" == x86_64 ]]; then
    SCALINO_LINK_LTO=thin
  else
    SCALINO_LINK_LTO=none
  fi
fi
export SCALINO_LINK_LTO
