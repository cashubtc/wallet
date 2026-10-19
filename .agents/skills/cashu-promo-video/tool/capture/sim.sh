#!/bin/zsh
# Finds the dedicated promo simulator, or creates it: an iPhone 17 Pro (the
# renderer's phone geometry is 402 × 874 pt at 3×) on the newest installed
# iOS runtime. Prints its UDID.
#
#   capture/sim.sh            SIM_NAME="Cashu Promo" by default
set -euo pipefail
SIM_NAME="${SIM_NAME:-Cashu Promo}"
DEVICE_TYPE="${DEVICE_TYPE:-iPhone 17 Pro}"

found="$(xcrun simctl list devices -j | python3 -c '
import json, sys
name = sys.argv[1]
for devices in json.load(sys.stdin)["devices"].values():
    for d in devices:
        if d["name"] == name and d.get("isAvailable", True):
            print(d["udid"]); sys.exit(0)
' "$SIM_NAME")"
if [[ -n "$found" ]]; then
  print "$found"
  exit 0
fi

runtime="$(xcrun simctl list runtimes -j | python3 -c '
import json, sys
rs = [r for r in json.load(sys.stdin)["runtimes"] if r["platform"] == "iOS" and r.get("isAvailable", True)]
rs.sort(key=lambda r: [int(p) for p in r["version"].split(".")])
print(rs[-1]["identifier"] if rs else "")
')"
if [[ -z "$runtime" ]]; then
  print -u2 "No iOS simulator runtime installed (Xcode → Settings → Components)."
  exit 1
fi
xcrun simctl create "$SIM_NAME" "$DEVICE_TYPE" "$runtime"
