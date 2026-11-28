#!/usr/bin/env bash
# Uploads every file `scalino sysroot build` downloads to the `sysroot-sources` GitHub release, which
# cli/SysrootBuild.scala's `download` tries before the upstream URL. Distributions delete a package
# version when the next one lands (Alpine keeps one per branch), so a release that only knows the
# upstream URL stops building sysroots some weeks later.
#
# Run it after changing a pin in versions.env and BEFORE pushing: CI's cross job builds every sysroot
# and addon from the mirror alone and fails on a missing file. Assets are only ever added, since older
# scalino releases still ask for the old files.
#
# The files are found by building every sysroot and addon with the given scalino (dist/scalino by
# default) into a scratch directory, so this needs what those builds need: curl, tar (xz, zstd), clang,
# llvm-ar, ld.lld. Files the upstream no longer has can be put in $SCALINO_SYSROOT_SOURCES beforehand
# (they are checked against the pinned sha256 like a download). Needs `gh` with write access.
#
# Not mirrored: llvm-mingw's tarballs for other hosts than this one (the lld and the Windows-hosted
# clang). They are GitHub release assets upstream, which stay.
#
# Usage: build/14-mirror-sysroot-sources.sh [path/to/scalino]
#        DRY_RUN=1 ... lists what would be uploaded.
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")/.."

scalino="${1:-dist/scalino}"
repo="${SCALINO_REPO:-lolgab/scalino}"
tag=sysroot-sources
triples=(${TRIPLES:-x86_64-unknown-linux-musl aarch64-unknown-linux-musl
  x86_64-unknown-linux-gnu aarch64-unknown-linux-gnu
  x86_64-apple-darwin aarch64-apple-darwin
  x86_64-pc-windows-gnu aarch64-pc-windows-gnu})
# keep in step with Sysroot.Addons
addons=(openssl idn2 s2n curl utf8proc)

work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT
src="${SCALINO_SYSROOT_SOURCES:-$work/sources}"
mkdir -p "$src"

with=()
for a in "${addons[@]}"; do with+=(--with "$a"); done
for t in "${triples[@]}"; do
  echo "== $t" >&2
  SCALINO_SYSROOT_DIR="$work/root" SCALINO_SYSROOT_SOURCES="$src" "$scalino" sysroot build "$t" "${with[@]}" >&2
done
find "$src" -name '*.part' -delete

# the asset name of a file: SysrootBuild.mirrorName
mirror_name() { printf '%s' "$1" | LC_ALL=C sed 's/[^A-Za-z0-9._-]/-/g'; }

if ! gh release view "$tag" -R "$repo" >/dev/null 2>&1; then
  if [ -n "${DRY_RUN:-}" ]; then echo "would create the $tag release" >&2
  else
    gh release create "$tag" -R "$repo" --latest=false --title "Sysroot sources" --notes \
"Not a scalino release. These are unmodified copies of the upstream files \`scalino sysroot build\` assembles cross-compilation sysroots and static C libraries from (Debian, Alpine and MSYS2 packages, Homebrew bottles, source tarballs), kept here because distributions delete a package version when the next one comes out.

scalino checks each file against a sha256 pinned in its [versions.env](https://github.com/$repo/blob/main/versions.env), which also names the upstream version of each. Every file is under its own licence, not scalino's. The source of a binary package is the upstream distribution's source package of the same version: \`https://snapshot.debian.org\` (Debian), \`https://git.alpinelinux.org/aports\` (Alpine), \`https://github.com/msys2/MINGW-packages\` (MSYS2), \`https://github.com/Homebrew/homebrew-core\` (Homebrew)."
  fi
  existing=""
else
  existing="$(gh release view "$tag" -R "$repo" --json assets -q '.assets[].name')"
fi

stage="$work/stage"
mkdir -p "$stage"
n=0
for f in "$src"/*; do
  [ -f "$f" ] || continue
  name="$(mirror_name "$(basename "$f")")"
  if printf '%s\n' "$existing" | grep -qxF "$name"; then continue; fi
  cp "$f" "$stage/$name"
  n=$((n + 1))
done
echo "$n file(s) to upload to $repo@$tag" >&2
[ "$n" -gt 0 ] || exit 0
ls -l "$stage" >&2
[ -z "${DRY_RUN:-}" ] || exit 0
# a few at a time: one request with every file times out
find "$stage" -type f | sort | xargs -n 8 gh release upload "$tag" -R "$repo"
echo "OK: uploaded $n file(s)" >&2
