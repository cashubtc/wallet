#!/bin/zsh
# Sets Cashu's "Paste from Other Apps" to Allow on the promo simulator (the
# Settings → Apps → Cashu switch, kept in TCC as kTCCServicePasteboard). The
# Receive sheet's Paste button reads UIPasteboard in code, which otherwise
# asks "Allow Paste?" for text copied elsewhere. An uninstall clears it, so
# run this after every fresh install (take.sh does, with ALLOW_PASTE=1).
#
#   capture/allow-paste.sh [revoke]
set -euo pipefail
source "${0:A:h}/env.sh"
db="$HOME/Library/Developer/CoreSimulator/Devices/$UDID/data/Library/TCC/TCC.db"
if [[ "${1:-}" == revoke ]]; then
  sqlite3 "$db" "DELETE FROM access WHERE service = 'kTCCServicePasteboard' AND client = '$BUNDLE'"
else
  # auth_value 2 = allowed; auth_reason 3 = user set (in Settings).
  sqlite3 "$db" "INSERT OR REPLACE INTO access (service, client, client_type, auth_value, auth_reason, auth_version, flags)
                 VALUES ('kTCCServicePasteboard', '$BUNDLE', 0, 2, 3, 1, 0)"
fi
# tccd caches decisions; restart it so the next read sees the row.
xcrun simctl spawn "$UDID" launchctl kill SIGKILL system/com.apple.tccd 2>/dev/null || true
echo "paste from other apps: ${1:-allow} ($BUNDLE)"
