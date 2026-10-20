#!/bin/zsh
# The send film, one appearance: a clean Simulator (Apple's demo Messages
# threads), a funded wallet (unrecorded), then the recorded take: 2,500 sat of
# ecash → Copy → paste into John Appleseed's demo thread → send.
#
# Not a group chat: the Simulator can only send in its two demo threads; any
# new thread, group or not, comes up as SMS, which it can't send.
#
#   APPEARANCE=dark capture/send-session.sh [DRY=1]
set -uo pipefail
source "${0:A:h}/env.sh"
export APPEARANCE="${APPEARANCE:-dark}"
cd "$ROOT"
capture/prep-sim.sh
capture/take.sh testFund --fresh | tail -1
capture/take.sh testSendChat --record "$@" | tail -1
print "SEND-DONE $APPEARANCE"
