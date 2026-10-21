"""Local LNURL/mint stubs plus a caching DNSSEC DNS-over-TCP relay.

Only documented public BIP-353 test records are fetched. No
recipient HTTP or mint traffic is forwarded. Use --offline to require the cache.
"""
import argparse
import hashlib
import http.server
import json
from pathlib import Path
import socketserver
import ssl
import struct
import threading
import time
import urllib.parse
import urllib.request
from datetime import datetime, timedelta, timezone

import bolt11
from coincurve import PrivateKey
from cryptography import x509
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import rsa
from cryptography.x509.oid import NameOID

STATE = {"events": [], "lnurl_status": 200, "bolt12": True, "mint_failure": False, "dns_failure": False}
LOCK = threading.Lock()
KEYS = {str(2**i): PrivateKey.from_int(i + 1).public_key.format().hex() for i in range(32)}
KEYSET = "00" + hashlib.sha256(b"".join(bytes.fromhex(k) for k in KEYS.values())).hexdigest()[:14]


def event(kind, **values):
    with LOCK:
        STATE["events"].append({"kind": kind, **values})


def invoice(amount_msat):
    return bolt11.encode(bolt11.Bolt11(currency="bc", date=int(time.time()), amount_msat=amount_msat,
        tags=bolt11.Tags([
            bolt11.Tag(bolt11.TagChar.payment_hash, "11" * 32),
            bolt11.Tag(bolt11.TagChar.payment_secret, "22" * 32),
            bolt11.Tag(bolt11.TagChar.description, "LNURL native binding fixture"),
            bolt11.Tag(bolt11.TagChar.features, bolt11.Features.from_feature_list({
                bolt11.Feature.var_onion_optin: bolt11.FeatureState.supported,
                bolt11.Feature.payment_secret: bolt11.FeatureState.required,
            })),
        ])), private_key="01" * 32)


def certificates(directory):
    directory.mkdir(parents=True, exist_ok=True)
    if (directory / "ca.pem").exists():
        return
    key = rsa.generate_private_key(public_exponent=65537, key_size=2048)
    name = x509.Name([x509.NameAttribute(NameOID.COMMON_NAME, "Wallet fixture CA")])
    now = datetime.now(timezone.utc)
    ca = (x509.CertificateBuilder().subject_name(name).issuer_name(name).public_key(key.public_key())
        .serial_number(x509.random_serial_number()).not_valid_before(now - timedelta(days=1))
        .not_valid_after(now + timedelta(days=30)).add_extension(x509.BasicConstraints(ca=True, path_length=None), True)
        .add_extension(x509.KeyUsage(True, False, False, False, False, True, True, False, False), True)
        .sign(key, hashes.SHA256()))
    leaf_key = rsa.generate_private_key(public_exponent=65537, key_size=2048)
    leaf = (x509.CertificateBuilder().subject_name(x509.Name([x509.NameAttribute(NameOID.COMMON_NAME, "twelve.cash")]))
        .issuer_name(name).public_key(leaf_key.public_key()).serial_number(x509.random_serial_number())
        .not_valid_before(now - timedelta(days=1)).not_valid_after(now + timedelta(days=30))
        .add_extension(x509.BasicConstraints(ca=False, path_length=None), True)
        .add_extension(x509.SubjectAlternativeName([x509.DNSName("twelve.cash"), x509.DNSName("mattcorallo.com")]), False)
        .sign(key, hashes.SHA256()))
    (directory / "ca.pem").write_bytes(ca.public_bytes(serialization.Encoding.PEM))
    (directory / "leaf.pem").write_bytes(leaf.public_bytes(serialization.Encoding.PEM))
    (directory / "leaf.key").write_bytes(leaf_key.private_bytes(serialization.Encoding.PEM, serialization.PrivateFormat.PKCS8, serialization.NoEncryption()))


