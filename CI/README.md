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

## Prebuilt Binaries (Fast CI!)

CDK setup uses release binaries instead of compiling Rust in CI:

- **CDK**: Downloads and verifies `cdk-mintd 0.17.3-rc.0`, including the native Apple Silicon binary
- **Nutshell**: Installs via `pip install cashu` from PyPI (exposes the `mint` binary)

## Quick Start

### Local Testing

Run the full integration test suite locally:

```bash
# Set up and start mints
./CI/setup-nutshell.sh
./CI/setup-cdk.sh
./CI/start-nutshell.sh
./CI/start-cdk.sh

# Run tests
NUTSHELL_MINT_URL=http://localhost:3338 \
CDK_MINT_URL=http://localhost:3339 \
xcodebuild test \
  -project ios/CashuWallet.xcodeproj \
  -scheme CashuWallet \
  -destination 'platform=iOS Simulator,name=iPhone 17' \
  -only-testing:CashuWalletTests

# Stop mints when done
./CI/stop-nutshell.sh
./CI/stop-cdk.sh
```

### Automated CI Flow

```bash
# Setup (downloads binaries, ~30 seconds)
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

## Payment safety pack

The [payment coverage review and runbook](payment-tests/README.md) describes the
new scenario-isolated fault proxy, controlled fee/no-fee mints, native payment
suites, live UI journeys, required-test manifest, known regression, and prioritized
remaining gaps. Both platform workflows run core payment cases on PRs and the
expanded request/NWC/unit matrix nightly.

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
| **BOLT12 mint** (CDK only) | Quote via `/v1/mint/quote/bolt12` → offer auto-paid → mint → balance updates |
| **BOLT12 melt** (CDK only) | Amountless offer melt via `/v1/melt/quote/bolt12` → auto-settled → balance reduces |
| **On-chain mint** (CDK only) | Quote via `/v1/mint/quote/onchain` → regtest deposit auto-confirmed → mint |
| **On-chain melt** (CDK only) | Fee options via `/v1/melt/quote/onchain` → select → auto-settled withdrawal |

BOLT12 and on-chain flows run against the CDK mint only — Nutshell's FakeWallet
backend is bolt11-only. The CDK FakeWallet registers bolt11, bolt12, and onchain
payment methods automatically; no extra mint configuration is required.

## File Structure

```
CI/
├── setup-nutshell.sh      # pip install cashu (downloads wheels)
├── start-nutshell.sh      # Launch Nutshell with FakeWallet on port 3338
├── stop-nutshell.sh       # Stop Nutshell mint
│
├── setup-cdk.sh           # Download cdk-mintd binary from GitHub releases
├── start-cdk.sh           # Launch CDK mint with FakeWallet on port 3339
├── stop-cdk.sh            # Stop CDK mint
│
├── cleanup.sh             # Cleanup all artifacts
│
└── IntegrationTests/      # Swift package with integration tests
    ├── Package.swift
    └── Tests/
        ├── IntegrationTestBase.swift
        └── NutshellIntegrationTests.swift # Shared scenarios plus both mint suites
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
MINT_DATABASE=CI/.nutshell-workdir
MINT_BACKEND_BOLT11_SAT=FakeWallet
MINT_INPUT_FEE_PPK=0
```

### CDK Config

Written to `CI/.cdk-workdir/config.toml` by `setup-cdk.sh`:

```toml
[info]
url = "http://127.0.0.1:3339"
listen_host = "127.0.0.1"
listen_port = 3339
mnemonic = "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about"
input_fee_ppk = 0

[database]
engine = "sqlite"

[ln]
ln_backend = "fakewallet"
unit = "sat"

[fake_wallet]
fee_percent = 0.0
reserve_fee_min = 0
min_delay_time = 0
max_delay_time = 0
```

## GitHub Actions Workflow

The iOS workflow (`.github/workflows/ios-tests.yml`) runs for relevant pull
requests and pushes to main/develop. Native UI coverage is described in the
[wallet UI matrix](../docs/testing/wallet-ui-coverage.md). The workflow uses
`./CI/setup-cdk.sh 3339 android` to enable the shared SAT/USD profile needed by
the multi-currency UI journey; the historical profile name is used by both apps.

1. Builds the app and both test bundles once on macOS with Xcode 26.5.
2. Archives `Build/Products`, including the `.xctestrun` file, using tar to
   preserve executable permissions and framework symlinks.
3. Runs four jobs on separate macOS runners using those exact products:
   unit/mint integration, lifecycle UI, settings UI, and all remaining UI tests.
   Each job owns one simulator and its own local mint/fixture processes.
4. Exports test results and timing summaries on every run, including successful
   runs. Failed jobs also upload full `.xcresult` bundles and diagnostic logs.
5. Aggregates exported JSON on Linux, requires every job to succeed, and checks
   the unchanged payment coverage manifest across all four reports. The final
   check keeps the existing `iOS Unit, Mint Integration & UI Tests` name.

`CI/run-ios-test-shard.sh` defines the partitions. The remaining-UI partition
includes the entire UI target and excludes only the two explicitly assigned
classes, so new UI classes run automatically. The groups were chosen from a
successful baseline: lifecycle took about 11 minutes, settings 9 minutes, and
remaining UI tests 12 minutes. Unit/integration tests took about 3 minutes.
Tests run once and serially within each runner; matrix fail-fast is disabled so
one failure does not cancel the other groups. A test step has a 25-minute bound
inside a 35-minute job, leaving time for cleanup and diagnostics. Existing
per-test UI allowances remain in place, including three minutes for lifecycle
journeys. This reduces elapsed time by using more macOS runner capacity; queue
and artifact-transfer times must be included when comparing runs.

Swift package downloads and mint runtimes use separate caches. Compiled products
are shared only within a workflow run, not restored from an older revision.
Settings tests configure their first launch before starting the app. Live
lifecycle journeys retain onboarding and real mint provisioning: the existing
seeded-mint helper is a UI placeholder, not a funded integration fixture.
Fresh test launches clear App Lock; persistence relaunches keep it. Disabled
animations also hold animated QR codes on their initial frame so accessibility
queries can settle during clipboard journeys.

For timing comparisons, inspect the build/test job durations, each test job's
summary, and the `ios-reports-*` artifacts (test trees plus summary JSON). Compare
both total elapsed time and runner minutes, including cold-cache runs. Full UI
coverage remains enabled for PRs; the existing payment `pr`/`full` tiers are
unchanged.

Validate the shard selection/failure propagation and report aggregation locally:

```bash
python3 -m unittest discover -s CI/tests -v
python3 -m unittest discover -s CI/payment-tests -p test_coverage.py -v
```

## Manual Testing

You can also test manually against the live mints:

```bash
# Start mints
./CI/start-nutshell.sh
./CI/start-cdk.sh

# Run iOS app in simulator
open ios/CashuWallet.xcodeproj

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

### Interrupted receipt recovery

`python3 CI/receipt-recovery-proxy.py` starts a loopback-only proxy on port 3340
in front of the Nutshell test mint on port 3338. The iOS `ReceiveRecoveryTests`
and Android `ReceiptRecoveryInstrumentedTest` exercise a completed receipt before
app tracking, an accepted swap whose response is interrupted, an offline database
reopen, and repeated CDK reconciliation. They never redeem the original token a
second time. Run these tests serially because they share the proxy's fault state.
The native CI jobs start and stop the proxy automatically. Android uses
`cashu.nativeWalletLocalMintIntegration=true` and
`cashu.receiptRecoveryMintUrl=http://10.0.2.2:3340` on hosted emulators; use an ADB
reverse mapping and the emulator loopback address for a local run.
