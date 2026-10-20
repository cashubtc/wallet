#!/bin/zsh
# Runs one PromoTakes test, optionally recording the simulator.
#
#   capture/take.sh <testName> [--record] [--fresh] [KEY=VALUE …]
#
# --record  record the simulator (take.mp4) while the test runs.
# --fresh   wipe the wallet first (keychain reset + reinstall). An uninstall
#           alone keeps the seed in the keychain and skips onboarding.
# KEY=VALUE forwarded to the test runner (e.g. DRY=1 for stills).
#
# Env: APPEARANCE (dark), ALLOW_PASTE=1 (pre-allow Cashu's clipboard reads
# after a fresh install; see allow-paste.sh).
#
# Output: takes/<testName>-<appearance>/<stamp>/{take.mp4, take-start.txt,
# *-marks.json, *.png}. Analysis reads "-dark/" in that path as a dark take.
set -euo pipefail
source "${0:A:h}/env.sh"

test="$1"; shift
record=0; fresh=0; extra=()
for a in "$@"; do
  case "$a" in
    --record) record=1 ;;
    --fresh) fresh=1 ;;
    *=*) extra+=("TEST_RUNNER_$a") ;;
  esac
done

stamp=$(date +%Y%m%d-%H%M%S)
out="$TAKES/$test-${APPEARANCE:-dark}/$stamp"
mkdir -p "$out"

# The status bar is part of the frame: 9:41, full, not charging.
xcrun simctl status_bar "$UDID" override --time 9:41 --batteryState discharging \
  --batteryLevel 100 --wifiBars 3 --cellularBars 4 --dataNetwork wifi
xcrun simctl ui "$UDID" appearance "${APPEARANCE:-dark}"

if (( fresh )); then
  xcrun simctl terminate "$UDID" "$BUNDLE" 2>/dev/null || true
  xcrun simctl uninstall "$UDID" "$BUNDLE" 2>/dev/null || true
  xcrun simctl keychain "$UDID" reset
  xcrun simctl install "$UDID" "$APP"
fi
# The Paste button's clipboard read, without the "Allow Paste?" prompt.
if [[ "${ALLOW_PASTE:-0}" == 1 ]]; then "${0:A:h}/allow-paste.sh"; fi

xctestrun=$(ls "$DERIVED"/Build/Products/*.xctestrun | head -1)

rec_pid=""
if (( record )); then
  xcrun simctl io "$UDID" recordVideo --codec=h264 --force "$out/take.mp4" > "$out/record.log" 2>&1 &
  rec_pid=$!
  # PTS 0 is the recorder's first frame; this stamp is its wall-clock anchor
  # (± the recorder's spin-up, refined later from content).
  # PTS 0 is when the recorder logs "Recording started" (1–3 s after it
  # launches): stamp then, not on a fixed sleep.
  for _ in {1..400}; do grep -q "Recording started" "$out/record.log" 2>/dev/null && break; sleep 0.02; done
  python3 -c 'import time; print(repr(time.time()))' > "$out/take-start.txt"
fi

set +e
env "${extra[@]}" TEST_RUNNER_PROMO_OUT="$out" xcodebuild test-without-building \
  -xctestrun "$xctestrun" \
  -destination "platform=iOS Simulator,id=$UDID" \
  -only-testing:"CashuWalletUITests/PromoTakes/$test" \
  -parallel-testing-enabled NO \
  > "$out/xcodebuild.log" 2>&1 &
xc_pid=$!
# After a failure xcodebuild can report the result and then never exit: give
# it 30 s past the suite's last line, then stop it.
while kill -0 "$xc_pid" 2>/dev/null; do
  if grep -qE "Test Suite 'Selected tests' (passed|failed)" "$out/xcodebuild.log" 2>/dev/null; then
    for _ in {1..300}; do kill -0 "$xc_pid" 2>/dev/null || break; sleep 0.1; done
    kill "$xc_pid" 2>/dev/null
    break
  fi
  sleep 0.5
done
wait "$xc_pid" 2>/dev/null
rc=$?
set -e

if [[ -n "$rec_pid" ]]; then
  # Ask the recorder to finish; it can hang, so escalate after 15 s.
  kill -INT "$rec_pid"
  for _ in {1..150}; do kill -0 "$rec_pid" 2>/dev/null || break; sleep 0.1; done
  kill -0 "$rec_pid" 2>/dev/null && { kill -INT "$rec_pid"; sleep 3; kill -TERM "$rec_pid" 2>/dev/null; }
  wait "$rec_pid" 2>/dev/null || true
fi

grep -E "Test Case .* (passed|failed)|error:" "$out/xcodebuild.log" | head -5
echo "take → $out (xcodebuild exit $rc)"
