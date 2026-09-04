#!/usr/bin/env python3
"""Drives a comprehensive real-editor-shaped LSP request sequence against a
dotty.tools.languageserver.Main process launched with the given command.
Used by build/05-regen-agent-config.sh to trace GraalVM native-image
reflection config against the same request shapes a real editor sends
(notably `initialize` with `workspaceFolders`, and `workspace/symbol`) --
see docs/findings.md "JVM-free language server (LSP)" for the bug class
this exists to catch: lsp4j deserializes request params via Gson
reflection, and native-image's closed-world reachability analysis only
registers types it saw exercised during a traced run.

Usage: lsp-trace-drive.py <project-dir> <command...>
<project-dir> must contain Model.scala, Greeter.scala, Main.scala (see
build/lsp-trace-fixture/) and have a .dotty-ide.json already generated
there (e.g. via `dist/sn-cli setup-ide Model.scala Greeter.scala Main.scala`).
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

    def request(self, method, params, timeout=25):
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
        print(f"[{label}] NO RESPONSE (timeout)"); return
    ms = f"{elapsed*1000:.0f}ms" if elapsed is not None else "?"
    if 'error' in resp:
        print(f"[{label}] ERROR in {ms}: {json.dumps(resp['error'])}")
    else:
        print(f"[{label}] OK in {ms}")

def main():
    if len(sys.argv) < 3:
        print("usage: lsp-trace-drive.py <project-dir> <command...>", file=sys.stderr)
        sys.exit(1)
    project = os.path.abspath(sys.argv[1])
    cmd = sys.argv[2:]

    model_uri = uri(os.path.join(project, "Model.scala"))
    greeter_uri = uri(os.path.join(project, "Greeter.scala"))
    main_uri = uri(os.path.join(project, "Main.scala"))
    with open(os.path.join(project, "Model.scala")) as f: model_text = f.read()
    with open(os.path.join(project, "Greeter.scala")) as f: greeter_text = f.read()
    with open(os.path.join(project, "Main.scala")) as f: main_text = f.read()
    main_text_broken = main_text.replace(
        'val a = Point(0, 0)',
        'val a: Point = Point(0, 0)\n  val badVal: Int = "not an int"'
    )

    proc = subprocess.Popen(cmd, stdin=subprocess.PIPE, stdout=subprocess.PIPE,
                             stderr=subprocess.PIPE, cwd=project)
    client = LspClient(proc)
    ok = True
    try:
        section("initialize (with workspaceFolders, like a real editor)")
        resp, elapsed = client.request("initialize", {
            "processId": os.getpid(),
            "rootUri": uri(project),
            "capabilities": {},
            "workspaceFolders": [{"uri": uri(project), "name": os.path.basename(project)}],
        }, timeout=60)
        show("initialize", resp, elapsed)
        ok = ok and resp is not None and 'error' not in resp

        client.notify("initialized", {})
        time.sleep(0.5)

        section("didOpen x3")
        for u, text in [(model_uri, model_text), (greeter_uri, greeter_text), (main_uri, main_text)]:
            client.notify("textDocument/didOpen", {"textDocument": {"uri": u, "languageId": "scala", "version": 1, "text": text}})
        t0 = time.time(); diags = {}
        while time.time() - t0 < 20 and len(diags) < 3:
            n, _ = client.drain_notifications(lambda n: n.get("method") == "textDocument/publishDiagnostics", timeout=2)
            if n:
                diags[n["params"]["uri"]] = n["params"]["diagnostics"]
        print(f"got diagnostics for {len(diags)}/3 files")

        main_lines = main_text.splitlines()
        hover_line = col = None
        for i, l in enumerate(main_lines):
            if "Point.distance" in l:
                hover_line = i; col = l.index("distance") + 2; break

        section("hover(distance)")
        resp, elapsed = client.request("textDocument/hover", {"textDocument": {"uri": main_uri}, "position": {"line": hover_line, "character": col}})
        show("hover(distance)", resp, elapsed)
        ok = ok and resp is not None and 'error' not in resp

        section("definition(distance)")
        resp, elapsed = client.request("textDocument/definition", {"textDocument": {"uri": main_uri}, "position": {"line": hover_line, "character": col}})
        show("definition(distance)", resp, elapsed)
        ok = ok and resp is not None and 'error' not in resp

        gline = gcol = None
        for i, l in enumerate(main_lines):
            if "g.greet" in l:
                gline = i; gcol = l.index("g.greet") + 2; break
        section("completion(g.)")
        resp, elapsed = client.request("textDocument/completion", {"textDocument": {"uri": main_uri}, "position": {"line": gline, "character": gcol}})
        show("completion(g.)", resp, elapsed)
        ok = ok and resp is not None and 'error' not in resp

        section("references(distance)")
        resp, elapsed = client.request("textDocument/references", {"textDocument": {"uri": main_uri}, "position": {"line": hover_line, "character": col}, "context": {"includeDeclaration": True}})
        show("references(distance)", resp, elapsed)
        ok = ok and resp is not None and 'error' not in resp

        section("rename(distance->dist)")
        resp, elapsed = client.request("textDocument/rename", {"textDocument": {"uri": main_uri}, "position": {"line": hover_line, "character": col}, "newName": "dist"})
        show("rename(distance->dist)", resp, elapsed)
        ok = ok and resp is not None and 'error' not in resp

        section("documentSymbol(Model.scala)")
        resp, elapsed = client.request("textDocument/documentSymbol", {"textDocument": {"uri": model_uri}})
        show("documentSymbol(Model.scala)", resp, elapsed)
        ok = ok and resp is not None and 'error' not in resp

        section("workspace/symbol(Point)")
        resp, elapsed = client.request("workspace/symbol", {"query": "Point"})
        show("workspace/symbol(Point)", resp, elapsed)
        ok = ok and resp is not None and 'error' not in resp

        section("didChange: introduce type error")
        client.notify("textDocument/didChange", {"textDocument": {"uri": main_uri, "version": 2}, "contentChanges": [{"text": main_text_broken}]})
        n, _ = client.drain_notifications(
            lambda n: n.get("method") == "textDocument/publishDiagnostics" and n["params"]["uri"] == main_uri and len(n["params"]["diagnostics"]) > 0,
            timeout=20)
        print("got error diagnostics after didChange" if n else "NO error diagnostics within 20s")
        ok = ok and n is not None

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

    sys.exit(0 if ok else 1)

if __name__ == "__main__":
    main()
