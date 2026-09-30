#!/bin/zsh
# The deliverables: the three dark 1:1 films, each as an H.264 master (.mp4)
# and a ProRes 422 HQ mezzanine (.mov), into out/.
#   message  "The message is the money." (two phones)
#   send     create a token, post it in a chat
#   restore  back up, delete, restore; the balance comes back
# Needs the takes each EDL names (under takes/) and a release build.
set -euo pipefail
cd "${0:A:h:h}"
swift build -c release > /dev/null
mkdir -p out
.build/release/promo render-message edl/message-dark.json 0 16.2 out/message-1x1-dark.mp4 dark out/message-1x1-dark.mov
.build/release/promo render-seq send edl/send-dark.json out/send-1x1-dark.mp4 dark out/send-1x1-dark.mov
.build/release/promo render-seq restore edl/restore-dark.json out/restore-1x1-dark.mp4 dark out/restore-1x1-dark.mov
.build/release/promo qa out/message-1x1-dark.mp4 16.2 message edl/message-dark.json
.build/release/promo qa out/send-1x1-dark.mp4 21.0 send edl/send-dark.json
.build/release/promo qa out/restore-1x1-dark.mp4 36.6 restore edl/restore-dark.json
