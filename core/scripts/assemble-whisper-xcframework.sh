#!/usr/bin/env bash
# Assemble whisper.xcframework from already-built iOS slices.
#
# Upstream's build-xcframework.sh aborts on visionOS (`xros` platform unknown
# to current vtool). We only need iOS device + iOS simulator, so this script
# stitches those slices into a usable xcframework, skipping the upstream lipo
# of all targets.
#
# Pre-requisite: run upstream build-xcframework.sh once (it'll produce
# build-ios-device/ and build-ios-sim/ before failing).

set -euo pipefail
cd "$(dirname "$0")/.."

WHISPER_DIR="third_party/whisper.cpp"
OUT="target/whisper.xcframework"

if [[ ! -d "$WHISPER_DIR/build-ios-device" || ! -d "$WHISPER_DIR/build-ios-sim" ]]; then
    echo "ERROR: expected $WHISPER_DIR/build-ios-{device,sim}/ — run the upstream"
    echo "       build script first: (cd $WHISPER_DIR && ./build-xcframework.sh)"
    exit 1
fi

assemble_slice() {
    local label="$1"        # device or sim
    local config_dir="$2"   # Release-iphoneos or Release-iphonesimulator
    local out_dir="target/whisper-ios-${label}"
    rm -rf "$out_dir"
    mkdir -p "$out_dir/Headers"

    local libs=()
    while IFS= read -r f; do
        libs+=("$f")
    done < <(find "$WHISPER_DIR/build-ios-${label}" \
        -path "*${config_dir}*" -name "*.a")

    if [[ ${#libs[@]} -eq 0 ]]; then
        echo "ERROR: no .a files found for $label"
        exit 1
    fi

    echo "==> Merging ${#libs[@]} archives for $label"
    libtool -static -o "$out_dir/libwhisper.a" "${libs[@]}"

    cp "$WHISPER_DIR/include/whisper.h"              "$out_dir/Headers/"
    cp "$WHISPER_DIR/ggml/include/"*.h               "$out_dir/Headers/"
}

assemble_slice device Release-iphoneos
assemble_slice sim    Release-iphonesimulator

echo "==> Creating xcframework"
rm -rf "$OUT"
xcodebuild -create-xcframework \
    -library target/whisper-ios-device/libwhisper.a \
    -headers target/whisper-ios-device/Headers \
    -library target/whisper-ios-sim/libwhisper.a \
    -headers target/whisper-ios-sim/Headers \
    -output "$OUT"

echo
echo "✓ $(pwd)/$OUT"
ls "$OUT"
