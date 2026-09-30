#!/bin/zsh
# The chat film's pair ("The message is the money."): a clean Simulator
# (Apple's empty demo Messages threads), a funded wallet (unrecorded) → phone
# A shares 1,000 sat into Kate Bell's thread (recorded) → phone B, a fresh
# wallet, copies that bubble and claims it with the Paste button (recorded).
# The token in the bubble is the one phone B claims.
#
#   APPEARANCE=dark capture/share-session.sh [DRY=1]
set -uo pipefail
source "${0:A:h}/env.sh"
export APPEARANCE="${APPEARANCE:-dark}"
cd "$ROOT"; mkdir -p tokens
capture/prep-sim.sh
capture/take.sh testFund --fresh | tail -1
capture/take.sh testShare --record "$@" | tail -1
# Phone A's copy is on the pasteboard: check it's a token (never print it).
xcrun simctl pbpaste "$UDID" > "tokens/shared-$APPEARANCE.txt"
python3 - "tokens/shared-$APPEARANCE.txt" <<'PY' || exit 1
import os, sys
p = sys.argv[1]; s = open(p).read().strip()
if s.startswith("cashu"): print(f"  token ok ({len(s)} chars)")
else: os.remove(p); print("  pasteboard did not hold a token — discarded"); sys.exit(1)
PY
# Phone B copies the bubble in Messages itself (testClaim), so iOS's banner
# reads "Cashu pasted from Messages". Leave Messages running: the Simulator
# keeps sent messages in memory only, so a terminate empties the thread.
# Empty the clipboard first: if that copy fails, Paste finds nothing rather
# than phone A's own copy.
printf '' | xcrun simctl pbcopy "$UDID"
ALLOW_PASTE=1 capture/take.sh testClaim --fresh --record "$@" | tail -1
rm -f "tokens/shared-$APPEARANCE.txt"
print "SHARE-DONE $APPEARANCE"
