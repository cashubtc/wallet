#!/bin/bash
set -euo pipefail

# setup-cdk.sh — Get cdk-mintd binary ready
#   Linux:  download prebuilt binary + checksum verify
#   macOS:  build from source with cargo (Rust must be installed)
# Usage: ./CI/setup-cdk.sh [port]

PORT=${1:-3339}
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
CDK_VERSION="0.17.1"
BIN_DIR="${SCRIPT_DIR}/.cdk-bin"
WORK_DIR="${SCRIPT_DIR}/.cdk-workdir"

echo "🔧 Setting up CDK mint (v${CDK_VERSION}) on port ${PORT}..."

mkdir -p "$BIN_DIR"
MINTD_BIN="${BIN_DIR}/cdk-mintd"

# Detect platform & arch
OS=$(uname -s | tr '[:upper:]' '[:lower:]')
ARCH=$(uname -m)
case "${ARCH}" in
    x86_64)  ASSET_ARCH="x86_64" ;;
    aarch64) ASSET_ARCH="aarch64" ;;
    arm64)   ASSET_ARCH="aarch64" ;;
    *)       echo "❌ Unsupported arch: ${ARCH}"; exit 1 ;;
esac

if [ -x "$MINTD_BIN" ]; then
    echo "✅ cdk-mintd already exists at ${MINTD_BIN}"
elif [ "$OS" = "darwin" ]; then
    echo "⚠️  Prebuilt binary v${CDK_VERSION} is Linux-only."
    echo "   macOS detected (${ARCH}). Building from source with cargo..."

    cargo install --git https://github.com/cashubtc/cdk --tag "v${CDK_VERSION}" --root "${BIN_DIR}/.cargo" cdk-mintd 2>&1

    # Move binary to expected location and clean up
    mv "${BIN_DIR}/.cargo/bin/cdk-mintd" "$MINTD_BIN"
    rm -rf "${BIN_DIR}/.cargo"

    echo "✅ Built cdk-mintd v${CDK_VERSION} from source"
else
    # Linux: download prebuilt + checksum verify
    ASSET_NAME="cdk-mintd-${CDK_VERSION}-${ASSET_ARCH}"
    ASSET_PATH="${BIN_DIR}/${ASSET_NAME}"
    CHECKSUM_FILE="${BIN_DIR}/SHA256SUMS"
    DOWNLOAD_URL="https://github.com/cashubtc/cdk/releases/download/v${CDK_VERSION}/${ASSET_NAME}"
    CHECKSUM_URL="https://github.com/cashubtc/cdk/releases/download/v${CDK_VERSION}/SHA256SUMS"

    echo "📥 Downloading ${ASSET_NAME}..."
    curl -fsSL -o "$ASSET_PATH" "$DOWNLOAD_URL"
    curl -fsSL -o "$CHECKSUM_FILE" "$CHECKSUM_URL"

    (cd "$BIN_DIR" && sha256sum -c SHA256SUMS --ignore-missing)
    mv "$ASSET_PATH" "$MINTD_BIN"
    rm -f "$CHECKSUM_FILE"
    chmod +x "$MINTD_BIN"

    echo "✅ cdk-mintd binary ready at ${MINTD_BIN}"
fi

# Create work directory with config
mkdir -p "$WORK_DIR"
SEED_FILE="${WORK_DIR}/mint-seed"

if [ ! -s "$SEED_FILE" ]; then
    echo "🔐 Generating local-only CDK mint seed..."
    if command -v openssl >/dev/null 2>&1; then
        openssl rand -hex 32 > "$SEED_FILE"
    else
        od -An -N32 -tx1 /dev/urandom | tr -d ' \n' > "$SEED_FILE"
    fi
    chmod 600 "$SEED_FILE"
fi

MINT_SEED="$(cat "$SEED_FILE")"

cat > "${WORK_DIR}/config.toml" << EOF
# CDK mint config for integration tests (FakeWallet backend)
[info]
url = "http://127.0.0.1:${PORT}"
listen_host = "127.0.0.1"
listen_port = ${PORT}
enable_info_page = true
seed = "${MINT_SEED}"

[mint_info]
name = "CDK Test Mint"
description = "Integration-test mint with FakeWallet backend"

[database]
engine = "sqlite"

[[ln]]
ln_backend = "fakewallet"
unit = "sat"
min_mint = 1
max_mint = 500000
min_melt = 1
max_melt = 500000

[[ln]]
ln_backend = "fakewallet"
unit = "usd"
min_mint = 1
max_mint = 100000
min_melt = 1
max_melt = 100000

[onchain]
onchain_backend = "fakewallet"
min_mint = 1
max_mint = 500000
min_melt = 1
max_melt = 500000

[fake_wallet]
supported_units = ["sat", "usd"]
fee_percent = 0.0
reserve_fee_min = 0
min_delay_time = 0
max_delay_time = 1
EOF

echo "✅ Config written to ${WORK_DIR}/config.toml"
echo "🚀 Start with: ./CI/start-cdk.sh"
