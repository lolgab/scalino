# Shared by build/03 and 08 (sourced, needs build/00-env.sh): runs the compiler that turns
# dotc's own sources (and the LSP's) into NIR.
#
# Default: dotty.tools.dotc.Main from the published scala3-compiler jar on a JVM
# ($WORK/compiler.cp), with the checkout's nscplugin jar as -Xplugin.
#
# SCALINO_BOOTSTRAP_DOTC=release: the scalino-dotc of a released scalino instead (the latest
# release, or the tag in SCALINO_BOOTSTRAP_VERSION), downloaded once into $WORK/bootstrap.
# SCALINO_BOOTSTRAP_DOTC=<path>: that scalino-dotc binary.
#
# Trade-off, same as 04b-fetch-scalino-linkdriver.sh's: scalino-dotc has its nscplugin linked
# in (it ignores -Xplugin's jar), so the NIR comes from the release's nscplugin, not from the
# checkout's patches/scala-native-*. A change to nscplugin reaches scalino-dotc's own binary one
# release later. A change to the NIR format needs the JVM path until it is released.

bootstrap_dotc_enabled() { [[ -n "${SCALINO_BOOTSTRAP_DOTC:-}" && "${SCALINO_BOOTSTRAP_DOTC}" != 0 ]]; }

# Prints the target of this host the way release asset names spell it (macos-arm64, ...).
bootstrap_target() {
  local os arch
  case "$(uname -s)" in
    Darwin) os=macos ;;
    Linux)  os=linux ;;
    *) echo "no released scalino for $(uname -s)" >&2; return 1 ;;
  esac
  case "$(uname -m)" in
    arm64|aarch64) arch=arm64 ;;
    x86_64|amd64)  arch=x86_64 ;;
    *) echo "unsupported arch $(uname -m)" >&2; return 1 ;;
  esac
  echo "$os-$arch"
}

# Prints the path of a released scalino-dotc, downloading the release first if needed.
bootstrap_fetch_dotc() {
  local repo="${SCALINO_REPO:-lolgab/scalino}" version="${SCALINO_BOOTSTRAP_VERSION:-latest}"
  local target api json tag url cache
  target="$(bootstrap_target)" || return 1
  api="https://api.github.com/repos/$repo/releases"
  if [[ "$version" == latest ]]; then api="$api/latest"; else api="$api/tags/$version"; fi
  # A pinned tag that is already unpacked needs no network at all.
  if [[ "$version" != latest && -x "$WORK/bootstrap/$version-$target/scalino-dotc" ]]; then
    echo "$WORK/bootstrap/$version-$target/scalino-dotc"; return 0
  fi
  local auth=()
  [[ -n "${GITHUB_TOKEN:-}" ]] && auth=(-H "Authorization: Bearer $GITHUB_TOKEN")
  json="$(curl -fsSL ${auth[@]+"${auth[@]}"} "$api")" || { echo "cannot query $api" >&2; return 1; }
  tag="$(printf '%s' "$json" | grep -o '"tag_name": *"[^"]*"' | head -1 | sed -E 's/.*"([^"]+)"$/\1/')"
  url="$(printf '%s' "$json" | grep -o "\"browser_download_url\": *\"[^\"]*-${target}\\.tar\\.gz\"" | head -1 | sed -E 's/.*"(https[^"]+)"/\1/')"
  [[ -n "$tag" && -n "$url" ]] || { echo "no $target release asset found via $api" >&2; return 1; }
  cache="$WORK/bootstrap/$tag-$target"
  if [[ ! -x "$cache/scalino-dotc" ]]; then
    echo "== downloading $url" >&2
    rm -rf "$cache"; mkdir -p "$cache"
    curl -fsSL "$url" -o "$cache/release.tar.gz"
    curl -fsSL "$url.sha256" -o "$cache/release.tar.gz.sha256"
    local expected actual
    expected="$(awk '{print $1}' "$cache/release.tar.gz.sha256")"
    actual="$( (shasum -a 256 "$cache/release.tar.gz" 2>/dev/null || sha256sum "$cache/release.tar.gz") | awk '{print $1}')"
    [[ "$expected" == "$actual" ]] || { echo "sha256 mismatch for $url" >&2; return 1; }
    tar -xzf "$cache/release.tar.gz" -C "$cache" --strip-components=1 '*/scalino-dotc' >&2
    rm -f "$cache/release.tar.gz"
    [[ -f "$cache/scalino-dotc" ]] || { echo "release tarball has no scalino-dotc" >&2; return 1; }
    chmod +x "$cache/scalino-dotc"
  fi
  echo "$cache/scalino-dotc"
}

# JDK modules beyond java.base that dotc's own sources use (profile/ChromeTrace.scala:
# java.lang.management). User code only gets java.base.jar, so these are built here, for the
# bootstrap compile only, from the JDK the build already needs (02-build-java-base.sh).
BOOTSTRAP_JDK_MODULES=(java.management)

# Prints the -javabootclasspath for scalino-dotc: java.base.jar plus BOOTSTRAP_JDK_MODULES.
bootstrap_bootclasspath() {
  local cp="$DIST/java.base.jar" mod jar
  for mod in "${BOOTSTRAP_JDK_MODULES[@]}"; do
    jar="$WORK/bootstrap/$mod.jar"
    if [[ ! -f "$jar" ]]; then
      if [[ ! -d "$WORK/jrt-extract/$mod" ]]; then
        "$JIMAGE" extract --dir="$WORK/jrt-extract" "$JAVA_HOME/lib/modules" >&2
      fi
      mkdir -p "$WORK/bootstrap"
      (cd "$WORK/jrt-extract/$mod" && "$JAR" cf "$jar" .)
    fi
    cp="$cp$CP_SEP$(to_native_path "$jar")"
  done
  echo "$cp"
}

# dotc_compile <extra JVM classpath> <dotc args...>
# The extra classpath only matters on the JVM path (what dotc itself needs to run, beyond
# compiler.cp); pass "" for none.
dotc_compile() {
  local jvm_cp="$1"; shift
  if bootstrap_dotc_enabled; then
    local dotc="$SCALINO_BOOTSTRAP_DOTC"
    if [[ "$dotc" == release ]]; then dotc="$(bootstrap_fetch_dotc)" || return 1; fi
    echo "  compiler: $dotc" >&2
    # No JDK inside scalino-dotc: java.lang & co. come from 02-build-java-base.sh's jar.
    local bootcp
    bootcp="$(bootstrap_bootclasspath)" || return 1
    # Compacting GC, as the dotc daemon runs it (cli/DotcDaemon.scala): a dotc-sized compile
    # fragments the Immix heap, and the runners have little RAM to spare.
    SCALANATIVE_EVAC_ENABLE="${SCALANATIVE_EVAC_ENABLE:-1}" "$dotc" -javabootclasspath "$bootcp" "$@"
  else
    "$JAVA" -cp "$(cat "$WORK/compiler.cp")${jvm_cp:+$CP_SEP$jvm_cp}" dotty.tools.dotc.Main "$@"
  fi
}
