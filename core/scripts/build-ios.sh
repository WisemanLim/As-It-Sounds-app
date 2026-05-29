#!/usr/bin/env bash
# Build ais-ffi for iOS device + simulator and assemble an XCFramework.
# Output: core/target/AisCoreFFI.xcframework

set -euo pipefail
cd "$(dirname "$0")/.."

export PATH="${HOME}/.cargo/bin:${PATH}"

PROFILE="${PROFILE:-release}"
LIBNAME="libais_ffi.a"
FFI_NAME="AisCoreFFI"

TARGETS=(
    aarch64-apple-ios
    aarch64-apple-ios-sim
    x86_64-apple-ios
)

echo "==> Ensuring iOS Rust targets are installed"
for t in "${TARGETS[@]}"; do
    rustup target add "$t"
done

echo "==> Building ais-ffi for each iOS target"
for t in "${TARGETS[@]}"; do
    cargo build --package ais-ffi --target "$t" --profile "$PROFILE"
done

echo "==> Generating Swift bindings"
cargo run --release --features cli --bin uniffi-bindgen -- \
    generate crates/ais-ffi/src/ais_ffi.udl \
    --language swift \
    --out-dir bindings/swift \
    --config crates/ais-ffi/uniffi.toml

# UniFFI emits AisCoreFFI.{swift,h,modulemap}. Xcode auto-picks `module.modulemap`,
# so rename it for the framework search path to work without -fmodule-map-file.
mv -f "bindings/swift/${FFI_NAME}.modulemap" "bindings/swift/module.modulemap"

echo "==> Combining simulator slices (arm64 + x86_64) with lipo"
mkdir -p target/lipo-ios-sim
lipo -create \
    "target/aarch64-apple-ios-sim/${PROFILE}/${LIBNAME}" \
    "target/x86_64-apple-ios/${PROFILE}/${LIBNAME}" \
    -output "target/lipo-ios-sim/${LIBNAME}"

echo "==> Assembling XCFramework"
rm -rf "target/${FFI_NAME}.xcframework"
xcodebuild -create-xcframework \
    -library "target/aarch64-apple-ios/${PROFILE}/${LIBNAME}" \
    -headers bindings/swift \
    -library "target/lipo-ios-sim/${LIBNAME}" \
    -headers bindings/swift \
    -output "target/${FFI_NAME}.xcframework"

echo
echo "✓ Output: $(pwd)/target/${FFI_NAME}.xcframework"
echo "✓ Swift binding: bindings/swift/${FFI_NAME}.swift"
echo
echo "Next: drag both into the Xcode project (or run xcodegen)."
