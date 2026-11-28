#!/usr/bin/env bash
# Downloads every file `scalino sysroot build` is pinned to, by building every sysroot and every static
# library addon from scratch (nothing cached), from one source only:
#   mirror    scalino's copy of the files (the sysroot-sources GitHub release). What users get: a missing
#             file fails the run. Fix: build/14-mirror-sysroot-sources.sh.
#   upstream  the distributions' own URLs. A missing file is reported and the run still passes: upstreams
#             delete old versions, which is what the mirror is for. It tells us which pins can no longer be
#             re-fetched from upstream.
# Needs curl, tar (xz, zstd), clang, llvm-ar and ld.lld on PATH.
#
# Usage: tests/sysroot-sources/run.sh <mirror|upstream> [path/to/scalino]
set -uo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")/../.."

mode="${1:?usage: run.sh <mirror|upstream> [scalino]}"
scalino="${2:-dist/scalino}"
case "$mode" in
  mirror) export SCALINO_SYSROOT_UPSTREAM=off ;;
  upstream) export SCALINO_SYSROOT_MIRROR=off ;;
  *) echo "run.sh: mirror or upstream, not '$mode'" >&2; exit 2 ;;
esac

triples=(${TRIPLES:-x86_64-unknown-linux-musl aarch64-unknown-linux-musl
  x86_64-unknown-linux-gnu aarch64-unknown-linux-gnu
  x86_64-apple-darwin aarch64-apple-darwin
  x86_64-pc-windows-gnu aarch64-pc-windows-gnu})
# keep in step with Sysroot.Addons
addons=(openssl idn2 s2n curl utf8proc)

work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT
export SCALINO_SYSROOT_DIR="$work/root" SCALINO_SYSROOT_SOURCES="$work/sources"

failed=()
for t in "${triples[@]}"; do
  echo "== $t"
  if ! "$scalino" sysroot build "$t" > "$work/log" 2>&1; then
    tail -5 "$work/log"; failed+=("$t: $(grep -m1 '^scalino: ' "$work/log")"); continue
  fi
  for a in "${addons[@]}"; do
    # s2n-tls has no Windows port
    [[ "$a" == s2n && "$t" == *windows* ]] && continue
    if ! "$scalino" sysroot build "$t" --with "$a" > "$work/log" 2>&1; then
      tail -5 "$work/log"; failed+=("$t --with $a: $(grep -m1 '^scalino: ' "$work/log")")
    fi
  done
done

n="$(find "$work/sources" -type f | wc -l | tr -d ' ')"
echo "$n file(s) downloaded from the $mode"
if [ "${#failed[@]}" -eq 0 ]; then echo "OK: every sysroot and addon builds from the $mode"; exit 0; fi
level=error; [ "$mode" = upstream ] && level=warning
for f in "${failed[@]}"; do echo "::$level::sysroot sources ($mode): $f"; done
if [ -n "${GITHUB_STEP_SUMMARY:-}" ]; then
  { echo "### Sysroot sources: ${#failed[@]} build(s) failed from the $mode"; printf -- '- %s\n' "${failed[@]}"; } >> "$GITHUB_STEP_SUMMARY"
fi
[ "$mode" = upstream ] && exit 0
exit 1
