#!/usr/bin/env bash
# Renders icon.svg / foreground.svg into the platform-native icon slots.
#
# Outputs:
#   iOS    — ios/AsItSounds/Resources/Assets.xcassets/AppIcon.appiconset/Icon-1024.png
#            (single universal 1024×1024 slot, Xcode 14+)
#   Android — android/app/src/main/res/mipmap-{mdpi,hdpi,xhdpi,xxhdpi,xxxhdpi}/
#              ic_launcher.png + ic_launcher_round.png + ic_launcher_foreground.png

set -euo pipefail
cd "$(dirname "$0")/.."

if ! command -v rsvg-convert >/dev/null; then
    echo "ERROR: rsvg-convert not found. Install: brew install librsvg"; exit 1
fi

IOS_ICON_DIR="ios/AsItSounds/Resources/Assets.xcassets/AppIcon.appiconset"
ANDROID_RES="android/app/src/main/res"

echo "==> iOS  1024×1024"
mkdir -p "$IOS_ICON_DIR"
rsvg-convert -w 1024 -h 1024 assets/icon.svg -o "$IOS_ICON_DIR/Icon-1024.png"

# Android adaptive icon: foreground at 5 densities. Parallel arrays avoid
# bash 3.2's broken `declare -A` semantics on stock macOS.
DENS=(mdpi hdpi xhdpi xxhdpi xxxhdpi)
FG_PX=(108 162 216 324 432)
LEGACY_PX=(48 72 96 144 192)

for i in "${!DENS[@]}"; do
    d="${DENS[$i]}"
    fg_px="${FG_PX[$i]}"
    legacy_px="${LEGACY_PX[$i]}"
    out="$ANDROID_RES/mipmap-$d"
    mkdir -p "$out"
    echo "==> Android $d  fg=${fg_px}px  legacy=${legacy_px}px"
    rsvg-convert -w "$fg_px"     -h "$fg_px"     assets/foreground.svg -o "$out/ic_launcher_foreground.png"
    rsvg-convert -w "$legacy_px" -h "$legacy_px" assets/icon.svg       -o "$out/ic_launcher.png"
    rsvg-convert -w "$legacy_px" -h "$legacy_px" assets/icon.svg       -o "$out/ic_launcher_round.png"
done

echo
echo "✓ Done. Adaptive XML stays as drawable references; foreground is now a PNG."
