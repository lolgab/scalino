# Shared by build/03, 07 and 08 (sourced, needs build/00-env.sh): links a
# program with the native $DIST/scalino-linkdriver (default) or, with
# SCALINO_JVM_LINK=1, with LinkDriver on a plain JVM.
#
# The native linkdriver is much faster to start but a dotc-sized link takes
# ~25 minutes and several GB under it (its own GC, compacting), which the CI
# runners do not afford; CI therefore sets SCALINO_JVM_LINK=1. The JVM link has
# no direct LLVM codegen (textual IR through clang), so the committed PGO
# profiles do not apply to it and pgo_prepare switches itself off.

jvm_link_enabled() { [[ -n "${SCALINO_JVM_LINK:-}" && "${SCALINO_JVM_LINK}" != 0 ]]; }

# linkdriver_run <classpath> <out dir> <main class> [LinkDriver flags...]
# Env settings for the run (PGO) go in the LINK_ENV array; LINKDRIVER overrides
# the native linker.
linkdriver_run() {
  local cp="$1" out="$2" main="$3"; shift 3
  if jvm_link_enabled; then
    # tools-patched-jvm.cp, not tools.cp: see 04-build-scalino-linkdriver.sh's DRIVER_CP.
    local driver_cp
    driver_cp="$(cat "$WORK/compiler.cp")$CP_SEP$(cat "$WORK/tools-patched-jvm.cp")$CP_SEP$(to_native_path "$WORK/driver-classes")"
    env ${LINK_ENV[@]+"${LINK_ENV[@]}"} "$JAVA" \
      -Xss64m -XX:MaxRAMPercentage="${SCALINO_LINK_RAM_PERCENT:-80}" \
      -cp "$driver_cp" \
      LinkDriver "$cp" "$out" "$main" "$CLANG" "$CLANGPP" info \
      --no-direct-codegen "$@"
  else
    # SCALANATIVE_EVAC_ENABLE=1 (compacting GC), for this command only: linking
    # dotc-sized programs fragments the Immix heap badly; without it the link
    # runs out of heap.
    env SCALANATIVE_EVAC_ENABLE=1 ${LINK_ENV[@]+"${LINK_ENV[@]}"} "${LINKDRIVER:-$DIST/scalino-linkdriver}" \
      "$cp" "$out" "$main" "$CLANG" "$CLANGPP" info "$@"
  fi
}

# Prerequisites of the chosen linker, for the scripts' up-front checks.
link_require() {
  if jvm_link_enabled; then
    [[ -f "$WORK/tools-patched-jvm.cp" && -d "$WORK/driver-classes" && -f "$DIST/tools-patched-jvm.jar" ]] \
      || { echo "SCALINO_JVM_LINK needs build/04a + 04 output (tools-patched-jvm.*, driver-classes)" >&2; exit 1; }
  else
    [[ -x "$DIST/scalino-linkdriver" ]] || { echo "missing $DIST/scalino-linkdriver -- run build/04-build-scalino-linkdriver.sh first" >&2; exit 1; }
  fi
}
