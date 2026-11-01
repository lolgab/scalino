#!/usr/bin/env python3
"""Regenerates patches/scala-native-*.patch from a scala-native checkout.

Workflow (see docs/rebasing-scala-native.md): keep a scala-native clone with
upstream main on one ref (BASE) and main + all scalino changes on another (HEAD),
then run

    build/tools/regen-scala-native-patches.py <clone> <BASE> <HEAD> patches

The diff BASE..HEAD is split into one patch per area (BUCKETS below). Every file
belongs to exactly one bucket, so the patches are independent of each other and
apply in any order; the script checks that applying them all to BASE reproduces
HEAD exactly. The `selfhost` patch holds the few hunks that only compile against
scalino's self-hosted dotc; build/01a-publish-scala-native.sh reverts it while it
runs sbt, which compiles against the published compiler.
"""
import os
import re
import subprocess
import sys
import tempfile

# (patch name, path predicate). First match wins.
BUCKETS = [
    ("nscplugin-tagging-and-compact-headers", lambda p: p.startswith("nscplugin/")),
    ("javalib", lambda p: p.startswith("javalib/")),
    ("nir-util", lambda p: p.startswith(("nir/", "util/"))),
    ("interflow", lambda p: "/scalanative/interflow/" in p or "/scalanative/checker/" in p),
    ("codegen", lambda p: "/scalanative/codegen/" in p),
    ("linker", lambda p: "/scalanative/linker/" in p),
    ("build-tools", lambda p: p.startswith("tools/")),
    ("runtime-scala", lambda p: p.startswith("nativelib/src/main/scala/")),
    ("gc-immix-commix", lambda p: re.match(
        r"nativelib/src/main/resources/scala-native/gc/(immix|immix_commix|commix|none|boehm)/", p)),
    ("gc-shared-and-runtime-c", lambda p: p.startswith("nativelib/src/main/resources/")),
    ("docs", lambda p: p.startswith("docs/")),
]

# Hunks of nscplugin/ that only compile against scalino's self-hosted dotc.
SELFHOST_MARKERS = ("SymExtensions", "GenBCode", "caseInsensitiveNameOf")


def git(repo, *args, input=None):
    return subprocess.run(["git", "-C", repo, *args], input=input, text=True,
                          capture_output=True, check=True).stdout


def split_file_diffs(diff):
    parts = re.split(r"(?m)^(?=diff --git )", diff)
    return [p for p in parts if p.startswith("diff --git")]


def split_hunks(file_diff):
    i = file_diff.find("\n@@")
    if i < 0:
        return file_diff, []
    header = file_diff[: i + 1]
    hunks = [h for h in re.split(r"(?m)^(?=@@ )", file_diff[i + 1:]) if h]
    return header, hunks


def main():
    repo, base, head, out = sys.argv[1:5]
    full = git(repo, "diff", "--binary", "--no-renames", base, head)
    files = split_file_diffs(full)
    buckets = {name: [] for name, _ in BUCKETS}
    buckets["selfhost"] = []
    for fd in files:
        path = re.match(r"diff --git a/(\S+) b/", fd).group(1)
        for name, pred in BUCKETS:
            if pred(path):
                break
        else:
            sys.exit(f"no bucket for {path}")
        if name.startswith("nscplugin"):
            header, hunks = split_hunks(fd)
            sh = [h for h in hunks if any(m in h for m in SELFHOST_MARKERS)]
            rest = [h for h in hunks if h not in sh]
            if sh:
                buckets["selfhost"].append(header + "".join(sh))
            if rest:
                buckets[name].append(header + "".join(rest))
        else:
            buckets[name].append(fd)

    order = ["selfhost"] + [n for n, _ in BUCKETS]
    for old in os.listdir(out):
        if old.startswith("scala-native-") and old.endswith(".patch"):
            os.remove(os.path.join(out, old))
    written = []
    for n, name in enumerate([b for b in order if buckets[b]], 1):
        fn = os.path.join(out, f"scala-native-{n:04d}-{name}.patch")
        with open(fn, "w") as f:
            f.write("".join(buckets[name]))
        written.append(fn)
        print(fn)

    # Verify: BASE + all patches == HEAD.
    with tempfile.TemporaryDirectory() as tmp:
        idx = os.path.join(tmp, "index")
        env = dict(os.environ, GIT_INDEX_FILE=idx)
        subprocess.run(["git", "-C", repo, "read-tree", base], env=env, check=True)
        for fn in written:
            subprocess.run(["git", "-C", repo, "apply", "--cached", os.path.abspath(fn)],
                           env=env, check=True)
        tree = subprocess.run(["git", "-C", repo, "write-tree"], env=env, text=True,
                              capture_output=True, check=True).stdout.strip()
    want = git(repo, "rev-parse", f"{head}^{{tree}}").strip()
    if tree != want:
        sys.exit(f"MISMATCH: patches give tree {tree}, {head} is {want}")
    print("OK: patches reproduce", head)


if __name__ == "__main__":
    main()
