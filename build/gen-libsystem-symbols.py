#!/usr/bin/env python3
"""Lists the symbols libSystem exports, as far as the Darwin libc headers declare them.

    gen-libsystem-symbols.py <sysroot> <out-prefix> [--clang path]

writes <out-prefix>-x86_64.txt and <out-prefix>-arm64.txt.

The macOS sysroot needs a libSystem.tbd stub for the linker. Rather than shipping
the one extracted from Apple's SDK, build/12-build-sysroot.sh writes its own from
build/libsystem-symbols-<arch>.txt, which this script produces from Apple's open source
headers alone:

  1. every header under <sysroot>/usr/include that compiles on its own goes into
     one umbrella translation unit;
  2. every identifier of its preprocessed text is a candidate;
  3. a second translation unit takes the address of each candidate, and clang
     itself rejects whatever is not a linkable function or variable (types,
     members, enum constants, unavailable declarations...);
  4. the undefined symbols of the resulting object file are the answer. They are
     what clang really emits, so asm-label renames like `_fopen$DARWIN_EXTSN`
     come out right.

The list is committed (and reviewed) rather than regenerated on every build.
build/check-libsystem-symbols.py compares it against the real library on a Mac,
which can only remove names (a declared symbol libSystem does not export).
"""
import argparse
import concurrent.futures as cf
import os
import re
import subprocess
import sys
import tempfile

KEYWORDS = set("""auto break case char const continue default do double else enum extern float for goto
if inline int long register restrict return short signed sizeof static struct switch typedef union
unsigned void volatile while _Bool _Complex _Imaginary _Alignas _Alignof _Atomic _Generic _Noreturn
_Static_assert _Thread_local""".split())

TARGETS = ["x86_64-apple-darwin", "arm64-apple-darwin"]  # file names use x86_64 / arm64
COMMON = ["-mmacos-version-min=11.0", "-x", "c", "-w", "-ferror-limit=0"]


def run(cmd, **kw):
    return subprocess.run(cmd, capture_output=True, text=True, **kw)


def headers(inc):
    out = []
    for d, _, fs in os.walk(inc):
        for f in fs:
            if f.endswith(".h"):
                out.append(os.path.relpath(os.path.join(d, f), inc))
    return sorted(out)


def compiles(clang, target, sysroot, rel):
    # stdint/sys/types first: plenty of headers assume them, as the real ones are included after them.
    src = "#include <stdint.h>\n#include <sys/types.h>\n#include <%s>\n" % rel
    r = run([clang, "-target", target, "-isysroot", sysroot, "-fsyntax-only", *COMMON, "-"], input=src)
    return rel if r.returncode == 0 else None


def umbrella(clang, target, sysroot, rels, work):
    """The headers that compile together: drop whichever one an error is attributed to, retry."""
    rels = list(rels)
    path = os.path.join(work, "umbrella.c")
    for _ in range(30):
        with open(path, "w") as f:
            f.write("#include <stdint.h>\n#include <sys/types.h>\n")
            for r in rels:
                f.write("#include <%s>\n" % r)
        res = run([clang, "-target", target, "-isysroot", sysroot, "-fsyntax-only", *COMMON, path])
        if res.returncode == 0:
            return rels
        bad = set()
        # "In file included from .../umbrella.c:7:" names the include that pulled the failing header in.
        for m in re.finditer(r"In file included from [^\n]*umbrella\.c:(\d+):", res.stderr):
            bad.add(int(m.group(1)))
        # an error directly in the umbrella (a redefinition, ...) names its own line
        for m in re.finditer(r"umbrella\.c:(\d+):\d+: error", res.stderr):
            bad.add(int(m.group(1)))
        bad = {b for b in bad if 3 <= b < 3 + len(rels)}
        if not bad:
            sys.exit("umbrella does not compile and no include is to blame:\n" + res.stderr[:2000])
        rels = [r for i, r in enumerate(rels) if (i + 3) not in bad]
    sys.exit("umbrella did not converge")


