#!/usr/bin/env bash
# Shared helpers for profile guided builds (see docs/pgo.md). Sourced, needs
# build/00-env.sh first ($ROOT, $WORK, $CLANG, $SCALINO_LINK_MODE).
#
# Release builds (build/03, 04, 08) only *consume* the profiles committed in
# $ROOT/pgo (pgo_prepare); build/pgo/refresh-profiles.sh regenerates them with
# the multi-stage pipeline below (pgo_pipeline).

PGO_DIR="$ROOT/pgo"

# pgo_prepare <name>: sets PGO_ENV (array) to the env settings that make the
# linker use pgo/<name>.* -- empty when PGO is off (SCALINO_PGO=0), the mode is
# not a release mode, or there is no profile for <name>.
pgo_prepare() {
  local name="$1"
  PGO_ENV=()
  [[ "${SCALINO_PGO:-1}" != 0 ]] || return 0
  case "${SCALINO_LINK_MODE:-debug}" in release-*) ;; *) return 0 ;; esac
  local d="$WORK/pgo-profiles"
  mkdir -p "$d"
  # LLVM's pgo-instr-use only reads the *indexed* profile format (given text it
  # fails silently inside the linker), and that format is not readable by an
  # older LLVM than the one that wrote it: the committed text profile is
  # converted here by the llvm-profdata of the LLVM this build links against.
  local pd
  pd="$(command -v llvm-profdata 2>/dev/null || true)"
  if [[ -z "$pd" ]] && command -v llvm-config > /dev/null 2>&1; then pd="$(llvm-config --bindir)/llvm-profdata"; fi
  if [[ -f "$PGO_DIR/$name.llvm.gz" ]]; then
    if [[ -x "$pd" ]] && gzip -dc "$PGO_DIR/$name.llvm.gz" > "$d/$name.llvm.proftext" \
        && "$pd" merge "$d/$name.llvm.proftext" -o "$d/$name.llvm.profdata" 2> "$d/$name.llvm.merge.log"; then
      PGO_ENV+=("SCALANATIVE_PGO_USE=$d/$name.llvm.profdata")
    else
      echo "  PGO: cannot convert pgo/$name.llvm.gz (llvm-profdata: ${pd:-not found}), LLVM part off" >&2
    fi
  fi
  # the Interflow profile exists for two-round profiles only (see pgo_pipeline)
  if [[ -f "$PGO_DIR/$name.interflow.gz" ]]; then
    gzip -dc "$PGO_DIR/$name.interflow.gz" > "$d/$name.interflow.txt"
    PGO_ENV+=("SCALANATIVE_PGO_PROFILE=$d/$name.interflow.txt")
  fi
  if [[ ${#PGO_ENV[@]} -gt 0 ]]; then
    echo "  PGO: using $PGO_DIR/$name.* (${#PGO_ENV[@]} setting(s))"
  else
    echo "  PGO: no usable profile for $name in $PGO_DIR, linking without"
  fi
}

# pgo_pack <name> <show-format profile of the unguided build, or "" for none>
# <merged .profdata of the build the final one repeats>: writes the committed form.
#   <name>.interflow.gz  `<entry count>\t<symbol>` per function (Interflow)
#   <name>.llvm.gz       llvm-profdata text profile (version independent, unlike
#                        the raw/indexed formats; read by the linker's own LLVM)
pgo_pack() {
  local name="$1" show="$2" profdata="$3"
  mkdir -p "$PGO_DIR"
  rm -f "$PGO_DIR/$name.interflow.gz"
  if [[ -n "$show" ]]; then
  python3 - "$show" <<'PY' | gzip -9 -n > "$PGO_DIR/$name.interflow.gz"
import re, sys
name = None
for l in open(sys.argv[1]):
    if l.startswith('  ') and not l.startswith('   ') and l.rstrip().endswith(':'):
        name = l.strip()[:-1]
    elif name and 'Block counts: [' in l:
        sys.stdout.write("%s\t%s\n" % (re.search(r'\[(\d+)', l).group(1), name))
        name = None
PY
  fi
  "$LLVM_BIN/llvm-profdata" merge --text "$profdata" -o - | gzip -9 -n > "$PGO_DIR/$name.llvm.gz"
  echo "  packed $PGO_DIR/$name.* ($(du -ch "$PGO_DIR/$name".*.gz | tail -1 | cut -f1))"
}

# compiler-rt profile runtime of $CLANG, linked into instrumented binaries
pgo_profile_runtime() {
  local res
  res="$("$CLANG" --print-resource-dir)"
  case "$(uname -s)" in
    Darwin) ls "$res"/lib/darwin/libclang_rt.profile_osx.a ;;
    *) ls "$res"/lib/*/libclang_rt.profile*"$(uname -m)"*.a "$res"/lib/"$(uname -m)"-*/libclang_rt.profile.a 2>/dev/null | head -1 ;;
  esac
}

