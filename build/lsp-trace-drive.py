#!/usr/bin/env python3
"""Drives a comprehensive real-editor-shaped LSP request sequence against a
dotty.tools.languageserver.Main process launched with the given command --
a full functional smoke test exercising every endpoint DottyLanguageServer
implements (initialize with a real editor's full capabilities+
workspaceFolders, didOpen/didChange, hover, definition, completion,
references, rename, documentSymbol, workspace/symbol) against real dotc
compilation of build/lsp-trace-fixture.

No longer wired into build/05-regen-agent-config.sh: the LSP module used
to be lsp4j+Gson (reflection-based), and this script's job was tracing
GraalVM native-image reflection config against realistic request shapes --
Gson's reflective TypeAdapter construction only worked for types actually
exercised during a traced run, and a real editor's rich `initialize`
payload turned out to trigger a native-image-specific pathology no amount
of extra tracing fixed (see docs/findings.md "JVM-free language server
(LSP)"). The module is now a hand-rolled JSON-RPC/LSP implementation over
hand-written jsoniter-scala codecs, zero reflection, so there's nothing
left to trace -- this script is kept purely as an end-to-end functional
test to run by hand after any change to Main.scala/Lsp.scala/
DottyLanguageServer.scala.

Usage: lsp-trace-drive.py <project-dir> <command...>
<project-dir> must contain Model.scala, Greeter.scala, Main.scala (see
build/lsp-trace-fixture/) and have a .dotty-ide.json already generated
there (e.g. via `dist/scalino setup-ide Model.scala Greeter.scala Main.scala`).
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
        # A real editor's `initialize` carries `clientInfo` and a deeply
        # nested `capabilities` tree (workspace/*, textDocument/*, window),
        # each level a distinct lsp4j POJO Gson must reflectively
        # instantiate/populate on the way in -- a bare `"capabilities": {}`
        # (this fixture's shape until 2026-09-05) never exercises any of
        # that, so native-image's reachability trace never registers those
        # types, and a real client's `initialize` request then dies with
        # "Type ... was never registered" the moment Gson tries to
        # deserialize e.g. ClientInfo or CompletionClientCapabilities --
        # invisible to the client (just a dead connection), only visible in
        # the server's own stderr. See docs/findings.md "JVM-free language
        # server (LSP)" for the write-side sibling of this same bug class.
        client_capabilities = {
            "workspace": {
                "applyEdit": True,
                "workspaceEdit": {"documentChanges": True, "resourceOperations": ["create", "rename", "delete"]},
                "didChangeConfiguration": {"dynamicRegistration": True},
                "didChangeWatchedFiles": {"dynamicRegistration": True},
                "symbol": {"dynamicRegistration": True, "symbolKind": {"valueSet": list(range(1, 27))}},
                "executeCommand": {"dynamicRegistration": True},
                "workspaceFolders": True,
                "configuration": True,
            },
            "textDocument": {
                "synchronization": {"dynamicRegistration": True, "willSave": True, "willSaveWaitUntil": True, "didSave": True},
                "completion": {
                    "dynamicRegistration": True,
                    "completionItem": {
                        "snippetSupport": True,
                        "commitCharactersSupport": True,
                        "documentationFormat": ["markdown", "plaintext"],
                        "deprecatedSupport": True,
                        "preselectSupport": True,
                    },
                    "completionItemKind": {"valueSet": list(range(1, 26))},
                    "contextSupport": True,
                },
                "hover": {"dynamicRegistration": True, "contentFormat": ["markdown", "plaintext"]},
                "signatureHelp": {
                    "dynamicRegistration": True,
                    "signatureInformation": {"documentationFormat": ["markdown", "plaintext"]},
                },
                "declaration": {"dynamicRegistration": True, "linkSupport": True},
                "definition": {"dynamicRegistration": True, "linkSupport": True},
                "typeDefinition": {"dynamicRegistration": True, "linkSupport": True},
                "implementation": {"dynamicRegistration": True, "linkSupport": True},
                "references": {"dynamicRegistration": True},
                "documentHighlight": {"dynamicRegistration": True},
                "documentSymbol": {
                    "dynamicRegistration": True,
                    "symbolKind": {"valueSet": list(range(1, 27))},
                    "hierarchicalDocumentSymbolSupport": True,
                },
                "codeAction": {
                    "dynamicRegistration": True,
                    "codeActionLiteralSupport": {"codeActionKind": {"valueSet": ["", "quickfix", "refactor", "source"]}},
                },
                "codeLens": {"dynamicRegistration": True},
                "documentLink": {"dynamicRegistration": True, "tooltipSupport": True},
                "colorProvider": {"dynamicRegistration": True},
                "formatting": {"dynamicRegistration": True},
                "rangeFormatting": {"dynamicRegistration": True},
                "onTypeFormatting": {"dynamicRegistration": True},
                "rename": {"dynamicRegistration": True, "prepareSupport": True},
                "publishDiagnostics": {"relatedInformation": True, "tagSupport": {"valueSet": [1, 2]}},
                "foldingRange": {"dynamicRegistration": True, "rangeLimit": 5000, "lineFoldingOnly": True},
            },
            "window": {"workDoneProgress": True},
            # A real editor's `initialize` (Zed confirmed) also sends these
            # two top-level capability groups, which this fixture never
            # exercised until a real Zed launch silently hung for exactly
            # ~60s then cleanly self-exited (no error printed anywhere --
            # the read-loop's worker thread pool has a default 60s
            # keep-alive, and once whatever silently ate the parse failure
            # never resubmits another read, that's what's left ticking).
            # Bisected down to these two fields independently reproducing
            # it; see docs/findings.md "JVM-free language server (LSP)".
            "general": {"positionEncodings": ["utf-16"]},
            "experimental": {"serverStatusNotification": True, "localDocs": True},
        }

        section("initialize (with clientInfo + full nested capabilities + workspaceFolders, like a real editor)")
        resp, elapsed = client.request("initialize", {
            "processId": os.getpid(),
            "clientInfo": {"name": "trace-client", "version": "0.1.0"},
            "rootUri": uri(project),
            "capabilities": client_capabilities,
            "trace": "off",
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
