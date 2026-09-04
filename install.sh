#!/usr/bin/env bash
# One-line installer for scala-native-compiler's `sn-cli` toolchain.
#
#   curl -fsSL https://raw.githubusercontent.com/lolgab/snc/main/install.sh | bash
#
# Downloads the latest (or $SNC_VERSION-pinned) GitHub release tarball for
# the current OS/arch, verifies its sha256 checksum, unpacks it into a
# versioned directory under $SNC_INSTALL_DIR (default: ~/.local/share/snc),
# and symlinks `sn-cli` into $SNC_BIN_DIR (default: ~/.local/bin). sn-cli
# resolves its own dist root from its real (symlink-resolved) path at
# runtime -- see cli/selfexe/*.scala -- so a symlink here is safe and the
# rest of dist/ (dotc-native, linkdriver-native, lib/, dotty-lsp-native)
# never needs to move.
#
# Still required on top of this, on the running machine (not bundled --
# see docs/findings.md): `clang`/`clang++` (needed for every build, even a
# zero-dependency Hello World) and, only if you use `//> using dep`,
# coursier's `cs` launcher for dependency resolution.
set -euo pipefail

repo="lolgab/snc"
version="${SNC_VERSION:-latest}"
install_dir="${SNC_INSTALL_DIR:-$HOME/.local/share/snc}"
bin_dir="${SNC_BIN_DIR:-$HOME/.local/bin}"

die() { echo "install.sh: $*" >&2; exit 1; }

os="$(uname -s)"
arch="$(uname -m)"

case "$os" in
  Linux) plat="linux" ;;
  Darwin) plat="macos" ;;
  *) die "unsupported OS: $os (this toolchain also ships experimental Windows builds -- see the release page for those tarballs)" ;;
esac

case "$arch" in
  x86_64|amd64) plat_arch="x86_64" ;;
  arm64|aarch64) plat_arch="arm64" ;;
  *) die "unsupported architecture: $arch" ;;
esac

target="${plat}-${plat_arch}"

if [ "$version" = "latest" ]; then
  api_url="https://api.github.com/repos/$repo/releases/latest"
else
  api_url="https://api.github.com/repos/$repo/releases/tags/$version"
fi

echo "install.sh: looking up $version release for $target..."
release_json="$(curl -fsSL "$api_url")" || die "failed to query $api_url -- has a release been published yet?"

asset_url="$(printf '%s' "$release_json" | grep -o "\"browser_download_url\": *\"[^\"]*${target}\\.tar\\.gz\"" | head -1 | sed -E 's/.*"(https[^"]+)"/\1/')"
tag="$(printf '%s' "$release_json" | grep -o '"tag_name": *"[^"]*"' | head -1 | sed -E 's/.*"([^"]+)"$/\1/')"

[ -n "$asset_url" ] || die "no release asset found for target '$target' in $version -- check https://github.com/$repo/releases"

dest_dir="$install_dir/$tag"
if [ -d "$dest_dir" ]; then
  echo "install.sh: $dest_dir already exists, reusing (delete it to force a fresh download)"
else
  work="$(mktemp -d)"
  trap 'rm -rf "$work"' EXIT
  tarball="$work/snc.tar.gz"

  echo "install.sh: downloading $asset_url"
  curl -fsSL -o "$tarball" "$asset_url"

  checksum_url="${asset_url}.sha256"
  if curl -fsSL -o "$tarball.sha256" "$checksum_url" 2>/dev/null; then
    expected="$(cut -d' ' -f1 < "$tarball.sha256")"
    actual="$( (sha256sum "$tarball" 2>/dev/null || shasum -a 256 "$tarball") | cut -d' ' -f1)"
    [ "$expected" = "$actual" ] || die "checksum mismatch for $asset_url (expected $expected, got $actual)"
    echo "install.sh: checksum verified"
  else
    echo "install.sh: warning: no .sha256 asset found, skipping checksum verification"
  fi

  mkdir -p "$install_dir"
  extract_dir="$work/extracted"
  mkdir -p "$extract_dir"
  tar xzf "$tarball" -C "$extract_dir"
  # The tarball's top-level directory is named after the release
  # (scala-native-compiler-<version>-<target>); move whatever single
  # directory it contains into place under the tag, not that release name,
  # so re-running with a different $SNC_VERSION doesn't collide.
  inner="$(find "$extract_dir" -mindepth 1 -maxdepth 1 -type d)"
  [ -n "$inner" ] || die "unexpected tarball layout (no top-level directory)"
  mv "$inner" "$dest_dir"
fi

mkdir -p "$bin_dir"
ln -sf "$dest_dir/sn-cli" "$bin_dir/sn-cli"
chmod +x "$dest_dir/sn-cli"

echo "install.sh: installed sn-cli $tag -> $bin_dir/sn-cli (dist: $dest_dir)"

case ":$PATH:" in
  *":$bin_dir:"*) ;;
  *) echo "install.sh: note -- $bin_dir is not on your PATH. Add it, e.g.: export PATH=\"$bin_dir:\$PATH\"" ;;
esac

if ! command -v clang >/dev/null 2>&1; then
  echo "install.sh: warning -- clang not found on PATH. sn-cli needs clang/clang++ to build anything, even a zero-dependency Hello World."
fi

echo "install.sh: try it: echo '@main def hello(): Unit = println(\"hello\")' > Hello.scala && sn-cli run Hello.scala"
