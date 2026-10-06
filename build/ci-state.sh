#!/usr/bin/env bash
# Hands the build's working state from one CI job to the next (ci.yml's
# core -> dotc/lsp split): `save <file.tgz>` / `restore <file.tgz>`.
#
# Everything is archived under its absolute path and extracted at the same
# one, because the *.cp manifests in .build-work hold absolute jar paths
# (coursier cache, ~/.ivy2/local). That works because every job of one OS
# checks out to the same workspace path.
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
usage() { echo "usage: $0 save|restore <file.tgz>" >&2; exit 2; }
[[ $# -eq 2 ]] || usage

case "$1" in
  save)
    # Relative to / so the archive has no leading slash (bsdtar and GNU tar
    # disagree on -P).
    ps=()
    for p in "$ROOT/dist" "$ROOT/.build-work" "$ROOT/vendor" "$HOME/.ivy2/local" \
             "$HOME/.cache/coursier" "$HOME/Library/Caches/Coursier"; do
      [[ -e "$p" ]] && ps+=("${p#/}")
    done
    out="$(cd "$(dirname "$2")" && pwd)/$(basename "$2")"
    # sbt's compile output for the vendored scala-native isn't needed
    # downstream: 01b/01c only hand on the jars they publish to ~/.ivy2/local.
    GZIP=-1 tar -C / -czf "$out" \
      --exclude="${ROOT#/}/vendor/scala-native/*/target" \
      --exclude="${ROOT#/}/vendor/scala-native/project/target" \
      "${ps[@]}"
    ls -lh "$out"
    ;;
  restore)
    tar -C / -xzf "$2"
    ;;
  *) usage ;;
esac
