#!/usr/bin/env python3
"""Smoke test for scalino-lsp on a Scala.js project (build/lsp-js-fixture, after
`scalino setup-ide App.scala Union.scala` there): the config must carry platform js and `-scalajs`, the
Scala.js library types must resolve (no diagnostics on valid code), a real error must still be reported,
hover and go-to-definition must work on a Scala.js type, and Union.scala must report that
`(1: Int | Int).merge` is not a member: only with -scalajs selecting the Scala.js platform is `js.|` a
plain union (a plain platform treats it as js.|'s class, which has `merge`).

Usage: lsp-js-smoke.py <fixture-dir> <scalino-lsp> [-stdio]"""
import importlib.util, json, os, subprocess, sys, time

here = os.path.dirname(os.path.abspath(__file__))
spec = importlib.util.spec_from_file_location("drive", os.path.join(here, "lsp-trace-drive.py"))
drive = importlib.util.module_from_spec(spec); spec.loader.exec_module(drive)

project = os.path.abspath(sys.argv[1]); cmd = sys.argv[2:]
app = os.path.join(project, "App.scala"); app_uri = drive.uri(app)
text = open(app).read()
union = os.path.join(project, "Union.scala"); union_uri = drive.uri(union)

proc = subprocess.Popen(cmd, stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.PIPE, cwd=project)
client = drive.LspClient(proc)

def diagnostics(uri, timeout=60):
    n, _ = client.drain_notifications(
        lambda m: m.get("method") == "textDocument/publishDiagnostics" and m["params"]["uri"] == uri, timeout)
    return None if n is None else n["params"]["diagnostics"]

ok = True
try:
    resp, _ = client.request("initialize", {
        "processId": os.getpid(), "rootUri": drive.uri(project), "capabilities": {},
        "workspaceFolders": [{"uri": drive.uri(project), "name": "js"}]}, timeout=60)
    ok &= resp is not None and "error" not in resp
    client.notify("initialized", {})
    client.notify("textDocument/didOpen", {"textDocument": {"uri": app_uri, "languageId": "scala", "version": 1, "text": text}})
    client.notify("textDocument/didOpen", {"textDocument": {"uri": union_uri, "languageId": "scala", "version": 1, "text": open(union).read()}})
    d = diagnostics(app_uri)
    print("diagnostics on valid JS code:", d)
    ok &= d == []
    d = diagnostics(union_uri)
    print("diagnostics on Union.scala:", [x["message"].splitlines()[0] for x in d or []])
    ok &= any("merge" in x["message"] for x in d or [])

    broken = text.replace("val u: js.UndefOr[Int] = 1", 'val u: js.UndefOr[Int] = "not an int"')
    client.notify("textDocument/didChange", {"textDocument": {"uri": app_uri, "version": 2}, "contentChanges": [{"text": broken}]})
    d = diagnostics(app_uri)
    print("diagnostics on broken code:", [x["message"].splitlines()[0] for x in d or []])
    ok &= bool(d)

    line = next(i for i, l in enumerate(text.splitlines()) if "val u:" in l)
    col = text.splitlines()[line].index("UndefOr") + 2
    client.notify("textDocument/didChange", {"textDocument": {"uri": app_uri, "version": 3}, "contentChanges": [{"text": text}]})
    time.sleep(1)
    resp, _ = client.request("textDocument/hover", {"textDocument": {"uri": app_uri}, "position": {"line": line, "character": col}})
    hover = json.dumps(resp.get("result")) if resp else "NO RESPONSE"
    print("hover on js.UndefOr:", hover[:160])
    ok &= "UndefOr" in hover
    # Go-to-definition into library sources works for TASTy classes (Option), not for the Scala 2-compiled
    # scalajs-library (js.Promise, js.Function1): those have no source position.
    oline = next(i for i, l in enumerate(text.splitlines()) if "val opt:" in l)
    ocol = text.splitlines()[oline].index("Option") + 2
    resp, _ = client.request("textDocument/definition", {"textDocument": {"uri": app_uri}, "position": {"line": oline, "character": ocol}})
    definition = json.dumps(resp.get("result")) if resp else "NO RESPONSE"
    print("definition of Option:", definition[:200])
    ok &= "scala/Option.scala" in definition
finally:
    proc.kill()
    for l in client.stderr_lines[-5:]: print("stderr:", l)
print("OK" if ok else "FAILED")
sys.exit(0 if ok else 1)
