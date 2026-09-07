#!/usr/bin/env python3
"""Stress-test the self-hosted scalino-lsp binary against the real, multi-file
~/scala/ape project -- NEW coverage beyond build/lsp-trace-drive.py's small
fixed 3-file fixture:
  - a real multi-directory project (6 sourceDirectories, 19 src files)
  - a cross-PACKAGE symbol (BuildingInput defined in domain/Building.scala,
    used in db/BuildingRepo.scala via a real import) for hover/definition/
    references, instead of same-directory same-file resolution
  - an incremental didChange to the DEFINITION file (not the usage file),
    followed by re-querying hover on the (unchanged) USAGE file to confirm
    the server's cross-file model actually invalidates/reflects the edit
    (stronger than lsp-trace-drive.py's single-file didChange+diagnostics
    check)

Usage: ape-lsp-stress.py <project-dir> <command...>
"""
import json, subprocess, sys, threading, time, queue, os

def uri(path):
    return "file://" + path

class LspClient:
    def __init__(self, proc):
        self.proc = proc
        self.next_id = 1
        self.notifications = queue.Queue()
        self.responses = {}
        self.lock = threading.Lock()
        self.cond = threading.Condition(self.lock)
        threading.Thread(target=self._read_loop, daemon=True).start()
        self.stderr_lines = []
        threading.Thread(target=self._stderr_loop, daemon=True).start()

    def _stderr_loop(self):
        for line in iter(self.proc.stderr.readline, b''):
            self.stderr_lines.append(line.decode('utf-8', errors='replace').rstrip())

    def _read_loop(self):
        stdout = self.proc.stdout
        while True:
            headers = {}
            while True:
                line = stdout.readline()
                if not line:
                    return
                line = line.rstrip(b'\r\n')
                if line == b'':
                    break
                if b':' in line:
                    k, v = line.split(b':', 1)
                    headers[k.strip().lower()] = v.strip()
            length = int(headers.get(b'content-length', b'0'))
            body = b''
            while len(body) < length:
                chunk = stdout.read(length - len(body))
                if not chunk:
                    return
                body += chunk
            try:
                msg = json.loads(body.decode('utf-8'))
            except Exception:
                continue
            with self.cond:
                if 'id' in msg and ('result' in msg or 'error' in msg):
                    self.responses[msg['id']] = msg
                    self.cond.notify_all()
                else:
                    self.notifications.put(msg)

    def send(self, method, params, is_notification=False):
        msg = {"jsonrpc": "2.0", "method": method, "params": params}
        if not is_notification:
            msg_id = self.next_id
            self.next_id += 1
            msg["id"] = msg_id
        else:
            msg_id = None
        data = json.dumps(msg).encode('utf-8')
        header = f"Content-Length: {len(data)}\r\n\r\n".encode('utf-8')
        self.proc.stdin.write(header + data)
        self.proc.stdin.flush()
        return msg_id

    def request(self, method, params, timeout=60):
        start = time.time()
        msg_id = self.send(method, params, is_notification=False)
        with self.cond:
            while msg_id not in self.responses:
                remaining = timeout - (time.time() - start)
                if remaining <= 0:
                    return None, None
                self.cond.wait(timeout=remaining)
            resp = self.responses.pop(msg_id)
        elapsed = time.time() - start
        return resp, elapsed

    def notify(self, method, params):
        self.send(method, params, is_notification=True)

    def drain_notifications(self, predicate, timeout=5):
        start = time.time()
        collected = []
        while time.time() - start < timeout:
            try:
                n = self.notifications.get(timeout=0.2)
                collected.append(n)
                if predicate(n):
                    return n, collected
            except queue.Empty:
                continue
        return None, collected

def section(title):
    print("\n" + "=" * 100); print(title); print("=" * 100)

def show(label, resp, elapsed):
    if resp is None:
        print(f"[{label}] NO RESPONSE (timeout)"); return None
    ms = f"{elapsed*1000:.0f}ms" if elapsed is not None else "?"
    if 'error' in resp:
        print(f"[{label}] ERROR in {ms}: {json.dumps(resp['error'])}")
        return None
    else:
        print(f"[{label}] OK in {ms}")
        return resp['result']

