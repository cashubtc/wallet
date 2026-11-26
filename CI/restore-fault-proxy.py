#!/usr/bin/env python3
"""Loopback-only fault injection for long-running seed-restore tests.

Forwards every request to a local mint. Only POST /v1/restore (NUT-09) is
affected, so a restore that scans many batches can be slowed down and cut off
part-way while the rest of the mint API stays untouched.

Control endpoints (POST, JSON body, loopback only):
  /__restore/config  {"delay_ms": 50, "fail_after": 120, "mode": "drop"|"503"}
      delay_ms    sleep before forwarding each restore request
      fail_after  let this many more restore requests succeed, then fail every
                  later one until /__restore/heal (null disables failing)
      mode        drop closes the connection without a reply; 503 answers 503
  /__restore/heal    stop failing restore requests
  /__restore/stats   {"forwarded": n, "failed": n, "fail_after": n|null, ...}

Request and response bodies are never logged.
"""
import argparse
import http.client
import http.server
import json
import threading
import time

parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
parser.add_argument("--port", type=int, default=3341)
parser.add_argument("--mint-port", type=int, default=3340)
args = parser.parse_args()

lock = threading.Lock()
state = {"delay_ms": 0, "fail_after": None, "mode": "drop", "forwarded": 0, "failed": 0}


class Handler(http.server.BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def log_message(self, *_):
        pass

    def respond(self, status, body=b"{}"):
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def do_GET(self):
        self.handle_request()

    def do_POST(self):
        self.handle_request()

    def handle_control(self, body):
        action = self.path.rsplit("/", 1)[-1]
        with lock:
            if action == "config":
                payload = json.loads(body) if body else {}
                for key in ("delay_ms", "fail_after", "mode"):
                    if key in payload:
                        state[key] = payload[key]
            elif action == "heal":
                state["fail_after"] = None
            elif action != "stats":
                return self.respond(404)
            return self.respond(200, json.dumps(state).encode())

    def handle_request(self):
        body = self.rfile.read(int(self.headers.get("Content-Length", "0")))
        if self.path.startswith("/__restore/"):
            return self.handle_control(body)

        if self.command == "POST" and self.path == "/v1/restore":
            with lock:
                delay = state["delay_ms"]
                remaining = state["fail_after"]
                mode = state["mode"]
                fail = remaining is not None and remaining <= 0
                if remaining is not None and remaining > 0:
                    state["fail_after"] = remaining - 1
                if fail:
                    state["failed"] += 1
                else:
                    state["forwarded"] += 1
            if delay:
                time.sleep(delay / 1000)
            if fail:
                if mode == "503":
                    return self.respond(503, b'{"detail":"injected restore failure"}')
                self.close_connection = True
                self.connection.close()
                return

        upstream = http.client.HTTPConnection("127.0.0.1", args.mint_port, timeout=120)
        headers = {k: v for k, v in self.headers.items() if k.lower() not in ("host", "connection")}
        upstream.request(self.command, self.path, body=body or None, headers=headers)
        response = upstream.getresponse()
        data = response.read()
        self.send_response(response.status)
        for key, value in response.getheaders():
            if key.lower() not in ("transfer-encoding", "connection", "content-length"):
                self.send_header(key, value)
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)
        upstream.close()


class Server(http.server.ThreadingHTTPServer):
    daemon_threads = True


Server(("127.0.0.1", args.port), Handler).serve_forever()
