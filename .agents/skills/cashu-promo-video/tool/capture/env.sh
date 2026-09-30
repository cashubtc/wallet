# Shared capture settings, sourced by every capture script (zsh).
#
# The simulator is a dedicated one (default name "Cashu Promo", an iPhone 17
# Pro; capture/sim.sh makes it). It's always targeted by UDID, never `booted`,
# because other work often keeps its own simulators booted.
#
# Override any of these in the environment:
#   SIM_NAME          simulator name to resolve              (Cashu Promo)
#   UDID              skip the name lookup
#   CAPTURE_WORKTREE  throwaway app worktree the takes build from
#                     (<repo>/../<repo name>-promo-capture)
ROOT="${0:A:h:h}"
REPO_ROOT="$(git -C "$ROOT" rev-parse --show-toplevel)"
SIM_NAME="${SIM_NAME:-Cashu Promo}"
# Scripts that never touch the simulator set NEED_SIM=0 before sourcing.
if [[ "${NEED_SIM:-1}" == 1 && -z "${UDID:-}" ]]; then
  UDID="$(xcrun simctl list devices -j | python3 -c '
import json, sys
name = sys.argv[1]
for devices in json.load(sys.stdin)["devices"].values():
    for d in devices:
        if d["name"] == name and d.get("isAvailable", True):
            print(d["udid"]); sys.exit(0)
' "$SIM_NAME")"
fi
if [[ "${NEED_SIM:-1}" == 1 && -z "${UDID:-}" ]]; then
  print -u2 "No simulator named \"$SIM_NAME\". Create it with capture/sim.sh (or set SIM_NAME / UDID)."
  exit 1
fi
CAPTURE_WORKTREE="${CAPTURE_WORKTREE:-${REPO_ROOT:h}/${REPO_ROOT:t}-promo-capture}"
DERIVED="$ROOT/DerivedData"
BUNDLE=com.cashu.me
APP="$DERIVED/Build/Products/Release-iphonesimulator/CashuWallet.app"
TAKES="$ROOT/takes"
