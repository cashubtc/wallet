#!/bin/zsh
# Creates (or refreshes to the repository's HEAD) the throwaway app worktree
# the takes build from, and installs the capture tests into it.
#
# PromoTakes.swift is copied over CashuWalletUITests/MainTabUITests.swift:
# that file is already registered in the Xcode project, so the capture tests
# build without touching the pbxproj. The worktree is detached and never
# committed from; delete it with `git worktree remove --force <path>`.
#
#   capture/worktree.sh       honours CAPTURE_WORKTREE (see env.sh)
set -euo pipefail
NEED_SIM=0
source "${0:A:h}/env.sh"

head="$(git -C "$REPO_ROOT" rev-parse HEAD)"
if [[ -d "$CAPTURE_WORKTREE" ]]; then
  git -C "$CAPTURE_WORKTREE" checkout -q --force --detach "$head"
else
  git -C "$REPO_ROOT" worktree add -q --detach "$CAPTURE_WORKTREE" "$head"
fi
cp "$ROOT/capture/PromoTakes.swift" "$CAPTURE_WORKTREE/ios/CashuWalletUITests/MainTabUITests.swift"
print "capture worktree → $CAPTURE_WORKTREE @ ${head[1,8]} (PromoTakes installed)"
