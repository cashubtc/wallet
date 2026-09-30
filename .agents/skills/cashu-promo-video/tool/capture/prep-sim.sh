#!/bin/zsh
# Erase the dedicated promo simulator back to factory state (Apple's empty
# demo Messages threads, no wallet, no keychain) and re-apply the settings
# every take assumes: en_US 12-hour clock (9:41, not 09:41).
set -euo pipefail
source "${0:A:h}/env.sh"
xcrun simctl shutdown "$UDID" 2>/dev/null || true
xcrun simctl erase "$UDID"
xcrun simctl boot "$UDID"
xcrun simctl bootstatus "$UDID" -b > /dev/null
xcrun simctl spawn "$UDID" defaults write -g AppleLocale en_US
xcrun simctl spawn "$UDID" defaults write -g AppleLanguages -array en
xcrun simctl spawn "$UDID" defaults write -g AppleICUForce24HourTime -bool false
# Locale changes need a reboot to reach SpringBoard's clock.
xcrun simctl shutdown "$UDID"
xcrun simctl boot "$UDID"
xcrun simctl bootstatus "$UDID" -b > /dev/null
xcrun simctl status_bar "$UDID" override --time 9:41 --batteryState discharging \
  --batteryLevel 100 --wifiBars 3 --cellularBars 4 --dataNetwork wifi
echo "prepared $UDID"
