#!/usr/bin/env python3
"""
Mock en memoria de Jira Cloud REST v3 + Xray Cloud (auth + GraphQL), solo stdlib.

Sirve para simular la integración sin una instancia real. Valida las credenciales
contra MOCK_JIRA_EMAIL / MOCK_JIRA_TOKEN / MOCK_XRAY_CLIENT_ID / MOCK_XRAY_CLIENT_SECRET,
de modo que se comprueba que el pipeline realmente inyectó los secretos.

Uso: python3 mock/jira_xray_mock.py --port 8089
"""
import argparse
import base64
import json
import os
import re
import secrets
import sys
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import urlparse

ISSUES = {}          # key -> issue
LINKS = []
COUNTERS = {"id": 10000, "QA": 0}
XRAY_TOKENS = set()
TEST_PLAN_TESTS = {}  # planId -> [testIds]


def seed():
    def add(key, summary, itype, subtask=False):
        COUNTERS["id"] += 1
        ISSUES[key] = {
            "id": str(COUNTERS["id"]), "key": key,
            "fields": {
                "summary": summary,
                "project": {"key": key.split("-")[0]},
                "issuetype": {"name": itype, "subtask": subtask},
                "subtasks": [], "labels": [],
            },
        }
        COUNTERS["QA"] = max(COUNTERS["QA"], int(key.split("-")[1]))
    add("QA-100", "Login con biometría en app móvil", "Story")
    add("QA-101", "Transferencias interbancarias inmediatas", "Story")
    for i, s in enumerate(["Login exitoso con huella", "Login con huella no registrada",
                           "Fallback a contraseña", "Bloqueo tras 3 intentos", "Login offline"], start=201):
        add("QA-%d" % i, s, "Test")


def expected_basic():
    raw = "%s:%s" % (os.environ.get("MOCK_JIRA_EMAIL", ""), os.environ.get("MOCK_JIRA_TOKEN", ""))
    return "Basic " + base64.b64encode(raw.encode()).decode()


def new_issue(fields):
    project = fields.get("project", {}).get("key")
    itype = fields.get("issuetype", {}).get("name")
    summary = fields.get("summary")
    errors = {}
    if not project:
        errors["project"] = "project es obligatorio"
    if not itype:
        errors["issuetype"] = "issuetype es obligatorio"
    if not summary:
        errors["summary"] = "summary es obligatorio"
    if "FAIL" in (summary or ""):  # gancho para simular errores parciales en bulk
        errors["summary"] = "Error simulado por el mock"
    parent_key = fields.get("parent", {}).get("key")
    if parent_key and parent_key not in ISSUES:
        errors["parent"] = "El padre %s no existe" % parent_key
    if errors:
        return None, {"errorMessages": [], "errors": errors}

    COUNTERS["id"] += 1
    COUNTERS["QA"] += 1
    key = "%s-%d" % (project, COUNTERS["QA"])
    issue = {"id": str(COUNTERS["id"]), "key": key,
             "fields": dict(fields, subtasks=[],
                            issuetype={"name": itype, "subtask": bool(parent_key)})}
    ISSUES[key] = issue
    if parent_key:
        ISSUES[parent_key]["fields"]["subtasks"].append(
            {"id": issue["id"], "key": key, "fields": {"summary": summary}})
    return {"id": issue["id"], "key": key, "self": "/rest/api/3/issue/" + issue["id"]}, None


