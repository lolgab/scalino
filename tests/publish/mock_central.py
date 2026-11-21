# A stand-in for the Sonatype Central Portal publisher API and a Maven repository that takes PUTs,
# for tests/publish/run.sh. Records every request (method, path, query, Authorization header) as
# JSON lines in <dir>/requests.jsonl, keeps uploaded bundles and PUT files under <dir>, and
# prints the port it listens on.
#
# Usage: python3 mock_central.py <dir>
import json, os, re, sys
from http.server import BaseHTTPRequestHandler, HTTPServer
from urllib.parse import urlparse, parse_qs

DIR = sys.argv[1]
status_polls = {}

class Handler(BaseHTTPRequestHandler):
    def log_message(self, *a): pass

    def record(self, extra=None):
        u = urlparse(self.path)
        entry = {"method": self.command, "path": u.path, "query": parse_qs(u.query),
                 "auth": self.headers.get("Authorization", "")}
        entry.update(extra or {})
        with open(os.path.join(DIR, "requests.jsonl"), "a") as f:
            f.write(json.dumps(entry) + "\n")
        return u

    def reply(self, code, body=b"", ctype="text/plain"):
        self.send_response(code)
        self.send_header("Content-Type", ctype)
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def do_POST(self):
        u = urlparse(self.path)
        if u.path == "/api/v1/publisher/upload":
            # one-part multipart/form-data, parsed by hand (the cgi module is gone from Python 3.13)
            boundary = re.search(r'boundary="?([^";]+)', self.headers["Content-Type"]).group(1).encode()
            body = self.rfile.read(int(self.headers["Content-Length"]))
            part = body.split(b"--" + boundary)[1]
            head, _, data = part.partition(b"\r\n\r\n")
            field = re.search(rb'name="([^"]*)"', head).group(1).decode()
            filename = re.search(rb'filename="([^"]*)"', head).group(1).decode()
            with open(os.path.join(DIR, "bundle.zip"), "wb") as f:
                f.write(data[:-2])  # the CRLF before the closing boundary
            self.record({"field": field, "filename": filename})
            self.reply(201, b"dep-1234")
        elif u.path == "/api/v1/publisher/status":
            self.record()
            n = status_polls.get("n", 0); status_polls["n"] = n + 1
            state = ["PENDING", "VALIDATING", "PUBLISHING"][min(n, 2)]
            if os.environ.get("MOCK_FAIL"):
                state = "FAILED" if n >= 1 else "VALIDATING"
            body = {"deploymentId": "dep-1234", "deploymentName": "x", "deploymentState": state, "purls": []}
            if state == "FAILED":
                body["errors"] = {"pkg:maven/x": ["Missing signature for file: x.jar"]}
            self.reply(200, json.dumps(body).encode(), "application/json")
        else:
            self.record(); self.reply(404)

    def do_PUT(self):
        u = self.record()
        data = self.rfile.read(int(self.headers.get("Content-Length", 0)))
        p = os.path.join(DIR, "put", u.path.lstrip("/"))
        os.makedirs(os.path.dirname(p), exist_ok=True)
        with open(p, "wb") as f:
            f.write(data)
        self.reply(201)

    def do_GET(self):
        u = self.record()
        p = os.path.join(DIR, "put", u.path.lstrip("/"))
        if os.path.isfile(p):
            with open(p, "rb") as f:
                self.reply(200, f.read())
        else:
            self.reply(404)

srv = HTTPServer(("127.0.0.1", 0), Handler)
print(srv.server_address[1], flush=True)
srv.serve_forever()