class HTTP(http.server.BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def log_message(self, *_):
        pass

    def reply(self, value, status=200, raw=False):
        data = value if raw else json.dumps(value).encode()
        self.send_response(status)
        self.send_header("Content-Length", str(len(data)))
        self.send_header("Content-Type", "application/octet-stream" if raw else "application/json")
        self.send_header("Connection", "close")
        self.end_headers()
        self.wfile.write(data)
        self.close_connection = True

    def do_GET(self):
        url = urllib.parse.urlsplit(self.path)
        path = url.path
        if path == "/ca.pem":
            return self.reply((ARGS.directory / "ca.pem").read_bytes(), raw=True)
        if path == "/state":
            with LOCK:
                snapshot = dict(STATE, events=list(STATE["events"]))
            return self.reply(snapshot)
        if path in ["/.well-known/lnurlp/test", "/.well-known/lnurlp/matt"]:
            event("lnurl-discovery", status=STATE["lnurl_status"])
            if STATE["lnurl_status"] != 200:
                return self.reply({"error": "recipient fixture failure"}, STATE["lnurl_status"])
            return self.reply({"tag": "payRequest", "callback": "https://" + self.headers["Host"] + "/invoice",
                "minSendable": 1000, "maxSendable": 100000000, "metadata": '[["text/plain","fixture"]]'})
        if path == "/invoice":
            amount = int(urllib.parse.parse_qs(url.query)["amount"][0])
            event("lnurl-callback", amount_msat=amount)
            return self.reply({"pr": invoice(amount)})
        if path.endswith("/v1/info"):
            methods = [{"method": method, "unit": "sat", "min_amount": 1, "max_amount": 1000000, "amountless": True}
                for method in (["bolt11", "bolt12"] if STATE["bolt12"] else ["bolt11"])]
            return self.reply({"name": "Lightning Address stub mint", "nuts": {"4": {"methods": methods, "disabled": False}, "5": {"methods": methods, "disabled": False}}})
        if path.endswith("/v1/keysets"):
            return self.reply({"keysets": [{"id": KEYSET, "unit": "sat", "active": True, "input_fee_ppk": 0}]})
        if "/v1/keys" in path:
            return self.reply({"keysets": [{"id": KEYSET, "unit": "sat", "keys": KEYS}]})
        return self.reply({"error": "unconfigured path"}, 404)

    def do_POST(self):
        body = json.loads(self.rfile.read(int(self.headers.get("Content-Length", 0))) or b"{}")
        path = urllib.parse.urlsplit(self.path).path
        if path == "/scenario":
            allowed = {"lnurl_status", "bolt12", "mint_failure", "dns_failure"}
            if not set(body) <= allowed:
                return self.reply({"error": "invalid scenario"}, 400)
            with LOCK:
                STATE.update(lnurl_status=200, bolt12=True, mint_failure=False, dns_failure=False, events=[])
                STATE.update(body)
            return self.reply({"ready": True})
        if "/v1/melt/quote/" in path:
            method = path.rsplit("/", 1)[-1]
            event("mint-quote", method=method, request=body["request"], options=body.get("options"))
            if STATE["mint_failure"]:
                # An actual dropped native mint HTTP connection, not a fabricated FFI exception.
                self.close_connection = True
                self.connection.close()
                return
            return self.reply({"quote": "fixture-" + method, "amount": 100, "fee_reserve": 1,
                "state": "UNPAID", "expiry": int(time.time()) + 600, "unit": "sat", "method": method})
        return self.reply({"error": "unconfigured path"}, 404)


def read_exact(stream, length):
    data = b""
    while len(data) < length:
        part = stream.read(length - len(data))
        if not part:
            raise EOFError()
        data += part
    return data


class DNS(socketserver.StreamRequestHandler):
    def handle(self):
        while True:
            try:
                query = read_exact(self.rfile, struct.unpack("!H", read_exact(self.rfile, 2))[0])
            except EOFError:
                return
            offset, labels = 12, []
            while query[offset]:
                size = query[offset]
                labels.append(query[offset + 1:offset + 1 + size].decode())
                offset += size + 1
            name = ".".join(labels)
            qtype = struct.unpack("!H", query[offset + 1:offset + 3])[0]
            # The proof builder requests the signed leaf, its zone, TLD and root.
            if name not in ["test.user._bitcoin-payment.twelve.cash", "twelve.cash", "cash", "matt.user._bitcoin-payment.mattcorallo.com", "mattcorallo.com", "com", ""]:
                raise ValueError("Unconfigured DNS name")
            event("dns", name=name, qtype=qtype)
            if STATE["dns_failure"]:
                return  # Real socket failure reaches CDK's BIP-353 error wrapper.
            key = hashlib.sha256(query[2:]).hexdigest()
            cache = ARGS.directory / ("dns-" + key + ".bin")
            if cache.exists():
                response = cache.read_bytes()
            else:
                if ARGS.offline:
                    raise RuntimeError("Offline DNS fixture cache miss")
                request = urllib.request.Request("https://dns.google/dns-query", query,
                    headers={"Content-Type": "application/dns-message", "Accept": "application/dns-message"})
                response = urllib.request.urlopen(request, timeout=15).read()
                cache.write_bytes(response)
            response = query[:2] + response[2:]
            self.wfile.write(struct.pack("!H", len(response)) + response)
            self.wfile.flush()


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--directory", type=Path, required=True)
    parser.add_argument("--offline", action="store_true")
    ARGS = parser.parse_args()
    certificates(ARGS.directory)
    TLS = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
    TLS.load_cert_chain(ARGS.directory / "leaf.pem", ARGS.directory / "leaf.key")
    for port in [18444, 18445]:
        server = http.server.ThreadingHTTPServer(("127.0.0.1", port), HTTP)
        if port == 18445:
            server.socket = TLS.wrap_socket(server.socket, server_side=True)
        threading.Thread(target=server.serve_forever, daemon=True).start()
    socketserver.ThreadingTCPServer.allow_reuse_address = True
    server = socketserver.ThreadingTCPServer(("127.0.0.1", 15353), DNS)
    print("Lightning fixtures ready", flush=True)
    server.serve_forever()