class Handler(BaseHTTPRequestHandler):
    server_version = "JiraXrayMock/1.0"

    def log_message(self, fmt, *args):
        sys.stderr.write("[mock] %s %s\n" % (self.command, self.path))

    def send_json(self, status, body):
        data = json.dumps(body).encode()
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def body(self):
        n = int(self.headers.get("Content-Length") or 0)
        return json.loads(self.rfile.read(n) or b"{}")

    def jira_auth_ok(self):
        if self.headers.get("Authorization") != expected_basic():
            self.send_json(401, {"errorMessages": ["Client must be authenticated to access this resource."]})
            return False
        return True

    # ---------- GET ----------
    def do_GET(self):
        path = urlparse(self.path).path
        if path == "/health":
            return self.send_json(200, {"ok": True})
        if not self.jira_auth_ok():
            return
        if path == "/rest/api/3/myself":
            return self.send_json(200, {"displayName": "QE Service Account (mock)",
                                        "emailAddress": os.environ.get("MOCK_JIRA_EMAIL")})
        m = re.match(r"^/rest/api/3/issue/([A-Z][A-Z0-9_]+-\d+)$", path)
        if m:
            issue = ISSUES.get(m.group(1))
            if not issue:
                return self.send_json(404, {"errorMessages": ["Issue does not exist or you do not have permission to see it."]})
            return self.send_json(200, issue)
        self.send_json(404, {"errorMessages": ["mock: ruta no soportada " + path]})

    # ---------- POST ----------
    def do_POST(self):
        path = urlparse(self.path).path

        # --- Xray ---
        if path == "/api/v2/authenticate":
            b = self.body()
            if (b.get("client_id") == os.environ.get("MOCK_XRAY_CLIENT_ID")
                    and b.get("client_secret") == os.environ.get("MOCK_XRAY_CLIENT_SECRET")
                    and b.get("client_id")):
                tok = "mock-xray-" + secrets.token_hex(8)
                XRAY_TOKENS.add(tok)
                return self.send_json(200, tok)
            return self.send_json(401, {"error": "Authentication failed. Invalid client credentials!"})
        if path == "/api/v2/graphql":
            auth = self.headers.get("Authorization", "")
            if auth.replace("Bearer ", "") not in XRAY_TOKENS:
                return self.send_json(401, {"error": "Invalid token"})
            return self.graphql(self.body())

        # --- Jira ---
        if not self.jira_auth_ok():
            return
        if path == "/rest/api/3/issue":
            b = self.body()
            created, err = new_issue(b.get("fields", {}))
            if err:
                return self.send_json(400, err)
            for link in (b.get("update", {}).get("issuelinks") or []):
                add = link.get("add", {})
                LINKS.append({"type": add.get("type", {}).get("name"), "from": created["key"],
                              "to": add.get("outwardIssue", {}).get("key")})
            return self.send_json(201, created)
        if path == "/rest/api/3/issue/bulk":
            ok, errors = [], []
            for i, upd in enumerate(self.body().get("issueUpdates", [])):
                created, err = new_issue(upd.get("fields", {}))
                if err:
                    errors.append({"status": 400, "elementErrors": err, "failedElementNumber": i})
                else:
                    ok.append(created)
            return self.send_json(201, {"issues": ok, "errors": errors})
        if path == "/rest/api/3/search/jql":
            jql = self.body().get("jql", "")
            m = re.search(r"key\s+in\s*\(([^)]*)\)", jql, re.I)
            if m:
                keys = [k.strip().upper() for k in m.group(1).split(",")]
                found = [ISSUES[k] for k in keys if k in ISSUES]
            else:  # JQL simplificado: cualquier otra consulta devuelve los Test del proyecto
                found = [i for i in ISSUES.values() if i["fields"]["issuetype"]["name"] == "Test"]
            return self.send_json(200, {"issues": [{"id": i["id"], "key": i["key"],
                                                    "fields": {"summary": i["fields"]["summary"]}} for i in found]})
        if path == "/rest/api/3/issueLink":
            LINKS.append(self.body())
            return self.send_json(201, {})
        self.send_json(404, {"errorMessages": ["mock: ruta no soportada " + path]})

    def graphql(self, b):
        q = b.get("query", "")
        v = b.get("variables") or {}
        if "addTestsToTestPlan" in q:
            plan = v.get("id")
            if not any(i["id"] == plan for i in ISSUES.values()):
                return self.send_json(200, {"errors": [{"message": "Test Plan con id %s no encontrado" % plan}]})
            current = TEST_PLAN_TESTS.setdefault(plan, [])
            added = [t for t in v.get("tests", []) if t not in current]
            current.extend(added)
            return self.send_json(200, {"data": {"addTestsToTestPlan": {"addedTests": added, "warning": None}}})
        return self.send_json(200, {"errors": [{"message": "mock: operación GraphQL no soportada"}]})


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--port", type=int, default=8089)
    args = ap.parse_args()
    missing = [k for k in ("MOCK_JIRA_EMAIL", "MOCK_JIRA_TOKEN") if not os.environ.get(k)]
    if missing:
        sys.exit("Faltan variables para el mock: " + ", ".join(missing))
    seed()
    srv = ThreadingHTTPServer(("127.0.0.1", args.port), Handler)
    print("Jira/Xray mock escuchando en http://127.0.0.1:%d (issues semilla: %s)"
          % (args.port, ", ".join(sorted(ISSUES))), flush=True)
    srv.serve_forever()


if __name__ == "__main__":
    main()
