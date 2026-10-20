#!/bin/zsh
# The restore film, one appearance: a funded wallet (unrecorded), then the
# recorded take: Settings → reveal and copy the recovery phrase (Face ID) →
# Delete Wallet → Restore Wallet → paste the phrase → find the mint → the
# balance comes back.
#
#   APPEARANCE=dark capture/restore-session.sh [DRY=1]
set -uo pipefail
source "${0:A:h}/env.sh"
export APPEARANCE="${APPEARANCE:-dark}"
cd "$ROOT"
capture/take.sh testFund --fresh | tail -1

# Reveal and Copy each ask the device owner. Enroll a Simulator face, and
# match it whenever the take asks (it drops a faceid-request file).
xcrun simctl spawn "$UDID" notifyutil -s com.apple.BiometricKit.enrollmentChanged '1'
xcrun simctl spawn "$UDID" notifyutil -p com.apple.BiometricKit.enrollmentChanged
( while true; do
    for f in takes/testRestoreFlow-$APPEARANCE/*/faceid-request(N); do
      rm -f "$f"; sleep 0.9
      xcrun simctl spawn "$UDID" notifyutil -p com.apple.BiometricKit_Sim.pearl.match
    done
    sleep 0.1
  done ) &
watcher=$!
capture/take.sh testRestoreFlow --record "$@" | tail -1
kill $watcher 2>/dev/null
print "RESTORE-DONE $APPEARANCE"
