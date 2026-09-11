#!/usr/bin/env bash
# Sourced by every build/*.sh script. Not run directly.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
source "$ROOT/versions.env"

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

# Real java -cp on Windows takes ";"-separated paths, and coursier's own
# --classpath output already uses it there (confirmed via CI: switching
# GenMiniPhaseOverrides's own classpath split from a hardcoded ":" to
# File.pathSeparator is what made it find any jars at all on Windows) --
# every *.cp manifest under $WORK/$DIST already uses this separator. Any
# build script joining/splitting them must use $CP_SEP too: a hardcoded
# ":" shatters a real Windows path at its own drive-letter colon
# ("C:\...") instead of finding a real separator.
case "$(uname -s)" in
  MINGW*|MSYS*|CYGWIN*) CP_SEP=';' ;;
  *) CP_SEP=':' ;;
esac

# $ROOT/$WORK/$DIST are bash/MSYS-style on Windows (e.g. "/d/a/scalino/...",
# from git-bash's own `pwd`) -- fine for bash's own builtins, but a raw path
# in that style, embedded directly (not via a coursier-written *.cp file --
# those are already native-Windows-style) into a classpath string passed to
# a real native Windows .exe, silently fails to resolve. Confirmed via CI:
# 04-build-scalino-linkdriver.sh's native-image invocation couldn't find
# "LinkDriver" at all despite it compiling cleanly one step earlier -- the
# compile step only ever WROTE to that same raw path (via `-d`), never had
# to actually read a classpath entry back from it, so it never surfaced
# there. Use this wherever a raw $WORK/$DIST-derived directory (not a
# coursier .cp file's already-correct content) is embedded in a classpath
# string a native .exe will actually read from.
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

# Same idea as to_native_path, but as a stream filter for a one-path-per-line
# file: build/selfhost/gen-*-file-list.sh build their whole 500+-file source
# list from raw `find $ROOT/...`/`echo $ROOT/...` (MSYS-style on Windows,
# since gen-file-list.sh never sources 00-env.sh -- it's meant to be
# reproducible standalone), then 03-build-scalino-dotc.sh/
# 08-build-scalino-lsp.sh feed the whole list to a real Windows dotc process
# via `@file-list.txt`. Confirmed via CI: dotc reported all ~550 files as
# "source file not found", each shown mangled to "\d\a\scalino\..." -- real
# Windows Java normalizes "/" to "\" but doesn't understand "/d" as a drive
# letter the way it understands "D:", so every path silently became relative
# to the current drive's root instead of absolute.
to_native_path_list() {
  case "$(uname -s)" in
    MINGW*|MSYS*|CYGWIN*)
      while IFS= read -r line; do to_native_path "$line"; echo; done
      ;;
    *) cat ;;
  esac
}

# Confirmed via Windows CI: scala-native's own Validator rejected this
# runner's real, working clang/clang++ with "does not exist" -- same root
# cause resolve_tool above already exists for GraalVM's tools, but a
# `[[ -e "$CLANG" ]] || CLANG="$CLANG.exe"` fallback (checking the bare
# `command -v clang` result first) did NOT fix it, confirmed via a second
# CI run with that exact fix in place -- whatever git-bash/MSYS resolves
# for the bare name "clang" apparently satisfies bash's own `-e` test even
# though it isn't what scala-native's Java-side Files.exists (a real Win32
# GetFileAttributes-style check) accepts as the same file. Sidestep the
# ambiguity entirely: ask `command -v` for the real, unambiguous ".exe" name
# directly on Windows, instead of inferring it after the fact.
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
require "$JAVAC"
require "$CLANG"
