#!/usr/bin/env python3
"""A tiny stand-in for the Saiku launcher's REST surface, for offline self-check tests.

Serves just what infra/preview/selfcheck.sh touches:

  GET  /rest/saiku/info          200
  GET  /ui/                      200 (or 404 when ui=false)
  POST /rest/saiku/session       form login; 200 on a known user/password, else 401
  GET  /rest/saiku/session       the session map for the cookie holder
  GET  /rest/saiku/api/ai/cubes  the cube list, only with a session cookie (else 401)

Configuration is a JSON object in FAKE_SAIKU_CONFIG:
  users   {name: password}        default {"admin": "good-password"}
  ui      bool                    default true
  cubes   list of cube objects    default: a FoodMart Sales cube
  info    HTTP status for /info   default 200

The chosen port is written to FAKE_SAIKU_PORT_FILE. Stdlib only.
"""
import json
import os
import sys
from http.server import BaseHTTPRequestHandler, HTTPServer
from urllib.parse import parse_qs

CFG = json.loads(os.environ.get("FAKE_SAIKU_CONFIG", "{}"))
USERS = CFG.get("users", {"admin": "good-password"})
UI = CFG.get("ui", True)
INFO_STATUS = CFG.get("info", 200)
CUBES = CFG.get(
    "cubes",
    [
        {"connectionName": "foodmart", "catalog": "FoodMart", "schema": "FoodMart", "cubeName": "Sales"},
        {"connectionName": "bank", "catalog": "Bank", "schema": "Bank", "cubeName": "Accounts"},
    ],
)
COOKIE = "JSESSIONID=fake-session"


class Handler(BaseHTTPRequestHandler):
    def log_message(self, *_):
        pass

    def _send(self, status, body=b"", headers=None):
        self.send_response(status)
        for k, v in (headers or {}).items():
            self.send_header(k, v)
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def _authed(self):
        return COOKIE in (self.headers.get("Cookie") or "")

    def do_GET(self):
        path = self.path.split("?")[0]
        if path == "/rest/saiku/info":
            self._send(INFO_STATUS, b"{}")
        elif path == "/ui/":
            self._send(200 if UI else 404, b"<html></html>")
        elif path == "/rest/saiku/session":
            if not self._authed():
                self._send(401, b"")
            else:
                body = json.dumps({"username": "admin", "isadmin": True}, separators=(",", ":")).encode()
                self._send(200, body, {"Content-Type": "application/json"})
        elif path == "/rest/saiku/api/ai/cubes":
            if not self._authed():
                self._send(401, b"")
            else:
                self._send(200, json.dumps(CUBES, separators=(",", ":")).encode(), {"Content-Type": "application/json"})
        else:
            self._send(404, b"")

    def do_POST(self):
        if self.path.split("?")[0] != "/rest/saiku/session":
            return self._send(404, b"")
        length = int(self.headers.get("Content-Length") or 0)
        form = parse_qs(self.rfile.read(length).decode())
        user = (form.get("username") or [""])[0]
        password = (form.get("password") or [""])[0]
        if USERS.get(user) == password and password != "":
            self._send(200, b"", {"Set-Cookie": f"{COOKIE}; Path=/"})
        else:
            self._send(401, b"Authentication failed")


if __name__ == "__main__":
    server = HTTPServer(("127.0.0.1", 0), Handler)
    port_file = os.environ.get("FAKE_SAIKU_PORT_FILE")
    if port_file:
        with open(port_file, "w") as fh:
            fh.write(str(server.server_port))
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        sys.exit(0)
