#!/bin/bash
# Each shard has its own runner, simulator, and mints. Keep execution serial
# inside the runner: competing simulator clones previously caused AX timeouts.
set -euo pipefail

case "${1:-}" in
  unit)
    TEST_ARGUMENTS=(-only-testing:CashuWalletTests)
    ;;
  ui-lifecycle)
    TEST_ARGUMENTS=(-only-testing:CashuWalletUITests/WalletLifecycleUITests)
    ;;
  ui-settings)
    TEST_ARGUMENTS=(-only-testing:CashuWalletUITests/SettingsUITests)
    ;;
  ui-other)
    # Include every other class, including future UI tests, automatically.
    TEST_ARGUMENTS=(-only-testing:CashuWalletUITests
      -skip-testing:CashuWalletUITests/WalletLifecycleUITests
      -skip-testing:CashuWalletUITests/SettingsUITests)
    ;;
  *) echo "Unknown iOS test shard: ${1:-missing}" >&2; exit 1 ;;
esac

if [ "$1" != unit ]; then
  TEST_ARGUMENTS+=(-test-timeouts-enabled YES
    -default-test-execution-time-allowance 90
    -maximum-test-execution-time-allowance 360)
fi

shopt -s nullglob
TEST_RUNS=("$RUNNER_TEMP"/Products/*.xctestrun)
if [ "${#TEST_RUNS[@]}" -ne 1 ]; then
  echo "Expected exactly one compiled xctestrun file" >&2
  exit 1
fi
xcrun simctl bootstatus "$SIMULATOR_UDID" -b > "$RUNNER_TEMP/simulator-boot.log" 2>&1
xcodebuild test-without-building \
  -xctestrun "${TEST_RUNS[0]}" \
  -destination "platform=iOS Simulator,id=$SIMULATOR_UDID" \
  "${TEST_ARGUMENTS[@]}" \
  -parallel-testing-enabled NO \
  -resultBundlePath "$RUNNER_TEMP/$1.xcresult" \
  2>&1 | tee ios-test-output.log
