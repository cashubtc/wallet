#!/bin/zsh
# The films' local FakeWallet mint ("Local test mint", 127.0.0.1:3340), run
# from mint/config.toml with the repository's CI build of cdk-mintd
# (CI/setup-cdk.sh fetches and verifies it on first use).
#
#   capture/mint.sh start|stop|status
#
# State lives in mint/work/ (ignored): delete it for a mint with no history.
set -euo pipefail
ROOT="${0:A:h:h}"
REPO_ROOT="$(git -C "$ROOT" rev-parse --show-toplevel)"
MINTD="${CDK_MINTD:-$REPO_ROOT/CI/.cdk-bin/cdk-mintd}"
WORK="$ROOT/mint/work"
PIDFILE="$WORK/mint.pid"
URL="http://127.0.0.1:3340/v1/info"

running() { [[ -f "$PIDFILE" ]] && kill -0 "$(<"$PIDFILE")" 2>/dev/null }

case "${1:-status}" in
  start)
    if running; then print "mint already running (pid $(<"$PIDFILE"))"; exit 0; fi
    if curl -s -m 2 "$URL" > /dev/null; then
      print -u2 "Something else is already serving 127.0.0.1:3340. Stop it first."
      exit 1
    fi
    if [[ ! -x "$MINTD" ]]; then
      print "cdk-mintd missing; fetching it with CI/setup-cdk.sh"
      "$REPO_ROOT/CI/setup-cdk.sh" > /dev/null
    fi
    mkdir -p "$WORK"
    cp "$ROOT/mint/config.toml" "$WORK/config.toml"
    "$MINTD" --work-dir "$WORK" > "$WORK/mint.log" 2>&1 &
    print $! > "$PIDFILE"
    for _ in {1..50}; do
      curl -s -m 1 "$URL" > /dev/null && { print "mint up → http://127.0.0.1:3340 (pid $(<"$PIDFILE"))"; exit 0; }
      sleep 0.2
    done
    print -u2 "mint didn't answer; see $WORK/mint.log"
    exit 1
    ;;
  stop)
    if running; then kill "$(<"$PIDFILE")"; rm -f "$PIDFILE"; print "mint stopped"; else print "mint not running"; fi
    ;;
  status)
    if curl -s -m 2 "$URL" | grep -q '"Local test mint"'; then print "mint up → http://127.0.0.1:3340"; else print "mint down"; exit 1; fi
    ;;
  *)
    print -u2 "usage: capture/mint.sh start|stop|status"
    exit 2
    ;;
esac