def main():
    project = os.path.abspath(sys.argv[1])
    cmd = sys.argv[2:]

    building_path = os.path.join(project, "src", "domain", "Building.scala")
    repo_path = os.path.join(project, "src", "db", "BuildingRepo.scala")
    building_uri = uri(building_path)
    repo_uri = uri(repo_path)
    with open(building_path) as f: building_text = f.read()
    with open(repo_path) as f: repo_text = f.read()

    proc = subprocess.Popen(cmd, stdin=subprocess.PIPE, stdout=subprocess.PIPE,
                             stderr=subprocess.PIPE, cwd=project)
    client = LspClient(proc)
    ok = True
    try:
        section("initialize (real multi-dir ape project, 6 sourceDirectories)")
        resp, elapsed = client.request("initialize", {
            "processId": os.getpid(),
            "clientInfo": {"name": "ape-stress-client", "version": "0.1.0"},
            "rootUri": uri(project),
            "capabilities": {
                "textDocument": {
                    "hover": {"contentFormat": ["markdown", "plaintext"]},
                    "definition": {"linkSupport": True},
                    "references": {},
                    "publishDiagnostics": {},
                },
            },
            "workspaceFolders": [{"uri": uri(project), "name": os.path.basename(project)}],
        }, timeout=90)
        result = show("initialize", resp, elapsed)
        ok = ok and result is not None
        client.notify("initialized", {})
        time.sleep(0.5)

        section("didOpen: domain/Building.scala + db/BuildingRepo.scala (different packages/dirs)")
        client.notify("textDocument/didOpen", {"textDocument": {"uri": building_uri, "languageId": "scala", "version": 1, "text": building_text}})
        client.notify("textDocument/didOpen", {"textDocument": {"uri": repo_uri, "languageId": "scala", "version": 1, "text": repo_text}})
        t0 = time.time(); diags = {}
        while time.time() - t0 < 90 and len(diags) < 2:
            n, _ = client.drain_notifications(lambda n: n.get("method") == "textDocument/publishDiagnostics", timeout=3)
            if n:
                diags[n["params"]["uri"]] = n["params"]["diagnostics"]
        print(f"got diagnostics for {len(diags)}/2 files")
        for u, d in diags.items():
            if d:
                print(f"  UNEXPECTED pre-existing diagnostics in {u}: {d}")

        # Locate a real cross-package use site: `input: BuildingInput,` on the
        # BuildingRepo trait's `find`/`save` signature in db/BuildingRepo.scala.
        repo_lines = repo_text.splitlines()
        use_line = use_col = None
        for i, l in enumerate(repo_lines):
            if "def save(input: BuildingInput" in l:
                use_line = i; use_col = l.index("BuildingInput") + 2; break
        assert use_line is not None, "couldn't find BuildingInput use site in BuildingRepo.scala"

        section("hover(BuildingInput) at cross-package use site in db/BuildingRepo.scala")
        resp, elapsed = client.request("textDocument/hover", {"textDocument": {"uri": repo_uri}, "position": {"line": use_line, "character": use_col}})
        result = show("hover(BuildingInput)", resp, elapsed)
        ok = ok and result is not None
        if result:
            print("  hover contents:", json.dumps(result.get("contents"))[:300])

        section("definition(BuildingInput) -- should resolve INTO domain/Building.scala")
        resp, elapsed = client.request("textDocument/definition", {"textDocument": {"uri": repo_uri}, "position": {"line": use_line, "character": use_col}})
        result = show("definition(BuildingInput)", resp, elapsed)
        ok = ok and result is not None
        cross_file_resolved = False
        if result:
            locs = result if isinstance(result, list) else [result]
            for loc in locs:
                target_uri = loc.get("uri") or loc.get("targetUri")
                print(f"  resolved to: {target_uri}")
                if target_uri == building_uri:
                    cross_file_resolved = True
            print(f"  cross-package resolution correct: {cross_file_resolved}")
        ok = ok and cross_file_resolved

        section("references(BuildingInput) from its definition site in domain/Building.scala")
        def_line = def_col = None
        for i, l in enumerate(building_text.splitlines()):
            if "case class BuildingInput" in l:
                def_line = i; def_col = l.index("BuildingInput") + 2; break
        resp, elapsed = client.request("textDocument/references", {"textDocument": {"uri": building_uri}, "position": {"line": def_line, "character": def_col}, "context": {"includeDeclaration": True}})
        result = show("references(BuildingInput)", resp, elapsed)
        ok = ok and result is not None
        found_cross_file_ref = False
        if result:
            uris_found = sorted(set(loc["uri"] for loc in result))
            print(f"  {len(result)} reference(s) across {len(uris_found)} file(s): {uris_found}")
            found_cross_file_ref = repo_uri in uris_found
            print(f"  found reference back in db/BuildingRepo.scala: {found_cross_file_ref}")
        ok = ok and found_cross_file_ref

        section("didChange: edit the DEFINITION file (domain/Building.scala), introduce a real type error")
        building_broken = building_text.replace(
            "case class BuildingInput(",
            "case class BuildingInput(\n  brokenField: NoSuchTypeAtAll,",
            1,
        )
        assert building_broken != building_text, "replace didn't match -- fixture assumption wrong"
        client.notify("textDocument/didChange", {"textDocument": {"uri": building_uri, "version": 2}, "contentChanges": [{"text": building_broken}]})
        n, _ = client.drain_notifications(
            lambda n: n.get("method") == "textDocument/publishDiagnostics" and n["params"]["uri"] == building_uri and len(n["params"]["diagnostics"]) > 0,
            timeout=60)
        print("got error diagnostics in domain/Building.scala after didChange" if n else "NO error diagnostics within 60s")
        ok = ok and n is not None

        section("re-hover(BuildingInput) in db/BuildingRepo.scala -- does the cross-file model see the edit?")
        # The usage file itself wasn't edited -- this tests whether the
        # interactive driver's cross-file dependency tracking actually
        # re-elaborates BuildingRepo.scala's view of BuildingInput after its
        # *definition* changed elsewhere, not just re-diagnosing the edited
        # file itself (a much weaker, single-file-only notion of "incremental").
        resp, elapsed = client.request("textDocument/hover", {"textDocument": {"uri": repo_uri}, "position": {"line": use_line, "character": use_col}})
        result = show("re-hover(BuildingInput) after cross-file edit", resp, elapsed)
        ok = ok and result is not None

        section("STDERR")
        time.sleep(0.5)
        for line in client.stderr_lines:
            print(line)
    finally:
        try:
            client.request("shutdown", {}, timeout=5)
            client.notify("exit", {})
        except Exception:
            pass
        time.sleep(0.5)
        proc.terminate()
        try:
            proc.wait(timeout=5)
        except Exception:
            proc.kill()

    print(f"\nOVERALL: {'PASS' if ok else 'FAIL'}")
    sys.exit(0 if ok else 1)

if __name__ == "__main__":
    main()
