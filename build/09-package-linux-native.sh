#!/usr/bin/env bash
# Builds .deb and .rpm packages from dist/ via fpm (gem install fpm), for
# native apt/dnf installs as an alternative to the tarball+install.sh flow.
# Linux only -- fpm's rpm backend shells out to rpmbuild, absent on
# macOS/Windows CI legs, so this script is only invoked for the
# linux-x86_64/linux-arm64 release targets.
#
# Layout installed on the target machine (mirrors 06-package.sh's
# self-contained, relocatable dist/ -- scalino resolves its dist root from
# its own real path, so the /usr/bin symlinks below are safe and the rest
# of /usr/lib/scalino/ never needs to move):
#   /usr/lib/scalino/{scalino,scalino-dotc,scalino-linkdriver,scalino-lsp,
#                     java.base.jar,compiler.cp,nativelibs.cp,
#                     nscplugin.jar.txt,lib/,README.md,LICENSE,NOTICE}
#   /usr/bin/scalino     -> /usr/lib/scalino/scalino     (symlink)
#   /usr/bin/scalino-lsp -> /usr/lib/scalino/scalino-lsp (symlink)
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

mkdir -p assets

fpm -s dir -C "$staging" \
  -t deb -n scalino -v "$deb_version" -a "$deb_arch" \
  --license "Apache-2.0" \
  --url "https://github.com/lolgab/scalino" \
  --description "Scala toolchain compiling straight to native binaries (no JVM at runtime)." \
  --depends clang \
  --package "assets/scalino_${deb_version}_${deb_arch}.deb" \
  usr

fpm -s dir -C "$staging" \
  -t rpm -n scalino -v "$rpm_version" -a "$rpm_arch" \
  --license "Apache-2.0" \
  --url "https://github.com/lolgab/scalino" \
  --description "Scala toolchain compiling straight to native binaries (no JVM at runtime)." \
  --depends clang \
  --package "assets/scalino-${rpm_version}-1.${rpm_arch}.rpm" \
  usr

echo "OK: assets/*.deb and assets/*.rpm ready"
