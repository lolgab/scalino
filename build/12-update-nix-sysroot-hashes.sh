#!/usr/bin/env bash
# Regenerates packaging/nix/sysroot-sources.json: for each cross target, the
# Nix hash of the upstream archives `scalino sysroot build <triple> --with ...`
# downloads (the .deb/.apk/tarballs, see cli/SysrootBuild.scala). The flake
# fetches them in a fixed-output derivation (mkScalinoSysrootSources) and then
# builds the sysroot offline.
#
# The archive set is fixed by the pins in the scalino release's versions.env,
# so run this after a release, with that release's scalino (like
# 10-update-package-metadata.sh). Needs `nix`, curl, tar (xz, zstd), clang
# and llvm-ar (the s2n/curl addons are compiled) and ld.lld on PATH: with an ld.lld around,
# scalino doesn't also download one, which is the situation inside the Nix derivation.
# The clang must not inject its own libc's headers (a plain distro clang is fine; in
# Nix use llvmPackages.clangNoLibc, not pkgs.clang).
#
# Keep the --with list in step with allSysrootAddons in packaging/nix/flake.nix.
#
# Usage: build/12-update-nix-sysroot-hashes.sh vX.Y.Z [path/to/scalino]
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")/.."

tag="${1:?usage: 12-update-nix-sysroot-hashes.sh vX.Y.Z [scalino]}"
command -v nix >/dev/null || { echo "nix is required" >&2; exit 1; }
for tool in ld.lld clang llvm-ar; do
  command -v "$tool" >/dev/null || { echo "$tool must be on PATH (see header)" >&2; exit 1; }
done

work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT

scalino="${2:-}"
if [ -z "$scalino" ]; then
  case "$(uname -s)-$(uname -m)" in
    Linux-x86_64) target=linux-x86_64 ;;
    Linux-aarch64) target=linux-arm64 ;;
    Darwin-x86_64) target=macos-x86_64 ;;
    Darwin-arm64) target=macos-arm64 ;;
    *) echo "unsupported host" >&2; exit 1 ;;
  esac
  curl -fsSL "https://github.com/lolgab/scalino/releases/download/$tag/scalino-$tag-$target.tar.gz" | tar -xz -C "$work"
  scalino="$(find "$work" -maxdepth 2 -name scalino -type f | head -n1)"
fi

# TRIPLES="a b" limits the run (for testing); the committed file needs all.
triples=(${TRIPLES:-x86_64-unknown-linux-musl aarch64-unknown-linux-musl
  x86_64-unknown-linux-gnu aarch64-unknown-linux-gnu
  x86_64-apple-darwin aarch64-apple-darwin
  x86_64-pc-windows-gnu aarch64-pc-windows-gnu})

out=packaging/nix/sysroot-sources.json
{
  echo "{"
  last="${triples[${#triples[@]}-1]}"
  for t in "${triples[@]}"; do
    echo "== $t" >&2
    src="$work/src-$t"
    mkdir -p "$src"
    # upstream hosts (savannah's cgit in older releases) fail now and then;
    # finished downloads are cached in $src, so a retry only repeats the rest
    for attempt in 1 2 3 4; do
      SCALINO_SYSROOT_DIR="$work/root-$t" SCALINO_SYSROOT_SOURCES="$src" \
        "$scalino" sysroot build "$t" --with openssl --with idn2 --with s2n --with curl >&2 && break
      [ "$attempt" = 4 ] && { echo "sysroot build for $t failed" >&2; exit 1; }
      echo "retrying ($attempt)" >&2; sleep 20
    done
    find "$src" -name '*.part' -delete
    hash="$(nix hash path --type sha256 "$src")"
    rm -rf "$work/root-$t" "$src"
    sep=","; [ "$t" = "$last" ] && sep=""
    echo "  \"$t\": \"$hash\"$sep"
  done
  echo "}"
} > "$out.new"
mv "$out.new" "$out"
echo "wrote $out" >&2