def symbols(clang, nm, target, sysroot, rels, work):
    um = os.path.join(work, "umbrella.c")
    pre = run([clang, "-target", target, "-isysroot", sysroot, "-E", "-P", *COMMON, um]).stdout
    ids = sorted(set(re.findall(r"[A-Za-z_][A-Za-z0-9_]*", pre)) - KEYWORDS)
    head = "".join("#include <%s>\n" % r for r in ["stdint.h", "sys/types.h"] + rels)
    refs = os.path.join(work, "refs.c")
    cand = list(ids)
    for _ in range(12):
        # one declaration per line: errors do not cascade into the next candidate
        body = head + "".join("void *r%d = (void *)&%s;\n" % (i, c) for i, c in enumerate(cand))
        with open(refs, "w") as f:
            f.write(body)
        # LLVM IR rather than an object: same symbol names, no backend (some SIMD inline
        # functions in the headers crash the x86 backend).
        ir = os.path.join(work, "refs.ll")
        res = run([clang, "-target", target, "-isysroot", sysroot, "-S", "-emit-llvm", *COMMON, refs, "-o", ir])
        if res.returncode == 0:
            break
        first = head.count("\n") + 1  # line of the first candidate
        bad = {int(m.group(1)) for m in re.finditer(r"refs\.c:(\d+):\d+: error", res.stderr)}
        bad = {b for b in bad if first <= b < first + len(cand)}
        if not bad:
            sys.exit("refs.c does not compile and no candidate is to blame:\n" + res.stderr[:2000])
        print("  round: %d candidates, %d rejected" % (len(cand), len(bad)), file=sys.stderr)
        cand = [c for i, c in enumerate(cand) if (i + first) not in bad]
    else:
        sys.exit("refs.c did not converge")
    text = open(ir).read()
    names = set()
    # external functions and variables; \01 means "exactly this name", otherwise Darwin adds "_"
    for m in re.finditer(r'^declare [^@\n]*@("(?:[^"\\]|\\.)*"|[\w.$]+)\(', text, re.M):
        names.add(m.group(1))
    for m in re.finditer(r'^@("(?:[^"\\]|\\.)*"|[\w.$]+) = (?:extern_weak |external )[^\n]*', text, re.M):
        names.add(m.group(1))
    out = set()
    for n in names:
        n = n.strip('"')
        if n.startswith("llvm."):
            continue
        if n.startswith("\\01"):  # the 3 characters backslash-0-1 in the IR: exactly this name
            out.add(n[3:])
        elif n.startswith("\x01"):
            out.add(n[1:])
        else:
            out.add("_" + n)
    return out


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("sysroot")
    ap.add_argument("out")
    ap.add_argument("--clang", default="clang")
    ap.add_argument("--nm", default="llvm-nm")  # unused, kept for compatibility
    a = ap.parse_args()
    inc = os.path.join(a.sysroot, "usr", "include")
    allh = headers(inc)
    for target in TARGETS:
        with cf.ThreadPoolExecutor(max_workers=os.cpu_count() or 4) as ex:
            ok = [r for r in ex.map(lambda h: compiles(a.clang, target, a.sysroot, h), allh) if r]
        with tempfile.TemporaryDirectory() as work:
            ok = umbrella(a.clang, target, a.sysroot, ok, work)
            syms = symbols(a.clang, a.nm, target, a.sysroot, ok, work)
        print("%s: %d of %d headers, %d symbols" % (target, len(ok), len(allh), len(syms)), file=sys.stderr)
        arch = target.split("-")[0]
        with open("%s-%s.txt" % (a.out, arch), "w") as f:
            f.write("# Symbols libSystem exports on %s, derived from Apple's open source Darwin headers by\n" % arch)
            f.write("# build/gen-libsystem-symbols.py and pruned by build/check-libsystem-symbols.py.\n")
            f.write("# Only symbol names: no SDK content.\n")
            for sym in sorted(syms):
                f.write(sym + "\n")


main()
