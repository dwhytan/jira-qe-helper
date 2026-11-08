#!/usr/bin/env python3
"""
Mock en memoria de los endpoints de Jira Cloud que usa QE Helper (solo stdlib).
Valida Basic auth contra MOCK_JIRA_EMAIL / MOCK_JIRA_TOKEN: así se comprueba que
el pipeline inyectó los secretos, igual que haría Jira real (401 si no).

Uso: MOCK_JIRA_EMAIL=... MOCK_JIRA_TOKEN=... python3 mock/jira_mock.py --port 8089
"""
import argparse
import base64
import json
import os
import re
import sys
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

ISSUES = {
    "QA-100": {"summary": "Login con biometría en app móvil", "subtasks": [], "comments": []},
    "QA-101": {"summary": "Transferencias interbancarias inmediatas", "subtasks": [], "comments": []},
    "QA-102": {"summary": "Pago de servicios con QR", "subtasks": [], "comments": []},
}
SUBTASK_TYPE = {"id": "10003", "name": "Subtarea", "subtask": True}
NEXT = [200]


class Handler(BaseHTTPRequestHandler):
    def log_message(self, fmt, *args):
        sys.stderr.write("[mock] %s %s\n" % (self.command, self.path))

    def reply(self, status, body):
        data = json.dumps(body).encode()
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def authorized(self):
        raw = "%s:%s" % (os.environ["MOCK_JIRA_EMAIL"], os.environ["MOCK_JIRA_TOKEN"])
        if self.headers.get("Authorization") == "Basic " + base64.b64encode(raw.encode()).decode():
            return True
        self.reply(401, {"errorMessages": ["Client must be authenticated to access this resource."]})
        return False

    def do_GET(self):
        path = self.path.split("?")[0]
        if path == "/health":
            return self.reply(200, {"ok": True})
        if not self.authorized():
            return
        m = re.match(r"^/rest/api/3/issue/([A-Z0-9_]+-\d+)$", path)
        if m:
            issue = ISSUES.get(m.group(1))
            if not issue:
                return self.reply(404, {"errorMessages": ["Issue does not exist or you do not have permission to see it."]})
            return self.reply(200, {"key": m.group(1), "fields": {
                "summary": issue["summary"], "project": {"key": m.group(1).split("-")[0]},
                "subtasks": [{"key": k, "fields": {"summary": s}} for k, s in issue["subtasks"]],
                "comment": {"total": len(issue["comments"]), "comments": issue["comments"]}}})
        if re.match(r"^/rest/api/3/project/[A-Z0-9_]+$", path):
            return self.reply(200, {"issueTypes": [{"id": "10001", "name": "Historia", "subtask": False}, SUBTASK_TYPE]})
        self.reply(404, {"errorMessages": ["mock: ruta no soportada " + path]})

    def do_POST(self):
        if not self.authorized():
            return
        body = json.loads(self.rfile.read(int(self.headers.get("Content-Length") or 0)) or b"{}")
        m = re.match(r"^/rest/api/3/issue/([A-Z0-9_]+-\d+)/comment$", self.path)
        if m:
            issue = ISSUES.get(m.group(1))
            if not issue:
                return self.reply(404, {"errorMessages": ["Issue does not exist"]})
            if body.get("body", {}).get("type") != "doc":
                return self.reply(400, {"errorMessages": ["Comment body is not valid ADF"]})
            issue["comments"].append({"id": str(len(issue["comments"]) + 1), "body": body["body"]})
            return self.reply(201, issue["comments"][-1])
        if self.path != "/rest/api/3/issue/bulk":
            return self.reply(404, {"errorMessages": ["mock: ruta no soportada " + self.path]})
        issues, errors = [], []
        for i, upd in enumerate(body.get("issueUpdates", [])):
            f = upd.get("fields", {})
            parent = f.get("parent", {}).get("key")
            if parent not in ISSUES or f.get("issuetype", {}).get("id") != SUBTASK_TYPE["id"]:
                errors.append({"status": 400, "failedElementNumber": i,
                               "elementErrors": {"errors": {"parent/issuetype": "inválido"}}})
                continue
            NEXT[0] += 1
            key = "QA-%d" % NEXT[0]
            ISSUES[parent]["subtasks"].append((key, f.get("summary")))
            issues.append({"id": str(10000 + NEXT[0]), "key": key})
        self.reply(201, {"issues": issues, "errors": errors})


if __name__ == "__main__":
    ap = argparse.ArgumentParser()
    ap.add_argument("--port", type=int, default=8089)
    port = ap.parse_args().port
    missing = [k for k in ("MOCK_JIRA_EMAIL", "MOCK_JIRA_TOKEN") if not os.environ.get(k)]
    if missing:
        sys.exit("Faltan secretos para el mock: " + ", ".join(missing))
    print("Jira mock en http://127.0.0.1:%d (issues: %s)" % (port, ", ".join(ISSUES)), flush=True)
    ThreadingHTTPServer(("127.0.0.1", port), Handler).serve_forever()
