#!/usr/bin/env python3
"""Prunes build/libsystem-symbols-<arch>.txt and build/libobjc-symbols-<arch>.txt against the real libSystem/libobjc. Run on a Mac with Xcode.

    check-libsystem-symbols.py [--write]

The lists come from the headers alone (gen-libsystem-symbols.py), so they can name symbols the
library does not export (declared but never implemented, other OS versions...). This links a
dylib that references every listed name against the installed SDK and reports what the linker
cannot find; with --write those names are removed. It only ever removes: the SDK is a test
oracle here, none of its content ends up in the lists.
"""
import os
import re
import subprocess
import sys
import tempfile

HERE = os.path.dirname(os.path.abspath(__file__))


def main():
    write = "--write" in sys.argv
    check("libsystem", [], write, True)
    check("libobjc", ["-lobjc"], write, False)


def check(lib, link_flags, write, has_extra):
    all_missing, per_arch_missing = set(), []
    extra_path = os.path.join(HERE, "libsystem-symbols-extra.txt")
    for arch in ["x86_64", "arm64"]:
        path = os.path.join(HERE, "%s-symbols-%s.txt" % (lib, arch))
        lines = open(path).read().splitlines()
        syms = [l for l in lines if l and not l.startswith("#")]
        extra = [l for l in open(extra_path).read().splitlines() if l and not l.startswith("#")] if has_extra else []
        syms = syms + [e for e in extra if e not in syms]
        with tempfile.TemporaryDirectory() as d:
            asm = os.path.join(d, "refs.s")
            with open(asm, "w") as f:
                f.write(".data\n")
                for s in syms:
                    f.write('.quad "%s"\n' % s)
            obj = os.path.join(d, "refs.o")
            subprocess.run(["clang", "-arch", arch, "-c", asm, "-o", obj], check=True)
            r = subprocess.run(["clang", "-arch", arch, "-dynamiclib", "-o", os.path.join(d, "x.dylib"), obj,
                                "-Wl,-undefined,error", *link_flags], capture_output=True, text=True)
        missing = set(re.findall(r'^\s+"([^"]+)", referenced from', r.stderr, re.M))
        per_arch_missing.append(missing)
        print("%s: %d symbols, %d not exported by the installed %s" % (arch, len(syms), len(missing), lib))
        if missing and not write:
            print("  e.g. " + ", ".join(sorted(missing)[:10]))
        if write:
            with open(path, "w") as f:
                for l in lines:
                    if l not in missing:
                        f.write(l + "\n")
            all_missing.update(missing)
    if write and has_extra:
        # a name is dropped from the extra list only if neither architecture exports it
        both = {s for s in all_missing if all(s in m for m in per_arch_missing)}
        keep = [l for l in open(extra_path).read().splitlines() if l.startswith("#") or l not in both]
        with open(extra_path, "w") as f:
            f.write("\n".join(keep) + "\n")


main()
