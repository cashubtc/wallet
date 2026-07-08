# Integration Test Infrastructure

This directory contains the complete CI infrastructure for running end-to-end integration tests against real Cashu mint implementations.

## Architecture

```
┌─────────────────────────────────────────────────────────┐
│  CI GitHub Actions Runner (macOS)                        │
├─────────────────────────────────────────────────────────┤
│                                                         │
│  ┌──────────────────┐         ┌─────────────────────┐   │
│  │ Nutshell Mint    │◄───────►│  iOS Simulator      │   │
│  │ (FakeWallet)     │  HTTP   │  Running CashuWallet│   │
│  │ localhost:3338   │         │                     │   │
│  └──────────────────┘         └─────────────────────┘   │
│           ▲                                              │
│           │                                              │
│  ┌────────┴─────────┐                                    │
│  │ CDK Mint         │                                    │
│  │ (FakeWallet)     │                                    │
│  │ localhost:3339   │                                    │
│  └──────────────────┘                                    │
│                                                         │
│  Both mints use FakeWallet backend:                     │
│  - Auto-accepts Lightning invoices                      │
│  - Auto-pays Lightning invoices                         │
│  - No real LN node required                             │
└─────────────────────────────────────────────────────────┘
```

## Test Mint Setup

The scripts keep CI and local integration setup reproducible:

- **CDK**: downloads `cdk-mintd-0.17.1` on Linux, and builds it from the tagged source with Cargo on macOS because that release only ships Linux binaries.
- **Nutshell**: installs via `pip install cashu` from PyPI with Python 3.10-3.12. Set `PYTHON=/path/to/python` if your default `python3` is newer than the supported range.

## Quick Start

### Local Testing

Run the full integration test suite locally:

```bash
# Start mints
./CI/start-nutshell.sh
./CI/start-cdk.sh

# Run tests
xcodebuild test \
  -project CashuWallet.xcodeproj \
  -scheme CashuWalletIntegration \
  -destination 'platform=iOS Simulator,name=iPhone 15' \
  NUTSHELL_MINT_URL=http://localhost:3338 \
  CDK_MINT_URL=http://localhost:3339

# Stop mints when done
./CI/stop-nutshell.sh
./CI/stop-cdk.sh
```

### Automated CI Flow

```bash
# Setup (downloads or builds the local mint binaries)
./CI/setup-nutshell.sh
./CI/setup-cdk.sh

# Start mints
./CI/start-nutshell.sh
./CI/start-cdk.sh

# Run your tests...
# (tests run here)

# Cleanup
./CI/stop-nutshell.sh
./CI/stop-cdk.sh
```

## Test Coverage

The integration tests verify **CDK-Swift ↔ real mint** compatibility:

| Test | What it verifies |
|---|---|
| **Mint via Lightning** | App creates invoice → FakeWallet auto-pays → balance updates |
| **Pay Lightning invoice** | App melts ecash → FakeWallet auto-accepts → balance reduces |
| **Add mint** | App discovers mint via `/v1/info` |
| **Fetch keysets** | App fetches keysets via `/v1/keys` |
| **Mint quote** | App quotes minting via `/v1/mint/quote/bolt11` |
| **Mint tokens** | App mints via `/v1/mint/bolt11` |
| **Melt quote** | App quotes melting via `/v1/melt/quote/bolt11` |
| **Token round-trip** | Mint 50s → create token → redeem token → balance restored |
| **Multi-mint** | Add both mints → switch between them → query keys for both |

## File Structure

```
CI/
├── setup-nutshell.sh      # pip install cashu (downloads wheels)
├── start-nutshell.sh      # Launch Nutshell with FakeWallet on port 3338
├── stop-nutshell.sh       # Stop Nutshell mint
│
├── setup-cdk.sh           # Prepare cdk-mintd from a release binary or source build
├── start-cdk.sh           # Launch CDK mint with FakeWallet on port 3339
├── stop-cdk.sh            # Stop CDK mint
│
├── cleanup.sh             # Cleanup all artifacts
│
└── IntegrationTests/      # Swift package with integration tests
    ├── Package.swift
    └── Tests/
        ├── IntegrationTestBase.swift
        ├── NutshellIntegrationTests.swift
        └── CDKIntegrationTests.swift
```

## Configuration

Both mints use the **FakeWallet** backend, which means:

- ✅ No real Lightning node needed
- ✅ No real BTC needed
- ✅ Invoices are auto-paid and auto-accepted
- ✅ Perfect for CI testing

### Nutshell Config

Set via environment variables in `start-nutshell.sh`:

```bash
MINT_LISTEN_PORT=3338
MINT_DATABASE=memory
MINT_LIGHTNING_BACKEND=FakeWallet
FAKE_WALLET_SECRET="toTheMoon"
```

### CDK Config

Written to `CI/.cdk-workdir/config.toml` by `setup-cdk.sh`:

```toml
[lightning_backend]
name = "fakewallet"

[fakewallet]
seed = "0000000000000000000000000000000000000000000000000000000000000001"

[database]
engine = "sqlite"
```

## GitHub Actions Workflow

The workflow (`.github/workflows/integration-tests.yml`) runs on every push/PR to main:

1. Checks out code
2. Setups Python 3.11 and Xcode
3. Installs Nutshell via pip with a supported Python runtime
4. Prepares CDK from a release binary on Linux or a Cargo source build on macOS
5. Starts both mints
6. Runs integration tests against both mints
7. Uploads test results on failure

**Expected CI time:** Linux runners should stay close to the previous fast path; macOS CDK setup depends on whether the Cargo build output is already cached.

## Manual Testing

You can also test manually against the live mints:

```bash
# Start mints
./CI/start-nutshell.sh
./CI/start-cdk.sh

# Run iOS app in simulator
open CashuWallet.xcodeproj

# Add mints in the app:
# Settings → Mints → Add Mint:
# - http://localhost:3338 (Nutshell)
# - http://localhost:3339 (CDK)

# Test operations:
# - Request Lightning payment (should auto-pay via FakeWallet)
# - Send Lightning payment (should auto-accept via FakeWallet)
# - Create Cashu token
# - Redeem Cashu token
# - Add/remove mints
# - Multi-mint balances

# Stop mints when done
./CI/stop-nutshell.sh
./CI/stop-cdk.sh
```

## Troubleshooting

### Port already in use

```bash
lsof -i :3338  # Find what's using the port
kill <PID>      # Kill it
```

### Check mint logs

```bash
tail -f CI/.nutshell.log
tail -f CI/.cdk.log
```

### Test mint connectivity

```bash
curl http://localhost:3338/v1/info | jq
curl http://localhost:3339/v1/info | jq
```

### Reset mint state

```bash
./CI/stop-nutshell.sh
./CI/stop-cdk.sh
rm -rf CI/.nutshell-workdir
rm -rf CI/.cdk-workdir
./CI/start-nutshell.sh
./CI/start-cdk.sh
```

## What We're Really Testing

This isn't just "does the app work" — this tests **CDK-Swift ↔ real Cashu mint** protocol compatibility:

- ✅ Wire protocol (NUT-04/05/06)
- ✅ Token serialization (`cashuA...` format)
- ✅ Mint discovery (`/v1/info`)
- ✅ Keyset exchange (`/v1/keys`)
- ✅ Mint quotes & minting
- ✅ Melt quotes & melting
- ✅ Token creation & redemption
- ✅ Swap operations
- ✅ Fee calculation
- ✅ Multi-mint management

**This catches real integration bugs** that unit tests can't find!