pgo_merge() { # <raw dir> <name under $OUT>
  rm -f "$OUT/$2.profdata"
  "$LLVM_BIN/llvm-profdata" merge "$1"/*.profraw -o "$OUT/$2.profdata"
  "$LLVM_BIN/llvm-profdata" show --all-functions --counts "$OUT/$2.profdata" > "$OUT/$2.txt"
  echo "  $2: $(grep -c 'Hash:' "$OUT/$2.txt") functions"
}

# pgo_pipeline: the multi-stage build, needs these set by the caller:
#   NAME   profile name (linkdriver|dotc|lsp)      BIN   file name of the linked binary
#   OUT    work dir                                BOOT  PGO capable linker
#   functions: pgo_link <linker> <dir> [ENV=val...]  -> links the program into <dir>/$BIN
#              pgo_train <binary> <raw dir>          -> runs the workload, writes .profraw there
#   SKIP_TO=<gen1|train1|gen2|train2|final> resumes a stage, SKIP_FINAL=1 omits the last one
#
#   gen1   BOOT links the program with SCALANATIVE_PGO_GEN=1 (LLVM instruments every
#          function, entry-first + indirect call value profiling)
#   train1 the instrumented binary runs the workload -> prof1 (function entry counts)
#   gen2   BOOT links again, with Interflow guided by prof1 (inlines hot calls more,
#          cold ones less, wide polymorphic inlining/specialization at very hot
#          sites) and instrumented again: the LLVM profile has to be taken on the
#          IR of the *final* NIR, which the guidance changed
#   train2 -> prof2; the profiles are packed into $ROOT/pgo
#   final  BOOT links with the packed profiles, exactly like a release build does
#
# PGO_STAGES=1 skips round two: the LLVM profile of the unguided build is used
# directly and Interflow is not guided (LLVM PGO alone is about two thirds of the
# gain, and it saves two links -- worth it for programs whose links take hours).
pgo_pipeline() {
  local skip="${SKIP_TO:-gen1}" on=0
  mkdir -p "$OUT"
  stage() { [[ "$on" == 1 || "$skip" == "$1" ]] && { on=1; return 0; } || return 1; }
  if stage gen1; then echo "== $NAME gen1"; pgo_link "$BOOT" "$OUT/gen1" SCALANATIVE_PGO_GEN=1; fi
  if stage train1; then
    echo "== $NAME train1"; rm -rf "${OUT:?}/raw1"; mkdir -p "$OUT/raw1"
    pgo_train "$OUT/gen1/$BIN" "$OUT/raw1"; pgo_merge "$OUT/raw1" prof1
    [[ "${PGO_STAGES:-2}" != 1 ]] || pgo_pack "$NAME" "" "$OUT/prof1.profdata"
  fi
  if [[ "${PGO_STAGES:-2}" != 1 ]] && stage gen2; then
    echo "== $NAME gen2"
    pgo_link "$BOOT" "$OUT/gen2" SCALANATIVE_PGO_PROFILE="$OUT/prof1.txt" SCALANATIVE_PGO_STATS=1 SCALANATIVE_PGO_GEN=1
  fi
  if [[ "${PGO_STAGES:-2}" != 1 ]] && stage train2; then
    echo "== $NAME train2"; rm -rf "${OUT:?}/raw2"; mkdir -p "$OUT/raw2"
    pgo_train "$OUT/gen2/$BIN" "$OUT/raw2"; pgo_merge "$OUT/raw2" prof2
    pgo_pack "$NAME" "$OUT/prof1.txt" "$OUT/prof2.profdata"
  fi
  if [[ "${PGO_STAGES:-2}" == 1 ]]; then on=1; fi
  # SKIP_FINAL=1: stop after packing (the real build/03|08 link is the final one)
  if [[ -z "${SKIP_FINAL:-}" ]] && stage final; then
    echo "== $NAME final (from the packed profiles)"
    pgo_prepare "$NAME"
    [[ ${#PGO_ENV[@]} -gt 0 ]] || { echo "no packed profiles" >&2; return 1; }
    pgo_link "$BOOT" "$OUT/final" "${PGO_ENV[@]}" SCALANATIVE_PGO_STATS=1
    echo "OK: $OUT/final/$BIN"
  fi
}
