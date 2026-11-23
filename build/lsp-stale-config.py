#!/usr/bin/env python3
"""Checks that scalino-lsp recovers from a stale .scalino-build/scalino-lsp.json: one whose paths
point into a scalino install that no longer exists, as after `brew upgrade` deletes the old
version's directory.

Usage: lsp-stale-config.py <dist-dir>

Copies build/lsp-trace-fixture to a temporary directory, runs `scalino setup-ide` there, then
rewrites the config's install paths to a directory that doesn't exist. scalino-lsp (with <dist-dir>
first on PATH, so it finds `scalino`) must then answer a hover anyway, both when the config is stale
at startup and when it goes stale under a running server.
"""
import json, os, shutil, subprocess, sys, tempfile, time

dist = os.path.abspath(sys.argv[1])
fixture = os.path.join(os.path.dirname(os.path.abspath(__file__)), "lsp-trace-fixture")
work = tempfile.mkdtemp(prefix="scalino-lsp-stale-")
root = os.path.join(work, "project")
shutil.copytree(fixture, root, ignore=shutil.ignore_patterns(".scalino-build"))
env = dict(os.environ, PATH=dist + os.pathsep + os.environ.get("PATH", ""))
scalino = os.path.join(dist, "scalino.exe" if os.name == "nt" else "scalino")
subprocess.run([scalino, "setup-ide", "Model.scala", "Greeter.scala", "Main.scala"], cwd=root, env=env,
               check=True, stdout=subprocess.DEVNULL)
config = os.path.join(root, ".scalino-build", "scalino-lsp.json")

def make_stale():
    # Every path into the install moves to a directory that was never there.
    text = open(config).read()
    gone = os.path.join(work, "removed-install")
    assert dist in text or json.dumps(dist)[1:-1] in text, "config doesn't name the dist directory"
    text = text.replace(json.dumps(dist)[1:-1], json.dumps(gone)[1:-1])
    open(config, "w").write(text)
    # A later mtime than the server last read, even on filesystems with coarse timestamps.
    st = os.stat(config)
    os.utime(config, (st.st_atime, st.st_mtime + 2))

def uri(path):
    if os.name == "nt":
        import pathlib
        return pathlib.Path(path).as_uri()
    return "file://" + path

stderr = open(os.path.join(work, "stderr.log"), "w")
proc = subprocess.Popen([os.path.join(dist, "scalino-lsp"), "-stdio"], cwd=root, env=env,
                        stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=stderr)

def send(msg):
    body = json.dumps(msg).encode()
    proc.stdin.write(b"Content-Length: %d\r\n\r\n" % len(body) + body)
    proc.stdin.flush()

def read():
    headers = {}
    while True:
        line = proc.stdout.readline().decode().strip()
        if not line:
            break
        k, v = line.split(":", 1)
        headers[k.lower()] = v.strip()
    return json.loads(proc.stdout.read(int(headers["content-length"])))

def request(i, method, params):
    send({"jsonrpc": "2.0", "id": i, "method": method, "params": params})
    while True:
        msg = read()
        if msg.get("id") == i:
            return msg

main = os.path.join(root, "Main.scala")
text = open(main).read()
line = next(i for i, l in enumerate(text.splitlines()) if "Point.distance" in l)
col = text.splitlines()[line].index("distance") + 2

def hover(i):
    r = request(i, "textDocument/hover", {"textDocument": {"uri": uri(main)}, "position": {"line": line, "character": col}})
    if "error" in r or not r.get("result"):
        print(f"FAIL: hover {i}: {json.dumps(r)[:400]}", file=sys.stderr)
        print(open(os.path.join(work, "stderr.log")).read()[-3000:], file=sys.stderr)
        proc.kill()
        sys.exit(1)

try:
    make_stale()
    request(1, "initialize", {"rootUri": uri(root), "capabilities": {}})
    send({"jsonrpc": "2.0", "method": "initialized", "params": {}})
    send({"jsonrpc": "2.0", "method": "textDocument/didOpen",
          "params": {"textDocument": {"uri": uri(main), "languageId": "scala", "version": 1, "text": text}}})
    hover(2)
    print("OK: stale config at startup")
    make_stale()
    hover(3)
    print("OK: config went stale under a running server")
finally:
    proc.kill()
    stderr.close()
shutil.rmtree(work, ignore_errors=True)
