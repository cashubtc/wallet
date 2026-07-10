#!/bin/bash
set -euo pipefail

# setup-cdk.sh — Download and configure the prebuilt cdk-mintd release.
# Usage: ./CI/setup-cdk.sh [port]

PORT=${1:-3339}
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
CDK_VERSION="0.17.3-rc.0"
BIN_DIR="${SCRIPT_DIR}/.cdk-bin"
WORK_DIR="${SCRIPT_DIR}/.cdk-workdir"
MINTD_BIN="${BIN_DIR}/cdk-mintd"

echo "🔧 Setting up CDK mint (v${CDK_VERSION}) on port ${PORT}..."

OS="$(uname -s | tr '[:upper:]' '[:lower:]')"
ARCH="$(uname -m)"

case "${OS}-${ARCH}" in
    darwin-arm64|darwin-aarch64)
        ASSET_NAME="cdk-mintd-${CDK_VERSION}-aarch64-apple-darwin"
        EXPECTED_SHA256="f0173e8f6d189e488e1349a8fbbfebc100d681c3ec856a5d137d4dddba76a547"
        ;;
    linux-arm64|linux-aarch64)
        ASSET_NAME="cdk-mintd-${CDK_VERSION}-aarch64"
        EXPECTED_SHA256="52bec7013cedfdbbefe6c294786cb6b72b8054186acc79a3fc533ba8240c8bdc"
        ;;
    linux-x86_64)
        ASSET_NAME="cdk-mintd-${CDK_VERSION}-x86_64"
        EXPECTED_SHA256="01316b33ca30fdfa25e33a7491d0d3b451cdbd24e44d085f1d1b616095dc659f"
        ;;
    *)
        echo "❌ No prebuilt cdk-mintd v${CDK_VERSION} binary for ${OS}/${ARCH}"
        exit 1
        ;;
esac

sha256() {
    if command -v shasum >/dev/null 2>&1; then
        shasum -a 256 "$1" | awk '{print $1}'
    elif command -v sha256sum >/dev/null 2>&1; then
        sha256sum "$1" | awk '{print $1}'
    else
        echo "❌ Neither shasum nor sha256sum is available" >&2
        return 1
    fi
}

mkdir -p "$BIN_DIR"

if [ -f "$MINTD_BIN" ] && [ "$(sha256 "$MINTD_BIN")" = "$EXPECTED_SHA256" ]; then
    echo "✅ Reusing verified cdk-mintd binary"
else
    DOWNLOAD_URL="https://github.com/cashubtc/cdk/releases/download/v${CDK_VERSION}/${ASSET_NAME}"
    DOWNLOAD_PATH="${MINTD_BIN}.download"

    echo "📥 Downloading ${ASSET_NAME}..."
    curl --retry 3 --retry-all-errors -fsSL -o "$DOWNLOAD_PATH" "$DOWNLOAD_URL"

    ACTUAL_SHA256="$(sha256 "$DOWNLOAD_PATH")"
    if [ "$ACTUAL_SHA256" != "$EXPECTED_SHA256" ]; then
        rm -f "$DOWNLOAD_PATH"
        echo "❌ CDK checksum mismatch: expected $EXPECTED_SHA256, got $ACTUAL_SHA256"
        exit 1
    fi

    mv "$DOWNLOAD_PATH" "$MINTD_BIN"
    chmod +x "$MINTD_BIN"
    echo "✅ Download verified (SHA-256: $EXPECTED_SHA256)"
fi

chmod +x "$MINTD_BIN"
if ! "$MINTD_BIN" --version | grep -q "$CDK_VERSION"; then
    echo "❌ Downloaded cdk-mintd did not report version $CDK_VERSION"
    exit 1
fi

mkdir -p "$WORK_DIR"

cat > "${WORK_DIR}/config.toml" << EOF
# CDK mint config for integration tests. Never use this seed in production.
[info]
url = "http://127.0.0.1:${PORT}"
listen_host = "127.0.0.1"
listen_port = ${PORT}
mnemonic = "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about"
input_fee_ppk = 0

[info.logging]
output = "stderr"
console_level = "info"

[mint_info]
name = "CDK CI Mint"
description = "Local CDK integration-test mint"

[database]
engine = "sqlite"

[ln]
ln_backend = "fakewallet"
unit = "sat"
min_mint = 1
max_mint = 500000
min_melt = 1
max_melt = 500000

[fake_wallet]
supported_units = ["sat"]
fee_percent = 0.0
reserve_fee_min = 0
custom_payment_methods = []
min_delay_time = 0
max_delay_time = 0
EOF

echo "✅ Config written to ${WORK_DIR}/config.toml"
echo "🚀 Start with: ./CI/start-cdk.sh"
