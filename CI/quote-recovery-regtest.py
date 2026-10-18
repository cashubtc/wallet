#!/usr/bin/env python3
"""Local-only Bitcoin regtest fixture for native deposit recovery journeys.

Requires a running Bitcoin regtest node and CDK mint with the BDK backend.
Uses a fresh test mnemonic per quote; never use these wallets for real funds.
Requires Python cryptography and mnemonic. The fixture creates and pays quotes, but NEVER
calls a mint/issuance endpoint. Native apps must recover the quote themselves.
"""
import argparse
import base64
import hashlib
import hmac
import json
import time
import urllib.error
import urllib.parse
import urllib.request
from http.server import BaseHTTPRequestHandler, HTTPServer
from pathlib import Path

from mnemonic import Mnemonic

from cryptography.hazmat.primitives.asymmetric import ec
from cryptography.hazmat.primitives.serialization import Encoding, PublicFormat

ORDER = 0xFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFEBAAEDCE6AF48A03BBFD25E8CD0364141


def quote_pubkey(mnemonic, counter):
    """NUT-20 m/129373'/20'/0'/0'/{counter}, independent of proof counters."""
    seed = hashlib.pbkdf2_hmac("sha512", mnemonic.encode(), b"mnemonic", 2048)
    digest = hmac.digest(b"Bitcoin seed", seed, "sha512")
    key, chain = int.from_bytes(digest[:32], "big"), digest[32:]
    for child in [129373 | 0x80000000, 20 | 0x80000000, 0x80000000, 0x80000000, counter]:
        public = ec.derive_private_key(key, ec.SECP256K1()).public_key().public_bytes(Encoding.X962, PublicFormat.CompressedPoint)
        data = b"\0" + key.to_bytes(32, "big") if child & 0x80000000 else public
        digest = hmac.digest(chain, data + child.to_bytes(4, "big"), "sha512")
        key, chain = (int.from_bytes(digest[:32], "big") + key) % ORDER, digest[32:]
    return ec.derive_private_key(key, ec.SECP256K1()).public_key().public_bytes(Encoding.X962, PublicFormat.CompressedPoint).hex()


def request(url, body=None, authorization=None):
    headers = {"Content-Type": "application/json"}
    if authorization:
        headers["Authorization"] = authorization
    data = None if body is None else json.dumps(body).encode()
    with urllib.request.urlopen(urllib.request.Request(url, data=data, headers=headers), timeout=15) as response:
        return json.load(response)


class Fixture:
    def __init__(self, args):
        self.args = args
        self.counter = 7
        self.evidence = Path(args.evidence_dir)
        self.evidence.mkdir(parents=True, exist_ok=True)
        info = self.rpc("getblockchaininfo")
        if info["chain"] != "regtest":
            raise ValueError("Fixture requires Bitcoin regtest")
        info = request(args.mint_url + "/v1/info")
        assert info["nuts"]["20"]["supported"], "Mint must support NUT-20"
        self.rpc("createwallet", ["recovery-funder"], allow_error=True)
        self.address = self.rpc("getnewaddress", wallet=True)
        self.rpc("generatetoaddress", [101, self.address])

    def rpc(self, method, params=None, wallet=False, allow_error=False):
        auth = base64.b64encode(self.args.rpc_auth.encode()).decode()
        url = self.args.bitcoin_rpc + ("/wallet/recovery-funder" if wallet else "")
        try:
            result = request(url, {"jsonrpc": "2.0", "id": "recovery", "method": method, "params": params or []}, "Basic " + auth)
        except urllib.error.HTTPError:
            if allow_error:
                return None
            raise
        if result.get("error") and not allow_error:
            raise ValueError(result["error"])
        return result.get("result")

    def quote(self, quote_id):
        return request(self.args.mint_url + "/v1/mint/quote/onchain/" + quote_id)

    def create(self):
        # NUT-09 intentionally restores every proof from the same seed. Isolate
        # each journey so another test's funds cannot inflate its balance.
        mnemonic = Mnemonic("english").generate(strength=128)
        pubkey = quote_pubkey(mnemonic, self.counter)
        self.counter += 1
        quote = request(self.args.mint_url + "/v1/mint/quote/onchain", {"unit": "sat", "amount": 21000, "pubkey": pubkey})
        assert quote["pubkey"] == pubkey
        quote_id = quote["quote"]
        address = quote["request"].removeprefix("bitcoin:").split("?")[0]
        txid = self.rpc("sendtoaddress", [address, 0.00021], wallet=True)
        blocks = self.rpc("generatetoaddress", [3, self.address])
        deadline = time.monotonic() + 100
        while time.monotonic() < deadline:
            paid = self.quote(quote_id)
            if paid["amount_paid"] == 21000:
                break
            time.sleep(0.5)
        else:
            raise TimeoutError("BDK did not observe confirmed regtest payment")
        assert paid["amount_issued"] == 0, "Fixture must leave the ecash unminted"
        evidence = {"quote": quote_id, "txid": txid, "blocks": blocks, "before_recovery": paid,
                    "confirmations": self.rpc("gettransaction", [txid], wallet=True)["confirmations"]}
        (self.evidence / (quote_id + ".json")).write_text(json.dumps(evidence, indent=2) + "\n")
        return {"mnemonic": mnemonic, "quote": quote_id, "mint_url": self.args.mint_url, "amount": 21000,
                "amount_paid": paid["amount_paid"], "amount_issued": paid["amount_issued"], "confirmations": evidence["confirmations"]}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--mint-url", default="http://127.0.0.1:3341")
    parser.add_argument("--bitcoin-rpc", default="http://127.0.0.1:18483")
    parser.add_argument("--rpc-auth", default="regtest:local-recovery-only", help="Public regtest credentials only")
    parser.add_argument("--port", type=int, default=3340)
    parser.add_argument("--evidence-dir", required=True)
    args = parser.parse_args()
    for endpoint in (args.mint_url, args.bitcoin_rpc):
        parts = urllib.parse.urlparse(endpoint)
        if parts.scheme != "http" or parts.hostname not in ("localhost", "127.0.0.1"):
            parser.error("Only local HTTP mint and Bitcoin RPC endpoints are allowed")
    fixture = Fixture(args)

    class Handler(BaseHTTPRequestHandler):
        def do_GET(self):
            try:
                if self.path == "/new":
                    data = fixture.create()
                elif self.path.startswith("/quote/"):
                    quote_id = self.path.removeprefix("/quote/")
                    data = fixture.quote(quote_id)
                    path = fixture.evidence / (quote_id + ".json")
                    evidence = json.loads(path.read_text())
                    evidence["after_recovery"] = data
                    path.write_text(json.dumps(evidence, indent=2) + "\n")
                elif self.path == "/health":
                    data = {"chain": "regtest"}
                else:
                    self.send_error(404)
                    return
                payload = json.dumps(data).encode()
                self.send_response(200)
            except Exception as error:
                payload = json.dumps({"error": str(error)}).encode()
                self.send_response(500)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(payload)))
            self.end_headers()
            self.wfile.write(payload)

    print(f"Regtest quote fixture listening on localhost:{args.port}", flush=True)
    server = HTTPServer(("127.0.0.1", args.port), Handler)
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        server.server_close()


if __name__ == "__main__":
    main()
