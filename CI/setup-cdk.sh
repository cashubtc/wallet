#!/bin/bash
set -euo pipefail

# setup-cdk.sh — Download and configure the prebuilt cdk-mintd release.
# Usage: ./CI/setup-cdk.sh [port] [default|android] [input-fee-ppk]

PORT=${1:-3339}
PROFILE=${2:-default}
INPUT_FEE_PPK=${3:-0}
FAKE_DELAY_SECONDS=${CDK_FAKE_DELAY_SECONDS:-0}
if [[ ! "$INPUT_FEE_PPK" =~ ^[0-9]+$ ]]; then
    echo "❌ Input fee ppk must be a nonnegative integer" >&2
    exit 1
fi
if [[ ! "$FAKE_DELAY_SECONDS" =~ ^[0-9]+$ ]]; then
    echo "❌ Fake wallet delay must be a nonnegative integer" >&2
    exit 1
fi
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
CDK_VERSION="0.18.2"
BIN_DIR="${SCRIPT_DIR}/.cdk-bin"
WORK_DIR="${CDK_TEST_WORK_DIR:-${SCRIPT_DIR}/.cdk-workdir}"
MINTD_BIN="${BIN_DIR}/cdk-mintd"

case "$PROFILE" in
    default)
        MINT_NAME="CDK CI Mint"
        LN_CONFIG='[ln]
ln_backend = "fakewallet"
unit = "sat"
min_mint = 1
max_mint = 500000
min_melt = 1
max_melt = 500000'
        SUPPORTED_UNITS='["sat"]'
        ;;
    android)
        MINT_NAME="CDK Test Mint"
        LN_CONFIG='[[ln]]
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
max_melt = 100000'
        SUPPORTED_UNITS='["sat", "usd"]'
        ;;
    *)
        echo "❌ Unknown CDK test profile: $PROFILE (expected default or android)"
        exit 1
        ;;
esac

echo "🔧 Setting up CDK mint (v${CDK_VERSION}) on port ${PORT} with ${PROFILE} profile..."

OS="$(uname -s | tr '[:upper:]' '[:lower:]')"
ARCH="$(uname -m)"

case "${OS}-${ARCH}" in
    darwin-arm64|darwin-aarch64)
        ASSET_NAME="cdk-mintd-${CDK_VERSION}-aarch64-apple-darwin"
        EXPECTED_SHA256="3e93c4735c265c8079acbb62c2d8f111cade1d737fa1e797ed846c3a656f78f8"
        ;;
    linux-arm64|linux-aarch64)
        ASSET_NAME="cdk-mintd-${CDK_VERSION}-aarch64"
        EXPECTED_SHA256="8c94a9eb110a5ad1bc2d78bd9af94866f9590564d25e64fe56596659a4d44b8c"
        ;;
    linux-x86_64)
        ASSET_NAME="cdk-mintd-${CDK_VERSION}-x86_64"
        EXPECTED_SHA256="0d33d68695f50f606df385bbb439862011fef73462df32d586ad014b8900c54f"
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
INIT_MODE=--new-mint
if [ -s "${WORK_DIR}/cdk-mintd.sqlite" ]; then
    INIT_MODE=--existing-mint
fi

cat > "${WORK_DIR}/config.toml" << EOF
# CDK mint config for integration tests. Never use this seed in production.
[info]
url = "http://127.0.0.1:${PORT}"
listen_host = "127.0.0.1"
listen_port = ${PORT}
mnemonic = "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about"
input_fee_ppk = ${INPUT_FEE_PPK}

[info.logging]
output = "stderr"
console_level = "info"

[mint_info]
name = "${MINT_NAME}"
description = "Local CDK integration-test mint"

[database]
engine = "sqlite"

${LN_CONFIG}

[fake_wallet]
supported_units = ${SUPPORTED_UNITS}
fee_percent = 0.0
reserve_fee_min = 0
custom_payment_methods = []
min_delay_time = ${FAKE_DELAY_SECONDS}
max_delay_time = ${FAKE_DELAY_SECONDS}
EOF

# Stable CDK 0.18 stores configuration in SQLite. Use the release's own
# migration so secret references and payment_backend fields stay canonical.
IMPORT_FILE="${WORK_DIR}/config-import.toml"
"$MINTD_BIN" config migrate --file "${WORK_DIR}/config.toml" --output "$IMPORT_FILE" --force
if "$MINTD_BIN" --work-dir "$WORK_DIR" config show > /dev/null 2>&1; then
    "$MINTD_BIN" --work-dir "$WORK_DIR" config apply --file "$IMPORT_FILE"
else
    "$MINTD_BIN" --work-dir "$WORK_DIR" config init "$INIT_MODE" --file "$IMPORT_FILE"
fi

echo "✅ Config written to ${WORK_DIR}/config.toml"
echo "🚀 Start with: ./CI/start-cdk.sh"
