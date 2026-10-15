#!/usr/bin/env bash
# Cross-linking helpers (sourced; needs build/00-env.sh). The Intel macOS runner
# has 14GB, the arm64 one 7GB, which linking scalino-dotc/scalino-lsp does not
# fit in: with SCALINO_CROSS_TRIPLE=aarch64-apple-darwin, build/03 and build/08
# link their NIR once more for that target (clang cross-compiles, no emulation)
# and leave the binaries in dist-cross/<triple>/, where build/13-install-prebuilt.sh
# picks them up on the target machine. Only programs that do not embed LLVM can
# be cross-linked this way (scalino-linkdriver links the host's static libLLVM).

cross_enabled() { [[ -n "${SCALINO_CROSS_TRIPLE:-}" ]]; }

# cross_install <linked binary> <name in dist-cross/<triple>/>
cross_install() {
  local d="$ROOT/dist-cross/$SCALINO_CROSS_TRIPLE"
  mkdir -p "$d"
  rm -f "$d/$2"
  cp "$1" "$d/$2"
  chmod +x "$d/$2"
  echo "OK: $d/$2 ($(file -b "$d/$2" | cut -c1-60))"
}
