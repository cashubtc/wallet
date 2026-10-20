#!/usr/bin/env python3
"""Loopback-only mint fault injection for wallet history integration tests.

Forward to a local Nutshell FakeWallet mint. POST /__mint_history/reject or
/accept with {"quote": "..."} controls definitive rejection of that quote's
mint requests. POST /__mint_history/stats returns rejected/successful counts.
Quote creation, invoice settlement, blind signatures and proofs use the real
mint; only the rejected mint HTTP responses are injected. No payloads are logged.
"""
import argparse
import http.client
import http.server
import json
import threading

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("--port", type=int, default=3344)
parser.add_argument("--mint-port", type=int, default=3345)
args = parser.parse_args()
lock = threading.Lock()
quotes = {}


class Handler(http.server.BaseHTTPRequestHandler):
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

    def handle_request(self):
        body = self.rfile.read(int(self.headers.get("Content-Length", "0")))
        payload = json.loads(body) if body else {}
        quote_id = payload.get("quote")
        if self.path.startswith("/__mint_history/"):
            if self.command != "POST" or not isinstance(quote_id, str) or not quote_id:
                return self.respond(400)
            action = self.path.rsplit("/", 1)[-1]
            with lock:
                state = quotes.setdefault(quote_id, {"blocked": False, "rejected": 0, "successful": 0})
                if action == "reject":
                    state["blocked"] = True
                elif action == "accept":
                    state["blocked"] = False
                elif action != "stats":
                    return self.respond(404)
                return self.respond(200, json.dumps(state).encode())
        mint_request = self.command == "POST" and self.path == "/v1/mint/bolt11"
        if mint_request:
            with lock:
                state = quotes.get(quote_id)
                if state and state["blocked"]:
                    state["rejected"] += 1
                    return self.respond(400, b'{"code":20003,"detail":"Minting disabled by local test"}')
        connection = http.client.HTTPConnection("127.0.0.1", args.mint_port, timeout=20)
        try:
            connection.request(self.command, self.path, body, {"Content-Type": "application/json"})
            response = connection.getresponse()
            result = response.read()
            if mint_request and 200 <= response.status < 300:
                with lock:
                    if quote_id in quotes:
                        quotes[quote_id]["successful"] += 1
            self.respond(response.status, result)
        except OSError:
            self.respond(503)
        finally:
            connection.close()


http.server.ThreadingHTTPServer(("127.0.0.1", args.port), Handler).serve_forever()
