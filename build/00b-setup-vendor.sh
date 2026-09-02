#!/usr/bin/env bash
# Clones scala/scala3 and scala-native/scala-native at their pinned versions
# (if not already present) and applies every matching patch from patches/,
# in order. vendor/ is gitignored (full clones are large) -- this script is
# what "pulls and patches the scala/scala3 [and scala-native] code" from the
# original project brief actually refers to.
#
# Patch files are prefixed by which vendored repo they target:
# patches/scala3-*.patch -> vendor/scala3, patches/scala-native-*.patch ->
# vendor/scala-native.
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")"
source ./00-env.sh

setup_one() {
  local name="$1" url="$2" ref="$3" prefix="$4"
  local vendor="$ROOT/vendor/$name"

  if [[ ! -d "$vendor/.git" ]]; then
    # -c core.autocrlf=false: our patches/*.patch files have LF line endings
    # (as committed); on Windows, git's default CRLF checkout conversion
    # would make the vendored source not match them byte-for-byte and
    # `git apply` fails with "patch does not apply".
    git -c core.autocrlf=false clone --depth 1 --branch "$ref" "$url" "$vendor"
  fi

  cd "$vendor"
  if [[ -n "$(git status --short)" ]]; then
    echo "vendor/$name has local changes already -- resetting before reapplying patches" >&2
    git checkout -q -- .
  fi

  for p in "$ROOT"/patches/"$prefix"-*.patch; do
    [[ -e "$p" ]] || continue
    echo "applying $(basename "$p") to vendor/$name"
    git apply "$p"
  done
  cd - > /dev/null

  echo "OK: $vendor patched"
}

setup_one scala3 https://github.com/scala/scala3.git "$SCALA_VERSION" scala3
setup_one scala-native https://github.com/scala-native/scala-native.git "v$SCALA_NATIVE_VERSION" scala-native
