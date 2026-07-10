#!/bin/bash
set -euo pipefail

# start-cdk.sh — Launch CDK mint daemon
# Usage: ./CI/start-cdk.sh

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
BIN_DIR="${SCRIPT_DIR}/.cdk-bin"
WORK_DIR="${SCRIPT_DIR}/.cdk-workdir"
LOG_FILE="${SCRIPT_DIR}/.cdk.log"
PID_FILE="${SCRIPT_DIR}/.cdk.pid"

MINTD_BIN="${BIN_DIR}/cdk-mintd"

if [ ! -x "$MINTD_BIN" ]; then
    echo "❌ cdk-mintd not found. Run ./CI/setup-cdk.sh first"
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

PORT=$(grep '^listen_port' "${WORK_DIR}/config.toml" 2>/dev/null | head -1 | sed 's/.*= *//')
PORT=${PORT:-3339}

echo "🚀 Starting CDK mint on port ${PORT}..."

cd "$WORK_DIR"
nohup "$MINTD_BIN" --work-dir "$WORK_DIR" --enable-logging > "$LOG_FILE" 2>&1 &
MINT_PID=$!
echo "$MINT_PID" > "$PID_FILE"

echo "✅ CDK started (PID: $MINT_PID)"
echo "📝 Log: $LOG_FILE"

echo "⏳ Waiting for mint to be ready on port ${PORT}..."
for i in {1..75}; do
    if curl -sf "http://localhost:${PORT}/v1/info" > /dev/null 2>&1; then
        echo "✅ CDK mint is ready!"
        exit 0
    fi

    if ! kill -0 "$MINT_PID" 2>/dev/null; then
        echo "❌ CDK mint exited before becoming ready"
        cat "$LOG_FILE"
        exit 1
    fi

    sleep 0.2
done

echo "❌ CDK mint failed to start within 15 seconds"
cat "$LOG_FILE"
exit 1
