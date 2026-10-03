#!/bin/zsh
# Builds the app + UI-test runner from the throwaway capture worktree
# (capture/worktree.sh). Simulator builds sign ad hoc ("-") so the keychain
# entitlement is present: CODE_SIGNING_ALLOWED=NO strips it and the wallet
# can't start (-34018).
set -euo pipefail
source "${0:A:h}/env.sh"

if [[ ! -f "$CAPTURE_WORKTREE/ios/CashuWallet.xcodeproj/project.pbxproj" ]]; then
  print -u2 "No capture worktree at $CAPTURE_WORKTREE. Run capture/worktree.sh first."
  exit 1
fi
if ! cmp -s "$ROOT/capture/PromoTakes.swift" "$CAPTURE_WORKTREE/ios/CashuWalletUITests/MainTabUITests.swift"; then
  print -u2 "The worktree's capture tests are stale. Run capture/worktree.sh to reinstall PromoTakes.swift."
  exit 1
fi

cd "$CAPTURE_WORKTREE/ios"
xcodebuild build-for-testing \
  -project CashuWallet.xcodeproj -scheme CashuWallet -configuration Release \
  -destination "platform=iOS Simulator,id=$UDID" \
  -derivedDataPath "$DERIVED" ENABLE_TESTABILITY=YES \
  CODE_SIGN_IDENTITY=- CODE_SIGN_STYLE=Manual DEVELOPMENT_TEAM= PROVISIONING_PROFILE_SPECIFIER= \
  > "$ROOT/build-for-testing.log" 2>&1 || { grep -E "error:" "$ROOT/build-for-testing.log" | head; exit 1; }
echo "built → $(ls "$DERIVED"/Build/Products/*.xctestrun)"
