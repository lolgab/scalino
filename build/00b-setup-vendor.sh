#!/usr/bin/env bash
# Clones scala/scala3 (at the tag SCALA_VERSION) and scala-native/scala-native (at the upstream main
# commit SCALA_NATIVE_REF) if not already present and applies every matching patch from patches/,
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
    if [[ "$ref" =~ ^[0-9a-f]{40}$ ]]; then
      # a commit, not a tag: GitHub serves any reachable sha to a shallow fetch
      git -c core.autocrlf=false init -q "$vendor"
      git -C "$vendor" remote add origin "$url"
      git -C "$vendor" -c core.autocrlf=false fetch -q --depth 1 origin "$ref"
      git -C "$vendor" -c core.autocrlf=false checkout -q FETCH_HEAD
    else
      git -c core.autocrlf=false clone --depth 1 --branch "$ref" "$url" "$vendor"
    fi
  fi

  cd "$vendor"
  # A pin that moved (versions.env bumped since this clone was made): move the clone to it. The
  # reset below discards the old patched tree, which is regenerated from patches/ anyway.
  at_ref=false
  if [[ "$ref" =~ ^[0-9a-f]{40}$ ]]; then
    [[ "$(git rev-parse HEAD)" == "$ref" ]] && at_ref=true
  else
    [[ "$(git describe --tags --exact-match 2>/dev/null || true)" == "$ref" ]] && at_ref=true
  fi
  if ! $at_ref; then
    echo "vendor/$name is not at $ref -- moving it" >&2
    git reset -q --hard HEAD
    git clean -q -fd
    if [[ "$ref" =~ ^[0-9a-f]{40}$ ]]; then
      git -c core.autocrlf=false fetch -q --depth 1 origin "$ref"
    else
      git -c core.autocrlf=false fetch -q --depth 1 origin "refs/tags/$ref:refs/tags/$ref"
    fi
    git -c core.autocrlf=false checkout -q "$( [[ "$ref" =~ ^[0-9a-f]{40}$ ]] && echo FETCH_HEAD || echo "tags/$ref" )"
  fi
  if [[ -n "$(git status --short)" ]]; then
    echo "vendor/$name has local changes already -- resetting before reapplying patches" >&2
    # `git checkout -q -- .` only restores MODIFIED tracked files -- it
    # doesn't remove files a previous patch run ADDED or restore files it
    # DELETED, so re-running this script against an already-patched clone
    # left those half-applied and every subsequent `git apply` failed with
    # "already exists"/"No such file or directory". A hard reset + clean
    # actually gets back to the pristine post-clone state.
    git reset -q --hard HEAD
    git clean -q -fd
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
setup_one scala-native https://github.com/scala-native/scala-native.git "$SCALA_NATIVE_REF" scala-native
