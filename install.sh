#!/usr/bin/env bash
# One-line installer for scalino's `scalino` toolchain.
#
#   curl -fsSL https://raw.githubusercontent.com/lolgab/scalino/main/install.sh | bash
#
# Downloads the latest (or $SCALINO_VERSION-pinned) GitHub release tarball for
# the current OS/arch, verifies its sha256 checksum, unpacks it into a
# versioned directory under $SCALINO_INSTALL_DIR (default: ~/.local/share/scalino),
# and symlinks `scalino` and `scalino-lsp` into $SCALINO_BIN_DIR (default:
# ~/.local/bin) so editor LSP clients find scalino-lsp on PATH with no
# per-project config. Both resolve their own dist root from their real
# (symlink-resolved) path at runtime -- see cli/selfexe/*.scala -- so a
# symlink here is safe and the rest of dist/ (scalino-dotc,
# scalino-linkdriver, scalino-cs, lib/) never needs to move.
#
# Still required on top of this, on the running machine (not bundled --
# see docs/findings.md): `clang`/`clang++`, needed for every build, even a
# zero-dependency Hello World. Dependency resolution for `//> using dep`
# is self-contained -- scalino bundles its own renamed copy of coursier's
# `cs` launcher (dist/scalino-cs) so the real `cs`, if any, on the user's
# own PATH is never touched.
set -euo pipefail

repo="lolgab/scalino"
version="${SCALINO_VERSION:-latest}"
install_dir="${SCALINO_INSTALL_DIR:-$HOME/.local/share/scalino}"
bin_dir="${SCALINO_BIN_DIR:-$HOME/.local/bin}"

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

# Authenticated API calls get a much higher rate limit than anonymous ones --
# shared CI runner IPs routinely exhaust the anonymous quota (HTTP 403).
gh_token="${GITHUB_TOKEN:-${GH_TOKEN:-}}"

# GET an api.github.com URL, sending the token (if any); dies with a message
# that tells a rate limit (403/429) apart from "no such release" (404).
api_get() {
  local url="$1" out status
  out="$(mktemp)"
  local args=(-sSL -o "$out" -w '%{http_code}' -H 'Accept: application/vnd.github+json')
  [ -z "$gh_token" ] || args+=(-H "Authorization: Bearer $gh_token")
  status="$(curl "${args[@]}" "$url")" || { rm -f "$out"; die "failed to reach $url"; }
  if [ "$status" != "200" ]; then
    rm -f "$out"
    case "$status" in
      403|429)
        die "GitHub API rate limit hit (HTTP $status) querying $url -- set GITHUB_TOKEN (or GH_TOKEN) to authenticate, or pin SCALINO_VERSION (e.g. SCALINO_VERSION=v0.0.16) to skip the API entirely" ;;
      404)
        die "no release found at $url (HTTP 404) -- has a release been published yet?" ;;
      *)
        die "failed to query $url (HTTP $status)" ;;
    esac
  fi
  cat "$out"; rm -f "$out"
}

if [ "$version" != "latest" ]; then
  # Pinned: the release asset names are fixed by .github/workflows/release.yml
  # (scalino-<tag>-<target>.tar.gz, <tag> keeps its leading "v"), so
  # build the URL directly -- no api.github.com call, no rate limit.
  tag="$version"
  case "$tag" in v*) ;; *) tag="v$tag" ;; esac
  asset_url="https://github.com/$repo/releases/download/$tag/scalino-${tag}-${target}.tar.gz"
  echo "install.sh: using pinned $tag release for $target..."
else
  api_url="https://api.github.com/repos/$repo/releases/latest"
  echo "install.sh: looking up $version release for $target..."
  release_json="$(api_get "$api_url")"

  asset_url="$(printf '%s' "$release_json" | grep -o "\"browser_download_url\": *\"[^\"]*${target}\\.tar\\.gz\"" | head -1 | sed -E 's/.*"(https[^"]+)"/\1/')"
  tag="$(printf '%s' "$release_json" | grep -o '"tag_name": *"[^"]*"' | head -1 | sed -E 's/.*"([^"]+)"$/\1/')"

  [ -n "$asset_url" ] || die "no release asset found for target '$target' in $version -- check https://github.com/$repo/releases"
fi

dest_dir="$install_dir/$tag"
if [ -d "$dest_dir" ]; then
  echo "install.sh: $dest_dir already exists, reusing (delete it to force a fresh download)"
else
  work="$(mktemp -d)"
  trap 'rm -rf "$work"' EXIT
  tarball="$work/scalino.tar.gz"

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
  # (scalino-<version>-<target>); move whatever single
  # directory it contains into place under the tag, not that release name,
  # so re-running with a different $SCALINO_VERSION doesn't collide.
  inner="$(find "$extract_dir" -mindepth 1 -maxdepth 1 -type d)"
  [ -n "$inner" ] || die "unexpected tarball layout (no top-level directory)"
  mv "$inner" "$dest_dir"
fi

mkdir -p "$bin_dir"
ln -sf "$dest_dir/scalino" "$bin_dir/scalino"
chmod +x "$dest_dir/scalino"
ln -sf "$dest_dir/scalino-lsp" "$bin_dir/scalino-lsp"
chmod +x "$dest_dir/scalino-lsp"
ln -sf "$dest_dir/scalino-cs" "$bin_dir/scalino-cs"
chmod +x "$dest_dir/scalino-cs"

echo "install.sh: installed scalino $tag -> $bin_dir/scalino (dist: $dest_dir)"

case ":$PATH:" in
  *":$bin_dir:"*) ;;
  *) echo "install.sh: note -- $bin_dir is not on your PATH. Add it, e.g.: export PATH=\"$bin_dir:\$PATH\"" ;;
esac

if ! command -v clang >/dev/null 2>&1; then
  echo "install.sh: warning -- clang not found on PATH. scalino needs clang/clang++ to build anything, even a zero-dependency Hello World."
fi

# Pre-generated shell completion scripts ship in the tarball at
# dist/completions/ (see cli/ScalinoCli.scala's `completions` subcommand) --
# a manual install like this one has no single system-wide completions
# dir to drop them into, so just point the user at them (brew/apt/dnf/arch/
# nix installs place these automatically -- see packaging/*).
echo "install.sh: shell completions are in $dest_dir/completions/ -- e.g. source $dest_dir/completions/scalino.bash from your shell rc, or copy _scalino/scalino.fish to your zsh/fish completions dir"

echo "install.sh: try it: echo '@main def hello(): Unit = println(\"hello\")' > Hello.scala && scalino run Hello.scala"
