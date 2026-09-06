#!/usr/bin/env bash
# Run by a maintainer (or a CI step) after a release has ACTUALLY been
# published (release.yml's tag push already ran) -- downloads that
# release's tarballs, computes their sha256, and rewrites the version+hash
# fields in the third-party packaging metadata that can't fetch them at
# eval/build time on its own:
#   packaging/arch/PKGBUILD        (pkgver, sha256sums_x86_64/_aarch64)
#   packaging/nix/sources.json     (version, linux-x86_64/linux-arm64 sha256)
#   packaging/homebrew/scalino.rb  (version, all 4 platform sha256)
#
# Usage: build/10-update-package-metadata.sh vX.Y.Z
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")/.."

tag="${1:?usage: 10-update-package-metadata.sh vX.Y.Z}"
version="${tag#v}"
repo="lolgab/scalino"
base_url="https://github.com/$repo/releases/download/$tag"

work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT

sha256_of() {
  local target="$1" out="$work/scalino-$tag-$target.tar.gz"
  curl -fsSL "$base_url/scalino-$tag-$target.tar.gz" -o "$out" >&2
  (sha256sum "$out" 2>/dev/null || shasum -a 256 "$out") | cut -d' ' -f1
}

echo "Fetching release assets for $tag..." >&2
sha_linux_x86_64="$(sha256_of linux-x86_64)"
sha_linux_arm64="$(sha256_of linux-arm64)"
sha_macos_x86_64="$(sha256_of macos-x86_64)"
sha_macos_arm64="$(sha256_of macos-arm64)"

# --- packaging/arch/PKGBUILD ---
sed -i.bak \
  -e "s/^pkgver=.*/pkgver=$version/" \
  -e "s/^sha256sums_x86_64=.*/sha256sums_x86_64=('$sha_linux_x86_64')/" \
  -e "s/^sha256sums_aarch64=.*/sha256sums_aarch64=('$sha_linux_arm64')/" \
  packaging/arch/PKGBUILD
rm -f packaging/arch/PKGBUILD.bak

# --- packaging/nix/sources.json ---
cat > packaging/nix/sources.json <<EOF
{
  "version": "$version",
  "linux-x86_64": { "sha256": "$sha_linux_x86_64" },
  "linux-arm64":  { "sha256": "$sha_linux_arm64" }
}
EOF

# --- packaging/homebrew/scalino.rb ---
sed -i.bak \
  -e "s/^  version \".*\"/  version \"$version\"/" \
  packaging/homebrew/scalino.rb
# The 4 sha256 lines appear in a fixed on_macos/on_intel,on_arm then
# on_linux/on_intel,on_arm order -- replace them positionally, 1st..4th.
python3 - "$sha_macos_x86_64" "$sha_macos_arm64" "$sha_linux_x86_64" "$sha_linux_arm64" <<'PYEOF'
import re, sys
path = "packaging/homebrew/scalino.rb"
shas = sys.argv[1:5]
text = open(path).read()
it = iter(shas)
text = re.sub(r'sha256 "[0-9a-f]{64}"', lambda _: f'sha256 "{next(it)}"', text)
open(path, "w").write(text)
PYEOF
rm -f packaging/homebrew/scalino.rb.bak

echo "OK: updated packaging/{arch/PKGBUILD,nix/sources.json,homebrew/scalino.rb} for $tag"
echo "  linux-x86_64:  $sha_linux_x86_64"
echo "  linux-arm64:   $sha_linux_arm64"
echo "  macos-x86_64:  $sha_macos_x86_64"
echo "  macos-arm64:   $sha_macos_arm64"
echo "Review the diff, then commit."
