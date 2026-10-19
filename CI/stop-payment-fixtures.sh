#!/usr/bin/env bash
set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PID_FILE="${SCRIPT_DIR}/.payment-workdir/pids"
if [ -f "$PID_FILE" ]; then
    while read -r pid; do
        kill "$pid" 2>/dev/null || true
    done < "$PID_FILE"
    # A restart must not mistake the old listeners for newly started fixtures.
    for _ in {1..50}; do
        alive=false
        while read -r pid; do
            if kill -0 "$pid" 2>/dev/null; then alive=true; fi
        done < "$PID_FILE"
        if [ "$alive" = false ]; then break; fi
        sleep 0.1
    done
    rm -f "$PID_FILE"
fi
