#!/bin/bash
set -euo pipefail

# start-nutshell.sh — Launch Nutshell mint with FakeWallet backend
# Usage: ./CI/start-nutshell.sh [port]

PORT=${1:-3338}
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
VENV_DIR="${SCRIPT_DIR}/.nutshell-venv"
LOG_FILE="${SCRIPT_DIR}/.nutshell.log"
PID_FILE="${SCRIPT_DIR}/.nutshell.pid"

if [ ! -d "$VENV_DIR" ]; then
    echo "❌ Nutshell venv not found. Run ./CI/setup-nutshell.sh first"
    exit 1
fi

# Kill any existing mint
if [ -f "$PID_FILE" ]; then
    if pid=$(cat "$PID_FILE") && kill -0 "$pid" 2>/dev/null; then
        kill "$pid" 2>/dev/null || true
        sleep 1
    fi
    rm -f "$PID_FILE"
fi

echo "🚀 Starting Nutshell mint on port ${PORT}..."

WORKDIR="${SCRIPT_DIR}/.nutshell-workdir"
mkdir -p "$WORKDIR"

CONFIG_FILE="${WORKDIR}/.env"
mkdir -p "${WORKDIR}/cashu"
# Nutshell looks for ./.env first, then falls back to ~/.cashu/.env. Keep the
# CI mint hermetic so local user configuration cannot change integration tests.
: > "$CONFIG_FILE"

# Nutshell (cashu) reads configuration from environment variables.
export CASHU_DIR="${WORKDIR}/cashu"
export MINT_URL="http://localhost:${PORT}"
export MINT_HOST=localhost
export MINT_PORT="$PORT"
export MINT_LISTEN_HOST=127.0.0.1
export MINT_LISTEN_PORT="$PORT"
export MINT_DATABASE="$WORKDIR"
export MINT_BACKEND_BOLT11_SAT=FakeWallet
export MINT_PRIVATE_KEY="TEST_PRIVATE_KEY_DO_NOT_USE_IN_PRODUCTION"
# Zero input fee so integration tests can assert exact amounts.
export MINT_INPUT_FEE_PPK=0
# Make FakeWallet integration tests deterministic and fast.
export FAKEWALLET_DELAY_INCOMING_PAYMENT=0
export FAKEWALLET_DELAY_OUTGOING_PAYMENT=0
export MINT_QUOTE_BACKEND_CHECK_RATE_LIMIT=0

pushd "$WORKDIR" > /dev/null
nohup "$VENV_DIR/bin/mint" > "$LOG_FILE" 2>&1 < /dev/null &
MINT_PID=$!
disown "$MINT_PID" 2>/dev/null || true
popd > /dev/null
echo "$MINT_PID" > "$PID_FILE"

echo "✅ Nutshell started (PID: $MINT_PID)"
echo "📝 Log: $LOG_FILE"

# Wait for mint to be ready
echo "⏳ Waiting for mint to be ready..."
for i in {1..30}; do
    if curl -sf "http://localhost:${PORT}/v1/info" > /dev/null 2>&1; then
        echo "✅ Mint is ready!"
        exit 0
    fi
    sleep 1
done

echo "❌ Mint failed to start within 30 seconds"
cat "$LOG_FILE"
exit 1
