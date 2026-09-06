#!/usr/bin/env bash
# Builds a static apt repo (dists/+pool/) and dnf repo (per-arch repodata/)
# from every .deb/.rpm ever published to this repo's GitHub Releases, signed
# with the GPG key in $APT_GPG_PRIVATE_KEY. Output goes to $1 (default:
# ./site), ready to hand to actions/upload-pages-artifact.
#
# Rebuilds the whole tree from scratch every run (via `gh release download`
# across ALL tags) rather than keeping incremental state, since a GitHub
# Pages deploy is itself a full-tree replace each time -- there's nowhere
# durable to keep a partial apt/dnf repo between runs anyway.
#
# Requires on PATH: gh (authenticated), gpg, dpkg-scanpackages (dpkg-dev),
# apt-ftparchive (apt-utils), createrepo_c, gzip.
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")/.."

repo="lolgab/scalino"
site="${1:-site}"
: "${APT_GPG_PRIVATE_KEY:?APT_GPG_PRIVATE_KEY env var (armored private key) must be set}"

work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT
export GNUPGHOME="$work/gnupg"
mkdir -m 700 "$GNUPGHOME"

echo "$APT_GPG_PRIVATE_KEY" | gpg --batch --import
key_id="$(gpg --list-secret-keys --with-colons | awk -F: '/^sec/ { print $5; exit }')"
gpg --export --armor "$key_id" > "$work/pubkey.asc"

# rpm's own --addsign shells out to gpg directly (not via this script's
# GNUPGHOME export) -- point it at the same key/homedir explicitly. Default
# %__gpg is /usr/bin/gpg2, which doesn't exist on Debian/Ubuntu (only
# /usr/bin/gpg) -- override it too.
cat > "$HOME/.rpmmacros" <<EOF
%_signature gpg
%_gpg_name $key_id
%_gpg_path $GNUPGHOME
%__gpg /usr/bin/gpg
EOF

rm -rf "$site"
mkdir -p "$site/apt/pool/main/s/scalino" "$site/dnf/x86_64" "$site/dnf/aarch64" "$work/raw"

echo "Fetching .deb/.rpm assets from every published release of $repo..."
tags="$(gh release list --repo "$repo" --json tagName -q '.[].tagName' || true)"
if [ -z "$tags" ]; then
  echo "No published releases yet -- publishing an EMPTY apt/dnf repo (valid, just has nothing installable)."
fi
for tag in $tags; do
  gh release download "$tag" --repo "$repo" --dir "$work/raw" --pattern '*.deb' --pattern '*.rpm' --clobber || true
done

find "$work/raw" -name '*.deb' -exec cp -t "$site/apt/pool/main/s/scalino/" {} + 2>/dev/null || true
find "$work/raw" -name '*-*.x86_64.rpm' -exec cp -t "$site/dnf/x86_64/" {} + 2>/dev/null || true
find "$work/raw" -name '*-*.aarch64.rpm' -exec cp -t "$site/dnf/aarch64/" {} + 2>/dev/null || true

# --- apt repo ---
(
  cd "$site/apt"
  for arch in amd64 arm64; do
    mkdir -p "dists/stable/main/binary-$arch"
    dpkg-scanpackages --arch "$arch" pool /dev/null > "dists/stable/main/binary-$arch/Packages" 2>/dev/null
    gzip -9c "dists/stable/main/binary-$arch/Packages" > "dists/stable/main/binary-$arch/Packages.gz"
  done
  apt-ftparchive \
    -o APT::FTPArchive::Release::Origin=scalino \
    -o APT::FTPArchive::Release::Label=scalino \
    -o APT::FTPArchive::Release::Suite=stable \
    -o APT::FTPArchive::Release::Codename=stable \
    -o APT::FTPArchive::Release::Architectures="amd64 arm64" \
    -o APT::FTPArchive::Release::Components=main \
    release dists/stable > dists/stable/Release
  gpg --batch --local-user "$key_id" --clearsign -o dists/stable/InRelease dists/stable/Release
  gpg --batch --local-user "$key_id" -abs -o dists/stable/Release.gpg dists/stable/Release
)

# --- dnf repo ---
for arch in x86_64 aarch64; do
  find "$site/dnf/$arch" -maxdepth 1 -name '*.rpm' -exec rpm --addsign {} +
  createrepo_c "$site/dnf/$arch"
  gpg --batch --local-user "$key_id" --detach-sign --armor -o "$site/dnf/$arch/repodata/repomd.xml.asc" "$site/dnf/$arch/repodata/repomd.xml"
done

cp "$work/pubkey.asc" "$site/scalino-signing-key.pub.asc"

cat > "$site/index.html" <<'HTML'
<!doctype html><meta charset="utf-8"><title>scalino package repos</title>
<h1>scalino apt/dnf repos</h1>
<h2>Debian/Ubuntu</h2>
<pre>
curl -fsSL https://lolgab.github.io/scalino/scalino-signing-key.pub.asc | sudo gpg --dearmor -o /usr/share/keyrings/scalino.gpg
echo "deb [signed-by=/usr/share/keyrings/scalino.gpg] https://lolgab.github.io/scalino/apt stable main" | sudo tee /etc/apt/sources.list.d/scalino.list
sudo apt update && sudo apt install scalino
</pre>
<h2>Fedora/RHEL/openSUSE</h2>
<pre>
sudo rpm --import https://lolgab.github.io/scalino/scalino-signing-key.pub.asc
sudo tee /etc/yum.repos.d/scalino.repo &lt;&lt;EOF
[scalino]
name=scalino
baseurl=https://lolgab.github.io/scalino/dnf/$basearch
enabled=1
gpgcheck=1
repo_gpgcheck=1
gpgkey=https://lolgab.github.io/scalino/scalino-signing-key.pub.asc
EOF
sudo dnf install scalino
</pre>
HTML

echo "OK: $site ready (apt/, dnf/, scalino-signing-key.pub.asc, index.html)"
