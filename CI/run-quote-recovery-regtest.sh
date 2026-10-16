#!/usr/bin/env bash
# Start isolated Bitcoin + CDK/BDK regtest services and the recovery fixture.
# Requires bitcoind, bitcoin-cli, Python cryptography + mnemonic, and a cdk-mintd build
# supporting BDK/bitcoinrpc and database-backed `config init`.
set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
MINTD="${CDK_MINTD_BIN:-cdk-mintd}"
RUN_DIR="${RECOVERY_REGTEST_DIR:-$(mktemp -d "${TMPDIR:-/tmp}/quote-recovery.XXXXXX")}"
mkdir -p "$RUN_DIR/bitcoin" "$RUN_DIR/mint" "$RUN_DIR/evidence"
export RECOVERY_TEST_MINT_SEED='abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about'
export RECOVERY_TEST_BDK_SEED='legal winner thank year wave sausage worth useful legal winner thank yellow'
export RECOVERY_TEST_RPC_PASSWORD='local-recovery-only'
BITCOIN_PID=''
MINT_PID=''
cleanup() {
    if [ -n "$MINT_PID" ]; then kill "$MINT_PID" 2>/dev/null || true; wait "$MINT_PID" 2>/dev/null || true; fi
    if [ -n "$BITCOIN_PID" ]; then kill "$BITCOIN_PID" 2>/dev/null || true; wait "$BITCOIN_PID" 2>/dev/null || true; fi
}
trap cleanup EXIT
trap 'exit 130' INT TERM
bitcoind -regtest -datadir="$RUN_DIR/bitcoin" -server -listen=0 -rpcport=18483 \
    -rpcuser=regtest -rpcpassword="$RECOVERY_TEST_RPC_PASSWORD" -fallbackfee=0.00001 > "$RUN_DIR/bitcoin.log" 2>&1 &
BITCOIN_PID=$!
bitcoin-cli -regtest -datadir="$RUN_DIR/bitcoin" -rpcport=18483 -rpcuser=regtest \
    -rpcpassword="$RECOVERY_TEST_RPC_PASSWORD" -rpcwait -rpcwaittimeout=20 getblockchaininfo > /dev/null
# These are public test seeds. Both services are restricted to loopback/regtest.
cat > "$RUN_DIR/mint.toml" <<'EOF'
[info]
url = "http://127.0.0.1:3341"
listen_host = "127.0.0.1"
listen_port = 3341
mnemonic = "env:RECOVERY_TEST_MINT_SEED"
input_fee_ppk = 0
[info.logging]
output = "stderr"
console_level = "info"
[mint_info]
name = "Quote recovery regtest mint"
[database]
engine = "sqlite"
[payment_backend]
backend = "none"
[onchain]
onchain_backend = "bdk"
min_mint = 1000
max_mint = 1000000
[bdk]
mnemonic = "env:RECOVERY_TEST_BDK_SEED"
network = "regtest"
num_confs = 3
min_receive_amount_sat = 1000
min_send_amount_sat = 546
chain_source_type = "bitcoinrpc"
bitcoind_rpc_host = "127.0.0.1"
bitcoind_rpc_port = 18483
bitcoind_rpc_user = "regtest"
bitcoind_rpc_password = "env:RECOVERY_TEST_RPC_PASSWORD"
EOF
"$MINTD" -w "$RUN_DIR/mint" config init --file "$RUN_DIR/mint.toml" > "$RUN_DIR/mint-init.log" 2>&1
"$MINTD" -w "$RUN_DIR/mint" --enable-logging > "$RUN_DIR/mint.log" 2>&1 &
MINT_PID=$!
for attempt in {1..75}; do
    if curl --silent --fail http://127.0.0.1:3341/v1/info > /dev/null; then break; fi
    if ! kill -0 "$MINT_PID" 2>/dev/null; then cat "$RUN_DIR/mint.log"; exit 1; fi
    sleep 0.2
done
printf 'Regtest evidence and logs: %s\n' "$RUN_DIR"
python3 "$SCRIPT_DIR/quote-recovery-regtest.py" --evidence-dir "$RUN_DIR/evidence"
