#!/usr/bin/env bash
# Programmatically grab store-quality screenshots from running emulator/sim.
# Run after both apps are installed + a sample transcription has been done.
#
# Output: assets/screenshots/{ios,android}/<screen>.png

set -euo pipefail
cd "$(dirname "$0")/.."

OUT_IOS="assets/screenshots/ios"
OUT_AND="assets/screenshots/android"
mkdir -p "$OUT_IOS" "$OUT_AND"

ADB="$HOME/Library/Android/sdk/platform-tools/adb"

shoot_ios() {
    local name="$1"
    echo "==> iOS: $name"
    xcrun simctl io "iPhone 17 Pro" screenshot "$OUT_IOS/$name.png" >/dev/null
    sleep 1
}

shoot_android() {
    local name="$1"
    echo "==> Android: $name"
    "$ADB" shell screencap -p > "$OUT_AND/$name.png"
    sleep 1
}

echo "Make sure both apps are foregrounded on each screen, then press Enter."
read -r _ < /dev/tty

for screen in transcribe library translit settings; do
    echo
    echo "Navigate both devices to: $screen"
    read -r _ < /dev/tty
    shoot_ios "$screen" || true
    shoot_android "$screen" || true
done

echo
echo "Done. Crop/annotate before uploading to App Store Connect / Play Console."
ls -la "$OUT_IOS" "$OUT_AND"
