#!/usr/bin/env bash
# Builds .deb and .rpm packages from dist/ via fpm (gem install fpm), as an alternative to the
# tarball+install.sh flow. Linux only: fpm's rpm backend needs rpmbuild, absent on macOS/Windows
# CI legs, so only the linux-x86_64/linux-arm64 release targets run it.
#
# Layout installed on the target (scalino resolves its dist root from its real path, so the
# /usr/bin symlinks are safe):
#   /usr/lib/scalino/{scalino,scalino-dotc,scalino-linkdriver,scalino-jslink,
#                     scalino-lsp,scalino-cs,jslibs.cp,java.base.jar,compiler.cp,nativelibs.cp,
#                     nscplugin.jar.txt,lib/,README.md,LICENSE,NOTICE}
#   /usr/bin/scalino     -> /usr/lib/scalino/scalino     (symlink)
#   /usr/bin/scalino-lsp -> /usr/lib/scalino/scalino-lsp (symlink)
#   /usr/bin/scalino-cs  -> /usr/lib/scalino/scalino-cs  (symlink)
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")/.."

tag="${1:?usage: 09-package-linux-native.sh <git-tag e.g. v1.2.3> <deb-arch e.g. amd64> <rpm-arch e.g. x86_64>}"
deb_arch="${2:?}"
rpm_arch="${3:?}"

command -v fpm >/dev/null 2>&1 || { echo "09-package-linux-native.sh: fpm not found -- gem install --no-document fpm" >&2; exit 1; }
command -v rpmbuild >/dev/null 2>&1 || { echo "09-package-linux-native.sh: rpmbuild not found -- apt-get install -y rpm" >&2; exit 1; }

# Debian policy: version must start with a digit. RPM: version can't contain
# '-'. Strip the leading 'v' and fold any pre-release hyphens (v1.2.3-rc1)
# into '~' (deb, sorts before the final release) / '_' (rpm).
raw="${tag#v}"
deb_version="${raw//-/~}"
rpm_version="${raw//-/_}"

work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT

prefix="usr/lib/scalino"
staging="$work/staging"
mkdir -p "$staging/$prefix" "$staging/usr/bin"
cp -a dist/. "$staging/$prefix/"
cp README.md LICENSE NOTICE "$staging/$prefix/"

ln -s "/$prefix/scalino" "$staging/usr/bin/scalino"
ln -s "/$prefix/scalino-lsp" "$staging/usr/bin/scalino-lsp"
ln -s "/$prefix/scalino-cs" "$staging/usr/bin/scalino-cs"

# Shell completions (generated into dist/completions/ by 07-build-scalino.sh), installed at each
# shell's standard system-wide path.
mkdir -p "$staging/usr/share/bash-completion/completions" \
         "$staging/usr/share/zsh/site-functions" \
         "$staging/usr/share/fish/vendor_completions.d"
cp "dist/completions/scalino.bash" "$staging/usr/share/bash-completion/completions/scalino"
cp "dist/completions/_scalino" "$staging/usr/share/zsh/site-functions/_scalino"
cp "dist/completions/scalino.fish" "$staging/usr/share/fish/vendor_completions.d/scalino.fish"

mkdir -p assets

fpm -s dir -C "$staging" \
  -t deb -n scalino -v "$deb_version" -a "$deb_arch" \
  --deb-suggests nodejs \
  --license "Apache-2.0" \
  --url "https://github.com/lolgab/scalino" \
  --description "Scala toolchain compiling straight to native binaries (no JVM at runtime)." \
  --depends clang \
  --package "assets/scalino_${deb_version}_${deb_arch}.deb" \
  usr

fpm -s dir -C "$staging" \
  -t rpm -n scalino -v "$rpm_version" -a "$rpm_arch" \
  --rpm-tag "Suggests: nodejs" \
  --license "Apache-2.0" \
  --url "https://github.com/lolgab/scalino" \
  --description "Scala toolchain compiling straight to native binaries (no JVM at runtime)." \
  --depends clang \
  --package "assets/scalino-${rpm_version}-1.${rpm_arch}.rpm" \
  usr

echo "OK: assets/*.deb and assets/*.rpm ready"
